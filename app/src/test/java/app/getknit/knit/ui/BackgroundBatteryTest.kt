package app.getknit.knit.ui

import android.app.ActivityManager
import android.app.Application
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * [backgroundBattery] folds the two platform probes into the one radio group the user actually sees.
 * The case that matters is the last one: Restricted wins even while the exemption still reads true
 * underneath it, because Restricted is the position that decides whether the mesh runs off screen at all.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundBatteryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun set(
        exempt: Boolean,
        restricted: Boolean,
    ) {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, exempt)
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(restricted)
    }

    @Test
    fun `the default is Optimized`() {
        set(exempt = false, restricted = false)
        assertEquals(BackgroundBattery.Optimized, backgroundBattery(app))
    }

    @Test
    fun `the exemption reads as Unrestricted`() {
        set(exempt = true, restricted = false)
        assertEquals(BackgroundBattery.Unrestricted, backgroundBattery(app))
    }

    @Test
    fun `Restricted wins over a stale exemption`() {
        set(exempt = true, restricted = true)
        assertEquals(BackgroundBattery.Restricted, backgroundBattery(app))
    }
}
