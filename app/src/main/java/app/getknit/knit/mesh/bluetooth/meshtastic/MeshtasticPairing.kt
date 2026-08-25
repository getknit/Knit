package app.getknit.knit.mesh.bluetooth.meshtastic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.BoardRef
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A Meshtastic board found by a scan: its MAC [address], advertised [name], signal, and bond state. */
internal data class MeshtasticCandidate(
    val address: String,
    val name: String,
    val rssi: Int,
    val bonded: Boolean,
)

/**
 * Lists the bonded Meshtastic boards for the settings picker. A `SecurityException` (permission revoked
 * out from under us) degrades to an empty list rather than crashing, matching the mesh BLE plane's posture.
 */
@SuppressLint("MissingPermission")
internal class BondedBoardDirectory(
    context: Context,
) : BoardDirectory {
    private val adapter: BluetoothAdapter? =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    override fun bonded(): List<BoardRef> =
        runCatching {
            adapter?.bondedDevices.orEmpty().map { BoardRef(it.address, it.name ?: it.address) }
        }.getOrDefault(emptyList())
}

/**
 * Scans for advertising Meshtastic boards (filtered on the service UUID) for the picker. A board stops
 * advertising once a client is connected (ESP32 is single-client), so a board already held by the
 * Meshtastic app won't appear — which is the correct signal to the user.
 */
@SuppressLint("MissingPermission")
internal class MeshtasticScanner(
    context: Context,
) {
    private val adapter: BluetoothAdapter? =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    fun scan(): Flow<MeshtasticCandidate> =
        callbackFlow {
            val scanner = adapter?.bluetoothLeScanner
            if (scanner == null) {
                close()
                return@callbackFlow
            }
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        val device = result.device
                        trySend(
                            MeshtasticCandidate(
                                address = device.address,
                                name = device.name ?: result.scanRecord?.deviceName ?: device.address,
                                rssi = result.rssi,
                                bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                            ),
                        )
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Log.w(TAG, "board scan failed: $errorCode")
                        close()
                    }
                }
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MeshtasticUuids.SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            runCatching { scanner.startScan(listOf(filter), settings, callback) }
                .onFailure { close(it) }
            awaitClose { runCatching { scanner.stopScan(callback) } }
        }

    private companion object {
        const val TAG = "MeshtasticGatt"
    }
}

/** The result of a [MeshtasticBonder.bond] attempt. */
internal sealed interface BondResult {
    data object Bonded : BondResult

    data object AlreadyBonded : BondResult

    data class Failed(
        val reason: Int,
    ) : BondResult

    data object Timeout : BondResult
}

/**
 * Initiates system pairing with a board (the OLED shows a 6-digit PIN) and waits for the bond to settle.
 * Bonding happens here, in the picker — not in the session — so the session only ever deals with an
 * already-bonded device and can classify a later auth failure as a stale bond.
 */
@SuppressLint("MissingPermission")
internal class MeshtasticBonder(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    suspend fun bond(address: String): BondResult {
        val device = adapter?.getRemoteDevice(address) ?: return BondResult.Failed(reason = -1)
        if (device.bondState == BluetoothDevice.BOND_BONDED) return BondResult.AlreadyBonded
        val settled = kotlinx.coroutines.CompletableDeferred<BondResult>()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                        BluetoothDevice.BOND_BONDED -> settled.complete(BondResult.Bonded)
                        BluetoothDevice.BOND_NONE -> settled.complete(BondResult.Failed(reason = 0))
                    }
                }
            }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            if (!device.createBond()) return BondResult.Failed(reason = -2)
            return kotlinx.coroutines.withTimeoutOrNull(BOND_TIMEOUT_MS) { settled.await() } ?: BondResult.Timeout
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    private companion object {
        const val BOND_TIMEOUT_MS = 90_000L
    }
}
