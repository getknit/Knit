package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraGossipPolicyTest {
    /** No jitter, so the transmit point is exactly the interval's midpoint and the schedule is readable. */
    private fun policy(
        min: Long = 5 * 60_000,
        max: Long = 15 * 60_000,
        redundancy: Int = 1,
    ) = LoraGossipPolicy(minIntervalMs = min, maxIntervalMs = max, redundancy = redundancy, random = { 0 })

    /**
     * A policy whose first interval starts at t=0. The timer arms on its first question rather than at
     * construction — which is what the transport's gossip loop does on its first pass — so a test that starts
     * asking at t=100_000 would otherwise be measuring an interval that began there.
     */
    private fun armed(
        min: Long = 5 * 60_000,
        max: Long = 15 * 60_000,
        redundancy: Int = 1,
    ) = policy(min, max, redundancy).also { it.nextDueAt(0) }

    @Test
    fun theFirstTransmitLandsInTheSecondHalfOfTheFirstInterval() {
        val p = policy()
        assertEquals(150_000L, p.nextDueAt(0))
        assertFalse("listen first", p.takeTransmitSlot(149_999))
        assertTrue(p.takeTransmitSlot(150_000))
    }

    @Test
    fun oneIntervalTransmitsAtMostOnce() {
        val p = armed()
        assertTrue(p.takeTransmitSlot(150_000))
        assertFalse(p.takeTransmitSlot(160_000))
        assertFalse(p.takeTransmitSlot(299_999))
    }

    @Test
    fun theIntervalDoublesWhileNothingChangesAndStopsAtTheCeiling() {
        val p = armed()
        assertEquals(5 * 60_000L, p.interval)
        p.nextDueAt(5 * 60_000) // interval 1 (0..5 min) elapsed
        assertEquals(10 * 60_000L, p.interval)
        p.nextDueAt(15 * 60_000) // interval 2 (5..15 min) elapsed
        assertEquals(15 * 60_000L, p.interval)
        p.nextDueAt(30 * 60_000)
        assertEquals("capped", 15 * 60_000L, p.interval)
    }

    @Test
    fun aResetSnapsBackToTheFloor() {
        val p = armed()
        p.nextDueAt(5 * 60_000)
        p.nextDueAt(15 * 60_000)
        assertEquals(15 * 60_000L, p.interval)
        p.reset(20 * 60_000)
        assertEquals(5 * 60_000L, p.interval)
        assertEquals("and re-arms from now", 20 * 60_000L + 150_000, p.nextDueAt(20 * 60_000))
    }

    @Test
    fun anIdenticalOfferFromSomeoneElseSuppressesOurs() {
        val p = armed()
        p.onOffer(sameSet = true, now = 100_000)
        assertFalse("theirs said exactly what ours would", p.takeTransmitSlot(150_000))
    }

    @Test
    fun anOfferAnnouncingADifferentSetDoesNotSuppressOurs() {
        // A peer holding a superset has not spoken on our behalf — it has said the opposite of what we need
        // to say, which is "here is what I am missing".
        val p = armed()
        p.onOffer(sameSet = false, now = 100_000)
        assertTrue(p.takeTransmitSlot(150_000))
    }

    @Test
    fun aSuppressedIntervalStillConsumesItsSlotRatherThanRetryingEveryWakeUp() {
        val p = armed()
        p.onOffer(sameSet = true, now = 100_000)
        assertFalse(p.takeTransmitSlot(150_000))
        assertFalse(p.takeTransmitSlot(160_000))
    }

    @Test
    fun suppressionDoesNotCarryIntoTheNextInterval() {
        val p = armed()
        p.onOffer(sameSet = true, now = 100_000)
        assertFalse(p.takeTransmitSlot(150_000))
        // Interval 2 begins when the timer is next consulted after interval 1 expires — it runs
        // 300_000..900_000, so with no jitter its transmit point is 600_000.
        p.nextDueAt(300_000)
        assertTrue(p.takeTransmitSlot(600_000))
    }

    @Test
    fun theFirstIntervalIsNotWreckedByTheNeverSentinel() {
        // `now - Long.MIN_VALUE` wraps; the same overflow that once blocked the first LoRa profile beacon.
        val p = policy()
        assertTrue(p.nextDueAt(0) > 0)
        assertTrue(p.takeTransmitSlot(150_000))
    }
}
