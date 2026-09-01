package app.getknit.knit.data.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogSearchTest {
    private val thumbsUp = entry("👍", EmojiGroup.PEOPLE, "thumbs up")
    private val thumbsUpLight = entry("👍🏻", EmojiGroup.PEOPLE, "thumbs up: light skin tone", tone = true)
    private val thumbsDown = entry("👎", EmojiGroup.PEOPLE, "thumbs down")
    private val redHeart = entry("❤️", EmojiGroup.SMILEYS, "red heart")
    private val heartEyes = entry("😍", EmojiGroup.SMILEYS, "smiling face with heart-eyes")
    private val greenHeart = entry("💚", EmojiGroup.SMILEYS, "green heart")
    private val upArrow = entry("⬆️", EmojiGroup.SYMBOLS, "up arrow")
    private val cupcake = entry("🧁", EmojiGroup.FOOD, "cupcake")
    private val catalog = EmojiCatalog(listOf(redHeart, heartEyes, greenHeart, thumbsUp, thumbsUpLight, thumbsDown, cupcake, upArrow))

    @Test
    fun blankQueryIsEmpty() {
        assertTrue(catalog.search("").isEmpty())
        assertTrue(catalog.search("   ").isEmpty())
    }

    @Test
    fun caseInsensitiveWithBaseBeforeItsToneVariants() {
        assertEquals(listOf(thumbsUp, thumbsUpLight, thumbsDown), catalog.search("THUMBS"))
    }

    @Test
    fun ranksExactThenPrefixThenWordPrefixThenSubstring() {
        // "up": exact none; prefix "up arrow"; word-prefix "thumbs up", "thumbs up: light…"; substring "cupcake".
        assertEquals(listOf(upArrow, thumbsUp, thumbsUpLight, cupcake), catalog.search("up"))
        assertEquals(redHeart, catalog.search("red heart").first())
    }

    @Test
    fun everyTokenMustMatch() {
        assertEquals(listOf(redHeart), catalog.search("red heart"))
        assertEquals(listOf(redHeart, heartEyes, greenHeart), catalog.search("heart"))
    }

    @Test
    fun aPastedEmojiComesFirst() {
        assertEquals(listOf(thumbsUp), catalog.search(" 👍 "))
    }

    @Test
    fun limitIsHonoured() {
        assertEquals(2, catalog.search("heart", limit = 2).size)
    }

    @Test
    fun browseHidesToneVariantsAndByGroupKeepsGroupOrder() {
        assertEquals(listOf(redHeart, heartEyes, greenHeart, thumbsUp, thumbsDown, cupcake, upArrow), catalog.browse)
        assertEquals(listOf(EmojiGroup.SMILEYS, EmojiGroup.PEOPLE, EmojiGroup.FOOD, EmojiGroup.SYMBOLS), catalog.byGroup.keys.toList())
        assertTrue(EmojiGroup.FLAGS !in catalog.byGroup)
    }

    private fun entry(
        emoji: String,
        group: EmojiGroup,
        name: String,
        tone: Boolean = false,
    ) = EmojiEntry(emoji, group, name, tone)
}
