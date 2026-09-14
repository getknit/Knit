package app.getknit.knit.ui.relay

import app.getknit.knit.mesh.spool.RelayInvite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A process-scoped, single-shot handoff for a relay invite arriving from outside Compose — a tapped
 * `https://getknit.app/r#…` / `knit://r/…` link, or a shared text that contains one. Mirrors
 * [app.getknit.knit.ui.addcontact.ContactCardInbox]: `MainActivity` [offer]s the raw text, `KnitApp`
 * observes [pending] and opens the Internet-relays screen, whose ViewModel [consume]s it and raises the
 * preview sheet. Like a card and unlike a share, an invite that lands before onboarding is **kept** — a
 * fresh install opened from an operator's link is the primary way one arrives, and it waits for the
 * permission gate rather than being dropped.
 */
class RelayInviteInbox {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** Stage link text to preview. Text that isn't invite-shaped is ignored. */
    fun offer(text: String) {
        if (RelayInvite.looksLikeInvite(text)) _pending.value = text
    }

    /** Take the staged text (if any) and clear it, so only the first reader previews it. */
    fun consume(): String? = _pending.getAndUpdate { null }

    fun clear() {
        _pending.value = null
    }
}

/**
 * The relay invite an incoming intent carries, or null. A `VIEW` of one of the link forms carries it as
 * its data; a `SEND` of plain text carries it when the shared text is (or contains) an invite — the
 * Android-idiomatic route on 12+, where an unverified https link opens in the browser rather than the app.
 */
fun relayInviteFrom(
    action: String?,
    dataString: String?,
    sharedText: String?,
): String? =
    when (action) {
        "android.intent.action.VIEW" -> dataString?.takeIf { RelayInvite.looksLikeInvite(it) }
        "android.intent.action.SEND" -> sharedText?.takeIf { RelayInvite.looksLikeInvite(it) }
        else -> null
    }
