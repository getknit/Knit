package app.getknit.knit.legal

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallSourceTest {
    @Test
    fun playIsTheVendingPackage() {
        assertEquals(InstallSource.PLAY, installSourceOf("com.android.vending"))
    }

    @Test
    fun everyFdroidClientIsFdroid() {
        listOf("org.fdroid.fdroid", "org.fdroid.basic", "org.fdroid.fdroid.privileged").forEach {
            assertEquals(it, InstallSource.FDROID, installSourceOf(it))
        }
    }

    /** The package installer, another store and an unknown installer all say nothing about origin. */
    @Test
    fun everythingElseIsSideloaded() {
        listOf(null, "com.google.android.packageinstaller", "com.android.packageinstaller", "com.aurora.store", "").forEach {
            assertEquals("$it", InstallSource.SIDELOADED, installSourceOf(it))
        }
    }
}
