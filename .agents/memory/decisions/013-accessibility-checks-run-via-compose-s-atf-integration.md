---
id: "013"
slug: accessibility-checks-run-via-compose-s-atf-integration
title: "Accessibility checks run via Compose's ATF integration, not Espresso"
date: 2026-07-09
topics: [testing, a11y]
---

# ADR 013 — Accessibility checks run via Compose's ATF integration, not Espresso

Status: Accepted (2026-07-09, branch `build/accessability-test-framework`)

The Play Console pre-launch report runs Google's Accessibility Test Framework (ATF). We run the *same*
framework locally so a11y regressions (missing labels, sub-48dp targets, low contrast, bad traversal) fail
before upload. Knit is Compose-only and uses **no** Espresso view actions, so ATF's classic
`AccessibilityChecks.enable()` (an Espresso `ViewAction` hook) has nothing to fire on — the right seam is
Compose's own `androidx.compose.ui:ui-test-junit4-accessibility` (`compose.enableAccessibilityChecks(...)` +
`onRoot().tryPerformAccessibilityChecks()`), which pulls ATF transitively. The suite
(`app/src/androidTest/…/a11y/`, `AccessibilityInstrumentedTest`) reuses the seeded `SeededUiTest` harness to
deep-link and audit each screen. **Gated to API 34+**: the Compose ATF API is `@RequiresApi(34)`, so tests
carry `@SdkSuppress(minSdkVersion = 34)` (skip, don't fail, on older devices; also the lint `NewApi` guard —
`@RequiresApi` in a test is rejected by lint's `UseSdkSuppress`) and run on a new `pixel8api34` managed
emulator / FTL API-34+ device. **Gate policy**: errors fail the test, warnings/info are logged (validator
`setThrowExceptionFor(ERROR)` + `addCheckListener`). Detail: `context/testing.md`.
