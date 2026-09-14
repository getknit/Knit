---
id: "2026-09.f69x"
slug: a-start-into-a-demoted-foreground-service-re-claims-the-state-instead-of-timing
title: "A start into a demoted foreground service re-claims the state instead of timing out"
date: 2026-09-14
topics: [reliability, service, android]
---

# ADR 2026-09.f69x — A start into a demoted foreground service re-claims the state instead of timing out

Status: Accepted (2026-09-14)

**What was observed.** Two Play crashes on 2.5.1 (vc21), one user, one device (motorola *manila*, Android
15), four hours apart on 2026-09-13: `ForegroundServiceDidNotStartInTimeException`, whose `Caused by` is
the app's last `startForegroundService()` — once from `KnitApp`'s `ON_RESUME` observer, once from its
route-keyed effect. It looked like `5da5601`'s crash again (a slow `onCreate` missing the deadline), and
it is not. Two facts rule that out. `ContextImpl.startServiceCommon` records that inner stack, and every
*successful* `Service.startForeground()` erases it (`clearStartForegroundServiceStackTrace`), so no
`startForeground` succeeded after that call — and `onCreate` calls it before anything else, so the start
did not land in `onCreate` at all. It landed in `onStartCommand` of an instance that was already alive,
which returned `START_STICKY` without touching the foreground state. And the deadline is not 10 s on 15:
`ActivityManagerConstants.DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS` is 30 s, so a main-thread stall
would have had to be three times what the old comments assumed, with no ANR alongside it.

**Why a live service was not foreground.** Read from `ActiveServices` on `android15-release`, not from
memory: `sendServiceArgsLocked` arms the deadline for *any* start with `fgRequired && !r.isForeground`,
and a foreground service can lose `isForeground` with no callback to the app. `updateForegroundApps`
does it when a **background-restricted** app leaves TOP ("bg-restricted app … exiting top; demoting fg
services" → `stopAllForegroundServicesLocked` → `stopForeground`), and the `BackgroundRestrictedListener`
does it the moment the user flips Battery → *Restricted* while the app is off screen. The service then
runs on as a plain started service until `stopInBackgroundLocked` stops it at the background settle
time (60 s). Come back to Knit inside that minute — the resume observer fires `startForegroundService`
(the pre-check passes: an activity is visible), the timer arms, `onStartCommand` runs, nothing calls
`startForeground`, and 30 s later `serviceForegroundTimeout` → `bringDownServiceLocked` with `fgRequired`
still set posts the crash. That is the whole day: restrict the app, leave, return; hours later the mesh is
gone again, open the app, return. Only a restricted install can reach it, which is why it is one user.
`getForegroundServiceType()` cannot detect the demotion (`r.foregroundServiceType` is not reset), so the
app has no way to *ask* whether it still holds the state.

**What changed.** `MeshService.onStartCommand` re-claims the foreground state on every start that is not
`ACTION_STOP`: the same `postForeground(buildNotification(…))` call `observeStatus` makes on every count or
health change, fed from the two `StateFlow`s so the text stays live. Calling `startForeground` again with
the same id is the supported update path, so on a service that still holds the state it is a notification
refresh; on a demoted one it re-claims (allowed, because `KnitApp` only starts us while TOP) and cancels
the deadline. A refusal — the same `IllegalStateException` family ADR 043 catches — means the state is gone
for this session, so the service stops itself (`START_NOT_STICKY`; `foregrounded` stays true so
`onDestroy` still brings the mesh down) rather than run on as a background service the system is about to
stop anyway. This amends ADR 043's "starting an already-running service is an idempotent null-action
`onStartCommand`": it is idempotent only while the system still agrees the service is foreground.

**The alternative.** Making `KnitApp` skip the redundant starts (a process-wide "running" flag) would hide
this instance of it and keep the trap: a `START_STICKY` restart, `BootReceiver`, and any future caller
would still be one demotion away from the same crash, and the resume start is the retry ADR 043 relies on.
The service is the one place that knows a start arrived, so the service re-asserts.

**What it costs and does not cover.** One `setServiceForeground` binder call per start — a resume and each
navigation, next to the `heal()` on the same line. It does not change what a restricted install *gets*:
the mesh still stops within a minute of leaving the screen, by the platform's design; surfacing
`ActivityManager.isBackgroundRestricted()` on the permissions page is a separate, product-shaped follow-up
(roadmap). `MeshServiceForegroundReclaimTest` pins it: a demoted instance (`stopForeground` from the
test, the app-side shape of the system's silent demotion) holds the state again after a plain start, and
a refused re-claim stops the service yet still stops the mesh in `onDestroy`. Device-verified 2026-09-14 on
the Pixel 9 (Android 17): the unfixed build crashed at exactly +30 s with the Play message, the fixed build
re-claimed within 3 s on the same pid. Repro: `cmd appops set app.getknit.knit RUN_ANY_IN_BACKGROUND ignore`,
open Knit, Home, reopen within 60 s (`allow` restores the setting).
