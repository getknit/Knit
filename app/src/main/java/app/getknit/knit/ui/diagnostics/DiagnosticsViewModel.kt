package app.getknit.knit.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.crash.CrashReportRef
import app.getknit.knit.crash.CrashReports
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.moderation.ModelLoadGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A node in the mesh, classified as a direct neighbor or reachable only via relay. */
data class NodeInfo(
    val nodeId: String,
    val displayName: String,
    val direct: Boolean,
    // When this node's cached profile was last updated (millis); null if we've never received one.
    val profileUpdatedAt: Long?,
    // Which radios this node is currently reachable over (BLE / NAN); empty for relay-only nodes.
    val transports: Set<TransportKind> = emptySet(),
)

data class DiagnosticsUiState(
    val myNodeId: String = "",
    val myName: String = "",
    val directNodes: List<NodeInfo> = emptyList(),
    val relayNodes: List<NodeInfo> = emptyList(),
    val metrics: MeshMetrics.Snapshot = MeshMetrics.Snapshot(0, 0, 0, 0, 0, 0),
    // Per-radio status (Bluetooth vs Wi-Fi Aware), one entry per active transport.
    val transports: List<TransportStatus> = emptyList(),
    // Per-spool status for the Internet plane; empty whenever the plane is parked.
    val spools: List<SpoolStatus> = emptyList(),
)

/** The three flows folded into the [DiagnosticsViewModel.state] combine's fifth slot (combine tops out at 5). */
private data class DiagExtras(
    val metrics: MeshMetrics.Snapshot,
    val statuses: List<TransportStatus>,
    val peerTransports: Map<String, Set<TransportKind>>,
    val spools: List<SpoolStatus>,
)

/**
 * Backs the read-only Diagnostics screen. Classifies known nodes as directly-connected (in the
 * transport's live neighbor set) vs reachable only via relay (we hold a flooded profile for them but
 * they aren't a direct neighbor) — the mesh is a pure flood network with no routing table, so this
 * classification is as deep as the existing data goes. [MeshMetrics] has no reactive stream, so it's
 * polled on a [REFRESH_MS] timer.
 */
class DiagnosticsViewModel(
    peers: PeerRepository,
    private val meshManager: MeshController,
    identity: Identity,
    settings: SettingsStore,
    private val metrics: MeshMetrics,
    relayStatus: RelayStatusRepository,
    private val crashes: CrashReports,
    private val modelGuard: ModelLoadGuard,
) : ViewModel() {
    private val myNodeId = MutableStateFlow<String?>(null)

    /**
     * The newest stored crash, or null. Deliberately **not** part of [state]: that combine is already at
     * its five-source limit (hence [DiagExtras]), and a crash cannot change while this screen is alive —
     * a new one would mean the process died and took this ViewModel with it.
     */
    private val _lastCrash = MutableStateFlow<CrashReportRef?>(null)
    val lastCrash: StateFlow<CrashReportRef?> = _lastCrash.asStateFlow()

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
        refreshLastCrash()
    }

    /** Re-reads the store. Called on resume, so deleting the report on the crash screen clears this row. */
    fun refreshLastCrash() {
        viewModelScope.launch { _lastCrash.value = crashes.latest() }
    }

    /**
     * Whether the poison-pill has turned an on-device model off (ADR 037). Unlike [lastCrash] this is a
     * live flow, not a snapshot: the reset button is on this very screen, so the value *can* change while
     * the ViewModel is alive and a refresh-on-resume would leave the row stale until the user left.
     */
    val moderationLatched: StateFlow<Boolean> =
        combine(ModelLoadGuard.ALL.map(modelGuard::observeLatched)) { latched -> latched.any { it } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Gives every latched model another chance. Takes effect on the next process start — the moderator
     * latches `loaded` in memory on its first attempt, so nothing reloads inside a running app. The
     * confirm dialog says so rather than implying an instant fix.
     */
    fun resetModerationLatch() {
        viewModelScope.launch { ModelLoadGuard.ALL.forEach { modelGuard.clear(it) } }
        _events.tryEmit(R.string.diagnostics_moderation_reset_done)
    }

    /** Live radio health, shown as a status line above the mesh controls. */
    val health: StateFlow<TransportHealth> =
        meshManager.transportHealth
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransportHealth.Healthy)

    // One-shot snackbar feedback (a string resource id) for the Restart / Scan actions.
    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    /** Bounces the mesh transports (re-advertise, reconnect, clear stale peers); keeps the service. */
    fun restartMesh() {
        viewModelScope.launch { meshManager.restart() }
        _events.tryEmit(R.string.diagnostics_mesh_restarted)
    }

    /** Triggers an immediate rescan / reconnect. */
    fun rescan() {
        viewModelScope.launch { meshManager.heal() }
        _events.tryEmit(R.string.diagnostics_scanning)
    }

    private val metricsTicker: Flow<MeshMetrics.Snapshot> =
        flow {
            while (true) {
                emit(metrics.snapshot())
                delay(REFRESH_MS)
            }
        }

    // Metrics + per-transport status + per-peer transport map, pre-combined so the main [state] combine stays
    // within its five-source limit.
    private val extras: Flow<DiagExtras> =
        combine(
            metricsTicker,
            meshManager.transportStatuses,
            meshManager.peerTransports,
            relayStatus.statuses,
        ) { snapshot, statuses, peerTransports, spools -> DiagExtras(snapshot, statuses, peerTransports, spools) }

    val state: StateFlow<DiagnosticsUiState> =
        combine(
            peers.observePeers(),
            meshManager.neighbors,
            myNodeId,
            settings.displayName,
            extras,
        ) { peerList, neighbors, me, myName, extra ->
            val online = neighbors.map { it.nodeId }.toSet()
            val byNode = peerList.associateBy { it.nodeId }
            val nodeIds = (peerList.map { it.nodeId } + online).toSet() - setOfNotNull(me)
            val nodes =
                nodeIds.map { id ->
                    NodeInfo(
                        nodeId = id,
                        displayName = displayNameFor(byNode[id]?.name, id),
                        direct = id in online,
                        profileUpdatedAt = byNode[id]?.updatedAt?.takeIf { it > 0L },
                        transports = extra.peerTransports[id].orEmpty(),
                    )
                }
            DiagnosticsUiState(
                myNodeId = me.orEmpty(),
                myName = displayNameFor(myName, me.orEmpty()),
                directNodes = nodes.filter { it.direct }.sortedBy { it.displayName.lowercase() },
                relayNodes = nodes.filterNot { it.direct }.sortedBy { it.displayName.lowercase() },
                metrics = extra.metrics,
                transports = extra.statuses,
                spools = extra.spools,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    private companion object {
        const val REFRESH_MS = 2_000L
    }
}
