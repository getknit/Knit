package app.getknit.knit.ui.relay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.relay.RelayInviteApplier
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.spool.CommonsInvite
import app.getknit.knit.mesh.spool.RelayInvite
import app.getknit.knit.mesh.spool.SpoolUrl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
    // The commons this relay runs (§7.4), once its HELLO has said so: the room's advertised name and, when
    // this device has joined it, the thread it lives in. Null while disconnected or when the relay runs none.
    val commons: RelayCommons? = null,
)

/** A relay's commons as the row shows it: the operator's name for it, and our thread id when joined. */
data class RelayCommons(
    val name: String?,
    val joinedId: String?,
)

data class InternetRelayUiState(
    val enabled: Boolean = false,
    val relays: List<RelayRow> = emptyList(),
)

/** One-shot outcomes for the screen's snackbar and share sheet. */
sealed interface InternetRelayEvent {
    /** A freshly minted relay invite for the share sheet. */
    data class ShareInvite(
        val url: String,
    ) : InternetRelayEvent

    /** A freshly minted relay invite for the clipboard. */
    data class CopyInvite(
        val url: String,
    ) : InternetRelayEvent

    /** Text that arrived as an invite but does not parse as one this build can use. */
    data class InviteRefused(
        val reason: RelayInvite.Reason,
    ) : InternetRelayEvent

    /** The invite was applied; [host] is the relay's redacted host for the confirmation. */
    data class InviteApplied(
        val host: String,
    ) : InternetRelayEvent
}

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
    // The joined commons (§7.4) and the plane to nudge when one is joined or left. Nullable and last so the
    // screen tests' two-argument rig keeps compiling; production passes a store only while
    // `BuildConfig.COMMONS` is on, and without one the row shows no room and the verbs are no-ops.
    private val commons: CommonsRepository? = null,
    private val mesh: MeshController? = null,
    // The relay invite's two halves (docs/RELAY_INVITE.md): the link handoff a tapped `getknit.app/r` link
    // lands in, and the one apply sequence the sheet confirms. Observed rather than read once, so a second
    // link arriving while the screen is up (`launchSingleTop`) still raises the sheet.
    private val inbox: RelayInviteInbox? = null,
    private val applier: RelayInviteApplier? = null,
) : ViewModel() {
    val state: StateFlow<InternetRelayUiState> =
        combine(
            settings.spoolEnabled,
            settings.spoolUrls,
            settings.disabledSpoolUrls,
            relayStatus.statuses,
            commons?.observeAll() ?: flowOf(emptyList()),
        ) { enabled, urls, parked, statuses, rooms ->
            val byUrl = statuses.associateBy { it.url }
            val joinedByUrl = rooms.associateBy { it.spoolUrl }
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
                            // Drawn only when this build can join one: a Join that could not join is worse
                            // than no line, so a store-less build (`BuildConfig.COMMONS` off) shows no room.
                            commons =
                                if (commons != null) {
                                    live?.commons?.let { RelayCommons(name = it.name, joinedId = joinedByUrl[url]?.conversationId) }
                                } else {
                                    null
                                },
                        )
                    },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InternetRelayUiState())

    private val _showConsent = MutableStateFlow(false)

    /** True while the first-enable disclosure sheet should be on screen. */
    val showConsent: StateFlow<Boolean> = _showConsent.asStateFlow()

    private val _invitePreview = MutableStateFlow<RelayInviteApplier.Preview?>(null)

    /** The invite awaiting confirmation on the sheet, or null while none is. */
    val invitePreview: StateFlow<RelayInviteApplier.Preview?> = _invitePreview.asStateFlow()

    private val _events = MutableSharedFlow<InternetRelayEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<InternetRelayEvent> = _events.asSharedFlow()

    init {
        if (inbox != null) {
            viewModelScope.launch {
                inbox.pending.collect { text ->
                    if (text == null) return@collect
                    inbox.consume()
                    previewInvite(text)
                }
            }
        }
    }

    /**
     * Parses [text] as a relay invite and raises the sheet for it, or reports why it cannot be one. The
     * scheme policy is the editor's own (`BuildConfig.DEBUG` allows `ws://`), so a link is refused at entry
     * exactly where a typed URL would be.
     */
    fun previewInvite(text: String) {
        val applier = applier ?: return
        when (val parsed = RelayInvite.parse(text, BuildConfig.DEBUG)) {
            is RelayInvite.Parsed.Invalid -> {
                _events.tryEmit(InternetRelayEvent.InviteRefused(parsed.reason))
            }

            is RelayInvite.Parsed.Invite -> {
                viewModelScope.launch {
                    val liveName =
                        state.value.relays
                            .firstOrNull { it.url == parsed.url }
                            ?.commons
                            ?.name
                    _invitePreview.value = applier.preview(parsed, liveName)
                }
            }
        }
    }

    /** The sheet's confirmation: applies the previewed invite and drops the sheet. */
    fun confirmInvite() {
        val applier = applier ?: return
        val preview = _invitePreview.value ?: return
        viewModelScope.launch {
            applier.apply(preview)
            _invitePreview.value = null
            _events.tryEmit(InternetRelayEvent.InviteApplied(preview.host))
        }
    }

    /** The sheet dismissed: nothing is stored, and the link is gone — the user can tap it again. */
    fun dismissInvite() {
        _invitePreview.value = null
    }

    /**
     * Mints the invite for [url] as stored — token included, that is the point — plus the room this device
     * has joined there, if any (docs/RELAY_INVITE.md §4), for the share sheet.
     */
    fun shareInvite(url: String) {
        viewModelScope.launch { _events.tryEmit(InternetRelayEvent.ShareInvite(mintInvite(url))) }
    }

    /** As [shareInvite], for the clipboard. */
    fun copyInvite(url: String) {
        viewModelScope.launch { _events.tryEmit(InternetRelayEvent.CopyInvite(mintInvite(url))) }
    }

    private suspend fun mintInvite(url: String): String {
        val room = commons?.observeAll()?.first()?.firstOrNull { it.spoolUrl == url }
        return RelayInvite.url(RelayInvite.mint(url, room?.secret, room?.name))
    }

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
        viewModelScope.launch {
            settings.addSpoolUrl(trimmed)
            // Dial now rather than at the next reconcile tick, so the row the user just added goes green
            // while they are still looking at it.
            mesh?.refreshRelays()
        }
    }

    fun removeRelay(url: String) {
        viewModelScope.launch {
            settings.removeSpoolUrl(url)
            // A room without its relay is nothing: the row, its key and its history go with the relay.
            commons?.leaveBoundTo(url)
            mesh?.refreshRelays()
        }
    }

    /** Whether [invite] is a commons invite (`knit-commons:v1:…`) — the join field's validator. */
    fun isValidInvite(invite: String): Boolean = CommonsInvite.looksLikeInvite(invite)

    /**
     * Joins the commons [invite] unlocks at [url], titled with what the relay advertised. Idempotent on the
     * secret. The plane is nudged so the room is subscribed now, not at the next reconcile tick.
     */
    fun joinCommons(
        url: String,
        invite: String,
    ) {
        val secret = CommonsInvite.decode(invite) ?: return
        val store = commons ?: return
        viewModelScope.launch {
            val name =
                state.value.relays
                    .firstOrNull { it.url == url }
                    ?.commons
                    ?.name
            store.join(url, secret, name, System.currentTimeMillis())
            mesh?.refreshRelays()
        }
    }

    /** Leaves the commons [conversationId]: the room, its key and its history go; its members stay contacts. */
    fun leaveCommons(conversationId: String) {
        val store = commons ?: return
        viewModelScope.launch {
            store.leave(conversationId)
            mesh?.refreshRelays()
        }
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
