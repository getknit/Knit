package app.getknit.knit.uiauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box search: the magnifier opens the screen, a typed word finds Sam's seeded message, tapping it opens
 * the thread, and Back returns to the results with the query still in the field — the one thing the
 * in-process suite cannot see, because it needs the real back stack.
 */
@RunWith(AndroidJUnit4::class)
class SearchUiAutomatorTest : SeededUiAutomatorTest() {
    @Test
    fun search_findsAMessageAndKeepsTheQueryAcrossBack() {
        launch()
        requireDesc(str(R.string.search_title)).click()
        requireTag("search_input").text = "water"
        // The IME's Search key drops the keyboard, so the Back below pops the thread rather than the keyboard.
        device.pressEnter()

        requireTag("search_result_message_demo-dm-sam-3").click()
        assertTag("chat_thread")

        device.pressBack()
        assertEquals("the query survives Back", "water", requireTag("search_input").text)
        device.pressBack()
        assertTag("chat_row_nearby")
    }
}
