package app.getknit.knit.mesh.protocol

import java.security.SecureRandom
import java.util.Base64

/**
 * Mints a compact, globally-unique frame/request id: 128 random bits ([SecureRandom]) rendered as a
 * 22-char unpadded base64url string. Replaces `UUID.randomUUID().toString()` (36 chars — hex with
 * hyphens — for the same entropy). The id is opaque and forwarded verbatim (it is the dedup key, the
 * custody key, and what a receipt/reaction references), so only uniqueness matters; a tighter encoding
 * just trims every chat/reaction/receipt frame that carries one. base64url (`[A-Za-z0-9_-]`, no padding)
 * keeps it CBOR-text-, URL-, and filename-safe. Changing this format is *not* a wire break — every node
 * treats the id as an opaque string — as long as it stays collision-resistantly unique.
 */
object FrameId {
    /** The raw width of an id minted here. */
    const val ID_BYTES = 16

    /** The text width of an id minted here (`ceil(128 / 6)`, unpadded). */
    const val LENGTH = 22

    private val rng = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** A fresh random id. Thread-safe: [SecureRandom] and the stateless [Base64] encoder allow concurrent use. */
    fun new(): String = encoder.encodeToString(ByteArray(ID_BYTES).also { rng.nextBytes(it) })

    /**
     * The 16 raw bytes behind [id] when it is exactly what [new] mints — 22 base64url chars whose
     * re-encoding reproduces the string byte-for-byte — else null. The round trip, not a pattern, is the
     * test: the JDK decoder silently discards the two leftover bits of the last char, so a non-canonical
     * string would otherwise decode "successfully" to bytes that encode back to a *different* id. Ids
     * are opaque strings everywhere else (the class kdoc); only the v3 compact plaintext
     * ([app.getknit.knit.mesh.crypto.MessageContentV2]) carries them raw, and it falls back to text for
     * anything this refuses.
     */
    fun toBytesOrNull(id: String): ByteArray? {
        if (id.length != LENGTH) return null
        val bytes = runCatching { decoder.decode(id) }.getOrNull() ?: return null
        return bytes.takeIf { it.size == ID_BYTES && encoder.encodeToString(it) == id }
    }

    /** The id string for [bytes] (inverse of [toBytesOrNull]; [bytes] must be [ID_BYTES] wide). */
    fun fromBytes(bytes: ByteArray): String {
        require(bytes.size == ID_BYTES) { "a frame id is $ID_BYTES bytes, got ${bytes.size}" }
        return encoder.encodeToString(bytes)
    }
}
