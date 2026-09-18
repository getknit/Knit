package app.getknit.knit.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import app.getknit.knit.ui.camera.openAppSettings

/**
 * Android's per-app "Pause app activity if unused" switch ("Remove permissions if app isn't used" on
 * Android 11), as the mesh experiences it. On by default: after a few months without use the platform
 * takes the app's runtime grants back and, from Android 12, force-stops it and clears its cache too.
 * Any component invocation counts as use, so a phone whose `MeshService` is up never gets there; the
 * phone that does is one whose user pressed Stop and left Knit alone for a season, and its next launch
 * lands on the onboarding permissions page with the radio grants gone (ADR 2026-09.nzpr). This switch is
 * the one thing that prevents that, and — like Restricted battery use (ADR 2026-09.gc3m) — no prompt of
 * ours can flip it: the user has to, on the app-info page.
 */
enum class UnusedAppPause {
    /** The switch is off: the grants stay held however long Knit sits unused. */
    Off,

    /** The default: the platform may take the grants back after months without use. */
    On,
}

/**
 * Which way the switch is set, or `null` where the platform has no such switch. Android 10 has none in
 * the OS; Play services can back-port one there, but reading it means binding a service the phone may not
 * have (F-Droid installs), so that case is left as "nothing to show" rather than a probe that can hang.
 */
fun unusedAppPause(context: Context): UnusedAppPause? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return if (context.packageManager.isAutoRevokeWhitelisted) UnusedAppPause.Off else UnusedAppPause.On
}

/**
 * Opens the page that holds the switch. Android 11 has a page of its own for it; from 12 the switch sits
 * on the app-info page, which is what `IntentCompat.createManageUnusedAppRestrictionsIntent` resolves to
 * as well.
 */
fun openUnusedAppPauseSettings(context: Context) {
    if (Build.VERSION.SDK_INT != Build.VERSION_CODES.R) {
        openAppSettings(context)
        return
    }
    val intent =
        Intent(
            Intent.ACTION_AUTO_REVOKE_PERMISSIONS,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
