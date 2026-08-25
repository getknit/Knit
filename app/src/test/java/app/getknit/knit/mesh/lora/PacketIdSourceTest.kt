package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketIdSourceTest {
    @Test
    fun incrementsFromTheSeed() {
        val ids = PacketIdSource(seed = 100L)
        assertEquals(100u, ids.next())
        assertEquals(101u, ids.next())
        assertEquals(102u, ids.next())
    }

    @Test
    fun neverYieldsZeroOnWrap() {
        // Seed at UInt.MAX so the very next increment wraps through 0 — which must be skipped.
        val ids = PacketIdSource(seed = 0xFFFFFFFFL)
        assertEquals(0xFFFFFFFFu, ids.next())
        assertEquals(1u, ids.next()) // wrapped 0 skipped
        assertEquals(2u, ids.next())
    }

    @Test
    fun aZeroSeedStartsAtOne() {
        val ids = PacketIdSource(seed = 0L)
        assertNotEquals(0u, ids.next())
    }

    @Test
    fun idsAreDistinctAcrossAShortRun() {
        val ids = PacketIdSource(seed = 7L)
        val seen = HashSet<UInt>()
        repeat(1000) { assertTrue(seen.add(ids.next())) }
    }
}
