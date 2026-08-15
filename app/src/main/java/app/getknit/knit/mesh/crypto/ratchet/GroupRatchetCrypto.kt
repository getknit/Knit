package app.getknit.knit.mesh.crypto.ratchet

import com.google.crypto.tink.subtle.Hkdf
import java.security.SecureRandom

/**
 * The primitive layer of the group sender-key ratchet (crypto scheme v3 — see
 * docs/GROUP_FORWARD_SECRECY.md): per-sender epoch derivation from a random seed, and the same
 * forward-only message-key chain shape as the DM ratchet. Pure byte-array functions over Tink's
 * subtle HKDF, so everything runs unchanged under JVM unit tests and doubles as the normative
 * reference for a non-Tink (iOS CryptoKit) implementation.
 *
 * There is deliberately no DH here: freshness and confidentiality of an epoch seed come from the
 * pairwise v2 DM ratchet that carries it (`MessageContent.ctl = CTL_GROUP_KEY`), so the group layer
 * is a pure chain machine. Domain separation: every derivation is labeled under `knit/group/v1/...`,
 * disjoint from `knit/dm/v2/...` and RFC 9180's HPKE labels.
 *
 * All functions are deterministic except [newSeed]; none of them touch Android, IO, or state.
 */
object GroupRatchetCrypto {
    const val SEED_BYTES = 32
    const val KEY_BYTES = 32

    /** HKDF output: first 32 bytes key the epoch's message chain, second 32 feed the export API. */
    const val EPOCH_SECRET_BYTES = 64

    private const val MAC = "HMACSHA256"
    private val ZERO_SALT = ByteArray(KEY_BYTES)

    private val LABEL_EPOCH = "knit/group/v1/epoch".toByteArray()
    private val LABEL_MSG_KEY = "knit/group/v1/mk".toByteArray()
    private val LABEL_CHAIN_KEY = "knit/group/v1/ck".toByteArray()
    private val LABEL_EXPORT_EPOCH = "knit/group/v1/export/epoch".toByteArray()

    private val random = SecureRandom()

    /** A fresh random epoch seed — the only secret a sender ever distributes for its group chain. */
    fun newSeed(): ByteArray = ByteArray(SEED_BYTES).also(random::nextBytes)

    /** The two halves of a derived epoch secret. */
    class EpochKeys(
        val chainKey: ByteArray,
        val export: ByteArray,
    )

    /**
     * Derives one sender's epoch secret from its distributed [seed]. The info binds [groupId],
     * [senderId], and [epoch], so a seed leaked, replayed, or accidentally reused cannot be
     * transplanted across groups, senders, or epoch numbers — the derived chain would differ. The
     * `|` separator is the AAD-header convention; it cannot occur in a base32 node id or a `g-`-hex
     * group id, so the three fields cannot be made to alias each other.
     */
    fun deriveEpoch(
        seed: ByteArray,
        groupId: String,
        senderId: String,
        epoch: Int,
    ): EpochKeys {
        val info = LABEL_EPOCH + "$groupId|$senderId|".toByteArray() + u32be(epoch)
        val okm = Hkdf.computeHkdf(MAC, seed, ZERO_SALT, info, EPOCH_SECRET_BYTES)
        return EpochKeys(chainKey = okm.copyOfRange(0, KEY_BYTES), export = okm.copyOfRange(KEY_BYTES, EPOCH_SECRET_BYTES))
    }

    /** The AES-256-GCM key for chain index `n`, given `chainKey_n`. Single-use; deleted after decrypt. */
    fun messageKey(chainKey: ByteArray): ByteArray = Hkdf.computeHkdf(MAC, chainKey, ZERO_SALT, LABEL_MSG_KEY, KEY_BYTES)

    /** Advances the forward-only chain: `chainKey_{n+1}` from `chainKey_n` (one-way; enables delete-as-you-go). */
    fun nextChainKey(chainKey: ByteArray): ByteArray = Hkdf.computeHkdf(MAC, chainKey, ZERO_SALT, LABEL_CHAIN_KEY, KEY_BYTES)

    /**
     * Per-(sender, epoch) export secret for the future relay ("spool") plane — API-only, like the DM
     * ratchet's §8 surface. A shared per-group root is deliberately deferred to the relay-plane
     * design doc; see docs/GROUP_FORWARD_SECRECY.md §8.
     */
    fun exportEpochSeal(epochExport: ByteArray): ByteArray = Hkdf.computeHkdf(MAC, epochExport, ZERO_SALT, LABEL_EXPORT_EPOCH, KEY_BYTES)

    @Suppress("MagicNumber") // big-endian byte-lane shifts; naming 24/16/8 would only obscure them
    private fun u32be(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
}
