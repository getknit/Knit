package app.getknit.knit.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search over the seeded history: a message hit opens its thread on that message, people and chats match by
 * name, a blocked peer and a stranger's request are in no section, and the magnifier on the chat list opens
 * the screen.
 */
@RunWith(AndroidJUnit4::class)
class SearchInstrumentedTest : SeededUiTest() {
    @Test
    fun aMessageHitOpensItsThreadOnThatMessage() {
        launch("search")

        awaitTag("search_input")
        compose.onNodeWithTag("search_input").performTextInput("water bottle")
        // Sam's "Oh and I found your water bottle 💧" (DemoScenario.hiking).
        awaitTag("search_result_message_demo-dm-sam-3")
        compose.onNodeWithTag("search_result_message_demo-dm-sam-3").performClick()

        awaitTag("chat_thread")
        awaitText("found your water bottle")
    }

    @Test
    fun peopleAndChatsMatchByName() {
        launch("search")

        awaitTag("search_input")
        compose.onNodeWithTag("search_input").performTextInput("sam")
        awaitTag("search_result_person_samr1v00")
        awaitTag("search_result_chat_samr1v00")
    }

    @Test
    fun aGroupMatchesByItsName() {
        launch("search")

        awaitTag("search_input")
        compose.onNodeWithTag("search_input").performTextInput("trailhead")
        // The group's id is derived from its roster, so the row is found by its spoken title.
        awaitContentDescription("Trailhead Crew")
    }

    @Test
    fun aBlockedPeerAndAStrangersRequestAreInNoSection() {
        launch("search")

        awaitTag("search_input")
        // Marlo K. is seeded blocked (DemoScenario.blocked).
        compose.onNodeWithTag("search_input").performTextInput("marlo")
        awaitTag("search_empty")

        compose.onNodeWithTag("search_clear").performClick()
        // River Salas is a stranger whose DM is still a message request.
        compose.onNodeWithTag("search_input").performTextInput("salas")
        awaitTag("search_empty")
    }

    @Test
    fun theMagnifierOnTheChatListOpensSearch() {
        launch()

        awaitTag("chatlist_search")
        compose.onNodeWithTag("chatlist_search").performClick()
        awaitTag("screen_search")
    }
}
