package app.getknit.knit.mesh.link

import java.io.ByteArrayOutputStream

/**
 * The little CBOR [FrameTranscoder] needs: a definite-length **item scanner** (where does the item at `pos`
 * end?) and a minimal-form **header writer**. kotlinx-serialization-cbor exposes no element model, and
 * `WireCodec` emits only definite lengths (`useDefiniteLengthEncoding`), so every indefinite or reserved form
 * (additional info 28..31) is refused outright — a frame this cannot scan simply keeps the `0x03` framing.
 * Total: every reader returns null rather than throwing on truncated or malformed input.
 */
internal object CborItems {
    const val MAJOR_UINT = 0
    const val MAJOR_NEGINT = 1
    const val MAJOR_BSTR = 2
    const val MAJOR_TSTR = 3
    const val MAJOR_ARRAY = 4
    const val MAJOR_MAP = 5
    const val MAJOR_TAG = 6
    const val MAJOR_SIMPLE = 7

    /** Deepest container nesting scanned (a wire frame is ≤ 5 deep); past it the item is refused. */
    const val MAX_DEPTH = 8

    /** The additional-info value whose argument rides in the next 8 bytes — the form every millisecond clock takes. */
    const val INFO_EIGHT_BYTES = 27

    private const val MAJOR_SHIFT = 5
    private const val INFO_MASK = 0x1F
    private const val INFO_INLINE_MAX = 23
    private const val INFO_ONE_BYTE = 24
    private const val INFO_TWO_BYTES = 25
    private const val INFO_FOUR_BYTES = 26
    private const val BYTE_MASK = 0xFFL
    private const val MAX_ONE_BYTE = 0xFFL
    private const val MAX_TWO_BYTES = 0xFFFFL
    private const val MAX_FOUR_BYTES = 0xFFFF_FFFFL

    /** The header of the item at a position: major type, additional info, argument (value, length, count or tag), content start. */
    class Header(
        val major: Int,
        val info: Int,
        val arg: Long,
        val contentStart: Int,
    )

    /** Parses the header at [pos], or null when truncated or of a refused (indefinite / reserved) form. */
    fun header(
        bytes: ByteArray,
        pos: Int,
    ): Header? {
        if (pos < 0 || pos >= bytes.size) return null
        val initial = bytes[pos].toLong() and BYTE_MASK
        val major = (initial shr MAJOR_SHIFT).toInt()
        val info = (initial and INFO_MASK.toLong()).toInt()
        val argBytes =
            when (info) {
                in 0..INFO_INLINE_MAX -> 0
                INFO_ONE_BYTE -> 1
                INFO_TWO_BYTES -> 2
                INFO_FOUR_BYTES -> 4
                INFO_EIGHT_BYTES -> Long.SIZE_BYTES
                else -> return null
            }
        if (pos + 1 + argBytes > bytes.size) return null
        val arg = if (argBytes == 0) info.toLong() else readBigEndian(bytes, pos + 1, argBytes)
        return Header(major, info, arg, pos + 1 + argBytes)
    }

    /** End (exclusive) of the whole item at [pos] — containers included — or null when truncated, malformed, or too deep. */
    fun itemEnd(
        bytes: ByteArray,
        pos: Int,
        depth: Int = 0,
    ): Int? {
        val h = header(bytes, pos) ?: return null
        return when (h.major) {
            MAJOR_UINT, MAJOR_NEGINT, MAJOR_SIMPLE -> h.contentStart
            MAJOR_BSTR, MAJOR_TSTR -> spanEnd(bytes, h.contentStart, h.arg)
            MAJOR_ARRAY -> itemsEnd(bytes, h.contentStart, h.arg, depth)
            MAJOR_MAP -> if (h.arg > bytes.size) null else itemsEnd(bytes, h.contentStart, h.arg * 2, depth)
            else -> itemsEnd(bytes, h.contentStart, 1, depth) // MAJOR_TAG: exactly one tagged item follows
        }
    }

    /** The big-endian unsigned value of [n] bytes at [start] (callers bound [n] at 8). */
    fun readBigEndian(
        bytes: ByteArray,
        start: Int,
        n: Int,
    ): Long {
        var value = 0L
        for (i in 0 until n) value = (value shl Byte.SIZE_BITS) or (bytes[start + i].toLong() and BYTE_MASK)
        return value
    }

    /** Writes the minimal-form header for [major] with the non-negative argument [arg] (a value, length, or count). */
    fun writeHeader(
        out: ByteArrayOutputStream,
        major: Int,
        arg: Long,
    ) {
        val mt = major shl MAJOR_SHIFT
        when {
            arg in 0..INFO_INLINE_MAX.toLong() -> {
                out.write(mt or arg.toInt())
            }

            arg in 0..MAX_ONE_BYTE -> {
                out.write(mt or INFO_ONE_BYTE)
                writeBigEndian(out, arg, Byte.SIZE_BYTES)
            }

            arg in 0..MAX_TWO_BYTES -> {
                out.write(mt or INFO_TWO_BYTES)
                writeBigEndian(out, arg, Short.SIZE_BYTES)
            }

            arg in 0..MAX_FOUR_BYTES -> {
                out.write(mt or INFO_FOUR_BYTES)
                writeBigEndian(out, arg, Int.SIZE_BYTES)
            }

            else -> {
                out.write(mt or INFO_EIGHT_BYTES)
                writeBigEndian(out, arg, Long.SIZE_BYTES)
            }
        }
    }

    /** The low [n] bytes of [value], big-endian. */
    fun writeBigEndian(
        out: ByteArrayOutputStream,
        value: Long,
        n: Int,
    ) {
        for (i in n - 1 downTo 0) out.write(((value ushr (Byte.SIZE_BITS * i)) and BYTE_MASK).toInt())
    }

    private fun spanEnd(
        bytes: ByteArray,
        start: Int,
        length: Long,
    ): Int? = if (length < 0 || length > bytes.size - start) null else start + length.toInt()

    private fun itemsEnd(
        bytes: ByteArray,
        start: Int,
        count: Long,
        depth: Int,
    ): Int? {
        // Every item is at least one byte, so a count past the remaining bytes is malformed before any scan.
        if (depth >= MAX_DEPTH || count < 0 || count > bytes.size - start) return null
        var p = start
        repeat(count.toInt()) { p = itemEnd(bytes, p, depth + 1) ?: return null }
        return p
    }
}
