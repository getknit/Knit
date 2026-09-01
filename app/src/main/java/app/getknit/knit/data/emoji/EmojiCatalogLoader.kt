package app.getknit.knit.data.emoji

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * Loads the bundled emoji catalog once per process, off the main thread, the first time the picker
 * opens: parses [ASSET] through [open], then drops every entry [canRender] refuses — on a device that is
 * `Paint.hasGlyph` ([AndroidGlyphCheck]), so an older phone never offers an emoji its fonts draw as a tofu
 * box. Fonts don't change mid-process, so the result is cached; concurrent first loads collapse on the
 * mutex. A missing or unreadable asset degrades to [EmojiCatalog.EMPTY] (the quick-reaction row still
 * works) and is **not** cached, so a transient failure is retried on the next open. Pure Kotlin — the
 * stream and the glyph check are injected so the JVM tests need no Android.
 */
class EmojiCatalogLoader(
    private val open: () -> InputStream,
    private val canRender: (String) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    private var cached: EmojiCatalog? = null
    private val mutex = Mutex()

    suspend fun load(): EmojiCatalog =
        cached ?: mutex.withLock {
            cached ?: withContext(io) { read() }?.also { cached = it } ?: EmojiCatalog.EMPTY
        }

    private fun read(): EmojiCatalog? =
        try {
            val entries = open().bufferedReader().useLines(EmojiCatalogParser::parse)
            EmojiCatalog(entries.filter { canRender(it.emoji) })
        } catch (_: IOException) {
            null
        }

    companion object {
        const val ASSET = "emoji/emoji_en.tsv"
    }
}
