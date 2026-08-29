package app.getknit.knit

import app.getknit.knit.mesh.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun advertiseParseRoundTrips() {
        // A real 26-char base32 nodeId (contains no '|', so the split-on-'|' round-trips cleanly).
        val id = "ffbbh6thbepahqxsv2gqog45m4"
        val parsed = Protocol.parse(Protocol.advertise(id))
        assertEquals(id, parsed.nodeId)
        assertEquals(Protocol.VERSION, parsed.protoVersion)
        assertEquals(Protocol.LOCAL_CAPABILITIES, parsed.capabilities)
    }

    @Test
    fun bareLegacyNodeIdParsesToUnknownVersion() {
        // A peer that advertises just its nodeId (a pre-protocol build) is "unknown" — version 0, no caps.
        val parsed = Protocol.parse("abcd1234")
        assertEquals("abcd1234", parsed.nodeId)
        assertEquals(0, parsed.protoVersion)
        assertEquals(0L, parsed.capabilities)
    }

    @Test
    fun parseNeverThrowsOnGarbageSegments() {
        val parsed = Protocol.parse("abcd1234|notanint|zzz")
        assertEquals("abcd1234", parsed.nodeId)
        assertEquals(0, parsed.protoVersion)
        assertEquals(0L, parsed.capabilities)
    }

    @Test
    fun cryptoV3RidesTheProfileAboveTheBleAdvertsEightBits() {
        // ADR 059: the bit is a send-time input read from the pinned profile; it deliberately sits above the
        // 8 bits a BLE advert carries (0x80 stays reserved for the transcoder), and every local build claims it.
        assertTrue(Protocol.LOCAL_CAPABILITIES and Protocol.CAP_CRYPTO_V3 != 0L)
        assertTrue(Protocol.CAP_CRYPTO_V3 > 0xFFL)
        assertEquals(0x100L, Protocol.CAP_CRYPTO_V3)
    }

    @Test
    fun nodeIdIsAlwaysTheFirstSegment() {
        // Robust to any future suffix appended after the capabilities field.
        assertEquals("abcd1234", Protocol.parse("abcd1234|1|f|future|stuff").nodeId)
    }
}
