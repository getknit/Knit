package app.getknit.knit.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * The direct-transfer byte protocol: framing, the per-chunk seal, the keyed trailer, the client proof and
 * the verdict byte.
 */
class TransferStreamTest {
    private val key = ByteArray(TransferStream.KEY_BYTES) { it.toByte() }
    private val otherKey = ByteArray(TransferStream.KEY_BYTES) { (it + 1).toByte() }

    private fun wire(
        payload: ByteArray,
        id: String = "t1",
        size: Long = payload.size.toLong(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        TransferStream.send(out, ByteArrayInputStream(payload), id, size, key) {}
        return out.toByteArray()
    }

    private fun receive(
        bytes: ByteArray,
        id: String = "t1",
        size: Long,
        key: ByteArray = this.key,
    ): Pair<Boolean, ByteArray> {
        val sink = ByteArrayOutputStream()
        val ok = TransferStream.receive(ByteArrayInputStream(bytes), sink, id, size, key) {}
        return ok to sink.toByteArray()
    }

    @Test
    fun roundTripsEmptyOneByteAndMultiChunkPayloads() {
        for (payload in listOf(ByteArray(0), byteArrayOf(7), Random(1).nextBytes(3 * 1024 * 1024 + 13))) {
            val (ok, got) = receive(wire(payload), size = payload.size.toLong())
            assertTrue("payload of ${payload.size} bytes", ok)
            assertArrayEquals(payload, got)
        }
    }

    @Test
    fun progressCountsUpToTheSize() {
        val payload = Random(2).nextBytes(TransferStream.CHUNK_BYTES * 2 + 1)
        val sent = mutableListOf<Long>()
        val out = ByteArrayOutputStream()
        TransferStream.send(out, ByteArrayInputStream(payload), "t1", payload.size.toLong(), key) { sent += it }
        assertEquals(payload.size.toLong(), sent.last())
        assertEquals(3, sent.size)
        val got = mutableListOf<Long>()
        assertTrue(
            TransferStream.receive(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream(), "t1", payload.size.toLong(), key) {
                got +=
                    it
            },
        )
        assertEquals(payload.size.toLong(), got.last())
    }

    @Test
    fun aFlippedByteFailsItsChunk() {
        val payload = Random(3).nextBytes(100_000)
        val bytes = wire(payload)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        assertFalse(receive(bytes, size = payload.size.toLong()).first)
    }

    @Test
    fun aWrongKeyOpensNothing() {
        val payload = Random(4).nextBytes(1000)
        assertFalse(receive(wire(payload), size = payload.size.toLong(), key = otherKey).first)
    }

    @Test
    fun aTruncatedStreamThrowsRatherThanVerifying() {
        val payload = Random(5).nextBytes(50_000)
        val bytes = wire(payload)
        assertThrows(EOFException::class.java) { receive(bytes.copyOf(bytes.size / 2), size = payload.size.toLong()) }
        assertThrows(EOFException::class.java) { receive(bytes.copyOf(bytes.size - 1), size = payload.size.toLong()) }
    }

    @Test
    fun aHeaderNamingAnotherTransferOrSizeIsRefused() {
        val payload = Random(6).nextBytes(64)
        assertFalse(receive(wire(payload), id = "t2", size = payload.size.toLong()).first)
        assertFalse(receive(wire(payload), size = payload.size + 1L).first)
    }

    @Test
    fun aSourceShorterThanTheDeclaredSizeThrows() {
        assertThrows(EOFException::class.java) { wire(ByteArray(10), size = 11) }
    }

    @Test
    fun theClientProofIsAcceptedOnlyForTheRightIdAndKey() {
        val proof = TransferStream.clientProof(key, "t1")
        assertTrue(TransferStream.readProof(ByteArrayInputStream(proof), key, "t1"))
        assertFalse(TransferStream.readProof(ByteArrayInputStream(proof), key, "t2"))
        assertFalse(TransferStream.readProof(ByteArrayInputStream(proof), otherKey, "t1"))
        assertFalse(TransferStream.readProof(ByteArrayInputStream(byteArrayOf(0)), key, "t1"))
    }

    /**
     * The headline property, and the reason this layer exists: the file is not on the wire. WPA2 is the other
     * lock, and a test that only checked the tag would pass just as happily with the payload in the clear.
     */
    @Test
    fun thePayloadNeverAppearsOnTheWire() {
        val marker = "SECRET-PAYLOAD!!".toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(100_000) { marker[it % marker.size] }
        val bytes = wire(payload)

        assertEquals("the marker is what we are looking for", 0, indexOf(payload, marker))
        assertEquals("and it is nowhere in the sealed stream", -1, indexOf(bytes, marker))
        val (ok, got) = receive(bytes, size = payload.size.toLong())
        assertTrue(ok)
        assertArrayEquals(payload, got)
    }

    /** Identical plaintext chunks must not seal alike, or the nonce is not advancing with the chunk index. */
    @Test
    fun twoIdenticalChunksSealDifferently() {
        val chunk = ByteArray(TransferStream.CHUNK_BYTES) { 0x5A }
        val bytes = wire(chunk + chunk)
        // Past the header, each chunk is u32 length + ciphertext + 16-byte tag; compare the two bodies.
        val first = bytes.size - (2 * (4 + TransferStream.CHUNK_BYTES + 16) + 32)
        val stride = 4 + TransferStream.CHUNK_BYTES + 16
        val a = bytes.copyOfRange(first + 4, first + stride)
        val b = bytes.copyOfRange(first + stride + 4, first + 2 * stride)
        assertEquals("same plaintext, same length", a.size, b.size)
        assertFalse("same plaintext must not seal to the same bytes", a.contentEquals(b))
    }

    /** A chunk carries its index in its AAD, so a stream replayed out of order opens nowhere. */
    @Test
    fun chunksCannotBeReordered() {
        val payload = Random(7).nextBytes(TransferStream.CHUNK_BYTES * 2)
        val bytes = wire(payload)
        val stride = 4 + TransferStream.CHUNK_BYTES + 16
        val start = bytes.size - (2 * stride + 32)
        val swapped =
            bytes.copyOfRange(0, start) +
                bytes.copyOfRange(start + stride, start + 2 * stride) +
                bytes.copyOfRange(start, start + stride) +
                bytes.copyOfRange(start + 2 * stride, bytes.size)

        assertFalse(receive(swapped, size = payload.size.toLong()).first)
    }

    /**
     * The final chunk is marked as final in its own AAD, and the receiver supplies that mark from the size it
     * agreed to rather than reading it off the wire — so a stream cut at a chunk boundary is refused rather
     * than accepted as a shorter file.
     */
    @Test
    fun aStreamCutAtAChunkBoundaryIsRefused() {
        val payload = Random(8).nextBytes(TransferStream.CHUNK_BYTES * 2)
        val bytes = wire(payload)
        val stride = 4 + TransferStream.CHUNK_BYTES + 16
        val start = bytes.size - (2 * stride + 32)
        // One whole chunk, then a trailer: well-formed framing for a file of exactly one chunk.
        val cut = bytes.copyOfRange(0, start + stride) + ByteArray(32)

        assertFalse(receive(cut, size = TransferStream.CHUNK_BYTES.toLong()).first)
    }

    /** The transfer secret is split before use, so using it raw as the MAC key proves nothing. */
    @Test
    fun theProofIsKeyedByADerivedKeyNotTheTransferSecret() {
        val raw =
            javax.crypto.Mac
                .getInstance("HmacSHA256")
                .apply { init(SecretKeySpec(key, "HmacSHA256")) }
        raw.update("client".toByteArray(Charsets.US_ASCII))
        raw.update("t1".toByteArray(Charsets.UTF_8))
        val forged = byteArrayOf(2) + "t1".toByteArray(Charsets.UTF_8) + raw.doFinal()

        assertFalse(TransferStream.readProof(ByteArrayInputStream(forged), key, "t1"))
        assertTrue(TransferStream.readProof(ByteArrayInputStream(TransferStream.clientProof(key, "t1")), key, "t1"))
    }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int =
        (0..haystack.size - needle.size).firstOrNull { at ->
            needle.indices.all { haystack[at + it] == needle[it] }
        } ?: -1

    @Test
    fun theVerdictByteRoundTripsAndAnEndedStreamReadsNull() {
        val out = ByteArrayOutputStream()
        TransferStream.writeVerdict(out, true)
        TransferStream.writeVerdict(out, false)
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(true, TransferStream.readVerdict(input))
        assertEquals(false, TransferStream.readVerdict(input))
        assertNull(TransferStream.readVerdict(input))
    }
}
