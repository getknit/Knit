package app.getknit.knit.mesh.crypto.scope

import java.security.MessageDigest

/**
 * The spool plane's stateless proof-of-work stamp (docs/SPOOL_PROTOCOL.md §8): Hashcash over
 * `scopeId ‖ utcDay`, NIP-13-style leading-zero-bit difficulty, no server challenge. A spool demands
 * a stamp only for a scope id it has never seen, so an honest client mines roughly once per scope per
 * spool; the ±1-day acceptance window bounds pre-mining. Pure JDK crypto, API-only until M2/M3.
 */
object SpoolPow {
    const val DAY_MS = 86_400_000L

    /** Default difficulty a spool advertises in HELLO (`0` disables); operator-tunable. */
    const val DEFAULT_BITS = 20

    /**
     * The most difficulty a client will attempt. `powBits` is a HELLO field, so it is the spool's claim
     * about itself: at 20 bits (§12's recommendation) a stamp costs ~1 M hashes, and each bit doubles
     * that — a spool asking for 64 guarantees the whole mining budget is burned and still fails. 24 bits
     * is 16× the recommendation and comfortably inside the budget; beyond it, refusing to mine is the
     * only sane answer, since the work is spent per scope on every heal round that re-subscribes.
     */
    const val MAX_BITS = 24

    private val LABEL = "knit/spool/v1/pow".toByteArray()

    /** The UTC day bucket a stamp is minted for. */
    fun utcDay(nowMs: Long): Long = Math.floorDiv(nowMs, DAY_MS)

    /** The stamp digest: SHA-256 over `label ‖ scopeId ‖ u64be(day) ‖ u64be(n)`. */
    fun digest(
        scopeId: ByteArray,
        day: Long,
        n: Long,
    ): ByteArray = MessageDigest.getInstance("SHA-256").digest(LABEL + scopeId + u64be(day) + u64be(n))

    /** A stamp is valid iff its digest has at least [bits] leading zero bits ([bits] `<= 0` accepts all). */
    fun verify(
        scopeId: ByteArray,
        day: Long,
        n: Long,
        bits: Int,
    ): Boolean = bits <= 0 || leadingZeroBits(digest(scopeId, day, n)) >= bits

    /**
     * Mines the smallest counter from [startN] whose stamp meets [bits], trying at most [maxAttempts];
     * null when the budget runs out. Deterministic for fixed inputs (the vector tests pin one).
     */
    fun stamp(
        scopeId: ByteArray,
        day: Long,
        bits: Int,
        maxAttempts: Long,
        startN: Long = 0L,
    ): Long? {
        var n = startN
        var attempts = 0L
        while (attempts < maxAttempts) {
            if (verify(scopeId, day, n, bits)) return n
            n++
            attempts++
        }
        return null
    }

    /** Counts leading zero bits of a digest, byte-lexicographic. */
    fun leadingZeroBits(digest: ByteArray): Int {
        var bits = 0
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v == 0) {
                bits += Byte.SIZE_BITS
            } else {
                bits += Integer.numberOfLeadingZeros(v) - (Integer.SIZE - Byte.SIZE_BITS)
                break
            }
        }
        return bits
    }

    @Suppress("MagicNumber") // big-endian byte-lane shifts; naming 56/48/… would only obscure them
    private fun u64be(value: Long): ByteArray = ByteArray(Long.SIZE_BYTES) { (value ushr ((Long.SIZE_BYTES - 1 - it) * 8)).toByte() }
}
