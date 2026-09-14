package app.getknit.knit.ui.addcontact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.BuildConfig
import app.getknit.knit.contacts.ContactCards
import app.getknit.knit.contacts.ContactImporter
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.relay.RelayInviteApplier
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.crypto.ContactCard
import app.getknit.knit.mesh.crypto.VerifyPayload
import app.getknit.knit.mesh.spool.RelayInvite
import app.getknit.knit.mesh.spool.SpoolUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Add-contact screen's link half shows. */
sealed interface AddContactUiState {
    /** Waiting for a link to be typed, pasted, or handed in. */
    data object Idle : AddContactUiState

    /** A parsed card, previewed before the user commits. */
    data class Preview(
        val ready: ContactImporter.Preview.Ready,
    ) : AddContactUiState

    data object Importing : AddContactUiState
}

/** Outcome of scanning an identity QR — the in-person half of the screen, shown once as a snackbar. */
enum class VerifyResult {
    /** The code is a valid, self-consistent Knit identity; its key is now pinned and marked verified. */
    VERIFIED,

    /** The code's key differs from the one already pinned for that node id (impersonation / collision). */
    MISMATCH,

    /** The scanned code is our own identity. */
    SELF,

    /** Not a Knit identity code, or its key doesn't derive to its claimed node id (forged / corrupt). */
    INVALID,
}

/** One-shot outcomes for the screen's snackbar / navigation / share sheet. */
sealed interface AddContactEvent {
    data class Imported(
        val nodeId: String,
    ) : AddContactEvent

    data object Self : AddContactEvent

    data class Mismatch(
        val displayName: String,
    ) : AddContactEvent

    data object Invalid : AddContactEvent

    /** A scanned identity code's outcome (the scan path pins + verifies; the link path never does). */
    data class Scanned(
        val result: VerifyResult,
    ) : AddContactEvent

    /** A freshly minted contact link for the share sheet. */
    data class ShareLink(
        val url: String,
    ) : AddContactEvent

    /** A freshly minted contact link for the clipboard. */
    data class CopyLink(
        val url: String,
    ) : AddContactEvent

    /** A card's relay hint was added through the invite sheet; [host] for the confirmation. */
    data class RelayAdded(
        val host: String,
    ) : AddContactEvent

    /** A card's relay hint names a relay this build cannot dial (the editor's own scheme rule). */
    data object RelayRefused : AddContactEvent
}

/**
 * Backs the merged Add-contact screen (docs/CONTACT_CARD.md) — every way one person becomes another's
 * contact, in one place:
 *  - **in person**, by showing this device's identity QR ([myQrPayload]) or scanning theirs ([onScanned]),
 *    which pins the peer's key *and* marks it verified; and
 *  - **at a distance**, by handing out this device's contact link ([shareLink] / [copyLink]) or importing
 *    someone else's — arriving from the [ContactCardInbox] (a tapped `getknit.app/c` link, or a shared
 *    text) or pasted into the field, then [lookup]ed for a preview and [confirm]ed through
 *    [ContactImporter], which pins + accepts but deliberately never verifies.
 *
 * Pinning on both paths mirrors [app.getknit.knit.mesh.InboundPipeline]'s `handleProfile` invariants: the
 * code must be self-certifying (its key derives back to its node id) and a peer's pinned key is immutable,
 * so a differing key for a known node id is refused rather than swapped in.
 *
 * Holds no `Context` — every string is resolved by the screen from state — so it drives under plain JVM
 * tests. The inbox is *observed*, not read once, so a second link arriving while the screen is up
 * (`launchSingleTop`) still loads.
 */
class AddContactViewModel(
    private val inbox: ContactCardInbox,
    private val importer: ContactImporter,
    private val peers: PeerRepository,
    private val identity: Identity,
    private val cards: ContactCards,
    // The relay invite sheet's apply sequence (docs/RELAY_INVITE.md), for a card's relay hint's "Add".
    private val relays: RelayInviteApplier? = null,
) : ViewModel() {
    val input = MutableStateFlow("")

    private val _state = MutableStateFlow<AddContactUiState>(AddContactUiState.Idle)
    val state: StateFlow<AddContactUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AddContactEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AddContactEvent> = _events.asSharedFlow()

    /** This device's identity code, for a peer standing next to us to scan. */
    private val _myQrPayload = MutableStateFlow<String?>(null)
    val myQrPayload: StateFlow<String?> = _myQrPayload.asStateFlow()

    private val _relayInvite = MutableStateFlow<RelayInviteApplier.Preview?>(null)

    /** A relay hint awaiting confirmation on the invite sheet, or null while none is. */
    val relayInvite: StateFlow<RelayInviteApplier.Preview?> = _relayInvite.asStateFlow()

    init {
        viewModelScope.launch {
            inbox.pending.collect { text ->
                if (text == null) return@collect
                inbox.consume()
                input.value = text
                lookup()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            _myQrPayload.value = VerifyPayload.encode(identity.nodeId(), identity.publicKeyBundle())
        }
    }

    fun setInput(text: String) {
        input.value = text
        if (_state.value is AddContactUiState.Preview) _state.value = AddContactUiState.Idle
    }

    /** Parses and previews whatever is in the field. */
    fun lookup() {
        viewModelScope.launch {
            when (val preview = importer.preview(ContactCard.parse(input.value))) {
                is ContactImporter.Preview.Ready -> _state.value = AddContactUiState.Preview(preview)
                is ContactImporter.Preview.Self -> _events.tryEmit(AddContactEvent.Self)
                is ContactImporter.Preview.Mismatch -> _events.tryEmit(AddContactEvent.Mismatch(preview.displayName))
                is ContactImporter.Preview.Invalid -> _events.tryEmit(AddContactEvent.Invalid)
            }
        }
    }

    /** Imports the previewed card; [unblock] lifts an existing block first. */
    fun confirm(unblock: Boolean = false) {
        val preview = (_state.value as? AddContactUiState.Preview)?.ready ?: return
        _state.value = AddContactUiState.Importing
        viewModelScope.launch {
            importer.import(preview, unblock)
            _events.tryEmit(AddContactEvent.Imported(preview.nodeId))
            _state.value = AddContactUiState.Idle
        }
    }

    /**
     * A card's relay hint tapped: raises the invite sheet for [url] the way a tapped link would, so adding
     * it from here shows the same host, the same cost and, the first time, the same disclosure. A card's
     * hint is untokened by construction (`ContactCards.mint`), so there is no room half.
     */
    fun previewRelay(url: String) {
        val relays = relays ?: return
        if (!SpoolUrl.isAcceptable(url, BuildConfig.DEBUG)) {
            _events.tryEmit(AddContactEvent.RelayRefused)
            return
        }
        viewModelScope.launch { _relayInvite.value = relays.preview(RelayInvite.Parsed.Invite(url, secret = null, name = null)) }
    }

    /** The invite sheet confirmed: applies it, drops the sheet, and re-previews the card so the hint updates. */
    fun confirmRelay() {
        val relays = relays ?: return
        val preview = _relayInvite.value ?: return
        viewModelScope.launch {
            relays.apply(preview)
            _relayInvite.value = null
            _events.tryEmit(AddContactEvent.RelayAdded(preview.host))
            if (_state.value is AddContactUiState.Preview) lookup()
        }
    }

    fun dismissRelay() {
        _relayInvite.value = null
    }

    /** Mints this device's contact link for the share sheet — the QR's job at a distance. */
    fun shareLink() {
        viewModelScope.launch { _events.tryEmit(AddContactEvent.ShareLink(cards.mint().url)) }
    }

    /** Mints this device's contact link for the clipboard. */
    fun copyLink() {
        viewModelScope.launch { _events.tryEmit(AddContactEvent.CopyLink(cards.mint().url)) }
    }

    /**
     * Parses and validates a scanned identity code, then pins + verifies the peer. Emits the outcome as a
     * one-shot [AddContactEvent.Scanned] for the screen's snackbar.
     */
    fun onScanned(payload: String) {
        // The codec applies the self-certifying check (a code whose key doesn't derive back to its claimed
        // node id is refused, the same check handleProfile applies to an advertised key) and accepts both
        // the legacy `knit-id:v1` code and a contact-link QR (docs/CONTACT_CARD.md).
        val parsed = ContactCard.parse(payload) as? ContactCard.Parsed.Card
        if (parsed == null) {
            _events.tryEmit(AddContactEvent.Scanned(VerifyResult.INVALID))
            return
        }
        val bundle = parsed.bundle
        viewModelScope.launch {
            if (parsed.nodeId == identity.nodeId()) {
                _events.tryEmit(AddContactEvent.Scanned(VerifyResult.SELF))
                return@launch
            }
            val existing = peers.find(parsed.nodeId)
            val result =
                when (existing?.pubKey) {
                    // No key pinned yet (a brand-new contact, or a bare avatar-only row): pin it and mark
                    // verified. Leave updatedAt at its default 0 so a later real profile frame still wins
                    // handleProfile's last-writer-wins check and fills in the name/status/avatar this code
                    // doesn't carry.
                    null -> {
                        peers.upsert(
                            (existing ?: PeerEntity(parsed.nodeId)).copy(
                                pubKey = bundle,
                                verified = true,
                            ),
                        )
                        VerifyResult.VERIFIED
                    }

                    // Same pinned key: just record the out-of-band verification.
                    bundle -> {
                        peers.setVerified(parsed.nodeId, true)
                        VerifyResult.VERIFIED
                    }

                    // A different key for this node id would require a hash collision (impossible for a
                    // self-consistent code, checked above) — refuse rather than overwrite the pin.
                    else -> {
                        VerifyResult.MISMATCH
                    }
                }
            _events.tryEmit(AddContactEvent.Scanned(result))
        }
    }
}
