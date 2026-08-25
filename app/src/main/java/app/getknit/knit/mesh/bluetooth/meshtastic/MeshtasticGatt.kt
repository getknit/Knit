package app.getknit.knit.mesh.bluetooth.meshtastic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import app.getknit.knit.mesh.bluetooth.BleConnectArbiter
import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.DialResult
import app.getknit.knit.mesh.lora.GattChannel
import app.getknit.knit.mesh.lora.GattEvent
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.MeshtasticGattDialer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The **only** `android.bluetooth.*` importer for the LoRa feature: a [MeshtasticGattDialer] that connects
 * to a Meshtastic board, discovers its service, negotiates the MTU, and hands back an [AndroidGattChannel].
 * Everything protocol-shaped runs pure above it (`mesh/lora/MeshtasticSession`). Mirrors the mesh BLE
 * plane's hard-won idioms: the adapter is a **provider** (re-fetched per dial, never cached, so an adapter
 * off→on cycle doesn't strand us), callbacks land on a dedicated [HandlerThread], and every GATT op carries
 * an explicit timeout with the `settled`-race watchdog so a callback that never comes can't wedge the actor.
 *
 * Device-verified only — there is no host GATT stack, so this class has no unit test; its logic lives behind
 * the pure session/codec, which do.
 */
@SuppressLint("MissingPermission")
internal class MeshtasticGatt(
    context: Context,
    private val arbiter: BleConnectArbiter,
) : MeshtasticGattDialer {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter // provider, not a cached handle

    private val callbackThread = HandlerThread("meshtastic-gatt").apply { start() }
    private val handler = Handler(callbackThread.looper)

    private val _adapterOn = MutableStateFlow(adapter?.isEnabled == true)
    override val adapterOn = _adapterOn.asStateFlow()

    private val stateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    _adapterOn.value = adapter?.isEnabled == true
                }
            }
        }

    init {
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                stateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "adapter-state receiver not registered: ${it.message}") }
    }

    override fun bondState(address: String): BondState =
        when (adapter?.getRemoteDevice(address)?.bondState) {
            BluetoothDevice.BOND_BONDED -> BondState.BONDED
            BluetoothDevice.BOND_BONDING -> BondState.BONDING
            BluetoothDevice.BOND_NONE -> BondState.NONE
            else -> BondState.UNKNOWN
        }

    override suspend fun dial(address: String): DialResult {
        val device = adapter?.getRemoteDevice(address) ?: return DialResult.NoHardware
        if (adapter?.isEnabled != true) return DialResult.AdapterOff
        arbiter.begin(ARBITER_TAG)
        try {
            return connectAndConfigure(device) // the arbiter slot covers only the connect→discover→MTU window
        } finally {
            arbiter.end(ARBITER_TAG)
        }
    }

    private suspend fun connectAndConfigure(device: BluetoothDevice): DialResult {
        val channel = AndroidGattChannel()
        val gatt =
            device.connectGatt(appContext, false, channel.callback, BluetoothDevice.TRANSPORT_LE)
                ?: return DialResult.Failed(status = -1, phase = "connectGatt")
        channel.attach(gatt)
        if (!channel.awaitConnected(CONNECT_TIMEOUT_MS)) {
            channel.close()
            return DialResult.Timeout
        }
        return finishSetup(channel)
    }

    private suspend fun finishSetup(channel: AndroidGattChannel): DialResult {
        val mtu = channel.negotiateMtu(REQUEST_MTU, MTU_TIMEOUT_MS)
        if (mtu < MIN_MTU) {
            channel.close()
            return DialResult.Failed(status = mtu, phase = "mtu")
        }
        if (!channel.discover(DISCOVER_TIMEOUT_MS) || !channel.resolveCharacteristics()) {
            channel.close()
            return DialResult.Failed(status = -1, phase = "service")
        }
        Log.d(TAG, "dial opened mtu=$mtu")
        return DialResult.Opened(channel, mtu)
    }

    /** One open GATT connection; serializes ops through a Mutex and a single completable per op. */
    private inner class AndroidGattChannel : GattChannel {
        private val eventsChannel = Channel<GattEvent>(Channel.UNLIMITED)
        override val events = eventsChannel

        @Volatile
        private var gatt: BluetoothGatt? = null
        private val opLock = Mutex()

        @Volatile
        private var pending: CompletableDeferred<GattResult<ByteArray>>? = null

        private var mtuResult: CompletableDeferred<Int>? = null
        private var connectResult: CompletableDeferred<Boolean>? = null
        private var discoverResult: CompletableDeferred<Boolean>? = null

        private var fromRadio: BluetoothGattCharacteristic? = null
        private var toRadio: BluetoothGattCharacteristic? = null
        private var fromNum: BluetoothGattCharacteristic? = null

        fun attach(g: BluetoothGatt) {
            gatt = g
        }

        val callback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            connectResult?.complete(true)
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            connectResult?.complete(false)
                            eventsChannel.trySend(GattEvent.Disconnected(status))
                            pending?.complete(GattResult.Closed)
                        }
                    }
                }

                override fun onMtuChanged(
                    g: BluetoothGatt,
                    mtu: Int,
                    status: Int,
                ) {
                    mtuResult?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else -1)
                }

                override fun onServicesDiscovered(
                    g: BluetoothGatt,
                    status: Int,
                ) {
                    discoverResult?.complete(status == BluetoothGatt.GATT_SUCCESS)
                }

                // API 33+ read; the deprecated form below covers 29–32.
                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) = completeRead(ch, value, status)

                @Deprecated("Pre-33 signature", ReplaceWith(""))
                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        @Suppress("DEPRECATION")
                        completeRead(ch, ch.value ?: ByteArray(0), status)
                    }
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (ch.uuid == MeshtasticUuids.TO_RADIO) {
                        pending?.complete(
                            if (status ==
                                BluetoothGatt.GATT_SUCCESS
                            ) {
                                GattResult.Ok(ByteArray(0))
                            } else {
                                GattResult.Failed(status)
                            },
                        )
                    }
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    if (descriptor.uuid == MeshtasticUuids.CCCD) {
                        pending?.complete(
                            if (status ==
                                BluetoothGatt.GATT_SUCCESS
                            ) {
                                GattResult.Ok(ByteArray(0))
                            } else {
                                GattResult.Failed(status)
                            },
                        )
                    }
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) = onFromNum(ch, value)

                @Deprecated("Pre-33 signature", ReplaceWith(""))
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                ) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        @Suppress("DEPRECATION")
                        onFromNum(ch, ch.value ?: ByteArray(0))
                    }
                }
            }

        private fun completeRead(
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (ch.uuid == MeshtasticUuids.FROM_RADIO) {
                pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) GattResult.Ok(value.copyOf()) else GattResult.Failed(status))
            }
        }

        private fun onFromNum(
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (ch.uuid == MeshtasticUuids.FROM_NUM) {
                val counter =
                    if (value.size >= UINT_BYTES) {
                        ByteBuffer
                            .wrap(value)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int
                            .toUInt()
                    } else {
                        0u
                    }
                eventsChannel.trySend(GattEvent.Notified(counter))
            }
        }

        suspend fun awaitConnected(timeoutMs: Long): Boolean {
            val d = CompletableDeferred<Boolean>()
            connectResult = d
            return withTimeoutOrNull(timeoutMs) { d.await() } ?: false
        }

        suspend fun negotiateMtu(
            mtu: Int,
            timeoutMs: Long,
        ): Int {
            val d = CompletableDeferred<Int>()
            mtuResult = d
            if (gatt?.requestMtu(mtu) != true) return -1
            return withTimeoutOrNull(timeoutMs) { d.await() } ?: -1
        }

        suspend fun discover(timeoutMs: Long): Boolean {
            val d = CompletableDeferred<Boolean>()
            discoverResult = d
            if (gatt?.discoverServices() != true) return false
            return withTimeoutOrNull(timeoutMs) { d.await() } ?: false
        }

        fun resolveCharacteristics(): Boolean {
            val service = gatt?.getService(MeshtasticUuids.SERVICE) ?: return false
            toRadio = service.getCharacteristic(MeshtasticUuids.TO_RADIO)
            fromRadio = service.getCharacteristic(MeshtasticUuids.FROM_RADIO)
            fromNum = service.getCharacteristic(MeshtasticUuids.FROM_NUM)
            return toRadio != null && fromRadio != null && fromNum != null
        }

        override suspend fun subscribeFromNum(timeoutMs: Long): GattResult<Unit> =
            op(timeoutMs) {
                val ch = fromNum ?: return@op false
                val g = gatt ?: return@op false
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(MeshtasticUuids.CCCD) ?: return@op false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
            }.map { }

        override suspend fun writeToRadio(
            bytes: ByteArray,
            timeoutMs: Long,
        ): GattResult<Unit> =
            op(timeoutMs) {
                val ch = toRadio ?: return@op false
                val g = gatt ?: return@op false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ch.value = bytes
                        g.writeCharacteristic(ch)
                    }
                }
            }.map { }

        override suspend fun readFromRadio(timeoutMs: Long): GattResult<ByteArray> =
            op(timeoutMs) {
                val ch = fromRadio ?: return@op false
                gatt?.readCharacteristic(ch) == true
            }

        override fun close() {
            runCatching {
                gatt?.disconnect()
                gatt?.close()
            }
            gatt = null
            eventsChannel.close()
        }

        /** Serializes one GATT op: issues [issue], awaits its callback, or Timeout/Closed/Failed. */
        private suspend fun op(
            timeoutMs: Long,
            issue: () -> Boolean,
        ): GattResult<ByteArray> =
            opLock.withLock {
                val d = CompletableDeferred<GattResult<ByteArray>>()
                pending = d
                if (!issue()) {
                    pending = null
                    return@withLock GattResult.Failed(-1)
                }
                val result = withTimeoutOrNull(timeoutMs) { d.await() } ?: GattResult.Timeout
                pending = null
                result
            }

        private fun <T> GattResult<ByteArray>.map(block: (ByteArray) -> T): GattResult<T> =
            when (this) {
                is GattResult.Ok -> GattResult.Ok(block(value))
                is GattResult.Failed -> this
                GattResult.Timeout -> GattResult.Timeout
                GattResult.Closed -> GattResult.Closed
            }
    }

    private companion object {
        const val TAG = "MeshtasticGatt"
        const val ARBITER_TAG = "lora-dial"
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val MTU_TIMEOUT_MS = 10_000L
        const val DISCOVER_TIMEOUT_MS = 10_000L
        const val REQUEST_MTU = 512

        // A floor to catch a failed negotiation (default ATT MTU 23); a real board negotiates 255+ and the
        // transport sizes its fragments DOWN to whatever this is, so no single write ever needs splitting.
        const val MIN_MTU = 128
        const val UINT_BYTES = 4
    }
}
