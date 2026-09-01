package app.getknit.knit.data.emoji

/**
 * The emoji the picker can offer: [entries] in Unicode order (already filtered to what this device can
 * render by [EmojiCatalogLoader]), the [browse] subset the grid shows (skin-tone variants collapsed away),
 * that subset [byGroup] in group order, and a name [search] over **all** entries so a tone variant is one
 * query away ("thumbs up: medium skin tone") without cluttering the grid. Pure Kotlin; the Android half
 * is [AndroidGlyphCheck].
 */
class EmojiCatalog(
    val entries: List<EmojiEntry>,
) {
    val browse: List<EmojiEntry> = entries.filter { !it.toneVariant }

    /** Insertion-ordered (Unicode group order); a group with nothing renderable is simply absent. */
    val byGroup: Map<EmojiGroup, List<EmojiEntry>> = browse.groupBy { it.group }

    /**
     * Case-insensitive name search: every whitespace-separated token of [query] must occur in the name
     * (so "red heart" finds "red heart" but not "heart"), ranked exact name < name prefix < word prefix <
     * substring, ties in Unicode order (a base emoji lands before its tone variants). A query that *is* an
     * entry's emoji — a paste — returns that entry first. Blank → empty. At most [limit] results.
     */
    fun search(
        query: String,
        limit: Int = SEARCH_LIMIT,
    ): List<EmojiEntry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val pasted = entries.firstOrNull { it.emoji == trimmed }
        val q = trimmed.lowercase()
        val tokens = q.split(WHITESPACE).filter { it.isNotEmpty() }
        val ranked =
            entries
                .asSequence()
                .filter { e -> e !== pasted && tokens.all { e.searchKey.contains(it) } }
                .map { e -> rank(e.searchKey, q, tokens.first()) to e }
                .sortedBy { it.first } // stable: Unicode order survives within a rank
                .map { it.second }
                .take(if (pasted != null) limit - 1 else limit)
                .toList()
        return if (pasted != null) listOf(pasted) + ranked else ranked
    }

    private fun rank(
        key: String,
        q: String,
        firstToken: String,
    ): Int =
        when {
            key == q -> RANK_EXACT
            key.startsWith(q) -> RANK_PREFIX
            key.split(WORD_BREAK).any { it.startsWith(firstToken) } -> RANK_WORD
            else -> RANK_SUBSTRING
        }

    companion object {
        val EMPTY = EmojiCatalog(emptyList())

        /** Enough to fill several grid screens; a query this broad wants refining, not scrolling. */
        const val SEARCH_LIMIT = 120

        private const val RANK_EXACT = 0
        private const val RANK_PREFIX = 1
        private const val RANK_WORD = 2
        private const val RANK_SUBSTRING = 3
        private val WHITESPACE = Regex("\\s+")
        private val WORD_BREAK = Regex("[\\s:,\\-]+")
    }
}
