package app.getknit.knit.mesh

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.getknit.knit.mesh.bluetooth.BluetoothMeshTransport

/**
 * Whether this device can run a phone-radio plane at all, and if not, why — the fact the Diagnostics
 * Transports section and the onboarding gate both read, so a plane the composite never built is explained
 * rather than silently missing. [NoHardware] outranks [NeedsAndroid12]: a NAN-less Android 11 phone must
 * not be told to upgrade Android.
 */
enum class PlaneSupport { Supported, NoHardware, NeedsAndroid12 }

/**
 * The Wi-Fi Aware verdict, tiered purely on its inputs so a JVM test can drive every case (Robolectric is
 * pinned to one SDK and the mockable `android.jar` reports `SDK_INT` as 0, so tests always pass [sdkInt]).
 * The floor is API 31, not the app's minSdk 29, because the accept-any responder the NDP data path depends
 * on (`WifiAwareNetworkSpecifier.Builder(publishSession)`) is API 31; 29–30 devices mesh over Bluetooth LE
 * only. `FEATURE_WIFI_AWARE` can be missing outright (some budget/older phones, certain Samsung and nubia
 * models), which is [hasHardware] false regardless of the SDK.
 */
fun wifiAwareSupport(
    hasHardware: Boolean,
    sdkInt: Int,
): PlaneSupport =
    when {
        !hasHardware -> PlaneSupport.NoHardware
        sdkInt < Build.VERSION_CODES.S -> PlaneSupport.NeedsAndroid12
        else -> PlaneSupport.Supported
    }

/** The Bluetooth LE verdict: there is no API floor, so it is only ever present or absent. */
fun bleSupport(hasHardware: Boolean): PlaneSupport = if (hasHardware) PlaneSupport.Supported else PlaneSupport.NoHardware

/**
 * The Wi-Fi Aware probe: the feature flag plus a live `WIFI_AWARE_SERVICE`, the same two facts
 * `WifiAwareTransport.isSupported` gates construction on. It lives here rather than on the transport because
 * that class is `@RequiresApi(31)` as a whole, and this must be callable on Android 10–11 to say so.
 */
fun wifiAwareSupport(
    context: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
): PlaneSupport =
    wifiAwareSupport(
        hasHardware =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
                context.getSystemService(Context.WIFI_AWARE_SERVICE) != null,
        sdkInt = sdkInt,
    )

/**
 * The device's two phone radios, probed once — a static fact for the life of the process. [any] is the
 * onboarding gate: a phone with either radio can mesh, and only one with neither is told it cannot.
 */
data class RadioSupport(
    val bluetooth: PlaneSupport,
    val wifiAware: PlaneSupport,
) {
    val any: Boolean get() = bluetooth == PlaneSupport.Supported || wifiAware == PlaneSupport.Supported

    companion object {
        /** A two-radio phone, for previews and tests that are not about hardware. */
        val ALL = RadioSupport(bluetooth = PlaneSupport.Supported, wifiAware = PlaneSupport.Supported)

        /** Reads both verdicts off the platform. The adapter probe stays behind `mesh/bluetooth/` (rules/mesh.md). */
        fun probe(context: Context): RadioSupport =
            RadioSupport(
                bluetooth = BluetoothMeshTransport.support(context),
                wifiAware = wifiAwareSupport(context),
            )
    }
}
