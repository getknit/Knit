package app.getknit.knit

import app.getknit.knit.mesh.AckSync
import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The [AckSync] test rig, shared by [AckSyncTest] (the owed / escalated / ride forms) and
 * [AckSyncRideDeadlineTest] (what happens when a ride nobody took reaches its deadline). The message author
 * is a plain transport that records what it receives; the recipient runs the [AckSync].
 */
internal const val SIG_MARKER: Byte = 0x5A

/** The message author: records every frame it receives, exposing the delivery-receipt ack ids. */
internal class Author(
    val id: String,
) {
    val transport = FakeLoopTransport(id)
    private val received = CopyOnWriteArrayList<InboundFrame>()

    fun start(scope: CoroutineScope) {
        scope.launch { transport.inbound.collect { received.add(it) } }
    }

    fun received(): List<InboundFrame> = received.toList()

    fun receipts(): List<InboundFrame> = received.filter { it.envelope.type == FrameType.RECEIPT }

    fun ackIds(): List<String> = receipts().mapNotNull { WireCodec.decodePayload<ReceiptContent>(it.envelope.payload)?.ackId }
}

internal fun ackSyncOn(
    transport: MeshTransport,
    id: String,
    clock: () -> Long = { 0L },
    canSeal: suspend (String) -> Boolean = { false },
    originateTick: suspend (String, List<String>) -> Boolean = { _, _ -> false },
    flushScope: () -> CoroutineScope? = { null },
    spoolPresent: (String) -> Boolean = { false },
    spoolTick: suspend (String, List<String>) -> AckSync.SpoolTick? = { _, _ -> null },
    // Last on purpose: the older tests hand the seal in as a trailing lambda.
    sealTick: suspend (String, List<String>) -> WireEnvelope? = { _, _ -> null },
) = AckSync(
    transport = transport,
    selfId = { id },
    signRaw = { byteArrayOf(SIG_MARKER) },
    now = clock,
    sealTick = sealTick,
    canSeal = canSeal,
    originateTick = originateTick,
    flushScope = flushScope,
    spoolPresent = spoolPresent,
    spoolTick = spoolTick,
)

/** A stand-in sealed tick: a signed CHAT-shaped wire whose bytes identify the (author, first-ackId) seal. */
internal fun sealedWire(
    me: String,
    authorId: String,
    ackIds: List<String>,
): WireEnvelope {
    val env =
        RelayEnvelope(
            type = FrameType.CHAT,
            id = "sealed-${ackIds.first()}",
            senderId = me,
            sentAt = 1L,
            recipientId = authorId,
            payload = WireCodec.encodePayload(ReceiptContent(ackIds.joinToString("+"))),
        )
    val signed = WireCodec.encodeEnvelope(env)
    return WireEnvelope(relay = false, sig = byteArrayOf(SIG_MARKER), signed = signed)
}

/**
 * Records coordination-plane [MeshTransport.fastSend] attempts, delegating everything else — and lets a
 * test sight a peer ([sighted] → [reachable]) without linking it, the state of an author heard only over
 * a LoRa board.
 */
internal class FastSendRecorder(
    inner: FakeLoopTransport,
) : MeshTransport by inner {
    val fastSent = CopyOnWriteArrayList<WireEnvelope>()
    val sighted = MutableStateFlow<Set<Peer>>(emptySet())
    override val reachable: StateFlow<Set<Peer>> get() = sighted

    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {
        fastSent.add(wire)
    }
}
