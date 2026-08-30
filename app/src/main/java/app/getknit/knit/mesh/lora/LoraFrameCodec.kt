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
    ): List<ByteArray>? = encodeBest(wire, fragId, maxPayload, transcode)?.parts

    /**
     * [encode] plus what it chose: with [transcode], the `0x05` form when it is the smaller (ADR 060 — the
     * one-packet form for a signed tick), else `0x03`. [Encoded.transcodeRefused] flags a frame the transcoder
     * could not reproduce, which rode `0x03` for that reason.
     */
    fun encodeBest(
        wire: WireEnvelope,
        fragId: Int,
        maxPayload: Int = MeshtasticProto.MAX_PAYLOAD,
        transcode: Boolean = false,
    ): Encoded? {
        val cap = maxPayload.coerceIn(MIN_PAYLOAD, MeshtasticProto.MAX_PAYLOAD)
        val best = FastFrameCodec.encodeBest(wire, transcode) ?: return null
        val parts = if (best.frame.size <= cap) listOf(best.frame) else FastFrameCodec.fragment(best.frame, cap, fragId) ?: return null
        return Encoded(parts, transcoded = best.transcoded, transcodeRefused = best.transcodeRefused)
    }

    /** The packets for one frame and the form they took. */
    class Encoded(
        val parts: List<ByteArray>,
        val transcoded: Boolean,
        val transcodeRefused: Boolean,
    )
}
