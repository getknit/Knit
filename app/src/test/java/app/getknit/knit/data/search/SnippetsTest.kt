package app.getknit.knit.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-line window a message hit shows, and where the match sits in it. */
class SnippetsTest {
    private fun tokens(query: String) = SearchQuery.tokens(query)

    private fun Snippets.Snippet.matched() = text.substring(hit!!)

    @Test
    fun `a short body is returned whole with the hit located`() {
        val s = Snippets.around("Oh and I found your water bottle", tokens("water"))
        assertEquals("Oh and I found your water bottle", s.text)
        assertEquals("water", s.matched())
    }

    @Test
    fun `matching folds case and diacritics both ways`() {
        assertEquals("Café", Snippets.around("Meet at the Café later", tokens("cafe")).matched())
        assertEquals("cafe", Snippets.around("meet at the cafe later", tokens("Café")).matched())
        assertEquals("WATER", Snippets.around("WATER bottle", tokens("water")).matched())
    }

    @Test
    fun `a hit lands only where a token starts`() {
        assertEquals(13..15, Snippets.around("start of the art", tokens("art")).hit)
        assertNull(Snippets.around("start", tokens("art")).hit)
    }

    @Test
    fun `a long body is windowed around the first hit, on word boundaries, with ellipses`() {
        val filler = (1..60).joinToString(" ") { "word$it" }
        val s = Snippets.around("$filler needle in the haystack $filler", tokens("needle"))
        assertTrue(s.text.startsWith("…"))
        assertTrue(s.text.endsWith("…"))
        assertTrue(s.text.length <= Snippets.DEFAULT_MAX + 2)
        assertEquals("needle", s.matched())
        assertFalse("cut on a word boundary, not mid-word", s.text.startsWith("… ") || s.text.endsWith(" …"))
        assertTrue(s.text.substring(1, s.hit!!.first).startsWith("word"))
    }

    @Test
    fun `without a hit the head of the body is shown`() {
        val body = (1..60).joinToString(" ") { "word$it" }
        val s = Snippets.around(body, tokens("zzz"))
        assertNull(s.hit)
        assertTrue(s.text.startsWith("word1 word2"))
        assertTrue(s.text.endsWith("…"))
        assertEquals("nothing", Snippets.around("nothing", emptyList()).text)
    }

    @Test
    fun `whitespace collapses to one line`() {
        val s = Snippets.around("  first line\n\n  second\tline ", tokens("second"))
        assertEquals("first line second line", s.text)
        assertEquals("second", s.matched())
    }

    @Test
    fun `the earliest hit of any token anchors the window`() {
        assertEquals("bottle", Snippets.around("bottle first, then water", tokens("water bottle")).matched())
    }

    @Test
    fun `surrogate pairs keep their indices`() {
        assertEquals("water", Snippets.around("🙂🙂 café 🙂 water", tokens("water")).matched())
        assertEquals("café", Snippets.around("🙂🙂 café 🙂 water", tokens("cafe")).matched())
        val filler = "🙂".repeat(120)
        val s = Snippets.around("$filler water $filler", tokens("water"))
        assertEquals("water", s.matched())
        assertFalse("never cut inside a pair", s.text.any { Character.isSurrogate(it) } && s.text.codePoints().anyMatch { it == 0xFFFD })
    }

    @Test
    fun `a CJK query matches only at the start of a run, as the index does`() {
        assertEquals("東京", Snippets.around("東京に行く", tokens("東京")).matched())
        assertNull(Snippets.around("明日は東京に行く", tokens("東京")).hit)
    }
}
