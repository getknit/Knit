package app.getknit.knit.ui.theme

/**
 * The platform's per-app night mode — the seam between a stored [ThemeMode] and `android.app
 * .UiModeManager`, so the projection is JVM-tested against an in-process fake the way the radios sit
 * behind `MeshTransport` and the position behind `LocationSource`.
 *
 * Applying the choice to the app's **configuration**, rather than passing a `darkTheme` down to
 * `KnitTheme`, is what makes the rest of the app need no changes at all: `isSystemInDarkTheme()`, the
 * `values-night` launch background and `enableEdgeToEdge()`'s bar polarity all read that configuration,
 * and the system has already applied it before the process starts. See ADR 2026-09.v5ck.
 */
fun interface NightMode {
    /** Pin the app to [mode], or hand the decision back to the system when it is [ThemeMode.System]. */
    fun apply(mode: ThemeMode)
}
