package app.getknit.knit

import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.link.FragReassembler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [FragReassembler] — the bounded, clock-injected fragment reassembly store. */
class FragReassemblerTest {
    private var clock = 0L
    private val drops = mutableListOf<FragReassembler.Drop>()

    private fun reassembler(
        capacity: Int = 8,
        timeoutMs: Long = 5_000,
    ) = FragReassembler<String>(now = { clock }, capacity = capacity, timeoutMs = timeoutMs, onDrop = { drops += it })

    private fun frag(
        fragId: Int,
        part: Int,
        count: Int,
        payload: ByteArray,
    ) = FastFrameCodec.Fragment(fragId, part, count, payload)

    @Test
    fun partsInOrderReassemble() {
        val r = reassembler()
        assertNull(r.accept("a", frag(1, 0, 2, byteArrayOf(1, 2))))
        assertArrayEquals(byteArrayOf(1, 2, 3), r.accept("a", frag(1, 1, 2, byteArrayOf(3))))
    }

    @Test
    fun partsOutOfOrderReassemble() {
        val r = reassembler()
        assertNull(r.accept("a", frag(1, 2, 3, byteArrayOf(5))))
        assertNull(r.accept("a", frag(1, 0, 3, byteArrayOf(1, 2))))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), r.accept("a", frag(1, 1, 3, byteArrayOf(3, 4))))
    }

    @Test
    fun duplicatePartIsIdempotent() {
        val r = reassembler()
        assertNull(r.accept("a", frag(1, 0, 2, byteArrayOf(1))))
        assertNull("re-delivery of the same part completes nothing", r.accept("a", frag(1, 0, 2, byteArrayOf(9))))
        assertArrayEquals("first copy wins", byteArrayOf(1, 2), r.accept("a", frag(1, 1, 2, byteArrayOf(2))))
    }

    @Test
    fun interleavedSendersReassembleIndependently() {
        val r = reassembler()
        assertNull(r.accept("a", frag(1, 0, 2, byteArrayOf(1))))
        assertNull(r.accept("b", frag(1, 0, 2, byteArrayOf(9))))
        assertArrayEquals(byteArrayOf(9, 8), r.accept("b", frag(1, 1, 2, byteArrayOf(8))))
        assertArrayEquals(byteArrayOf(1, 2), r.accept("a", frag(1, 1, 2, byteArrayOf(2))))
    }

    @Test
    fun interleavedFragIdsFromOneSenderReassembleIndependently() {
        val r = reassembler()
        assertNull(r.accept("a", frag(1, 0, 2, byteArrayOf(1))))
        assertNull(r.accept("a", frag(2, 0, 2, byteArrayOf(9))))
        assertArrayEquals(byteArrayOf(1, 2), r.accept("a", frag(1, 1, 2, byteArrayOf(2))))
        assertArrayEquals(byteArrayOf(9, 8), r.accept("a", frag(2, 1, 2, byteArrayOf(8))))
    }

    @Test
    fun staleEntryTimesOutOnALaterInsert() {
        val r = reassembler(timeoutMs = 5_000)
        assertNull(r.accept("a", frag(1, 0, 2, byteArrayOf(1))))
        clock = 6_000
        assertNull("the late part starts a FRESH entry (old part swept)", r.accept("a", frag(1, 1, 2, byteArrayOf(2))))
        assertEquals(listOf(FragReassembler.Drop.TIMEOUT), drops)
    }

    @Test
    fun capacityEvictsOldestWithOverflowDrop() {
        val r = reassembler(capacity = 2)
        assertNull(r.accept("a", frag(1, 0, 2, byteArrayOf(1))))
        clock = 1
        assertNull(r.accept("b", frag(1, 0, 2, byteArrayOf(2))))
        clock = 2
        assertNull(r.accept("c", frag(1, 0, 2, byteArrayOf(3))))
        assertEquals(listOf(FragReassembler.Drop.OVERFLOW), drops)
        assertNull("a's entry was the oldest and is gone", r.accept("a", frag(1, 1, 2, byteArrayOf(9))))
        assertArrayEquals("c survived", byteArrayOf(3, 4), r.accept("c", frag(1, 1, 2, byteArrayOf(4))))
    }

    @Test
    fun mismatchedCountRestartsTheEntry() {
        val r = reassembler()
        assertNull(r.accept("a", frag(1, 0, 3, byteArrayOf(1))))
        assertNull("same fragId re-announced with count 2 discards the count-3 entry", r.accept("a", frag(1, 0, 2, byteArrayOf(7))))
        assertArrayEquals(byteArrayOf(7, 8), r.accept("a", frag(1, 1, 2, byteArrayOf(8))))
    }

    @Test
    fun completionRemovesTheEntry() {
        val r = reassembler()
        assertNull(r.accept("a", frag(1, 0, 2, byteArrayOf(1))))
        assertArrayEquals(byteArrayOf(1, 2), r.accept("a", frag(1, 1, 2, byteArrayOf(2))))
        assertNull("a re-sent part starts over instead of re-completing", r.accept("a", frag(1, 1, 2, byteArrayOf(2))))
    }

    @Test
    fun clearDropsEverything() {
        val r = reassembler()
        assertNull(r.accept("a", frag(1, 0, 2, byteArrayOf(1))))
        r.clear()
        assertNull(r.accept("a", frag(1, 1, 2, byteArrayOf(2))))
    }
}
