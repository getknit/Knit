package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.GroupKeyPayload
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfilePayload
import app.getknit.knit.mesh.protocol.ReactionPayload
import app.getknit.knit.mesh.protocol.ReplyRef
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * The plaintext payload of an encrypted DM/group message — everything that must stay private. It is
 * CBOR-serialized and AES-256-GCM-encrypted into [app.getknit.knit.mesh.protocol.EncEnvelope.ct]; the
 * cleartext [app.getknit.knit.mesh.protocol.RelayEnvelope] only keeps the routing metadata (id, sender,
 * recipientId/group) that relays need.
 *
 * [v] versions the *decrypted plaintext schema* — deliberately independent of
 * [app.getknit.knit.mesh.protocol.EncEnvelope.v] (the crypto scheme), so the two layers move
 * separately. It rides inside the authenticated ciphertext (it isn't on the wire). An unsupported
 * version is dropped on delivery (see `MeshManager.decrypt`).
 *
 * [attachmentKey] is the base64 AES key for the (separately encrypted, content-addressed by ciphertext
 * hash) image blob referenced by [attachmentHash]; null for text-only messages.
 *
 * Note [v] is elided on the wire while it equals [VERSION] (`encodeDefaults = false`), so it cannot
 * discriminate a second layout. The compact layout crypto scheme v3 carries — the same facts with integer
 * keys and raw ids, [MessageContentV2] — is therefore discriminated by `EncEnvelope.v` and keeps a
 * reserved label-0 version of its own; this class stays the domain object both layouts convert to.
 */
@Serializable
data class MessageContent(
    val v: Int = VERSION,
    val body: String,
    val mentions: List<Mention> = emptyList(),
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    val attachmentKey: String? = null,
    // Quoted-reply reference for an encrypted DM/group, carried here (inside the ciphertext) so the
    // quoted author + snippet stay private — never on the cleartext [ChatContent.replyTo] for these.
    // Relies, like every field here, on cryptoCbor's `encodeDefaults = false` to stay off the wire when
    // null; do not enable encodeDefaults or every DM frame inflates with an empty reply.
    val replyTo: ReplyRef? = null,
    // Control marker for ratchet session management and sealed metadata (additive;
    // [CTL_SESSION_RESET], [CTL_GROUP_KEY], [CTL_GROUP_KEY_REQ], [CTL_GROUP_KEY_ACK],
    // [CTL_RECEIPT], [CTL_REACTION], [CTL_PROFILE]). A non-null value means this frame is machinery, not
    // conversation: it is never persisted as a message, never notified, never acked-as-a-message —
    // see docs/FORWARD_SECRECY_RATCHET.md §7. Inside the ciphertext deliberately: a relay cannot
    // distinguish machinery from an ordinary DM. An unknown value is consumed as a silent no-op,
    // which is what lets new ctl values ship additively.
    val ctl: Int? = null,
    // Group-key payload for the CTL_GROUP_KEY* values (additive; docs/GROUP_FORWARD_SECRECY.md §3).
    val gk: GroupKeyPayload? = null,
    // Acked frame id for [CTL_RECEIPT] (additive; docs/ENCRYPTED_RECEIPTS_REACTIONS.md).
    val ack: String? = null,
    // Reaction payload for [CTL_REACTION] (additive; docs/ENCRYPTED_RECEIPTS_REACTIONS.md).
    val rp: ReactionPayload? = null,
    // Profile payload for [CTL_PROFILE] (additive; docs/SEALED_PROFILE_UPDATES.md).
    val pr: ProfilePayload? = null,
    // Batched acked frame ids for [CTL_RECEIPT] (additive; docs/ENCRYPTED_RECEIPTS_REACTIONS.md §2).
    // Single-ack ticks keep [ack]; a batch is one custody-escalated group tick covering every id.
    val acks: List<String>? = null,
) {
    @OptIn(ExperimentalSerializationApi::class)
    fun encode(): ByteArray = cryptoCbor.encodeToByteArray(this)

    /** Whether this build understands this content schema version. */
    fun isSupported(): Boolean = v <= MAX_SUPPORTED

    companion object {
        /** Current plaintext-content schema version this build originates. */
        const val VERSION = 1

        /** Highest content schema version this build can read; a higher [v] is dropped on delivery. */
        const val MAX_SUPPORTED = 1

        /** [ctl]: this frame requests a ratchet session reset (carries a fresh init; not a message). */
        const val CTL_SESSION_RESET = 1

        /** [ctl]: [gk] distributes one or more group send-epoch seeds (docs/GROUP_FORWARD_SECRECY.md §3). */
        const val CTL_GROUP_KEY = 2

        /** [ctl]: [gk] asks the recipient to re-send its current seeds for [GroupKeyPayload.groupId]. */
        const val CTL_GROUP_KEY_REQ = 3

        /** [ctl]: [gk] acknowledges adopting the sender's seed ([GroupKeyPayload.ackEpoch]) — stops re-sends. */
        const val CTL_GROUP_KEY_ACK = 4

        /**
         * [ctl]: [ack] is a sealed delivery receipt for the named frame — the encrypted replacement
         * for the cleartext receipt frame. Flips the sender's tick only; deliberately does NOT
         * vaccine-purge custody (a carrier can't read it, so nobody purges — the delivered DM ages
         * out on the custody TTL uniformly; docs/ENCRYPTED_RECEIPTS_REACTIONS.md).
         */
        const val CTL_RECEIPT = 5

        /** [ctl]: [rp] is a sealed reaction (or retraction) — DM form or group form. */
        const val CTL_REACTION = 6

        // `7` is RESERVED for CTL_SCOPE_CONFIG (docs/SPOOL_PROTOCOL.md §5) — specified but not yet
        // shipped. Named here so the value is never recycled; ctl numbers are append-only.

        /**
         * [ctl]: [pr] is a sealed profile update (name/status/avatar) for an already-established
         * contact — the encrypted replacement for re-flooding a cleartext `profile` frame. The
         * cleartext frame keeps its one irreplaceable job, first contact: it is authenticated against
         * the `pubKey` inside its own payload, so it can never be encrypted.
         */
        const val CTL_PROFILE = 8

        @OptIn(ExperimentalSerializationApi::class)
        fun decode(bytes: ByteArray): MessageContent? = runCatching { cryptoCbor.decodeFromByteArray<MessageContent>(bytes) }.getOrNull()
    }
}
