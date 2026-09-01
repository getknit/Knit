package app.getknit.knit.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import app.getknit.knit.ui.theme.LocalReduceMotion

/**
 * The pulse driving a screen's loading placeholders — a slow alpha sweep that says "this is filling in"
 * rather than "this is empty". Hoisted to the *list* rather than per row so one transition drives every
 * placeholder on screen and they breathe together.
 *
 * Infinite, which the rest of the app's motion deliberately is not (see [app.getknit.knit.ui.theme.KnitMotion]
 * and ADR 047): a skeleton is only ever composed transiently, until the first real emission replaces it, so it
 * never leaves a settled screen unable to go idle. Under **Remove animations** it collapses to a still tint,
 * which also composes no transition at all.
 */
@Composable
fun skeletonPulseAlpha(label: String): Float = skeletonPulseAlphaState(label).value

/**
 * The same pulse as a [State], for a placeholder that reads it **in its draw lambda** rather than in
 * composition: a skeleton with many blocks then re-draws once per frame without recomposing or re-laying
 * out a node per block (the emoji picker's grid placeholder, ~50 blocks, went from 49 recompositions per
 * animation frame to none this way).
 */
@Composable
fun skeletonPulseAlphaState(label: String): State<Float> {
    if (LocalReduceMotion.current) return remember { mutableFloatStateOf(SKELETON_STILL_ALPHA) }
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = SKELETON_MIN_ALPHA,
        targetValue = SKELETON_MAX_ALPHA,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = SKELETON_PULSE_MILLIS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "${label}Alpha",
    )
}

/**
 * The colour a placeholder block draws at for a [skeletonPulseAlpha] value. Tinted from the theme so it reads
 * correctly in light and dark, and the pulse rides on top of a low base opacity so the shapes never fully
 * disappear.
 */
@Composable
fun skeletonBlockColor(pulseAlpha: Float): Color = skeletonBlockColor(MaterialTheme.colorScheme.onSurface, pulseAlpha)

/** Non-composable twin of [skeletonBlockColor] for a draw lambda: pass the theme's `onSurface` from composition. */
fun skeletonBlockColor(
    onSurface: Color,
    pulseAlpha: Float,
): Color = onSurface.copy(alpha = SKELETON_PULSE_RANGE * pulseAlpha + SKELETON_BASE_ALPHA)

private const val SKELETON_MIN_ALPHA = 0.3f
private const val SKELETON_MAX_ALPHA = 0.9f

// Where the pulse rests when the user has asked for no animations: mid-sweep, so the placeholders read the
// same weight they average out to when they are moving.
private const val SKELETON_STILL_ALPHA = 0.6f
private const val SKELETON_PULSE_MILLIS = 700
private const val SKELETON_PULSE_RANGE = 0.12f
private const val SKELETON_BASE_ALPHA = 0.04f
