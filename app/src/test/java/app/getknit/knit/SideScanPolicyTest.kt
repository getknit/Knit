package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.SideScanPolicy
import app.getknit.knit.mesh.bluetooth.SideScanPolicy.Tier
import app.getknit.knit.mesh.power.PowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [SideScanPolicy] — the BLE side channel's scan-tier table and its restart rationing. */
class SideScanPolicyTest {
    private fun decide(
        live: Boolean = true,
        capable: Boolean = true,
        connectBusy: Boolean = false,
        audio: Boolean = false,
        power: PowerState = PowerState(interactive = false),
    ) = SideScanPolicy.decide(SideScanPolicy.Inputs(live, capable, connectBusy, audio, power))

    @Test
    fun offWhenNobodyCouldBeSending() {
        assertEquals(Tier.Off, decide(live = false))
        assertEquals(Tier.Off, decide(capable = false))
    }

    @Test
    fun offWhileAConnectIsInFlight() {
        // Scanning starves connects — the same rule the presence scan follows.
        assertEquals(Tier.Off, decide(connectBusy = true, power = PowerState(interactive = true, charging = true)))
    }

    @Test
    fun lowPowerScreenOffOnBattery() {
        assertEquals(Tier.LowPower, decide())
    }

    @Test
    fun balancedWhenInteractiveOrCharging() {
        assertEquals(Tier.Balanced, decide(power = PowerState(interactive = true)))
        assertEquals(Tier.Balanced, decide(power = PowerState(interactive = false, charging = true)))
    }

    @Test
    fun offOnALowBatteryUnlessCharging() {
        assertEquals(Tier.Off, decide(power = PowerState(interactive = true, batteryLow = true)))
        assertEquals(Tier.Balanced, decide(power = PowerState(interactive = true, charging = true, batteryLow = true)))
    }

    @Test
    fun a2dpContentionCapsAtLowPower() {
        assertEquals(Tier.LowPower, decide(audio = true, power = PowerState(interactive = true, charging = true)))
    }

    @Test
    fun aRestartWaitsOutTheSharedBudgetGap() {
        assertFalse(SideScanPolicy.mayRestart(lastStartAt = 1_000, now = 1_000 + SideScanPolicy.MIN_RESTART_GAP_MS - 1))
        assertTrue(SideScanPolicy.mayRestart(lastStartAt = 1_000, now = 1_000 + SideScanPolicy.MIN_RESTART_GAP_MS))
    }

    @Test
    fun aLongRunningScanIsDueARestartBeforeTheStackDemotesIt() {
        assertTrue(SideScanPolicy.PERIODIC_RESTART_MS < 30 * 60_000L)
        assertFalse(SideScanPolicy.dueRestart(startedAt = 0, now = SideScanPolicy.PERIODIC_RESTART_MS - 1))
        assertTrue(SideScanPolicy.dueRestart(startedAt = 0, now = SideScanPolicy.PERIODIC_RESTART_MS))
    }
}
