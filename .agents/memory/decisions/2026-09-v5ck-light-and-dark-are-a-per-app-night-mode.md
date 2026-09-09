---
id: "2026-09.v5ck"
slug: light-and-dark-are-a-per-app-night-mode
title: "Light and dark are a per-app night mode"
date: 2026-09-09
topics: [ui, theme, settings]
---

# ADR 2026-09.v5ck — Light and dark are a per-app night mode

Status: Accepted (2026-09-09; `ThemeMode`, `NightMode`/`AndroidNightMode`, `ThemePreferences`,
`SettingsStore.themeMode`, `SettingsScreen`'s theme row). Supersedes the closing paragraph of
ADR 2026-09.m9h8.

Knit had no in-app light/dark control. `KnitTheme`'s `darkTheme` parameter has defaulted to
`isSystemInDarkTheme()` since the first commit and no caller ever passed it — `Theme.kt` holds the app's
only call to that function. That was a decision, not an omission: ADR 047 ruled reduce-motion out of
Settings because "the user has already told the system once, for every app, and a second switch in
Settings would be a second answer to the same question", ADR 2026-09.m9h8 applied the same rule to
light/dark when Material You shipped, and `roadmap.md` carried it under *Still deferred (by design)*.

**What changed is that the second answer is no longer a second answer.** Android has had a per-app night
mode since API 31, exposed to Settings' own app list; an app that sets it is not disagreeing with the
system, it is filling in the per-app slot the system already keeps for it. ADR 047's rule survives intact
for reduce-motion, which has no such slot.

## The objection was the launch window, and it is the reason for the mechanism

m9h8 recorded the real obstacle: `Theme.Knit` resolves `@color/splash_background` — `#FFF8F6` in
`values/`, `#1A110E` in `values-night/` — **before the process starts**. An override the app applies in
Compose therefore cannot reach it, so pinning Light on a dark phone would flash full-screen dark on every
cold launch. That is issue #2 verbatim, a 90 L\* step, and it is not a defect worth trading a preference
for.

So the choice is applied to the app's **configuration**, not to `KnitTheme`:
`UiModeManager.setApplicationNightMode`, which commits through
`ActivityTaskManagerInternal.PackageConfigurationUpdater` and is persisted by the system per package until
the app changes it, the user clears app data, or the app is uninstalled. Everything downstream then
follows without being told:

| Surface | Why it is already right |
| --- | --- |
| `KnitTheme`, `LocalKnitColors`, the overscroll glow | `isSystemInDarkTheme()` reads the overridden configuration |
| Launch window and the API 31 splash | the system resolves `values-night` under the persisted override, before the process exists |
| Status- and navigation-bar icon polarity | `enableEdgeToEdge()`'s default `SystemBarStyle.auto` reads `configuration.uiMode` |

`Theme.kt`, `MainActivity.kt`, `themes.xml` and both `colors.xml` files are untouched by this change,
which is also what keeps `ColorSchemeTest`'s and `SplashThemeTest`'s source- and resource-parsing guards
meaningful. There is likewise no launch race to narrow: unlike `dynamicColor`, which `ThemePreferences`
warms `Eagerly` because the first composition reads it, the night mode was applied by the system before
`onCreate` ran.

**`MODE_NIGHT_AUTO` is the escape hatch, and it is not obvious from its name.** The platform documents
four modes and none of them says "follow the system"; `AUTO` reads like the location-and-sensor mode it
means on the system-wide `setNightMode`. `UiModeManagerService.setApplicationNightMode` maps both `AUTO`
and `CUSTOM` to `Configuration.UI_MODE_NIGHT_UNDEFINED`, and an undefined per-app override *is* the app
following the system. Without that the feature would be a trap — a mode a user could set and never leave
— so `ThemeModeTest.eachModeMapsToItsPlatformConstant` pins the mapping.

## API 29 and 30 have no control at all

They have no per-app night mode and no other route to the launch window, so the row is hidden there,
exactly as m9h8 hides the Material You switch: "a switch that can never move needs a reason beside it,
and the settings screen has nowhere to put one." `THEME_MODE_SUPPORTED` is deliberately a separate
constant from `DYNAMIC_COLOR_SUPPORTED` even though both read 31 — they are two unrelated platform facts
that happen to share a number, and merging them would make either one impossible to move later. Both are
plain-function-plus-`val` for the reason `DynamicColor.kt` gives: `isReturnDefaultValues` makes
`Build.VERSION.SDK_INT` read 0 on the JVM.

*Rejected:* shipping the Compose-only override on 29–30 anyway. It would work for everything except the
one frame that matters, on the two releases the lab can no longer test — every device in it is API 34 or
later.

*Rejected:* `AppCompatDelegate.setDefaultNightMode`, the answer most references give. A bare
`ComponentActivity` has no delegate, AppCompat is not a direct dependency, and it cannot reach the launch
window either — it recreates the Activity *after* the system has already painted it.

*Rejected:* `values-v34`-style splash overrides, for the reasons m9h8 already worked out: half a fix at
best, and the night qualifier is evaluated before the version one.

## Cost, and the trap

**Changing the setting recreates the Activity.** A per-app night mode is a configuration change and
`MainActivity` declares no `configChanges`, so the app restarts its window — the same recreate Android's
own dark-theme switch already causes. `uiMode` was deliberately not added to `configChanges`: it would
change behaviour for the system switch too, to smooth a control that gets touched about once. It is also
why the control is segmented buttons rather than the dialog its neighbouring rows would suggest — a
dialog would be torn down as you chose from it — and why the collector must not re-apply an unchanged
mode, since every apply is a potential recreate. `ThemePreferencesTest` pins the de-duplication.

**`ThemeModeRow` fights Material's own content layout twice, for the same reason both times:
`SegmentedButtonContentMeasurePolicy` centres the check and the label as one pair.** First, it passes
`SegmentedButtonDefaults.Icon` a same-size `inactiveContent` rather than the default null — null makes an
unselected icon slot measure zero wide, and the policy then animates the label half an icon-width left to
re-centre the pair, so the word slides sideways under your finger as the check arrives. Second, the label
carries `end` padding of one check slot (`IconSize` + the 8dp `IconSpacing` Material keeps private), which
balances the check on the far side of the word and puts the word's own midpoint on the pair's midpoint —
without it the labels sit visibly right of centre, worst on the two buttons showing no check. Padding
rather than a negative offset on the icon: the space is really measured, so nothing can be pushed outside
the `Surface` and clipped on a narrow screen, where the slack is only `ContentPadding`'s 12dp. The button's
own width is unaffected throughout, since the policy already floors the icon slot at `IconSize`.

**Two copies of the state, and DataStore is the source of truth.** The platform has no getter for the
per-app mode, so `SettingsStore.themeMode` keeps its own copy — that is what the selector reads, and it
is what makes "System" distinguishable from "Dark on a dark phone". `ThemePreferences` collects it once
per process and re-applies it, which costs nothing when the two agree and heals them when they do not,
such as after a restore that carried the preferences but not the system-side override. The stored value
is the enum's `name`, and `ThemeMode.of` is total, so an unrecognised or renamed constant reads back as
`System` rather than throwing; `SettingsStoreTest` writes the raw key to prove it, which also pins the
key's name.

**Still owed:** the device trial — pin Dark on a light phone, force-stop, and watch a cold launch for the
dark launch window that is the entire point; then pin Light on a dark phone; then set System back and
flip Android's own switch to confirm the override really cleared. The ATF suite has not been run against
the segmented control either: Material's segmented container is 40dp, under the 48dp touch target, so
`ThemeModeRow` sets the height explicitly and that is the assertion the audit would make.
