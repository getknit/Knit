package app.getknit.knit.ui.lora

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.lora.AirtimeSnapshot
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.BoardFilter
import app.getknit.knit.mesh.lora.BoardRef
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraGatewayPolicy
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import app.getknit.knit.mesh.lora.ProvisionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

/** The result of the last "Set up Knit channel" tap, mapped off the internal provision result for the screen. */
enum class LoraProvisionOutcome { Provisioned, AlreadyPresent, NoFreeSlot, Failed, NotReady }

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
            // Slot 0 is the board's unnamed primary, so it reads as a mismatch too — correctly: Knit is never there.
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
                firmware = ready?.board?.firmwareVersion,
                battery = ready?.let { status.battery },
                bridgePassive = status.role == LoraGatewayPolicy.Role.PASSIVE,
                airtimePercent = ready?.let { status.airtime?.let(::airtimePercent) },
                radioConfig = ready?.radio?.let { "${it.region} ${it.modemPreset}" },
                channelName = channelName,
                channelMismatch = ready != null && channelName != KnitChannel.NAME,
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
        if (provisionState.value.running) return
        viewModelScope.launch {
            provisionState.update { it.copy(running = true, outcome = null) }
            val result = lora.provisionKnitChannel()
            if (result is ProvisionResult.Provisioned) settings.setLoraChannelIndex(result.index)
            provisionState.value = ProvisionState(running = false, outcome = result.toOutcome())
        }
    }

    /** Dismisses the last provisioning outcome banner. */
    fun dismissProvisionOutcome() {
        provisionState.update { it.copy(outcome = null) }
    }

    private fun ProvisionResult.toOutcome(): LoraProvisionOutcome =
        when (this) {
            is ProvisionResult.Provisioned -> if (alreadyPresent) LoraProvisionOutcome.AlreadyPresent else LoraProvisionOutcome.Provisioned
            ProvisionResult.NoFreeSlot -> LoraProvisionOutcome.NoFreeSlot
            is ProvisionResult.Failed -> LoraProvisionOutcome.Failed
            is ProvisionResult.NotReady -> LoraProvisionOutcome.NotReady
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
    )

    private companion object {
        private const val PERCENT = 100L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
