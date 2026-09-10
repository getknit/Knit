package app.getknit.knit.transfer

import com.google.crypto.tink.subtle.Hkdf
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The byte protocol of a direct file transfer over one TCP connection — pure, transport-neutral and
 * JVM-tested. Framing, in order:
 *
 * 1. The client's proof, sent first by whoever connected: `idLen(u8) ‖ id ‖ HMAC-SHA256(macKey, "client" ‖ id)`.
 *    The listener reads it before writing a byte, so a stray connection on the port (the sender's LAN can
 *    reach it) never receives the file.
 * 2. The header: `"KNXF" ‖ version(u8) ‖ idLen(u8) ‖ id ‖ size(i64)`, in the clear — it names a transfer the
 *    receiver already agreed to over the mesh, so it carries nothing the receiver did not already know.
 * 3. One or more sealed chunks, `plainLen(u32be) ‖ AES-256-GCM(encKey, nonce, plaintext, aad) ‖ tag(16)`,
 *    covering exactly `size` plaintext bytes. Always at least one, so a zero-byte file still ends with a
 *    chunk marked final.
 * 4. A trailer `HMAC-SHA256(macKey, header ‖ every sealed chunk as written)`.
 * 5. One verdict byte from the receiver ([VERDICT_OK] / [VERDICT_CORRUPT]), so the sender's record can say
 *    what actually landed.
 *
 * ### The seal
 *
 * The link is already WPA2 with a single-use passphrase, so this is the second lock rather than the only one:
 * it puts the file out of reach of anything that gets past the Wi-Fi layer, and of a supplicant bug in either
 * phone. [key] is the per-transfer 32-byte secret the sealed READY carried, and is never used directly —
 * HKDF-SHA256 splits it into an `encKey` for the chunks and a `macKey` for the proof and the trailer, so no
 * one key does two jobs.
 *
 * The nonce is the chunk index, which is safe precisely because `encKey` is derived from a secret minted for
 * one transfer and never reused. Each chunk's AAD is `header ‖ index(u64be) ‖ final(u8)`, and **the receiver
 * supplies that AAD from its own arithmetic rather than reading it off the wire** — so a chunk cannot be
 * reordered, replayed into another transfer, or dropped to truncate the file without failing its tag. The
 * receiver therefore writes only bytes it has already authenticated.
 *
 * The trailer is deliberately kept on top of that. It costs one HMAC pass over the ciphertext at a fraction
 * of the link's throughput, it is what the verdict byte reports, and it means a transfer that fails does so
 * as a stated outcome rather than an exception.
 *
 * The version rides in the header, which the receiver compares whole, so two builds that disagree about this
 * format fail the transfer rather than misread each other.
 *
 * Blocking I/O throughout — callers run it on an I/O dispatcher and cancel by closing the socket, which
 * surfaces here as an [IOException].
 */
object TransferStream {
    const val VERSION = 2
    const val KEY_BYTES = 32
    const val CHUNK_BYTES = 256 * 1024
    const val VERDICT_OK: Byte = 1
    const val VERDICT_CORRUPT: Byte = 0

    private val MAGIC = "KNXF".toByteArray(Charsets.US_ASCII)
    private const val TAG_BYTES = 32
    private const val MAX_ID_BYTES = 64
    private const val HMAC = "HmacSHA256"
    private const val CLIENT_LABEL = "client"

    /** Tink spells the same primitive without the dash; [HMAC] is the JCE name. */
    private const val HKDF_MAC = "HMACSHA256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val NONCE_BYTES = 12
    private const val COUNTER_BYTES = 8
    private const val BITS_PER_BYTE = 8
    private const val OKM_BYTES = KEY_BYTES * 2
    private val ZERO_SALT = ByteArray(KEY_BYTES)

    /** Domain separation: disjoint from `knit/scope/v1/…`, `knit/dm/v2/…` and `knit/group/v1/…`. */
    private val STREAM_LABEL = "knit/xfer/v2/stream".toByteArray(Charsets.US_ASCII)

    /** The bytes the connecting side writes first (framing item 1). */
    fun clientProof(
        key: ByteArray,
        id: String,
    ): ByteArray {
        val idBytes = idBytes(id)
        val mac = mac(keys(key).mac)
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
     * Seals [size] bytes of [source] to [out] under the framing above. [onProgress] is called with the running
     * count of **plaintext** bytes after every chunk. Throws [IOException] if [source] ends early or [out] fails.
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
        val keys = keys(key)
        val mac = mac(keys.mac)
        val header = header(id, size)
        mac.update(header)
        data.write(header)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val plain = ByteArray(CHUNK_BYTES)
        val sealed = ByteArray(CHUNK_BYTES + GCM_TAG_BYTES)
        var sent = 0L
        var index = 0L
        while (true) {
            val want = minOf(plain.size.toLong(), size - sent).toInt()
            fill(source, plain, want, sent, size)
            val last = sent + want >= size
            cipher.start(Cipher.ENCRYPT_MODE, keys.enc, index, header, last)
            val n = cipher.doFinal(plain, 0, want, sealed, 0)
            data.writeInt(want)
            data.write(sealed, 0, n)
            mac.update(sealed, 0, n)
            sent += want
            index += 1
            onProgress(sent)
            if (last) break
        }
        data.write(mac.doFinal())
        data.flush()
    }

    /**
     * Reads one transfer off [input] into [sink]. True when the header named [expectedId]/[expectedSize], every
     * chunk opened under [key] in its own place, and the trailer verified; false on any mismatch — the caller
     * then discards what [sink] holds. A stream that ends early throws [IOException].
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
        val keys = keys(key)
        val expectedHeader = header(expectedId, expectedSize)
        val header = ByteArray(expectedHeader.size).also(data::readFully)
        if (!header.contentEquals(expectedHeader)) return false
        val mac = mac(keys.mac)
        mac.update(header)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val plain = ByteArray(CHUNK_BYTES)
        val sealed = ByteArray(CHUNK_BYTES + GCM_TAG_BYTES)
        var got = 0L
        var index = 0L
        while (true) {
            val len = data.readInt()
            // Bounded before it is trusted: the buffers are one chunk wide, and a chunk that claims more
            // plaintext than the transfer has left is refused rather than opened.
            if (len < 0 || len > CHUNK_BYTES || got + len > expectedSize) return false
            data.readFully(sealed, 0, len + GCM_TAG_BYTES)
            mac.update(sealed, 0, len + GCM_TAG_BYTES)
            val last = got + len >= expectedSize
            cipher.start(Cipher.DECRYPT_MODE, keys.enc, index, header, last)
            val n =
                try {
                    cipher.doFinal(sealed, 0, len + GCM_TAG_BYTES, plain, 0)
                } catch (_: AEADBadTagException) {
                    return false
                }
            sink.write(plain, 0, n)
            got += n
            index += 1
            onProgress(got)
            if (last) break
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

    /** The two keys one transfer secret expands to; neither is the secret itself, and neither does the other's job. */
    private class StreamKeys(
        val enc: ByteArray,
        val mac: ByteArray,
    )

    private fun keys(key: ByteArray): StreamKeys {
        require(key.size == KEY_BYTES) { "transfer key must be $KEY_BYTES bytes" }
        val okm = Hkdf.computeHkdf(HKDF_MAC, key, ZERO_SALT, STREAM_LABEL, OKM_BYTES)
        return StreamKeys(okm.copyOfRange(0, KEY_BYTES), okm.copyOfRange(KEY_BYTES, OKM_BYTES))
    }

    /** Re-arms [this] for one chunk: its own nonce, and the AAD that pins the chunk to its place in the stream. */
    private fun Cipher.start(
        mode: Int,
        encKey: ByteArray,
        index: Long,
        header: ByteArray,
        last: Boolean,
    ) {
        init(mode, SecretKeySpec(encKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce(index)))
        updateAAD(aad(header, index, last))
    }

    /** Zero-padded big-endian chunk index. Unique per chunk, and the key it is used under is unique per transfer. */
    private fun nonce(index: Long): ByteArray = ByteArray(NONCE_BYTES).also { beLong(index, it, NONCE_BYTES - COUNTER_BYTES) }

    /** `header ‖ index(u64be) ‖ final(u8)` — what makes a chunk mean only itself, in only this transfer. */
    private fun aad(
        header: ByteArray,
        index: Long,
        last: Boolean,
    ): ByteArray {
        val out = ByteArray(header.size + COUNTER_BYTES + 1)
        header.copyInto(out)
        beLong(index, out, header.size)
        out[out.size - 1] = if (last) 1 else 0
        return out
    }

    private fun beLong(
        value: Long,
        into: ByteArray,
        at: Int,
    ) {
        for (i in 0 until COUNTER_BYTES) into[at + COUNTER_BYTES - 1 - i] = (value ushr (BITS_PER_BYTE * i)).toByte()
    }

    /** Reads exactly [want] bytes into [buf]; a source that ends first is a failed transfer, not a short chunk. */
    private fun fill(
        source: InputStream,
        buf: ByteArray,
        want: Int,
        sent: Long,
        size: Long,
    ) {
        var filled = 0
        while (filled < want) {
            val n = source.read(buf, filled, want - filled)
            if (n < 0) throw EOFException("source ended at ${sent + filled} of $size bytes")
            filled += n
        }
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

    private fun mac(macKey: ByteArray): Mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(macKey, HMAC)) }
}
