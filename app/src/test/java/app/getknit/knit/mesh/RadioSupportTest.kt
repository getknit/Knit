package app.getknit.knit.mesh

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM guard for the tiered Wi-Fi Aware verdict. `Build.VERSION_CODES.*` are compile-time constants, so
 * this needs no Robolectric — which matters because Robolectric is pinned to one SDK and the mockable
 * `android.jar` reports `SDK_INT` as 0, so every case passes the SDK explicitly (as `PermissionsTest` does).
 * The load-bearing assertion is the tier order: missing hardware outranks the API floor, so a NAN-less
 * Android 11 phone is told "not supported", never "needs Android 12".
 */
class RadioSupportTest {
    @Test
    fun noHardware_isNotSupported_whateverTheSdk() {
        for (sdk in intArrayOf(Build.VERSION_CODES.Q, Build.VERSION_CODES.R, Build.VERSION_CODES.S, 36)) {
            assertEquals("sdk=$sdk", PlaneSupport.NoHardware, wifiAwareSupport(hasHardware = false, sdkInt = sdk))
        }
    }

    @Test
    fun hardwareBelowTheAcceptAnyResponderFloor_needsAndroid12() {
        // Q = 29, R = 30: the app installs there (minSdk 29) but the NDP data path needs API 31.
        for (sdk in intArrayOf(Build.VERSION_CODES.Q, Build.VERSION_CODES.R)) {
            assertEquals("sdk=$sdk", PlaneSupport.NeedsAndroid12, wifiAwareSupport(hasHardware = true, sdkInt = sdk))
        }
    }

    @Test
    fun hardwareAtOrAboveTheFloor_isSupported() {
        for (sdk in intArrayOf(Build.VERSION_CODES.S, Build.VERSION_CODES.TIRAMISU, 36)) {
            assertEquals("sdk=$sdk", PlaneSupport.Supported, wifiAwareSupport(hasHardware = true, sdkInt = sdk))
        }
    }

    @Test
    fun bluetoothHasNoFloor() {
        assertEquals(PlaneSupport.Supported, bleSupport(hasHardware = true))
        assertEquals(PlaneSupport.NoHardware, bleSupport(hasHardware = false))
    }

    @Test
    fun anyRadioIsEnoughToMesh_neitherIsNot() {
        assertTrue(RadioSupport(PlaneSupport.Supported, PlaneSupport.NoHardware).any)
        assertTrue(RadioSupport(PlaneSupport.NoHardware, PlaneSupport.Supported).any)
        // A NAN-capable Android 11 phone with no BLE: the composite would build no short-range child.
        assertFalse(RadioSupport(PlaneSupport.NoHardware, PlaneSupport.NeedsAndroid12).any)
        assertFalse(RadioSupport(PlaneSupport.NoHardware, PlaneSupport.NoHardware).any)
    }
}
