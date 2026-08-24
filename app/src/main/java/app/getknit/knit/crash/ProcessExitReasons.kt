package app.getknit.knit.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * What the platform recorded about the death of the process before this one.
 *
 * @param at when that process died (epoch millis), so a caller can tell whether the record actually
 *   belongs to the attempt it is asking about, rather than to some older, unrelated exit
 * @param nativeFault the process took a fault signal — a crash inside native code
 * @param explained the platform gave a reason that has an ordinary cause: a Java crash, an ANR, the
 *   low-memory killer, a force-stop, a package update. Not our fault and not evidence against anyone.
 *   `false` here means "the platform did not tell us anything useful", **not** "nothing was wrong".
 */
data class ProcessExitEvidence(
    val at: Long,
    val nativeFault: Boolean,
    val explained: Boolean,
)

/**
 * Reads `ActivityManager.getHistoricalProcessExitReasons` for the one thing [CrashHandler] structurally
 * cannot see: a **native** crash. ADR 028 named this API as the natural next step for exactly that gap;
 * `moderation/ModelLoadGuard` (ADR 037) is its first consumer.
 *
 * Only the single newest record is read. Knit is single-process — no `android:process` anywhere in either
 * manifest — so that record always describes the process immediately before this one, which is why there
 * is no "have I already counted this exit?" bookkeeping.
 *
 * **`reason` alone is not enough; `status` decides the signalled case.** `WifiAwareTransport` kills its
 * own process on a NAN wedge, and that surfaces as `REASON_SIGNALED` with status 9 (`SIGKILL`) —
 * indistinguishable by reason from a genuine `SIGSEGV`, which is status 11. Reading the status separates
 * them, and it is also the hedge that matters most here: if a ROM's debuggerd never files the tombstone
 * that produces `REASON_CRASH_NATIVE`, the fault-signal arm still catches the crash. Issue getknit/knit#9
 * is a LineageOS build, so "the ROM does it differently" is the premise, not an edge case.
 *
 * API 30+; on API 29 (our minSdk) and on any unexpected failure this returns `null` and the caller falls
 * back to whatever it decides without corroboration.
 */
class ProcessExitReasons(
    private val context: Context,
) {
    fun lastExit(): ProcessExitEvidence? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) newestExit() else null

    @RequiresApi(Build.VERSION_CODES.R)
    private fun newestExit(): ProcessExitEvidence? =
        runCatching {
            context
                .getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                ?.firstOrNull()
                ?.let { info ->
                    ProcessExitEvidence(
                        at = info.timestamp,
                        nativeFault = isNativeFault(info.reason, info.status),
                        explained = isExplained(info.reason, info.status),
                    )
                }
        }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isNativeFault(
        reason: Int,
        status: Int,
    ): Boolean =
        reason == ApplicationExitInfo.REASON_CRASH_NATIVE || (reason == ApplicationExitInfo.REASON_SIGNALED && status in FAULT_SIGNALS)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isExplained(
        reason: Int,
        status: Int,
    ): Boolean =
        when (reason) {
            // A Java crash or an ANR is captured elsewhere and has nothing to do with a native load;
            // the rest are the system reclaiming or the user acting.
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_EXIT_SELF,
            -> true

            // Our own NAN-wedge self-kill, and every other deliberate SIGKILL.
            ApplicationExitInfo.REASON_SIGNALED -> status == SIGKILL

            else -> false
        }

    private companion object {
        /** SIGILL, SIGABRT, SIGBUS, SIGSEGV — the signals a fault in native code arrives as. */
        val FAULT_SIGNALS = setOf(4, 6, 7, 11)

        const val SIGKILL = 9
    }
}
