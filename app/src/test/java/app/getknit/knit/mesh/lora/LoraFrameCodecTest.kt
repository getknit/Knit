package app.getknit.knit.mesh.lora

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LoraFrameCodecTest {
    private fun frame(body: String): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "id",
                senderId = "alice",
                payload = WireCodec.encodePayload(ChatContent(body = body)),
            )
        return WireEnvelope(relay = true, sig = ByteArray(64) { it.toByte() }, signed = WireCodec.encodeEnvelope(env))
    }

    @Test
    fun aSmallFrameIsOneMessageAtTheDefaultCap() {
        val parts = LoraFrameCodec.encode(frame("hi"), fragId = 1)
        assertNotNull(parts)
        assertTrue("a short frame is a single packet", parts!!.size == 1)
    }

    @Test
    fun everyPacketRespectsTheGivenPayloadCap() {
        // A frame that fits one packet at 233 must split when the board's MTU only allows ~120-byte payloads.
        val wire = frame("The quick brown fox jumps over the lazy dog near the north gate at ten past.")
        val atMtu255 = LoraFrameCodec.encode(wire, fragId = 1, maxPayload = 120)
        assertNotNull("still representable, just fragmented", atMtu255)
        assertTrue("every packet fits the cap", atMtu255!!.all { it.size <= 120 })
        assertTrue("it actually fragmented", atMtu255.size > 1)
    }

    /** ADR 060: the transcoded form is what puts a signed v3 tick in one packet at the MTU-255 boards' cap. */
    @Test
    fun theTranscodedFormFlipsASignedTickToOnePacketAtTheEsp32Cap() {
        val rng = Random(1)
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = FrameId.new(),
                senderId = NodeId.derive("alice"),
                sentAt = 1_755_700_000_000L,
                recipientId = NodeId.derive("bob"),
                payload =
                    WireCodec.encodePayload(
                        ChatContent(
                            enc =
                                EncEnvelope(
                                    v = EncEnvelope.VERSION_DM_V3,
                                    nonce = ByteArray(0),
                                    ct = rng.nextBytes(37),
                                    keys = emptyList(),
                                    r = RatchetHeader(se = 1, ek = rng.nextBytes(32), pe = 0, n = 2),
                                ),
                        ),
                    ),
            )
        val tick = WireEnvelope(relay = true, sig = rng.nextBytes(64), signed = WireCodec.encodeEnvelope(env))
        val cap = 255 - 27
        assertEquals("0x03 needs two packets", 2, LoraFrameCodec.encode(tick, fragId = 1, maxPayload = cap)!!.size)
        val best = checkNotNull(LoraFrameCodec.encodeBest(tick, fragId = 1, maxPayload = cap, transcode = true))
        assertTrue(best.transcoded)
        assertFalse(best.transcodeRefused)
        assertEquals("0x05 is one packet", 1, best.parts.size)
        assertEquals(FastFrameCodec.TAG_TRANSCODED, best.parts.single()[0])
    }

    @Test
    fun theCapIsClampedToTheProtocolLimit() {
        // Asking for more than 233 must not produce a packet the board would reject.
        val wire = frame("x".repeat(400))
        val huge = LoraFrameCodec.encode(wire, fragId = 1, maxPayload = 10_000)
        val normal = LoraFrameCodec.encode(wire, fragId = 1, maxPayload = MeshtasticProto.MAX_PAYLOAD)
        assertTrue("an over-large cap is clamped to 233", huge?.all { it.size <= MeshtasticProto.MAX_PAYLOAD } == true)
        assertTrue("...and matches the protocol-limit encoding", huge?.size == normal?.size)
    }
}
