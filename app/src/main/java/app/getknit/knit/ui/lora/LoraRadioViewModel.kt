package app.getknit.knit.ui.lora

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.settings.DedicatedBoard
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.lora.AirtimeSnapshot
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.BoardFilter
import app.getknit.knit.mesh.lora.BoardIntervals
import app.getknit.knit.mesh.lora.BoardRef
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraGatewayPolicy
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.lora.ProvisionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A bonded board the user can bind the LoRa plane to. */
data class BoardOption(
    val address: String,
    val name: String,
    val selected: Boolean,
)

/** How the LoRa link is doing, as a UI-facing enum decoupled from the internal link state. */
enum class LoraConnState { Off, Connecting, Ready, Reconnecting, NeedsPairing, Unavailable }

/** The result of the last provisioning tap, mapped off the internal provision result for the screen. */
enum class LoraProvisionOutcome { Provisioned, AlreadyPresent, Dedicated, Restored, NoFreeSlot, Failed, NotReady }

data class LoraRadioUiState(
    val enabled: Boolean = false,
    /** Whether private messages ride LoRa too (`SettingsStore.loraDmEnabled`; meaningful only while [enabled]). */
    val dmEnabled: Boolean = true,
    val bridgeEnabled: Boolean = true,
    val boardName: String? = null,
    val boardAddress: String? = null,
    val channel: Int = 0,
    val connection: LoraConnState = LoraConnState.Off,
    val boardNodeNum: String? = null,
    val snr: Float? = null,
    val rssi: Int? = null,
    val heard: Int = 0,
    /** Radios heard on our channel — the honest answer to "is the other board in range". */
    val boardsHeard: Int = 0,
    /** The board's firmware, once the handshake has told us. */
    val firmware: String? = null,
    /** The board's own battery, once it has reported one; null while not connected. */
    val battery: BoardBattery? = null,
    /** True when another board in this BLE/NAN clique won the gateway election and this one only listens. */
    val bridgePassive: Boolean = false,
    /** Airtime spent this hour as a percentage of the plane's own budget, or null before the link is up. */
    val airtimePercent: Int? = null,
    /** The board's region and modem preset, once its config stream has reported them. */
    val radioConfig: String? = null,
    /** The name the connected board gives the selected [channel] slot; null while not connected or when unnamed. */
    val channelName: String? = null,
    /** Connected, but the selected slot is not the Knit channel — the setup step most likely still owed. */
    val channelMismatch: Boolean = false,
    /** The board carries Knit as its *primary*: the radio is on a Knit slot and its housekeeping is quiet. */
    val dedicated: Boolean = false,
    /** The "dedicate this board" confirmation is open — the costs are real, so the tap is never the action. */
    val confirmDedicate: Boolean = false,
    val boards: List<BoardOption> = emptyList(),
    /** Bonded devices the picker hides as not board-like (`BoardFilter`); the "show all" toggle reveals them. */
    val hiddenBoards: Int = 0,
    val showAllBoards: Boolean = false,
    /** Whether the phone has *any* bonded device — splits "pair one first" from "none of these looks like a board". */
    val anyBonded: Boolean = false,
    val provisioning: Boolean = false,
    val provisionOutcome: LoraProvisionOutcome? = null,
)

/**
 * The LoRa radio settings screen: the master switch, the bonded-board picker, the channel index, and the
 * live link status. Mirrors [app.getknit.knit.ui.relay.InternetRelayViewModel]; there is no consent sheet
 * because a LoRa board is the user's own hardware, not a third-party relay.
 */
internal class LoraRadioViewModel(
    private val settings: SettingsStore,
    private val lora: LoraPlaneStatus,
    private val boards: BoardDirectory,
) : ViewModel() {
    // Transient, action-driven UI state (the provisioning spinner + its outcome) that isn't in a settings flow.
    private val provisionState = MutableStateFlow(ProvisionState())

    // Bumped to re-read the bonded list (the screen does so on resume, after the user pairs a board in the
    // system settings); the toggle that reveals the devices the board filter hides.
    private val refresh = MutableStateFlow(0)
    private val showAll = MutableStateFlow(false)

    /** The bonded list is a Binder call into the Bluetooth service, so it is read on its own arm — never on link churn. */
    private data class Picker(
        val address: String?,
        val bonded: List<BoardRef>,
        val showAll: Boolean,
    )

    private val picker =
        combine(settings.loraDeviceAddress, refresh, showAll) { address, _, all ->
            Picker(address, runCatching { boards.bonded() }.getOrDefault(emptyList()), all)
        }

    val state: StateFlow<LoraRadioUiState> =
        combine(
            combine(settings.loraEnabled, settings.loraDmEnabled, settings.loraBridgeEnabled, ::Triple),
            picker,
            settings.loraChannelIndex,
            lora.status,
            provisionState,
        ) { (enabled, dmEnabled, bridgeEnabled), picker, channel, status, provision ->
            val address = picker.address
            val ready = status.state as? LinkState.Ready
            // An un-dedicated board leaves slot 0 as its own (usually unnamed) primary, so that reads as a
            // mismatch — correctly, since Knit is then in a secondary. On a dedicated board Knit *is* slot 0.
            val channelName =
                ready
                    ?.channels
                    ?.firstOrNull { it.index == channel }
                    ?.name
                    ?.takeIf { it.isNotEmpty() }
            LoraRadioUiState(
                enabled = enabled,
                dmEnabled = dmEnabled,
                bridgeEnabled = bridgeEnabled,
                boardName = picker.bonded.firstOrNull { it.address == address }?.name ?: address,
                boardAddress = address,
                channel = channel,
                connection = status.state.toConnState(),
                boardNodeNum = status.boardNodeNum?.let { "!%08x".format(it.toInt()) },
                snr = status.lastSnr,
                rssi = status.lastRssi,
                heard = status.heard,
                boardsHeard = status.boardsHeard,
                firmware = ready?.board?.firmwareVersion,
                battery = ready?.let { status.battery },
                bridgePassive = status.role == LoraGatewayPolicy.Role.PASSIVE,
                airtimePercent = ready?.let { status.airtime?.let(::airtimePercent) },
                radioConfig = ready?.radio?.let { "${it.region} ${it.modemPreset}" },
                channelName = channelName,
                channelMismatch = ready != null && channelName != KnitChannel.NAME,
                dedicated = ready?.channels?.any { it.index == PRIMARY_INDEX && it.name == KnitChannel.NAME } == true,
                confirmDedicate = provision.confirm,
                boards =
                    BoardFilter
                        .visible(picker.bonded, address, picker.showAll)
                        .map { BoardOption(it.address, it.name, it.address == address) },
                hiddenBoards = BoardFilter.hidden(picker.bonded, address),
                showAllBoards = picker.showAll,
                anyBonded = picker.bonded.isNotEmpty(),
                provisioning = provision.running,
                provisionOutcome = provision.outcome,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LoraRadioUiState())

    /**
     * Airtime spent this hour as a percentage of what the plane allows itself — the LIVE budget, which is the
     * whole allowance, so this reads as "how much of my radio time Knit has used". Rounded up, so any spending
     * at all shows as at least 1 %.
     */
    private fun airtimePercent(air: AirtimeSnapshot): Int {
        val budget = air.liveBudgetMs
        if (budget <= 0) return 0
        val used = air.liveUsedMs + air.bridgeUsedMs
        return ((used * PERCENT + budget - 1) / budget).toInt().coerceIn(0, PERCENT.toInt())
    }

    /** Re-reads the bonded list — the screen calls this on resume, so a board paired in Settings shows up on return. */
    fun refreshBoards() {
        refresh.update { it + 1 }
    }

    /** Reveals (or re-hides) the bonded devices the board filter keeps out of the picker. */
    fun setShowAllBoards(on: Boolean) {
        showAll.value = on
    }

    fun onToggle(on: Boolean) {
        viewModelScope.launch { settings.setLoraEnabled(on) }
    }

    fun onToggleBridge(on: Boolean) {
        viewModelScope.launch { settings.setLoraBridgeEnabled(on) }
    }

    fun onToggleDms(on: Boolean) {
        viewModelScope.launch { settings.setLoraDmEnabled(on) }
    }

    fun pickBoard(board: BoardOption) {
        viewModelScope.launch { settings.setLoraDevice(board.address, board.name) }
    }

    fun forgetBoard() {
        viewModelScope.launch { settings.clearLoraDevice() }
    }

    fun setChannel(index: Int) {
        viewModelScope.launch { settings.setLoraChannelIndex(index) }
    }

    /**
     * Writes the well-known Knit channel onto the connected board (over the Meshtastic admin API), and on
     * success binds the plane to the slot it landed in. A one-tap replacement for configuring a channel by
     * hand in the Meshtastic app.
     */
    fun provisionChannel() {
        provision(ProvisionMode.Rendezvous)
    }

    /** Opens the confirmation for handing the whole board to Knit; the costs are stated there, not here. */
    fun askDedicate() {
        provisionState.update { it.copy(confirm = true, outcome = null) }
    }

    fun dismissDedicate() {
        provisionState.update { it.copy(confirm = false) }
    }

    /**
     * Hands the board to Knit (ADR 045): Knit becomes its primary channel, which moves the radio onto a
     * Knit-derived RF slot, and its housekeeping broadcasts are stretched. The intervals the board had before
     * come back in the result and are persisted here — they are the only way a restore can be faithful.
     */
    fun dedicateBoard() {
        provision(ProvisionMode.Dedicate)
    }

    /** Puts the board back on the stock public channel, with Knit demoted to a secondary. */
    fun restoreBoard() {
        provision(ProvisionMode.Restore)
    }

    private fun provision(mode: ProvisionMode) {
        if (provisionState.value.running) return
        viewModelScope.launch {
            provisionState.update { it.copy(running = true, outcome = null, confirm = false) }
            val recorded = settings.loraDedicatedBoard.first()
            val result = lora.provisionKnitChannel(mode, recorded?.toIntervals())
            if (result is ProvisionResult.Provisioned) {
                settings.setLoraChannelIndex(result.index)
                when (mode) {
                    ProvisionMode.Dedicate -> settings.rememberDedication(result)
                    ProvisionMode.Restore -> settings.clearLoraDedicatedBoard()
                    ProvisionMode.Rendezvous -> Unit
                }
            }
            provisionState.value = ProvisionState(running = false, outcome = result.toOutcome(mode))
        }
    }

    /**
     * Records the dedication against the bound board's address. Skipped when the board was already dedicated
     * ([ProvisionResult.Provisioned.previous] is null then) — overwriting the stored intervals with nothing
     * would throw away the only copy of what the board looked like before Knit took it over.
     */
    private suspend fun SettingsStore.rememberDedication(result: ProvisionResult.Provisioned) {
        val address = loraDeviceAddress.first() ?: return
        val previous = result.previous ?: return
        setLoraDedicatedBoard(
            DedicatedBoard(
                address = address,
                nodeInfoSecs = previous.nodeInfoSecs,
                positionSecs = previous.positionSecs,
                smartPosition = previous.smartPosition,
                telemetrySecs = previous.telemetrySecs,
            ),
        )
    }

    private fun DedicatedBoard.toIntervals(): BoardIntervals =
        BoardIntervals(
            nodeInfoSecs = nodeInfoSecs,
            positionSecs = positionSecs,
            smartPosition = smartPosition,
            telemetrySecs = telemetrySecs,
        )

    /** Dismisses the last provisioning outcome banner. */
    fun dismissProvisionOutcome() {
        provisionState.update { it.copy(outcome = null) }
    }

    private fun ProvisionResult.toOutcome(mode: ProvisionMode): LoraProvisionOutcome =
        when (this) {
            is ProvisionResult.Provisioned -> {
                when {
                    alreadyPresent -> LoraProvisionOutcome.AlreadyPresent
                    mode == ProvisionMode.Dedicate -> LoraProvisionOutcome.Dedicated
                    mode == ProvisionMode.Restore -> LoraProvisionOutcome.Restored
                    else -> LoraProvisionOutcome.Provisioned
                }
            }

            ProvisionResult.NoFreeSlot -> {
                LoraProvisionOutcome.NoFreeSlot
            }

            is ProvisionResult.Failed -> {
                LoraProvisionOutcome.Failed
            }

            is ProvisionResult.NotReady -> {
                LoraProvisionOutcome.NotReady
            }
        }

    private fun LinkState.toConnState(): LoraConnState =
        when (this) {
            is LinkState.Ready -> LoraConnState.Ready
            LinkState.Connecting, LinkState.Bonding, is LinkState.Handshaking -> LoraConnState.Connecting
            is LinkState.Disconnected -> LoraConnState.Reconnecting
            is LinkState.NeedsPairing, is LinkState.StaleBond -> LoraConnState.NeedsPairing
            LinkState.Unavailable -> LoraConnState.Unavailable
            LinkState.Idle -> LoraConnState.Off
        }

    private data class ProvisionState(
        val running: Boolean = false,
        val outcome: LoraProvisionOutcome? = null,
        val confirm: Boolean = false,
    )

    private companion object {
        private const val PERCENT = 100L

        /** The board's primary channel slot — where a dedicated board carries Knit. */
        private const val PRIMARY_INDEX = 0
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
