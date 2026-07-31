package app.getknit.knit.ui.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Decodes a QR code out of a raw camera luminance (Y) plane. **Deliberately Android-free** — zxing core is
 * pure Java, so this whole object is a plain-JVM unit-test target ([app.getknit.knit.ui.scan.QrDecoderTest]);
 * the Android/CameraX adapter lives in `QrScannerContent`.
 *
 * This file exists because the library it replaced got exactly this arithmetic wrong.
 * `com.journeyapps:zxing-android-embedded` ran its decode loop on a bare `HandlerThread` with no `try`/
 * `catch` anywhere in the chain, and cropped the preview buffer using the frame size the camera *reported*
 * rather than the one it *delivered*. On a device where those disagree — ordinary camera-HAL variance —
 * `System.arraycopy` threw `ArrayIndexOutOfBoundsException` off the main thread and killed the process
 * (the F-Droid crash report against 2.2.1). So the invariant here is absolute:
 *
 * > **[decode] never throws.** Any frame — truncated, mis-strided, absurdly sized, or simply without a
 * > code in it — yields `null`. A camera frame must not be able to take the app down.
 *
 * Rotation is not handled, and doesn't need to be: zxing locates a QR code by its finder patterns in any
 * orientation, which is what lets us drop the rotate-and-crop stage that caused the crash.
 */
object QrDecoder {
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    /**
     * Returns the decoded QR text, or `null` if this frame holds no readable code (the common case, once
     * per frame until the user lines the code up) *or* if [luma] doesn't match the geometry described by
     * [rowStride]/[pixelStride]/[width]/[height].
     *
     * [rowStride] and [pixelStride] come straight from the camera plane and are **not** assumed to equal
     * [width] and 1: padded rows are routine, and `YUV_420_888` permits a Y plane with `pixelStride > 1`.
     */
    fun decode(
        luma: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
    ): String? =
        // runCatching (Throwable, not just Exception) is the point: a decoder bug, a malformed frame, or an
        // allocation failure costs us one skipped frame, never the process.
        runCatching {
            val packed = pack(luma, rowStride, pixelStride, width, height) ?: return null
            // left/top = 0 and crop = full size, so PlanarYUVLuminanceSource's own bounds check
            // ("Crop rectangle does not fit within image data") is unreachable by construction.
            val source = PlanarYUVLuminanceSource(packed, width, height, 0, 0, width, height, false)
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        }.getOrNull()

    /**
     * Copies the Y plane into a tightly packed `width * height` buffer, or returns `null` if [luma] is
     * too small for the geometry it claims — the check the old library was missing (it compared the
     * Y-plane size against a 1.5x NV21 buffer, so a mismatch slipped through and detonated later).
     */
    private fun pack(
        luma: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
    ): ByteArray? {
        // Every dimension must be positive; a single min keeps this one condition and allocation-free
        // (this runs on every camera frame).
        if (minOf(minOf(width, height), minOf(rowStride, pixelStride)) <= 0) return null
        // Index of the last byte we will read. Anything shorter and the geometry is a lie.
        val needed = (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride + 1
        if (luma.size < needed) return null
        // Already tightly packed: hand the camera's own buffer straight to zxing, no copy.
        if (rowStride == width && pixelStride == 1) return luma

        val packed = ByteArray(width * height)
        for (y in 0 until height) {
            val src = y * rowStride
            val dst = y * width
            if (pixelStride == 1) {
                System.arraycopy(luma, src, packed, dst, width)
            } else {
                for (x in 0 until width) {
                    packed[dst + x] = luma[src + x * pixelStride]
                }
            }
        }
        return packed
    }
}
