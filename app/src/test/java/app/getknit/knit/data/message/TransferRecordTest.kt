package app.getknit.knit.data.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transfer record's JSON body and the notice-shaped chat row it becomes. */
class TransferRecordTest {
    private val record =
        TransferRecord(
            id = "t1",
            outgoing = false,
            name = "clip.mp4",
            size = 123_456_789L,
            mime = "video/mp4",
            phase = TransferPhase.Done,
            savedUri = "content://media/external/downloads/42",
        )

    @Test
    fun encodeThenDecodeRoundTripsAndGarbageDecodesToNull() {
        assertEquals(record, TransferRecord.decode(record.encode()))
        assertNull(TransferRecord.decode("not json"))
        assertNull(TransferRecord.decode("{\"id\":\"t1\"}"))
    }

    @Test
    fun theRowIsANoticeShapedDmRowFromWhoeverOffered() {
        val incoming = record.toEntity(peerId = "peer", selfId = "me", sentAt = 5L)
        assertEquals("xfer:t1", incoming.id)
        assertEquals("peer", incoming.senderId)
        assertEquals("me", incoming.recipientId)
        assertEquals("peer", incoming.conversationId)
        assertEquals(MessageEntity.KIND_FILE_TRANSFER, incoming.kind)
        assertTrue(incoming.received)
        assertTrue(incoming.isStatusNotice)
        assertEquals(record, TransferRecord.decode(incoming.body))
        val outgoing = record.copy(outgoing = true).toEntity(peerId = "peer", selfId = "me", sentAt = 5L)
        assertEquals("me", outgoing.senderId)
        assertEquals("peer", outgoing.recipientId)
    }

    @Test
    fun onlyTheFivePhasesThatEndATransferAreTerminal() {
        val terminal = TransferPhase.entries.filter { it.terminal }
        assertEquals(
            listOf(TransferPhase.Done, TransferPhase.Declined, TransferPhase.Expired, TransferPhase.Cancelled, TransferPhase.Failed),
            terminal,
        )
        assertFalse(TransferPhase.Offered.terminal)
    }
}
