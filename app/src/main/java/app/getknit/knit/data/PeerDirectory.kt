package app.getknit.knit.data

import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.identity.PeerLabel
import app.getknit.knit.identity.PeerLabelIndex

/**
 * One emission of the cached peer table together with the collision-aware label index built over it
 * (plus this device's own name — see [PeerRepository.observeDirectory]). A ViewModel that used to
 * collect `observePeers()` collects this instead and resolves every name through [label], so two
 * peers who render to the same name come out as `Name (Alias)` on every surface at once (ADR 058).
 */
data class PeerDirectory(
    val peers: List<PeerEntity>,
    val labels: PeerLabelIndex,
) {
    /** The peer rows by node id — the `peersByNode` map every ViewModel used to build itself. */
    val byNode: Map<String, PeerEntity> by lazy { peers.associateBy { it.nodeId } }

    /**
     * The label for [nodeId]: a cached peer's stored name, our own name for our own id, or the alias for
     * an identity this device has never pinned — discriminated whenever another known identity renders
     * to the same name.
     */
    fun label(nodeId: String): PeerLabel {
        val row = byNode[nodeId]
        return if (row != null) labels.labelFor(nodeId, row.name) else labels.labelFor(nodeId)
    }

    companion object {
        /** No peers, nothing discriminated — the state before the first emission. */
        val EMPTY = PeerDirectory(emptyList(), PeerLabelIndex.EMPTY)
    }
}
