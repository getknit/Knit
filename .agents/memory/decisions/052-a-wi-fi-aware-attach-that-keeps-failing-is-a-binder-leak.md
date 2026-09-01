---
id: "052"
slug: a-wi-fi-aware-attach-that-keeps-failing-is-a-binder-leak
title: "A Wi-Fi Aware attach that keeps failing is a binder leak, so the retry budget is a leak budget"
date: 2026-08-27
topics: [mesh, nan, reliability]
---

# ADR 052 — A Wi-Fi Aware attach that keeps failing is a binder leak, so the retry budget is a leak budget

The discovery loop's detached branch retried `WifiAwareManager.attach` at a flat `ATTACH_RETRY_MS` (3 s)
forever. That is right for every failure it was written for — the chipset needing a beat after a
`reattach()` teardown, Wi-Fi mid-toggle, another app briefly holding the single NAN interface — and on a
device where the attach can **never** succeed it is not merely wasteful. It gets the app killed.

**Each failed attach strands two binder objects in `system_server`.** `WifiAwareManager.attach` mints a fresh
`Binder` token *and* an `IWifiAwareEventCallback.Stub` and passes both to `IWifiAwareManager.connect`. The
success path releases them — `onConnectSuccess` hands the token to the new `WifiAwareSession` and
`session.close()` calls `disconnect(clientId, binder)`. The failure path has no release at all:
`onConnectFail` clears a `WeakReference` and calls `onAttachFailed`, and there is no client id to disconnect
with, so nothing ever tells `system_server` to let go (AOSP `WifiAwareManager`, read against the android-36.1
sources). `ActivityManagerService` watches that count per uid and kills every process of a uid that crosses
its high watermark — `REASON_EXCESSIVE_RESOURCE_USAGE` / `SUBREASON_EXCESSIVE_BINDER_OBJECTS`, description
"Too many Binders sent to SYSTEM". No exception, no trace, nothing in the log.

**`isAvailable` is the trap that let it run.** It reports whether Aware is *enabled*, not whether an interface
can be had, so on a chipset whose vendor HAL publishes no STA+NAN interface combination it stays `true` while
every attach fails at `HalDevMgr: bestIfaceCreationProposal is null` with `wlan0` holding the only slot.
`onAttachFailed` set `Degraded` and logged, the loop came back 3 s later, and that was the whole cycle:
~28.8 k failed attaches a day, ~57.6 k stranded objects, an AMS kill within hours — and then a re-kill within
seconds of each restart, because the dead proxies outlive our process until `system_server` collects them.
That is getknit/Knit#9 (OnePlus 8 `IN2010`, `kona`, LineageOS 23.2), reported as "crashes 10 s to half a
minute after opening, no logs at all". The reporter's `dumpsys activity exit-info` named the subreason; their
earlier logcat had already shown the cause, with the framework's Aware client id past 51 k while `wlan0` sat
at `Id=22`. The live count is readable with `adb shell dumpsys activity binder-proxies`, which also breaks
down by interface — a healthy Knit install sits at ~20 objects for its uid.

So `mesh/wifiaware/NanAttachPolicy` is a **leak budget wearing a backoff's clothes**, and its constants come
from what a failure costs rather than from any cadence one would otherwise pick:

- `BASE_BACKOFF_MS` equals the old flat cadence, so a *lone* failure retries exactly as promptly as it always
  did and the reattach-needs-a-beat case is unchanged.
- It doubles to a `MAX_BACKOFF_MS` of **half an hour** — far longer than a retry cadence is useful, which is
  the point: the only failure that survives it is a permanent one, and 48 attempts a day costs 96 objects a
  day against a watermark in the thousands.
- After `MAX_FAILURES` = 60 consecutive failures it **stops attaching altogether**: ~25 h of trying for ~120
  stranded objects, a couple of per cent of the budget. A radio that has refused for a day is not coming back
  on its own.

**What ends an episode is the load-bearing half.** A successful attach, recorded *before* the supersede check
in `onAttached` because the streak counts whether the radio opened, not whether we kept that session. Then the
availability broadcast, **both** edges: Aware changing state is the one genuinely new fact about the radio, so
a streak that predates it says nothing about the next attempt, and the up edge attaches immediately rather
than serving out a stale backoff. That keeps the common recovery — Wi-Fi off→on, airplane mode — as fast as it
was, and it is why giving up is safe. `heal()` deliberately does **not** clear it: it says the app was opened,
not that the radio changed, and refunding the fast part of the curve on every `ON_RESUME` would put the leak
back on a user-behaviour clock.

**The cost, stated plainly.** There is no broadcast for *another app* releasing the NAN interface, so polling
is the only recovery there: it now lags by up to half an hour, and after a day of refusals it stops until
availability changes. That is the trade, and it is not close — the alternative is a mesh app that silently
kills itself on any device whose chipset won't give it an interface.

Deliberately not done: no health state for "this device cannot do NAN". The plane already reports `Degraded`,
`CompositeMeshTransport` still reads Healthy off Bluetooth, and a permanent refusal is not distinguishable at
runtime from a long contended one — asserting otherwise in the UI would claim more than the evidence supports.
The gate lives inside `attach()`, the single choke point every caller funnels through, with
`rediscoverDelayMs` stretched to the deadline so the loop *sleeps* instead of waking only to be turned away.
`NanAttachPolicyTest` pins the curve, the cap, the overflow guard, the give-up threshold, and the two budget
numbers on the JVM.
