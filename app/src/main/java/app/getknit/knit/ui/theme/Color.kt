package app.getknit.knit.ui.theme

import androidx.compose.ui.graphics.Color

// Knit brand palette — a warm coral, ported from the legacy app (colorPrimary #E67474,
// colorPrimaryDark #ED4854, accent slate #454551, success green #08AD6C) and expanded into a
// Material 3 scheme. Seeds: primary #E55E4C (warm coral), secondary #454551 (slate),
// tertiary #08AD6C (green — used for the "online" indicator).

// Light
val CoralPrimaryLight = Color(0xFFBD4030)
val CoralOnPrimaryLight = Color(0xFFFFFFFF)
val CoralPrimaryContainerLight = Color(0xFFFFDBD1)
val CoralOnPrimaryContainerLight = Color(0xFF3B0A00)
val CoralSecondaryLight = Color(0xFF5B5B67)
val CoralOnSecondaryLight = Color(0xFFFFFFFF)
val CoralSecondaryContainerLight = Color(0xFFE0E0EC)
val CoralOnSecondaryContainerLight = Color(0xFF181824)
val CoralTertiaryLight = Color(0xFF006D43)
val CoralOnTertiaryLight = Color(0xFFFFFFFF)
val CoralTertiaryContainerLight = Color(0xFF8FF7BD)
val CoralOnTertiaryContainerLight = Color(0xFF00210F)
val BackgroundLight = Color(0xFFFFF8F6)
val OnBackgroundLight = Color(0xFF271814)
val SurfaceLight = Color(0xFFFFF8F6)
val OnSurfaceLight = Color(0xFF271814)
val SurfaceVariantLight = Color(0xFFF5DDD6)
val OnSurfaceVariantLight = Color(0xFF53433D)
val OutlineLight = Color(0xFF857369)
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)

// Dark
val CoralPrimaryDark = Color(0xFFFFB5A0)
val CoralOnPrimaryDark = Color(0xFF5F1500)
val CoralPrimaryContainerDark = Color(0xFF7E2D17)
val CoralOnPrimaryContainerDark = Color(0xFFFFDBD1)
val CoralSecondaryDark = Color(0xFFC5C4D2)
val CoralOnSecondaryDark = Color(0xFF2D2E39)
val CoralSecondaryContainerDark = Color(0xFF444450)
val CoralOnSecondaryContainerDark = Color(0xFFE0E0EC)
val CoralTertiaryDark = Color(0xFF72DAA0)
val CoralOnTertiaryDark = Color(0xFF00391F)
val CoralTertiaryContainerDark = Color(0xFF005230)
val CoralOnTertiaryContainerDark = Color(0xFF8FF7BD)
val BackgroundDark = Color(0xFF1A110E)
val OnBackgroundDark = Color(0xFFF1DFD9)
val SurfaceDark = Color(0xFF1A110E)
val OnSurfaceDark = Color(0xFFF1DFD9)
val SurfaceVariantDark = Color(0xFF53433D)
val OnSurfaceVariantDark = Color(0xFFD8C2BA)
val OutlineDark = Color(0xFFA08D85)
val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)

// Neutral-variant ramp — hue 41.3 / chroma 8.7, the ramp Knit's own surfaceVariant/outline/background
// constants above already sit on (to within 3/255). Named by tone rather than by role because several
// roles share one tone: light `inverseSurface` and dark `inverseOnSurface` are both tone 20. Theme.kt
// maps role → tone, following Material's own assignment (material3 1.4.0, dynamic*ColorScheme31).
//
// These exist because `lightColorScheme()`/`darkColorScheme()` default every unset role to Material's
// BASELINE palette, which is tinted purple — `surfaceContainer` was #F3EDF7 (hue 276°) sitting next to
// these warm coral surfaces (hue ~14°), under every Card, AlertDialog, DropdownMenu, ModalBottomSheet
// and scrolled TopAppBar in the app. Regenerate with `python3 scripts/gen-color-scheme.py`.
val NeutralVariant4 = Color(0xFF170B07)
val NeutralVariant12 = Color(0xFF2A1D17)
val NeutralVariant17 = Color(0xFF352721)
val NeutralVariant20 = Color(0xFF3C2D27)
val NeutralVariant22 = Color(0xFF40312C)
val NeutralVariant24 = Color(0xFF453630)
val NeutralVariant87 = Color(0xFFEDD5CC)
val NeutralVariant92 = Color(0xFFFCE3DA)
val NeutralVariant94 = Color(0xFFFFE9E2)
val NeutralVariant95 = Color(0xFFFFEDE7)
val NeutralVariant96 = Color(0xFFFFF1EC)
