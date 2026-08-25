package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LoraPacePolicyTest {
    private fun frame(
        label: String,
        klass: FrameClass = FrameClass.ROOM,
    ) = OutboundFrame(messages = listOf(byteArrayOf(1)), label = label, klass = klass)

    @Test
    fun holdsTheMinimumGapBetweenSends() {
        val pace = LoraPacePolicy(minGapMs = 3_000)
        pace.enqueue(frame("a"))
        pace.enqueue(frame("b"))
        assertEquals("a", pace.take(0)!!.label)
        assertNull("second send blocked until the gap elapses", pace.take(2_999))
        assertEquals("b", pace.take(3_000)!!.label)
    }

    @Test
    fun theQueueDropsTheOldestWholeFrameWhenFull() {
        val pace = LoraPacePolicy(queueCap = 2)
        assertEquals(LoraPacePolicy.Admission.ACCEPTED, pace.enqueue(frame("a")))
        assertEquals(LoraPacePolicy.Admission.ACCEPTED, pace.enqueue(frame("b")))
        assertEquals(LoraPacePolicy.Admission.DROPPED_OLDEST, pace.enqueue(frame("c")))
        assertEquals(2, pace.pending)
        assertEquals("oldest evicted, b is next", "b", pace.take(10_000)!!.label)
        assertEquals("c", pace.take(20_000)!!.label)
    }

    @Test
    fun aFullQueueShedsTheRoomBeforeADmAndNeverTheProfile() {
        val pace = LoraPacePolicy(queueCap = 3)
        pace.enqueue(frame("profile", FrameClass.BOOTSTRAP))
        pace.enqueue(frame("room-1"))
        pace.enqueue(frame("dm-1", FrameClass.DM))
        // A second DM evicts the room post (the lowest class present), not the older profile or DM.
        assertEquals(LoraPacePolicy.Admission.DROPPED_OLDEST, pace.enqueue(frame("dm-2", FrameClass.DM)))
        assertEquals(listOf("profile", "dm-1", "dm-2"), drain(pace))
    }

    @Test
    fun aNewcomerAloneAtTheBottomYieldsInsteadOfEvicting() {
        val pace = LoraPacePolicy(queueCap = 2)
        pace.enqueue(frame("profile", FrameClass.BOOTSTRAP))
        pace.enqueue(frame("dm", FrameClass.DM))
        assertEquals("a room post cannot displace a DM or the bootstrap", LoraPacePolicy.Admission.REFUSED, pace.enqueue(frame("room")))
        assertEquals(2, pace.pending)
        // Within one class the oldest still goes and the newcomer stays (recency wins, as before).
        assertEquals(LoraPacePolicy.Admission.DROPPED_OLDEST, pace.enqueue(frame("dm-2", FrameClass.DM)))
        assertEquals(listOf("profile", "dm-2"), drain(pace))
    }

    @Test
    fun dequeueStaysFifoAcrossClasses() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.enqueue(frame("room"))
        pace.enqueue(frame("dm", FrameClass.DM))
        pace.enqueue(frame("profile", FrameClass.BOOTSTRAP))
        assertEquals("class governs shedding, not send order", listOf("room", "dm", "profile"), drain(pace))
    }

    /** Takes everything queued, advancing the clock past the min gap between takes. */
    private fun drain(pace: LoraPacePolicy): List<String> {
        var now = 1_000_000L
        return generateSequence { pace.take(now).also { now += 10_000 } }.map { it.label }.toList()
    }

    @Test
    fun aFullBoardQueueHoldsAllSends() {
        val pace = LoraPacePolicy()
        pace.enqueue(frame("a"))
        pace.onQueueStatus(free = 0)
        assertNull("board has no headroom", pace.take(10_000))
        pace.onQueueStatus(free = 3)
        assertNotNull(pace.take(10_000))
    }

    @Test
    fun aRateLimitNakWidensTheGap() {
        val pace = LoraPacePolicy(minGapMs = 3_000, nakBackoffMs = 60_000)
        pace.enqueue(frame("a"))
        assertNotNull(pace.take(0))
        pace.enqueue(frame("b"))
        pace.onNak(RoutingError.RATE_LIMIT_EXCEEDED, now = 1_000)
        assertNull("cool-down blocks the next send past the normal gap", pace.take(3_000))
        assertNotNull("sends resume after the cool-down", pace.take(61_000))
    }

    @Test
    fun anUnrelatedNakDoesNotPace() {
        val pace = LoraPacePolicy(minGapMs = 3_000)
        pace.enqueue(frame("a"))
        assertNotNull(pace.take(0))
        pace.enqueue(frame("b"))
        pace.onNak(RoutingError.NO_CHANNEL, now = 1_000)
        assertNotNull("a NO_CHANNEL nak is not a rate limit", pace.take(3_000))
    }

    @Test
    fun takeIsNullWhenEmpty() {
        assertNull(LoraPacePolicy().take(10_000))
    }
}
