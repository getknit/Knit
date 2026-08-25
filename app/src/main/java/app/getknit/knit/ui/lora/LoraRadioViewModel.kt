package app.getknit.knit.ui.lora

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraMeshTransport
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A bonded board the user can bind the LoRa plane to. */
data class BoardOption(
    val address: String,
    val name: String,
    val selected: Boolean,
)

/** How the LoRa link is doing, as a UI-facing enum decoupled from the internal link state. */
enum class LoraConnState { Off, Connecting, Ready, Reconnecting, NeedsPairing, Unavailable }

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
    val state: StateFlow<LoraRadioUiState> =
        combine(
            settings.loraEnabled,
            settings.loraDeviceAddress,
            settings.loraChannelIndex,
            lora.status,
        ) { enabled, address, channel, status ->
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

    private fun LinkState.toConnState(): LoraConnState =
        when (this) {
            is LinkState.Ready -> LoraConnState.Ready
            LinkState.Connecting, LinkState.Bonding, is LinkState.Handshaking -> LoraConnState.Connecting
            is LinkState.Disconnected -> LoraConnState.Reconnecting
            is LinkState.NeedsPairing, is LinkState.StaleBond -> LoraConnState.NeedsPairing
            LinkState.Unavailable -> LoraConnState.Unavailable
            LinkState.Idle -> LoraConnState.Off
        }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
