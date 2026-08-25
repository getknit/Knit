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

    /** A FromRadio.node_info for [num], with `device_metrics { battery_level, voltage }` when either is given. */
    fun nodeInfo(
        num: UInt,
        batteryLevel: Int? = null,
        voltage: Float? = null,
    ): ByteArray =
        ProtoWriter()
            .message(4) {
                uint32(1, num)
                if (batteryLevel != null || voltage != null) {
                    message(6) {
                        if (batteryLevel != null) varint(1, batteryLevel)
                        if (voltage != null) fixed32(2, voltage.toRawBits().toUInt())
                    }
                }
            }.toByteArray()

    /** A TELEMETRY_APP packet from [from] carrying `Telemetry { device_metrics { battery_level, voltage } }`. */
    fun telemetry(
        from: UInt,
        batteryLevel: Int,
        voltage: Float,
    ): ByteArray =
        packet(
            from = from,
            channel = 0,
            portnum = MeshtasticProto.PORT_TELEMETRY,
            payload =
                ProtoWriter()
                    .message(2) {
                        varint(1, batteryLevel)
                        fixed32(2, voltage.toRawBits().toUInt())
                    }.toByteArray(),
        )

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

    /** The `to`, `portnum`, and `Data.payload` of a ToRadio{packet}, or null when [toRadio] isn't a packet. */
    data class OutboundData(
        val to: UInt,
        val portnum: Int,
        val payload: ByteArray,
    )

    fun outboundData(toRadio: ByteArray): OutboundData? {
        if (!isPacket(toRadio)) return null
        val reader = ProtoReader(toRadio)
        reader.readTag() // packet (ToRadio field 1)
        val mp = reader.sub()
        var to = 0u
        var portnum = 0
        var payload = ByteArray(0)
        while (mp.hasMore) {
            val tag = mp.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                2 -> {
                    to = mp.readFixed32()
                }

                4 -> {
                    val d = mp.sub()
                    while (d.hasMore) {
                        val t = d.readTag()
                        when (t ushr WireType.FIELD_SHIFT) {
                            1 -> portnum = d.readVarint32()
                            2 -> payload = d.readBytes()
                            else -> d.skip(t and WireType.MASK)
                        }
                    }
                }

                else -> {
                    mp.skip(tag and WireType.MASK)
                }
            }
        }
        return OutboundData(to, portnum, payload)
    }

    /** An admin `get_channel_request` (its payload leads with field 1 → tag 0x08). */
    fun isAdminGet(toRadio: ByteArray): Boolean =
        outboundData(toRadio)?.let { it.portnum == MeshtasticProto.PORT_ADMIN && it.payload.firstOrNull() == 0x08.toByte() } ?: false

    fun isAdmin(toRadio: ByteArray): Boolean = outboundData(toRadio)?.portnum == MeshtasticProto.PORT_ADMIN

    /** An admin `set_channel` (its payload leads with field 33 → tag bytes 0x8A 0x02). */
    fun isAdminSet(toRadio: ByteArray): Boolean =
        outboundData(toRadio)?.let {
            it.portnum == MeshtasticProto.PORT_ADMIN && it.payload.size >= 2 && it.payload[0] == 0x8A.toByte() &&
                it.payload[1] == 0x02.toByte()
        } ?: false

    /** A board→phone `AdminMessage { get_channel_response{ index, name, role }, session_passkey }`. */
    fun adminGetResponse(
        from: UInt,
        requestId: UInt,
        passkey: ByteArray,
        index: Int = 0,
        name: String = "",
        role: Int = 1,
    ): ByteArray =
        packet(
            from = from,
            channel = 0,
            portnum = MeshtasticProto.PORT_ADMIN,
            payload =
                ProtoWriter()
                    .apply {
                        message(2) {
                            varint(1, index)
                            message(2) { string(3, name) }
                            varint(3, role)
                        }
                        bytes(101, passkey)
                    }.toByteArray(),
            requestId = requestId,
        )

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
