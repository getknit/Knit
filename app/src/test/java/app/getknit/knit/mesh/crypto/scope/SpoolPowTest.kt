package app.getknit.knit.mesh.crypto.scope

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Behavior anchors for [SpoolPow]: the stamp digest is recomputed independently, difficulty counting
 * is pinned bit-by-bit, and mining is proven deterministic (smallest counter first — what the spec
 * vector in [ScopeVectorTest] relies on). docs/SPOOL_PROTOCOL.md §8 is the normative spec.
 */
class SpoolPowTest {
    @Test
    fun digestMatchesAnIndependentRecompute() {
        val scopeId = ByteArray(32) { ((it * 7 + 9) and 0xFF).toByte() }
        val day = 20_680L
        val n = 42L

        val expected =
            MessageDigest.getInstance("SHA-256").digest(
                "knit/spool/v1/pow".toByteArray() + scopeId +
                    byteArrayOf(0, 0, 0, 0, 0, 0, 0x50, 0xC8.toByte()) +
                    byteArrayOf(0, 0, 0, 0, 0, 0, 0, 42),
            )
        assertArrayEquals(expected, SpoolPow.digest(scopeId, day, n))
    }

    @Test
    fun leadingZeroBitsCountsBitLexicographically() {
        assertEquals(0, SpoolPow.leadingZeroBits(byteArrayOf(0x80.toByte(), 0)))
        assertEquals(7, SpoolPow.leadingZeroBits(byteArrayOf(0x01, 0xFF.toByte())))
        assertEquals(8, SpoolPow.leadingZeroBits(byteArrayOf(0x00, 0xFF.toByte())))
        assertEquals(17, SpoolPow.leadingZeroBits(byteArrayOf(0, 0, 0x40, 0)))
        assertEquals(32, SpoolPow.leadingZeroBits(ByteArray(4)))
    }

    @Test
    fun miningFindsTheSmallestValidCounterAndItVerifies() {
        val scopeId = ByteArray(32) { ((it * 7 + 9) and 0xFF).toByte() }
        val day = 20_680L

        val n = SpoolPow.stamp(scopeId, day, bits = 8, maxAttempts = 1_000_000)

        assertNotNull(n)
        assertTrue(SpoolPow.verify(scopeId, day, n!!, bits = 8))
        for (earlier in 0 until n) {
            assertFalse(SpoolPow.verify(scopeId, day, earlier, bits = 8))
        }
    }

    @Test
    fun exhaustedBudgetReturnsNullAndZeroBitsAcceptsEverything() {
        val scopeId = ByteArray(32) { 1 }

        assertNull(SpoolPow.stamp(scopeId, day = 1L, bits = 256, maxAttempts = 64))
        assertTrue(SpoolPow.verify(scopeId, day = 1L, n = 0L, bits = 0))
        assertTrue(SpoolPow.verify(scopeId, day = 1L, n = 0L, bits = -1))
    }

    @Test
    fun utcDayFloorsTheEpochMillis() {
        assertEquals(0L, SpoolPow.utcDay(0L))
        assertEquals(0L, SpoolPow.utcDay(SpoolPow.DAY_MS - 1))
        assertEquals(1L, SpoolPow.utcDay(SpoolPow.DAY_MS))
        assertEquals(-1L, SpoolPow.utcDay(-1L))
    }
}
