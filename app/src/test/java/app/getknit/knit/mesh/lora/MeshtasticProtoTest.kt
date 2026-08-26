package app.getknit.knit.mesh.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Golden-vector + property tests for [MeshtasticProto]. The vectors are hand-derived against
 * `meshtastic/protobufs` (tag = field << 3 | wireType; 0 varint, 2 length-delimited, 5 fixed32) and are
 * the executable pin on the field numbers the board interop depends on. The property loops assert the
 * decoder is total: no input, however hostile, throws.
 */
class MeshtasticProtoTest {
    private fun hex(s: String): ByteArray =
        s
            .split(" ")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }

    // --- encode vectors ---

    @Test
    fun encodeWantConfig() {
        assertEquals("18 F8 AC D1 91 01", MeshtasticProto.encodeWantConfig(0x12345678u).hex())
    }

    @Test
    fun encodeHeartbeatIsAnEmptyPresentMessage() {
        assertEquals("3A 00", MeshtasticProto.encodeHeartbeat().hex())
    }

    @Test
    fun encodeDisconnect() {
        assertEquals("20 01", MeshtasticProto.encodeDisconnect().hex())
    }

    @Test
    fun encodeBroadcastPacket() {
        val bytes =
            MeshtasticProto.encodePacket(
                OutboundPacket(channelIndex = 1, id = 0xDEADBEEFu, payload = byteArrayOf(1, 2, 3)),
            )
        assertEquals(
            "0A 16 15 FF FF FF FF 18 01 22 08 08 80 02 12 03 01 02 03 35 EF BE AD DE",
            bytes.hex(),
        )
    }

    @Test
    fun encodePacketOmitsDefaultChannelAndEmitsHopLimit() {
        val bytes =
            MeshtasticProto.encodePacket(
                OutboundPacket(channelIndex = 0, id = 0xDEADBEEFu, payload = byteArrayOf(1, 2, 3), hopLimit = 3),
            )
        // channel 0 is the proto3 default → omitted; hop_limit (field 9, tag 0x48) is appended.
        assertEquals(
            "0A 16 15 FF FF FF FF 22 08 08 80 02 12 03 01 02 03 35 EF BE AD DE 48 03",
            bytes.hex(),
        )
    }

    @Test
    fun aFullPayloadPacketStaysUnderOneWrite() {
        // 233 B payload → the whole ToRadio worst case must fit one MTU-512 write (the MTU >= 263 gate).
        val bytes =
            MeshtasticProto.encodePacket(
                OutboundPacket(channelIndex = 7, id = 0xFFFFFFFEu, payload = ByteArray(MeshtasticProto.MAX_PAYLOAD)),
            )
        assertTrue("worst-case ToRadio is one ATT write under MTU 512: ${bytes.size}", bytes.size <= 259)
    }

    @Test
    fun encodePacketEmitsWantResponse() {
        // want_response (Data field 3) rides an admin request; here on a self-addressed ADMIN packet.
        val bytes =
            MeshtasticProto.encodePacket(
                OutboundPacket(
                    to = 0x11223344u,
                    channelIndex = 0,
                    id = 0xAABBCCDDu,
                    portnum = MeshtasticProto.PORT_ADMIN,
                    payload = byteArrayOf(0x08, 0x01),
                    wantResponse = true,
                ),
            )
        // packet{ to=11223344, decoded{ portnum=6, payload=08 01, want_response=true }, id=AABBCCDD }
        assertEquals(
            "0A 14 15 44 33 22 11 22 08 08 06 12 02 08 01 18 01 35 DD CC BB AA",
            bytes.hex(),
        )
    }

    // --- admin encode vectors ---

    @Test
    fun encodeAdminGetChannelIsIndexPlusOne() {
        assertEquals("08 01", MeshtasticProto.encodeAdminGetChannel(0).hex())
        assertEquals("08 08", MeshtasticProto.encodeAdminGetChannel(7).hex())
    }

    @Test
    fun encodeAdminBeginAndCommitEdit() {
        assertEquals("80 04 01", MeshtasticProto.encodeAdminBeginEdit(null).hex())
        assertEquals("88 04 01", MeshtasticProto.encodeAdminCommitEdit(null).hex())
    }

    @Test
    fun encodeAdminCommitEchoesSessionPasskey() {
        // commit_edit_settings=true (field 65) then session_passkey=AA BB CC (field 101).
        assertEquals(
            "88 04 01 AA 06 03 AA BB CC",
            MeshtasticProto.encodeAdminCommitEdit(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())).hex(),
        )
    }

    @Test
    fun encodeAdminSetChannel() {
        val bytes =
            MeshtasticProto.encodeAdminSetChannel(
                ChannelWrite(index = 1, name = "A", psk = byteArrayOf(1, 2), role = MeshtasticProto.ROLE_SECONDARY),
                passkey = null,
            )
        // set_channel(33){ index=1, settings{ psk=01 02, name="A" }, role=2 }
        assertEquals("8A 02 0D 08 01 12 07 12 02 01 02 1A 01 41 18 02", bytes.hex())
    }

    @Test
    fun encodeAdminSetChannelCarriesPositionPrecision() {
        val bytes =
            MeshtasticProto.encodeAdminSetChannel(
                ChannelWrite(
                    index = 1,
                    name = "A",
                    psk = byteArrayOf(1, 2),
                    role = MeshtasticProto.ROLE_SECONDARY,
                    positionPrecision = MeshtasticProto.POSITION_PRECISION_NONE,
                ),
                passkey = null,
            )
        // ...settings{ psk, name, module_settings{} }: the submessage is *present but empty* (3A 00), which is
        // how precision 0 reaches the firmware — omitting it would read as "unset" and default to full.
        assertEquals("8A 02 0F 08 01 12 09 12 02 01 02 1A 01 41 3A 00 18 02", bytes.hex())
    }

    @Test
    fun encodeAdminGetConfigIsAOneofMemberEvenAtZero() {
        // get_config_request(5) = DEVICE_CONFIG(0) — a oneof member must appear on the wire at its default.
        assertEquals("28 00", MeshtasticProto.encodeAdminGetConfig(BoardConfig.DEVICE).hex())
        assertEquals("28 01", MeshtasticProto.encodeAdminGetConfig(BoardConfig.POSITION).hex())
        // The module half is its own request field (7) with its own enum (TELEMETRY_CONFIG = 5).
        assertEquals("38 05", MeshtasticProto.encodeAdminGetConfig(BoardConfig.TELEMETRY).hex())
    }

    @Test
    fun encodeAdminSetConfigWrapsTheRawSubConfig() {
        val raw = hex("38 84 54")
        // set_config(34){ Config{ device(1) = raw } }
        assertEquals("92 02 05 0A 03 38 84 54", MeshtasticProto.encodeAdminSetConfig(BoardConfig.DEVICE, raw, null).hex())
        // set_module_config(35){ ModuleConfig{ telemetry(6) = raw } } + session_passkey(101)
        assertEquals(
            "9A 02 05 32 03 38 84 54 AA 06 03 AA BB CC",
            MeshtasticProto.encodeAdminSetConfig(BoardConfig.TELEMETRY, raw, hex("AA BB CC")).hex(),
        )
    }

    @Test
    fun encodeAdminSetConfigKeepsAnAllDefaultSubConfigPresent() {
        // An empty sub-config still has to select the oneof, or the board reads "no config in this message".
        assertEquals("92 02 02 0A 00", MeshtasticProto.encodeAdminSetConfig(BoardConfig.DEVICE, ByteArray(0), null).hex())
    }

    // --- admin decode vectors ---

    @Test
    fun decodeAdminReadsChannelAndPasskey() {
        // get_channel_response(2){ index=2, settings{name="knit"}, role=2 } + session_passkey(101)=AA BB CC
        val reply = MeshtasticProto.decodeAdmin(hex("12 0C 08 02 12 06 1A 04 6B 6E 69 74 18 02 AA 06 03 AA BB CC"))!!
        assertEquals(ChannelInfo(index = 2, name = "knit", role = 2), reply.channel)
        assertEquals("AA BB CC", reply.passkey!!.hex())
    }

    @Test
    fun decodeAdminReadsASubConfigAsRawBytes() {
        // get_config_response(6){ Config{ position(2) = 08 84 07 } } + session_passkey(101)
        val reply = MeshtasticProto.decodeAdmin(hex("32 05 12 03 08 84 07 AA 06 03 AA BB CC"))!!
        assertEquals(BoardConfig.POSITION, reply.config!!.config)
        assertEquals("08 84 07", reply.config!!.raw.hex())
        assertEquals("AA BB CC", reply.passkey!!.hex())
    }

    @Test
    fun decodeAdminReadsAModuleSubConfig() {
        // get_module_config_response(8){ ModuleConfig{ telemetry(6) = 08 84 07 } }
        val reply = MeshtasticProto.decodeAdmin(hex("42 05 32 03 08 84 07"))!!
        assertEquals(BoardConfig.TELEMETRY, reply.config!!.config)
        assertEquals("08 84 07", reply.config!!.raw.hex())
    }

    @Test
    fun decodeAdminIgnoresASubConfigKnitNeverAsksAbout() {
        // Config{ lora(6) = … }: modelled elsewhere, but never a read-modify-write target, so it stays null
        // rather than being mistaken for one of the three.
        assertNull(MeshtasticProto.decodeAdmin(hex("32 05 32 03 08 84 07"))!!.config)
    }

    @Test
    fun decodeAdminWithoutPasskeyOrChannelIsEmptyReply() {
        val reply = MeshtasticProto.decodeAdmin(ByteArray(0))!!
        assertNull(reply.passkey)
        assertNull(reply.channel)
    }

    @Test
    fun decodeAdminIsTotalOnGarbage() {
        val rng = Random(7)
        repeat(2_000) {
            val bytes = ByteArray(rng.nextInt(0, 24)) { rng.nextInt().toByte() }
            MeshtasticProto.decodeAdmin(bytes) // must never throw
        }
    }

    // --- decode vectors ---

    @Test
    fun decodeEmptyIsDrained() {
        assertEquals(FromRadio.Empty, MeshtasticProto.decodeFromRadio(ByteArray(0)))
    }

    @Test
    fun decodeMyInfo() {
        val fr = MeshtasticProto.decodeFromRadio(hex("1A 11 08 F8 AC D1 91 01 6A 09 68 65 6C 74 65 63 2D 76 34"))
        assertEquals(FromRadio.MyInfo(0x12345678u, "heltec-v4"), fr)
    }

    @Test
    fun decodeConfigComplete() {
        assertEquals(FromRadio.ConfigComplete(0x12345678u), MeshtasticProto.decodeFromRadio(hex("38 F8 AC D1 91 01")))
    }

    @Test
    fun decodeLoraRadioConfig() {
        // FromRadio{config=5}{lora=6}{use_preset=1 t, modem_preset=2 MEDIUM_FAST, region=7 EU_868,
        // hop_limit=8 -> 3, override_duty_cycle=12 f}
        val fr = MeshtasticProto.decodeFromRadio(hex("2A 0C 32 0A 08 01 10 04 38 03 40 03 60 00"))
        assertEquals(
            FromRadio.Config(
                LoraRadioConfig(
                    usePreset = true,
                    modemPreset = ModemPreset.MEDIUM_FAST,
                    region = LoraRegion.EU_868,
                    hopLimit = 3,
                    overrideDutyCycle = false,
                ),
            ),
            fr,
        )
    }

    @Test
    fun decodeLoraRadioConfigHonoursTheDutyCycleOverride() {
        val fr = MeshtasticProto.decodeFromRadio(hex("2A 06 32 04 38 03 60 01")) as FromRadio.Config
        assertEquals(true, fr.lora?.overrideDutyCycle)
        assertEquals(LoraRegion.EU_868, fr.lora?.region)
    }

    @Test
    fun anotherConfigVariantDecodesToANullRadioRatherThanBreakingTheHandshake() {
        // FromRadio{config=5}{device=1}{role=1} — a variant we don't read.
        assertEquals(FromRadio.Config(null), MeshtasticProto.decodeFromRadio(hex("2A 04 0A 02 08 01")))
    }

    @Test
    fun anUnknownPresetOrRegionFallsBackRatherThanThrowing() {
        // An over-estimating preset and a 100 %-duty region are the safe fallbacks for codes we don't know.
        assertEquals(ModemPreset.LONG_FAST, ModemPreset.fromCode(99))
        assertEquals(LoraRegion.OTHER, LoraRegion.fromCode(99))
        assertEquals(LoraRegion.OTHER, LoraRegion.fromCode(1)) // US: 100 % duty, collapses into OTHER
        assertEquals(LoraRegion.UNSET, LoraRegion.fromCode(0))
    }

    @Test
    fun decodeRebooted() {
        assertEquals(FromRadio.Rebooted, MeshtasticProto.decodeFromRadio(hex("40 01")))
    }

    @Test
    fun decodeQueueStatus() {
        val fr = MeshtasticProto.decodeFromRadio(hex("5A 0A 10 0F 18 10 20 EF FD B6 F5 0D"))
        assertEquals(FromRadio.QueueStatus(res = 0, free = 15, maxlen = 16, meshPacketId = 0xDEADBEEFu), fr)
    }

    @Test
    fun decodeQueueStatusWithNegativeResReadsTheTenByteVarint() {
        val fr = MeshtasticProto.decodeFromRadio(hex("5A 0F 08 FF FF FF FF FF FF FF FF FF 01 10 0F 18 10"))
        assertEquals(FromRadio.QueueStatus(res = -1, free = 15, maxlen = 16, meshPacketId = 0u), fr)
    }

    @Test
    fun decodeChannelByName() {
        // Channel { index=1, settings { name="knit" (field 3) }, role=2 } — name is settings field 3 (tag 0x1A).
        val fr = MeshtasticProto.decodeFromRadio(hex("52 0C 08 01 12 06 1A 04 6B 6E 69 74 18 02"))
        assertEquals(FromRadio.Channel(ChannelInfo(index = 1, name = "knit", role = 2)), fr)
    }

    @Test
    fun decodeMetadataFirmware() {
        // DeviceMetadata { firmware_version="2.5.0" (field 1) }.
        val fr = MeshtasticProto.decodeFromRadio(hex("6A 07 0A 05 32 2E 35 2E 30"))
        assertEquals(FromRadio.Metadata("2.5.0"), fr)
    }

    @Test
    fun decodeUnknownVariantKeepsItsFieldNumber() {
        assertEquals(FromRadio.Other(19), MeshtasticProto.decodeFromRadio(hex("9A 01 02 08 01")))
    }

    @Test
    fun decodePacketWithInterleavedUnknownFieldsAndSignalQuality() {
        val bytes =
            hex(
                "12 32 " + // FromRadio.packet, len 50
                    "0D 78 56 34 12 " + // from = 0x12345678
                    "15 FF FF FF FF " + // to = broadcast
                    "18 01 " + // channel = 1
                    "22 08 08 80 02 12 03 AA BB CC " + // decoded { portnum=256, payload=AABBCC }
                    "98 06 01 " + // unknown field 99 (varint) — must be skipped
                    "35 01 01 00 00 " + // id = 0x101
                    "45 00 00 D0 40 " + // rx_snr = 6.5f
                    "48 02 " + // hop_limit = 2
                    "60 AB FF FF FF FF FF FF FF FF 01 " + // rx_rssi = -85 (10-byte sign-extended)
                    "78 03", // hop_start = 3
            )
        val fr = MeshtasticProto.decodeFromRadio(bytes) as FromRadio.Packet
        val p = fr.packet
        assertEquals(0x12345678u, p.from)
        assertEquals(MeshtasticProto.BROADCAST, p.to)
        assertEquals(1, p.channel)
        assertEquals(0x101u, p.id)
        assertFalse(p.encrypted)
        val data = p.decoded!!
        assertEquals(MeshtasticProto.PORT_PRIVATE_APP, data.portnum)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), data.payload)
        assertEquals(6.5f, p.rxSnr!!, 0.0001f)
        assertEquals(-85, p.rxRssi)
        assertEquals(1, p.hopsAway) // hop_start 3 - hop_limit 2
    }

    @Test
    fun decodeEncryptedPacketHasNoDecodedData() {
        val fr =
            MeshtasticProto.decodeFromRadio(
                hex("12 10 0D 78 56 34 12 15 FF FF FF FF 2A 04 DE AD BE EF"),
            ) as FromRadio.Packet
        assertTrue(fr.packet.encrypted)
        assertNull(fr.packet.decoded)
    }

    @Test
    fun decodeRoutingNakCorrelatesByRequestId() {
        val bytes =
            hex(
                "12 1C " +
                    "0D 78 56 34 12 " + // from
                    "15 78 56 34 12 " + // to (us)
                    "22 0B 08 05 12 02 18 01 35 EF BE AD DE " + // decoded { portnum=5, payload={error_reason=1}, request_id=0xDEADBEEF }
                    "35 02 02 00 00", // packet id
            )
        val p = (MeshtasticProto.decodeFromRadio(bytes) as FromRadio.Packet).packet
        val data = p.decoded!!
        assertEquals(MeshtasticProto.PORT_ROUTING, data.portnum)
        assertEquals(0xDEADBEEFu, data.requestId)
        assertEquals(RoutingError.NO_ROUTE, MeshtasticProto.decodeRouting(data.payload))
    }

    @Test
    fun decodeRoutingEmptyIsNone() {
        assertEquals(RoutingError.NONE, MeshtasticProto.decodeRouting(ByteArray(0)))
    }

    @Test
    fun decodeRoutingUnknownCodeSurfacesAsUnknown() {
        // error_reason = 99, a code this build doesn't enumerate.
        assertEquals(RoutingError.UNKNOWN, MeshtasticProto.decodeRouting(hex("18 63")))
    }

    // --- totality / robustness ---

    @Test
    fun decodeNodeInfoWithDeviceMetrics() {
        // node_info (field 4) { num = 42, device_metrics (field 6) { battery_level = 78, voltage = 3.92f } }
        val fr = MeshtasticProto.decodeFromRadio(hex("22 0B 08 2A 32 07 08 4E 15 48 E1 7A 40"))
        assertEquals(FromRadio.NodeInfo(42u, DeviceMetrics(batteryLevel = 78, voltage = 3.92f)), fr)
    }

    @Test
    fun decodeNodeInfoWithoutMetricsHasNone() {
        assertEquals(FromRadio.NodeInfo(42u, null), MeshtasticProto.decodeFromRadio(hex("22 02 08 2A")))
    }

    @Test
    fun decodeTelemetryReadsDeviceMetricsAndSkipsTheRest() {
        // Telemetry { time = 0x66A1B2C3, device_metrics { battery_level = 101, voltage = 3.92f, uptime_seconds = 1000 } }
        val metrics = MeshtasticProto.decodeTelemetry(hex("0D C3 B2 A1 66 12 0A 08 65 15 48 E1 7A 40 28 E8 07"))
        assertEquals(DeviceMetrics(batteryLevel = 101, voltage = 3.92f), metrics)
    }

    @Test
    fun decodeTelemetryOfAnotherVariantIsNull() {
        // Telemetry { environment_metrics (field 3) { temperature = 20.0f } } — says nothing about the battery.
        assertNull(MeshtasticProto.decodeTelemetry(hex("1A 05 0D 00 00 A0 41")))
    }

    @Test
    fun everyPrefixTruncationDecodesToNullNeverThrows() {
        val vectors =
            listOf(
                "1A 11 08 F8 AC D1 91 01 6A 09 68 65 6C 74 65 63 2D 76 34",
                "5A 0A 10 0F 18 10 20 EF FD B6 F5 0D",
                "52 0C 08 01 12 06 1A 04 6B 6E 69 74 18 02",
                "12 10 0D 78 56 34 12 15 FF FF FF FF 2A 04 DE AD BE EF",
                "22 0B 08 2A 32 07 08 4E 15 48 E1 7A 40",
            )
        for (v in vectors) {
            val full = hex(v)
            for (len in 1 until full.size) {
                // A truncated frame either decodes to something (it happened to end on a field boundary)
                // or to null — but it must never throw.
                MeshtasticProto.decodeFromRadio(full.copyOf(len))
            }
        }
    }

    @Test
    fun randomBytesNeverThrow() {
        val rng = Random(42)
        repeat(10_000) {
            val bytes = ByteArray(rng.nextInt(0, 64)) { rng.nextInt().toByte() }
            MeshtasticProto.decodeFromRadio(bytes)
            MeshtasticProto.decodeRouting(bytes)
            MeshtasticProto.decodeTelemetry(bytes)
        }
    }

    @Test
    fun aGroupWireTypeIsRefused() {
        // Field 2 with wire type 3 (START_GROUP) — a construct nothing we speak uses.
        assertNull(MeshtasticProto.decodeFromRadio(hex("13 08 01 14")))
    }

    @Test
    fun aLengthPastTheEndIsRefused() {
        // FromRadio.packet claiming length 40 with only a few bytes present.
        assertNull(MeshtasticProto.decodeFromRadio(hex("12 28 0D 78 56")))
    }

    @Test
    fun anElevenByteVarintIsRefused() {
        assertNull(MeshtasticProto.decodeFromRadio(hex("38 FF FF FF FF FF FF FF FF FF FF 01")))
    }
}
