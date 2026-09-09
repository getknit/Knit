package app.getknit.knit.ui.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The theme settings, held for the process: [dynamicColor] warmed so the first composition can read one
 * synchronously, and the light/dark choice projected onto the platform's per-app night mode.
 *
 * **Why [dynamicColor] is warmed.** `MainActivity` composes `KnitTheme` immediately, but DataStore emits
 * asynchronously, so a plain `collectAsStateWithLifecycle(initialValue = false)` would paint the coral scheme
 * for the first frames and then flip to the wallpaper palette — a launch-time colour pop, the same class of
 * defect as issue #2. There is deliberately no `runBlocking` anywhere in this app, so the fix is to start the
 * read early instead: `Eagerly` begins collecting when this object is built, which `KnitApplication.onCreate`
 * does, giving the read the whole cold-start window (Koin graph, notification channel, Activity create,
 * inflate, first measure) to land before `setContent` runs. `collectAsStateWithLifecycle` on a [StateFlow]
 * then seeds the first composition from `value`, a synchronous read of the already-warmed flag.
 *
 * This narrows the race rather than closing it. If a slow device loses it the cost is one repaint at
 * launch — `animateColorAsState` initialises at its target on first composition, so `ConnectionStatus`'s
 * dot does not crossfade through the change. The next step if that ever shows up in the field is a
 * pre-draw gate on the first frame, **not** a blocking read; note that `MainActivity` already runs
 * `watchForUndrawnWindow`, so anything that deliberately withholds a frame has to be weighed against
 * making those wedge reports ambiguous (ADR 2026-09.un9n).
 *
 * **Why [ThemeMode] needs none of that.** It is not read at composition time at all: the system holds the
 * per-app night mode and had already applied it to this process's `Configuration` before `onCreate` ran, so
 * `isSystemInDarkTheme()`, the `values-night` launch background and `enableEdgeToEdge()`'s bar polarity are
 * all correct on the first frame with nothing warmed. The collector below is for **drift**, not for launch:
 * DataStore stays the source of truth, and re-applying it once per process heals the case where the two
 * disagree — a backup that restored the preferences but not the system-side override, say. Re-applying a
 * value the system already holds changes no configuration, so it costs nothing when they agree
 * (ADR 2026-09.v5ck).
 */
class ThemePreferences(
    dynamicColorSource: Flow<Boolean>,
    themeModeSource: Flow<ThemeMode>,
    nightMode: NightMode,
    scope: CoroutineScope,
) {
    val dynamicColor: StateFlow<Boolean> = dynamicColorSource.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch { themeModeSource.distinctUntilChanged().collect(nightMode::apply) }
    }
}
