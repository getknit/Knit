package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanAttachPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NanAttachPolicy] — the retry budget that keeps a chipset which can never open a NAN
 * interface from stranding enough binder objects in `system_server` for AMS to kill the process
 * (getknit/Knit#9). The assertions are budget assertions: what a run of failures *costs*.
 */
class NanAttachPolicyTest {
    // rand()=0.5 → zero offset, so the delay is exactly the un-jittered value (easy to assert).
    private val noJitter = { 0.5 }

    @Test
    fun theFirstFailureRetriesAtTheOldFlatCadence() {
        // The transient case this branch was written for — a chipset needing a beat after a reattach
        // teardown — must be no slower than it was before the budget existed.
        assertEquals(3_000, NanAttachPolicy.backoffMs(1, noJitter))
        assertFalse(NanAttachPolicy.giveUp(1))
    }

    @Test
    fun backoffDoublesPerConsecutiveFailure() {
        assertEquals(6_000, NanAttachPolicy.backoffMs(2, noJitter))
        assertEquals(12_000, NanAttachPolicy.backoffMs(3, noJitter))
        assertEquals(24_000, NanAttachPolicy.backoffMs(4, noJitter))
        assertEquals(48_000, NanAttachPolicy.backoffMs(5, noJitter))
        assertEquals(96_000, NanAttachPolicy.backoffMs(6, noJitter))
        assertEquals(192_000, NanAttachPolicy.backoffMs(7, noJitter))
    }

    @Test
    fun backoffSaturatesAtTheHalfHourCapWithoutOverflow() {
        assertEquals("streak 10 is the last below the cap", 1_536_000, NanAttachPolicy.backoffMs(10, noJitter))
        assertEquals("streak 11 saturates", 1_800_000, NanAttachPolicy.backoffMs(11, noJitter))
        assertEquals(1_800_000, NanAttachPolicy.backoffMs(40, noJitter))
        assertEquals(1_800_000, NanAttachPolicy.backoffMs(Int.MAX_VALUE, noJitter)) // no Long overflow
    }

    @Test
    fun theBudgetIsSpentAfterAboutADayOfRefusals() {
        assertFalse("59 consecutive failures still retries", NanAttachPolicy.giveUp(NanAttachPolicy.MAX_FAILURES - 1))
        assertTrue(NanAttachPolicy.giveUp(NanAttachPolicy.MAX_FAILURES))
        assertTrue(NanAttachPolicy.giveUp(NanAttachPolicy.MAX_FAILURES + 1))

        // What the whole budget costs in wall-clock and in leaked binder objects. Two objects per failed
        // attach; AMS's per-uid watermark is in the thousands, so the whole run must stay a rounding error.
        val untilGiveUp = (1 until NanAttachPolicy.MAX_FAILURES).sumOf { NanAttachPolicy.backoffMs(it, noJitter) }
        val hours = untilGiveUp / 3_600_000.0
        assertTrue("about a day of trying before giving up: got ${"%.1f".format(hours)}h", hours in 24.0..28.0)
        assertEquals("two binder objects per failure", 120, NanAttachPolicy.MAX_FAILURES * 2)
    }

    @Test
    fun theOldFlatCadenceLeakedTwoOrdersOfMagnitudeMore() {
        // The #9 shape: a flat 3 s retry is 28,800 failed attaches a day, i.e. ~57,600 stranded binder
        // objects — AMS kills the uid within hours. The capped curve settles at 48 attempts a day.
        val flatPerDay = DAY_MS / 3_000L
        val cappedPerDay = DAY_MS / NanAttachPolicy.MAX_BACKOFF_MS
        assertEquals(28_800, flatPerDay)
        assertEquals(48, cappedPerDay)
        assertTrue("a 600x cut before the give-up even applies", flatPerDay / cappedPerDay >= 600)
    }

    @Test
    fun streakBelowOneIsTreatedAsTheFirstFailure() {
        assertEquals(3_000, NanAttachPolicy.backoffMs(0, noJitter))
        assertEquals(3_000, NanAttachPolicy.backoffMs(-3, noJitter))
        assertFalse(NanAttachPolicy.giveUp(0))
    }

    @Test
    fun jitterSpansPlusMinusTheConfiguredFraction() {
        // rand()=0 → −20 %, rand()→1 → +20 % of the un-jittered 3_000 ms.
        assertEquals(2_400, NanAttachPolicy.backoffMs(1) { 0.0 })
        val hi = NanAttachPolicy.backoffMs(1) { 0.999999 }
        assertTrue("upper bound ≈ +20 %: got $hi", hi in 3_590..3_600)
    }

    @Test
    fun theRateFloorSurvivesAStreakThatIsRefundedEveryTime() {
        // getknit/Knit#9, second act: 2.3.1's availability receiver refunded the streak on every Aware
        // broadcast, so the backoff was recomputed from 1 forever and never actually delayed anything. The
        // reporter's log shows attaches 3.5 ms apart. The floor is the bound that does not care why.
        var now = 0L
        var lastAttach = -NanAttachPolicy.MIN_ATTACH_INTERVAL_MS // nothing attached yet
        var attaches = 0
        // 40 s of broadcasts arriving every 4 ms
        repeat(10_000) {
            now += 4
            if (!NanAttachPolicy.tooSoon(now - lastAttach)) {
                lastAttach = now
                attaches++
            }
        }
        assertEquals("one attach per 3 s floor, not one per broadcast", 14, attaches)
        assertTrue("the storm itself was three orders of magnitude bigger", 10_000 / attaches > 700)
    }

    @Test
    fun theFloorIsInvisibleToAnythingAlreadyPacingItself() {
        assertTrue(NanAttachPolicy.tooSoon(0))
        assertTrue(NanAttachPolicy.tooSoon(NanAttachPolicy.MIN_ATTACH_INTERVAL_MS - 1))
        assertFalse(NanAttachPolicy.tooSoon(NanAttachPolicy.MIN_ATTACH_INTERVAL_MS))
        // The shortest delay the streak can ask for is the floor itself, so the two never disagree.
        assertFalse(NanAttachPolicy.tooSoon(NanAttachPolicy.backoffMs(1) { 1.0 }))
    }

    @Test
    fun theLifetimeCapBoundsTheLeakWhateverTheRefundsDo() {
        assertFalse(NanAttachPolicy.leakBudgetSpent(NanAttachPolicy.MAX_LIFETIME_FAILURES - 1))
        assertTrue(NanAttachPolicy.leakBudgetSpent(NanAttachPolicy.MAX_LIFETIME_FAILURES))

        // Two binder objects per failed attach. AMS's per-uid watermark is in the thousands, so this is the
        // bound that makes the kill unreachable even if every other gate is being refunded in a loop.
        assertEquals("400 binder objects, ever", 400, NanAttachPolicy.MAX_LIFETIME_FAILURES * 2)
        assertTrue(
            "the process bound must outlast a full streak, or it would preempt the ordinary path",
            NanAttachPolicy.MAX_LIFETIME_FAILURES > NanAttachPolicy.MAX_FAILURES,
        )
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1_000L
    }
}
