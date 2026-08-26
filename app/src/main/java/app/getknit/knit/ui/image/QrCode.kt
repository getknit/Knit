package app.getknit.knit.ui.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

/** Renders text (e.g. an identity [app.getknit.knit.mesh.crypto.VerifyPayload]) into a QR [ImageBitmap]. */
object QrCode {
    /**
     * Encodes [content] as a [sizePx]-square QR.
     *
     * The pixels are filled into an `IntArray` and handed to [Bitmap.createBitmap] in one go rather than
     * written with `Bitmap.setPixel`. That looks like a micro-optimisation and is not: `setPixel` is a JNI
     * call with its own bounds check *per pixel*, so the old loop made 230,400 of them for the 480px QR this
     * app renders, measured at **123 ms** against **8.6 ms** for this version on an emulator — a 14x
     * difference, and 123 ms of it landed on the main thread inside a screen transition.
     *
     * Still not free at 8.6 ms, so callers must not run it during composition — see the `produceState` in
     * `EncryptionSection`.
     */
    fun render(
        content: String,
        sizePx: Int,
    ): ImageBitmap? =
        runCatching {
            val matrix =
                MultiFormatWriter().encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    sizePx,
                    sizePx,
                    mapOf(EncodeHintType.MARGIN to 1),
                )
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val row = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
}
