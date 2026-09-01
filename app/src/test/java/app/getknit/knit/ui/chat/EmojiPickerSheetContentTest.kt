package app.getknit.knit.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.emoji.EmojiCatalog
import app.getknit.knit.data.emoji.EmojiEntry
import app.getknit.knit.data.emoji.EmojiGroup
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * What the "more reactions" sheet owes the person who opened it: a grid they can pick from (and the pick
 * reaches the caller), a search that narrows it, an honest empty state, and a placeholder while the
 * catalog is still parsing. The catalog is a hand-built three-group one — the real asset is contract-tested
 * in `EmojiCatalogAssetTest`, and the renderability filter is the loader's job, not the sheet's.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmojiPickerSheetContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val catalog =
        EmojiCatalog(
            listOf(
                EmojiEntry("😀", EmojiGroup.SMILEYS, "grinning face", toneVariant = false),
                EmojiEntry("👍", EmojiGroup.PEOPLE, "thumbs up", toneVariant = false),
                EmojiEntry("👍🏽", EmojiGroup.PEOPLE, "thumbs up: medium skin tone", toneVariant = true),
                EmojiEntry("🦄", EmojiGroup.ANIMALS, "unicorn", toneVariant = false),
            ),
        )

    private fun render(
        catalog: EmojiCatalog?,
        onPick: (String) -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                EmojiPickerSheetContent(catalog = catalog, onPick = onPick)
            }
        }
    }

    @Test
    fun `browse shows the base emoji under group headers and hides tone variants`() {
        render(catalog)

        compose.onNodeWithTag("emoji_grid").assertIsDisplayed()
        compose.onNodeWithTag("emoji_cell_😀").assertIsDisplayed()
        compose.onNodeWithTag("emoji_cell_👍").assertIsDisplayed()
        compose.onNodeWithTag("emoji_cell_👍🏽").assertDoesNotExist()
        compose.onNodeWithTag("emoji_group_ANIMALS").assertIsDisplayed()
        compose.onNodeWithTag("emoji_group_FLAGS").assertDoesNotExist()
    }

    @Test
    fun `tapping a cell hands the emoji to the caller`() {
        val picked = mutableListOf<String>()
        render(catalog, onPick = { picked += it })

        compose.onNodeWithTag("emoji_cell_🦄").performClick()

        assertEquals(listOf("🦄"), picked)
    }

    @Test
    fun `search narrows the grid and reaches tone variants by name`() {
        render(catalog)

        compose.onNodeWithTag("emoji_search").performTextInput("thumb")

        compose.onNodeWithTag("emoji_cell_👍").assertIsDisplayed()
        compose.onNodeWithTag("emoji_cell_👍🏽").assertIsDisplayed()
        compose.onNodeWithTag("emoji_cell_😀").assertDoesNotExist()
        compose.onNodeWithTag("emoji_group_ANIMALS").assertDoesNotExist()
    }

    @Test
    fun `a query nothing matches shows the empty state, and clearing it restores the grid`() {
        render(catalog)

        compose.onNodeWithTag("emoji_search").performTextInput("zzz")
        compose.onNodeWithTag("emoji_search_empty").assertIsDisplayed()

        compose.onNodeWithTag("emoji_search_clear").performClick()
        compose.onNodeWithTag("emoji_cell_😀").assertIsDisplayed()
    }

    @Test
    fun `a catalog still loading shows the placeholder, not an empty grid`() {
        render(catalog = null)

        compose.onNodeWithTag("emoji_loading").assertIsDisplayed()
        compose.onNodeWithTag("emoji_grid").assertDoesNotExist()
    }
}
