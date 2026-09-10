package app.getknit.knit.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
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
import androidx.annotation.RequiresApi
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
import java.net.Inet6Address
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
            val config = config(credentials, band = true, ipv6 = false)
            val formed =
                withTimeoutOrNull(timeoutMs) {
                    awaitFormation(manager, ch, credentials.ssid, owner = true) { manager.createGroup(ch, config, it) }
                } ?: throw DirectWifiException("group did not form within ${timeoutMs}ms", TransferRefusal.NoWifiDirect)
            val addresses = addressesOf(formed.group, formed.address)
            Log.i(
                TAG,
                "hosting ${credentials.ssid} on ${addresses.joinToString { "${it.address.hostAddress}/${it.prefixLength}" }} " +
                    "freq=${frequencyOf(formed.group)}",
            )
            HostedGroup(addresses, frequencyOf(formed.group))
        }

    override suspend fun join(
        credentials: GroupCredentials,
        attemptMs: Long,
    ): JoinedGroup =
        mutex.withLock {
            val manager = ready()
            val ch = prepare(manager)
            val ipv6 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && ipv6LinkLocalSupported()
            val config = config(credentials, band = false, ipv6 = ipv6)
            val formed =
                withTimeoutOrNull(attemptMs) {
                    awaitFormation(manager, ch, credentials.ssid, owner = false) { manager.connect(ch, config, it) }
                }
            if (formed == null) {
                runCatching { act("cancelConnect") { manager.cancelConnect(ch, it) } }
                throw DirectWifiException("no group formed within ${attemptMs}ms")
            }
            Log.i(
                TAG,
                "joined ${credentials.ssid} (${if (ipv6) "IPv6 link-local" else "IPv4 DHCP"}); " +
                    "owner at ${formed.address.hostAddress} freq=${frequencyOf(formed.group)}",
            )
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

    override suspend fun sweep() {
        val manager = p2p ?: return
        if (refusal() != null) return
        mutex.withLock {
            // A live transfer owns the channel and sweeps for itself in prepare(); leave it alone.
            if (channel != null) return@withLock
            val ch = manager.initialize(app, Looper.getMainLooper()) {} ?: return@withLock
            try {
                val leftover = groupInfo(manager, ch)
                if (leftover != null && GroupCredentials.isOurs(leftover.networkName)) {
                    Log.w(TAG, "removing ${leftover.networkName}, left on air by a previous run")
                    runCatching { withTimeoutOrNull(RELEASE_MS) { act("removeGroup(stale)") { manager.removeGroup(ch, it) } } }
                    // No mesh.resume() here: nothing paused the transport this run, so its own attach loop
                    // is already running and picks the radio up now that the group is gone.
                }
            } finally {
                runCatching { ch.close() }
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
        ipv6: Boolean,
    ): WifiP2pConfig =
        WifiP2pConfig
            .Builder()
            .setNetworkName(credentials.ssid)
            .setPassphrase(credentials.passphrase)
            .apply {
                if (band) setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                if (ipv6 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setGroupClientIpProvisioningMode(WifiP2pConfig.GROUP_CLIENT_IP_PROVISIONING_MODE_IPV6_LINK_LOCAL)
                }
            }.enablePersistentMode(false)
            .build()

    /**
     * Whether this device should join with IPv6 link-local provisioning instead of taking a DHCP lease —
     * which skips the DHCP round trip and is the modern path. Android 14 (the public API's own level, even
     * though the plumbing under it landed in 13), and only when the platform *also* promises to hand the
     * group owner's link-local address back in `WIFI_P2P_CONNECTION_CHANGED_ACTION`: otherwise finding it is
     * the app's problem, and there is no out-of-band channel for it here, since the group is up precisely
     * because Wi-Fi Aware has been paused. The promise also implies the owner *has* such an address, which is
     * what the host side binds. The mode is a client-side choice — the host is never told which family the
     * receiver arrived on, and listens on both.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun ipv6LinkLocalSupported(): Boolean = p2p?.isGroupOwnerIPv6LinkLocalAddressProvided == true

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
        val nif = runCatching { NetworkInterface.getByName(group.`interface`) }.getOrNull()
        return Formation(info, group, scopedTo(address, nif))
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
        when {
            reason == WifiP2pManager.BUSY -> TransferRefusal.Hotspot
            reason == WifiP2pManager.ERROR && backgrounded() -> TransferRefusal.Background
            else -> TransferRefusal.NoWifiDirect
        }

    /**
     * Whether a refused call is most likely the API 30-32 background gap rather than a device that cannot do
     * Wi-Fi Direct at all. Below `NEARBY_WIFI_DEVICES` (33+) the framework gates P2P on the *location* AppOp,
     * which a foreground-only grant does not satisfy off-screen — and the sender hosts when an ACCEPT lands,
     * which may be minutes after the user left. Telling them to open Knit is the only fix this cut carries; a
     * foreground service of our own declaring the `location` type would be the other one.
     */
    private fun backgrounded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun reasonName(reason: Int): String =
        when (reason) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
            else -> reason.toString()
        }

    /**
     * Every usable address on the group interface, owner-address first. Both families are returned: the IPv4
     * one a DHCP client reaches, and the IPv6 link-local one a client that joined with link-local
     * provisioning reaches. Falls back to [fallback] alone when the interface cannot be read.
     */
    private fun addressesOf(
        group: WifiP2pGroup,
        fallback: InetAddress,
    ): List<GroupAddress> {
        val nif = runCatching { NetworkInterface.getByName(group.`interface`) }.getOrNull()
        val found =
            nif
                ?.interfaceAddresses
                ?.mapNotNull { entry ->
                    val address = entry.address ?: return@mapNotNull null
                    if (address.isAnyLocalAddress || address.isLoopbackAddress) return@mapNotNull null
                    GroupAddress(scopedTo(address, nif), entry.networkPrefixLength.toInt())
                }.orEmpty()
                .sortedBy { it.address != fallback }
        return found.ifEmpty { listOf(GroupAddress(fallback, DEFAULT_PREFIX_LENGTH)) }
    }

    /**
     * An IPv6 link-local address is only routable with the interface it belongs to attached to it. The
     * platform may hand back a bare `fe80::…`, and `NetworkInterface` may too, so pin the scope before the
     * address is used to bind or connect.
     */
    private fun scopedTo(
        address: InetAddress,
        nif: NetworkInterface?,
    ): InetAddress {
        val v6 = address as? Inet6Address ?: return address
        if (nif == null || !v6.isLinkLocalAddress || v6.scopeId != 0) return address
        return runCatching { Inet6Address.getByAddress(null, v6.address, nif) as InetAddress }.getOrDefault(address)
    }

    private fun frequencyOf(group: WifiP2pGroup): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) group.frequency else 0

    private companion object {
        const val TAG = "KnitTransfer"
        const val WAKE_LOCK_TAG = "knit:transfer"

        /**
         * After [MeshTransport.pause]: the Aware interface's own teardown plus a margin. Measured on a Pixel 7
         * and a Pixel 9 the teardown (`pause` → `aware_nmi0 is deactivated`) takes 120-146 ms, so this is
         * about five times the observed cost. It is the floor under how fast a group can come up, and it is
         * paid on both sides, so it was worth measuring rather than guessing: too short and `createGroup`
         * answers BUSY, which surfaces as a Hotspot refusal.
         */
        const val SETTLE_MS = 750L
        const val RELEASE_MS = 5_000L
        const val WAKE_LOCK_MAX_MS = 2 * 60 * 60_000L
        const val DEFAULT_PREFIX_LENGTH = 24
    }
}
