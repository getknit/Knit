package app.getknit.knit.data.relay

import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.spool.RelayInvite
import app.getknit.knit.mesh.spool.SpoolUrl
import kotlinx.coroutines.flow.first

/**
 * The one home for what accepting a relay invite *does* (docs/RELAY_INVITE.md §3): turn the plane on,
 * store the relay, un-park it, join its room, dial now. Both doors — a tapped link on the relays screen
 * and a contact card's "Add" on the Add-contact screen — [preview] the same facts onto the same sheet and
 * [apply] the same sequence, for ADR 063's reason: two copies of "what enabling means" would drift, and
 * the one that drifts is the one that skips the disclosure.
 *
 * Every step is idempotent, so a second tap on the same link converges rather than doubling anything.
 * Consent is re-read at apply time rather than trusted from the preview, and is recorded through
 * [SettingsStore.acceptSpoolConsent] alone — the sheet is the master switch's disclosure raised from a
 * second door, not a second consent.
 *
 * [commons] is null while the commons is dark (`BuildConfig.COMMONS` off): the room half of a link is
 * then ignored without comment, and the relay half still applies.
 */
class RelayInviteApplier(
    private val settings: SettingsStore,
    private val commons: CommonsRepository?,
    private val mesh: MeshController,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * What the sheet shows and [apply] acts on. [url] is what will be stored — the invite's, or the entry
     * already in the list when that one is the better credential (see [preview]). A plain class: the
     * secret inside [room] must never reach a log line through a data class's `toString`.
     */
    class Preview(
        val url: String,
        /** `SpoolUrl.host` — the only rendering of the address the sheet ever makes. */
        val host: String,
        /** The URL carries a `?k=` bearer token. */
        val private: Boolean,
        /** The relay (by redacted URL) is already in the list. */
        val alreadyAdded: Boolean,
        /** …and parked; applying turns it back on. */
        val parked: Boolean,
        /** An untokened entry for the same host that the invite's tokened URL will replace. */
        val replaces: String?,
        /** The plane is off; applying turns it on. */
        val planeOff: Boolean,
        /** The disclosure has never been accepted; the sheet carries it and applying records consent. */
        val consentNeeded: Boolean,
        /** The room the link joins, or null when it carries none or this build cannot join one. */
        val room: Room?,
    ) {
        /** Nothing would change: every fact is already true. The sheet still shows, so the tap is answered. */
        val isNoOp: Boolean
            get() = alreadyAdded && !parked && !planeOff && replaces == null && (room == null || room.alreadyJoined)
    }

    class Room(
        val secret: ByteArray,
        val name: String?,
        /** This room (by secret) is already joined at this relay. */
        val alreadyJoined: Boolean,
        /** A different room is joined at this relay — a rotated invite. Applying leaves it first. */
        val replacesRoom: Boolean,
    )

    /**
     * Resolves [invite] against what this device already has. [liveName] is the room name the relay's
     * HELLO advertised, if the relay is connected — used when the link carries none.
     *
     * A relay is matched by its **redacted** URL: a card's hint is untokened and an operator's invite is
     * tokened, and by exact string the two would be separate rows for one host, the untokened one refused
     * `4001` forever. A tokened invite therefore replaces an untokened entry; an untokened invite over a
     * tokened entry is "already added" and keeps the stored credential — never a downgrade.
     */
    suspend fun preview(
        invite: RelayInvite.Parsed.Invite,
        liveName: String? = null,
    ): Preview {
        val stored = settings.spoolUrls.first()
        val parked = settings.disabledSpoolUrls.first()
        // Matching is by exact string, so the link's URL is folded to the stored form first: a link that
        // spells its scheme `WSS://` names the relay a lowercase row already holds.
        val inviteUrl = SpoolUrl.canonical(invite.url)
        val redacted = SpoolUrl.redact(inviteUrl)
        val sameHost = stored.firstOrNull { SpoolUrl.redact(it) == redacted }
        val inviteTokened = inviteUrl != redacted
        val storedTokened = sameHost != null && sameHost != SpoolUrl.redact(sameHost)
        val url = if (sameHost != null && (storedTokened || !inviteTokened)) sameHost else inviteUrl
        val replaces = sameHost?.takeIf { it != url }
        val room =
            invite.secret?.let { secret ->
                val store = commons ?: return@let null
                val bound = store.roots().filter { it.spoolUrl == url || it.spoolUrl == replaces }
                val joined = bound.any { it.secret.contentEquals(secret) }
                Room(
                    secret = secret,
                    name = invite.name ?: liveName,
                    alreadyJoined = joined,
                    replacesRoom = !joined && bound.isNotEmpty(),
                )
            }
        return Preview(
            url = url,
            host = SpoolUrl.host(url),
            private = url != SpoolUrl.redact(url),
            alreadyAdded = sameHost != null && replaces == null,
            parked = url in parked,
            replaces = replaces,
            planeOff = !settings.spoolEnabled.first(),
            consentNeeded = !settings.spoolConsented.first(),
            room = room,
        )
    }

    /** Applies [preview], in order, each step idempotent; ends by nudging the plane so the relay dials now. */
    suspend fun apply(preview: Preview) {
        when {
            !settings.spoolConsented.first() -> settings.acceptSpoolConsent()
            !settings.spoolEnabled.first() -> settings.setSpoolEnabled(true)
        }
        preview.replaces?.let { old -> replaceEntry(old, preview.url) }
        settings.addSpoolUrl(preview.url)
        if (preview.parked) settings.setSpoolUrlEnabled(preview.url, true)
        val store = commons
        val room = preview.room
        if (store != null && room != null) {
            if (room.replacesRoom) store.leaveBoundTo(preview.url)
            // A room already joined is left alone: `join` upserts the whole row, and re-writing it with the
            // link's (possibly absent) name would erase the one the relay advertised at join time.
            if (!room.alreadyJoined) store.join(preview.url, room.secret, room.name, clock())
        }
        mesh.refreshRelays()
    }

    /**
     * Swaps the untokened [old] entry for [new]. A room joined under the old address is re-bound rather
     * than left: the affinity moves with the relay, and the thread and its history stay.
     */
    private suspend fun replaceEntry(
        old: String,
        new: String,
    ) {
        commons?.let { store ->
            for (root in store.roots().filter { it.spoolUrl == old }) {
                store.join(new, root.secret, store.find(root.conversationId)?.name, clock())
            }
        }
        settings.removeSpoolUrl(old)
    }
}
