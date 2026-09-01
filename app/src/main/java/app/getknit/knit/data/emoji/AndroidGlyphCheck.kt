package app.getknit.knit.data.emoji

import android.graphics.Paint

/**
 * The device-font renderability predicate for [EmojiCatalogLoader]: `Paint.hasGlyph` (API 23) is true
 * only when the platform's font chain can shape the whole string — a flag pair, a ZWJ family, a keycap —
 * as one glyph, which is exactly "would this draw as an emoji rather than tofu or a broken sequence".
 * The single `android.graphics` importer of the package. [Paint] isn't thread-safe; the loader calls this
 * from one coroutine under its mutex.
 */
class AndroidGlyphCheck : (String) -> Boolean {
    private val paint = Paint()

    override fun invoke(emoji: String): Boolean = paint.hasGlyph(emoji)
}
