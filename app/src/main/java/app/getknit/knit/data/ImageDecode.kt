package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.min

/*
 * Shared image-decoding helpers used by [AvatarStore] and [AttachmentStore]. Decodes are
 * EXIF-corrected (so portrait photos aren't stored sideways) and bounded by an `inSampleSize`
 * pre-pass (so a large source image can't OOM the full decode).
 */

/**
 * Decodes [uri] applying its EXIF orientation, sub-sampling during decode so the result stays at or
 * just above [maxDim] on each pre-rotation edge. Pair with [downscale] for an exact bound. Returns
 * null if the stream can't be read.
 */
internal fun decodeOrientedBounded(
    context: Context,
    uri: Uri,
    maxDim: Int,
): Bitmap? = decodeOrientedBounded(maxDim) { context.contentResolver.openInputStream(uri) }

/**
 * The in-memory twin of the [Uri] overload above, for an image that never had a Uri — the in-app
 * camera's captured JPEG. Note this is *not* [decodeBoundedFromBytes], which skips EXIF orientation
 * because it only feeds the content classifier; a photo decoded without it is stored sideways.
 */
internal fun decodeOrientedBounded(
    bytes: ByteArray,
    maxDim: Int,
): Bitmap? = decodeOrientedBounded(maxDim) { ByteArrayInputStream(bytes) }

/**
 * Shared body of the two overloads above. [source] is opened three times — the bounds pre-pass, the
 * sub-sampled decode, and the EXIF read — because each consumes the stream.
 */
private fun decodeOrientedBounded(
    maxDim: Int,
    source: () -> InputStream?,
): Bitmap? {
    // inJustDecodeBounds populates bounds.outWidth/outHeight and returns null by design, so the
    // null check must be on the source, not on decodeStream's (always-null) result.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    (source() ?: return null).use {
        BitmapFactory.decodeStream(it, null, bounds)
    }

    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
        }
    val bitmap =
        (source() ?: return null).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

    val orientation =
        source()?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    return rotatedForExif(bitmap, orientation)
}

/** Rotates [bitmap] upright per its EXIF [orientation]; returns it unchanged when no rotation applies. */
@Suppress("MagicNumber") // rotation degrees (90/180/270) mirror the named ORIENTATION_ROTATE_* constants
private fun rotatedForExif(bitmap: Bitmap, orientation: Int): Bitmap {
    val degrees =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * Largest power-of-two sample size that keeps both decoded dimensions at or above [maxDim], so the
 * coarse decode never drops below the target before [downscale] applies the exact bound.
 */
private fun sampleSizeFor(
    width: Int,
    height: Int,
    maxDim: Int,
): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= maxDim && height / (sample * 2) >= maxDim) {
        sample *= 2
    }
    return sample
}

/**
 * Decodes a bitmap from in-memory [bytes], sub-sampled and downscaled so neither side exceeds [maxDim]
 * (so screening a peer-supplied image can't OOM). For an animated GIF this yields its first frame, which
 * is enough for content classification. Returns null if the bytes can't be decoded.
 */
internal fun decodeBoundedFromBytes(
    bytes: ByteArray,
    maxDim: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
        }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return downscale(bitmap, maxDim)
}

/** Scales [src] down so neither side exceeds [max], preserving aspect ratio; returns [src] if already within bounds. */
internal fun downscale(
    src: Bitmap,
    max: Int,
): Bitmap {
    if (src.width <= max && src.height <= max) return src
    val ratio = min(max.toFloat() / src.width, max.toFloat() / src.height)
    val w = (src.width * ratio).toInt().coerceAtLeast(1)
    val h = (src.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, w, h, true)
}
