package app.getknit.knit.ui.theme

import android.app.UiModeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the light/dark choice: what a stored string means, which releases can honour it, and
 * which platform constant each mode becomes.
 *
 * Plain JVM. `themeModeAvailable` takes the SDK level as an argument for exactly this reason — this module
 * sets `unitTests.isReturnDefaultValues = true`, so a direct `Build.VERSION.SDK_INT` read here would be 0.
 */
class ThemeModeTest {
    @Test
    fun anAbsentOrUnknownModeReadsAsTheSystem() {
        assertEquals(ThemeMode.System, ThemeMode.of(null))
        assertEquals(ThemeMode.System, ThemeMode.of(""))
        assertEquals(ThemeMode.System, ThemeMode.of("Sepia"))
        // Case matters: the value is written by `name`, so a near-miss is still a value we did not write.
        assertEquals(ThemeMode.System, ThemeMode.of("dark"))
    }

    @Test
    fun everyModeRoundTripsThroughItsStoredName() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.of(mode.name))
        }
    }

    /** Per-app night mode is API 31; 29 and 30 are the two releases minSdk still carries without it. */
    @Test
    fun perAppNightModeStartsAtApi31() {
        assertFalse(themeModeAvailable(29))
        assertFalse(themeModeAvailable(30))
        assertTrue(themeModeAvailable(31))
        assertTrue(themeModeAvailable(36))
    }

    /**
     * The mapping the whole feature rests on. `MODE_NIGHT_AUTO` is what *clears* the override —
     * `UiModeManagerService` maps it to `Configuration.UI_MODE_NIGHT_UNDEFINED` — so "follow the system"
     * is expressible, and a user who pins dark can get back out again.
     */
    @Test
    fun eachModeMapsToItsPlatformConstant() {
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, nightModeFor(ThemeMode.System))
        assertEquals(UiModeManager.MODE_NIGHT_NO, nightModeFor(ThemeMode.Light))
        assertEquals(UiModeManager.MODE_NIGHT_YES, nightModeFor(ThemeMode.Dark))
    }
}
