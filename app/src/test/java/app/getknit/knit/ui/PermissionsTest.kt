package app.getknit.knit.ui

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM guard for the SDK-tiered [requiredRadioPermissions]. `Manifest.permission.*` and
 * `Build.VERSION_CODES.*` are compile-time constants (inlined into the test bytecode), so this needs no
 * Robolectric — which matters because Robolectric is pinned to `sdk=36` and could only ever exercise the
 * ≥33 branch. The load-bearing assertions: the two API-33-only permissions are **absent** below 33 (an
 * ungrantable permission would keep `hasRadioPermissions` false and wedge onboarding), and the notification
 * grant is **never** in the radio set on any tier — it is an optional row, not a gate.
 */
class PermissionsTest {
    @Test
    fun tiramisu_radioSetIsLocationFree_andCarriesNoNotificationGrant() {
        val perms = requiredRadioPermissions(Build.VERSION_CODES.TIRAMISU).toSet()
        assertEquals(
            setOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            perms,
        )
        // ADR 2026-09.tss4: the 33+ tier never names location — the pin asks on first use, not onboarding.
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in perms)
        assertFalse(Manifest.permission.ACCESS_COARSE_LOCATION in perms)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in perms)
    }

    @Test
    fun android12_requestsSplitBtPlusLocationPair_neverTheApi33Perms() {
        // S = 31, S_V2 = 32. Android 12 requires FINE + COARSE together (the precise/approximate toggle).
        for (sdk in intArrayOf(Build.VERSION_CODES.S, Build.VERSION_CODES.S_V2)) {
            val perms = requiredRadioPermissions(sdk).toSet()
            assertEquals(
                "sdk=$sdk",
                setOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                perms,
            )
            // The onboarding-wedge guard: neither API-33 permission may be requested pre-33.
            assertFalse("sdk=$sdk", Manifest.permission.NEARBY_WIFI_DEVICES in perms)
            assertFalse("sdk=$sdk", Manifest.permission.POST_NOTIFICATIONS in perms)
        }
    }

    @Test
    fun android10and11_requestOnlyTheLocationPair() {
        // Q = 29, R = 30 — legacy BLUETOOTH/BLUETOOTH_ADMIN are normal (auto-granted), not requested at runtime.
        for (sdk in intArrayOf(Build.VERSION_CODES.Q, Build.VERSION_CODES.R)) {
            assertEquals(
                "sdk=$sdk",
                setOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                requiredRadioPermissions(sdk).toSet(),
            )
        }
    }

    @Test
    fun radioSetNeverCarriesTheNotificationGrant() {
        for (sdk in Build.VERSION_CODES.Q..Build.VERSION_CODES.BAKLAVA) {
            assertFalse("sdk=$sdk", Manifest.permission.POST_NOTIFICATIONS in requiredRadioPermissions(sdk))
        }
    }

    @Test
    fun notificationGrant_isRuntimeFrom33_andAbsentBelow() {
        for (sdk in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2) {
            assertNull("sdk=$sdk", optionalNotificationPermission(sdk))
        }
        for (sdk in Build.VERSION_CODES.TIRAMISU..Build.VERSION_CODES.BAKLAVA) {
            assertEquals("sdk=$sdk", Manifest.permission.POST_NOTIFICATIONS, optionalNotificationPermission(sdk))
        }
    }

    @Test
    fun tier_tracksTheRadioSet() {
        // The row copy and the request list are derived separately from the same SDK; keep them agreeing.
        assertEquals(MeshPermissionTier.LOCATION, meshPermissionTier(Build.VERSION_CODES.Q))
        assertEquals(MeshPermissionTier.LOCATION, meshPermissionTier(Build.VERSION_CODES.R))
        assertEquals(MeshPermissionTier.LOCATION_AND_BLUETOOTH, meshPermissionTier(Build.VERSION_CODES.S))
        assertEquals(MeshPermissionTier.LOCATION_AND_BLUETOOTH, meshPermissionTier(Build.VERSION_CODES.S_V2))
        assertEquals(MeshPermissionTier.NEARBY_DEVICES, meshPermissionTier(Build.VERSION_CODES.TIRAMISU))
        assertEquals(MeshPermissionTier.NEARBY_DEVICES, meshPermissionTier(Build.VERSION_CODES.BAKLAVA))
    }
}
