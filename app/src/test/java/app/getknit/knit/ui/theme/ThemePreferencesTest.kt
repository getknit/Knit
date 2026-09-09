@file:OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle is an experimental kotlinx API

package app.getknit.knit.ui.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ThemePreferences] as the process-scoped bridge between the store and the two things that read it: the
 * warmed Material You flag, and the platform's per-app night mode.
 *
 * Plain JVM against a recording [NightMode]; the real one is the app's only `UiModeManager` user.
 */
class ThemePreferencesTest {
    private val applied = mutableListOf<ThemeMode>()
    private val nightMode = NightMode { applied += it }

    /**
     * The process scope this class is given in production (`AppModule`'s app-wide `CoroutineScope`).
     * Unconfined so the collector starts at construction: `advanceUntilIdle` alone does not run
     * `backgroundScope` work, which is why `SettingsViewModelTest` reaches for the same dispatcher.
     */
    private fun TestScope.appScope() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `the stored mode reaches the platform, once per change`() =
        runTest {
            val modes = MutableStateFlow(ThemeMode.System)
            ThemePreferences(MutableStateFlow(false), modes, nightMode, appScope())
            advanceUntilIdle()
            // Applied at process start even when it matches: the system-side override can be missing after a
            // restore that carried the preferences but not the override, and this is what heals that.
            assertEquals(listOf(ThemeMode.System), applied)

            modes.value = ThemeMode.Dark
            advanceUntilIdle()
            assertEquals(listOf(ThemeMode.System, ThemeMode.Dark), applied)

            // A DataStore write re-emits every flow in the store, so the collector must not re-apply a mode
            // that did not change — each apply is a potential Activity recreate.
            modes.value = ThemeMode.Dark
            advanceUntilIdle()
            assertEquals(listOf(ThemeMode.System, ThemeMode.Dark), applied)

            modes.value = ThemeMode.System
            advanceUntilIdle()
            assertEquals(listOf(ThemeMode.System, ThemeMode.Dark, ThemeMode.System), applied)
        }

    @Test
    fun `the Material You flag is warmed and stays live`() =
        runTest {
            val dynamic = MutableStateFlow(false)
            val prefs = ThemePreferences(dynamic, MutableStateFlow(ThemeMode.System), nightMode, appScope())
            advanceUntilIdle()
            assertEquals(false, prefs.dynamicColor.value)

            dynamic.value = true
            advanceUntilIdle()
            assertTrue(prefs.dynamicColor.value)
        }
}
