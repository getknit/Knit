package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraFramePolicyTest {
    private fun env(
        type: String,
        recipientId: String? = null,
        group: GroupInfo? = null,
    ) = RelayEnvelope(type = type, id = "id", senderId = "alice", recipientId = recipientId, group = group, payload = ByteArray(0))

    private fun wire(relay: Boolean = true) = WireEnvelope(relay = relay, sig = ByteArray(64), signed = ByteArray(0))

    private fun fanout(
        env: RelayEnvelope,
        wire: WireEnvelope = wire(),
    ) = LoraFramePolicy.eligible(env, wire, LoraFramePolicy.Path.FANOUT)

    private fun targeted(
        env: RelayEnvelope,
        wire: WireEnvelope,
        to: String?,
    ) = LoraFramePolicy.eligible(env, wire, LoraFramePolicy.Path.TARGETED, to)

    @Test
    fun broadcastRoomChatAndReactionAndProfileFanOut() {
        assertTrue(fanout(env(FrameType.CHAT)))
        assertTrue(fanout(env(FrameType.REACTION)))
        assertTrue(fanout(env(FrameType.PROFILE)))
    }

    @Test
    fun dmFormChatFansOutButGroupFormDoesNot() {
        // ADR 039: a sealed DM-form chat rides the long-range plane, whatever its relay flag (a flooded DM, or a
        // sealed receipt/reaction/ctl riding as one — the policy cannot and must not tell them apart).
        assertTrue("a DM chat rides LoRa", fanout(env(FrameType.CHAT, recipientId = "bob")))
        assertTrue("a relay=false DM-form frame rides too", fanout(env(FrameType.CHAT, recipientId = "bob"), wire(relay = false)))
        assertTrue(LoraFramePolicy.isDmForm(env(FrameType.CHAT, recipientId = "bob")))
        assertFalse(LoraFramePolicy.isDmForm(env(FrameType.CHAT)))
        assertFalse(
            "a group chat is not carried — the plane has no group conversation",
            fanout(env(FrameType.CHAT, group = GroupInfo(id = "g-x", members = listOf("alice", "bob"), createdBy = "alice"))),
        )
        assertFalse(
            "a group reaction is private",
            fanout(env(FrameType.REACTION, group = GroupInfo(id = "g-x", members = listOf("alice", "bob"), createdBy = "alice"))),
        )
    }

    @Test
    fun typingAndGroupMetaAndRequestsNeverRide() {
        assertFalse(fanout(env(FrameType.TYPING)))
        assertFalse(fanout(env(FrameType.GROUP_UPDATE)))
        assertFalse(fanout(env(FrameType.GROUP_LEAVE)))
        assertFalse(fanout(env(FrameType.KEY_REQ)))
        assertFalse(fanout(env(FrameType.BLOB_REQ)))
    }

    @Test
    fun aCleartextReceiptRidesTheTargetedPath() {
        assertTrue(targeted(env(FrameType.RECEIPT), wire(), to = "alice"))
    }

    @Test
    fun aSealedTickToTheAuthorRidesButADmDoesNot() {
        // A sealed CTL_RECEIPT tick: a relay=false chat frame addressed to the author.
        assertTrue(targeted(env(FrameType.CHAT, recipientId = "alice"), wire(relay = false), to = "alice"))
        // A real DM is relay=true and must never ride the targeted path.
        assertFalse(targeted(env(FrameType.CHAT, recipientId = "alice"), wire(relay = true), to = "alice"))
        // A relay=false chat addressed to someone other than the target is not this tick.
        assertFalse(targeted(env(FrameType.CHAT, recipientId = "carol"), wire(relay = false), to = "alice"))
    }

    @Test
    fun typingIsRefusedOnTheTargetedPathEvenThoughItIsRelayFalse() {
        assertFalse(targeted(env(FrameType.TYPING, recipientId = "alice"), wire(relay = false), to = "alice"))
    }
}
