package app.getknit.knit.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The query fold, tokeniser and FTS expression every search surface shares. */
class SearchQueryTest {
    @Test
    fun `fold decomposes, drops the Latin diacritics and lower-cases without changing length`() {
        assertEquals("cafe", SearchQuery.fold("Café"))
        assertEquals("jose", SearchQuery.fold("JOSÉ"))
        assertEquals("cafe", SearchQuery.fold("café")) // already decomposed
        // A Devanagari virama is a mark outside the Latin combining block: part of its word, kept.
        assertEquals("क्ष", SearchQuery.fold("क्ष"))
        assertEquals("i", SearchQuery.fold("İ")) // a simple mapping, never the two-char lowercase()
        // A Hangul syllable decomposes under NFD too — into jamo the index never sees — so it stays whole.
        assertEquals("한국", SearchQuery.fold("한국"))
        assertEquals("привет", SearchQuery.fold("Привет"))
        assertEquals("ǆ", SearchQuery.fold("Ǆ"))
    }

    @Test
    fun `tokens split on whitespace and punctuation, fold, dedupe and keep order`() {
        assertEquals(listOf("sam", "rivera"), SearchQuery.tokens("  Sam, Rivera! "))
        assertEquals(listOf("hi"), SearchQuery.tokens("hi hi HI"))
        assertEquals(listOf("cafe", "2pm"), SearchQuery.tokens("café@2pm"))
        assertEquals(emptyList<String>(), SearchQuery.tokens(" \n\t"))
    }

    @Test
    fun `no FTS operator survives tokenising, and the keywords become plain terms`() {
        val match = SearchQuery.toMatch("\"quoted\" OR (grouped) -negated col:filter ^anchored star* NOT")!!
        assertEquals("quoted* or* grouped* negated* col* filter* anchored* star*", match)
        assertFalse(match.any { it in "\"()-:^" })
    }

    @Test
    fun `toMatch emits one prefix term per token`() {
        assertEquals("sam* riv*", SearchQuery.toMatch("Sam riv"))
        assertEquals("cafe*", SearchQuery.toMatch("Café"))
        // A short token rides along once a searchable one exists: "a cat" still narrows to both.
        assertEquals("a* cat*", SearchQuery.toMatch("a cat"))
    }

    @Test
    fun `toMatch is null when there is nothing worth asking the index`() {
        assertNull(SearchQuery.toMatch(""))
        assertNull(SearchQuery.toMatch("   "))
        assertNull(SearchQuery.toMatch("!!! ... \"\""))
        assertNull(SearchQuery.toMatch("🙂"))
        assertNull("one ASCII letter would match half the table", SearchQuery.toMatch("a"))
        assertFalse(SearchQuery.isSearchable("a"))
        assertTrue(SearchQuery.isSearchable("ab"))
    }

    @Test
    fun `a lone ideograph, kana or hangul syllable is a word`() {
        assertEquals("京*", SearchQuery.toMatch("京"))
        assertEquals("あ*", SearchQuery.toMatch("あ"))
        assertEquals("한*", SearchQuery.toMatch("한"))
    }

    @Test
    fun `long tokens are truncated and the token count is capped`() {
        assertEquals(64, SearchQuery.tokens("x".repeat(100)).single().length)
        assertEquals(SearchQuery.MAX_TOKENS, SearchQuery.tokens((1..20).joinToString(" ") { "t$it" }).size)
    }

    @Test
    fun `matches needs every token in some field`() {
        val tokens = SearchQuery.tokens("riv sam")
        assertTrue(SearchQuery.matches(tokens, SearchQuery.fold("Sam Rivera")))
        assertTrue(SearchQuery.matches(tokens, SearchQuery.fold("Sam"), SearchQuery.fold("River Alias")))
        assertFalse(SearchQuery.matches(tokens, SearchQuery.fold("Sam")))
        assertFalse(SearchQuery.matches(emptyList(), "anything"))
    }

    @Test
    fun `rank orders exact before prefix before word before substring`() {
        val tokens = SearchQuery.tokens("sam")
        val exact = SearchQuery.rank("sam", tokens)
        val prefix = SearchQuery.rank("samantha", tokens)
        val word = SearchQuery.rank("dr sam rivera", tokens)
        val substring = SearchQuery.rank("balsam", tokens)
        assertTrue(exact < prefix && prefix < word && word < substring)
    }

    @Test
    fun `isTokenStart is the first character or one after a separator`() {
        assertTrue(SearchQuery.isTokenStart("start", 0))
        assertFalse(SearchQuery.isTokenStart("start", 2))
        assertTrue(SearchQuery.isTokenStart("a start", 2))
        assertTrue(SearchQuery.isTokenStart("🙂start", 2))
    }
}
