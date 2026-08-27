package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanAttachPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NanAttachPolicy] — the backoff that stops a chipset which can never open a NAN iface from
 * being retried at the discovery loop's flat detached cadence forever (getknit/Knit#9).
 */
class NanAttachPolicyTest {
    // rand()=0.5 → zero offset, so the delay is exactly the un-jittered value (easy to assert).
    private val noJitter = { 0.5 }

    @Test
    fun theFirstFailureRetriesAtTheOldFlatCadence() {
        // The transient case this branch was written for — a chipset needing a beat after a reattach
        // teardown — must be no slower than it was before the backoff existed.
        assertEquals(3_000, NanAttachPolicy.backoffMs(1, noJitter))
    }

    @Test
    fun backoffDoublesPerConsecutiveFailure() {
        assertEquals(6_000, NanAttachPolicy.backoffMs(2, noJitter))
        assertEquals(12_000, NanAttachPolicy.backoffMs(3, noJitter))
        assertEquals(24_000, NanAttachPolicy.backoffMs(4, noJitter))
        assertEquals(48_000, NanAttachPolicy.backoffMs(5, noJitter))
        assertEquals(96_000, NanAttachPolicy.backoffMs(6, noJitter))
    }

    @Test
    fun backoffSaturatesAtTheLoopIdleCadenceWithoutOverflow() {
        assertEquals("streak 7 saturates at the loop's own idle cadence", 120_000, NanAttachPolicy.backoffMs(7, noJitter))
        assertEquals(120_000, NanAttachPolicy.backoffMs(12, noJitter))
        assertEquals(120_000, NanAttachPolicy.backoffMs(50, noJitter)) // no Long overflow on a big streak
        assertEquals(120_000, NanAttachPolicy.backoffMs(Int.MAX_VALUE, noJitter))
    }

    @Test
    fun aDeviceThatCanNeverAttachSettlesInsideAFewMinutes() {
        // The #9 shape: ~51k attach attempts in ~43 h at the old flat 3 s, i.e. ~28.8k/day. Assert what the
        // curve is actually worth — the 7th attempt (the first at the cap) lands ~3 min in, and every attempt
        // after it is one per cap, so a full day of a radio that can never open costs ~725 attaches, not 28.8k.
        val untilTheSeventhAttempt = (1..6).sumOf { NanAttachPolicy.backoffMs(it, noJitter) }
        assertEquals(189_000, untilTheSeventhAttempt)
        val perDay = 6 + (DAY_MS - untilTheSeventhAttempt) / NanAttachPolicy.MAX_BACKOFF_MS
        assertTrue("a 40x cut on the old flat cadence: got $perDay/day", perDay < 800)
    }

    @Test
    fun streakBelowOneIsTreatedAsTheFirstFailure() {
        assertEquals(3_000, NanAttachPolicy.backoffMs(0, noJitter))
        assertEquals(3_000, NanAttachPolicy.backoffMs(-3, noJitter))
    }

    @Test
    fun jitterSpansPlusMinusTheConfiguredFraction() {
        // rand()=0 → −20 %, rand()→1 → +20 % of the un-jittered 3_000 ms.
        assertEquals(2_400, NanAttachPolicy.backoffMs(1) { 0.0 })
        val hi = NanAttachPolicy.backoffMs(1) { 0.999999 }
        assertTrue("upper bound ≈ +20 %: got $hi", hi in 3_590..3_600)
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1_000L
    }
}
