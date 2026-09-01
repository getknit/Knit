---
id: "026"
slug: the-era-gate-is-single-clock-only-when-we-responded
title: "The era gate is single-clock only when we responded; the initiator half needs a local bound"
date: 2026-08-19
topics: [crypto, pfs, recovery]
---

# ADR 026 — The era gate is single-clock only when we responded; the initiator half needs a local bound

ADR 024's era gate (`InboundPipeline.isLiveEvidence`) compares `env.sentAt` against
`session.establishedAt` and claims the rule "reads identically on both ends". It does not, and that
paragraph is superseded. `sentAt` is always the *sender's* clock; `establishedAt` follows
`weAreInitiator`, and a security audit of `v2.2.3..HEAD` (GitLab `knit/knit-next#22`) found the two are
the same clock in only one of the two directions:

| write site | value | `weAreInitiator` | clock |
| --- | --- | --- | --- |
| `RatchetEngine.initiate` | `now` | `true` | **ours** |
| responder establish | `init.at` | `false` | the peer's |
| replacement adopt | `init.at` | `false` | the peer's |
| race-loser adopt | `init.at` | `false` | the peer's |

The race *winner* changes neither field, so the correlation is total: **`establishedAt` is our own clock
exactly when `weAreInitiator`**. When we responded, `establishedAt` IS the sender's clock and the raw
comparison is exact. When we initiated, it is ours, and a peer whose clock lags ours has every frame it
sends classified pre-era until the skew is worked off — the heuristic silently disabled in that
direction, which is the one-directional blackout ADR 024 was opened to close, reappearing under skew
instead of custody re-serves. On offline mesh devices that may run for weeks without network time, that
is the expected case, not the corner one.

The fix keeps the exact comparison for `!weAreInitiator` and gives the initiator half two bounds:

1. `Protocol.MAX_FUTURE_SKEW_MS` (5 min), the house tolerance already used for inbound `sentAt` clamping
   and custody admission. Absorbs ordinary disagreement.
2. `RatchetSessions.STRANDED_TAIL_MS` (48 h) — if `now - establishedAt` exceeds it, the gate opens
   regardless of what the frame is stamped. This is the clause skew cannot defeat, because it compares
   our own clock against our own stamp. Its warrant is retention, not time-as-such: the reason a pre-era
   frame is not evidence is that custody (24 h) and the spool's default scope retention (48 h) keep
   re-serving the tail. Past both, no tail survives anywhere, so an unreadable frame is real divergence
   whatever the peer thinks the time is.

Honest limit: a skew larger than 5 min still suppresses the heuristic in the initiator direction until
either the peer's clock passes our era stamp or the 48 h window expires. Bounded and self-healing, which
is the property that was missing; not instant.

**`establishedAtLocal` was considered and rejected.** The issue proposed stamping our local clock on the
session row and comparing it against local *arrival* time, so the gate never spans two clocks. That
defeats the gate outright: a re-served pre-era frame arrives *after* the era began, so
`arrival >= establishedAtLocal` always holds and every doomed frame passes — ADR 024's loop back in full.
It is also unnecessary, because on the only broken half `establishedAt` already *is* our local clock, so
`now - establishedAt` is a pure-local elapsed measure with no new column, no DB bump, no migration.
`establishedAt` itself stays untouched — it is the peers' shared idempotence anchor.

Scheme: `InboundPipeline` + one constant in `RatchetSessions`. No wire field, no schema change, no
vector. The suppressing return now logs (id, sender, `sentAt`, `establishedAt`, `weAreInitiator`) —
without it a wedged pair in the field is indistinguishable from one that simply has not reached three
distinct failures yet, which is how this stayed invisible. `RatchetEngineTest` pins the
`weAreInitiator` ⇔ our-clock invariant across all four write sites, since a fifth site that ignored it
would silently disarm the heuristic again.
