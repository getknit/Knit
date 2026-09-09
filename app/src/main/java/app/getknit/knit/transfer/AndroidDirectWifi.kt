package app.getknit.knit.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.getknit.knit.mesh.MeshTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [DirectWifi] over `android.net.wifi.p2p` — the only importer of it in the app (detekt's `ForbiddenImport`).
 *
 * Hosting is `createGroup` with our own name and passphrase; joining is `connect` with the same two facts and
 * nothing else (the API-29 "join a known group" path: no discovery, no device address, no dialog on either
 * side). Formation is read off `WIFI_P2P_CONNECTION_CHANGED_ACTION` plus a poll of `requestConnectionInfo`,
 * matched by SSID and role, and the group-owner address is taken from the platform, never assumed.
 *
 * Around every group this class also does what the pure manager must not know about: it lends the Wi-Fi
 * radio out of the mesh ([MeshTransport.pause] — on Android 12+ our own Aware session would otherwise make
 * `createGroup`/`connect` answer BUSY, and the transport's attach loop would burn its leak budget while the
 * group is up), holds a partial wake lock so a long transfer survives the screen going off, and on
 * [release] removes the group, **closes the channel** (the P2P interface lingers ~150 s otherwise and blocks
 * Aware from coming back) and hands the radio back ([MeshTransport.resume]).
 *
 * The runtime grant (`NEARBY_WIFI_DEVICES` on 33+, fine location below) is the radios' own, asked at
 * onboarding; it is checked at entry and the calls are marked [SuppressLint] for "MissingPermission", the
 * `WifiAwareTransport` precedent.
 */
@SuppressLint("MissingPermission")
class AndroidDirectWifi(
    context: Context,
    private val mesh: MeshTransport,
) : DirectWifi {
    private val app = context.applicationContext
    private val p2p: WifiP2pManager? = app.getSystemService(WifiP2pManager::class.java)
    private val wifi: WifiManager? = app.getSystemService(WifiManager::class.java)
    private val power: PowerManager? = app.getSystemService(PowerManager::class.java)

    // host / join / release are serialized; every field below is touched only under this lock.
    private val mutex = Mutex()
    private var channel: WifiP2pManager.Channel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lent = false

    private class Formation(
        val info: WifiP2pInfo,
        val group: WifiP2pGroup,
        val address: InetAddress,
    ) {
        fun matches(
            ssid: String,
            owner: Boolean,
        ): Boolean = info.isGroupOwner == owner && group.networkName == ssid
    }

    override fun refusal(): TransferRefusal? =
        when {
            p2p == null || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) -> TransferRefusal.NoWifiDirect
            wifi?.isWifiEnabled != true -> TransferRefusal.WifiOff
            !hasPermission() -> TransferRefusal.Permission
            else -> null
        }

    override suspend fun host(
        credentials: GroupCredentials,
        timeoutMs: Long,
    ): HostedGroup =
        mutex.withLock {
            val manager = ready()
            val ch = prepare(manager)
            val config = config(credentials, band = true)
            val formed =
                withTimeoutOrNull(timeoutMs) {
                    awaitFormation(manager, ch, credentials.ssid, owner = true) { manager.createGroup(ch, config, it) }
                } ?: throw DirectWifiException("group did not form within ${timeoutMs}ms", TransferRefusal.NoWifiDirect)
            Log.i(TAG, "hosting ${credentials.ssid} at ${formed.address.hostAddress} freq=${frequencyOf(formed.group)}")
            HostedGroup(formed.address, prefixLengthOf(formed.group, formed.address), frequencyOf(formed.group))
        }

    override suspend fun join(
        credentials: GroupCredentials,
        attemptMs: Long,
    ): JoinedGroup =
        mutex.withLock {
            val manager = ready()
            val ch = prepare(manager)
            val config = config(credentials, band = false)
            val formed =
                withTimeoutOrNull(attemptMs) {
                    awaitFormation(manager, ch, credentials.ssid, owner = false) { manager.connect(ch, config, it) }
                }
            if (formed == null) {
                runCatching { act("cancelConnect") { manager.cancelConnect(ch, it) } }
                throw DirectWifiException("no group formed within ${attemptMs}ms")
            }
            Log.i(TAG, "joined ${credentials.ssid}; owner at ${formed.address.hostAddress} freq=${frequencyOf(formed.group)}")
            JoinedGroup(formed.address)
        }

    override suspend fun release() {
        mutex.withLock {
            val manager = p2p
            val ch = channel
            if (manager != null && ch != null) {
                // BUSY here means "no group to remove", which is the state we want.
                runCatching { withTimeoutOrNull(RELEASE_MS) { act("removeGroup") { manager.removeGroup(ch, it) } } }
                runCatching { ch.close() }
            }
            channel = null
            wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
            wakeLock = null
            if (lent) {
                lent = false
                mesh.resume()
            }
        }
    }

    // ---- setup and teardown ----

    private fun ready(): WifiP2pManager {
        refusal()?.let { throw DirectWifiException("Wi-Fi Direct not usable: $it", it) }
        return checkNotNull(p2p)
    }

    /** Lends the radio out, opens the per-transfer channel, clears a group a crash of ours left behind. */
    private suspend fun prepare(manager: WifiP2pManager): WifiP2pManager.Channel {
        if (!lent) {
            mesh.pause()
            lent = true
            delay(SETTLE_MS)
        }
        acquireWakeLock()
        val ch =
            channel ?: manager.initialize(app, Looper.getMainLooper()) {
                Log.w(TAG, "Wi-Fi Direct channel disconnected")
                channel = null
            } ?: throw DirectWifiException("Wi-Fi Direct channel unavailable", TransferRefusal.NoWifiDirect)
        channel = ch
        val leftover = groupInfo(manager, ch)
        if (leftover != null && GroupCredentials.isOurs(leftover.networkName)) {
            Log.w(TAG, "removing a leftover group ${leftover.networkName}")
            runCatching { act("removeGroup(leftover)") { manager.removeGroup(ch, it) } }
        }
        return ch
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock =
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MAX_MS)
            }
    }

    private fun hasPermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        return ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun config(
        credentials: GroupCredentials,
        band: Boolean,
    ): WifiP2pConfig =
        WifiP2pConfig
            .Builder()
            .setNetworkName(credentials.ssid)
            .setPassphrase(credentials.passphrase)
            .apply { if (band) setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO) }
            .enablePersistentMode(false)
            .build()

    // ---- the platform's callbacks as suspend calls ----

    /** Issues [request] and returns the first formation that matches [ssid]/[owner]; a refused request throws. */
    private suspend fun awaitFormation(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        ssid: String,
        owner: Boolean,
        request: (WifiP2pManager.ActionListener) -> Unit,
    ): Formation =
        coroutineScope {
            // Collect before asking, so a formation that lands between the two is never missed; the flow's
            // own opening poll covers one that had already landed.
            val formed = async { formations(manager, ch).first { it != null && it.matches(ssid, owner) } }
            act(if (owner) "createGroup" else "connect", request)
            checkNotNull(formed.await())
        }

    /** The current formation after every connection-changed broadcast, starting with one poll. */
    private fun formations(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
    ): Flow<Formation?> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) trySend(Unit)
                    }
                }
            val registered =
                runCatching {
                    ContextCompat.registerReceiver(
                        app,
                        receiver,
                        IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                }.isSuccess
            trySend(Unit)
            awaitClose { if (registered) runCatching { app.unregisterReceiver(receiver) } }
        }.map { readFormation(manager, ch) }

    private suspend fun readFormation(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
    ): Formation? {
        val info = suspendCancellableCoroutine { cont -> manager.requestConnectionInfo(ch) { cont.resume(it) } }
        if (info == null || !info.groupFormed) return null
        val address = info.groupOwnerAddress ?: return null
        val group = groupInfo(manager, ch) ?: return null
        return Formation(info, group, address)
    }

    private suspend fun groupInfo(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
    ): WifiP2pGroup? = suspendCancellableCoroutine { cont -> manager.requestGroupInfo(ch) { cont.resume(it) } }

    private suspend fun act(
        what: String,
        call: (WifiP2pManager.ActionListener) -> Unit,
    ) = suspendCancellableCoroutine { cont ->
        call(
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onFailure(reason: Int) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            DirectWifiException("$what failed: ${reasonName(reason)}", refusalFor(reason)),
                        )
                    }
                }
            },
        )
    }

    private fun refusalFor(reason: Int): TransferRefusal =
        if (reason == WifiP2pManager.BUSY) TransferRefusal.Hotspot else TransferRefusal.NoWifiDirect

    private fun reasonName(reason: Int): String =
        when (reason) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
            else -> reason.toString()
        }

    private fun prefixLengthOf(
        group: WifiP2pGroup,
        address: InetAddress,
    ): Int =
        runCatching {
            NetworkInterface
                .getByName(group.`interface`)
                ?.interfaceAddresses
                ?.firstOrNull { it.address == address }
                ?.networkPrefixLength
                ?.toInt()
        }.getOrNull() ?: DEFAULT_PREFIX_LENGTH

    private fun frequencyOf(group: WifiP2pGroup): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) group.frequency else 0

    private companion object {
        const val TAG = "KnitTransfer"
        const val WAKE_LOCK_TAG = "knit:transfer"

        /** After [MeshTransport.pause]: the Aware interface's own teardown plus the transport's settle margin. */
        const val SETTLE_MS = 2_000L
        const val RELEASE_MS = 5_000L
        const val WAKE_LOCK_MAX_MS = 2 * 60 * 60_000L
        const val DEFAULT_PREFIX_LENGTH = 24
    }
}
