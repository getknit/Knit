package app.getknit.knit.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The platform half of [LocationSource] — the **only** file that imports `android.location` (detekt's
 * `ForbiddenImport` keeps it so), and GMS-free: every call goes through `LocationManager`, which every
 * Android build has, via the `androidx.core` compat shims that paper over the API 29–31 differences.
 *
 * [fixes] subscribes every enabled provider among GPS, network and — on API 31+, where it exists as a
 * platform provider — fused, and lets [LocationFixPolicy] pick between their readings; a provider that
 * refuses (GPS under an approximate-only grant throws, a missing one throws) is skipped rather than fatal.
 * The subscription lives exactly as long as the collector: `awaitClose` unregisters, so the status-bar
 * location indicator goes off the moment the staged tile stops listening. The pre-31 request path ignores a
 * duration, which is why the window is the collector's to enforce, not this class's.
 *
 * Every platform call runs behind [precision], which reads `checkSelfPermission` for both grants and turns a
 * missing one into `None` before a provider is touched — lint cannot see through the helper, so the check is
 * marked the way the Aware and Bluetooth planes mark theirs. Each subscribe is also `runCatching`, so a grant
 * revoked between the check and the call is skipped, never thrown.
 */
@SuppressLint("MissingPermission")
class AndroidLocationSource(
    context: Context,
) : LocationSource {
    private val app = context.applicationContext
    private val manager: LocationManager? = app.getSystemService(LocationManager::class.java)

    override fun isEnabled(): Boolean = manager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

    override fun precision(): LocationPrecision =
        when {
            granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationPrecision.Fine
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationPrecision.Coarse
            else -> LocationPrecision.None
        }

    override suspend fun lastKnown(): LocationFix? {
        val lm = manager ?: return null
        val precision = precision()
        if (precision == LocationPrecision.None) return null
        val now = SystemClock.elapsedRealtime()
        return providers(lm)
            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .map { it.toFix(precision) }
            .fold(null as LocationFix?) { best, fix -> LocationFixPolicy.better(best, fix) }
            .let { LocationFixPolicy.usableLastKnown(it, now) }
    }

    override fun fixes(): Flow<LocationFix> =
        callbackFlow {
            val lm = manager
            val precision = precision()
            if (lm == null || precision == LocationPrecision.None) {
                close()
                return@callbackFlow
            }
            val listener = LocationListenerCompat { location -> trySend(location.toFix(precision)) }
            val request =
                LocationRequestCompat
                    .Builder(INTERVAL_MS)
                    .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
                    .build()
            val executor = ContextCompat.getMainExecutor(app)
            val subscribed =
                providers(lm).count { provider ->
                    runCatching { LocationManagerCompat.requestLocationUpdates(lm, provider, request, executor, listener) }.isSuccess
                }
            if (subscribed == 0) close()
            awaitClose { LocationManagerCompat.removeUpdates(lm, listener) }
        }

    /** The active providers worth asking, in the order the platform enables them; passive is never ours to drive. */
    private fun providers(lm: LocationManager): List<String> {
        val wanted = ArrayList<String>(3)
        wanted += LocationManager.GPS_PROVIDER
        wanted += LocationManager.NETWORK_PROVIDER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManagerCompat.hasProvider(lm, LocationManager.FUSED_PROVIDER)) {
            wanted += LocationManager.FUSED_PROVIDER
        }
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        return wanted.filter { it in enabled }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toFix(precision: LocationPrecision): LocationFix =
        LocationFix(
            lat = latitude,
            lon = longitude,
            accuracyM = if (hasAccuracy()) accuracy else null,
            timeMs = time,
            elapsedRealtimeMs = LocationCompat.getElapsedRealtimeMillis(this),
            coarse = precision != LocationPrecision.Fine,
        )

    private companion object {
        /** How often a provider is asked to report while the tile refines; one reading a second is GPS's own pace. */
        const val INTERVAL_MS = 1_000L
        const val MIN_INTERVAL_MS = 500L
    }
}
