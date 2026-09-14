---
id: "2026-09.nzpr"
slug: onboarding-gates-on-the-radio-permissions-only
title: "Onboarding gates on the radio permissions only"
date: 2026-09-13
topics: [ui, onboarding, permissions]
---

# ADR 2026-09.nzpr — Onboarding gates on the radio permissions only

Status: Accepted (2026-09-13)

**What was observed.** The first run was one page — a headline, a four-line paragraph and three stacked
buttons — and "onboarded" was defined as `hasAllMeshPermissions()`, a set that on Android 13+ held five
grants including `POST_NOTIFICATIONS`. That definition made the page a trap. Decline any one grant, the
notification one included, and "Start meshing" stayed disabled; decline twice and Android stops showing
the dialog, so "Grant permissions" became a silent no-op with no way to Settings. The screen's own KDoc
said the mesh "degrades" on a denial; the button disagreed. Nothing on the page said Bluetooth, or why
Android 10–12 asks for *location* to find phones, and nobody was asked their name — a fresh phone met the
mesh as `SmartlyBrightSparrow` with Profile three taps deep under Settings. The battery button never
reflected the exemption it had just obtained (Settings had a stateful row; this page did not), and the
column did not scroll, so the third button fell below the fold at a large font scale.

**What changed.** Onboarding is three pages behind one fixed footer — what Knit is, what to call you, and
what it needs — and the gate is `ui/Permissions.kt`'s `requiredRadioPermissions` alone, at both call sites
(`KnitApp` and `BootReceiver`). The radio set is what the transports assume: `BluetoothMeshTransport` and
`WifiAwareTransport` carry `@SuppressLint("MissingPermission")`, a lint suppression and not a runtime
guard, so the mesh must not start without every one of those grants — that part of the old gate was
right. `POST_NOTIFICATIONS` is not in it. `MessageNotifier` already checks the grant before every post
and skips silently without it, and the foreground service's own notification goes through
`startForeground`, which needs no grant; a user who declines it meshes exactly as well and hears about
messages when they open the app. It is asked for on the permissions page as an optional row beside the
battery exemption, under an "Optional" header, and neither ever touches Start.

Each row reads its own state — a check once held, "Allow" while the dialog can still be shown, "Open
settings" once it can't — and the probes are re-read on every resume, because the permission dialog, the
battery dialog and app-info Settings are all separate activities. The "can't" is the pure `needsSettings`
rule in `OnboardingPermissions.kt`: Android reports `shouldShowRequestPermissionRationale == false` both
before the first ask and after "don't ask again", so an `asked` flag (`rememberSaveable`) is what tells
the two apart, and one un-askable grant in the set is enough, since the radio request is a single dialog
sequence. The obvious alternative — treat the whole radio set as degradable and let the composite run
whichever plane got its grant — was not taken: it would need the transports' every `android.bluetooth.*`
and `android.net.wifi.aware.*` call wrapped against `SecurityException`, and a half-granted set is a
one-tap fix, not a state worth shipping around.

The name page holds its text locally (the `ProfileViewModel` rule; the field itself is now the shared
`ui/components/DisplayNameField`, under a live `Avatar` of the initial peers will see, keyed on the node
id so the preview draws exactly what the Profile screen and every peer draw) and writes it once, on
leaving the page, through `setDisplayName` — never `setProfile`, which would add a pointless status write. Neither bumps `profileVersion`, and that is
fine: `MeshManager.watchProfileChanges` only runs inside a session and the first session's own-profile
frame is built from whatever is stored. Leaving the page also writes `SettingsStore.onboardingSeen`, the
one persisted fact this adds. It is not a gate — whether onboarding shows at all is still the radio
grants — it only decides which page a returning phone opens on. That case is real: Android 11+ revokes
an unused app's runtime grants after a few months, and a manual revoke kills the process; either way
the next launch lands on onboarding, and without the flag it read "Welcome to Knit" and asked for a
name the phone already had. Written when the name page is left rather than on Start, because Start
pops the route with `popUpTo(inclusive = true)`, which clears the ViewModel and can cancel a write
launched on that tap; the write is `NonCancellable` for the same reason. Not inferred from
`displayName`, because the seeded demo build writes one and its store screenshot has to open on the
welcome page.

**What it costs.** On Android 13+ a declined notification grant also keeps the "Knit is meshing"
foreground notification out of the shade; the service runs regardless, but the user has lost the one
glance that says so. And Settings has no notifications row, so a user who declined at onboarding has no
in-app path back except the system's app-info page — the gap this change opens, on the roadmap. The
trap for the next person: `hasRadioPermissions` is the gate *because* the transports are not
permission-safe; adding a grant to `requiredRadioPermissions` re-wedges the front door for everyone who
declines it, and moving one out means auditing every radio call that assumed it. `PermissionsTest` pins
the set (location-free on 33+, never `POST_NOTIFICATIONS` on any tier), `OnboardingScreenContentTest.
startNeedsOnlyTheRadios` pins that Start ignores the optional rows, and `BootReceiverTest`'s first case
holds only the radio set so a declined notification can never keep the mesh down after a reboot.
