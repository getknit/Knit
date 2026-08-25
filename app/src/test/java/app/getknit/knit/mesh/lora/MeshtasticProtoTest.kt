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

    // --- admin decode vectors ---

    @Test
    fun decodeAdminReadsChannelAndPasskey() {
        // get_channel_response(2){ index=2, settings{name="knit"}, role=2 } + session_passkey(101)=AA BB CC
        val reply = MeshtasticProto.decodeAdmin(hex("12 0C 08 02 12 06 1A 04 6B 6E 69 74 18 02 AA 06 03 AA BB CC"))!!
        assertEquals(ChannelInfo(index = 2, name = "knit", role = 2), reply.channel)
        assertEquals("AA BB CC", reply.passkey!!.hex())
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
    fun everyPrefixTruncationDecodesToNullNeverThrows() {
        val vectors =
            listOf(
                "1A 11 08 F8 AC D1 91 01 6A 09 68 65 6C 74 65 63 2D 76 34",
                "5A 0A 10 0F 18 10 20 EF FD B6 F5 0D",
                "52 0C 08 01 12 06 1A 04 6B 6E 69 74 18 02",
                "12 10 0D 78 56 34 12 15 FF FF FF FF 2A 04 DE AD BE EF",
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
