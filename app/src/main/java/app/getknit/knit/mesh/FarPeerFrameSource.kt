package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * Supplies the carried frames worth re-offering to a peer a long-range plane
 * ([app.getknit.knit.mesh.lora.LoraMeshTransport]) has just heard for the first time (ADR 039). That plane
 * has no custody sync — `neighbors` is always empty, so `ForwardSync`'s digest exchange never runs over it —
 * and a DM sent while the peer's board was off is otherwise lost to it until radio or spool contact.
 * Implemented by [MeshManager] as the newest few live custody frames addressed to the peer, minus our own
 * already-acked ones, re-wrapped verbatim like a custody re-serve. Late-bound (the transport is constructed
 * before [MeshManager]); resolved at call time. Sibling of [ProfileFrameSource].
 */
fun interface FarPeerFrameSource {
    /** Signed frames to re-offer to [nodeId], newest first; empty when nothing is worth an airslot. */
    suspend fun framesFor(nodeId: String): List<WireEnvelope>
}
