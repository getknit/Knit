package app.getknit.knit.location

import kotlinx.coroutines.flow.Flow

/**
 * Where the device's position comes from — the seam between the composer and `android.location`, so the
 * ViewModel that stages a location is JVM-tested against an in-process fake, the way the link-preview
 * fetch sits behind `PreviewFetcher` and the radios behind `MeshTransport`.
 *
 * The contract that keeps the feature honest: **nothing here runs unless it is called.** [fixes] registers
 * with the platform only while it is collected and unregisters when the collector goes away, so the one
 * collector — the staged tile, between the pin tap and the send — is the whole of the app's location use.
 * No implementation may cache a listener, poll, or read a position on its own initiative.
 */
interface LocationSource {
    /** Whether the system location toggle is on; false means no provider can answer and the tile must say so. */
    fun isEnabled(): Boolean

    /** What the runtime grant allows this instant, re-read on every use because the user can change it. */
    fun precision(): LocationPrecision

    /**
     * The platform's cached reading when it is recent enough to show ([LocationFixPolicy.usableLastKnown]),
     * else null. Reads a cache; never turns a radio on.
     */
    suspend fun lastKnown(): LocationFix?

    /**
     * Live readings from every enabled provider, for as long as the flow is collected. Completes on its own
     * only when no provider could be subscribed (no grant, nothing enabled), so a collector that sees the
     * end with no reading knows the platform had nothing to give.
     */
    fun fixes(): Flow<LocationFix>
}
