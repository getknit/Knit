---
id: "055"
slug: the-attach-budget-s-refund-path-was-the-leak
title: "The attach budget's refund path was the leak: a bound is only worth what refunds it"
date: 2026-08-27
topics: [mesh, nan, reliability]
---

# ADR 055 — The attach budget's refund path was the leak: a bound is only worth what refunds it

ADR 052 shipped in 2.3.1 and did not fix getknit/Knit#9. The reporter came back with `versionName=2.3.1` and
a logcat that says exactly what went wrong, in two details:

```
13:58:11.558 W WifiAwareTransport: Wi-Fi Aware attach failed (streak 1) — next attach in 2947ms
13:58:11.562 W WifiAwareTransport: Wi-Fi Aware attach failed (streak 1) — next attach in 3404ms
13:58:11.565 W WifiAwareTransport: Wi-Fi Aware attach failed (streak 1) — next attach in 2810ms
```

**`streak 1` on every line**, so the counter was zero on entry every time; and **3.5 ms apart**, so a computed
3-second backoff delayed nothing. ~286 attaches a second, ~570 binder objects a second, across the AMS
watermark in about ten seconds. `noteAttachFailed` increments before it logs, so only `clearAttachBackoff`
could produce that, and of its three callers `onAttached` and `stop` were both out.

It was the availability receiver — the half ADR 052 called load-bearing. That receiver had no edge detection
at all: it read `mgr.isAvailable` fresh on each broadcast and treated every `true` as "Aware came back". On a
chipset that cannot produce a NAN interface, each refused attach makes the framework churn Aware state and
broadcast again, `isAvailable` still `true`. Attach, fail, broadcast, refund, attach. **The refusal drove its
own refund**, and the tighter the loop, the faster it ran. On a working radio the streak is always 0, so
nothing about this is observable outside a device that refuses.

The correction is not a better refund rule. It is that **a bound whose refund path can be driven by the thing
it bounds is not a bound**, so `NanAttachPolicy` now carries three that fail independently:

- **The streak** (`backoffMs`/`giveUp`) still paces retries and is still refundable, because a genuine change
  in the radio really does make a past streak meaningless. Refunding it is now gated on a real `false→true`
  transition — `lastAvailable` compared against the broadcast, seeded at `registerAvailability`. A repeat of
  `true` is not news.
- **`tooSoon` floors the rate** at `MIN_ATTACH_INTERVAL_MS` (3 s, the same figure, so it is invisible to
  everything that already paced itself). Nothing refunds it; it reads `attachStartedAt`, which is never
  cleared. Had it existed, this bug would have cost one attach per 3 s and no kill at all.
- **`leakBudgetSpent` caps the total** at `MAX_LIFETIME_FAILURES` = 200 for the life of the process, refunded
  only by an attach that actually succeeds — not by availability, not by `stop`. 400 binder objects, ever,
  whatever the other two do. This is the one that makes the kill unreachable rather than merely unlikely.

**Giving up is now durable**, via `data/settings/NanAttachJournal` (the `ModelLoadJournal` seam, same shape).
`MeshService` is `START_STICKY`, so an AMS kill is followed by a restart, and a fresh process was starting
with an empty streak and a full budget — re-learning the same refusal forever. The stored value is a stamp,
app version code plus `Build.FINGERPRINT`, so a new build or a flashed ROM re-arms on its own; #9 is a
LineageOS device, and a ROM update is exactly the event that might publish a working STA+NAN combination.

Honest residuals. The floor and the streak floor are the same 3 s by construction, so the first retry after a
lone transient failure is unchanged — but a caller that legitimately wants two attaches inside 3 s now cannot
have them; `reattach()`'s inline attach is the only one that ever tried, and the discovery loop already
covered it one beat later. A device that reaches the lifetime cap stays off Aware until it is restarted, a
Wi-Fi toggle re-arms it, or the app/ROM stamp changes; Bluetooth carries the mesh throughout, which is why #9
reported crashes and never reported undelivered messages. `NanAttachPolicyTest` pins the floor against a
replay of the reporter's 4-ms broadcast storm (10,000 broadcasts → 14 attaches) and the cap's two budget
numbers; the receiver's edge itself is not unit-tested, because the transport is Android-bound — that is what
the field report is for.
