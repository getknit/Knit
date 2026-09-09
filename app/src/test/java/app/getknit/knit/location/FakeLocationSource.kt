package app.getknit.knit.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * An in-memory [LocationSource] for ViewModel tests: a test pushes readings into [readings] and reads
 * [collectors] to prove the one rule the feature makes — that nothing listens except the staged tile,
 * between the pin tap and the send. Never a relaxed mock: a mock's `fixes()` would hand back a flow that
 * never emits and never completes, and every assertion on the tile would stall.
 */
class FakeLocationSource : LocationSource {
    var enabled = true
    var precision = LocationPrecision.Fine
    var lastKnown: LocationFix? = null

    /** What [fixes] relays while collected. */
    val readings = MutableSharedFlow<LocationFix>(extraBufferCapacity = 16)

    /** Collectors alive right now — the platform listener count this stands in for. */
    var collectors = 0
        private set

    /** Every collection ever started, so a test can tell a re-arm from a never-stopped one. */
    var subscriptions = 0
        private set

    /** When true, [fixes] completes at once with nothing, as the platform does with no provider to ask. */
    var noProvider = false

    override fun isEnabled(): Boolean = enabled

    override fun precision(): LocationPrecision = precision

    override suspend fun lastKnown(): LocationFix? = lastKnown

    override fun fixes(): Flow<LocationFix> =
        flow {
            subscriptions++
            collectors++
            try {
                if (!noProvider) emitAll(readings)
            } finally {
                collectors--
            }
        }
}
