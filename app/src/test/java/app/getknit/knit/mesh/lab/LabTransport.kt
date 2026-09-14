package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The in-process radio behind a [LabNode]: a [MeshTransport] whose links are other [LabTransport]s in the same
 * JVM. Like `FakeLoopTransport` (which the single-SUT rigs use) it has no fast plane, so every originated frame
 * floods over `send(wire, null)` and every custody re-serve unicasts over `send(wire, peer)` — the routes the
 * BLE plane takes. What it adds is a **directed, holdable pipe per link**: a scenario can [hold] what one side
 * sends the other and [release] it in an order of its choosing, which is how "custody serves the two in either
 * order" becomes a deterministic case rather than a lucky one.
 */
class LabTransport(
    val nodeId: String,
    /**
     * Where a file *sent to this node* is staged before the blob store ingests it. The real transports write
     * socket bytes into the receiver's own cache; the lab copies the sender's temp file here for the same
     * reason — `MeshBlobStore.saveIncoming` deletes what it reads, and two receivers of one blob handed the
     * sender's single path would race each other for it (the loser drops the blob silently).
     */
    private val stagingDir: File,
) : MeshTransport {
    private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
    override val neighbors = _neighbors.asStateFlow()

    override val health = MutableStateFlow(TransportHealth.Healthy).asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = BUFFER)
    override val inbound = _inbound.asSharedFlow()

    private val _incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = BUFFER)
    override val incomingFiles = _incomingFiles.asSharedFlow()

    private val _incomingDigests = MutableSharedFlow<ReceivedDigest>(extraBufferCapacity = BUFFER)
    override val incomingDigests = _incomingDigests.asSharedFlow()

    /** Outbound pipes, keyed by the far node id. */
    private val pipes = linkedMapOf<String, Pipe>()

    /**
     * One direction of a link: frames from this transport toward [target], held while [holding], and dropped
     * on the floor when [lossy] says so (a radio that lost the packet — nothing is parked, nothing is told).
     */
    private class Pipe(
        val target: LabTransport,
    ) {
        @Volatile
        var holding = false
        val held = mutableListOf<WireEnvelope>()

        @Volatile
        var lossy: (WireEnvelope) -> Boolean = { false }
    }

    /** Every frame a lossy pipe dropped, for a scenario that asserts on what the air ate. */
    val lost = CopyOnWriteArrayList<WireEnvelope>()

    /** Every frame this transport handed a peer, as `to type id` in send order — the diagnosis of "who served that". */
    val sent = CopyOnWriteArrayList<String>()

    /**
     * Links both ways so the two become neighbors, as a data path coming up does. With [publish] false the
     * pipes exist but neither side's `neighbors` moves yet — [publishNeighbors] does that — so a topology of
     * several links can come up at once (see [MeshLab.linkAll]).
     */
    fun connect(
        other: LabTransport,
        publish: Boolean = true,
    ) {
        if (other.nodeId == nodeId) return
        pipes[other.nodeId] = Pipe(other)
        other.pipes[nodeId] = Pipe(this)
        if (publish) {
            publishNeighbors()
            other.publishNeighbors()
        }
    }

    /** Publishes the current link set as `neighbors`, which is what starts the profile push on the far side. */
    fun publishNeighbors() = refreshNeighbors()

    /** Unlinks both ways (out of range). Held frames on that link are dropped, as a torn-down link drops them. */
    fun disconnect(other: LabTransport) {
        pipes.remove(other.nodeId)
        other.pipes.remove(nodeId)
        refreshNeighbors()
        other.refreshNeighbors()
    }

    fun disconnectAll() {
        pipes.values.map { it.target }.forEach { disconnect(it) }
    }

    /** From now on, frames this node sends [to] are parked instead of delivered — until [release]. */
    fun hold(to: LabTransport) {
        pipe(to).holding = true
    }

    /**
     * From now on, a frame this node sends [to] for which [drop] answers true is lost — not parked, not
     * delivered, recorded in [lost]. The default answers false, so `lossy(to)` restores a clean link. Judged
     * before [hold], so a held pipe still loses what it would have lost.
     */
    fun lossy(
        to: LabTransport,
        drop: (WireEnvelope) -> Boolean = { false },
    ) {
        pipe(to).lossy = drop
    }

    /** What is parked for [to] right now, in send order — for a scenario that waits for a frame to be held. */
    fun held(to: LabTransport): List<WireEnvelope> = pipe(to).let { synchronized(it.held) { it.held.toList() } }

    /**
     * Delivers everything parked for [to], in the order [reorder] returns (default: as sent), and stops holding.
     * Returns what was released, for a scenario that wants to assert on the frames themselves.
     */
    suspend fun release(
        to: LabTransport,
        reorder: (List<WireEnvelope>) -> List<WireEnvelope> = { it },
    ): List<WireEnvelope> {
        val pipe = pipe(to)
        val batch = synchronized(pipe.held) { pipe.held.toList().also { pipe.held.clear() } }
        pipe.holding = false
        val ordered = reorder(batch)
        ordered.forEach { pipe.target.deliver(it, nodeId) }
        return ordered
    }

    /**
     * Whether the node's router is collecting [inbound] yet. `MeshRouter.start` subscribes on
     * `Dispatchers.Default`, and a [MutableSharedFlow] with no replay drops what is emitted before that — so
     * a link brought up too early would lose the profile push. [MeshLab.node] waits on this.
     */
    val collecting: Boolean get() = _inbound.subscriptionCount.value > 0

    override fun start() = Unit

    override fun stop() = Unit

    override fun heal() = Unit

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        val targets = if (to == null) pipes.values.toList() else listOfNotNull(pipes[to.nodeId])
        targets.forEach { pipe ->
            when {
                pipe.lossy(wire) -> lost += wire
                pipe.holding -> synchronized(pipe.held) { pipe.held += wire }
                else -> pipe.target.deliver(wire, nodeId)
            }
        }
    }

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean {
        val target = pipes[to.nodeId]?.target ?: return false
        // The receiver ingests and then deletes the staged copy; it must be the receiver's own copy.
        val staged = File(target.stagingDir.apply { mkdirs() }, "${meta.key}-${UUID.randomUUID()}")
        file.copyTo(staged, overwrite = true)
        target._incomingFiles.emit(ReceivedFile(nodeId, staged.absolutePath, meta.kind, meta.key, meta.mime))
        return true
    }

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        pipes[to.nodeId]?.target?._incomingDigests?.emit(ReceivedDigest(nodeId, ids))
    }

    private fun pipe(to: LabTransport): Pipe = checkNotNull(pipes[to.nodeId]) { "$nodeId is not linked to ${to.nodeId}" }

    private suspend fun deliver(
        wire: WireEnvelope,
        fromNodeId: String,
    ) {
        // Mirror the real transport: decode the routing envelope on receipt (drop undecodable bytes).
        val envelope = WireCodec.decodeEnvelope(wire.signed) ?: return
        pipes[fromNodeId]?.target?.sent?.add(
            "#${SEQ.incrementAndGet()} ${nodeId.take(6)} ${envelope.type} ${envelope.id} relay=${wire.relay}",
        )
        _inbound.emit(InboundFrame(wire, envelope, fromNodeId))
    }

    private fun refreshNeighbors() {
        _neighbors.value = pipes.keys.map { Peer(it) }.toSet()
    }

    private companion object {
        const val BUFFER = 1024

        /** One counter across every transport in the JVM, so two nodes' send logs interleave by time. */
        val SEQ =
            java.util.concurrent.atomic
                .AtomicLong()
    }
}
