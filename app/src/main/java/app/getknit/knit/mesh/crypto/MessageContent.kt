package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.GroupKeyPayload
import app.getknit.knit.mesh.protocol.Mention
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
    // Control marker for ratchet session management (additive; [CTL_SESSION_RESET],
    // [CTL_GROUP_KEY], [CTL_GROUP_KEY_REQ], [CTL_GROUP_KEY_ACK]). A non-null value means this frame
    // is machinery, not conversation: it is never persisted as a message, never notified, never
    // acked-as-a-message — see docs/FORWARD_SECRECY_RATCHET.md §7. Inside the ciphertext
    // deliberately: a relay cannot distinguish machinery from an ordinary DM. An unknown value is
    // consumed as a silent no-op, which is what lets new ctl values ship additively.
    val ctl: Int? = null,
    // Group-key payload for the CTL_GROUP_KEY* values (additive; docs/GROUP_FORWARD_SECRECY.md §3).
    val gk: GroupKeyPayload? = null,
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

        @OptIn(ExperimentalSerializationApi::class)
        fun decode(bytes: ByteArray): MessageContent? = runCatching { cryptoCbor.decodeFromByteArray<MessageContent>(bytes) }.getOrNull()
    }
}
