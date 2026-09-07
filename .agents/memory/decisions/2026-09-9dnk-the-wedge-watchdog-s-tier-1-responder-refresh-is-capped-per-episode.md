---
id: "2026-09.9dnk"
slug: the-wedge-watchdog-s-tier-1-responder-refresh-is-capped-per-episode
title: "The wedge watchdog's Tier-1 responder refresh is capped per episode"
date: 2026-09-07
topics: [mesh, nan, recovery]
---

# ADR 2026-09.9dnk — The wedge watchdog's Tier-1 responder refresh is capped per episode

A three-Pixel lab session (2026-09-07) reported "P7 isn't receiving P8's messages, though both say they're
connected over NAN". Broadcast-room chat *was* arriving — `shouldFastFanout` puts it on the coordination
plane, which needs no data path. What never arrived was anything that rides the NDP: DMs, group chat, and
custody sync. `…debug.STORE` showed P7 and P9 byte-identical (same `liveFingerprint`, 170 rows) with P8
holding 67 rows nobody else had, and every initiate on the fleet failing:

```
12:54:09.208 HAL: COMMAND_TYPE_INITIATE_DATA_PATH_SETUP   app.getknit.knit
12:54:09.211 HAL: RESPONSE_TYPE_ON_INITIATE_DATA_PATH_SUCCESS
12:54:10.066 HAL: NOTIFICATION_TYPE_ON_DATA_PATH_CONFIRM      ← the NDP came up
12:54:10.074 app: handshake with hzwy… ended without a link (stale=true streak=49)
```

The firmware negotiated the data path; the app called it a stale peer handle. Consecutive fast-fail streaks
stood at 49-52 and had been climbing for hours, so `dropHandleOnFastFail` had long since stopped re-arming
discovery and was blaming the peer's responder — for a fault on our own side.

The cause was the watchdog's own Tier-1 cure. P7 and P9 logged `session cycle: settle elapsed — re-attaching
over the wiped request cache` **every ~30 s for 45 minutes** (~50 re-attaches each, `mNextClientId` 1559→1608
and 906→953). Two things made that permanent, and both had to be fixed:

- **Tier 1 had no bound.** `NanWatchdogPolicy` re-fired `RefreshResponder` whenever `owedFor >=
  RESPONDER_REFRESH_MS` and the reattach cooldown had elapsed, stamping `lastReattachAt` but leaving
  `syncOwedSince` alone. `WEDGE_CHECK_MS` (30 s) is **longer** than `REATTACH_COOLDOWN_MS` (20 s), so the
  cooldown never blocked — meaning the Tier-2 fall-through the policy was written for (and which
  `tier1CooldownBlockedFallsThroughToTier2` covers) was unreachable in production. Tier 1 ran forever and the
  180 s corroborated restart never got a turn.
- **A session cycle orphaned the handles it invalidated.** `sessionCycleWithSettle` closed the attach and both
  discovery sessions but left `discovered` populated. A `PeerHandle` is only valid on the session it was
  learned on, so `initiateTo` then paired a *fresh* subscribe session with a *dead* handle; worse,
  `needsRediscovery` read those stale entries as "we already hold a handle" and suppressed the subscribe
  re-arm that would have refreshed them. `stop()` and `rearmSubscribe()` had always cleared the map; this path
  and the NAN-down path had not.

So the recovery caused the failure it was recovering from: each cycle tore down the sessions the next
handshake needed, that handshake failed, `lastLinkOrAcceptAt` never advanced, the episode never reset, and
Tier 1 fired again 30 s later. Self-sustaining, and invisible to the health surfaces — discovery and the
publish SSI are session config updates, not data-path traffic, so every device kept reporting `Healthy` with
both peers reachable throughout.

The obvious fix is to make Tier 1 cheaper or slower. It does not work: any cadence that still cycles sessions
while an episode is open re-opens the same race, and lengthening it only widens the window in which DMs are
silently undeliverable. The bound has to be on **attempts**, not time — a cure that has been applied three
times without producing a link is not the cure. `NanWatchdogPolicy.decide` now takes `responderRefreshes` and
`maxResponderRefreshes` and returns `nextResponderRefreshes` alongside `nextSyncOwedSince`; the two reset
together, so an episode that ends for any reason refunds the whole budget and a later genuine wedge gets a
full set of attempts. `MAX_RESPONDER_REFRESHES = 3` spends the budget by ~120 s and leaves the radio still for
the last ~60 s of the episode — long enough for a handshake to finish, and the only reason Tier 2 is reachable
at all.

Verified on the same three Pixels, same session (2026-09-07). Before: zero NDPs in 45 min, streaks 49-52,
P8 holding 67 frames alone, a DM and a group message from P8 both undeliverable to P7. After: first `link up`
within 90 s, all three converged on one `liveFingerprint` (247 rows, then 261) and holding across a 12-minute
soak, and the full matrix — room, DM, group — delivering. The single Tier-1 cycle that did fire in the soak
(P8, 13:25:28) was followed by `link up` **10 s later**, which is the whole change in one line: with
`discovered` cleared the subscribe re-arms, the handles are fresh, and the cycle cures instead of
perpetuating. P7's group traffic recovered on its own once the NDP existed — three `NO_KEY` drops, then
`requesting group key`, then no further `NO_KEY` — confirming the ADR 017 recovery was never broken, only
starved: its request is a ctl DM and so needed the very data path that was down.

What this does **not** do is explain why the framework dropped a confirmed NDP in the first place. It removes
the app's contribution — the stale-handle pairing and the session churn — and restores the escalation path;
if a wedge survives three quiet cycles it now reaches the Tier-2 restart instead of cycling forever. Two traps
for the next person. First, the cap is only correct while it is spent *before* `WEDGE_RESTART_MS`: raise
`MAX_RESPONDER_REFRESHES` or drop `WEDGE_RESTART_MS` and Tier 2 becomes unreachable again, which is the bug
this ADR exists to close — `NanWatchdogPolicyTest.tier1CannotCycleForeverAndTier2IsReached` walks the real
`WEDGE_CHECK_MS` cadence and is the regression that catches it. Second, any *new* path that closes
`subscribeSession` must clear `discovered` with it; the map is the fleet's only record of which handles are
live, and a stale entry both mis-dials and suppresses its own repair.
