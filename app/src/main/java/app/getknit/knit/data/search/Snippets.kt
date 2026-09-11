package app.getknit.knit.data.search

/**
 * The one line a search result shows of a matching body: a window of about [Snippets.DEFAULT_MAX]
 * characters around the first token that matches, with the match's position so the row can draw it bold.
 * Matching follows [SearchQuery]'s fold, so the window lands on `Café` for the query `cafe`, and it lands
 * only where the index would — at the start of a token, so `art` never highlights inside `start`.
 */
object Snippets {
    /** [text] to show, and the character range of the match inside it — null when no token was found. */
    data class Snippet(
        val text: String,
        val hit: IntRange?,
    )

    const val DEFAULT_MAX = 160
    private const val DEFAULT_BEFORE = 32
    private const val ELLIPSIS = "…"
    private val WHITESPACE = Regex("\\s+")

    /**
     * [body] collapsed to one line and cut to at most [max] characters around the earliest hit of any of
     * [tokens] (already folded — [SearchQuery.tokens]'s), keeping about [before] characters of lead-in and
     * never cutting a word or a surrogate pair. Without a hit, the head of the body.
     */
    fun around(
        body: String,
        tokens: List<String>,
        before: Int = DEFAULT_BEFORE,
        max: Int = DEFAULT_MAX,
    ): Snippet {
        val line = body.trim().replace(WHITESPACE, " ")
        val hit = Folded.of(line).firstHit(tokens)
        if (line.length <= max) return Snippet(line, hit)
        val start = windowStart(line, hit?.first ?: 0, before)
        val end = windowEnd(line, start, hit, max)
        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (end < line.length) ELLIPSIS else ""
        val shifted = hit?.let { (it.first - start + prefix.length)..(it.last - start + prefix.length) }
        return Snippet(prefix + line.substring(start, end) + suffix, shifted)
    }

    /** Where the window opens: about [before] characters ahead of [from], moved up to the next word and off a surrogate pair. */
    private fun windowStart(
        line: String,
        from: Int,
        before: Int,
    ): Int {
        var start = (from - before).coerceAtLeast(0)
        if (start > 0) {
            val space = line.indexOf(' ', start)
            if (space in start until from) start = space + 1
        }
        if (start > 0 && Character.isLowSurrogate(line[start])) start--
        return start
    }

    /** Where it closes: [max] on from [start], never before the hit ends, moved back to a word end and off a surrogate pair. */
    private fun windowEnd(
        line: String,
        start: Int,
        hit: IntRange?,
        max: Int,
    ): Int {
        var end = (start + max).coerceAtMost(line.length)
        val hitEnd = hit?.let { it.last + 1 } ?: start
        if (end < hitEnd) end = hitEnd.coerceAtMost(line.length)
        if (end < line.length) {
            val space = line.lastIndexOf(' ', end)
            if (space > (hit?.last ?: start)) end = space
        }
        if (end < line.length && Character.isLowSurrogate(line[end])) end++
        return end
    }

    /** [text] folded as [SearchQuery.fold] does, with each folded char mapped back to the original chars it came from. */
    private class Folded(
        val text: String,
        private val starts: IntArray,
        private val ends: IntArray,
    ) {
        /** The original-text range of the earliest token-start occurrence of any token, or null. */
        fun firstHit(tokens: List<String>): IntRange? {
            var bestAt = -1
            var bestLength = 0
            for (token in tokens) {
                if (token.isEmpty()) continue
                var at = text.indexOf(token)
                while (at >= 0 && !SearchQuery.isTokenStart(text, at)) at = text.indexOf(token, at + 1)
                if (at >= 0 && (bestAt < 0 || at < bestAt)) {
                    bestAt = at
                    bestLength = token.length
                }
            }
            if (bestAt < 0) return null
            return starts[bestAt]..(ends[bestAt + bestLength - 1] - 1)
        }

        companion object {
            fun of(line: String): Folded {
                val out = StringBuilder(line.length)
                val starts = ArrayList<Int>(line.length)
                val ends = ArrayList<Int>(line.length)
                var i = 0
                while (i < line.length) {
                    val cp = line.codePointAt(i)
                    val width = Character.charCount(cp)
                    val folded = SearchQuery.fold(String(Character.toChars(cp)))
                    for (ch in folded) {
                        out.append(ch)
                        starts += i
                        ends += i + width
                    }
                    i += width
                }
                return Folded(out.toString(), starts.toIntArray(), ends.toIntArray())
            }
        }
    }
}
