package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.protocol.Protocol

/**
 * Which on-air framing a fast frame takes toward one peer, from the capability bits its advert copy carries —
 * a transport-local routing hint, never a trust input (every receiver accepts every tag): the transcoded
 * form (`0x05`, or `0x03` when that is smaller) toward a `Protocol.CAP_FRAME_TRANSCODE` peer, the compact
 * `0x03`/`0x04` form toward a `Protocol.CAP_FAST_COMPACT` peer, and the legacy `0x01` framing — which can
 * represent any envelope — toward everyone else, with each richer form falling back to the next when it
 * cannot carry the frame. Pure, so the choice the Wi-Fi Aware transport makes per peer is testable without
 * a radio; the encodings are passed lazily because a frame is encoded once per fan-out, not per peer.
 */
internal object FastFramePick {
    /** The framing a message list is in, read off its first byte (a fragment's first slice starts with the whole frame's tag). */
    enum class Form { LEGACY, COMPACT, TRANSCODED }

    class Choice(
        val messages: List<ByteArray>,
        val form: Form,
    )

    fun choose(
        caps: Long,
        transcoded: () -> List<ByteArray>?,
        compact: () -> List<ByteArray>?,
        legacy: () -> List<ByteArray>?,
    ): Choice? {
        val messages =
            when {
                caps and Protocol.CAP_FRAME_TRANSCODE != 0L -> transcoded() ?: compact() ?: legacy()
                caps and Protocol.CAP_FAST_COMPACT != 0L -> compact() ?: legacy()
                else -> legacy()
            } ?: return null
        return Choice(messages, formOf(messages))
    }

    fun formOf(messages: List<ByteArray>): Form {
        val first = messages.first()
        val tag = if (first[0] == FastFrameCodec.TAG_FRAG) first[FastFrameCodec.FRAG_HEADER_BYTES] else first[0]
        return when (tag) {
            FastFrameCodec.TAG_TRANSCODED -> Form.TRANSCODED
            FastFrameCodec.TAG_COMPACT -> Form.COMPACT
            else -> Form.LEGACY
        }
    }

    /**
     * Counts one send of [choice]: legacy, transcoded (however many parts), or a single compact message — plus
     * a fragmented send whenever a compact-family frame took more than one message.
     */
    fun record(
        choice: Choice,
        metrics: MeshMetrics,
    ) {
        when (choice.form) {
            Form.LEGACY -> metrics.onFastLegacySent()
            Form.TRANSCODED -> metrics.onFastTranscodedSent()
            Form.COMPACT -> if (choice.messages.size == 1) metrics.onFastCompactSent()
        }
        if (choice.form != Form.LEGACY && choice.messages.size > 1) metrics.onFastFragSent()
    }
}
