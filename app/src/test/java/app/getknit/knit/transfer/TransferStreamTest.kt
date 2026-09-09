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
import kotlin.random.Random

/** The direct-transfer byte protocol: framing, the keyed trailer, the client proof and the verdict byte. */
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
    fun aFlippedByteFailsTheTrailer() {
        val payload = Random(3).nextBytes(100_000)
        val bytes = wire(payload)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        assertFalse(receive(bytes, size = payload.size.toLong()).first)
    }

    @Test
    fun aWrongKeyFailsTheTrailer() {
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
