package app.getknit.knit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Meanings the app owns, which no colour scheme is allowed to reassign.
 *
 * `tertiary` was the wrong home for "good". Under Material You it is `system_accent3_*`, derived from the
 * user's wallpaper, so it can land anywhere on the wheel — including a red a few degrees from `error`.
 * Every one of this app's fifteen `tertiary` reads meant *live / verified / healthy / connected*, and
 * three of them sit in the same `when` as `error`: `DiagnosticsScreen`'s transport dot maps `Healthy` to
 * one and `Degraded` to the other, on the same 10dp circle. A wallpaper that collapsed those two hues
 * would not merely look wrong, it would make the app report a seized radio as a working one.
 *
 * So the green is pinned here instead, and pinning it is safe: Material fixes the *tone* of a dynamic
 * surface (98 light, 6 dark) and varies only its hue, so a fixed mid-tone green keeps its contrast under
 * every wallpaper. `ColorSchemeTest` asserts that against both surfaces.
 *
 * `error` needs no equivalent. The dynamic schemes never set `error`, `onError`, `errorContainer` or
 * `onErrorContainer` — the string does not appear in `DynamicTonalPalette.android.kt` at all — so "bad"
 * inherits Material's wallpaper-independent red in every scheme. That fixed red is exactly what makes one
 * pinned green sufficient.
 */
@Immutable
data class KnitSemanticColors(
    /** Live, verified, healthy, reachable. Green in every scheme, dynamic or not. */
    val positive: Color,
    /** Content drawn on top of [positive]. */
    val onPositive: Color,
)

internal val LightSemanticColors = KnitSemanticColors(positive = PositiveLight, onPositive = OnPositiveLight)
internal val DarkSemanticColors = KnitSemanticColors(positive = PositiveDark, onPositive = OnPositiveDark)

/**
 * `static` for the same reason as [LocalReduceMotion]: it changes only at a theme flip, which already
 * recomposes everything, so there is nothing to gain from tracking reads.
 */
val LocalKnitColors = staticCompositionLocalOf { LightSemanticColors }

/** Reads beside `MaterialTheme.colorScheme.x`, so a call site changes by one word. */
val MaterialTheme.knitColors: KnitSemanticColors
    @Composable @ReadOnlyComposable
    get() = LocalKnitColors.current
