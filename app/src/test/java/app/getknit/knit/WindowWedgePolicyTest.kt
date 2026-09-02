package app.getknit.knit

import app.getknit.knit.ui.WindowWedgePolicy
import app.getknit.knit.ui.WindowWedgePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [WindowWedgePolicy] — the pure never-drawn-window episode clock, cooldown and cap. */
class WindowWedgePolicyTest {
    // Production tuning constants (MainActivity companion), passed explicitly here.
    private val graceMs = 2_500L
    private val cooldownMs = 60_000L
    private val maxRecreates = 3
    private val episodeStart = 1_000_000L

    /** decide() mid-wedge by default, past the grace period; each test overrides the axis it exercises. */
    @Suppress("LongParameterList") // mirrors the policy's own (suppressed) parameter list
    private fun decide(
        resumed: Boolean = true,
        focused: Boolean = true,
        windowVisible: Boolean = false,
        recreatable: Boolean = true,
        now: Long = episodeStart + graceMs,
        wedgedSince: Long = episodeStart,
        lastRecreateAt: Long = 0L,
        recreates: Int = 0,
    ) = WindowWedgePolicy.decide(
        resumed = resumed,
        focused = focused,
        windowVisible = windowVisible,
        recreatable = recreatable,
        now = now,
        wedgedSince = wedgedSince,
        lastRecreateAt = lastRecreateAt,
        recreates = recreates,
        graceMs = graceMs,
        cooldownMs = cooldownMs,
        maxRecreates = maxRecreates,
    )

    @Test
    fun `a wedge held for the whole grace period recreates`() {
        assertEquals(Action.Recreate, decide().action)
    }

    @Test
    fun `acting clears the episode so the replacement window starts its own`() {
        assertEquals(0L, decide().nextWedgedSince)
    }

    @Test
    fun `the first sighting only starts the clock`() {
        val d = decide(wedgedSince = 0L, now = episodeStart)
        assertEquals(Action.None, d.action)
        assertEquals(episodeStart, d.nextWedgedSince)
    }

    @Test
    fun `a wedge shorter than the grace period is left alone`() {
        val d = decide(now = episodeStart + graceMs - 1)
        assertEquals(Action.None, d.action)
        assertEquals(episodeStart, d.nextWedgedSince)
    }

    // Each healthy signal on its own is an ordinary state, and each must clear the clock outright — a
    // wedge has to be one unbroken stretch, not the sum of several interrupted ones.

    @Test
    fun `a visible window is not a wedge and clears the episode`() {
        val d = decide(windowVisible = true)
        assertEquals(Action.None, d.action)
        assertEquals(0L, d.nextWedgedSince)
    }

    @Test
    fun `an unfocused window is not a wedge — our own Compose popups take focus`() {
        val d = decide(focused = false)
        assertEquals(Action.None, d.action)
        assertEquals(0L, d.nextWedgedSince)
    }

    @Test
    fun `a backgrounded activity is not a wedge — it is meant not to draw`() {
        val d = decide(resumed = false)
        assertEquals(Action.None, d.action)
        assertEquals(0L, d.nextWedgedSince)
    }

    @Test
    fun `a broken stretch has to start over rather than accumulate`() {
        // Wedged, then one healthy tick, then wedged again: the clock restarts at the later sighting, so
        // the full grace period must elapse again before anything happens.
        assertEquals(0L, decide(windowVisible = true).nextWedgedSince)
        val restarted = decide(wedgedSince = 0L, now = episodeStart + graceMs)
        assertEquals(Action.None, restarted.action)
        assertEquals(episodeStart + graceMs, restarted.nextWedgedSince)
    }

    // Loop guards.

    @Test
    fun `a second recreate inside the cooldown is refused`() {
        val d = decide(lastRecreateAt = episodeStart, recreates = 1, now = episodeStart + cooldownMs - 1)
        assertEquals(Action.None, d.action)
    }

    @Test
    fun `a second recreate past the cooldown is allowed`() {
        val d = decide(lastRecreateAt = episodeStart, recreates = 1, now = episodeStart + cooldownMs)
        assertEquals(Action.Recreate, d.action)
    }

    @Test
    fun `the per-process ceiling stops the watchdog for good`() {
        val d = decide(recreates = maxRecreates, lastRecreateAt = 1L, now = episodeStart + cooldownMs * 10)
        assertEquals(Action.None, d.action)
    }

    @Test
    fun `a refused recreate keeps the episode running so a later tick can still fire`() {
        val d = decide(recreatable = false)
        assertEquals(Action.None, d.action)
        assertEquals(episodeStart, d.nextWedgedSince)
    }

    @Test
    fun `a finishing or reconfiguring activity is never recreated`() {
        assertEquals(Action.None, decide(recreatable = false).action)
    }
}
