package app.getknit.knit.data.message

import app.getknit.knit.ui.msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Meshtastic room's title rule, shared by the thread header and the chat-list row. */
class MeshRoomNamingTest {
    private val rows =
        listOf(
            msg(senderId = "me", sentAt = 1, conversationId = Conversations.MESHTASTIC, originNode = 1, originChannel = "LongFast"),
            msg(senderId = "me", sentAt = 2, conversationId = Conversations.MESHTASTIC, originNode = 1, originChannel = "LongTurbo"),
            msg(senderId = "me", sentAt = 3, conversationId = Conversations.MESHTASTIC, originNode = 1, originChannel = ""),
        )

    @Test
    fun `the live board wins, then the newest channel a post named, then nothing`() {
        assertEquals("MediumFast", meshRoomChannel("MediumFast", "LongTurbo"))
        assertEquals("a blank live name says nothing", "LongTurbo", meshRoomChannel("", "LongTurbo"))
        assertEquals("LongTurbo", meshRoomChannel(null, "LongTurbo"))
        assertNull(meshRoomChannel(null, null))
        assertNull("a blank stored name says nothing either", meshRoomChannel(null, " "))
    }

    @Test
    fun `the newest post that named a channel is the window's channel`() {
        // The thread header reads this off its window; the chat list asks the database the same question.
        assertEquals("LongTurbo", newestOriginChannel(rows))
        assertNull(newestOriginChannel(emptyList()))
        assertNull(newestOriginChannel(listOf(rows.last())))
    }
}
