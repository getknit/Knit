package app.getknit.knit.ui.about

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.legal.License
import app.getknit.knit.legal.ThirdPartyNotices
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The licenses list: Knit first, then every notice under its section, each row opening its license. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LicensesScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<License>()

    private fun render() {
        compose.setContent {
            KnitTheme {
                LicensesScreenContent(notices = ThirdPartyNotices.ALL, onBack = {}, onOpenLicense = { opened += it })
            }
        }
    }

    @Test
    fun knitLeadsTheListUnderItsOwnLicense() {
        render()
        compose.onNodeWithTag("licenses_row_knit").assertIsDisplayed().assertTextContains("GPL-3.0-or-later", substring = true)
        compose.onNodeWithTag("licenses_row_knit").performClick()
        assertEquals(listOf(License.GPL_3_0_OR_LATER), opened)
    }

    @Test
    fun everyNoticeHasARowThatOpensItsLicense() {
        render()
        compose.onNodeWithText("Libraries").assertIsDisplayed()
        // A lazy list composes only what is on screen, so each row is scrolled to through the list itself.
        val list = compose.onNodeWithTag("licenses_list")
        ThirdPartyNotices.ALL.forEach { notice ->
            val tag = "licenses_row_${notice.key}"
            list.performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertTextContains(notice.name)
        }
        list.performScrollToNode(hasText("Bundled models and data"))
        compose.onNodeWithText("Bundled models and data").assertIsDisplayed()

        list.performScrollToNode(hasTestTag("licenses_row_sqlcipher"))
        compose.onNodeWithTag("licenses_row_sqlcipher").performClick()
        list.performScrollToNode(hasTestTag("licenses_row_emoji-catalog"))
        compose.onNodeWithTag("licenses_row_emoji-catalog").performClick()
        assertEquals(listOf(License.BSD_3_CLAUSE, License.UNICODE_3_0), opened)
    }
}
