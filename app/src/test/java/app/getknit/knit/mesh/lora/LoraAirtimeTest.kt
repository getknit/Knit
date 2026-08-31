package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraAirtimeTest {
    private fun radio(
        preset: ModemPreset = ModemPreset.LONG_FAST,
        region: LoraRegion = LoraRegion.OTHER,
        override: Boolean = false,
        channelNum: Int = 0,
    ) = LoraRadioConfig(
        usePreset = true,
        modemPreset = preset,
        region = region,
        hopLimit = 3,
        overrideDutyCycle = override,
        channelNum = channelNum,
    )

    @Test
    fun aFullPacketAtLongFastIsAboutTwoSecondsOnAir() {
        val air = LoraAirtime()
        air.onRadioConfig(radio())
        // SF11 / BW250 / CR4-5, 233-byte payload + framing: the ~2 s figure the plane's docs quote.
        val ms = air.timeOnAirMs(MeshtasticProto.MAX_PAYLOAD)
        assertTrue("expected 1.8-2.6 s, got $ms ms", ms in 1_800..2_600)
    }

    @Test
    fun aSlowerPresetCostsMoreAirAndAFasterOneCostsLess() {
        val long = LoraAirtime().apply { onRadioConfig(radio(ModemPreset.LONG_FAST)) }.timeOnAirMs(200)
        val slow = LoraAirtime().apply { onRadioConfig(radio(ModemPreset.LONG_SLOW)) }.timeOnAirMs(200)
        val fast = LoraAirtime().apply { onRadioConfig(radio(ModemPreset.SHORT_TURBO)) }.timeOnAirMs(200)
        assertTrue("LONG_SLOW ($slow) must cost more than LONG_FAST ($long)", slow > long)
        assertTrue("SHORT_TURBO ($fast) must cost less than LONG_FAST ($long)", fast < long)
    }

    @Test
    fun aSmallPacketCostsLessThanAFullOne() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        assertTrue(air.timeOnAirMs(40) < air.timeOnAirMs(MeshtasticProto.MAX_PAYLOAD))
    }

    @Test
    fun withNoBoardConfigTheAllowanceIsTheConservativeFallback() {
        val air = LoraAirtime()
        val expected = (LoraAirtime.WINDOW_MS * LoraAirtime.FALLBACK_PERCENT / 100 * LoraAirtime.SAFETY).toLong()
        assertEquals(expected, air.allowanceMs())
        assertFalse(air.snapshot(0).known)
    }

    @Test
    fun theRegionsDutyCycleCapsTheAllowanceAndThePolitenessCeilingCapsTheRest() {
        val eu = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.EU_868)) }
        val us = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.OTHER)) }
        // EU_868 runs at 10 %, which is also our politeness ceiling, so the two allowances coincide —
        // a 100 %-duty region is limited by politeness, not by law.
        assertEquals(eu.allowanceMs(), us.allowanceMs())
        val ceiling = (LoraAirtime.WINDOW_MS * LoraAirtime.POLITE_CEILING_PERCENT / 100 * LoraAirtime.SAFETY).toLong()
        assertEquals(ceiling, us.allowanceMs())
    }

    @Test
    fun aDutyCycleOverrideDropsTheRegionalCapButKeepsThePolitenessCeiling() {
        val strict = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.EU_433)) }
        val overridden = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.EU_433, override = true)) }
        assertTrue(overridden.allowanceMs() >= strict.allowanceMs())
        val ceiling = (LoraAirtime.WINDOW_MS * LoraAirtime.POLITE_CEILING_PERCENT / 100 * LoraAirtime.SAFETY).toLong()
        assertEquals(ceiling, overridden.allowanceMs())
    }

    @Test
    fun theBridgeBudgetIsAShareOfTheWholeAllowanceNotASecondOne() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        assertEquals((air.allowanceMs() * LoraAirtime.BRIDGE_SHARE).toLong(), air.budgetMs(AirBucket.BRIDGE))
        assertEquals(air.allowanceMs(), air.budgetMs(AirBucket.LIVE))
    }

    @Test
    fun bridgeIsRefusedAtItsShareWhileLiveStillGoes() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        // Spend the bridge share and nothing else.
        var now = 0L
        while (air.admits(AirBucket.BRIDGE, FrameClass.ROOM, packet, now)) {
            air.record(AirBucket.BRIDGE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertFalse("bridge is spent", air.admits(AirBucket.BRIDGE, FrameClass.ROOM, packet, now))
        assertTrue("live still has the rest of the allowance", air.admits(AirBucket.LIVE, FrameClass.ROOM, packet, now))
        assertTrue(air.usedMs(AirBucket.BRIDGE, now) <= air.budgetMs(AirBucket.BRIDGE))
    }

    @Test
    fun aBootstrapFrameStillRidesWithTheRestOfTheBudgetSpent() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        var now = 0L
        while (air.admits(AirBucket.LIVE, FrameClass.ROOM, packet, now)) {
            air.record(AirBucket.LIVE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertFalse(air.admits(AirBucket.LIVE, FrameClass.ROOM, packet, now))
        assertTrue(
            "nothing verifies without the author's profile, so a spent window must not silence it",
            air.admits(AirBucket.BOOTSTRAP, FrameClass.BOOTSTRAP, packet, now),
        )
    }

    @Test
    fun theBootstrapIsRefusedAtItsOwnShareSoItCanNeverBlankThePlane() {
        // ADR 056. Before it, BOOTSTRAP returned true unconditionally *and* was recorded, so a relayed
        // profile re-fanned every 10 min (the sig dedup's TTL) could take the whole allowance and leave the
        // plane refusing everything a human had typed. On the lab gateway it took 79 % of all frames sent.
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        var now = 0L
        var admitted = 0
        while (air.admits(AirBucket.BOOTSTRAP, FrameClass.BOOTSTRAP, packet, now)) {
            air.record(AirBucket.BOOTSTRAP, MeshtasticProto.MAX_PAYLOAD, now)
            admitted++
            now += 3_000
            assertTrue("the exemption is bounded, not unbounded", admitted < 100)
        }
        assertTrue("some bootstrap always fits", admitted > 0)
        assertTrue(
            "spending stays inside the bootstrap share",
            air.usedMs(AirBucket.BOOTSTRAP, now) <= air.budgetMs(AirBucket.BOOTSTRAP),
        )
        assertEquals(
            (air.allowanceMs() * LoraAirtime.BOOTSTRAP_SHARE).toLong(),
            air.budgetMs(AirBucket.BOOTSTRAP),
        )
        // And what it did spend is real air: content sees a window that much smaller, not a fresh one.
        val left = air.allowanceMs() - air.usedMs(AirBucket.BOOTSTRAP, now)
        assertTrue("the bootstrap's air is charged to the total too", left < air.allowanceMs())
        assertTrue("three quarters of the window survives it", left >= air.allowanceMs() * 3 / 4)
    }

    @Test
    fun aSpentBootstrapShareDoesNotStopContentGoingOut() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        var now = 0L
        while (air.admits(AirBucket.BOOTSTRAP, FrameClass.BOOTSTRAP, packet, now)) {
            air.record(AirBucket.BOOTSTRAP, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertFalse(air.admits(AirBucket.BOOTSTRAP, FrameClass.BOOTSTRAP, packet, now))
        assertTrue("a DM still has the rest of the window", air.admits(AirBucket.LIVE, FrameClass.DM, packet, now))
    }

    @Test
    fun theWindowIsFifteenMinutesSoAWorstCaseHourStaysUnderTheEuRefusalPoint() {
        assertEquals(15 * 60_000L, LoraAirtime.WINDOW_MS)
        val air = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.EU_868)) }
        // Rolling windows straddle an hour, so five can partly overlap it: 5/4 of the nominal allowance.
        val worstHourPercent = air.allowanceMs() * 5 / 4 * 100.0 / (60 * 60_000L)
        assertTrue(
            "worst hour $worstHourPercent % must stay under the firmware's 10 %",
            worstHourPercent < LoraRegion.EU_868.dutyCyclePercent,
        )
    }

    @Test
    fun aTickNeverSpendsTheTailOfAWindowButContentStillDoes() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        var now = 0L
        while (air.admits(AirBucket.LIVE, FrameClass.TICK, packet, now)) {
            air.record(AirBucket.LIVE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        val tail = (air.budgetMs(AirBucket.LIVE) * LoraAirtime.TICK_TAIL_SHARE).toLong()
        assertTrue(
            "ticks stop at the tail",
            air.usedMs(AirBucket.LIVE, now) >= air.budgetMs(AirBucket.LIVE) - tail - air.timeOnAirMs(MeshtasticProto.MAX_PAYLOAD),
        )
        assertFalse(air.admits(AirBucket.LIVE, FrameClass.TICK, packet, now))
        assertTrue("a DM still has the tail", air.admits(AirBucket.LIVE, FrameClass.DM, packet, now))
        assertTrue("so does the room", air.admits(AirBucket.LIVE, FrameClass.ROOM, packet, now))
    }

    @Test
    fun spendingAgesOutOfTheRollingWindow() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        air.record(AirBucket.LIVE, MeshtasticProto.MAX_PAYLOAD, 0)
        assertTrue(air.usedMs(AirBucket.LIVE, 0) > 0)
        assertEquals(0L, air.usedMs(AirBucket.LIVE, LoraAirtime.WINDOW_MS))
    }

    @Test
    fun aWholeFragmentedFrameIsAdmittedOrRefusedTogether() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val one = listOf(MeshtasticProto.MAX_PAYLOAD)
        val three = List(3) { MeshtasticProto.MAX_PAYLOAD }
        var now = 0L
        // Spend down to where one packet fits but three do not.
        while (air.admits(AirBucket.LIVE, FrameClass.ROOM, three, now)) {
            air.record(AirBucket.LIVE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertFalse(air.admits(AirBucket.LIVE, FrameClass.ROOM, three, now))
        assertTrue(air.admits(AirBucket.LIVE, FrameClass.ROOM, one, now))
    }

    // --- ADR 067: a dedicated RF slot lifts the politeness ceiling, never the law ---

    @Test
    fun aDedicatedSlotIsInertUnlessTheBuildUnlocksIt() {
        // The default — every release build. A board somebody pinned by hand in the Meshtastic app must
        // budget exactly as a shared-frequency one does.
        val locked = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.US, channelNum = 37)) }
        val shared = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.US)) }
        assertEquals(shared.allowanceMs(), locked.allowanceMs())
        assertFalse(locked.dedicated())
    }

    @Test
    fun aDedicatedSlotInAHundredPercentRegionSpendsTheWholeDutyCycle() {
        val unlocked = LoraAirtime(dedicatedUnlocksDuty = true)
        unlocked.onRadioConfig(radio(region = LoraRegion.US, channelNum = 37))
        assertTrue(unlocked.dedicated())
        // 100 % duty x the 0.5 safety factor = half the window, against a tenth of that when sharing.
        assertEquals(LoraAirtime.WINDOW_MS / 2, unlocked.allowanceMs())
        val shared = LoraAirtime(dedicatedUnlocksDuty = true).apply { onRadioConfig(radio(region = LoraRegion.US)) }
        assertEquals(10L, unlocked.allowanceMs() / shared.allowanceMs())
    }

    @Test
    fun aDedicatedSlotNeverLiftsARegionsLegalDutyCycle() {
        // EU_868's 10 % is law, not manners: a dedicated slot there must budget exactly as a shared one.
        val dedicated = LoraAirtime(dedicatedUnlocksDuty = true)
        dedicated.onRadioConfig(radio(region = LoraRegion.EU_868, channelNum = 3))
        val shared = LoraAirtime(dedicatedUnlocksDuty = true).apply { onRadioConfig(radio(region = LoraRegion.EU_868)) }
        assertEquals(shared.allowanceMs(), dedicated.allowanceMs())
    }

    @Test
    fun theSnapshotReportsWhetherTheBudgetIsRunningDedicated() {
        val air = LoraAirtime(dedicatedUnlocksDuty = true)
        air.onRadioConfig(radio(region = LoraRegion.US, channelNum = 12))
        assertTrue(air.snapshot(0L).dedicated)
        assertFalse(LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.US)) }.snapshot(0L).dedicated)
    }
}
