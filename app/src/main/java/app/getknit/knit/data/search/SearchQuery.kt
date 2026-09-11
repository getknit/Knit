package app.getknit.knit.data.search

import java.text.Normalizer

/**
 * What a typed search query means, decided once for every surface that matches text: the fold that makes
 * `Café` and `cafe` the same word, the tokens a query is made of, and the FTS4 `MATCH` expression the
 * message index is asked. Pure Kotlin, no Android.
 *
 * The fold mirrors the `unicode61` tokenizer the `messages_fts` index uses, so an in-memory match over a
 * chat title and an index match over a body agree: canonical decomposition, the Latin combining
 * diacritics dropped (only those — a Devanagari vowel sign or virama is part of its word and must stay),
 * and a per-code-point lower-casing that never changes a string's length. A token is a maximal run of
 * letters, digits and marks; everything else — whitespace, punctuation, and every character FTS gives
 * meaning to (`"`, `*`, `(`, `)`, `-`, `:`, `^`) — is a separator, which is what makes [toMatch] total:
 * no input can reach the index as an operator, and lower-casing turns `AND`, `OR`, `NOT` and `NEAR` into
 * ordinary terms. The expression is only ever `term* term*` — implicit AND, one trailing prefix star per
 * term — which the standard and the enhanced FTS query syntaxes read identically.
 */
object SearchQuery {
    /** The shortest token worth asking the index for: one ASCII letter would prefix-match half the table. */
    const val MIN_LENGTH = 2

    /** A query longer than this is a sentence, not a search; the rest is ignored. */
    const val MAX_TOKENS = 8

    private const val MAX_TOKEN_LENGTH = 64
    private const val ASCII_LIMIT = 0x80
    private const val FIRST_DIACRITIC = 0x0300
    private const val LAST_DIACRITIC = 0x036F

    /**
     * [text] folded for matching, code point by code point: a letter carrying only Latin diacritics becomes
     * its base letter (`é` → `e`), a bare combining diacritic is dropped, everything else keeps its shape
     * and is lower-cased with the simple one-to-one mapping. Decomposition is decided per code point on
     * purpose — a Hangul syllable also decomposes under NFD, into jamo the index never sees, so it must
     * stay whole — and the string never changes length in a way [Snippets] cannot map back.
     */
    fun fold(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            appendFolded(out, cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun appendFolded(
        out: StringBuilder,
        cp: Int,
    ) {
        if (cp < ASCII_LIMIT) {
            out.append(Character.toLowerCase(cp.toChar()))
            return
        }
        if (isDiacritic(cp)) return
        val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
        val base = decomposed.codePointAt(0)
        var j = Character.charCount(base)
        var onlyDiacritics = true
        while (j < decomposed.length) {
            val mark = decomposed.codePointAt(j)
            if (!isDiacritic(mark)) {
                onlyDiacritics = false
                break
            }
            j += Character.charCount(mark)
        }
        out.appendCodePoint(Character.toLowerCase(if (onlyDiacritics) base else cp))
    }

    /** The folded tokens of [raw], in order, deduplicated, at most [MAX_TOKENS]; empty for a blank query. */
    fun tokens(raw: String): List<String> {
        val folded = fold(raw)
        val out = LinkedHashSet<String>()
        val run = StringBuilder()

        fun flush() {
            if (run.isNotEmpty()) out += run.substring(0, minOf(run.length, MAX_TOKEN_LENGTH))
            run.setLength(0)
        }
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            if (isTokenChar(cp)) run.appendCodePoint(cp) else flush()
            i += Character.charCount(cp)
        }
        flush()
        return out.take(MAX_TOKENS)
    }

    /**
     * The FTS4 expression for [raw], or null when there is nothing worth asking: no token at all, or none
     * at least [MIN_LENGTH] long — a lone ideograph, kana or hangul syllable counts as a word and passes.
     */
    fun toMatch(raw: String): String? {
        val tokens = tokens(raw)
        if (tokens.none { isSearchable(it) }) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    /** Whether [raw] is a query the message index will be asked — [toMatch] would be non-null. */
    fun isSearchable(raw: String): Boolean = tokens(raw).any { isSearchableToken(it) }

    /**
     * Whether every token occurs in some [folded] field — "riv sam" finds "Sam Rivera". The fields are
     * already folded (fold once per row, not once per keystroke); [tokens] are [tokens]'s.
     */
    fun matches(
        tokens: List<String>,
        vararg folded: String,
    ): Boolean = tokens.isNotEmpty() && tokens.all { token -> folded.any { it.contains(token) } }

    /**
     * Where a matching [folded] field sorts: the whole field is the query, then a prefix of it, then a word
     * of it starts with the first token, then a bare substring — the emoji picker's ladder.
     */
    fun rank(
        folded: String,
        tokens: List<String>,
    ): Int {
        val q = tokens.joinToString(" ")
        val first = tokens.firstOrNull() ?: return RANK_SUBSTRING
        return when {
            folded == q -> RANK_EXACT
            folded.startsWith(q) -> RANK_PREFIX
            words(folded).any { it.startsWith(first) } -> RANK_WORD
            else -> RANK_SUBSTRING
        }
    }

    /** Whether the character at [index] of [folded] starts a token: the first character, or one after a separator. */
    fun isTokenStart(
        folded: CharSequence,
        index: Int,
    ): Boolean = index == 0 || !isTokenChar(Character.codePointBefore(folded, index))

    /** A letter, digit or mark — what a token is made of; [isTokenStart] and the snippet share the definition. */
    fun isTokenChar(cp: Int): Boolean =
        Character.isLetterOrDigit(cp) ||
            when (Character.getType(cp)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                Character.LETTER_NUMBER.toInt(),
                Character.OTHER_NUMBER.toInt(),
                -> true

                else -> false
            }

    private fun isSearchableToken(token: String): Boolean =
        token.length >= MIN_LENGTH ||
            (token.isNotEmpty() && token.codePointCount(0, token.length) == 1 && isIdeographic(token.codePointAt(0)))

    private fun isIdeographic(cp: Int): Boolean =
        when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            -> true

            else -> false
        }

    private fun isDiacritic(cp: Int): Boolean = cp in FIRST_DIACRITIC..LAST_DIACRITIC

    private fun words(folded: String): List<String> = tokens(folded)

    private const val RANK_EXACT = 0
    private const val RANK_PREFIX = 1
    private const val RANK_WORD = 2
    private const val RANK_SUBSTRING = 3
}
