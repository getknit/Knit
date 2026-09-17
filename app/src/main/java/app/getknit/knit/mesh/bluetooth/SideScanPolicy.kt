package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.power.PowerState

/**
 * When, and how hard, the BLE side channel listens ([BleSideChannel]'s extended scan). Pure, like
 * [ScanDemandPolicy], so the table is a JVM test.
 *
 * The honest cost of a connectionless channel is that hearing it means scanning: while a flagged peer is
 * nearby the phone runs a hardware-filtered scan continuously (LOW_POWER ≈ 10 % receiver duty, BALANCED
 * ≈ 25 %) instead of the presence scan's floor, which is off for minutes between windows. So the scan is
 * [Tier.Off] whenever nobody could be sending (no flagged peer sighted or linked, the channel dark), whenever
 * scanning would cost something it must not (an L2CAP connect in flight or the board dial holding the
 * arbiter — scanning starves connects — or a low battery off the charger), and drops to LOW_POWER under A2DP
 * contention. Never LOW_LATENCY.
 *
 * Restarts are rationed: Android allows an app five scan starts per 30 s and the presence scan shares that
 * budget, so a tier change waits out [MIN_RESTART_GAP_MS] (stopping is always immediate), and a scan older
 * than [PERIODIC_RESTART_MS] is restarted before the stack demotes it to opportunistic at 30 min.
 */
internal object SideScanPolicy {
    enum class Tier { Off, LowPower, Balanced }

    data class Inputs(
        /** [BleSideChannel.live] — this controller can advertise and scan extended pages. */
        val live: Boolean,
        /** [SideCapableTracker.anyCapable] — someone nearby could be sending pages. */
        val capableNearby: Boolean,
        /** An initiator L2CAP connect is in flight, or the Meshtastic board dial holds [BleConnectArbiter]. */
        val connectBusy: Boolean,
        /** [BluetoothAudioMonitor.contended] — A2DP audio is streaming on this controller. */
        val audioContended: Boolean,
        val power: PowerState,
    )

    fun decide(i: Inputs): Tier =
        when {
            !i.live || !i.capableNearby || i.connectBusy -> Tier.Off
            i.audioContended -> Tier.LowPower
            i.power.charging -> Tier.Balanced
            i.power.batteryLow -> Tier.Off
            i.power.interactive -> Tier.Balanced
            else -> Tier.LowPower
        }

    /** Minimum gap between two scan starts (the per-app budget is five per 30 s, shared with the presence scan). */
    const val MIN_RESTART_GAP_MS = 30_000L

    /** Restart a running scan this often, ahead of the stack's 30-minute opportunistic demotion. */
    const val PERIODIC_RESTART_MS = 25 * 60_000L

    fun mayRestart(
        lastStartAt: Long,
        now: Long,
    ): Boolean = now - lastStartAt >= MIN_RESTART_GAP_MS

    /** A scan started at [startedAt] is due its periodic restart. */
    fun dueRestart(
        startedAt: Long,
        now: Long,
    ): Boolean = now - startedAt >= PERIODIC_RESTART_MS
}
