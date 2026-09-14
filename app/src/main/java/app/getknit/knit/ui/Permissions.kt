package app.getknit.knit.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions the radios need across both transport planes, **tiered by API level** — the permission
 * model changed twice under us, so a single static list would wedge onboarding below API 33 (an ungrantable
 * permission would keep [hasRadioPermissions] false forever):
 *
 * - **API 33+** — location-free. Wi-Fi Aware discovery rides `NEARBY_WIFI_DEVICES` (`neverForLocation`) and
 *   BLE rides the split `BLUETOOTH_SCAN`/`ADVERTISE`/`CONNECT` (`BLUETOOTH_SCAN` also `neverForLocation`;
 *   identity is the nodeId, RSSI is proximity-only).
 * - **API 31-32** — the split BLE permissions exist, but Wi-Fi Aware discovery has no `NEARBY_WIFI_DEVICES`
 *   yet, so it needs `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (Android 12 requires the pair — the
 *   precise/approximate toggle; there is no `neverForLocation` escape before API 31). `NEARBY_WIFI_DEVICES`
 *   is not a runtime permission pre-33.
 * - **API 29-30** — no split BLE permissions; BLE scan *and* Wi-Fi Aware discovery both need fine location
 *   (`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`). Legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` are install-time
 *   (normal) permissions auto-granted from the manifest, so they are not requested here.
 *
 * This set is the **gate**: the transports assume every one of these is held (`@SuppressLint("MissingPermission")`
 * is a lint suppression there, not a runtime guard), so the mesh must not start without them — `KnitApp` and
 * `BootReceiver` both decide on [hasRadioPermissions]. The notification grant is deliberately *not* in it: see
 * [optionalNotificationPermission].
 *
 * A device missing either radio is handled at runtime (the transport self-degrades and the composite runs
 * whichever plane is present), so an unused permission on a given device is simply a grant never exercised.
 * [sdkInt] is injectable (default [Build.VERSION.SDK_INT]) so the tiering is a pure, unit-testable function.
 */
fun requiredRadioPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
    when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        }

        sdkInt >= Build.VERSION_CODES.S -> {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

        else -> {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
    }

/**
 * The runtime notification grant — `POST_NOTIFICATIONS` on API 33+, null below (install-time there). Asked for
 * on the onboarding permissions page but **never a gate**: `MessageNotifier` checks the grant before every post
 * and skips silently without it, and the foreground service's own notification goes through `startForeground`,
 * which needs no grant. A user who declines it still meshes; they just don't hear about messages until they
 * open the app.
 */
fun optionalNotificationPermission(sdkInt: Int = Build.VERSION.SDK_INT): String? =
    Manifest.permission.POST_NOTIFICATIONS.takeIf { sdkInt >= Build.VERSION_CODES.TIRAMISU }

/** Which permission Android actually puts in front of the user — drives the onboarding row's title and rationale. */
enum class MeshPermissionTier {
    /** API 29–30: the location pair alone. */
    LOCATION,

    /** API 31–32: the split Bluetooth grants plus the location pair. */
    LOCATION_AND_BLUETOOTH,

    /** API 33+: nearby Wi-Fi devices plus the split Bluetooth grants; location never named. */
    NEARBY_DEVICES,
}

/** The tier [requiredRadioPermissions] resolves to on [sdkInt] — the two must agree, and `PermissionsTest` pins it. */
fun meshPermissionTier(sdkInt: Int = Build.VERSION.SDK_INT): MeshPermissionTier =
    when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> MeshPermissionTier.NEARBY_DEVICES
        sdkInt >= Build.VERSION_CODES.S -> MeshPermissionTier.LOCATION_AND_BLUETOOTH
        else -> MeshPermissionTier.LOCATION
    }

fun hasRadioPermissions(context: Context): Boolean =
    requiredRadioPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
