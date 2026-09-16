package app.getknit.knit.legal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Every [License] reads back from the merged assets — the same `AssetManager` path the screen takes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LicenseTextReadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyLicenseTextIsReadable() =
        runTest {
            License.entries.forEach { license ->
                val text = readLicenseText(context.assets, license, UnconfinedTestDispatcher(testScheduler))
                assertTrue("${license.asset} unreadable", text != null && text.length > 1_000)
            }
        }

    @Test
    fun theGplTextIsTheGpl() =
        runTest {
            val text = readLicenseText(context.assets, License.GPL_3_0_OR_LATER, UnconfinedTestDispatcher(testScheduler))
            assertTrue(text!!.contains("GNU GENERAL PUBLIC LICENSE") && text.contains("Version 3, 29 June 2007"))
        }
}
