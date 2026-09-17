package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.SideCapableTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [SideCapableTracker] — who nearby can hear the BLE side channel, on a virtual clock. */
class SideCapableTrackerTest {
    private val linger = 1_000L
    private val tracker = SideCapableTracker(lingerMs = linger)

    @Test
    fun aFlaggedSightingCountsUntilItsLingerRunsOut() {
        assertFalse(tracker.anyCapable(now = 0, linked = emptySet()))
        tracker.note("a", capable = true, now = 0)
        assertTrue(tracker.anyCapable(now = linger - 1, linked = emptySet()))
        assertFalse(tracker.anyCapable(now = linger, linked = emptySet()))
    }

    @Test
    fun aLinkedPeerKeepsItsFlagPastTheLinger() {
        // Presence prunes a linked peer the floored scan stops re-sighting; the link retains what it was sighted with.
        tracker.note("a", capable = true, now = 0)
        assertTrue(tracker.anyCapable(now = linger * 10, linked = setOf("a")))
        assertFalse("…but only while linked", tracker.anyCapable(now = linger * 10, linked = emptySet()))
    }

    @Test
    fun anUnflaggedSightingClearsTheFlagEvenForALinkedPeer() {
        tracker.note("a", capable = true, now = 0)
        tracker.note("a", capable = false, now = 1)
        assertFalse(tracker.anyCapable(now = 1, linked = setOf("a")))
    }

    @Test
    fun aPeerNeverSightedWithTheFlagDoesNotCountJustBecauseItIsLinked() {
        assertFalse(tracker.anyCapable(now = 0, linked = setOf("inbound")))
    }

    @Test
    fun forgetAndClear() {
        tracker.note("a", capable = true, now = 0)
        tracker.note("b", capable = true, now = 0)
        tracker.forget("a")
        assertTrue(tracker.anyCapable(now = 0, linked = emptySet()))
        tracker.clear()
        assertFalse(tracker.anyCapable(now = 0, linked = setOf("a", "b")))
    }
}
