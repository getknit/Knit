---
id: "2026-09.m9h8"
slug: material-you-is-opt-in
title: "Material You is opt-in, and green stays green"
date: 2026-09-08
topics: [ui, theme, settings]
---

# ADR 2026-09.m9h8 — Material You is opt-in, and green stays green

Status: Accepted (2026-09-08; `KnitTheme`, `KnitSemanticColors`, `ThemePreferences`, `DynamicColor`,
`SettingsStore.dynamicColor`, `ColorSchemeTest`, `scripts/gen-color-scheme.py`)

Issue #12 asked Knit to respect Material You. `KnitTheme` had carried a `dynamicColor: Boolean = false`
parameter and a live `Build.VERSION_CODES.S` branch since the first commit, and no caller had ever passed
`true`. Turning it on was one line. The other three quarters of this change are the things that only
became visible once someone looked at what flipping it would do.

## The switch is opt-in

Default off, hidden entirely below API 31. The coral brand is what the eight fastlane store screenshots
and the launcher icon show, and turning every existing user's app a different colour on update is not
something an issue asking for an option should cause. *Rejected:* default-on, which answers the issue for
people who never find Settings but makes the app stop matching its own store listing; and always-on with
no switch, which discards the brand on nearly every device and offers no way back.

Hidden rather than greyed on API 29–30, matching `showInternetRelays`/`showLoraRadio`: a switch that can
never move needs a reason beside it, and the settings screen has nowhere to put one.

## `tertiary` was the wrong home for "good"

All fifteen `colorScheme.tertiary` reads in the app meant *live / verified / healthy / connected* — the
online dot, the verified shield, transport health, relay connected. Under Material You `tertiary` is
`system_accent3_*`, derived from the user's wallpaper, so it can land anywhere on the wheel. Three of
those reads sit in the same `when` as `error`: `DiagnosticsScreen`'s transport dot maps `Healthy` to one
and `Degraded` to the other, on the same 10dp circle. A wallpaper that brought those two hues together
would not merely look wrong, it would make the app report a seized radio as a working one.

So `KnitSemanticColors.positive` pins the green, provided by `KnitTheme` beside `LocalReduceMotion` and
keyed on `darkTheme` rather than on `dynamicColor`, so `onPositive` stays legible on it in all four
combinations. It reuses the constants `tertiary` already held (renamed `CoralTertiary*` → `Positive*`), so
that commit changed no pixels.

Pinning a fixed colour into a wallpaper-derived scheme is safe here for a specific reason: Material fixes
the **tone** of a dynamic surface — 98 light, 6 dark — and varies only its hue. A fixed mid-tone green
therefore keeps its contrast under every wallpaper; `ColorSchemeTest` asserts 3:1 against both surfaces.

**`error` needs no equivalent, and that is what makes one pinned colour enough.** The dynamic schemes
never set `error`, `onError`, `errorContainer` or `onErrorContainer` — the string `error` does not appear
in `DynamicTonalPalette.android.kt` at all — so "bad" inherits Material's wallpaper-independent red in
every scheme. *Rejected:* `Blend.harmonize`, Material's own answer for a custom colour inside a dynamic
scheme. It needs `material-color-utilities`, and Compose's HCT solver is `internal`; a dependency to tint
one dot would touch the locked dependency set and the F-Droid path for no legibility gain.

## Two dozen roles were already wrong, before any of this

`lightColorScheme()`/`darkColorScheme()` default every role the caller omits, and the defaults are
Material's **baseline** palette — purple-tinted neutrals. Knit set 24 of ~45, so `surfaceContainer` was
`#F3EDF7` (hue 276°) sitting under every `Card`, `AlertDialog`, `DropdownMenu`, `ModalBottomSheet` and
scrolled `TopAppBar`, against warm coral surfaces at hue 14°. Nothing in the build could notice: an
omitted role is valid Kotlin and renders fine, just in the wrong hue family. Enabling Material You would
have *fixed* those roles and left "off" looking worse than "on", so they are filled in the same change.

**The palette is not a generated tonal ramp, and this is the trap.** Measuring every existing constant in
HCT shows the families do not share one ramp: hue drifts 10–15° within a family, and `CoralPrimaryLight`
sits at tone 45 with far more chroma than its own containers — a fitted primary ramp misses it by 31/255
per channel. So there is no seed that reproduces `Color.kt`, and "regenerate the scheme from the seeds"
silently restyles the app. Two things make that avoidable:

1. **Most missing roles are aliases.** Material's own tone mapping puts all twelve `*Fixed` roles,
   `inversePrimary` and `surfaceTint` on tones the palette already carries — `primaryFixed` is primary
   tone 90, which is exactly `CoralPrimaryContainerLight`. Those 14 are re-used verbatim, so the
   incoherent primary family never has to be regenerated.
2. **Everything genuinely new comes from one family.** Material derives every neutral surface from
   *neutralVariant*, and that is the family Knit authored most consistently: one ramp at hue 41.3 /
   chroma 8.7 reproduces all of its existing members to within 3/255. Only 11 near-neutral tones are new.

`scripts/gen-color-scheme.py` holds the HCT maths (a port of material-color-utilities, self-tested against
Material's published baseline palette) and re-derives the existing constants on every run, failing if the
fit ever drifts past 6/255.

## The launch flash, and why there is no pre-draw gate

`MainActivity` composes `KnitTheme` immediately but DataStore emits asynchronously, so a plain
`collectAsStateWithLifecycle(initialValue = false)` paints coral for the first frames and then flips —
a launch-time colour pop, the same class of defect as issue #2. There is deliberately no `runBlocking`
anywhere in `app/src/main`, so `ThemePreferences` starts the read `Eagerly` from
`KnitApplication.onCreate` instead, giving it the whole cold-start window before `setContent`.

*Rejected:* holding the first frame with a `ViewTreeObserver.OnPreDrawListener` (what
`core-splashscreen`'s `setKeepOnScreenCondition` does; that dependency is itself ruled out for the F-Droid
path by ADR 047). This Activity already carries `watchForUndrawnWindow`/`WindowWedgePolicy` because of a
window that resumed, focused and never drew for 94 seconds (ADR 2026-09.un9n). Adding a deliberate "do not
draw yet" to that same Activity makes every future wedge report ambiguous, for one frame.

**This narrows the race, it does not close it.** If a slow device loses it the cost is one repaint;
`animateColorAsState` initialises at its target on first composition, so `ConnectionStatus`'s dot does not
crossfade through the change. If it shows up in the field, the pre-draw gate is the next step, not a
blocking read.

## Cost and residuals (accepted)

The API-31 gate lives in `ui/theme/DynamicColor.kt`, **not** folded into `SettingsStore.dynamicColor` the
way `linkPreviewsEnabled` folds `BuildConfig.INTERNET_PLANE`. This module sets
`unitTests.isReturnDefaultValues = true`, so `Build.VERSION.SDK_INT` reads 0 on the JVM and a folded gate
would pin the flow false and fail `SettingsStoreTest` for a reason nobody would find. A `BuildConfig`
constant is safe there; `Build.VERSION` is not. For the same reason `ColorSchemeTest` hand-rolls its
colour arithmetic instead of using `androidx.core.graphics.ColorUtils`, which would read every channel
back as 0 and pass on garbage.

`ColorSchemeTest` guards the scheme from both directions, because they catch opposite mistakes: a
**missing** role, by reading `Theme.kt` and diffing its named arguments against the set the dynamic scheme
fills; and a **wrong** one, by hue, since a baseline fallthrough lands near 270°. Removing a single role
fails both. Its `DYNAMIC_ROLES` list is pinned to material3 1.4.0 — re-check it when the Compose BOM moves.

Deliberately untouched: the launch window stays brand-coloured, because `Theme.Knit` resolves
`@color/splash_background` before the process starts and so cannot know a per-app preference; the mismatch
is hue-only at fixed lightness (ΔE2000 3–7, under 0.4 L\*) against issue #2's 90 L\* white-to-black step,
and `@color/splash_background` doubles as the wedge screen. A `values-v34` override would make the splash
dynamic for the majority who left the switch *off*, and could only ever be half a fix, since API 31–33
derives `background` through a CAM16 `setLuminance` no XML can express — and `values-v34` without
`values-night-v34` silently reverts to brand on dark API-34 devices, because the night qualifier is
evaluated before the version one. Notification colours stay pinned to the light `secondaryContainer` pair
(the shade is not inside `KnitTheme`), and the themed launcher icon already shipped.

**Still owed:** the device trial — two API levels, since 31–33 and 34+ derive `background` through
different code; a wallpaper deliberately set to red or orange, to confirm the Healthy and Degraded dots
stay distinguishable, which is the one check the ATF suite cannot make (it disables screenshots off real
hardware, so contrast reports `NOT_RUN`); and a cold launch with the switch on, watching for the repaint
`ThemePreferences` is there to avoid.

No in-app light/dark override, per ADR 047's reasoning that the user has already told the system once.
`UiModeManager.setApplicationNightMode` would serve on API 31+, but minSdk is 29 and those two releases
have no per-app night mode at all, so an override there re-creates issue #2 exactly.
