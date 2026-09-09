package app.getknit.knit.location

/**
 * The rules the staged tile refines a position by, pure so they are JVM-tested: which of two readings to
 * keep, when one is good enough to stop listening, and how old a cached reading may be before it is not
 * worth showing. The tile owns the window ([REFINE_WINDOW_MS]) — the platform's own duration limit is not
 * honoured by the pre-31 request path, so nothing below this layer may be trusted to stop on its own.
 */
object LocationFixPolicy {
    /** A radius at or under this ends the refine early; GPS in the open settles here within seconds. */
    const val GOOD_ENOUGH_M = 8f

    /** How long the tile listens before it freezes what it has — the bound on the "location in use" indicator. */
    const val REFINE_WINDOW_MS = 60_000L

    /** A cached reading older than this is not shown while a fresh one is sought: the phone has probably moved. */
    const val MAX_LAST_KNOWN_AGE_MS = 120_000L

    /** Two readings further apart than this are about different moments, and the newer one wins on age alone. */
    const val STALE_AFTER_MS = 10_000L

    /**
     * The reading to keep between [current] and [candidate]: a candidate clearly newer than the current one
     * wins outright (an old, tighter radius describes where the phone *was*), a candidate clearly older loses
     * the same way, and within the same moment the tighter radius wins, a known radius beating none.
     */
    fun better(
        current: LocationFix?,
        candidate: LocationFix,
    ): LocationFix {
        if (current == null) return candidate
        val newerBy = candidate.elapsedRealtimeMs - current.elapsedRealtimeMs
        if (newerBy > STALE_AFTER_MS) return candidate
        if (newerBy < -STALE_AFTER_MS) return current
        val currentRadius = current.accuracyM ?: Float.MAX_VALUE
        val candidateRadius = candidate.accuracyM ?: Float.MAX_VALUE
        return if (candidateRadius <= currentRadius) candidate else current
    }

    /** Whether [fix] is tight enough that listening on would only spend battery. */
    fun isGoodEnough(fix: LocationFix): Boolean = fix.accuracyM != null && fix.accuracyM <= GOOD_ENOUGH_M

    /** [fix] when it is recent enough to seed the tile while a fresh reading is sought, else null. */
    fun usableLastKnown(
        fix: LocationFix?,
        nowElapsedMs: Long,
    ): LocationFix? = fix?.takeIf { nowElapsedMs - it.elapsedRealtimeMs in 0..MAX_LAST_KNOWN_AGE_MS }
}
