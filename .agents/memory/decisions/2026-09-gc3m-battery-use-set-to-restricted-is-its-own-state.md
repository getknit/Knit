---
id: "2026-09.gc3m"
slug: battery-use-set-to-restricted-is-its-own-state
title: "Battery use set to Restricted is its own state, shown where the exemption is"
date: 2026-09-14
topics: [ui, onboarding, settings, reliability]
---

# ADR 2026-09.gc3m — Battery use set to Restricted is its own state, shown where the exemption is

Status: Accepted (2026-09-14)

**What was observed.** Android's per-app battery setting is a radio group of three — Unrestricted, Optimized,
Restricted — and the app read only one of its edges. `ui/Battery.kt` (now `BackgroundBattery.kt`) asked
`PowerManager.isIgnoringBatteryOptimizations` and folded the answer into a boolean, so Settings' status line
said "restricted" for the *default* position and had nothing at all to say about the real one, and the
onboarding battery row offered the exemption prompt to a phone that prompt cannot help. The real one is the
position ADR 2026-09.f69x's crash lived in: Restricted makes the platform demote the mesh service the moment
Knit leaves the screen and stop it a minute later, so the mesh runs only while the app is open. That fix
stopped the crash; it left the user with a mesh that quietly dies every time they switch apps and no line in
the app that says why. `ActivityManager.isBackgroundRestricted()` (API 28, under our minSdk) reads it
directly.

**What changed.** `BackgroundBattery` (`ui/BackgroundBattery.kt`) is the three positions as one enum, resolved
by `backgroundBattery(context)` with Restricted first: the exemption can still read true underneath a
Restricted setting (they are two switches, and Settings only keeps them consistent from its own UI), and
Restricted is the one that decides whether the mesh runs off screen at all. Both surfaces that showed the
exemption now show the position. Settings' `BatteryOptimizationRow` says allowed / optimized / restricted in
the user's words ("the mesh stops as soon as you leave Knit"), keeps the exemption button for Optimized, and
swaps it for "Open settings" on Restricted. The onboarding row does the same through its existing "Open
settings" state, with its own hint in place of Android's "won't ask again" — `PermissionRow` grew a
`settingsHint` parameter for exactly that, so the permission rows keep theirs. Both re-read on resume, as they
already did for the exemption.

**The alternative.** Sending Restricted to the exemption prompt anyway.
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` adds the app to the Doze allowlist; it does not clear
`OP_RUN_ANY_IN_BACKGROUND`, which is what Restricted is, so the user would tap Allow, see a check, and still
lose the mesh on the next app switch. There is no public intent for the "App battery usage" page itself, so
"Open settings" is the app-info page (`openAppSettings`), one tap short, and the hint names the row to look
for.

**What it costs and does not cover.** One `ActivityManager` binder read beside the `PowerManager` one, on the
same resumes. Nothing here changes what a Restricted install *gets* — that is the platform's policy, and the
copy says so rather than promising a workaround. Neither the chat list's status line nor Diagnostics carries
the state; the two places a user goes to ask "why did it stop" are Settings and the permissions page, and the
line lives there. `BackgroundBatteryTest` pins the fold (Restricted over a stale exemption),
`SettingsScreenContentTest` the three rows, `OnboardingScreenContentTest` that a Restricted row offers
settings with its own hint and never the prompt, and that Start still does not care.
