package app.getknit.knit.transfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The byte protocol of a direct file transfer over one TCP connection — pure, transport-neutral and
 * JVM-tested. Framing, in order:
 *
 * 1. The client's proof, sent first by whoever connected: `idLen(u8) ‖ id ‖ HMAC-SHA256(key, "client" ‖ id)`.
 *    The listener reads it before writing a byte, so a stray connection on the port (the sender's LAN can
 *    reach it) never receives the file.
 * 2. The header: `"KNXF" ‖ version(u8) ‖ idLen(u8) ‖ id ‖ size(i64)`.
 * 3. Exactly `size` bytes.
 * 4. A trailer `HMAC-SHA256(key, header ‖ bytes)`, which the receiver verifies before committing anything.
 * 5. One verdict byte from the receiver ([VERDICT_OK] / [VERDICT_CORRUPT]), so the sender's record can say
 *    what actually landed.
 *
 * [key] is the per-transfer 32-byte secret the sealed READY carried; it makes the tag an authenticity check
 * rather than a checksum. Blocking I/O throughout — callers run it on an I/O dispatcher and cancel by closing
 * the socket, which surfaces here as an [IOException].
 */
object TransferStream {
    const val VERSION = 1
    const val KEY_BYTES = 32
    const val CHUNK_BYTES = 256 * 1024
    const val VERDICT_OK: Byte = 1
    const val VERDICT_CORRUPT: Byte = 0

    private val MAGIC = "KNXF".toByteArray(Charsets.US_ASCII)
    private const val TAG_BYTES = 32
    private const val MAX_ID_BYTES = 64
    private const val HMAC = "HmacSHA256"
    private const val CLIENT_LABEL = "client"

    /** The bytes the connecting side writes first (framing item 1). */
    fun clientProof(
        key: ByteArray,
        id: String,
    ): ByteArray {
        val idBytes = idBytes(id)
        val mac = mac(key)
        mac.update(CLIENT_LABEL.toByteArray(Charsets.US_ASCII))
        mac.update(idBytes)
        return byteArrayOf(idBytes.size.toByte()) + idBytes + mac.doFinal()
    }

    /** Reads a proof off [input]; true only when it names [expectedId] under [key]. */
    fun readProof(
        input: InputStream,
        key: ByteArray,
        expectedId: String,
    ): Boolean {
        val data = DataInputStream(input)
        val len = data.readUnsignedByte()
        if (len == 0 || len > MAX_ID_BYTES) return false
        val id = ByteArray(len).also(data::readFully)
        val tag = ByteArray(TAG_BYTES).also(data::readFully)
        val expected = clientProof(key, expectedId)
        return MessageDigest.isEqual(expected, byteArrayOf(len.toByte()) + id + tag)
    }

    /**
     * Streams [size] bytes of [source] to [out] under the framing above. [onProgress] is called with the
     * running byte count after every chunk. Throws [IOException] if [source] ends early or [out] fails.
     */
    fun send(
        out: OutputStream,
        source: InputStream,
        id: String,
        size: Long,
        key: ByteArray,
        onProgress: (Long) -> Unit,
    ) {
        val data = DataOutputStream(out)
        val mac = mac(key)
        val header = header(id, size)
        mac.update(header)
        data.write(header)
        val buf = ByteArray(CHUNK_BYTES)
        var sent = 0L
        while (sent < size) {
            val want = minOf(buf.size.toLong(), size - sent).toInt()
            val n = source.read(buf, 0, want)
            if (n < 0) throw EOFException("source ended at $sent of $size bytes")
            data.write(buf, 0, n)
            mac.update(buf, 0, n)
            sent += n
            onProgress(sent)
        }
        data.write(mac.doFinal())
        data.flush()
    }

    /**
     * Reads one transfer off [input] into [sink]. True when the header named [expectedId]/[expectedSize],
     * every byte arrived and the trailer verified under [key]; false on any mismatch — the caller then
     * discards what [sink] holds. A stream that ends early throws [IOException].
     */
    fun receive(
        input: InputStream,
        sink: OutputStream,
        expectedId: String,
        expectedSize: Long,
        key: ByteArray,
        onProgress: (Long) -> Unit,
    ): Boolean {
        val data = DataInputStream(input)
        val expectedHeader = header(expectedId, expectedSize)
        val header = ByteArray(expectedHeader.size).also(data::readFully)
        if (!header.contentEquals(expectedHeader)) return false
        val mac = mac(key)
        mac.update(header)
        val buf = ByteArray(CHUNK_BYTES)
        var got = 0L
        while (got < expectedSize) {
            val n = data.read(buf, 0, minOf(buf.size.toLong(), expectedSize - got).toInt())
            if (n < 0) throw EOFException("stream ended at $got of $expectedSize bytes")
            sink.write(buf, 0, n)
            mac.update(buf, 0, n)
            got += n
            onProgress(got)
        }
        sink.flush()
        val tag = ByteArray(TAG_BYTES).also(data::readFully)
        return MessageDigest.isEqual(mac.doFinal(), tag)
    }

    fun writeVerdict(
        out: OutputStream,
        ok: Boolean,
    ) {
        out.write((if (ok) VERDICT_OK else VERDICT_CORRUPT).toInt())
        out.flush()
    }

    /** The receiver's verdict, or null if the connection ended without one. */
    fun readVerdict(input: InputStream): Boolean? =
        when (input.read()) {
            VERDICT_OK.toInt() -> true
            VERDICT_CORRUPT.toInt() -> false
            else -> null
        }

    private fun header(
        id: String,
        size: Long,
    ): ByteArray {
        val idBytes = idBytes(id)
        val out = java.io.ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.write(MAGIC)
            it.writeByte(VERSION)
            it.writeByte(idBytes.size)
            it.write(idBytes)
            it.writeLong(size)
        }
        return out.toByteArray()
    }

    private fun idBytes(id: String): ByteArray {
        val bytes = id.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_ID_BYTES) { "transfer id must be 1..$MAX_ID_BYTES bytes" }
        return bytes
    }

    private fun mac(key: ByteArray): Mac {
        require(key.size == KEY_BYTES) { "transfer key must be $KEY_BYTES bytes" }
        return Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }
    }
}
