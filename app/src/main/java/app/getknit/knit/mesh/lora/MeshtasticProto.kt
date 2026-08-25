package app.getknit.knit.mesh.lora

/**
 * A hand-written codec for the sliver of the Meshtastic protobuf schema Knit's LoRa bridge needs — the
 * `ToRadio` frames we write to the board and the `FromRadio` frames it streams back. Deliberately not a
 * generated protobuf: the whole surface is a dozen fields, and vendoring a protobuf runtime or a codegen
 * Gradle plugin would fight the bleeding-edge toolchain (`.agents/context/toolchain.md`) for no benefit.
 *
 * Pure (no Android), so it is a plain-JVM unit-test target ([app.getknit.knit.mesh.lora.MeshtasticProtoTest]).
 * Field numbers are pinned against `meshtastic/protobufs` and covered by golden byte vectors in that test;
 * every decode is total — malformed input yields `null`, never a throw (via [ProtoException]).
 */
@Suppress("TooManyFunctions") // a flat codec: one small encode/decode helper per Meshtastic message we speak
internal object MeshtasticProto {
    /** `Data.payload`'s hard cap (`Constants.DATA_PAYLOAD_LEN`); a larger payload is refused before the radio. */
    const val MAX_PAYLOAD = 233

    /** `NodeNum` broadcast address (`0xFFFFFFFF`). */
    const val BROADCAST: UInt = 0xFFFFFFFFu

    /** `PortNum.PRIVATE_APP` — the 256..511 private range Knit's frames ride so they never collide with app data. */
    const val PORT_PRIVATE_APP = 256

    /** `PortNum.ROUTING_APP` — how the mesh delivers a NAK (an `error_reason`) back to the sender. */
    const val PORT_ROUTING = 5

    /** `PortNum.ADMIN_APP` — carries an `AdminMessage`; Knit uses it only to self-configure the local board. */
    const val PORT_ADMIN = 6

    /** `Channel.Role.SECONDARY` — the role Knit writes its channel as, so the board's primary is never touched. */
    const val ROLE_SECONDARY = 2

    // --- ToRadio (phone → board) ---

    /** `ToRadio { want_config_id = nonce }` — opens the config handshake; the board replies until `config_complete_id`. */
    fun encodeWantConfig(nonce: UInt): ByteArray = ProtoWriter().uint32(TO_WANT_CONFIG_ID, nonce).toByteArray()

    /** `ToRadio { heartbeat { nonce } }` — keeps the phone API alive; an empty message whose presence is the signal. */
    fun encodeHeartbeat(nonce: UInt = 0u): ByteArray =
        ProtoWriter().message(TO_HEARTBEAT, emitEmpty = true) { uint32(HEARTBEAT_NONCE, nonce) }.toByteArray()

    /** `ToRadio { disconnect = true }` — a clean goodbye so the board frees the phone slot at once. */
    fun encodeDisconnect(): ByteArray = ProtoWriter().bool(TO_DISCONNECT, true).toByteArray()

    /**
     * `ToRadio { packet = MeshPacket { … Data { portnum, payload } } }`. [OutboundPacket.hopLimit] omitted
     * (0) rides the node's configured default; `want_ack` is left false — a broadcast ack is meaningless and
     * only costs airtime.
     */
    fun encodePacket(packet: OutboundPacket): ByteArray =
        ProtoWriter()
            .message(TO_PACKET) {
                fixed32(MP_TO, packet.to)
                varint(MP_CHANNEL, packet.channelIndex)
                message(MP_DECODED) {
                    varint(DATA_PORTNUM, packet.portnum)
                    bytes(DATA_PAYLOAD, packet.payload)
                    if (packet.wantResponse) bool(DATA_WANT_RESPONSE, true)
                }
                fixed32(MP_ID, packet.id)
                if (packet.hopLimit != null) varint(MP_HOP_LIMIT, packet.hopLimit)
            }.toByteArray()

    // --- Admin (phone → local board): an AdminMessage rides a Data{portnum = ADMIN} packet addressed to self ---

    /**
     * `AdminMessage { get_channel_request = index + 1 }` — a read whose response carries the channel AND a
     * fresh `session_passkey` the board then demands on the matching set. (The `+ 1` is the wire convention:
     * the field doubles as a presence flag, so index 0 is requested as 1.)
     */
    fun encodeAdminGetChannel(index: Int): ByteArray = ProtoWriter().varint(ADMIN_GET_CHANNEL_REQUEST, index + 1).toByteArray()

    /**
     * `AdminMessage { begin_edit_settings = true }` — opens a transaction so the board defers its implicit
     * save-and-reboot until [encodeAdminCommitEdit], collapsing a multi-write channel edit into one reboot.
     */
    fun encodeAdminBeginEdit(passkey: ByteArray?): ByteArray = admin(passkey) { bool(ADMIN_BEGIN_EDIT, true) }

    /** `AdminMessage { set_channel = Channel { index, settings { psk, name }, role } }`. */
    fun encodeAdminSetChannel(
        spec: ChannelWrite,
        passkey: ByteArray?,
    ): ByteArray =
        admin(passkey) {
            message(ADMIN_SET_CHANNEL) {
                varint(CHANNEL_INDEX, spec.index)
                message(CHANNEL_SETTINGS) {
                    bytes(CHANNEL_SETTINGS_PSK, spec.psk)
                    string(CHANNEL_SETTINGS_NAME, spec.name)
                }
                varint(CHANNEL_ROLE, spec.role)
            }
        }

    /** `AdminMessage { commit_edit_settings = true }` — applies the open transaction (and may reboot the board). */
    fun encodeAdminCommitEdit(passkey: ByteArray?): ByteArray = admin(passkey) { bool(ADMIN_COMMIT_EDIT, true) }

    /** Wraps one AdminMessage `oneof` member ([block]) and appends the `session_passkey` the board issued, if any. */
    private inline fun admin(
        passkey: ByteArray?,
        block: ProtoWriter.() -> Unit,
    ): ByteArray =
        ProtoWriter()
            .apply {
                block()
                if (passkey != null && passkey.isNotEmpty()) bytes(ADMIN_SESSION_PASSKEY, passkey)
            }.toByteArray()

    /** Decodes an inbound `AdminMessage` down to the two fields provisioning reads: the passkey and a channel. */
    fun decodeAdmin(payload: ByteArray): AdminReply? =
        runCatching {
            val reader = ProtoReader(payload)
            var passkey: ByteArray? = null
            var channel: ChannelInfo? = null
            while (reader.hasMore) {
                val tag = reader.readTag()
                when (tag ushr WireType.FIELD_SHIFT) {
                    ADMIN_GET_CHANNEL_RESPONSE -> channel = decodeChannelInfo(reader.sub())
                    ADMIN_SESSION_PASSKEY -> passkey = reader.readBytes()
                    else -> reader.skip(tag and WireType.MASK)
                }
            }
            AdminReply(passkey, channel)
        }.getOrNull()

    // --- FromRadio (board → phone) ---

    /** One decoded `FromRadio` variant, or null when [bytes] is malformed. Empty input is [FromRadio.Empty]. */
    fun decodeFromRadio(bytes: ByteArray): FromRadio? =
        runCatching {
            if (bytes.isEmpty()) return FromRadio.Empty
            val reader = ProtoReader(bytes)
            var result: FromRadio = FromRadio.Empty
            // A known field with an unexpected wire type reads as garbage and the outer runCatching turns
            // that into null — no per-field wire guard needed, which keeps this dispatch under the cap.
            while (reader.hasMore) {
                val tag = reader.readTag()
                val field = tag ushr WireType.FIELD_SHIFT
                result =
                    when (field) {
                        FR_PACKET -> FromRadio.Packet(decodeMeshPacket(reader.sub()))
                        FR_MY_INFO -> decodeMyInfo(reader.sub())
                        FR_CONFIG_COMPLETE -> FromRadio.ConfigComplete(reader.readVarint32().toUInt())
                        FR_REBOOTED -> FromRadio.Rebooted.also { reader.readVarint64() }
                        FR_QUEUE_STATUS -> decodeQueueStatus(reader.sub())
                        FR_CHANNEL -> decodeChannel(reader.sub())
                        FR_METADATA -> decodeMetadata(reader.sub())
                        else -> FromRadio.Other(field).also { reader.skip(tag and WireType.MASK) }
                    }
            }
            result
        }.getOrNull()

    /** The `error_reason` inside a ROUTING_APP `Data.payload` (`Routing { error_reason }`), or null if malformed. */
    fun decodeRouting(payload: ByteArray): RoutingError? =
        runCatching {
            val reader = ProtoReader(payload)
            var reason = RoutingError.NONE
            while (reader.hasMore) {
                val tag = reader.readTag()
                val field = tag ushr WireType.FIELD_SHIFT
                val wire = tag and WireType.MASK
                if (field == ROUTING_ERROR_REASON && wire == WireType.VARINT) {
                    reason = RoutingError.fromCode(reader.readVarint32())
                } else {
                    reader.skip(wire)
                }
            }
            reason
        }.getOrNull()

    private fun decodeMeshPacket(reader: ProtoReader): MeshPacket {
        var from: UInt = 0u
        var to: UInt = 0u
        var channel = 0
        var id: UInt = 0u
        var decoded: MeshData? = null
        var encrypted = false
        var rxSnr: Float? = null
        var rxRssi: Int? = null
        var hopLimit = 0
        var hopStart = 0
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (val field = tag ushr WireType.FIELD_SHIFT) {
                MP_FROM -> {
                    from = reader.readFixed32()
                }

                MP_TO -> {
                    to = reader.readFixed32()
                }

                MP_CHANNEL -> {
                    channel = reader.readVarint32()
                }

                MP_DECODED -> {
                    decoded = decodeData(reader.sub())
                }

                MP_ENCRYPTED -> {
                    reader.readBytes()
                    encrypted = true
                }

                MP_ID -> {
                    id = reader.readFixed32()
                }

                MP_RX_SNR -> {
                    rxSnr = reader.readFloat()
                }

                MP_HOP_LIMIT -> {
                    hopLimit = reader.readVarint32()
                }

                MP_RX_RSSI -> {
                    rxRssi = reader.readVarint32()
                }

                MP_HOP_START -> {
                    hopStart = reader.readVarint32()
                }

                else -> {
                    reader.skip(tag and WireType.MASK)
                }
            }
        }
        return MeshPacket(from, to, channel, id, decoded, encrypted, rxSnr, rxRssi, hopLimit, hopStart)
    }

    private fun decodeData(reader: ProtoReader): MeshData {
        var portnum = 0
        var payload = ByteArray(0)
        var requestId: UInt = 0u
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                DATA_PORTNUM -> portnum = reader.readVarint32()
                DATA_PAYLOAD -> payload = reader.readBytes()
                DATA_REQUEST_ID -> requestId = reader.readFixed32()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return MeshData(portnum, payload, requestId)
    }

    private fun decodeMyInfo(reader: ProtoReader): FromRadio.MyInfo {
        var myNodeNum: UInt = 0u
        var pioEnv: String? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                MYINFO_NODE_NUM -> myNodeNum = reader.readVarint32().toUInt()
                MYINFO_PIO_ENV -> pioEnv = reader.readString()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return FromRadio.MyInfo(myNodeNum, pioEnv)
    }

    private fun decodeQueueStatus(reader: ProtoReader): FromRadio.QueueStatus {
        var res = 0
        var free = 0
        var maxlen = 0
        var meshPacketId: UInt = 0u
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                QS_RES -> res = reader.readVarint32()
                QS_FREE -> free = reader.readVarint32()
                QS_MAXLEN -> maxlen = reader.readVarint32()
                QS_MESH_PACKET_ID -> meshPacketId = reader.readVarint32().toUInt()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return FromRadio.QueueStatus(res, free, maxlen, meshPacketId)
    }

    private fun decodeChannel(reader: ProtoReader): FromRadio.Channel = FromRadio.Channel(decodeChannelInfo(reader))

    /** Decodes a `Channel { index, settings { name }, role }` — shared by the config handshake and admin reads. */
    private fun decodeChannelInfo(reader: ProtoReader): ChannelInfo {
        var index = 0
        var name = ""
        var role = 0
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                CHANNEL_INDEX -> index = reader.readVarint32()
                CHANNEL_SETTINGS -> name = decodeChannelName(reader.sub())
                CHANNEL_ROLE -> role = reader.readVarint32()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return ChannelInfo(index, name, role)
    }

    private fun decodeChannelName(reader: ProtoReader): String {
        var name = ""
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                CHANNEL_SETTINGS_NAME -> name = reader.readString()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return name
    }

    private fun decodeMetadata(reader: ProtoReader): FromRadio.Metadata {
        var firmware: String? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                METADATA_FIRMWARE_VERSION -> firmware = reader.readString()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return FromRadio.Metadata(firmware)
    }

    // ToRadio field numbers.
    private const val TO_PACKET = 1
    private const val TO_WANT_CONFIG_ID = 3
    private const val TO_DISCONNECT = 4
    private const val TO_HEARTBEAT = 7
    private const val HEARTBEAT_NONCE = 1

    // FromRadio field numbers.
    private const val FR_PACKET = 2
    private const val FR_MY_INFO = 3
    private const val FR_CONFIG_COMPLETE = 7
    private const val FR_REBOOTED = 8
    private const val FR_CHANNEL = 10
    private const val FR_QUEUE_STATUS = 11
    private const val FR_METADATA = 13

    // MeshPacket field numbers.
    private const val MP_FROM = 1
    private const val MP_TO = 2
    private const val MP_CHANNEL = 3
    private const val MP_DECODED = 4
    private const val MP_ENCRYPTED = 5
    private const val MP_ID = 6
    private const val MP_RX_SNR = 8
    private const val MP_HOP_LIMIT = 9
    private const val MP_RX_RSSI = 12
    private const val MP_HOP_START = 15

    // Data field numbers.
    private const val DATA_PORTNUM = 1
    private const val DATA_PAYLOAD = 2
    private const val DATA_WANT_RESPONSE = 3
    private const val DATA_REQUEST_ID = 6

    // AdminMessage field numbers (a `oneof`, so at most one of these per message) + its session key.
    private const val ADMIN_GET_CHANNEL_REQUEST = 1
    private const val ADMIN_GET_CHANNEL_RESPONSE = 2
    private const val ADMIN_SET_CHANNEL = 33
    private const val ADMIN_BEGIN_EDIT = 64
    private const val ADMIN_COMMIT_EDIT = 65
    private const val ADMIN_SESSION_PASSKEY = 101

    // ChannelSettings field numbers (psk + name; the rest are left at their proto3 defaults).
    private const val CHANNEL_SETTINGS_PSK = 2

    // QueueStatus / MyNodeInfo / Channel / DeviceMetadata / Routing field numbers.
    private const val QS_RES = 1
    private const val QS_FREE = 2
    private const val QS_MAXLEN = 3
    private const val QS_MESH_PACKET_ID = 4
    private const val MYINFO_NODE_NUM = 1
    private const val MYINFO_PIO_ENV = 13
    private const val CHANNEL_INDEX = 1
    private const val CHANNEL_SETTINGS = 2
    private const val CHANNEL_SETTINGS_NAME = 3
    private const val CHANNEL_ROLE = 3
    private const val METADATA_FIRMWARE_VERSION = 1
    private const val ROUTING_ERROR_REASON = 3
}

/** A `MeshPacket` we send: broadcast, one `Data` sub-message, a client-assigned [id] for NAK correlation. */
internal data class OutboundPacket(
    val to: UInt = MeshtasticProto.BROADCAST,
    val channelIndex: Int,
    val id: UInt,
    val portnum: Int = MeshtasticProto.PORT_PRIVATE_APP,
    val payload: ByteArray,
    val hopLimit: Int? = null,
    /** Set on an admin request so the local node returns a response (the passkey / get-channel reply). */
    val wantResponse: Boolean = false,
)

/** One channel Knit writes to the board via `set_channel`: a secondary slot with a name + AES PSK. */
internal data class ChannelWrite(
    val index: Int,
    val name: String,
    val psk: ByteArray,
    val role: Int = MeshtasticProto.ROLE_SECONDARY,
)

/** The two fields provisioning reads out of an inbound `AdminMessage`: the [passkey] and any [channel] returned. */
internal data class AdminReply(
    val passkey: ByteArray?,
    val channel: ChannelInfo?,
)

/** A decoded inbound `MeshPacket`. [decoded] is null when the packet arrived [encrypted] (a foreign channel). */
internal class MeshPacket(
    val from: UInt,
    val to: UInt,
    val channel: Int,
    val id: UInt,
    val decoded: MeshData?,
    val encrypted: Boolean,
    val rxSnr: Float?,
    val rxRssi: Int?,
    val hopLimit: Int,
    val hopStart: Int,
) {
    /** How many hops away the origin is (`hop_start - hop_limit`), or null when the board didn't report a start. */
    val hopsAway: Int? get() = if (hopStart > 0) (hopStart - hopLimit).coerceAtLeast(0) else null
}

/** A decoded `Data` sub-message. */
internal class MeshData(
    val portnum: Int,
    val payload: ByteArray,
    val requestId: UInt,
)

/** One channel as the board reports it in the config handshake, so the transport can select by [name]. */
internal data class ChannelInfo(
    val index: Int,
    val name: String,
    val role: Int,
)

/** The `FromRadio` variants the bridge acts on; everything else decodes to [Other] and is ignored. */
internal sealed interface FromRadio {
    /** A 0-length read: the board's FromRadio queue is drained. */
    data object Empty : FromRadio

    data class MyInfo(
        val myNodeNum: UInt,
        val pioEnv: String?,
    ) : FromRadio

    data class Metadata(
        val firmwareVersion: String?,
    ) : FromRadio

    data class Channel(
        val channel: ChannelInfo,
    ) : FromRadio

    data class ConfigComplete(
        val id: UInt,
    ) : FromRadio

    class Packet(
        val packet: MeshPacket,
    ) : FromRadio

    data class QueueStatus(
        val res: Int,
        val free: Int,
        val maxlen: Int,
        val meshPacketId: UInt,
    ) : FromRadio

    data object Rebooted : FromRadio

    /** A known-shape-but-unhandled variant, named by its field number for diagnostics. */
    data class Other(
        val fieldNumber: Int,
    ) : FromRadio
}

/**
 * `Routing.Error` values (`meshtastic/mesh.proto`), pinned by number. [UNKNOWN] catches a future code so a
 * NAK we don't recognise is still surfaced rather than swallowed.
 */
@Suppress("MagicNumber") // the numbers ARE meshtastic's Routing.Error wire codes; the enum name documents each
internal enum class RoutingError(
    val code: Int,
) {
    NONE(0),
    NO_ROUTE(1),
    GOT_NAK(2),
    TIMEOUT(3),
    NO_INTERFACE(4),
    MAX_RETRANSMIT(5),
    NO_CHANNEL(6),
    TOO_LARGE(7),
    NO_RESPONSE(8),
    DUTY_CYCLE_LIMIT(9),
    BAD_REQUEST(32),
    NOT_AUTHORIZED(33),
    PKI_FAILED(34),
    PKI_UNKNOWN_PUBKEY(35),
    ADMIN_BAD_SESSION_KEY(36),
    ADMIN_PUBLIC_KEY_UNAUTHORIZED(37),
    RATE_LIMIT_EXCEEDED(38),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromCode(code: Int): RoutingError = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
