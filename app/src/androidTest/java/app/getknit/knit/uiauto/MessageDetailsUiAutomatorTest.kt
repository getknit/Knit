package app.getknit.knit.uiauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box coverage of the one path issue #5 actually asks for: long-press a message → "Message info" →
 * see *who* reacted, and — for a message you sent to a group — *who has it*. It has to be driven from here
 * rather than the Compose suite because the long-press
 * menu is a `Popup` — a separate window that doesn't inherit the NavHost's `testTagsAsResourceId`, so its
 * items are addressed by their (localized) text, exactly as the group-management and overflow tests do.
 *
 * The seeded "Trailhead Crew" message is one of ours, carries three reactors across two emoji, and is
 * delivered to two of its three other members (`DemoScenarios`), so this exercises the populated screen on
 * both halves — the delivered/waiting split as well as the reactor list — and the reactor tap proves the
 * profile hop.
 */
@RunWith(AndroidJUnit4::class)
class MessageDetailsUiAutomatorTest : SeededUiAutomatorTest() {
    @Test
    fun longPress_messageInfo_listsReactorsAndOpensAProfile() {
        openGroupChat()

        requireText(SEEDED_BODY).longClick() // the bubble's long-press menu (a Popup)
        requireText(str(R.string.chat_action_details)).click() // "Message info"

        assertText(str(R.string.message_details_title))
        assertTag("screen_message_details")
        // The ✓✓ said "delivered"; this is the part it could never say — two of the three others have it,
        // and the third is named rather than silently folded into the tick.
        assertTag("message_details_delivered_header")
        assertTag("recipient_row_$SAM")
        assertTag("recipient_row_$THEO")
        // The chip said "👍 3"; this is the part it could never say.
        assertTag("reactor_row_$SAM")
        assertText(SAM_NAME)

        requireTag("reactor_row_$SAM").click()
        assertText(str(R.string.profile_details_title))
    }

    /**
     * Chat list → the "Trailhead Crew" row → the group chat. Same cold-start retry as
     * [GroupManagementUiAutomatorTest.openGroupDetails]: the row tap can race the async seed still
     * reflowing rows, so the captured coordinate goes stale and the tap misses.
     */
    private fun openGroupChat() {
        repeat(OPEN_ATTEMPTS) {
            launch()
            requireTag("chat_row_nearby") // the seeded list is populated before we tap (seed is async)
            requireDesc(GROUP_NAME).click()
            if (waitTag("chat_group_avatar", OPEN_POLL_MS) != null) return
            // The row tap didn't open the group chat — cold-start and try again.
        }
        error("the '$GROUP_NAME' group chat was unreachable after $OPEN_ATTEMPTS attempts")
    }

    private companion object {
        const val GROUP_NAME = "Trailhead Crew"

        /** The seeded group message that carries the multi-reactor cluster (`DemoScenarios`, demo-group-4). */
        const val SEEDED_BODY = "Works for me"

        // DemoSeeder.SAM / the hiking scenario's name for that slot, spelled out so this test reads as the
        // black-box check it is (the uiauto suite never reaches into the debug seed classes).
        const val SAM = "samr1v00"
        const val SAM_NAME = "Sam Rivera"

        /** The one seeded member the message has NOT reached — the "waiting on" half. */
        const val THEO = "theod001"

        const val OPEN_ATTEMPTS = 3
        const val OPEN_POLL_MS = 12_000L
    }
}
