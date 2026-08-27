package app.getknit.knit.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MeshStartGate] — the record of a refused [MeshService.start] that is still owed a retry. Pure state, so
 * plain JVM: nothing here touches Android. The retry itself is unconditional in `KnitApp`'s `ON_RESUME`
 * observer, so this flag is observability (`…debug.STATE`), not control flow. Work item #32.
 */
class MeshStartGateTest {
    @Test
    fun `nothing is owed before any start`() {
        assertFalse(MeshStartGate().deferred.value)
    }

    @Test
    fun `a refused start is owed a retry`() {
        val gate = MeshStartGate()
        gate.record(accepted = false)
        assertTrue(gate.deferred.value)
    }

    @Test
    fun `an accepted start clears the debt`() {
        val gate = MeshStartGate()
        gate.record(accepted = false)
        gate.record(accepted = true)
        assertFalse(gate.deferred.value)
    }

    @Test
    fun `a second refusal keeps it owed`() {
        val gate = MeshStartGate()
        gate.record(accepted = false)
        gate.record(accepted = false)
        assertTrue(gate.deferred.value)
    }
}
