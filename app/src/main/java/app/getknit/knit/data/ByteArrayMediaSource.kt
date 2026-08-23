package app.getknit.knit.data

import android.media.MediaDataSource

/**
 * A [MediaDataSource] over an in-memory [ByteArray], so `MediaExtractor`/`MediaCodec`/`MediaPlayer` can read
 * audio the app holds in RAM without it ever reaching the filesystem.
 *
 * This is the audio analogue of [app.getknit.knit.ui.image.BlobFetcher], which hands Coil an in-memory
 * `okio.Buffer` for the same reason (and is why the image loader is registered with `.diskCache(null)`).
 * Attachment bytes live encrypted in the `blobs` table; a voice note is decrypted for playback and must not
 * be staged as a plaintext file to satisfy a player's API — the invariant `AttachmentStore`'s KDoc states
 * and ADR 029 turned into a rule.
 *
 * The platform reads a data source on its own threads, so [readAt] must be safe to call concurrently: it is,
 * being a bounds-checked copy out of an array nothing mutates after construction. [close] is deliberately a
 * no-op — there is no handle to release, and the array's lifetime belongs to the caller.
 */
class ByteArrayMediaSource(
    private val bytes: ByteArray,
) : MediaDataSource() {
    override fun getSize(): Long = bytes.size.toLong()

    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        // The contract is -1 at (or past) EOF, and a short read is legal everywhere else.
        if (position >= bytes.size) return -1
        if (size <= 0) return 0
        val available = (bytes.size - position).coerceAtMost(size.toLong()).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, available)
        return available
    }

    override fun close() = Unit
}
