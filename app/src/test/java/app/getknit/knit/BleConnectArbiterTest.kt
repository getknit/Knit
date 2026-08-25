package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.BleConnectArbiter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleConnectArbiterTest {
    @Test
    fun busyWhileAnyHolderIsActive() {
        val arbiter = BleConnectArbiter()
        assertFalse(arbiter.busy.value)
        arbiter.begin("lora")
        assertTrue(arbiter.busy.value)
        arbiter.end("lora")
        assertFalse(arbiter.busy.value)
    }

    @Test
    fun staysBusyUntilTheLastHolderEnds() {
        val arbiter = BleConnectArbiter()
        arbiter.begin("a")
        arbiter.begin("b")
        arbiter.end("a")
        assertTrue("still one holder", arbiter.busy.value)
        arbiter.end("b")
        assertFalse(arbiter.busy.value)
    }

    @Test
    fun endingAnUnknownTagIsHarmless() {
        val arbiter = BleConnectArbiter()
        arbiter.end("never-began")
        assertFalse(arbiter.busy.value)
    }

    @Test
    fun aRepeatedBeginIsIdempotent() {
        val arbiter = BleConnectArbiter()
        arbiter.begin("a")
        arbiter.begin("a")
        arbiter.end("a")
        assertFalse("one end clears the single logical holder", arbiter.busy.value)
    }
}
