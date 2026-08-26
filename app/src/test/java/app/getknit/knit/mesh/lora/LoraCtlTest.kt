package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.link.FastFrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraCtlTest {
    private val publisher = StoreDigest.hash64("alice")

    private fun prefixes(vararg ids: String) = ids.map { LoraCtl.prefixOf(StoreDigest.hash64(it)) }.toIntArray()

    @Test
    fun anOfferRoundTrips() {
        val ours = prefixes("a", "b", "c")
        val decoded = LoraCtl.decodeOffer(LoraCtl.encodeOffer(publisher, ours, MeshtasticProto.MAX_PAYLOAD))!!
        assertEquals(publisher, decoded.publisher)
        assertArrayEquals(ours.sortedArray(), decoded.prefixes)
    }

    @Test
    fun anEmptyOfferIsValid() {
        // A node that holds nothing still has something to say: "send me what you have".
        val decoded = LoraCtl.decodeOffer(LoraCtl.encodeOffer(publisher, IntArray(0), MeshtasticProto.MAX_PAYLOAD))!!
        assertEquals(0, decoded.prefixes.size)
        assertEquals(publisher, decoded.publisher)
    }

    @Test
    fun theGoldenLayoutIsPinned() {
        // tag | version | kind | 8-byte publisher | 2-byte count | 4-byte prefixes
        val bytes = LoraCtl.encodeOffer(publisher = 0x0102030405060708L, prefixes = intArrayOf(0x11223344), maxPayload = 64)
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        assertEquals("10 01 01 01 02 03 04 05 06 07 08 00 01 11 22 33 44", hex)
    }

    @Test
    fun anOfferNeverFragmentsAndTruncatesToWhatOnePacketHolds() {
        val many = IntArray(500) { it }
        val bytes = LoraCtl.encodeOffer(publisher, many, MeshtasticProto.MAX_PAYLOAD)
        assertTrue("one packet or nothing: ${bytes.size} B", bytes.size <= MeshtasticProto.MAX_PAYLOAD)
        assertEquals(LoraCtl.capacityFor(MeshtasticProto.MAX_PAYLOAD), LoraCtl.decodeOffer(bytes)!!.prefixes.size)
    }

    @Test
    fun theWindowIsCappedEvenWhenThePacketWouldHoldMore() {
        // capacityFor never exceeds MAX_PREFIXES, so the offer stays a bounded window by policy, not by luck.
        assertEquals(LoraCtl.MAX_PREFIXES, LoraCtl.capacityFor(4096))
        assertEquals(LoraCtl.MAX_PREFIXES, LoraCtl.capacityFor(MeshtasticProto.MAX_PAYLOAD))
    }

    @Test
    fun aSmallMtuShrinksTheWindowRatherThanOverflowing() {
        val bytes = LoraCtl.encodeOffer(publisher, IntArray(50) { it }, LoraFrameCodec.MIN_PAYLOAD)
        assertTrue(bytes.size <= LoraFrameCodec.MIN_PAYLOAD)
        assertEquals(LoraCtl.capacityFor(LoraFrameCodec.MIN_PAYLOAD), LoraCtl.decodeOffer(bytes)!!.prefixes.size)
    }

    @Test
    fun theSameSetEncodesIdenticallyRegardlessOfOrder() {
        // What lets the gossip timer recognise a peer's offer as announcing the set ours would.
        val a = LoraCtl.encodeOffer(publisher, prefixes("a", "b", "c"), MeshtasticProto.MAX_PAYLOAD)
        val b = LoraCtl.encodeOffer(publisher, prefixes("c", "a", "b"), MeshtasticProto.MAX_PAYLOAD)
        assertArrayEquals(a, b)
    }

    @Test
    fun theTagIsClearOfTheFrameCodecs() {
        assertFalse(LoraCtl.TAG == FastFrameCodec.TAG_COMPACT)
        assertFalse(LoraCtl.TAG == FastFrameCodec.TAG_FRAG)
        assertTrue(LoraCtl.isCtl(byteArrayOf(LoraCtl.TAG)))
        assertFalse(LoraCtl.isCtl(byteArrayOf(FastFrameCodec.TAG_COMPACT)))
        assertFalse(LoraCtl.isCtl(ByteArray(0)))
    }

    @Test
    fun malformedInputDecodesToNullAndNeverThrows() {
        assertNull(LoraCtl.decodeOffer(ByteArray(0)))
        assertNull("truncated header", LoraCtl.decodeOffer(ByteArray(LoraCtl.HEADER_BYTES - 1) { LoraCtl.TAG }))
        assertNull("a compact frame is not an offer", LoraCtl.decodeOffer(ByteArray(40) { FastFrameCodec.TAG_COMPACT }))
        val good = LoraCtl.encodeOffer(publisher, prefixes("a", "b"), MeshtasticProto.MAX_PAYLOAD)
        assertNull("count outruns the body", LoraCtl.decodeOffer(good.copyOf(good.size - 1)))
        val wrongVersion = good.copyOf().also { it[1] = 0x7F }
        assertNull(LoraCtl.decodeOffer(wrongVersion))
        val wrongKind = good.copyOf().also { it[2] = 0x7F }
        assertNull(LoraCtl.decodeOffer(wrongKind))
    }

    @Test
    fun aTruncatedOfferDropsTheOldestBecauseCallersPassNewestFirst() {
        val newestFirst = intArrayOf(9, 8, 7, 6, 5)
        val kept = LoraCtl.decodeOffer(LoraCtl.encodeOffer(publisher, newestFirst, LoraCtl.HEADER_BYTES + 3 * 4))!!
        assertArrayEquals(intArrayOf(7, 8, 9), kept.prefixes)
    }

    @Test
    fun prefixesAreStableAndSpreadOverTheWholeIntRange() {
        assertEquals(LoraCtl.prefixOf(StoreDigest.hash64("id-1")), LoraCtl.prefixOf(StoreDigest.hash64("id-1")))
        val spread = (0 until 500).map { LoraCtl.prefixOf(StoreDigest.hash64("id-$it")) }.toSet()
        assertEquals("no collisions over 500 ids", 500, spread.size)
    }
}
