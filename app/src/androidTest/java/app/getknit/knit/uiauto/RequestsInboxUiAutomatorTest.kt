package app.getknit.knit.uiauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box coverage of the Message Requests inbox reached via the **in-app chat-list badge** — the sibling
 * of [MessageRequestNotificationUiAutomatorTest], which enters through the system notification shade — plus
 * the accept-then-open-the-thread hop and the per-row Block confirm path. The debug `REQNOTIF` seam injects
 * a synthetic unaccepted stranger DM *alongside* the requests the seed already writes (River's DM and the
 * "Ridge Run 2026" group), so every assertion here is per-row rather than about the inbox as a whole; no
 * `POST_NOTIFICATIONS` grant is needed here because these enter through the UI, not the shade (on API 33 the
 * heads-up simply no-ops without the grant, so nothing overlays the badge).
 */
@RunWith(AndroidJUnit4::class)
class RequestsInboxUiAutomatorTest : SeededUiAutomatorTest() {
    /** Injecting a request lights the chat-list badge; tapping it opens the populated Requests inbox. */
    @Test
    fun requestBadge_opensInbox() {
        launch() // seeded chat list; the seed's own requests already light the badge
        injectRequest()
        // The badge appears only when a request is pending; it lights reactively once the row lands.
        requireTag("chatlist_requests").click()
        assertText(str(R.string.message_requests_title))
        assertTag("request_row_$STRANGER")
        assertText(STRANGER_NAME)
    }

    /**
     * Accepting a request opens the thread it belongs to — the inbox is popped on the way, so Back from
     * the chat lands on the chat list rather than back in the (now shorter) inbox.
     */
    @Test
    fun request_accept_opensTheAcceptedThread() {
        launch()
        injectRequest()
        requireTag("chatlist_requests").click()
        requireTag("request_accept_$STRANGER").click()

        // We're in the stranger's thread: the composer is up and the top bar names them.
        assertTag("chat_input")
        assertText(STRANGER_NAME)

        // Back leaves the chat for the chat list, not the inbox we accepted from.
        device.pressBack()
        assertTag("chatlist_fab")
    }

    /** Blocking a request (row overflow → Block → confirm) removes that row and leaves the others alone. */
    @Test
    fun request_blockPath_removesFromInbox() {
        launch()
        injectRequest()
        requireTag("chatlist_requests").click()
        val strangerRow = requireTag("request_row_$STRANGER")

        // Scope the overflow to the stranger's own row → the "Block" item → the confirm dialog. The seed
        // puts River's DM and the "Ridge Run 2026" group in this inbox too, so there are three MoreVerts
        // here and an unscoped selector would open whichever one the tree yields first.
        requireDescIn(strangerRow, str(R.string.chat_more_options)).click()
        requireText(str(R.string.message_requests_block)).click()
        requireText(str(R.string.message_requests_block_confirm_title)) // "Block this person?" dialog is up
        // Exact match: the confirm button "Block" is a substring of the title "Block this person?".
        requireExactText(str(R.string.message_requests_block)).click()

        // The blocked request leaves the inbox — and only it does, which is the half an empty-state
        // assertion could never make: Block is targeted at one peer, not a clear-all.
        assertTagGone("request_row_$STRANGER")
        assertTag("request_row_$SEEDED_REQUEST")
    }

    /** Fires the debug seam that writes one synthetic unaccepted inbound DM (a "message request"). */
    private fun injectRequest() {
        device.executeShellCommand("am broadcast -p $PKG -a $REQNOTIF_ACTION --ei count 1")
    }

    private companion object {
        const val REQNOTIF_ACTION = "app.getknit.knit.debug.REQNOTIF"

        // Mirrors DebugBridgeReceiver.handleReqNotif's first synthetic stranger (nodeId + name).
        const val STRANGER = "strngr01"
        const val STRANGER_NAME = "Alex Stranger"

        /**
         * A request the seed writes and this test never acts on — `DemoSeeder.RIVER`, whose unanswered DM
         * is seeded unaccepted (`DemoWriter.seedRequests`, where a DM request's conversationId is the peer
         * node id). Blocking the stranger must leave it standing.
         */
        const val SEEDED_REQUEST = "river7x2"
    }
}
