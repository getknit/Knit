package app.getknit.knit.mesh.lora

/**
 * Hands out `MeshPacket.id`s: a random 32-bit start, then +1 each call, never 0. The Meshtastic Android
 * app assigns ids client-side for the same reason — so a `queueStatus.mesh_packet_id` and a ROUTING_APP
 * NAK's `request_id` correlate back to the send deterministically, on every firmware version (a board
 * that assigns its own id when we send 0 would break that correlation). Not thread-safe by design: it is
 * driven only from the single [MeshtasticSession] actor coroutine.
 */
internal class PacketIdSource(
    seed: Long,
) {
    private var next: UInt = seed.toUInt().let { if (it == 0u) 1u else it }

    fun next(): UInt {
        val id = next
        next = (next + 1u).let { if (it == 0u) 1u else it }
        return id
    }
}
