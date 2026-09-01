package app.getknit.knit.data.emoji

/**
 * The most-recently-used reaction emoji behind the long-press quick row: a pure codec over the single
 * preference string `SettingsStore` keeps, newest first. Seeded with the classic six ([DEFAULTS]) until the
 * first pick. Stored as one separator-joined string rather than a preference *set* because order is the
 * datum; the separator is U+001F (ASCII unit separator), a C0 control that can never occur inside an emoji
 * sequence and cannot be typed. Keeps [KEPT] so a future "Recent" section in the sheet needs no schema
 * change; the row shows [SHOWN].
 */
object RecentReactions {
    const val SHOWN = 6
    const val KEPT = 12
    val DEFAULTS: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    /** U+001F, ASCII unit separator. */
    val SEPARATOR: Char = 0x1F.toChar()

    /** Newest-first recents from the stored string; null/blank → [DEFAULTS]. */
    fun decode(raw: String?): List<String> =
        raw
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULTS

    fun encode(recents: List<String>): String = recents.joinToString(SEPARATOR.toString())

    /** [emoji] moved (or added) to the front, de-duplicated, capped at [KEPT]. */
    fun push(
        current: List<String>,
        emoji: String,
    ): List<String> = (listOf(emoji) + current.filter { it != emoji }).take(KEPT)
}
