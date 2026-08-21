package app.getknit.knit.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * What the details screen owes the person who opened it: every reactor by name (the thing a "👍 3" chip
 * cannot say), a filter row that actually narrows to one emoji, an empty state when nobody reacted, and
 * your own row inert while another reactor opens their profile.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageDetailsScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        state: MessageDetailsUiState,
        onOpenProfile: (String) -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                MessageDetailsScreenContent(state = state, onBack = {}, onOpenProfile = onOpenProfile)
            }
        }
    }

    @Test
    fun `lists every reactor by name with the emoji they left`() {
        render(populated())

        compose.onNodeWithText("Sam Rivera").assertIsDisplayed()
        compose.onNodeWithText("Priya Nair").assertIsDisplayed()
        compose.onNodeWithTag("reactor_row_theo").assertIsDisplayed()
        // The local user's row is labelled "You", not their own display name.
        compose.onNodeWithText("You").assertIsDisplayed()
    }

    @Test
    fun `selecting an emoji chip narrows the list to that emoji's reactors`() {
        render(populated())

        compose.onNodeWithTag("message_details_filter_❤️").performClick()

        compose.onNodeWithTag("reactor_row_me").assertIsDisplayed()
        compose.onNodeWithTag("reactor_row_sam").assertDoesNotExist()

        compose.onNodeWithTag("message_details_filter_all").performClick()
        compose.onNodeWithTag("reactor_row_sam").assertIsDisplayed()
    }

    @Test
    fun `tapping another reactor opens their profile, your own row is inert`() {
        var opened: String? = null
        render(populated(), onOpenProfile = { opened = it })

        compose.onNodeWithTag("reactor_row_me").performClick()
        assertEquals(null, opened)

        compose.onNodeWithTag("reactor_row_sam").performClick()
        assertEquals("sam", opened)
    }

    @Test
    fun `a message with no reactions says so instead of showing an empty list`() {
        render(MessageDetailsUiState(messageId = "m1", body = "hi", senderName = "Sam Rivera"))

        compose.onNodeWithTag("message_details_no_reactions").assertIsDisplayed()
        compose.onNodeWithTag("message_details_filter_all").assertDoesNotExist()
    }

    @Test
    fun `the body, sent time and delivery state are all shown`() {
        render(populated())

        compose.onNodeWithTag("message_details_body").assertIsDisplayed()
        compose.onNodeWithTag("message_details_sent_at").assertIsDisplayed()
        compose.onNodeWithTag("message_details_delivery").assertIsDisplayed()
        compose.onNodeWithText("Delivered over the Internet").assertIsDisplayed()
    }

    @Test
    fun `a content-filtered body stays collapsed here too`() {
        render(populated().copy(body = "something nasty", moderationFlagged = true))

        compose.onNodeWithText("something nasty").assertDoesNotExist()
    }

    private fun populated() =
        MessageDetailsUiState(
            messageId = "m1",
            body = "Works for me. I'll grab snacks.",
            mine = true,
            senderName = "You",
            senderNodeId = "me",
            sentAt = 1_700_000_000_000L,
            delivery = DeliveryStatus.Delivered,
            plane = DeliveryPlane.Internet,
            reactors =
                listOf(
                    ReactorRow("sam", "Sam Rivera", null, "👍", 10L, isSelf = false),
                    ReactorRow("priya", "Priya Nair", null, "👍", 20L, isSelf = false),
                    ReactorRow("theo", "Theo Diaz", null, "👍", 30L, isSelf = false),
                    ReactorRow("me", "Alice", null, "❤️", 40L, isSelf = true),
                ),
            filters = listOf(ReactionFilter("👍", 3), ReactionFilter("❤️", 1)),
        )
}
