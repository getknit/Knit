package app.getknit.knit.moderation

import app.getknit.knit.crash.ProcessExitEvidence
import app.getknit.knit.data.settings.ModelLoadState

/**
 * Pure "should we load this bundled model, or has loading it been killing us?" decision, mirroring
 * [app.getknit.knit.review.ReviewPromptPolicy] and [app.getknit.knit.mesh.wifiaware.NanWatchdogPolicy]:
 * no Android, no I/O, a [Decision] the caller applies, JVM-unit-testable.
 *
 * **The problem.** [MlTextModerator] and [NsfwImageModerator] absorb every *Java* failure a TFLite load
 * can produce, down to `UnsatisfiedLinkError` and `OutOfMemoryError`, and degrade to allow-all. A
 * **native** crash inside the interpreter — a bad build for an unfamiliar SoC, an XNNPACK path taken on
 * the first inference — cannot be caught at all: it takes the process. The toxicity warm-up runs on every
 * launch, so on a device where that reproduces the app is in a launch loop with no way out, no crash
 * report (nothing Java-level was thrown), and no help from clearing app data.
 *
 * **What the marker means.** [ModelLoadState.pendingSince] is set before the load and cleared in a
 * `finally`, so a load that returned nothing, threw, or was cancelled clears it just the same. Finding it
 * still set on a later launch therefore means one thing only: **the process died in there.** Everything
 * below reasons about that, and nothing has to ask whether a failure was "real".
 *
 * **The trap.** A process death mid-load that had nothing to do with the model — a force-stop, the
 * low-memory killer, a Java crash elsewhere in startup (`5da5601` fixed one that fired at ~10 s, five
 * seconds after this marker goes down) — leaves identical evidence, and counting it would disable the
 * classifier forever on a phone with nothing wrong with it. So the counter is not the only input:
 *
 * - a [ProcessExitEvidence.nativeFault] no older than the marker convicts on the **first** strike;
 * - an [ProcessExitEvidence.explained] exit is discarded **without counting**;
 * - anything else — API 29, no record, a reason the platform does not explain — counts toward
 *   [MAX_FAILS].
 *
 * **Why [MAX_FAILS] is 2.** The asymmetry is lopsided: a wrong latch is visible, resettable, and clears
 * itself on the next version bump, while a missed one leaves the app unusable. Two consecutive
 * *unexplained* deaths inside a sub-three-second window, with every ordinary cause already filtered out,
 * is evidence enough — and it halves how many times a genuinely affected user has to watch the app die.
 *
 * **What this does not claim.** It catches the launch-loop shape: a fault on the very first touch of a
 * model. A native crash on the five-hundredth inference leaves no marker and will recur. That is correct
 * — it is not a launch loop, and latching on it would assert far more than the evidence supports.
 */
object ModelLoadPolicy {
    /** Consecutive *unexplained* process deaths inside the load before the model is latched off. */
    const val MAX_FAILS = 2

    /**
     * @param load whether the caller should attempt the load
     * @param next the state to persist; when [load] is true this must be written **before** the attempt
     */
    data class Decision(
        val load: Boolean,
        val next: ModelLoadState,
    )

    /** Whether [state] has latched this model off. */
    fun latched(state: ModelLoadState): Boolean = state.fails >= MAX_FAILS

    /**
     * The state to persist once the attempt is over — however it ended. Clears the counter too: the
     * failures that matter are *consecutive*, and we just came back alive.
     */
    fun completed(stamp: String): ModelLoadState = ModelLoadState(stamp, pendingSince = 0L, fails = 0)

    fun decide(
        stored: ModelLoadState,
        stamp: String,
        now: Long,
        exit: ProcessExitEvidence?,
    ): Decision {
        val base = if (stored.stamp == stamp) stored else ModelLoadState(stamp, pendingSince = 0L, fails = 0)
        val resolved = resolvePending(base, exit)
        return if (latched(resolved)) {
            Decision(load = false, next = resolved)
        } else {
            Decision(load = true, next = resolved.copy(pendingSince = now))
        }
    }

    /**
     * Settles a marker the previous process left behind. The `exit.at >= pendingSince` test is what keeps
     * an older, unrelated native crash from being credited to this attempt — the record has to be at
     * least as new as the marker it is being asked about.
     */
    private fun resolvePending(
        state: ModelLoadState,
        exit: ProcessExitEvidence?,
    ): ModelLoadState =
        when {
            state.pendingSince == 0L -> state
            exit == null || exit.at < state.pendingSince -> state.copy(pendingSince = 0L, fails = state.fails + 1)
            exit.nativeFault -> state.copy(pendingSince = 0L, fails = MAX_FAILS)
            exit.explained -> state.copy(pendingSince = 0L)
            else -> state.copy(pendingSince = 0L, fails = state.fails + 1)
        }
}
