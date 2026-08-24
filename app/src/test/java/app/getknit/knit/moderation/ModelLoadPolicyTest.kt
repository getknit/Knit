package app.getknit.knit.moderation

import app.getknit.knit.crash.ProcessExitEvidence
import app.getknit.knit.data.settings.ModelLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure state-machine coverage for the model poison-pill (ADR 037). No Android, no DataStore. */
class ModelLoadPolicyTest {
    private val stamp = "16|Pixel/rel/1"
    private val now = 1_700_000_000_000L

    private fun decide(
        stored: ModelLoadState = ModelLoadState.NONE,
        exit: ProcessExitEvidence? = null,
    ) = ModelLoadPolicy.decide(stored, stamp, now, exit)

    private fun pending(
        fails: Int = 0,
        since: Long = now - 1_000L,
    ) = ModelLoadState(stamp, pendingSince = since, fails = fails)

    @Test
    fun `a device that has never loaded the model loads it and marks the attempt`() {
        val decision = decide()
        assertTrue(decision.load)
        assertEquals(ModelLoadState(stamp, pendingSince = now, fails = 0), decision.next)
    }

    @Test
    fun `a clean previous run loads again without accumulating anything`() {
        val decision = decide(stored = ModelLoadState(stamp, pendingSince = 0L, fails = 0))
        assertTrue(decision.load)
        assertEquals(0, decision.next.fails)
    }

    @Test
    fun `an unexplained death mid-load counts once and still loads`() {
        val decision = decide(stored = pending())
        assertTrue(decision.load)
        assertEquals(1, decision.next.fails)
        assertEquals(now, decision.next.pendingSince)
    }

    @Test
    fun `two unexplained deaths latch the model off`() {
        val decision = decide(stored = pending(fails = 1))
        assertFalse(decision.load)
        assertTrue(ModelLoadPolicy.latched(decision.next))
        assertEquals(0L, decision.next.pendingSince)
    }

    @Test
    fun `a native fault latches on the first strike`() {
        val decision = decide(stored = pending(), exit = nativeFault(at = now))
        assertFalse(decision.load)
        assertTrue(ModelLoadPolicy.latched(decision.next))
    }

    @Test
    fun `an explained exit is discarded without counting`() {
        // Force-stop, low memory, a Java crash elsewhere in startup: the marker clears, the counter does not move.
        val decision = decide(stored = pending(fails = 1), exit = explained(at = now))
        assertTrue(decision.load)
        assertEquals(1, decision.next.fails)
    }

    @Test
    fun `an explained exit alone never latches, however many times it happens`() {
        var state = ModelLoadState.NONE
        repeat(5) {
            val decision = ModelLoadPolicy.decide(state, stamp, now, explained(at = now))
            assertTrue(decision.load)
            state = decision.next
        }
        assertFalse(ModelLoadPolicy.latched(state))
    }

    @Test
    fun `a native fault older than the marker is not credited to this attempt`() {
        // The record predates the load we are asking about, so it belongs to some earlier, unrelated death.
        val decision = decide(stored = pending(since = now - 1_000L), exit = nativeFault(at = now - 5_000L))
        assertTrue(decision.load)
        assertEquals(1, decision.next.fails)
    }

    @Test
    fun `a latched model is not loaded and its record is left alone`() {
        val latched = ModelLoadState(stamp, pendingSince = 0L, fails = ModelLoadPolicy.MAX_FAILS)
        val decision = decide(stored = latched)
        assertFalse(decision.load)
        assertEquals(latched, decision.next)
    }

    @Test
    fun `a new app version or OS build gives a latched model a fresh chance`() {
        val latchedOnOldBuild = ModelLoadState("15|Pixel/rel/0", pendingSince = 0L, fails = ModelLoadPolicy.MAX_FAILS)
        val decision = decide(stored = latchedOnOldBuild)
        assertTrue(decision.load)
        assertEquals(ModelLoadState(stamp, pendingSince = now, fails = 0), decision.next)
    }

    @Test
    fun `a stamp change also discards a pending marker rather than counting it`() {
        val pendingOnOldBuild = ModelLoadState("15|Pixel/rel/0", pendingSince = now - 1_000L, fails = 1)
        val decision = decide(stored = pendingOnOldBuild, exit = nativeFault(at = now))
        assertTrue(decision.load)
        assertEquals(0, decision.next.fails)
    }

    @Test
    fun `completed clears both the marker and the counter`() {
        val completed = ModelLoadPolicy.completed(stamp)
        assertEquals(ModelLoadState(stamp, pendingSince = 0L, fails = 0), completed)
        assertFalse(ModelLoadPolicy.latched(completed))
    }

    private fun nativeFault(at: Long) = ProcessExitEvidence(at = at, nativeFault = true, explained = false)

    private fun explained(at: Long) = ProcessExitEvidence(at = at, nativeFault = false, explained = true)
}
