package app.getknit.knit.ui.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The theme flags, warmed at process start so the first composition can read one synchronously.
 *
 * `MainActivity` composes `KnitTheme` immediately, but DataStore emits asynchronously, so a plain
 * `collectAsStateWithLifecycle(initialValue = false)` would paint the coral scheme for the first frames
 * and then flip to the wallpaper palette — a launch-time colour pop, the same class of defect as issue #2.
 * There is deliberately no `runBlocking` anywhere in this app, so the fix is to start the read early
 * instead: `Eagerly` begins collecting when this object is built, which `KnitApplication.onCreate` does,
 * giving the read the whole cold-start window (Koin graph, notification channel, Activity create, inflate,
 * first measure) to land before `setContent` runs. `collectAsStateWithLifecycle` on a [StateFlow] then
 * seeds the first composition from `value`, a synchronous read of the already-warmed flag.
 *
 * This narrows the race rather than closing it. If a slow device loses it the cost is one repaint at
 * launch — `animateColorAsState` initialises at its target on first composition, so `ConnectionStatus`'s
 * dot does not crossfade through the change. The next step if that ever shows up in the field is a
 * pre-draw gate on the first frame, **not** a blocking read; note that `MainActivity` already runs
 * `watchForUndrawnWindow`, so anything that deliberately withholds a frame has to be weighed against
 * making those wedge reports ambiguous (ADR 2026-09.un9n).
 */
class ThemePreferences(
    source: Flow<Boolean>,
    scope: CoroutineScope,
) {
    val dynamicColor: StateFlow<Boolean> = source.stateIn(scope, SharingStarted.Eagerly, false)
}
