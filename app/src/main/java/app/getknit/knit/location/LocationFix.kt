package app.getknit.knit.location

import kotlin.math.ceil

/**
 * One reading of the device's position, as a [LocationSource] hands it over.
 *
 * [accuracyM] is the platform's 68 % radius in metres, null when the provider gave none. [timeMs] is the
 * wall clock the fix was taken at, for the tile's "as of" line; [elapsedRealtimeMs] is the monotonic clock
 * the freshness rules compare on, because the wall clock can jump. [coarse] says the grant was approximate
 * only, so the reading is deliberately fuzzed to a couple of kilometres and the tile must say so.
 */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val timeMs: Long,
    val elapsedRealtimeMs: Long,
    val coarse: Boolean = false,
) {
    /** The message form: the accuracy rounded *up* to whole metres, so a stated radius is never optimistic. */
    fun toPoint(): GeoPoint = GeoPoint(lat, lon, accuracyM?.let { ceil(it.toDouble()).toInt().coerceAtLeast(1) })
}

/** What the location grant allows this instant. */
enum class LocationPrecision { None, Coarse, Fine }
