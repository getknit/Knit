package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanWatchdogPolicy
import app.getknit.knit.mesh.wifiaware.NanWatchdogPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [NanWatchdogPolicy] — the pure two-tier wedge episode-clock decision. */
class NanWatchdogPolicyTest {
    // Production tuning constants (WifiAwareTransport companion), passed explicitly here.
    private val responderRefreshMs = 45_000L
    private val reattachCooldownMs = 20_000L
    private val wedgeRestartMs = 180_000L
    private val maxResponderRefreshes = 3
    private val wedgeCheckMs = 30_000L // the watchdog's own poll period — longer than the cooldown, on purpose
    private val episodeStart = 1_000_000L

    /** decide() with an owed, running episode by default; each test overrides the axis it exercises. */
    @Suppress("LongParameterList") // mirrors the policy's own (suppressed) parameter list
    private fun decide(
        healthy: Boolean = true,
        reachableOwed: Boolean = true,
        corroboratedOwed: Boolean = true,
        now: Long,
        syncOwedSince: Long = episodeStart,
        lastLinkOrAcceptAt: Long = 0L, // < episodeStart ⇒ no progress
        lastReattachAt: Long = 0L,
        lastRestartAt: Long = 0L,
        responderRefreshes: Int = 0,
    ) = NanWatchdogPolicy.decide(
        healthy = healthy,
        reachableOwed = reachableOwed,
        corroboratedOwed = corroboratedOwed,
        now = now,
        syncOwedSince = syncOwedSince,
        lastLinkOrAcceptAt = lastLinkOrAcceptAt,
        lastReattachAt = lastReattachAt,
        lastRestartAt = lastRestartAt,
        responderRefreshes = responderRefreshes,
        responderRefreshMs = responderRefreshMs,
        reattachCooldownMs = reattachCooldownMs,
        wedgeRestartMs = wedgeRestartMs,
        maxResponderRefreshes = maxResponderRefreshes,
    )

    @Test
    fun unhealthyClearsTheEpisode() {
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, 0L, 0),
            decide(healthy = false, now = episodeStart + 200_000L, responderRefreshes = 2),
        )
    }

    @Test
    fun nothingOwedClearsTheEpisode() {
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, 0L, 0),
            decide(reachableOwed = false, now = episodeStart + 200_000L, responderRefreshes = 2),
        )
    }

    @Test
    fun firstOwedTickStartsTheEpisodeAtNow() {
        val now = 1_234_567L
        assertEquals(NanWatchdogPolicy.Decision(Action.None, now, 0), decide(now = now, syncOwedSince = 0L))
    }

    @Test
    fun aLinkSinceTheEpisodeBeganRestartsTheClock() {
        val now = episodeStart + 50_000L
        // lastLinkOrAcceptAt >= syncOwedSince ⇒ progress ⇒ re-anchor the episode to now, take no action.
        assertEquals(NanWatchdogPolicy.Decision(Action.None, now, 0), decide(now = now, lastLinkOrAcceptAt = episodeStart))
    }

    @Test
    fun progressRefundsTheRefreshBudget() {
        val now = episodeStart + 50_000L
        // A link formed, so the next wedge episode gets a full set of Tier-1 attempts again.
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, now, 0),
            decide(now = now, lastLinkOrAcceptAt = episodeStart, responderRefreshes = maxResponderRefreshes),
        )
    }

    @Test
    fun tier1FiresAtTheResponderRefreshBoundaryWithCooldownElapsed() {
        val now = episodeStart + responderRefreshMs // owedFor == boundary (>=)
        assertEquals(NanWatchdogPolicy.Decision(Action.RefreshResponder, episodeStart, 1), decide(now = now))
    }

    @Test
    fun tier1DoesNotFireJustBelowTheBoundary() {
        val now = episodeStart + responderRefreshMs - 1
        assertEquals(NanWatchdogPolicy.Decision(Action.None, episodeStart, 0), decide(now = now))
    }

    @Test
    fun tier1BlockedByCooldownHoldsWhileBelowRestart() {
        val now = episodeStart + 50_000L // past refresh, below restart
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, episodeStart, 0),
            decide(now = now, lastReattachAt = now - (reattachCooldownMs - 1)), // cooldown NOT elapsed
        )
    }

    @Test
    fun tier1CooldownBlockedFallsThroughToTier2() {
        val now = episodeStart + wedgeRestartMs // owedFor == restart boundary
        assertEquals(
            NanWatchdogPolicy.Decision(Action.RestartProcess, episodeStart, 0),
            decide(now = now, lastReattachAt = now - 5_000L), // tier-1 cooldown blocks; tier-2 eligible + corroborated
        )
    }

    @Test
    fun tier1StopsAtTheEpisodeCap() {
        val now = episodeStart + 150_000L // well past refresh, cooldown elapsed, still below restart
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, episodeStart, maxResponderRefreshes),
            decide(now = now, responderRefreshes = maxResponderRefreshes),
        )
    }

    @Test
    fun tier1CapLetsTier2Escalate() {
        val now = episodeStart + wedgeRestartMs
        // Cooldown long elapsed — only the cap keeps Tier 1 quiet, and that is what makes Tier 2 reachable.
        assertEquals(
            NanWatchdogPolicy.Decision(Action.RestartProcess, episodeStart, maxResponderRefreshes),
            decide(now = now, responderRefreshes = maxResponderRefreshes, lastReattachAt = episodeStart + 105_000L),
        )
    }

    /**
     * The livelock regression (three-Pixel capture, 2026-09-07). Polling at [wedgeCheckMs] — which is longer
     * than [reattachCooldownMs], so the cooldown never blocks — Tier 1 used to fire on every single tick
     * forever, and Tier 2 was therefore never reached. Walk the real cadence and assert the escalation:
     * exactly [maxResponderRefreshes] cycles, then quiet, then the corroborated restart.
     */
    @Test
    fun tier1CannotCycleForeverAndTier2IsReached() {
        var refreshes = 0
        var lastReattachAt = 0L
        val fired = mutableListOf<Long>()
        var restartedAt: Long? = null
        var now = episodeStart
        while (now <= episodeStart + wedgeRestartMs) {
            val d = decide(now = now, lastReattachAt = lastReattachAt, responderRefreshes = refreshes)
            refreshes = d.nextResponderRefreshes
            when (d.action) {
                Action.RefreshResponder -> {
                    fired += now - episodeStart
                    lastReattachAt = now
                }

                Action.RestartProcess -> {
                    restartedAt = now - episodeStart
                }

                Action.None -> {}
            }
            now += wedgeCheckMs
        }
        // Ticks land on wedgeCheckMs multiples, so the first eligible one is 60 s in, not the 45 s boundary.
        assertEquals(listOf(60_000L, 90_000L, 120_000L), fired)
        assertEquals(wedgeRestartMs, restartedAt)
    }

    @Test
    fun tier2GatedOffWhenUncorroborated() {
        val now = episodeStart + wedgeRestartMs
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, episodeStart, 0),
            decide(now = now, corroboratedOwed = false, lastReattachAt = now - 5_000L),
        )
    }

    @Test
    fun tier2RateLimitedHolds() {
        val now = episodeStart + wedgeRestartMs
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, episodeStart, 0),
            decide(now = now, lastReattachAt = now - 5_000L, lastRestartAt = now - (wedgeRestartMs - 1)),
        )
    }
}
