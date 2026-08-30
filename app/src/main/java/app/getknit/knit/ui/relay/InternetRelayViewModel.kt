package app.getknit.knit.ui.relay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.spool.SpoolUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One configured relay, resolved for display: the [url] as stored, a redacted [host] for the row, and
 * whatever the live plane knows about it.
 *
 * [scopeCount] and [carriesPhotos] are null while disconnected rather than zero/false, because "we have
 * not spoken to it yet" is a different statement from "it holds nothing" and from "it refuses photos" —
 * and only the connected form of each is worth showing.
 *
 * [enabled] is this relay's own switch, independent of the master one: it says what the user asked for,
 * not what the plane is doing. The two settle apart for up to one `ScopeSync` reconcile tick after a
 * flip, which is why the row renders intent first.
 */
data class RelayRow(
    val url: String,
    val host: String,
    val enabled: Boolean,
    val connected: Boolean,
    val scopeCount: Int? = null,
    val carriesPhotos: Boolean? = null,
    val lastError: String? = null,
)

data class InternetRelayUiState(
    val enabled: Boolean = false,
    val relays: List<RelayRow> = emptyList(),
)

/**
 * The Internet relays screen: the master switch, the relay list editor with a switch per relay, and each
 * relay's live health.
 *
 * The switch does **not** write straight through on the way up — [onToggle] raises [showConsent] the
 * first time, and only [acceptConsent] turns the plane on. Turning it *off* is immediate and
 * unconditional: a user reaching for that switch wants it off now, and interposing a confirmation
 * would be a dark pattern on the one control that stops data leaving the device.
 */
class InternetRelayViewModel(
    private val settings: SettingsStore,
    relayStatus: RelayStatusRepository,
) : ViewModel() {
    val state: StateFlow<InternetRelayUiState> =
        combine(
            settings.spoolEnabled,
            settings.spoolUrls,
            settings.disabledSpoolUrls,
            relayStatus.statuses,
        ) { enabled, urls, parked, statuses ->
            val byUrl = statuses.associateBy { it.url }
            InternetRelayUiState(
                enabled = enabled,
                relays =
                    urls.sorted().map { url ->
                        val live = byUrl[url]?.takeIf { it.connected }
                        RelayRow(
                            url = url,
                            host = SpoolUrl.host(url),
                            enabled = url !in parked,
                            connected = live != null,
                            scopeCount = live?.scopes?.count { !it.retiring },
                            carriesPhotos = live?.let { it.maxAttachBytes != null },
                            lastError = byUrl[url]?.lastError,
                        )
                    },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InternetRelayUiState())

    private val _showConsent = MutableStateFlow(false)

    /** True while the first-enable disclosure sheet should be on screen. */
    val showConsent: StateFlow<Boolean> = _showConsent.asStateFlow()

    fun onToggle(on: Boolean) {
        viewModelScope.launch {
            when {
                !on -> settings.setSpoolEnabled(false)
                settings.spoolConsented.first() -> settings.setSpoolEnabled(true)
                else -> _showConsent.value = true
            }
        }
    }

    /** Consent given in the sheet: records it and enables the plane in one write. */
    fun acceptConsent() {
        viewModelScope.launch {
            settings.acceptSpoolConsent()
            _showConsent.value = false
        }
    }

    /** Sheet dismissed without accepting — the plane stays off and the disclosure will be shown again. */
    fun dismissConsent() {
        _showConsent.value = false
    }

    /**
     * Whether [url] can be stored. Deliberately the dialer's own rule ([SpoolUrl.isAcceptable]) rather
     * than a second scheme check, so the editor refuses at entry exactly what the dialer would refuse at
     * dial time — otherwise a release user could save a `ws://` relay that then silently never connects.
     */
    fun isValidUrl(url: String): Boolean = SpoolUrl.isAcceptable(url.trim(), BuildConfig.DEBUG)

    fun addRelay(url: String) {
        val trimmed = url.trim()
        if (!isValidUrl(trimmed)) return
        viewModelScope.launch { settings.addSpoolUrl(trimmed) }
    }

    fun removeRelay(url: String) {
        viewModelScope.launch { settings.removeSpoolUrl(url) }
    }

    /**
     * Parks or un-parks one relay. No consent interlock, unlike [onToggle]: the disclosure is about the
     * plane existing at all, and this switch can only ever narrow what an already-consented plane sends —
     * turning a relay on when the master switch is off still opens no socket.
     */
    fun setRelayEnabled(
        url: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch { settings.setSpoolUrlEnabled(url, enabled) }
    }
}
