package app.getknit.knit.ui.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * Regression coverage for the crash that took out the 2.2.1 scanner.
 *
 * The library this replaced (`com.journeyapps:zxing-android-embedded`) cropped the camera buffer using the
 * frame geometry the camera *reported* rather than what it *delivered*, on a `HandlerThread` with no
 * `try`/`catch` — so on a device where those disagree, `System.arraycopy` threw
 * `ArrayIndexOutOfBoundsException` off the main thread and killed the process. These tests pin both halves
 * of the fix: strides are honoured, and **no** input can make [QrDecoder.decode] throw.
 *
 * Plain JVM, no Robolectric — [QrDecoder] is deliberately Android-free, and a Robolectric test added to
 * this suite intermittently crashes Gradle 9.5's test-result serialization.
 */
class QrDecoderTest {
    private val payload = "knit-id:v1:s4mpl3n0d3:BUNDLE-abcdef0123456789"

    /** The happy path a real camera hands us: a tightly packed Y plane. */
    @Test
    fun `decodes a tightly packed luminance plane`() {
        val frame = renderQr(payload)
        assertEquals(payload, QrDecoder.decode(frame.luma, frame.width, 1, frame.width, frame.height))
    }

    /**
     * The case the old library got wrong. Camera planes are routinely padded, so `rowStride > width`;
     * reading the buffer as if it were tightly packed yields garbage at best and an overrun at worst.
     */
    @Test
    fun `decodes a plane with row padding`() {
        val frame = renderQr(payload).withRowStride(extraBytesPerRow = 64)
        assertEquals(payload, QrDecoder.decode(frame.luma, frame.stride, 1, frame.width, frame.height))
    }

    /** `YUV_420_888` permits a Y plane with pixelStride > 1 (interleaved), which some devices do use. */
    @Test
    fun `decodes an interleaved plane with pixelStride 2`() {
        val frame = renderQr(payload).withPixelStride(pixelStride = 2)
        assertEquals(payload, QrDecoder.decode(frame.luma, frame.stride, 2, frame.width, frame.height))
    }

    /** A frame with no code in it is the normal per-frame outcome, not an error. */
    @Test
    fun `returns null for a frame with no code`() {
        val blank = ByteArray(200 * 200) { -1 }
        assertNull(QrDecoder.decode(blank, 200, 1, 200, 200))
    }

    /**
     * The crash itself: a buffer that does not match its declared geometry. The old code read past the end
     * of the array here. Every one of these must yield null rather than throw.
     */
    @Test
    fun `returns null instead of throwing when the buffer is too small for its geometry`() {
        val frame = renderQr(payload)
        val truncated = frame.luma.copyOf(frame.luma.size / 2)

        assertNull(QrDecoder.decode(truncated, frame.width, 1, frame.width, frame.height))
        // Buffer intact, but the claimed stride/size overruns it — the exact mismatch that crashed 2.2.1.
        assertNull(QrDecoder.decode(frame.luma, frame.width * 2, 1, frame.width, frame.height))
        assertNull(QrDecoder.decode(frame.luma, frame.width, 4, frame.width, frame.height))
        assertNull(QrDecoder.decode(frame.luma, frame.width, 1, frame.width * 3, frame.height * 3))
    }

    /** Degenerate geometry must be rejected up front, never turned into an allocation or an index. */
    @Test
    fun `returns null for degenerate geometry`() {
        val frame = renderQr(payload)
        assertNull(QrDecoder.decode(frame.luma, frame.width, 1, 0, frame.height))
        assertNull(QrDecoder.decode(frame.luma, frame.width, 1, frame.width, 0))
        assertNull(QrDecoder.decode(frame.luma, 0, 1, frame.width, frame.height))
        assertNull(QrDecoder.decode(frame.luma, frame.width, 0, frame.width, frame.height))
        assertNull(QrDecoder.decode(frame.luma, -1, -1, -1, -1))
        assertNull(QrDecoder.decode(ByteArray(0), 1, 1, 1, 1))
    }

    /** Random bytes stand in for the endless stream of junk frames a real camera produces. */
    @Test
    fun `returns null for random noise without throwing`() {
        val random = Random(seed = 42)
        repeat(20) {
            val bytes = random.nextBytes(64 * 64)
            assertNull(QrDecoder.decode(bytes, 64, 1, 64, 64))
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private data class Frame(
        val luma: ByteArray,
        val stride: Int,
        val width: Int,
        val height: Int,
    ) {
        /** Re-lays the rows into a buffer whose stride exceeds the width, padding with black. */
        fun withRowStride(extraBytesPerRow: Int): Frame {
            val newStride = width + extraBytesPerRow
            val padded = ByteArray(newStride * height)
            for (y in 0 until height) {
                System.arraycopy(luma, y * stride, padded, y * newStride, width)
            }
            return Frame(padded, newStride, width, height)
        }

        /** Re-lays each row so consecutive luminance samples sit [pixelStride] bytes apart. */
        fun withPixelStride(pixelStride: Int): Frame {
            val newStride = width * pixelStride
            val spread = ByteArray(newStride * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    spread[y * newStride + x * pixelStride] = luma[y * stride + x]
                }
            }
            return Frame(spread, newStride, width, height)
        }

        // data class on a ByteArray: identity equality is fine here (these are only ever compared by
        // reference in tests), but override so the generated equals/hashCode aren't quietly wrong.
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** Renders [text] as a QR code and flattens it to an 8-bit luminance plane, the way a camera would. */
    private fun renderQr(text: String): Frame {
        val size = 256
        val matrix: BitMatrix =
            MultiFormatWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(EncodeHintType.MARGIN to 2),
            )
        val luma = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                // Dark module -> 0, light -> 255 (as an unsigned byte).
                luma[y * size + x] = if (matrix.get(x, y)) 0 else -1
            }
        }
        return Frame(luma, size, size, size)
    }
}
