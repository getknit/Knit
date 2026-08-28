package app.getknit.knit

import app.getknit.knit.mesh.DmAckCoalescer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DM-receipt coalescer (ADR 054) on a virtual clock: one tick per author per hold, and the piggyback seams. */
@OptIn(ExperimentalCoroutinesApi::class)
class DmAckCoalescerTest {
    private val flushes = mutableListOf<Pair<String, List<String>>>()

    @Test
    fun oneTimerPerAuthorFlushesEverythingHeldOnceTheOldestIdIsDue() =
        runTest {
            val c =
                DmAckCoalescer(now = { testScheduler.currentTime }, flush = {
                    a,
                    ids,
                    ->
                    flushes += a to ids
                }, flushScope = { backgroundScope })
            c.hold("alice", "m1")
            advanceTimeBy(20_000)
            c.hold("alice", "m2")
            c.hold("bob", "b1")
            advanceTimeBy(DmAckCoalescer.HOLD_MS - 20_000 + 1)
            runCurrent()
            // alice's batch is anchored on m1, so m2 did not push it out; bob's own timer is still running.
            assertEquals(listOf("alice" to listOf("m1", "m2")), flushes)
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(listOf("alice" to listOf("m1", "m2"), "bob" to listOf("b1")), flushes)
            assertTrue(c.pending("alice").isEmpty())
        }

    @Test
    fun aReDeliveredIdIsHeldOnce() =
        runTest {
            val c =
                DmAckCoalescer(now = { testScheduler.currentTime }, flush = {
                    a,
                    ids,
                    ->
                    flushes += a to ids
                }, flushScope = { backgroundScope })
            c.hold("alice", "m1")
            c.hold("alice", "m1")
            c.hold("alice", "m1")
            assertEquals(listOf("m1"), c.pending("alice"))
            advanceTimeBy(DmAckCoalescer.HOLD_MS + 1)
            runCurrent()
            assertEquals(listOf("alice" to listOf("m1")), flushes)
        }

    @Test
    fun aFullBatchFlushesAtOnceAndTheNextIdOpensAFreshOne() =
        runTest {
            val c = DmAckCoalescer(now = { testScheduler.currentTime }, flush = { a, ids -> flushes += a to ids }, maxBatch = 2)
            c.hold("alice", "m1")
            assertTrue(flushes.isEmpty())
            c.hold("alice", "m2")
            assertEquals(listOf("alice" to listOf("m1", "m2")), flushes)
            c.hold("alice", "m3")
            assertEquals(listOf("m3"), c.pending("alice"))
        }

    @Test
    fun flushDueIsTheBackstopWhenNoScopeRunsTheTimer() =
        runTest {
            val c = DmAckCoalescer(now = { testScheduler.currentTime }, flush = { a, ids -> flushes += a to ids })
            c.hold("alice", "m1")
            assertEquals(DmAckCoalescer.HOLD_MS, c.nextDueAt())
            c.flushDue()
            assertTrue("not due yet", flushes.isEmpty())
            advanceTimeBy(DmAckCoalescer.HOLD_MS)
            c.flushDue()
            assertEquals(listOf("alice" to listOf("m1")), flushes)
            assertNull(c.nextDueAt())
        }

    @Test
    fun takeCarriesTheOldestIdsAwayAndAnEmptiedBatchTicksNothing() =
        runTest {
            val c =
                DmAckCoalescer(now = { testScheduler.currentTime }, flush = {
                    a,
                    ids,
                    ->
                    flushes += a to ids
                }, flushScope = { backgroundScope })
            c.hold("alice", "m1")
            c.hold("alice", "m2")
            c.hold("alice", "m3")
            assertEquals(listOf("m1", "m2"), c.take("alice", 2))
            assertEquals(listOf("m3"), c.pending("alice"))
            assertEquals(listOf("m3"), c.take("alice", 5))
            assertTrue(c.take("alice", 5).isEmpty())
            advanceTimeBy(DmAckCoalescer.HOLD_MS + 1)
            runCurrent()
            assertTrue("the standalone tick was cancelled with the batch", flushes.isEmpty())
        }

    @Test
    fun givenBackIdsReEnterTheHold() =
        runTest {
            val c =
                DmAckCoalescer(now = { testScheduler.currentTime }, flush = {
                    a,
                    ids,
                    ->
                    flushes += a to ids
                }, flushScope = { backgroundScope })
            c.hold("alice", "m1")
            val taken = c.take("alice", 1)
            c.giveBack("alice", taken)
            assertEquals(listOf("m1"), c.pending("alice"))
            advanceTimeBy(DmAckCoalescer.HOLD_MS + 1)
            runCurrent()
            assertEquals(listOf("alice" to listOf("m1")), flushes)
        }

    @Test
    fun pastThePendingCapTheOldestAuthorFlushesEarly() =
        runTest {
            val c = DmAckCoalescer(now = { testScheduler.currentTime }, flush = { a, ids -> flushes += a to ids }, pendingCap = 2)
            c.hold("alice", "m1")
            advanceTimeBy(1)
            c.hold("bob", "b1")
            assertEquals(listOf("alice" to listOf("m1")), flushes)
            assertEquals(listOf("b1"), c.pending("bob"))
        }

    @Test
    fun anAckOlderThanTheTtlIsForgottenNotTicked() =
        runTest {
            val c = DmAckCoalescer(now = { testScheduler.currentTime }, flush = { a, ids -> flushes += a to ids }, ttlMs = 10_000)
            c.hold("alice", "m1")
            advanceTimeBy(10_001)
            c.flushDue()
            assertTrue(flushes.isEmpty())
            assertTrue(c.pending("alice").isEmpty())
        }
}
