package app.getknit.knit.ui.theme

import android.os.Build

/**
 * Whether the platform can derive a colour scheme from the wallpaper at all. Material You landed in
 * API 31, and this app's minSdk is 29, so two supported releases have no such thing.
 *
 * Split into a pure function plus one impure `val` for the same reason `Motion.kt` splits
 * `reduceMotionFor`: the decision is testable on the JVM, where `Build.VERSION.SDK_INT` reads 0 because
 * this module sets `unitTests.isReturnDefaultValues = true`.
 */
internal fun dynamicColorAvailable(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

/** The running device's answer, for the defaulted parameter that hides the setting on 29 and 30. */
val DYNAMIC_COLOR_SUPPORTED: Boolean = dynamicColorAvailable(Build.VERSION.SDK_INT)
