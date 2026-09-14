package app.getknit.knit.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * The three positions of Android's per-app battery setting (App info → App battery usage), as the mesh
 * experiences them. They are one radio group in Settings, so exactly one holds at a time; the order here is
 * how the probes resolve it, and [Restricted] wins because it is the one that decides whether the mesh runs
 * at all off screen — [PowerManager.isIgnoringBatteryOptimizations] can still read true underneath it.
 */
enum class BackgroundBattery {
    /** Exempt from battery optimization: the mesh runs through Doze and app standby. */
    Unrestricted,

    /** The default: the mesh runs in the background, and Android may pause it while the screen is off. */
    Optimized,

    /**
     * Background use denied. The platform silently demotes the mesh's foreground service the moment Knit
     * leaves the screen and stops it a minute later (ADR 2026-09.f69x), so the mesh only runs while Knit is
     * open. Nothing in the app can change it — the exemption prompt below is a different switch — so the
     * user has to, under App battery usage on the app's info page (`openAppSettings`).
     */
    Restricted,
}

/** Which of the three positions the app is in right now. */
fun backgroundBattery(context: Context): BackgroundBattery =
    when {
        isBackgroundRestricted(context) -> BackgroundBattery.Restricted
        isIgnoringBatteryOptimizations(context) -> BackgroundBattery.Unrestricted
        else -> BackgroundBattery.Optimized
    }

/** Whether the app is exempt from battery optimization (needed for a reliable background mesh). */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java)
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/** Whether the user (or Android, on its own suggestion) has set the app's battery use to Restricted. */
fun isBackgroundRestricted(context: Context): Boolean = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

/** Opens the system prompt to exempt the app from battery optimization (no-op if already exempt). */
fun requestIgnoreBatteryOptimizations(context: Context) {
    if (isIgnoringBatteryOptimizations(context)) return
    val intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
