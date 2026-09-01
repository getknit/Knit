package app.getknit.knit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLimitsTest {
    @Test
    fun `trims leading and trailing whitespace`() {
        assertEquals("hi", normalizeSingleLine("  hi  "))
    }

    @Test
    fun `collapses interior whitespace runs to a single space`() {
        assertEquals("hello world", normalizeSingleLine("hello    world"))
    }

    @Test
    fun `collapses stray newlines and tabs from a paste`() {
        assertEquals("a b c", normalizeSingleLine("a\n\tb  \r\n c"))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", normalizeSingleLine(""))
    }

    @Test
    fun `all-whitespace input normalizes to empty`() {
        assertEquals("", normalizeSingleLine("   \n\t "))
    }

    @Test
    fun `a single token is unchanged`() {
        assertEquals("knit", normalizeSingleLine("knit"))
    }

    // --- isValidReactionEmoji: the length-only cap shared by the sender and both inbound paths ---

    @Test
    fun `the longest RGI sequences are well under the reaction cap`() {
        // The cap's rationale: ~2x the worst case Unicode ships today, so it is never the limiting factor.
        assertEquals(15, KISS_WITH_TONES.length)
        assertEquals(14, ENGLAND_FLAG.length)
        assertTrue(isValidReactionEmoji("👍"))
        assertTrue(isValidReactionEmoji("❤️"))
        assertTrue(isValidReactionEmoji(KISS_WITH_TONES))
        assertTrue(isValidReactionEmoji(ENGLAND_FLAG))
    }

    @Test
    fun `exactly the cap is accepted and one more unit is refused`() {
        assertTrue(isValidReactionEmoji("👍".repeat(TextLimits.REACTION / 2))) // 32 units
        assertFalse(isValidReactionEmoji("👍".repeat(TextLimits.REACTION / 2 + 1))) // 34 units
    }

    @Test
    fun `blank is refused rather than read as a retraction`() {
        assertFalse(isValidReactionEmoji(""))
        assertFalse(isValidReactionEmoji(" "))
    }

    private companion object {
        /** 👩🏽‍❤️‍💋‍👨🏼 — a two-person kiss with skin tones, the longest RGI ZWJ sequence (10 code points, 35 B UTF-8). */
        const val KISS_WITH_TONES = "\uD83D\uDC69\uD83C\uDFFD\u200D\u2764\uFE0F\u200D\uD83D\uDC8B\u200D\uD83D\uDC68\uD83C\uDFFC"

        /** 🏴󠁧󠁢󠁥󠁮󠁧󠁿 — England, a tag-sequence subdivision flag (7 code points, 28 B UTF-8). */
        const val ENGLAND_FLAG = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
    }
}
