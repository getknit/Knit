package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.protocol.WireEnvelope
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compact re-encoding of a fast-path [WireEnvelope] for a size-capped side channel (the Wi-Fi Aware
 * coordination-plane `sendMessage`, ~255 B/message; the planned BLE extended-advertising analogue rides
 * the same format — knit/knit-next#13). **Transport-local, not a wire change**: only the outer envelope
 * (whose `ttl`/`hops`/`relay` are unsigned, mutable routing metadata every relayer already rewrites) is
 * re-framed; [WireEnvelope.sig] and [WireEnvelope.signed] pass through **byte-for-byte**, so the
 * originator's Ed25519 signature verifies unchanged at the endpoint. Senders emit these tags only
 * toward peers advertising `Protocol.CAP_FAST_COMPACT`; everyone else keeps the legacy tagged-CBOR
 * framing, which can represent any envelope.
 *
 * Compact frame ([TAG_COMPACT]):
 * ```
 * [0]    tag 0x03
 * [1]    flags: bit0 RELAY, bit1 DEFLATED, bits2-3 dictId (1 = DICT_V1), bits4-7 reserved (must be 0)
 * [2]    ttl (high nibble) / hops (low nibble), each saturated at 15 — legal values are ≤ DEFAULT_TTL=8,
 *        and saturating a hostile larger value can only tighten propagation, never loosen it
 * [3]    sig, 64 raw bytes (outside the deflate stream: 64 random bytes would only poison the
 *        Huffman histogram the compressible CBOR text needs)
 * [67]   signed — verbatim (DEFLATED clear) or raw-deflate `nowrap` with the preset dict (DEFLATED set;
 *        kept only when strictly smaller, so a compact frame never out-grows its stored form)
 * ```
 *
 * Fragment ([TAG_FRAG]) — the fit guarantee for frames past one message:
 * ```
 * [0]    tag 0x04
 * [1-2]  fragId, big-endian u16 (per-sender counter; one id per frame across all fan-out targets)
 * [3]    part index (high nibble) / part count (low nibble), count in 2..MAX_PARTS
 * [4]    a consecutive slice of the COMPLETE compact frame (its tag+header included), fixed-size
 *        chunks except the last — so reassembly yields a self-describing tagged unit fed back through
 *        [decodeCompact], and the receiver never needs to know the sender's chunk size
 * ```
 *
 * Tag registry (append-only, like capability bits): `0x01` legacy tagged-CBOR frame (forever), `0x02`
 * burned (a since-removed nudge), `0x03` compact, `0x04` fragment. Tags stay non-printable
 * (`0x00..0x1F`) so untagged cues — whose first byte is a printable node-id char — remain
 * distinguishable. [DICT_V1] is **frozen** once shipped (pinned by a SHA-256 golden test): a receiver
 * inflating with a different dictionary yields garbage that only dies later at decode/signature, so
 * post-ship tuning mints `DICT_V2` under a fresh dictId, never edits V1.
 *
 * Pure (no Android), so the codec is JVM-unit-testable ([app.getknit.knit.FastFrameCodecTest]).
 */
internal object FastFrameCodec {
    /** A single compact frame (layout above). */
    const val TAG_COMPACT: Byte = 0x03

    /** One fragment of a compact frame (layout above). */
    const val TAG_FRAG: Byte = 0x04

    // Reserved elsewhere in this tag space: 0x10 is `LoraCtl.TAG`, the LoRa plane's gateway-to-gateway
    // control packet (ADR 044). It never reaches this codec — it is dispatched before decode — but it shares
    // the first byte, so don't mint a frame tag over it.

    /** Most parts one frame may split into — bounds reassembly state and the loss-probability cost. */
    const val MAX_PARTS = 3

    /** Raw Ed25519 signature width; a [WireEnvelope] with any other [WireEnvelope.sig] size is unrepresentable. */
    const val SIG_BYTES = 64

    /** Compact fixed header: tag + flags + ttl/hops. */
    const val HEADER_BYTES = 3

    /** Fragment fixed header: tag + fragId(2) + part/count. */
    const val FRAG_HEADER_BYTES = 4

    /** The shipped preset dictionary's id (flags bits 2-3). 0 is reserved, never emitted. */
    const val DICT_ID_V1 = 1

    private const val FLAG_RELAY = 0x01
    private const val FLAG_DEFLATED = 0x02
    private const val DICT_SHIFT = 2
    private const val DICT_MASK = 0x03
    private const val RESERVED_MASK = 0xF0
    private const val NIBBLE_MAX = 15
    private const val NIBBLE_BITS = 4
    private const val BYTE_MASK = 0xFF

    /** Inflate output cap — a hostile tiny deflate stream can't balloon (largest honest frame ≪ this). */
    private const val MAX_INFLATED_BYTES = 8 * 1024

    /**
     * Preset deflate dictionary, version 1 — the CBOR key tokens (1-byte text header + UTF-8, matching
     * the on-wire bytes) of every frame field, ordered least-frequent first / hottest last (zlib scores
     * matches nearer the tail higher). FROZEN: see the class kdoc.
     */
    val DICT_V1: ByteArray = buildDictV1()

    /**
     * The one 0x03 frame for [wire] — deflated when that is strictly smaller, stored otherwise — or
     * null when [wire] is unrepresentable ([WireEnvelope.sig] not exactly [SIG_BYTES], e.g. the
     * unsigned blob-request form), in which case the caller falls back to legacy framing.
     */
    fun encodeCompact(wire: WireEnvelope): ByteArray? {
        if (wire.sig.size != SIG_BYTES) return null
        val deflated = deflateWithDict(wire.signed)
        val useDeflate = deflated.size < wire.signed.size
        val body = if (useDeflate) deflated else wire.signed
        var flags = 0
        if (wire.relay) flags = flags or FLAG_RELAY
        if (useDeflate) flags = flags or FLAG_DEFLATED or (DICT_ID_V1 shl DICT_SHIFT)
        val out = ByteArray(HEADER_BYTES + SIG_BYTES + body.size)
        out[0] = TAG_COMPACT
        out[1] = flags.toByte()
        out[2] = packTtlHops(wire.ttl, wire.hops)
        wire.sig.copyInto(out, HEADER_BYTES)
        body.copyInto(out, HEADER_BYTES + SIG_BYTES)
        return out
    }

    /**
     * The [WireEnvelope] a 0x03 message reconstructs, or null when it is malformed: wrong tag, shorter
     * than header + sig, a reserved flag bit set (an unknown future variant — the flood copy is the
     * backstop), an unknown dictId, or a deflate stream that fails to inflate.
     */
    fun decodeCompact(message: ByteArray): WireEnvelope? {
        if (message.size < HEADER_BYTES + SIG_BYTES + 1 || message[0] != TAG_COMPACT) return null
        val flags = message[1].toInt() and BYTE_MASK
        if (flags and RESERVED_MASK != 0) return null
        val sig = message.copyOfRange(HEADER_BYTES, HEADER_BYTES + SIG_BYTES)
        val body = message.copyOfRange(HEADER_BYTES + SIG_BYTES, message.size)
        val signed =
            if (flags and FLAG_DEFLATED != 0) {
                if ((flags shr DICT_SHIFT) and DICT_MASK != DICT_ID_V1) return null
                inflateWithDict(body) ?: return null
            } else {
                body
            }
        return WireEnvelope(
            ttl = (message[2].toInt() shr NIBBLE_BITS) and NIBBLE_MAX,
            hops = message[2].toInt() and NIBBLE_MAX,
            relay = flags and FLAG_RELAY != 0,
            sig = sig,
            signed = signed,
        )
    }

    /**
     * Splits a complete 0x03 frame into 0x04 messages of at most [maxMessage] bytes, or null when it
     * would take more than [MAX_PARTS]. Never called for a frame that already fits ([fragment] demands
     * ≥ 2 parts); chunks are fixed-size except the last, so the receiver reassembles by index alone.
     */
    fun fragment(
        compact: ByteArray,
        maxMessage: Int,
        fragId: Int,
    ): List<ByteArray>? {
        val chunk = maxMessage - FRAG_HEADER_BYTES
        if (chunk <= 0) return null
        val count = (compact.size + chunk - 1) / chunk
        if (count !in 2..MAX_PARTS) return null
        return List(count) { part ->
            val from = part * chunk
            val to = minOf(from + chunk, compact.size)
            byteArrayOf(
                TAG_FRAG,
                ((fragId shr Byte.SIZE_BITS) and BYTE_MASK).toByte(),
                (fragId and BYTE_MASK).toByte(),
                ((part shl NIBBLE_BITS) or count).toByte(),
            ) + compact.copyOfRange(from, to)
        }
    }

    /**
     * A parsed 0x04 message, or null when malformed: wrong tag, no payload, a count outside
     * 2..[MAX_PARTS], or a part index at/past the count.
     */
    fun parseFragment(message: ByteArray): Fragment? {
        if (message.size <= FRAG_HEADER_BYTES || message[0] != TAG_FRAG) return null
        val part = (message[3].toInt() shr NIBBLE_BITS) and NIBBLE_MAX
        val count = message[3].toInt() and NIBBLE_MAX
        if (count !in 2..MAX_PARTS || part >= count) return null
        val fragId = ((message[1].toInt() and BYTE_MASK) shl Byte.SIZE_BITS) or (message[2].toInt() and BYTE_MASK)
        return Fragment(fragId, part, count, message.copyOfRange(FRAG_HEADER_BYTES, message.size))
    }

    /**
     * One parsed fragment. A plain class (like [WireEnvelope]): a ByteArray field would make a data
     * class' equals/hashCode reference-based, so we don't pretend to value equality.
     */
    class Fragment(
        val fragId: Int,
        val part: Int,
        val count: Int,
        val payload: ByteArray,
    )

    /** [ttl]/[hops] packed to one byte, each nibble saturated at [NIBBLE_MAX] (kdoc: why that is safe). */
    private fun packTtlHops(
        ttl: Int,
        hops: Int,
    ): Byte {
        val t = ttl.coerceIn(0, NIBBLE_MAX)
        val h = hops.coerceIn(0, NIBBLE_MAX)
        return ((t shl NIBBLE_BITS) or h).toByte()
    }

    /** Raw-deflate [bytes] with the preset dictionary at best compression (frames are ≤ ~1 KB — µs work). */
    private fun deflateWithDict(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setDictionary(DICT_V1)
            deflater.setInput(bytes)
            deflater.finish()
            val buf = ByteArray(bytes.size + DEFLATE_SLACK)
            var len = 0
            while (!deflater.finished() && len < buf.size) {
                len += deflater.deflate(buf, len, buf.size - len)
            }
            // Not finished within input-size + slack ⇒ it expanded; report as such so stored wins.
            if (!deflater.finished()) return bytes
            return buf.copyOf(len)
        } finally {
            deflater.end()
        }
    }

    /** Inflates a raw-deflate [bytes] stream with the preset dictionary, or null if malformed/oversized. */
    private fun inflateWithDict(bytes: ByteArray): ByteArray? {
        val inflater = Inflater(true)
        try {
            // Raw (nowrap) streams take the preset dictionary up front; zlib-wrapped ones would signal it.
            inflater.setDictionary(DICT_V1)
            inflater.setInput(bytes)
            val buf = ByteArray(MAX_INFLATED_BYTES)
            var len = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(buf, len, buf.size - len)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                len += n
                if (len == buf.size && !inflater.finished()) return null // past the honest-frame cap
            }
            return buf.copyOf(len)
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    private const val DEFLATE_SLACK = 64

    /** A CBOR definite-length text token as it appears on the wire: 1-byte header (all keys ≤ 23 chars) + UTF-8. */
    private fun cborText(s: String): ByteArray {
        val utf8 = s.encodeToByteArray()
        check(utf8.size <= CBOR_TINY_TEXT_MAX) { "dict token too long for a 1-byte header: $s" }
        return byteArrayOf((CBOR_TEXT_BASE + utf8.size).toByte()) + utf8
    }

    private const val CBOR_TEXT_BASE = 0x60
    private const val CBOR_TINY_TEXT_MAX = 23

    /** See [DICT_V1]. Grouped rare → hot; the RelayEnvelope spine every frame carries goes last. */
    @Suppress("LongMethod") // a flat data list, one token per line — splitting it would scatter the ordering
    private fun buildDictV1(): ByteArray {
        val tokens =
            listOf(
                // Profile / group / request payload keys (rarer frames).
                "name",
                "status",
                "avatarHash",
                "pubKey",
                "deviceTag",
                "protoVersion",
                "capabilities",
                "prekey",
                "pkid",
                "version",
                "members",
                "createdBy",
                "groupId",
                "nodeIds",
                "hash",
                "photoHash",
                "photoUpdatedAt",
                "departed",
                "ackId",
                "messageId",
                "emoji",
                // Chat / crypto envelope keys.
                "body",
                "mentions",
                "attachmentHash",
                "attachmentMime",
                "replyTo",
                "enc",
                "nonce",
                "ct",
                "keys",
                "init",
                "eph",
                "at",
                "se",
                "ek",
                "pe",
                "n",
                "v",
                "r",
                "g",
                // Frame-type strings.
                "groupupdate",
                "groupleave",
                "blobreq",
                "keyreq",
                "typing",
                "profile",
                "reaction",
                "receipt",
                "chat",
                // The RelayEnvelope spine — on every frame, so hottest, so last.
                "type",
                "id",
                "senderId",
                "sentAt",
                "recipientId",
                "group",
                "payload",
            )
        return tokens.fold(ByteArray(0)) { acc, t -> acc + cborText(t) }
    }
}

/**
 * Reassembles [FastFrameCodec.TAG_FRAG] parts back into complete compact frames, keyed by [K] (the
 * transport's per-sender identity — e.g. the Wi-Fi Aware discovery session + peer handle) plus the
 * fragment id. Single-thread-confined by contract (the Wi-Fi Aware transport touches it only on its
 * one Aware callback thread), so it needs no locking; [now] is injected so the JVM tests drive time.
 *
 * Bounded on both axes: at most [capacity] in-flight entries (inserting past it evicts the oldest —
 * [Drop.OVERFLOW]) and a lazy [timeoutMs] sweep on every insert drops stale ones ([Drop.TIMEOUT]) —
 * no timer, because the whole map is ≤ [DEFAULT_CAPACITY] × ~765 B and an orphan parks harmlessly
 * until the next insert. Duplicate parts are idempotent; a re-announced fragId with a *different*
 * part count restarts its entry (the sender wrapped or restarted); completion removes the entry, so
 * a late duplicate part starts a fresh one that simply ages out. Losses are fine by design: this
 * plane is best-effort and the flood/custody path re-carries every reliable frame.
 *
 * Pure (no Android) — JVM-unit-testable ([app.getknit.knit.FragReassemblerTest]).
 */
internal class FragReassembler<K : Any>(
    private val now: () -> Long,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onDrop: (Drop) -> Unit = {},
) {
    /** Why an incomplete entry was discarded (surfaced as metrics by the transport). */
    enum class Drop { TIMEOUT, OVERFLOW }

    private class Entry(
        val count: Int,
        val firstAt: Long,
        val parts: Array<ByteArray?>,
    ) {
        fun complete(): Boolean = parts.all { it != null }

        fun assembled(): ByteArray = parts.fold(ByteArray(0)) { acc, p -> acc + checkNotNull(p) }
    }

    // Insertion-ordered so "evict the oldest" is the first key; entries are removed on completion.
    private val inFlight = LinkedHashMap<Pair<K, Int>, Entry>()

    /**
     * Absorbs one [frag] from [key]; returns the completed compact frame (and forgets the entry) once
     * every part is present, else null.
     */
    fun accept(
        key: K,
        frag: FastFrameCodec.Fragment,
    ): ByteArray? {
        sweep()
        val mapKey = key to frag.fragId
        var entry = inFlight[mapKey]
        if (entry == null || entry.count != frag.count) {
            entry = Entry(frag.count, now(), arrayOfNulls(frag.count))
            inFlight.remove(mapKey)
            if (inFlight.size >= capacity) {
                inFlight.remove(inFlight.keys.first())
                onDrop(Drop.OVERFLOW)
            }
            inFlight[mapKey] = entry
        }
        if (entry.parts[frag.part] == null) entry.parts[frag.part] = frag.payload
        if (!entry.complete()) return null
        inFlight.remove(mapKey)
        return entry.assembled()
    }

    /** Forgets everything (transport stop). */
    fun clear() = inFlight.clear()

    private fun sweep() {
        val cutoff = now() - timeoutMs
        val it = inFlight.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.firstAt < cutoff) {
                it.remove()
                onDrop(Drop.TIMEOUT)
            }
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 8
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
