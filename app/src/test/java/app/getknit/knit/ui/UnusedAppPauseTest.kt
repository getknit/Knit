package app.getknit.knit.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * [unusedAppPause] reads the platform's auto-revoke allowlist back as the switch the user sees: on the
 * allowlist means the switch is off. The Android 10 case (`null`) is a `Build.VERSION` branch the JVM
 * runtime here (SDK 36) cannot take; it is one line, read by eye.
 */
@RunWith(AndroidJUnit4::class)
class UnusedAppPauseTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the default is On`() {
        shadowOf(app.packageManager).setAutoRevokeWhitelisted(false)
        assertEquals(UnusedAppPause.On, unusedAppPause(app))
    }

    @Test
    fun `the allowlist reads as Off`() {
        shadowOf(app.packageManager).setAutoRevokeWhitelisted(true)
        assertEquals(UnusedAppPause.Off, unusedAppPause(app))
    }
}
