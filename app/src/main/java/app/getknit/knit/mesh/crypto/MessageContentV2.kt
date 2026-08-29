@file:OptIn(ExperimentalSerializationApi::class) // Cbor and @CborLabel are experimental kotlinx APIs

package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfilePayload
import app.getknit.knit.mesh.protocol.ReactionPayload
import app.getknit.knit.mesh.protocol.ReplyRef
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * The v3 sealed plaintext (crypto scheme v3, ADR 059): the same facts as [MessageContent], laid out for a
 * size-capped radio — integer map keys in place of the named ones, and every id, hash and key as its raw
 * bytes rather than the text the domain carries (a 22-char frame id is 16 bytes, a 26-char node id 16, a
 * 64-hex hash 32). A tick's plaintext drops from 39 B to 21, a twelve-ack batch by 72. The domain object
 * is unchanged: this is a codec, chosen per frame by the peer's capability, and both directions convert.
 *
 * **Discriminated by the envelope**: a v3 [EncEnvelope] carries this schema, a v2 one the named schema. The
 * label-0 version is the compact schema's own version axis (elided while it is the default, so it costs
 * nothing) — kept so the two layers stay independently versioned the way `MessageContent.v` and
 * `EncEnvelope.v` are, and mirrored by [MAX_SUPPORTED] on decode.
 *
 * **Canonical or not at all.** Every raw field round-trips: [encodeOrNull] refuses (returns null, and the
 * caller seals the named v2 form instead) any id, hash or key whose decoded bytes do not re-encode to the
 * exact string — the JDK base64 decoder and `NodeId`'s base32 both discard stray trailing bits, so a
 * pattern match would accept strings that come back different. No shipped build ever minted a
 * non-canonical id for anything that gets acked, reacted to or quoted, so the fallback is hostile-input
 * hygiene rather than a compatibility path, but it is what keeps this codec unable to lose a frame.
 * [MessageContent.gk] is not modelled (the group-key ctls stay v2) and refuses the same way.
 *
 * Nested types are this codec's own rather than the domain's: `Mention`/`ReplyRef`/`ReactionPayload`/
 * `ProfilePayload` are shared with the cleartext `ChatContent`, whose shape is frozen (docs/WIRE_COMPAT.md
 * rule 2). Plain classes, since they hold `ByteArray`s. Pure — JVM-tested in `MessageContentV2Test`.
 */
internal object MessageContentV2 {
    /** The compact schema version this build writes (label 0, elided while default). */
    const val VERSION = 1

    /** The highest compact schema version this build reads; a higher one decodes to null. */
    const val MAX_SUPPORTED = 1

    /** [content] in the compact layout, or null when something in it has no canonical raw form (see the kdoc). */
    fun encodeOrNull(content: MessageContent): ByteArray? {
        if (content.v != MessageContent.VERSION || content.gk != null) return null
        return try {
            compactCbor.encodeToByteArray(wireOf(content))
        } catch (_: NonCanonical) {
            null
        }
    }

    /** Signals one non-canonical id/hash/key inside [wireOf]: the whole content falls back to the named form. */
    private class NonCanonical : RuntimeException()

    private fun wireOf(content: MessageContent): Wire =
        Wire(
            body = content.body,
            mentions = content.mentions.map { MentionV2(nodeId = nodeIdBytes(it.nodeId), name = it.name) },
            attachmentHash = content.attachmentHash?.let(::hashBytes),
            attachmentMime = content.attachmentMime,
            attachmentKey = content.attachmentKey?.let(::keyBytes),
            replyTo = content.replyTo?.let(::replyRefOf),
            ctl = content.ctl,
            ack = content.ack?.let(::frameIdBytes),
            acks = content.acks?.map(::frameIdBytes),
            rp = content.rp?.let { ReactionV2(messageId = frameIdBytes(it.messageId), emoji = it.emoji) },
            pr =
                content.pr?.let {
                    ProfileV2(
                        name = it.name,
                        status = it.status,
                        avatarHash = it.avatarHash?.let(::hashBytes),
                        version = it.version,
                    )
                },
        )

    private fun replyRefOf(ref: ReplyRef): ReplyRefV2 =
        ReplyRefV2(
            messageId = frameIdBytes(ref.messageId),
            authorId = nodeIdBytes(ref.authorId),
            author = ref.author,
            snippet = ref.snippet,
            hasAttachment = ref.hasAttachment,
        )

    private fun frameIdBytes(id: String): ByteArray = FrameId.toBytesOrNull(id) ?: throw NonCanonical()

    private fun nodeIdBytes(id: String): ByteArray = NodeId.toBytesOrNull(id) ?: throw NonCanonical()

    private fun hashBytes(hex: String): ByteArray = hashBytesOrNull(hex) ?: throw NonCanonical()

    private fun keyBytes(key: String): ByteArray = keyBytesOrNull(key) ?: throw NonCanonical()

    /** The domain [MessageContent] for compact [bytes], or null when malformed or a newer compact schema. */
    fun decode(bytes: ByteArray): MessageContent? =
        runCatching {
            val w = compactCbor.decodeFromByteArray<Wire>(bytes)
            if (w.v > MAX_SUPPORTED) return@runCatching null
            MessageContent(
                body = w.body,
                mentions = w.mentions.map { Mention(nodeId = nodeIdText(it.nodeId), name = it.name) },
                attachmentHash = w.attachmentHash?.let(::hashText),
                attachmentMime = w.attachmentMime,
                attachmentKey = w.attachmentKey?.let(::b64),
                replyTo =
                    w.replyTo?.let {
                        ReplyRef(
                            messageId = FrameId.fromBytes(it.messageId),
                            authorId = nodeIdText(it.authorId),
                            author = it.author,
                            snippet = it.snippet,
                            hasAttachment = it.hasAttachment,
                        )
                    },
                ctl = w.ctl,
                ack = w.ack?.let(FrameId::fromBytes),
                acks = w.acks?.map(FrameId::fromBytes),
                rp = w.rp?.let { ReactionPayload(messageId = FrameId.fromBytes(it.messageId), emoji = it.emoji) },
                pr =
                    w.pr?.let {
                        ProfilePayload(
                            name = it.name,
                            status = it.status,
                            avatarHash = it.avatarHash?.let(::hashText),
                            version = it.version,
                        )
                    },
            )
        }.getOrNull()

    private fun nodeIdText(bytes: ByteArray): String {
        require(bytes.size == NodeId.BYTES) { "a node id is ${NodeId.BYTES} bytes, got ${bytes.size}" }
        return NodeId.fromBytes(bytes)
    }

    /** The 32 bytes behind a lowercase 64-hex content hash, or null when [hex] is not exactly that. */
    private fun hashBytesOrNull(hex: String): ByteArray? {
        if (hex.length != HASH_BYTES * 2) return null
        val bytes = runCatching { ByteArray(HASH_BYTES) { i -> hex.substring(2 * i, 2 * i + 2).toInt(HEX_RADIX).toByte() } }.getOrNull()
        return bytes?.takeIf { hashText(it) == hex }
    }

    private fun hashText(bytes: ByteArray): String {
        require(bytes.size == HASH_BYTES) { "a content hash is $HASH_BYTES bytes, got ${bytes.size}" }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** The bytes behind a standard padded-base64 key, or null unless they re-encode to exactly [key]. */
    private fun keyBytesOrNull(key: String): ByteArray? = runCatching { b64d(key) }.getOrNull()?.takeIf { b64(it) == key }

    private const val HASH_BYTES = 32
    private const val HEX_RADIX = 16

    @Serializable
    @Suppress("MagicNumber") // the CBOR labels are the layout itself, pinned by GoldenVectorTest
    private class Wire(
        @CborLabel(0)
        val v: Int = VERSION,
        @CborLabel(1)
        val body: String = "",
        @CborLabel(2)
        val mentions: List<MentionV2> = emptyList(),
        @CborLabel(3)
        val attachmentHash: ByteArray? = null,
        @CborLabel(4)
        val attachmentMime: String? = null,
        @CborLabel(5)
        val attachmentKey: ByteArray? = null,
        @CborLabel(6)
        val replyTo: ReplyRefV2? = null,
        @CborLabel(7)
        val ctl: Int? = null,
        @CborLabel(8)
        val ack: ByteArray? = null,
        @CborLabel(9)
        val acks: List<ByteArray>? = null,
        @CborLabel(10)
        val rp: ReactionV2? = null,
        @CborLabel(11)
        val pr: ProfileV2? = null,
    )

    @Serializable
    @Suppress("MagicNumber") // the CBOR labels are the layout itself, pinned by GoldenVectorTest
    private class MentionV2(
        @CborLabel(1)
        val nodeId: ByteArray,
        @CborLabel(2)
        val name: String,
    )

    @Serializable
    @Suppress("MagicNumber") // the CBOR labels are the layout itself, pinned by GoldenVectorTest
    private class ReplyRefV2(
        @CborLabel(1)
        val messageId: ByteArray,
        @CborLabel(2)
        val authorId: ByteArray,
        @CborLabel(3)
        val author: String,
        @CborLabel(4)
        val snippet: String,
        @CborLabel(5)
        val hasAttachment: Boolean = false,
    )

    @Serializable
    @Suppress("MagicNumber") // the CBOR labels are the layout itself, pinned by GoldenVectorTest
    private class ReactionV2(
        @CborLabel(1)
        val messageId: ByteArray,
        @CborLabel(2)
        val emoji: String? = null,
    )

    @Serializable
    @Suppress("MagicNumber") // the CBOR labels are the layout itself, pinned by GoldenVectorTest
    private class ProfileV2(
        @CborLabel(1)
        val name: String,
        @CborLabel(2)
        val status: String,
        @CborLabel(3)
        val avatarHash: ByteArray? = null,
        @CborLabel(4)
        val version: Long = 0L,
    )
}

/** What a DM-form seal site hands the ratchet: the plaintext bytes and the scheme they are laid out for. */
internal class SealBytes(
    val plaintext: ByteArray,
    val scheme: Int,
) {
    operator fun component1(): ByteArray = plaintext

    operator fun component2(): Int = scheme
}

/**
 * The bytes to seal this content under toward a peer, and the scheme: the compact v3 layout when the peer
 * reads it ([v3]) and the content has a canonical compact form, else the named layout under v2. A seal
 * site never picks v3 except through this, so a content the compact codec refuses can only ever fall
 * back — never be lost.
 */
internal fun MessageContent.sealBytes(v3: Boolean): SealBytes {
    if (v3) MessageContentV2.encodeOrNull(this)?.let { return SealBytes(it, EncEnvelope.VERSION_DM_V3) }
    return SealBytes(encode(), EncEnvelope.VERSION_RATCHET)
}
