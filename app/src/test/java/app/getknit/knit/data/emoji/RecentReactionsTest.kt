package app.getknit.knit.data.emoji

import app.getknit.knit.ui.chat.emojiOnlyCount
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentReactionsTest {
    @Test
    fun unsetOrBlankDecodesToTheClassicSix() {
        assertEquals(RecentReactions.DEFAULTS, RecentReactions.decode(null))
        assertEquals(RecentReactions.DEFAULTS, RecentReactions.decode(""))
        assertEquals(RecentReactions.DEFAULTS, RecentReactions.decode(RecentReactions.SEPARATOR.toString()))
    }

    @Test
    fun encodeDecodeRoundTripsIncludingSequences() {
        val recents = listOf("🦄", "👨‍👩‍👧‍👦", "🏴󠁧󠁢󠁳󠁣󠁴󠁿", "❤️", "1️⃣")
        assertEquals(recents, RecentReactions.decode(RecentReactions.encode(recents)))
    }

    @Test
    fun pushFrontsDedupesAndCaps() {
        val start = RecentReactions.DEFAULTS
        assertEquals(listOf("🦄") + start, RecentReactions.push(start, "🦄"))
        assertEquals(listOf("😂", "👍", "❤️", "😮", "😢", "🙏"), RecentReactions.push(start, "😂"))
        var list = start
        repeat(20) { list = RecentReactions.push(list, "e$it") }
        assertEquals(RecentReactions.KEPT, list.size)
        assertEquals("e19", list.first())
    }

    @Test
    fun theDefaultsAreSixDistinctSingleEmoji() {
        assertEquals(RecentReactions.SHOWN, RecentReactions.DEFAULTS.size)
        assertEquals(RecentReactions.DEFAULTS.size, RecentReactions.DEFAULTS.toSet().size)
        RecentReactions.DEFAULTS.forEach { assertEquals(1, emojiOnlyCount(it)) }
    }
}
