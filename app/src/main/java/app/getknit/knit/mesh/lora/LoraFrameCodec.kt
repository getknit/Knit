package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * Encodes a mesh [WireEnvelope] into one-or-more Meshtastic `Data.payload`s (≤ [MeshtasticProto.MAX_PAYLOAD]
 * bytes each) for the LoRa hop, reusing the transport-neutral [FastFrameCodec] the Wi-Fi Aware fast plane
 * already uses (ADR 030). Only the outer envelope is re-framed; `sig`/`signed` pass through byte-exact, so
 * the originator's Ed25519 signature verifies unchanged at the far endpoint — this is **not** a wire change.
 *
 * A frame that is unrepresentable (an unsigned blob-request, sig ≠ 64 B) or too large to fit
 * [FastFrameCodec.MAX_PARTS] fragments yields null; the caller counts it (`loraTooBig`) and the frame rides
 * the radios and store-and-forward instead.
 *
 * One packet may leave here **larger** than the frame needs (ADR 2026-09.mhs5): a Meshtastic 2.8 board signs any
 * packet small enough to still fit a 66-byte signature, so a packet just under that cliff is padded past it
 * and comes out cheaper on the air than it went in. It happens only where the receiver provably ignores the
 * extra bytes — a deflated body ([FastFrameCodec.deflated]) — and only to the last packet.
 */

internal object LoraFrameCodec {
    /** Smallest per-packet payload worth splitting to — below this a frame becomes `loraTooBig` instead. */
    const val MIN_PAYLOAD = 64

    /**
     * Compact-encodes [wire], splitting into ≤3 fragments sharing [fragId] when one message won't hold it.
     * [maxPayload] is the largest `Data.payload` a single packet may carry — capped at the protocol limit
     * ([MeshtasticProto.MAX_PAYLOAD] = 231, the firmware's on-air limit less the private-portnum framing) but
     * sized DOWN to the board's negotiated BLE MTU by the caller, so
     * a full fragment's `ToRadio` write fits one ATT operation (many ESP32 boards negotiate MTU 255, whose
     * ~252-byte usable write can't hold a 233-byte-payload packet — the "dial mtu 255" reconnect loop).
     */
    fun encode(
        wire: WireEnvelope,
        fragId: Int,
        maxPayload: Int = MeshtasticProto.MAX_PAYLOAD,
        transcode: Boolean = false,
        cost: PacketCost? = null,
    ): List<ByteArray>? = encodeBest(wire, fragId, maxPayload, transcode, cost)?.parts

    /**
     * [encode] plus what it chose: with [transcode], the `0x05` form when it is the smaller (ADR 060 — the
     * one-packet form for a signed tick), else `0x03`. [Encoded.transcodeRefused] flags a frame the transcoder
     * could not reproduce, which rode `0x03` for that reason.
     *
     * [cost] is the caller's airtime model ([LoraAirtime]). Given one, a frame that would land under a
     * Meshtastic 2.8 board's signature cliff is grown past it instead (ADR 2026-09.mhs5) — the board charges 66 bytes
     * for signing anything smaller, so a few bytes of pad are cheaper than the signature they avoid. Null by
     * default, so a caller with no board to price against gets exactly today's encoding.
     */
    fun encodeBest(
        wire: WireEnvelope,
        fragId: Int,
        maxPayload: Int = MeshtasticProto.MAX_PAYLOAD,
        transcode: Boolean = false,
        cost: PacketCost? = null,
    ): Encoded? {
        val cap = maxPayload.coerceIn(MIN_PAYLOAD, MeshtasticProto.MAX_PAYLOAD)
        val best = FastFrameCodec.encodeBest(wire, transcode) ?: return null
        val frame = if (cost == null) best.frame else paddable(best.frame, cap, cost)
        val parts = if (frame.size <= cap) listOf(frame) else FastFrameCodec.fragment(frame, cap, fragId) ?: return null
        val padded = if (cost != null && FastFrameCodec.deflated(frame)) pad(parts, cap, cost) else parts
        return Encoded(
            padded,
            transcoded = best.transcoded,
            transcodeRefused = best.transcodeRefused,
            grewBy = (padded.last().size - parts.last().size) + (frame.size - best.frame.size),
        )
    }

    /**
     * [frame], or — when that is cheaper on the air — its **deflated** equivalent.
     *
     * A stored body cannot be padded (a receiver would read the pad as part of `signed`), and the frames that
     * most want padding are exactly the ones that store: the transcoder has already thrown away the
     * compressible CBOR keys, so a sealed one-packet tick has nothing left to deflate. Re-encoding it as a
     * deflate stream anyway costs a measured **5 bytes** of framing and makes it paddable, which buys back the
     * board's 66. Priced, never assumed: [FastFrameCodec.deflatedForm] is taken only when [cost] agrees the
     * padded result is cheaper than the stored original, so a frame with nothing to gain is left alone.
     *
     * Single-packet frames only. Re-deflating a fragmented frame would change its size and could change the
     * part count, and its non-final packets are full-size and past the cliff already.
     */
    private fun paddable(
        frame: ByteArray,
        cap: Int,
        cost: PacketCost,
    ): ByteArray {
        if (frame.size > cap || FastFrameCodec.deflated(frame)) return frame
        val stream = FastFrameCodec.deflatedForm(frame) ?: return frame
        if (stream.size > cap) return frame
        return if (cost.timeOnAirMs(cost.padTo(stream.size, cap)) < cost.timeOnAirMs(frame.size)) stream else frame
    }

    /**
     * [parts] with its **last** packet grown to whatever [cost] prices cheaper, or [parts] unchanged.
     *
     * The last one is the only one worth touching: every earlier packet is a full [cap]-sized chunk, already
     * far past the cliff. Padding only the tail is also what keeps this free of consequences — the fragment
     * count cannot change, so nothing re-derives, and the extra bytes arrive at the far side as trailing
     * bytes of the reassembled frame, which `FastFrameCodec.decodeCompact` ignores for a deflated body (the
     * caller has already checked that; [FastFrameCodec.deflated] says what a *stored* body does with them).
     */
    private fun pad(
        parts: List<ByteArray>,
        cap: Int,
        cost: PacketCost,
    ): List<ByteArray> {
        val last = parts.last()
        val target = cost.padTo(last.size, cap).coerceAtMost(cap)
        if (target <= last.size) return parts
        return parts.dropLast(1) + last.copyOf(target)
    }

    /** The packets for one frame and the form they took. */
    class Encoded(
        val parts: List<ByteArray>,
        val transcoded: Boolean,
        val transcodeRefused: Boolean,
        /**
         * Bytes this frame grew by to dodge the firmware's signature (ADR 2026-09.mhs5) — deflate framing and pad
         * together; 0 when it was left as it encoded. Always a saving: it is only ever spent when the priced
         * result is cheaper on the air than the frame it replaced.
         */
        val grewBy: Int = 0,
    )
}
