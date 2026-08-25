package app.getknit.knit.mesh.lora

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A scriptable [GattChannel] for [MeshtasticSessionTest]: a FromRadio read queue, per-op failure
 * overrides, an [onWrite] hook so a test can respond to a ToRadio the way a board would, and an
 * overlap guard that asserts the session never issues two GATT ops at once.
 */
internal class FakeGattChannel : GattChannel {
    override val events = Channel<GattEvent>(Channel.UNLIMITED)

    private val reads = ArrayDeque<ByteArray>()
    val writes = mutableListOf<ByteArray>()

    var subscribeResult: GattResult<Unit> = GattResult.Ok(Unit)

    /** One-shot overrides consumed on the next matching op (null = behave normally). */
    var nextWrite: GattResult<Unit>? = null
    var nextRead: GattResult<ByteArray>? = null

    /** Called synchronously inside [writeToRadio]; lets a test enqueue the board's response. */
    var onWrite: (ByteArray) -> Unit = {}

    var closes = 0
    private var inFlight = false

    fun enqueueRead(bytes: ByteArray) = reads.addLast(bytes)

    fun notify(fromNum: UInt = 1u) {
        events.trySend(GattEvent.Notified(fromNum))
    }

    fun disconnect(status: Int = 0) {
        events.trySend(GattEvent.Disconnected(status))
    }

    override suspend fun subscribeFromNum(timeoutMs: Long): GattResult<Unit> = guarded { subscribeResult }

    override suspend fun writeToRadio(
        bytes: ByteArray,
        timeoutMs: Long,
    ): GattResult<Unit> =
        guarded {
            writes += bytes
            onWrite(bytes)
            nextWrite?.also { nextWrite = null } ?: GattResult.Ok(Unit)
        }

    override suspend fun readFromRadio(timeoutMs: Long): GattResult<ByteArray> =
        guarded {
            nextRead?.let {
                nextRead = null
                return@guarded it
            }
            GattResult.Ok(if (reads.isEmpty()) ByteArray(0) else reads.removeFirst())
        }

    override fun close() {
        closes++
    }

    private inline fun <T> guarded(block: () -> T): T {
        check(!inFlight) { "two GATT ops in flight — the session must serialize them" }
        inFlight = true
        try {
            return block()
        } finally {
            inFlight = false
        }
    }
}

/** A scriptable [MeshtasticGattDialer] over one [FakeGattChannel]. */
internal class FakeGattDialer(
    private val channel: FakeGattChannel,
    private val mtu: Int = 512,
) : MeshtasticGattDialer {
    override val adapterOn = MutableStateFlow(true)
    var bond = BondState.BONDED

    /** Scripted dial outcomes, consumed in order; when empty, dials open [channel]. */
    val dialResults = ArrayDeque<DialResult>()
    var dials = 0

    override fun bondState(address: String): BondState = bond

    override suspend fun dial(address: String): DialResult {
        dials++
        return if (dialResults.isEmpty()) DialResult.Opened(channel, mtu) else dialResults.removeFirst()
    }
}

/** Test helpers for building the FromRadio bytes a board would send, and reading a ToRadio's packet id. */
internal object BoardBytes {
    fun myInfo(
        nodeNum: UInt,
        pioEnv: String,
    ): ByteArray =
        ProtoWriter()
            .message(3) {
                uint32(1, nodeNum)
                string(13, pioEnv)
            }.toByteArray()

    fun configComplete(nonce: UInt): ByteArray = ProtoWriter().uint32(7, nonce).toByteArray()

    fun rebooted(): ByteArray = ProtoWriter().bool(8, true).toByteArray()

    fun queueStatus(
        free: Int,
        maxlen: Int,
        meshPacketId: UInt,
        res: Int = 0,
    ): ByteArray =
        ProtoWriter()
            .message(11) {
                varint(1, res)
                varint(2, free)
                varint(3, maxlen)
                uint32(4, meshPacketId)
            }.toByteArray()

    fun channel(
        index: Int,
        name: String,
        role: Int,
    ): ByteArray =
        ProtoWriter()
            .message(10) {
                varint(1, index)
                message(2) { string(3, name) }
                varint(3, role)
            }.toByteArray()

    /** A FromRadio.packet carrying a decoded Data payload on [portnum]. */
    fun packet(
        from: UInt,
        channel: Int,
        portnum: Int,
        payload: ByteArray,
        id: UInt = 0u,
        requestId: UInt = 0u,
    ): ByteArray =
        ProtoWriter()
            .message(2) {
                fixed32(1, from)
                fixed32(2, MeshtasticProto.BROADCAST)
                varint(3, channel)
                message(4) {
                    varint(1, portnum)
                    bytes(2, payload)
                    fixed32(6, requestId)
                }
                fixed32(6, id)
            }.toByteArray()

    /** A ROUTING_APP NAK packet for our packet [requestId] with [reason]. */
    fun nak(
        from: UInt,
        requestId: UInt,
        reason: RoutingError,
    ): ByteArray = packet(from, 0, MeshtasticProto.PORT_ROUTING, ProtoWriter().varint(3, reason.code).toByteArray(), requestId = requestId)

    /** The first byte identifies a ToRadio: 0x18 want_config, 0x0A packet, 0x3A heartbeat, 0x20 disconnect. */
    fun isWantConfig(toRadio: ByteArray): Boolean = toRadio.isNotEmpty() && toRadio[0] == 0x18.toByte()

    fun isPacket(toRadio: ByteArray): Boolean = toRadio.isNotEmpty() && toRadio[0] == 0x0A.toByte()

    fun isHeartbeat(toRadio: ByteArray): Boolean = toRadio.isNotEmpty() && toRadio[0] == 0x3A.toByte()

    /** Extracts the client-assigned id from a ToRadio{packet} (MeshPacket field 6, fixed32 tag 0x35). */
    fun packetId(toRadio: ByteArray): UInt {
        val reader = ProtoReader(toRadio)
        reader.readTag() // 0x0A (packet)
        val mp = reader.sub()
        var id = 0u
        while (mp.hasMore) {
            val tag = mp.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                6 -> id = mp.readFixed32()
                else -> mp.skip(tag and WireType.MASK)
            }
        }
        return id
    }
}
