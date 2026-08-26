package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.link.FastFrameCodec

/**
 * The LoRa plane's own control packet — a message between *gateways*, carrying no mesh frame and never
 * reaching the router.
 *
 * It rides the same `Data.payload` space as a compacted frame and is told apart by its first byte, the way
 * [FastFrameCodec] tells a compact frame ([FastFrameCodec.TAG_COMPACT]) from a fragment
 * ([FastFrameCodec.TAG_FRAG]). Because `LoraMeshTransport` already drops an unrecognised first byte as
 * `FastPathDrop.UNKNOWN_TAG`, **a build that predates this tag ignores the packet and carries on** — which
 * is what makes the whole bridge additive. It is emphatically **not** a wire change: no mesh frame type,
 * field, ctl code or capability bit moves, and `sig`/`signed` still cross byte-exact.
 *
 * ## The OFFER
 *
 * One kind for now: "here is what I am holding". The body is a list of 4-byte prefixes of the **frame ids**
 * in the publisher's live custody set (`ForwardStore.liveIds`), so a far gateway can compute what we lack
 * and serve exactly that — one packet of air instead of blind re-transmission, and no request round trip.
 *
 * Prefixes are the top 32 bits of `StoreDigest.hash64(id)`, reusing the mesh's existing id hash. A prefix
 * collision (~1 in 4·10⁹ per pair) means one frame looks present when it is not and is skipped for a round;
 * custody churn renames the set next time. That is the deliberate trade for fitting a useful window into one
 * packet — the frames themselves are Ed25519-signed, so nothing here is a trust boundary.
 *
 * ```
 * [0]      TAG (0x10)
 * [1]      VERSION (1)
 * [2]      kind (1 = OFFER)
 * [3..10]  publisher: the low 8 bytes of StoreDigest.hash64(nodeId), big-endian
 * [11..12] count, big-endian u16
 * [13..]   count x 4-byte id prefixes, big-endian
 * ```
 *
 * **Never fragmented.** A control packet that needs reassembly to be useful is worse than a shorter one, so
 * [encodeOffer] truncates the list to what fits ([capacityFor]) instead. Decoding is total — malformed input
 * yields null and never throws, like every other codec on this plane.
 */
internal object LoraCtl {
    /** The first byte that marks a LoRa control packet. Deliberately clear of [FastFrameCodec]'s 0x03/0x04. */
    const val TAG: Byte = 0x10

    const val VERSION: Byte = 0x01

    const val KIND_OFFER: Byte = 0x01

    const val HEADER_BYTES = 13

    const val PREFIX_BYTES = 4

    /**
     * The most prefixes an OFFER will ever carry, whatever the payload allows. A window, not a set: a pocket
     * busier than this under-reports its oldest frames, which the next round's changed set usually covers.
     */
    const val MAX_PREFIXES = 48

    /** A decoded OFFER: who published it, and the id prefixes they hold. */
    data class Offer(
        val publisher: Long,
        val prefixes: IntArray,
    ) {
        // Value semantics over the array, so tests and the transport's bookkeeping compare by content.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Offer && publisher == other.publisher && prefixes.contentEquals(other.prefixes))

        override fun hashCode(): Int = 31 * publisher.hashCode() + prefixes.contentHashCode()
    }

    /** Whether [payload] is a LoRa control packet (rather than a compacted frame or a fragment). */
    fun isCtl(payload: ByteArray): Boolean = payload.isNotEmpty() && payload[0] == TAG

    /** How many prefixes fit one packet of [maxPayload] bytes, after the header and the [MAX_PREFIXES] cap. */
    fun capacityFor(maxPayload: Int): Int = ((maxPayload - HEADER_BYTES) / PREFIX_BYTES).coerceIn(0, MAX_PREFIXES)

    /**
     * Encodes an OFFER from [publisher] over [prefixes], truncating to what [maxPayload] holds. Callers pass
     * prefixes newest-first, so a truncation drops the oldest — the frames most likely to have propagated
     * already, and the ones custody will expire first anyway.
     */
    fun encodeOffer(
        publisher: Long,
        prefixes: IntArray,
        maxPayload: Int,
    ): ByteArray {
        val count = minOf(prefixes.size, capacityFor(maxPayload))
        val out = ByteArray(HEADER_BYTES + count * PREFIX_BYTES)
        out[0] = TAG
        out[1] = VERSION
        out[2] = KIND_OFFER
        putLong(out, PUBLISHER_OFFSET, publisher)
        out[COUNT_OFFSET] = (count ushr Byte.SIZE_BITS).toByte()
        out[COUNT_OFFSET + 1] = count.toByte()
        // Sorted so a receiver can binary-search, and so two offers over the same set are byte-identical —
        // which is what lets the gossip policy recognise a peer's transmission as covering our own.
        val sorted = prefixes.copyOf(count).sortedArray()
        for (i in 0 until count) putInt(out, HEADER_BYTES + i * PREFIX_BYTES, sorted[i])
        return out
    }

    /** Decodes an OFFER, or null when [payload] is not one or is malformed. Never throws. */
    fun decodeOffer(payload: ByteArray): Offer? {
        if (payload.size < HEADER_BYTES) return null
        if (payload[0] != TAG || payload[1] != VERSION || payload[2] != KIND_OFFER) return null
        val count = (u8(payload[COUNT_OFFSET]) shl Byte.SIZE_BITS) or u8(payload[COUNT_OFFSET + 1])

        if (payload.size < HEADER_BYTES + count * PREFIX_BYTES) return null
        val prefixes = IntArray(count) { readInt(payload, HEADER_BYTES + it * PREFIX_BYTES) }
        return Offer(readLong(payload, PUBLISHER_OFFSET), prefixes)
    }

    /** The 4-byte prefix of a frame id: the top 32 bits of the mesh's own 64-bit id hash. */
    fun prefixOf(hash64: Long): Int = (hash64 ushr Int.SIZE_BITS).toInt()

    private const val PUBLISHER_OFFSET = 3
    private const val COUNT_OFFSET = 11

    private const val BYTE_MASK = 0xFF

    private fun u8(b: Byte): Int = b.toInt() and BYTE_MASK

    private fun putInt(
        out: ByteArray,
        at: Int,
        value: Int,
    ) {
        for (i in 0 until Int.SIZE_BYTES) out[at + i] = (value ushr (Byte.SIZE_BITS * (Int.SIZE_BYTES - 1 - i))).toByte()
    }

    private fun putLong(
        out: ByteArray,
        at: Int,
        value: Long,
    ) {
        for (i in 0 until Long.SIZE_BYTES) out[at + i] = (value ushr (Byte.SIZE_BITS * (Long.SIZE_BYTES - 1 - i))).toByte()
    }

    private fun readInt(
        bytes: ByteArray,
        at: Int,
    ): Int {
        var v = 0
        for (i in 0 until Int.SIZE_BYTES) v = (v shl Byte.SIZE_BITS) or u8(bytes[at + i])
        return v
    }

    private fun readLong(
        bytes: ByteArray,
        at: Int,
    ): Long {
        var v = 0L
        for (i in 0 until Long.SIZE_BYTES) v = (v shl Byte.SIZE_BITS) or u8(bytes[at + i]).toLong()
        return v
    }
}
