package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 067. The dedicated slot is a transmit frequency Knit picks itself, so the two properties that matter
 * are that it is always inside the band and that every Knit board picks the same one.
 */
class LoraSlotTest {
    @Test
    fun theSlotIsAlwaysInsideTheBandItWasDerivedFrom() {
        for (region in LoraRegion.entries) {
            for (preset in ModemPreset.entries) {
                val slot = LoraSlot.forRegion(region, preset) ?: continue
                val count = LoraSlot.channelCount(region, preset)
                assertTrue(
                    "$region/$preset slot $slot must be in 1..$count",
                    slot in 1..count,
                )
            }
        }
    }

    @Test
    fun everyBoardInTheSameRegionAndPresetPicksTheSameSlot() {
        // The whole point of deriving it rather than asking: a dedicated fleet still meets with no
        // coordination, exactly as ADR 045's shared one does.
        val first = LoraSlot.forRegion(LoraRegion.US, ModemPreset.LONG_FAST)
        assertNotNull(first)
        repeat(5) { assertEquals(first, LoraSlot.forRegion(LoraRegion.US, ModemPreset.LONG_FAST)) }
    }

    @Test
    fun aRegionWhoseBandKnitDoesNotKnowGetsNoSlot() {
        // Refusing is the feature: picking a frequency in a band we have not stated exactly is how you end
        // up transmitting out of band.
        assertNull(LoraSlot.forRegion(LoraRegion.OTHER, ModemPreset.LONG_FAST))
        assertNull(LoraSlot.forRegion(LoraRegion.UNSET, ModemPreset.LONG_FAST))
        assertNull(LoraSlot.forRegion(LoraRegion.EU_868, ModemPreset.LONG_FAST))
        assertNull(LoraSlot.forRegion(LoraRegion.EU_433, ModemPreset.LONG_FAST))
    }

    @Test
    fun theBandsKnitDoesKnowAreTheWideOnesWorthMovingIn() {
        // 26 MHz of US 915 and 13 MHz of ANZ at LongFast's 250 kHz.
        assertEquals(104, LoraSlot.channelCount(LoraRegion.US, ModemPreset.LONG_FAST))
        assertEquals(52, LoraSlot.channelCount(LoraRegion.ANZ, ModemPreset.LONG_FAST))
        assertNotNull(LoraSlot.forRegion(LoraRegion.US, ModemPreset.LONG_FAST))
        assertNotNull(LoraSlot.forRegion(LoraRegion.ANZ, ModemPreset.LONG_FAST))
    }

    @Test
    fun aWiderPresetHalvesTheSlotsItStillFitsIn() {
        // ShortTurbo's 500 kHz is twice LongFast's, so the same band holds half as many slots — and the
        // derivation has to follow the preset, not assume one.
        val fast = LoraSlot.channelCount(LoraRegion.US, ModemPreset.LONG_FAST)
        val turbo = LoraSlot.channelCount(LoraRegion.US, ModemPreset.SHORT_TURBO)
        assertEquals(fast / 2, turbo)
        assertTrue(LoraSlot.forRegion(LoraRegion.US, ModemPreset.SHORT_TURBO)!! <= turbo)
    }

    @Test
    fun aBandWithNowhereToMoveIsRefusedRatherThanPinnedToItsOnlySlot() {
        // MIN_CHANNELS is what stops "dedicated" meaning "the stock slot, but with the politeness ceiling
        // lifted" — which would be the worst of both.
        assertEquals(2, LoraSlot.MIN_CHANNELS)
        for (region in LoraRegion.entries) {
            for (preset in ModemPreset.entries) {
                if (LoraSlot.channelCount(region, preset) < LoraSlot.MIN_CHANNELS) {
                    assertNull("$region/$preset has no room and must be refused", LoraSlot.forRegion(region, preset))
                }
            }
        }
    }
}
