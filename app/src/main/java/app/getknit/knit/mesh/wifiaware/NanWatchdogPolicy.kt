package app.getknit.knit.mesh.wifiaware

/**
 * Pure decision for the two-tier NAN data-plane **wedge watchdog** (the leaked-request self-heal;
 * `docs/DIGEST_PULL_REATTACH.md`). Extracted from `WifiAwareTransport.checkWedge` so the episode-clock
 * arithmetic — the subtle part, an *owed-episode* measured from when divergence appeared, reset on any
 * progress, NOT time-since-last-link — is exercised on the JVM ([NanWatchdogPolicy]'s test). The transport
 * still owns the two owed signals, the clock, and the side effects (session cycle / process kill); this
 * object only maps them to an [Action] plus the next episode-clock values.
 *
 * Both tiers are measured against the **uncorroborated** [reachableOwed] so they still fire when the
 * corroborating Bluetooth plane is dark; the Tier-2 process kill is additionally gated on the
 * **corroborated** [corroboratedOwed] so an out-of-range-but-still-cueing peer can't self-kill the node.
 *
 * **Tier 1 is capped per episode ([maxResponderRefreshes]), and the cap is load-bearing.** Until it
 * existed, Tier 1 re-fired every [reattachCooldownMs] for as long as the sync stayed owed, and because
 * the watchdog's own check period is *longer* than that cooldown the cooldown never blocked — so the
 * Tier-2 fall-through the policy was written to reach (see `tier1CooldownBlockedFallsThroughToTier2`)
 * was unreachable in production and Tier 1 ran forever. That is not a benign retry: each refresh is a
 * `sessionCycleWithSettle()`, which closes the Aware attach and both discovery sessions, wiping the
 * framework's network-request cache and orphaning every `PeerHandle` the initiator would dial with. A
 * three-Pixel lab capture (2026-09-07) caught the resulting livelock — P7 and P9 cycling every ~30 s for
 * 45 min, ~50 re-attaches each, every NDP initiate failing (consecutive fast-fail streaks of 49-52),
 * `lastLinkOrAcceptAt` therefore never advancing, so the episode never reset and Tier 1 never stopped.
 * The recovery was itself the fault: it kept tearing down the sessions the handshake needed to complete.
 * A refresh that has been tried [maxResponderRefreshes] times without producing a link is not working, so
 * the episode goes quiet and lets Tier 2 — correctly gated on corroboration and its own long clock —
 * escalate over a radio that is finally holding still.
 */
object NanWatchdogPolicy {
    enum class Action { None, RefreshResponder, RestartProcess }

    /**
     * [action] to take now, plus the values the transport must write back into its episode state:
     * [nextSyncOwedSince] (the episode clock) and [nextResponderRefreshes] (Tier-1 attempts spent in this
     * episode). Both reset together — an episode that ends for any reason forgets its refresh budget, so a
     * later genuine wedge gets a full set of attempts.
     */
    data class Decision(
        val action: Action,
        val nextSyncOwedSince: Long,
        val nextResponderRefreshes: Int,
    )

    /**
     * @param healthy hardware present, Aware healthy, and a live session — else we can't sync, so not wedged.
     * @param reachableOwed a sync is owed to a *reachable* peer (uncorroborated) — the Tier-1 / episode signal.
     * @param corroboratedOwed the owed peer is corroborated genuinely-present — the extra Tier-2 gate.
     * @param syncOwedSince start of the current owed-with-no-link episode (0 = none).
     * @param lastLinkOrAcceptAt last successful link/accept (either role) — "progress" resets the episode.
     * @param responderRefreshes Tier-1 session cycles already spent in this episode.
     * @param responderRefreshMs / [reattachCooldownMs] / [wedgeRestartMs] the transport's tuning constants.
     * @param maxResponderRefreshes Tier-1 attempts allowed per episode before it goes quiet for Tier 2.
     */
    @Suppress("LongParameterList")
    fun decide(
        healthy: Boolean,
        reachableOwed: Boolean,
        corroboratedOwed: Boolean,
        now: Long,
        syncOwedSince: Long,
        lastLinkOrAcceptAt: Long,
        lastReattachAt: Long,
        lastRestartAt: Long,
        responderRefreshes: Int,
        responderRefreshMs: Long,
        reattachCooldownMs: Long,
        wedgeRestartMs: Long,
        maxResponderRefreshes: Int,
    ): Decision {
        // Nothing owed (or can't sync) → not wedged; clear the episode and its refresh budget.
        if (!healthy || !reachableOwed) return Decision(Action.None, 0L, 0)
        // Episode not yet started, or a data-path link formed since it began → making progress → (re)start it.
        if (syncOwedSince == 0L || lastLinkOrAcceptAt >= syncOwedSince) return Decision(Action.None, now, 0)
        val owedFor = now - syncOwedSince
        // Tier 1: refresh the (possibly wedged) responder — light, uncorroborated, safe at 0 NDPs. Shares the
        // reattach cooldown with the discovery-loop pinned-responder cycle so the two can't stack, and is
        // capped per episode so a cycle that isn't curing anything stops re-breaking the sessions (see above).
        if (owedFor >= responderRefreshMs &&
            now - lastReattachAt >= reattachCooldownMs &&
            responderRefreshes < maxResponderRefreshes
        ) {
            return Decision(Action.RefreshResponder, syncOwedSince, responderRefreshes + 1)
        }
        // Tier 2: last-resort process kill, gated on corroboration so an out-of-range peer can't trigger it.
        if (owedFor < wedgeRestartMs || now - lastRestartAt < wedgeRestartMs || !corroboratedOwed) {
            return Decision(Action.None, syncOwedSince, responderRefreshes)
        }
        return Decision(Action.RestartProcess, syncOwedSince, responderRefreshes)
    }
}
