package app.getknit.knit.ui.theme

import android.app.UiModeManager
import android.os.Build

/**
 * Which of the two schemes the app draws: whatever the system says, or the one this user pinned.
 *
 * Stored by name rather than by ordinal, so reordering the constants cannot silently re-theme every
 * install; [of] is total for the same reason, and answers [System] for anything it does not recognise.
 */
enum class ThemeMode {
    /** Follow the system's dark-theme setting — the default, and the only behaviour before this shipped. */
    System,

    Light,
    Dark,
    ;

    companion object {
        /** The stored string, read back. Unknown or absent means [System]. */
        fun of(stored: String?): ThemeMode = entries.firstOrNull { it.name == stored } ?: System
    }
}

/**
 * Whether the platform has a **per-app** night mode at all. `UiModeManager.setApplicationNightMode`
 * landed in API 31, and this app's minSdk is 29, so two supported releases have no such thing — and no
 * other mechanism reaches them: the launch window resolves `@color/splash_background` before the process
 * starts, so an override those releases could only apply in Compose would flash the wrong background on
 * every cold launch (issue #2). The control is therefore hidden below 31, the way Material You's is.
 *
 * Split into a pure function plus one impure `val` for the same reason [dynamicColorAvailable] is: the
 * decision is testable on the JVM, where `Build.VERSION.SDK_INT` reads 0 because this module sets
 * `unitTests.isReturnDefaultValues = true`. Deliberately **not** merged with [DYNAMIC_COLOR_SUPPORTED],
 * which happens to carry the same number for an unrelated reason (wallpaper colours, not night mode).
 */
internal fun themeModeAvailable(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

/** The running device's answer, for the defaulted parameter that hides the control on 29 and 30. */
val THEME_MODE_SUPPORTED: Boolean = themeModeAvailable(Build.VERSION.SDK_INT)

/**
 * [ThemeMode] as the constant `UiModeManager.setApplicationNightMode` wants.
 *
 * [ThemeMode.System] maps to `MODE_NIGHT_AUTO`, which is what *clears* the override rather than what its
 * name suggests: `UiModeManagerService` maps both `AUTO` and `CUSTOM` to `Configuration
 * .UI_MODE_NIGHT_UNDEFINED`, and an undefined override is the app falling back to the system's own night
 * mode. That is the whole reason this feature can be built on the platform API — a per-app mode you could
 * set but never take back would be a trap.
 *
 * The four `MODE_NIGHT_*` values are compile-time `int` constants, so they inline and survive
 * `isReturnDefaultValues`, unlike anything read through `Build.VERSION`.
 */
internal fun nightModeFor(mode: ThemeMode): Int =
    when (mode) {
        ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
        ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
        ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
    }
