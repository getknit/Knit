package app.getknit.knit.ui.lora

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraMeshTransport
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
    val boardName: String? = null,
    val boardAddress: String? = null,
    val channel: Int = 0,
    val connection: LoraConnState = LoraConnState.Off,
    val boardNodeNum: String? = null,
    val snr: Float? = null,
    val rssi: Int? = null,
    val heard: Int = 0,
    val boards: List<BoardOption> = emptyList(),
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
    private val lora: LoraMeshTransport,
    private val boards: BoardDirectory,
) : ViewModel() {
    // Transient, action-driven UI state (the provisioning spinner + its outcome) that isn't in a settings flow.
    private val provisionState = MutableStateFlow(ProvisionState())

    val state: StateFlow<LoraRadioUiState> =
        combine(
            settings.loraEnabled,
            settings.loraDeviceAddress,
            settings.loraChannelIndex,
            lora.status,
            provisionState,
        ) { enabled, address, channel, status, provision ->
            val bonded = runCatching { boards.bonded() }.getOrDefault(emptyList())
            LoraRadioUiState(
                enabled = enabled,
                boardName = bonded.firstOrNull { it.address == address }?.name ?: address,
                boardAddress = address,
                channel = channel,
                connection = status.state.toConnState(),
                boardNodeNum = status.boardNodeNum?.let { "!%08x".format(it.toInt()) },
                snr = status.lastSnr,
                rssi = status.lastRssi,
                heard = status.heard,
                boards = bonded.map { BoardOption(it.address, it.name, it.address == address) },
                provisioning = provision.running,
                provisionOutcome = provision.outcome,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LoraRadioUiState())

    fun onToggle(on: Boolean) {
        viewModelScope.launch { settings.setLoraEnabled(on) }
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
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
