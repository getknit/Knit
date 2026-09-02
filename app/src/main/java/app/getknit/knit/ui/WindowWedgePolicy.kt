package app.getknit.knit.ui

/**
 * Pure decision for the **never-drawn-window watchdog** (ADR 2026-09.un9n): when the Activity is resumed
 * and holds input focus but its window is not visible to `ViewRootImpl`, nothing is ever drawn and the
 * user sees the bare `windowBackground` — a white screen on an app that is otherwise completely alive.
 *
 * Extracted from [app.getknit.knit.MainActivity] so the part worth testing — the episode clock, the
 * cooldown and the process cap that together stop a recreate loop — runs on the JVM. The Activity owns
 * the three observations and the one side effect (`recreate()`); this object only maps them to an
 * [Action] plus the next episode-clock value, exactly as `NanWatchdogPolicy` does for the NAN plane.
 *
 * **Every healthy signal clears the episode.** The wedge is only asserted while all three hold at once —
 * resumed, focused, and window-not-visible — because each on its own is an ordinary state: an Activity
 * that has lost focus to a Compose `Popup` (its own sub-window) reports `hasWindowFocus() == false`, and
 * an Activity that is merely backgrounded is not resumed. Requiring all three, for [graceMs] without a
 * break, is what keeps a transition frame from being read as a wedge.
 */
object WindowWedgePolicy {
    enum class Action { None, Recreate }

    /** [action] to take now, and the value the caller must write back into its `wedgedSince` episode clock. */
    data class Decision(
        val action: Action,
        val nextWedgedSince: Long,
    )

    /**
     * @param resumed the Activity is at least RESUMED — a backgrounded window is *meant* not to draw.
     * @param focused the window holds input focus; false while one of our own Compose popups owns it.
     * @param windowVisible `decorView.windowVisibility == VISIBLE`, i.e. `ViewRootImpl` will draw at all.
     * @param recreatable neither finishing nor mid-configuration-change — recreating either is a no-op at
     *   best and a torn teardown at worst.
     * @param wedgedSince start of the current all-three-hold episode (0 = none), on the elapsed-realtime clock.
     * @param lastRecreateAt when this process last recreated for a wedge (0 = never).
     * @param recreates how many times this **process** has recreated for a wedge.
     * @param graceMs how long the wedge must hold before acting.
     * @param cooldownMs the floor between two recreates, so a wedge that survives one cannot spin.
     * @param maxRecreates the hard per-process ceiling; past it the watchdog stays quiet for good.
     */
    @Suppress("LongParameterList") // one boolean per observation reads better here than an options bag
    fun decide(
        resumed: Boolean,
        focused: Boolean,
        windowVisible: Boolean,
        recreatable: Boolean,
        now: Long,
        wedgedSince: Long,
        lastRecreateAt: Long,
        recreates: Int,
        graceMs: Long,
        cooldownMs: Long,
        maxRecreates: Int,
    ): Decision {
        // Any healthy signal ⇒ not wedged; clear the episode so the grace period always measures one
        // unbroken stretch rather than the sum of several.
        if (!resumed || !focused || windowVisible) return Decision(Action.None, 0L)
        // First sighting starts the clock; we never act on it.
        if (wedgedSince == 0L) return Decision(Action.None, now)
        // Hold until the wedge has persisted for the whole grace period.
        if (now - wedgedSince < graceMs) return Decision(Action.None, wedgedSince)
        // A recreate we cannot safely make, one inside the cooldown, or one past the per-process ceiling:
        // keep the episode running (so a later, permitted tick still fires) but do nothing now.
        if (!recreatable || recreates >= maxRecreates) return Decision(Action.None, wedgedSince)
        if (lastRecreateAt != 0L && now - lastRecreateAt < cooldownMs) return Decision(Action.None, wedgedSince)
        // Acting resets the clock: the replacement Activity is a fresh window and gets its own episode.
        return Decision(Action.Recreate, 0L)
    }
}
