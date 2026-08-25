package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
