package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The firmware's `battery_level` conventions, folded once in [BoardBattery.of]. */
class BoardBatteryTest {
    @Test
    fun aLevelInRangeIsACharge() {
        assertEquals(BoardBattery(percent = 78, voltage = 3.92f, powered = false), BoardBattery.of(78, 3.92f))
        assertEquals(BoardBattery(percent = 100, voltage = null, powered = false), BoardBattery.of(100, null))
    }

    @Test
    fun aLevelAbove100MeansExternalPower() {
        val powered = BoardBattery.of(101, 4.1f)
        assertEquals(BoardBattery(percent = null, voltage = 4.1f, powered = true), powered)
        assertFalse(powered!!.low)
    }

    @Test
    fun noLevelOrTheFirmwaresUnknownIsNoReading() {
        assertNull(BoardBattery.of(null, 3.9f))
        assertNull(BoardBattery.of(-1, null)) // the firmware's int8 "unknown", cast through a uint32
    }

    @Test
    fun zeroWithNoVoltageIsNoBatterySenseButZeroWithAVoltageIsAnEmptyCell() {
        assertNull(BoardBattery.of(0, null))
        assertNull(BoardBattery.of(0, 0f))
        assertEquals(BoardBattery(percent = 0, voltage = 3.2f, powered = false), BoardBattery.of(0, 3.2f))
    }

    @Test
    fun lowIsAtOrUnderTheThresholdOnBattery() {
        assertTrue(BoardBattery.of(BoardBattery.LOW_PERCENT, 3.5f)!!.low)
        assertFalse(BoardBattery.of(BoardBattery.LOW_PERCENT + 1, 3.6f)!!.low)
    }
}
