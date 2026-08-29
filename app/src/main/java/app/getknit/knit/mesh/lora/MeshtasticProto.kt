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
    /**
     * The protobuf's own `Constants.DATA_PAYLOAD_LEN`. Kept for reference only: it assumes a one-byte portnum,
     * and Knit's `PRIVATE_APP` (256) takes two, so the cap that actually clears the radio is [MAX_PAYLOAD].
     */
    const val DATA_PAYLOAD_LEN = 233

    /**
     * The largest encoded `Data` message the firmware's router will transmit — `MAX_RHPACKETLEN` (256) minus
     * the 16-byte packet header minus 3. Measured on a Heltec V4 on 2.7.26 (2026-08-29): a 231-byte
     * `PRIVATE_APP` payload (237-byte `Data`) is queued, 232 and 233 come back `Routing.error_reason =
     * TOO_LARGE`. Every packet that ever NAKed `TOO_LARGE` on the lab boards was one chunked at
     * [DATA_PAYLOAD_LEN].
     */
    const val LORA_DATA_MAX = 237

    /** What `Data` adds around a maximum payload: the two-byte private portnum and the payload's tag + length. */
    val DATA_FRAMING: Int =
        ProtoWriter()
            .varint(DATA_PORTNUM, PORT_PRIVATE_APP)
            .bytes(DATA_PAYLOAD, ByteArray(LORA_DATA_MAX))
            .toByteArray()
            .size -
            LORA_DATA_MAX

    /** `Data.payload`'s hard cap on the air for a Knit packet: [LORA_DATA_MAX] less [DATA_FRAMING] — 231. */
    val MAX_PAYLOAD: Int = LORA_DATA_MAX - DATA_FRAMING

    /**
     * The bytes a `ToRadio { packet }` wraps around a maximum `Data.payload`, measured by encoding one
     * rather than summed by hand (which is how the previous constant grew 6 B of "slack" nobody could
     * account for): the ToRadio tag + length, `to` and `id` as fixed32, the worst-case channel index, the
     * `decoded` submessage framing, the private-range portnum, and the payload's own tag + length. This is
     * what a BLE write of a full packet costs above the payload; the ATT header is the transport's.
     */
    val PACKET_OVERHEAD: Int =
        encodePacket(OutboundPacket(channelIndex = MAX_CHANNEL_INDEX, id = UInt.MAX_VALUE, payload = ByteArray(MAX_PAYLOAD))).size -
            MAX_PAYLOAD

    /** The highest channel index the firmware allows (`MAX_NUM_CHANNELS - 1`); the worst case for the varint. */
    private const val MAX_CHANNEL_INDEX = 7

    /** `NodeNum` broadcast address (`0xFFFFFFFF`). */
    const val BROADCAST: UInt = 0xFFFFFFFFu

    /** `PortNum.PRIVATE_APP` — the 256..511 private range Knit's frames ride so they never collide with app data. */
    const val PORT_PRIVATE_APP = 256

    /** `PortNum.ROUTING_APP` — how the mesh delivers a NAK (an `error_reason`) back to the sender. */
    const val PORT_ROUTING = 5

    /** `PortNum.ADMIN_APP` — carries an `AdminMessage`; Knit uses it only to self-configure the local board. */
    const val PORT_ADMIN = 6

    /** `PortNum.TELEMETRY_APP` — the board's own `DeviceMetrics` (its battery) arrive here, addressed from itself. */
    const val PORT_TELEMETRY = 67

    /**
     * `Channel.Role.SECONDARY` — the only role Knit ever writes. The firmware derives its RF slot from
     * `hash(primary channel name)`, so leaving the primary alone is what keeps a Knit board on the public
     * frequency, where stock nodes repeat its packets for free (ADR 045).
     */
    const val ROLE_SECONDARY = 2

    /** `ModuleSettings.position_precision = 0` — "share no position on this channel". */
    const val POSITION_PRECISION_NONE = 0

    // The three housekeeping intervals the board setup stretches (ADR 045). Public because [BoardQuiet]
    // owns the *values*; the field numbers stay here with the rest of the pinned wire, beside their vectors.

    /** `Config.DeviceConfig.node_info_broadcast_secs`. */
    const val DEVICE_NODE_INFO_BROADCAST_SECS = 7

    /** `Config.DeviceConfig.rebroadcast_mode`. */
    const val DEVICE_REBROADCAST_MODE = 6

    /** `Config.PositionConfig.position_broadcast_secs`. */
    const val POSITION_BROADCAST_SECS = 1

    /** `Config.PositionConfig.position_broadcast_smart_enabled`. */
    const val POSITION_BROADCAST_SMART = 2

    /** `ModuleConfig.TelemetryConfig.device_update_interval` — the *mesh* broadcast, not the phone-only feed. */
    const val TELEMETRY_DEVICE_UPDATE_INTERVAL = 1

    // The two `User` fields the setup renames (ADR 049). Public for the same reason as the intervals above:
    // [BoardName] owns the *names*, the field numbers stay here with the rest of the pinned wire.

    /** `User.long_name` — up to 39 characters; what a node list and the Meshtastic app show. */
    const val USER_LONG_NAME = 2

    /** `User.short_name` — up to **4** characters (`char[5]`); what the small on-board screens have room for. */
    const val USER_SHORT_NAME = 3

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
     * `AdminMessage { get_owner_request = true }` — the **read** half of the rename. Its response carries the
     * board's whole `User`, which is the base [encodeAdminSetOwner] splices into, and a fresh passkey.
     */
    fun encodeAdminGetOwner(): ByteArray = ProtoWriter().bool(ADMIN_GET_OWNER_REQUEST, true).toByteArray()

    /**
     * `AdminMessage { set_owner = User { … } }`. [raw] is the board's own `User` as `get_owner_request`
     * returned it, with only [USER_LONG_NAME] / [USER_SHORT_NAME] spliced ([spliceStringFields]).
     *
     * The firmware merges rather than assigns here — `handleSetOwner` copies each *non-empty* string — but
     * `is_licensed` is a plain proto3 bool with no presence, so a `User` built from scratch would read as
     * `false` and clear it, and with it `config.lora.override_duty_cycle`. Splicing the board's own bytes
     * keeps that, the public key, and everything else this codec does not model.
     */
    fun encodeAdminSetOwner(
        raw: ByteArray,
        passkey: ByteArray?,
    ): ByteArray = admin(passkey) { bytes(ADMIN_SET_OWNER, raw, emitEmpty = true) }

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
                    // Emitted even when empty: an absent `module_settings` reads as "unset" and the firmware
                    // falls back to full position precision, which is the opposite of precision 0.
                    if (spec.positionPrecision != null) {
                        message(CHANNEL_SETTINGS_MODULE_SETTINGS, emitEmpty = true) {
                            varint(MODULE_SETTINGS_POSITION_PRECISION, spec.positionPrecision)
                        }
                    }
                }
                varint(CHANNEL_ROLE, spec.role)
            }
        }

    /**
     * `AdminMessage { get_config_request = <ConfigType> }` (or the module-config variant) — the **read** half
     * of every config write this app does. The value rides [ProtoWriter.oneofVarint] because `ConfigType`'s
     * first member is 0 and a `oneof` member must appear on the wire even at its default.
     */
    fun encodeAdminGetConfig(config: BoardConfig): ByteArray =
        ProtoWriter()
            .oneofVarint(
                if (config.module) ADMIN_GET_MODULE_CONFIG_REQUEST else ADMIN_GET_CONFIG_REQUEST,
                config.configType,
            ).toByteArray()

    /**
     * `AdminMessage { set_config = Config { <member> = <raw> } }` (or the module-config variant). [raw] is
     * the board's own sub-config as it came back from [encodeAdminGetConfig], with only the intended fields
     * spliced ([spliceVarintFields]) — the firmware assigns the whole sub-message, so anything missing here
     * is anything reset on the board.
     */
    fun encodeAdminSetConfig(
        config: BoardConfig,
        raw: ByteArray,
        passkey: ByteArray?,
    ): ByteArray =
        admin(passkey) {
            message(if (config.module) ADMIN_SET_MODULE_CONFIG else ADMIN_SET_CONFIG) {
                bytes(config.member, raw, emitEmpty = true)
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

    /** Decodes an inbound `AdminMessage` down to the fields provisioning reads: the passkey, a channel, a
     * sub-config and the board's `User`. */
    fun decodeAdmin(payload: ByteArray): AdminReply? =
        runCatching {
            val reader = ProtoReader(payload)
            var passkey: ByteArray? = null
            var channel: ChannelInfo? = null
            var config: BoardConfigRaw? = null
            var owner: BoardOwnerRaw? = null
            while (reader.hasMore) {
                val tag = reader.readTag()
                when (tag ushr WireType.FIELD_SHIFT) {
                    ADMIN_GET_CHANNEL_RESPONSE -> channel = decodeChannelInfo(reader.sub())
                    ADMIN_GET_OWNER_RESPONSE -> owner = BoardOwnerRaw(reader.readBytes())
                    ADMIN_GET_CONFIG_RESPONSE -> config = decodeConfigRaw(reader.readBytes(), module = false)
                    ADMIN_GET_MODULE_CONFIG_RESPONSE -> config = decodeConfigRaw(reader.readBytes(), module = true)
                    ADMIN_SESSION_PASSKEY -> passkey = reader.readBytes()
                    else -> reader.skip(tag and WireType.MASK)
                }
            }
            AdminReply(passkey, channel, config, owner)
        }.getOrNull()

    // --- FromRadio (board → phone) ---

    /** One decoded `FromRadio` variant, or null when [bytes] is malformed. Empty input is [FromRadio.Empty]. */
    fun decodeFromRadio(bytes: ByteArray): FromRadio? =
        runCatching {
            if (bytes.isEmpty()) return FromRadio.Empty
            val reader = ProtoReader(bytes)
            var result: FromRadio = FromRadio.Empty
            while (reader.hasMore) {
                result = decodeVariant(reader, reader.readTag())
            }
            result
        }.getOrNull()

    /**
     * One `FromRadio` field by number. A known field with an unexpected wire type reads as garbage and
     * [decodeFromRadio]'s `runCatching` turns that into null — no per-field wire guard needed.
     */
    private fun decodeVariant(
        reader: ProtoReader,
        tag: Int,
    ): FromRadio =
        when (val field = tag ushr WireType.FIELD_SHIFT) {
            FR_PACKET -> FromRadio.Packet(decodeMeshPacket(reader.sub()))
            FR_MY_INFO -> decodeMyInfo(reader.sub())
            FR_NODE_INFO -> decodeNodeInfo(reader.sub())
            FR_CONFIG_COMPLETE -> FromRadio.ConfigComplete(reader.readVarint32().toUInt())
            FR_REBOOTED -> FromRadio.Rebooted.also { reader.readVarint64() }
            FR_QUEUE_STATUS -> decodeQueueStatus(reader.sub())
            FR_CONFIG -> decodeConfig(reader.sub())
            FR_CHANNEL -> decodeChannel(reader.sub())
            FR_METADATA -> decodeMetadata(reader.sub())
            else -> FromRadio.Other(field).also { reader.skip(tag and WireType.MASK) }
        }

    /**
     * The `device_metrics` inside a TELEMETRY_APP `Data.payload` (`Telemetry { time, device_metrics }`), or
     * null when the packet carries another telemetry variant (environment, power, …) or is malformed.
     */
    fun decodeTelemetry(payload: ByteArray): DeviceMetrics? =
        runCatching {
            val reader = ProtoReader(payload)
            var metrics: DeviceMetrics? = null
            while (reader.hasMore) {
                val tag = reader.readTag()
                when (tag ushr WireType.FIELD_SHIFT) {
                    TELEMETRY_DEVICE_METRICS -> metrics = decodeDeviceMetrics(reader.sub())
                    else -> reader.skip(tag and WireType.MASK)
                }
            }
            metrics
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

    /** `NodeInfo { num, user, device_metrics }` — the board's own entry carries its name and its battery. */
    private fun decodeNodeInfo(reader: ProtoReader): FromRadio.NodeInfo {
        var num: UInt = 0u
        var metrics: DeviceMetrics? = null
        var owner: BoardOwner? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                NODEINFO_NUM -> num = reader.readVarint32().toUInt()
                NODEINFO_USER -> owner = BoardOwnerRaw(reader.readBytes()).owner
                NODEINFO_DEVICE_METRICS -> metrics = decodeDeviceMetrics(reader.sub())
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return FromRadio.NodeInfo(num, metrics, owner)
    }

    /** `DeviceMetrics { battery_level, voltage }` — both proto3-`optional`, so an absent field stays null. */
    private fun decodeDeviceMetrics(reader: ProtoReader): DeviceMetrics {
        var level: Int? = null
        var voltage: Float? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                DM_BATTERY_LEVEL -> level = reader.readVarint32()
                DM_VOLTAGE -> voltage = reader.readFloat()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return DeviceMetrics(level, voltage)
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

    /**
     * A `Config` streamed during the want_config handshake. Only the `lora` variant is read (the radio
     * settings the airtime governor needs); every other variant decodes to a null-payload [FromRadio.Config]
     * and is ignored, so an unknown or reordered variant can never break the handshake.
     */
    private fun decodeConfig(reader: ProtoReader): FromRadio.Config {
        var lora: LoraRadioConfig? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                CONFIG_LORA -> lora = decodeLoraConfig(reader.sub())
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return FromRadio.Config(lora)
    }

    /** The four `Config.LoRaConfig` fields that decide time-on-air and the legal duty cycle. */
    private fun decodeLoraConfig(reader: ProtoReader): LoraRadioConfig {
        var usePreset = false
        var preset = ModemPreset.LONG_FAST
        var region = LoraRegion.UNSET
        var hopLimit = 0
        var overrideDutyCycle = false
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                LORA_USE_PRESET -> usePreset = reader.readVarint32() != 0
                LORA_MODEM_PRESET -> preset = ModemPreset.fromCode(reader.readVarint32())
                LORA_REGION -> region = LoraRegion.fromCode(reader.readVarint32())
                LORA_HOP_LIMIT -> hopLimit = reader.readVarint32()
                LORA_OVERRIDE_DUTY_CYCLE -> overrideDutyCycle = reader.readVarint32() != 0
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return LoraRadioConfig(usePreset, preset, region, hopLimit, overrideDutyCycle)
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

    /**
     * Picks the one populated member out of a `Config` / `ModuleConfig` and hands back its bytes **untouched**,
     * so a read-modify-write can splice them without this codec having to model the sub-config at all. A
     * member Knit never asks about yields null rather than a wrong [BoardConfig].
     */
    private fun decodeConfigRaw(
        bytes: ByteArray,
        module: Boolean,
    ): BoardConfigRaw? {
        val reader = ProtoReader(bytes)
        while (reader.hasMore) {
            val tag = reader.readTag()
            if (tag and WireType.MASK != WireType.LEN) {
                reader.skip(tag and WireType.MASK)
                continue
            }
            val raw = reader.readBytes()
            BoardConfig.of(tag ushr WireType.FIELD_SHIFT, module)?.let { return BoardConfigRaw(it, raw) }
        }
        return null
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
    private const val FR_NODE_INFO = 4
    private const val FR_CONFIG = 5
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
    private const val ADMIN_GET_OWNER_REQUEST = 3
    private const val ADMIN_GET_OWNER_RESPONSE = 4
    private const val ADMIN_GET_CONFIG_REQUEST = 5
    private const val ADMIN_GET_CONFIG_RESPONSE = 6
    private const val ADMIN_GET_MODULE_CONFIG_REQUEST = 7
    private const val ADMIN_GET_MODULE_CONFIG_RESPONSE = 8
    private const val ADMIN_SET_OWNER = 32
    private const val ADMIN_SET_CHANNEL = 33
    private const val ADMIN_SET_CONFIG = 34
    private const val ADMIN_SET_MODULE_CONFIG = 35
    private const val ADMIN_BEGIN_EDIT = 64
    private const val ADMIN_COMMIT_EDIT = 65
    private const val ADMIN_SESSION_PASSKEY = 101

    // ChannelSettings field numbers (psk + name; the rest are left at their proto3 defaults).
    private const val CHANNEL_SETTINGS_PSK = 2
    private const val CHANNEL_SETTINGS_MODULE_SETTINGS = 7
    private const val MODULE_SETTINGS_POSITION_PRECISION = 1

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

    // Config / Config.LoRaConfig field numbers (the radio settings the airtime governor reads).
    private const val CONFIG_LORA = 6
    private const val LORA_USE_PRESET = 1
    private const val LORA_MODEM_PRESET = 2
    private const val LORA_REGION = 7
    private const val LORA_HOP_LIMIT = 8
    private const val LORA_OVERRIDE_DUTY_CYCLE = 12

    // NodeInfo / Telemetry / DeviceMetrics field numbers (the battery path).
    private const val NODEINFO_NUM = 1
    private const val NODEINFO_USER = 2
    private const val NODEINFO_DEVICE_METRICS = 6
    private const val TELEMETRY_DEVICE_METRICS = 2
    private const val DM_BATTERY_LEVEL = 1
    private const val DM_VOLTAGE = 2
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

/** One channel Knit writes to the board via `set_channel`: a slot with a name + AES PSK, at some [role]. */
internal data class ChannelWrite(
    val index: Int,
    val name: String,
    val psk: ByteArray,
    val role: Int = MeshtasticProto.ROLE_SECONDARY,
    /** `module_settings.position_precision`; null leaves the board's own value alone (the rendezvous write). */
    val positionPrecision: Int? = null,
)

/** What provisioning reads out of an inbound `AdminMessage`: the [passkey] and whatever the read returned. */
internal data class AdminReply(
    val passkey: ByteArray?,
    val channel: ChannelInfo?,
    /** The sub-config a `get_config` / `get_module_config` returned, kept as raw bytes for splicing. */
    val config: BoardConfigRaw? = null,
    /** The `User` a `get_owner_request` returned, likewise kept raw. */
    val owner: BoardOwnerRaw? = null,
)

/**
 * One board sub-config the setup rewrites (ADR 045), naming both numbers the Meshtastic admin API
 * uses for it: [configType] is the `ConfigType` / `ModuleConfigType` enum value a *request* carries, and
 * [member] is the `Config` / `ModuleConfig` oneof field the *payload* rides in. They differ — LoRa is
 * `ConfigType.LORA_CONFIG = 5` but `Config.lora = 6` — so they are never interchangeable.
 */
internal enum class BoardConfig(
    val configType: Int,
    val member: Int,
    val module: Boolean,
) {
    DEVICE(configType = 0, member = 1, module = false),
    POSITION(configType = 1, member = 2, module = false),
    TELEMETRY(configType = 5, member = 6, module = true),
    ;

    companion object {
        fun of(
            member: Int,
            module: Boolean,
        ): BoardConfig? = entries.firstOrNull { it.member == member && it.module == module }
    }
}

/** A sub-config exactly as the board sent it — the base every read-modify-write splices into. */
internal class BoardConfigRaw(
    val config: BoardConfig,
    val raw: ByteArray,
)

/**
 * The board's `User` exactly as it sent it — the base the rename splices into — alongside the two names
 * decoded out of it, which are what a setup records so a restore can put them back.
 */
internal class BoardOwnerRaw(
    val raw: ByteArray,
) {
    val owner: BoardOwner =
        BoardOwner(
            longName = readStringField(raw, MeshtasticProto.USER_LONG_NAME).orEmpty(),
            shortName = readStringField(raw, MeshtasticProto.USER_SHORT_NAME).orEmpty(),
        )
}

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

/**
 * The two `DeviceMetrics` fields the bridge reads: `battery_level` (0–100; above 100 means external power;
 * absent when the board can't tell) and `voltage` (volts). Folded for display by [BoardBattery.of].
 */
internal data class DeviceMetrics(
    val batteryLevel: Int?,
    val voltage: Float?,
)

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

    /** One NodeDB entry, streamed in the handshake (and pushed on change); only the board's own [metrics] are read. */
    data class NodeInfo(
        val num: UInt,
        val metrics: DeviceMetrics?,
        /** `NodeInfo.user` — for the board's own entry, what it calls itself on the mesh. */
        val owner: BoardOwner? = null,
    ) : FromRadio

    data class Channel(
        val channel: ChannelInfo,
    ) : FromRadio

    /** A `Config` variant from the handshake; [lora] is non-null only for the radio-settings variant. */
    data class Config(
        val lora: LoraRadioConfig?,
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
 * The board's own radio settings, read off the `Config.LoRaConfig` the want_config handshake streams. They
 * are what [LoraAirtime] needs to turn a packet size into milliseconds of air and to know the legal duty
 * cycle — Knit never writes any of them (ADR 038 §10: region and modem preset are a per-board, legally
 * scoped setting the user configures once at flash time).
 *
 * [usePreset] false means the board is on hand-set bandwidth/spread-factor/coding-rate rather than
 * [modemPreset]; we keep the preset's estimate anyway and say so, since a wrong estimate is still far
 * better than none. [overrideDutyCycle] is the firmware's own escape hatch and is honoured: a user who set
 * it has taken the regulatory call, so we stop applying the regional cap and fall back to the polite
 * ceiling.
 */
internal data class LoraRadioConfig(
    val usePreset: Boolean,
    val modemPreset: ModemPreset,
    val region: LoraRegion,
    val hopLimit: Int,
    val overrideDutyCycle: Boolean,
)

/**
 * `Config.LoRaConfig.ModemPreset`, pinned by number, each carrying the spreading factor / bandwidth /
 * coding rate the Meshtastic firmware programs for it (`RadioInterface.cpp`). Those three are the whole
 * input to the LoRa time-on-air formula, which is why they live on the enum rather than in a side table.
 * An unrecognised code falls back to [LONG_FAST] — the shipping default, and the slowest of the common
 * presets, so an unknown future preset is over-estimated rather than under.
 */
@Suppress("MagicNumber") // the numbers ARE the wire codes and the firmware's radio parameters
internal enum class ModemPreset(
    val code: Int,
    val spreadFactor: Int,
    val bandwidthHz: Int,
    /** Coding-rate denominator: 5 means 4/5. */
    val codingRate: Int,
    /**
     * The name the firmware substitutes for an **empty primary channel name** (`Channels::getName`), and so
     * the string it hashes into the RF slot. A board whose primary is unnamed or named exactly this is on the
     * stock frequency for its preset — which is what lets two Knit boards meet without coordinating (ADR 045).
     */
    val defaultChannelName: String,
) {
    LONG_FAST(0, 11, 250_000, 5, "LongFast"),
    LONG_SLOW(1, 12, 125_000, 8, "LongSlow"),
    VERY_LONG_SLOW(2, 12, 62_500, 8, "VeryLongSlow"),
    MEDIUM_SLOW(3, 10, 250_000, 5, "MediumSlow"),
    MEDIUM_FAST(4, 9, 250_000, 5, "MediumFast"),
    SHORT_SLOW(5, 8, 250_000, 5, "ShortSlow"),
    SHORT_FAST(6, 7, 250_000, 5, "ShortFast"),
    LONG_MODERATE(7, 11, 125_000, 8, "LongMod"),
    SHORT_TURBO(8, 7, 500_000, 5, "ShortTurbo"),
    ;

    companion object {
        fun fromCode(code: Int): ModemPreset = entries.firstOrNull { it.code == code } ?: LONG_FAST
    }
}

/**
 * `Config.LoRaConfig.RegionCode`, pinned by number, carrying the transmit **duty cycle** percentage the
 * Meshtastic firmware enforces for each (`RegionInfo` in `RadioInterface.cpp`). Only the duty-limited
 * regions need naming individually; everything else is 100 % and collapses into [OTHER]. [UNSET] means the
 * board has not been given a region and will not transmit at all — treated as the conservative case.
 */
@Suppress("MagicNumber") // the numbers ARE meshtastic's RegionCode wire codes
internal enum class LoraRegion(
    val code: Int,
    val dutyCyclePercent: Double,
) {
    UNSET(0, DUTY_LIMITED_PERCENT),
    EU_433(2, DUTY_LIMITED_PERCENT),
    EU_868(3, DUTY_LIMITED_PERCENT),
    UA_433(14, DUTY_LIMITED_PERCENT),
    UA_868(15, DUTY_LIMITED_PERCENT),

    /** Every region the firmware runs at 100 % duty (US, ANZ, JP, IN, …); named once rather than enumerated. */
    OTHER(-1, 100.0),
    ;

    companion object {
        fun fromCode(code: Int): LoraRegion = entries.firstOrNull { it.code == code && it != OTHER } ?: OTHER
    }
}

/** The duty cycle the firmware enforces in the ETSI-style regions above. */
private const val DUTY_LIMITED_PERCENT = 10.0

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
