---
id: "2026-09.54xg"
slug: the-unused-app-switch-is-shown-where-the-battery-exemption-is
title: "The unused-app switch is shown where the battery exemption is"
date: 2026-09-17
topics: [ui, onboarding, settings, permissions]
---

# ADR 2026-09.54xg — The unused-app switch is shown where the battery exemption is

Status: Accepted (2026-09-17)

**What was observed.** Android 11+ ships "Pause app activity if unused" on by default ("Remove permissions
if app isn't used" on 11 itself): after a few months without use the platform takes an app's runtime
grants back and, from 12, force-stops it and clears its cache. The app already recovers from that — ADR
2026-09.nzpr made `hasRadioPermissions` the one gate at both call sites, so the next launch lands on the
onboarding permissions page (the page, not the welcome, by `onboardingSeen`), and `BootReceiver` never
starts a mesh without the grants. What it did not do was say anything *before* the revoke, or read the
switch at all: no `isAutoRevokeWhitelisted` probe anywhere. The exposure is narrower than it sounds. Any
component invocation counts as use, so a phone whose foreground service is up never goes unused; the phone
that does is one whose user pressed Stop (or whose service a Restricted battery setting kept killing) and
then left Knit alone for a season. Rare, but it is exactly the user who expected to be reachable when
they next needed it, and the only thing that prevents it is a switch on the app-info page.

**What changed.** `ui/UnusedAppPause.kt` is the switch as a two-value enum, `unusedAppPause(context)` reads
it from `PackageManager.isAutoRevokeWhitelisted` (API 30+; `null` below, so the row is not drawn), and
`openUnusedAppPauseSettings` opens the page that holds it — `ACTION_AUTO_REVOKE_PERMISSIONS` on Android 11,
which has a page of its own, the app-info page from 12, the same split `IntentCompat.
createManageUnusedAppRestrictionsIntent` makes. Both surfaces that show the battery exemption now show the
switch beside it, in the same shape (ADR 2026-09.gc3m): a `PermissionRow` under Optional on the onboarding
page, and a status line with "Open settings" in Settings. The row has no "Allow": there is no prompt for
this switch, so its only state short of the check is "Open settings", and the hint names the switch. That
state is the platform default rather than a fault, so `PermissionRow` grew `quietHint`, which keeps the
hint in the rationale's colour instead of the error red the battery row's Restricted hint earns. Both
re-read on resume. Start does not care, as it does not care about any optional row.

**The alternative.** `PackageManagerCompat.getUnusedAppRestrictionsStatus` is the recommended read: it also
covers Google's Android 6–10 back-port of the switch. It returns a `ListenableFuture` because that back-port
is a bound service in Play services' permission controller — which an F-Droid phone does not have, and
which a synchronous probe on resume must not wait on. Our minSdk is 29, so the back-port matters on exactly
one API level, on GMS phones only; the platform read is one synchronous binder call and exact from 30 up.
Android 10 gets no row.

**What it costs and does not cover.** One `PackageManager` read on the same resumes as the battery probes.
Nothing here changes what the platform does — the copy says what the switch does and where it is, not that
the app is safe from it — and nothing nags: the row sits under Optional with its default state drawn
quietly, so a fresh install sees one more line and no red. The `null` branch (Android 10) is a
`Build.VERSION` check the SDK-36 Robolectric runtime cannot take; `UnusedAppPauseTest` pins the two
readable states, `OnboardingScreenContentTest` that the row is absent when `null`, offers its own callback
(never the generic app-settings one) while On, shows the check when Off, and that Start ignores it;
`SettingsScreenContentTest` the same three for the status line. The trap: `openUnusedAppPauseSettings`
is the row's callback because Android 11's page is not the app-info page — wiring it to `openSettings`
would land an Android 11 user one screen short with no hint that says so.
