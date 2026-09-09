package app.getknit.knit.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build

/**
 * [NightMode] on a real device: the app's only `UiModeManager` user.
 *
 * A no-op below API 31, where there is no per-app night mode to set. The gate is an inline
 * `Build.VERSION.SDK_INT` check rather than [THEME_MODE_SUPPORTED] because that is the form lint's
 * `NewApi` can see through; the constant exists for the UI, which lint does not need to follow.
 *
 * The write is cheap and idempotent — the system compares against the override it already holds, so
 * re-applying the current value at every process start costs nothing and changes no configuration.
 */
class AndroidNightMode(
    private val context: Context,
) : NightMode {
    override fun apply(mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(nightModeFor(mode))
    }
}
