@file:Suppress("MagicNumber")

package app.getknit.knit.demo

import android.content.Context
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.withReply
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import org.koin.core.Koin
import java.security.MessageDigest

/**
 * Shared write primitives for the demo builds: they persist a [DemoScenario]'s content through the same
 * repositories the real app uses, so the reactive flows repopulate every screen with no UI changes. Both
 * the static screenshot seeder ([DemoSeeder]) and the animated trailer ([DemoDirector]) drive these — the
 * seeder writes the whole history up front; the director writes it beat-by-beat with `now` timestamps so it
 * animates in live. All writes are idempotent upserts keyed by stable ids.
 *
 * The recurring `60_000L` is the minutes->millis factor for the fixture offsets, so [MagicNumber] is
 * suppressed for the whole file.
 */
class DemoWriter(
    koin: Koin,
    private val scenario: DemoScenario,
    private val me: String,
    // Every message in the scenario, keyed by id — so a reply can resolve the message it quotes (for the
    // denormalized author/snippet, mirroring what a real inbound reply carries).
    private val msgById: Map<String, DemoMsg>,
) {
    private val peers = koin.get<PeerRepository>()
    private val messages = koin.get<MessageRepository>()
    private val reactions = koin.get<ReactionRepository>()
    private val receipts = koin.get<MessageReceiptRepository>()
    private val groups = koin.get<GroupRepository>()
    private val settings = koin.get<SettingsStore>()
    private val blobs = koin.get<BlobRepository>()
    private val context = koin.get<Context>()

    /**
     * Sets the local profile (name/status/avatar), pins self as a peer row, and upserts the scenario cast.
     * The self peer row feeds the self-referential UI (the group details "You" row) that resolves names
     * against the peer table; self is filtered out of the contact/diagnostics lists, so it's harmless there.
     */
    suspend fun seedProfileAndPeers(now: Long) {
        val myAvatar = avatar("me")
        settings.setDisplayName(scenario.meName)
        settings.setStatus(scenario.meStatus)
        myAvatar?.let { settings.setOwnAvatarHash(it) }
        peers.upsert(
            PeerEntity(nodeId = me, name = scenario.meName, status = scenario.meStatus, avatarHash = myAvatar, updatedAt = now),
        )
        scenario.peers.forEach { p ->
            peers.upsert(
                PeerEntity(
                    nodeId = nodeId(p.slot),
                    name = p.name,
                    status = p.status,
                    avatarHash = avatar(nodeId(p.slot)),
                    // A verified peer needs a pinned key + the out-of-band-confirmed flag for the badge.
                    pubKey = if (p.verified) "demo" else null,
                    verified = p.verified,
                    updatedAt = now,
                ),
            )
        }
    }

    /** Writes the full Nearby history + a read watermark leaving the latest message unread (a "1" badge). */
    suspend fun seedNearby(now: Long) {
        scenario.nearby.forEach { write(it, Conversations.NEARBY, dmPeer = null, now) }
        settings.setLastReadAt(Conversations.NEARBY, now - scenario.nearbyReadMinsAgo * 60_000L)
    }

    /** Writes each DM thread; a read thread gets a watermark (no badge), an unread one leaves a count. */
    suspend fun seedDms(now: Long) {
        scenario.dms.forEach { thread ->
            val peer = nodeId(thread.peer)
            thread.messages.forEach { write(it, conversationId = peer, dmPeer = peer, now) }
            if (thread.read) settings.setLastReadAt(peer, now)
        }
    }

    /**
     * Upserts [group] and writes its history; returns the group id. [read] false leaves the thread
     * without a read watermark, which is what a group the user has not opened — a **request** — looks
     * like; the accepted group passes true, as it always did.
     */
    suspend fun seedGroup(
        group: DemoGroup,
        now: Long,
        read: Boolean = true,
    ): String {
        val members = group.members.map { nodeId(it) }
        val groupId = Conversations.groupIdFor(members)
        val opener = group.messages.first() // lists are oldest-first -> first = creation
        groups.upsert(
            GroupEntity(
                groupId = groupId,
                name = group.name,
                members = GroupMembersStore.encode(members),
                createdBy = nodeId(opener.from),
                createdAt = now - opener.minsAgo * 60_000L,
                nameUpdatedAt = now - opener.minsAgo * 60_000L,
            ),
        )
        group.messages.forEach { write(it, conversationId = groupId, dmPeer = null, now) }
        if (read) settings.setLastReadAt(groupId, now)
        return groupId
    }

    /**
     * Writes the scenario's stranger threads — the DM requests and the group a stranger added us to —
     * **without** accepting any of them, so `Conversations.isAccepted` keeps them out of the chat list and
     * in the Message Requests inbox (whose badge then appears on the chat list). Deliberately not folded
     * into [seedDms]: the difference between a chat and a request is exactly the absence of an accept, and
     * a single list would make that absence an easy thing to lose.
     */
    suspend fun seedRequests(now: Long) {
        scenario.requests.forEach { thread ->
            val peer = nodeId(thread.peer)
            thread.messages.forEach { write(it, conversationId = peer, dmPeer = peer, now) }
            if (thread.read) settings.setLastReadAt(peer, now)
        }
        scenario.requestGroup?.let { seedGroup(it, now, read = false) }
    }

    /** Blocks the scenario's blocked slots, so "Blocked users" has rows instead of its empty state. */
    suspend fun seedBlocked() {
        scenario.blocked.forEach { settings.block(nodeId(it), deviceTag = null) }
    }

    /**
     * Writes one [DemoMsg] (message row + any inline reactions + any per-member delivery receipts). For a
     * DM, [dmPeer] is the other party so the
     * recipient is set per direction; for the room/group it's null. [received] doubles as the delivery tick and
     * is only meaningful for our own outbound messages, so it tracks "is this mine". A [DemoMsg.image] is
     * ingested as a plaintext blob (JPEG scene photo or animated WebP) and pinned via [MessageEntity.attachmentHash].
     *
     * [DemoMsg.via] is written to `receivedVia` for every row and carried onto the seeded receipts, so the
     * plane glyphs (globe / board) render on the bubble, on the ✓✓ tick and on the per-recipient rows of
     * "Message info" from one field.
     */
    suspend fun write(
        m: DemoMsg,
        conversationId: String,
        dmPeer: String?,
        now: Long,
    ) {
        val fromMe = m.from == Slot.ME
        val voice = m.voiceSeconds?.let { voiceBlob(it) }
        val imageHash = if (voice == null) m.image?.let { imageBlob(it, m.imageMime) } else null
        messages.save(
            MessageEntity(
                id = m.id,
                senderId = nodeId(m.from),
                recipientId = dmPeer?.let { if (fromMe) it else me },
                conversationId = conversationId,
                body = m.body,
                sentAt = now - m.minsAgo * 60_000L,
                // Seeded rather than observed, for the same reason the voice metadata below is: a seeded row
                // never arrives, so write what the real inbound path would have written. Half a minute after
                // the send — a plausible one-hop lag that can't land in the future for any minsAgo >= 1.
                arrivedAt = if (fromMe) null else now - m.minsAgo * 60_000L + 30_000L,
                received = fromMe,
                // The plane, in the same place the real path writes it: an inbound row is its own proof,
                // one of ours learns it from the receipt that flipped `received`.
                receivedVia = m.via.code,
                mentions =
                    MentionStore.encode(
                        if (m.mentionsMe) listOf(Mention(me, scenario.meName)) else emptyList(),
                    ),
                attachmentHash = voice?.hash ?: imageHash,
                // Plaintext blob (attachmentKey stays null) → BlobFetcher decodes the bytes directly.
                attachmentMime =
                    when {
                        voice != null -> VoiceAudio.MIME
                        imageHash != null -> m.imageMime
                        else -> null
                    },
                // Seeded rather than derived: the derivation runs when a blob *arrives*, and a seeded row
                // never arrives. Writing them here is what the real inbound path would have written.
                voiceDurationMs = voice?.durationMs,
                voicePeaks = voice?.peaks,
            ).withReply(replyRefFor(m)),
        )
        m.reactions.forEach { r ->
            reactions.apply(ReactionEntity(m.id, nodeId(r.reactor), r.emoji, now - r.minsAgo * 60_000L))
        }
        // A receipt lands after the message it acks; a minute is enough to keep the ordering readable.
        m.deliveredTo.forEach { slot ->
            receipts.record(m.id, nodeId(slot), m.via, now - (m.minsAgo - 1) * 60_000L)
        }
    }

    /** Applies a single reaction now — used by the director to pop a reaction onto an already-written message. */
    suspend fun react(
        messageId: String,
        reactor: Slot,
        emoji: String,
        ts: Long,
    ) = reactions.apply(ReactionEntity(messageId, nodeId(reactor), emoji, ts))

    /** Resolves a [Slot] to its node id ([Slot.ME] is this device's runtime id). */
    fun nodeId(slot: Slot): String =
        when (slot) {
            Slot.ME -> me
            Slot.SAM -> DemoSeeder.SAM
            Slot.DANI -> DemoSeeder.DANI
            Slot.THEO -> DemoSeeder.THEO
            Slot.PRIYA -> DemoSeeder.PRIYA
            Slot.JONAS -> DemoSeeder.JONAS
            Slot.LENA -> DemoSeeder.LENA
            Slot.JONAS_TWO -> DemoSeeder.JONAS_TWO
            Slot.RIVER -> DemoSeeder.RIVER
            Slot.NOAH -> DemoSeeder.NOAH
            Slot.MARLO -> DemoSeeder.MARLO
        }

    /** The display name of a [Slot]: the local profile name for [Slot.ME], else the peer's scenario name. */
    private fun displayName(slot: Slot): String =
        if (slot == Slot.ME) scenario.meName else scenario.peers.firstOrNull { it.slot == slot }?.name ?: nodeId(slot)

    /**
     * The denormalized [ReplyRef] for [m] when it quotes another scenario message (by [DemoMsg.replyTo] → the
     * quoted [DemoMsg.id]), else null. The snippet/author are copied onto the row exactly like a real inbound
     * reply, so the quote renders even though the demo never ran the mesh.
     */
    private fun replyRefFor(m: DemoMsg): ReplyRef? =
        m.replyTo?.let { refId ->
            msgById[refId]?.let { ref ->
                ReplyRef(
                    messageId = ref.id,
                    authorId = nodeId(ref.from),
                    author = displayName(ref.from),
                    snippet = ref.body.take(120),
                    hasAttachment = ref.image != null,
                )
            }
        }

    /**
     * Loads the bundled demo avatar for [key] (a node id, or "me") from the active theme's asset folder,
     * stores it as a content blob, and returns its hash — or null if the asset is missing, so the avatar
     * falls back to a letter circle. Content-addressed like the real avatar pipeline.
     */
    private suspend fun avatar(key: String): String? =
        runCatching {
            val bytes = context.assets.open("demo/avatars/${scenario.theme}/$key.jpg").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            blobs.insert(hash, "image/jpeg", bytes)
            hash
        }.getOrNull()

    /**
     * Loads bundled demo image [name] (base name of `demo/images/<theme>/<name>.<ext>`, ext derived from
     * [mime] — `.webp` for an animated WebP, else `.jpg`), stores it as a plaintext content blob, and returns
     * its hash to pin on a message's attachment — or null if the asset is missing (the message then renders
     * text-only, exactly as before).
     */
    private suspend fun imageBlob(
        name: String,
        mime: String,
    ): String? =
        runCatching {
            val ext = if (mime == "image/webp") "webp" else "jpg"
            val bytes = context.assets.open("demo/images/${scenario.theme}/$name.$ext").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            blobs.insert(hash, mime, bytes)
            hash
        }.getOrNull()

    /** A seeded voice note's blob hash plus the description a real arrival would have derived from it. */
    private class SeededVoice(
        val hash: String,
        val durationMs: Int,
        val peaks: String,
    )

    /**
     * Synthesizes a [seconds]-long **silent** ADTS stream, stores it as a plaintext blob, and returns it
     * with a plausible speech waveform. Silent because the point is to audit the bubble — its layout, its
     * contrast, its TalkBack description — not to play audio; bundling a real recording would add a binary
     * asset to the repo for a control the suites never press.
     *
     * The stream is genuinely well-formed, so `VoiceAudio.durationMs` reads exactly [seconds] back off it
     * and the seeded row is consistent with what the real pipeline would have produced.
     */
    private suspend fun voiceBlob(seconds: Int): SeededVoice? =
        runCatching {
            val frames = seconds * SEEDED_VOICE_SAMPLE_RATE / SEEDED_VOICE_SAMPLES_PER_FRAME
            val bytes = ByteArray(frames * SEEDED_VOICE_FRAME_BYTES)
            for (f in 0 until frames) {
                val o = f * SEEDED_VOICE_FRAME_BYTES
                bytes[o] = 0xFF.toByte()
                bytes[o + 1] = 0xF1.toByte()
                // profile AAC-LC, sampling_frequency_index 7 (22.05 kHz), 1 channel.
                bytes[o + 2] = 0x5C.toByte()
                bytes[o + 3] = (0x40 or ((SEEDED_VOICE_FRAME_BYTES shr 11) and 0x03)).toByte()
                bytes[o + 4] = ((SEEDED_VOICE_FRAME_BYTES shr 3) and 0xFF).toByte()
                bytes[o + 5] = (((SEEDED_VOICE_FRAME_BYTES and 0x07) shl 5) or 0x1F).toByte()
                bytes[o + 6] = 0xFC.toByte()
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            blobs.insert(hash, VoiceAudio.MIME, bytes)
            val bars =
                ByteArray(VoiceAudio.PEAK_COUNT) { i ->
                    // A repeating syllable-ish envelope; deterministic so captures are reproducible.
                    val phase = kotlin.math.abs(kotlin.math.sin(i / 4.0))
                    (60 + phase * 180).toInt().toByte()
                }
            SeededVoice(
                hash = hash,
                durationMs = VoiceAudio.durationMs(bytes) ?: (seconds * 1000),
                peaks = VoiceAudio.encodePeaks(bars),
            )
        }.getOrNull()

    private companion object {
        // A seeded voice note's synthetic ADTS stream: mono 22.05 kHz AAC-LC, the recorder's own format, so
        // VoiceAudio reads the same duration off it that it would off a real recording.
        const val SEEDED_VOICE_SAMPLE_RATE = 22_050
        const val SEEDED_VOICE_SAMPLES_PER_FRAME = 1024
        const val SEEDED_VOICE_FRAME_BYTES = 64
    }
}
