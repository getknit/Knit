package app.getknit.knit.ui.chat

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.lora.LoraSizeHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The LoRa-only notice, the composer's carry form, and its budget — pure rules over the plane facts. */
class LoraReachTest {
    private val live = LoraFacts(LoraPlane.Live, dms = true)
    private val boardOnly = setOf(TransportKind.LoRa)

    @Test
    fun `a DM whose peer only the board has heard reads LoRa-only`() {
        assertEquals(LoraReach.LoraOnly, loraReachFor("ana", live, boardOnly, RelayReach.Silent))
        assertEquals(LoraReach.LoraOnly, loraReachFor("ana", live, boardOnly, RelayReach.Pending))
    }

    @Test
    fun `the notice stays quiet whenever a better plane has the peer, or there is no board`() {
        // The room is addressed to no one; a peer another radio reaches needs no ornament.
        assertEquals(LoraReach.Silent, loraReachFor(Conversations.NEARBY, live, boardOnly, RelayReach.Room))
        assertEquals(LoraReach.Silent, loraReachFor("ana", live, setOf(TransportKind.LoRa, TransportKind.Bluetooth), RelayReach.Silent))
        assertEquals(LoraReach.Silent, loraReachFor("ana", live, setOf(TransportKind.WifiAware), RelayReach.Silent))
        // Not reachable over anything: the existing offline behaviour speaks, not this notice.
        assertEquals(LoraReach.Silent, loraReachFor("ana", live, null, RelayReach.Silent))
        // A relay-covered thread has a carrier that beats the board.
        assertEquals(LoraReach.Silent, loraReachFor("ana", live, boardOnly, RelayReach.Covered))
        // The board is down (the header glyph already says so).
        assertEquals(LoraReach.Silent, loraReachFor("ana", LoraFacts(LoraPlane.Down, dms = true), boardOnly, RelayReach.Silent))
        assertEquals(LoraReach.Silent, loraReachFor("ana", LoraFacts(), boardOnly, RelayReach.Silent))
    }

    @Test
    fun `with private messages kept off LoRa the notice says nothing reaches them`() {
        assertEquals(LoraReach.LoraOnlyDmsOff, loraReachFor("ana", LoraFacts(LoraPlane.Live, dms = false), boardOnly, RelayReach.Silent))
    }

    @Test
    fun `with the airtime window spent a LoRa-only DM is told it will wait`() {
        val spent = LoraFacts(LoraPlane.Live, dms = true, airtimeSpent = true)
        assertEquals(LoraReach.LoraOnlySaturated, loraReachFor("ana", spent, boardOnly, RelayReach.Silent))
        // Only where the LoRa-only notice would have shown: a better carrier or the room still says nothing…
        assertEquals(LoraReach.Silent, loraReachFor("ana", spent, setOf(TransportKind.LoRa, TransportKind.Bluetooth), RelayReach.Silent))
        assertEquals(LoraReach.Silent, loraReachFor(Conversations.NEARBY, spent, boardOnly, RelayReach.Room))
        // …and the DMs-off notice outranks it (nothing is going out at all, spent or not).
        assertEquals(LoraReach.LoraOnlyDmsOff, loraReachFor("ana", spent.copy(dms = false), boardOnly, RelayReach.Silent))
    }

    @Test
    fun `a draft rides LoRa as a room post or a DM, never in a group or with the switch off`() {
        assertEquals(LoraCarry.Room, loraCarryFor(Conversations.NEARBY, isGroup = false, facts = live))
        assertEquals(LoraCarry.Dm, loraCarryFor("ana", isGroup = false, facts = live))
        assertEquals(LoraCarry.None, loraCarryFor("g-1", isGroup = true, facts = live))
        assertEquals(LoraCarry.None, loraCarryFor("ana", isGroup = false, facts = LoraFacts(LoraPlane.Live, dms = false)))
        assertEquals(LoraCarry.None, loraCarryFor("ana", isGroup = false, facts = LoraFacts(LoraPlane.Down, dms = true)))
        // The room still rides with private messages off — the switch is about DMs only.
        assertEquals(LoraCarry.Room, loraCarryFor(Conversations.NEARBY, isGroup = false, facts = LoraFacts(LoraPlane.Live, dms = false)))
    }

    @Test
    fun `the budget follows the carry form and what rides along`() {
        assertNull(loraBudgetFor(LoraCarry.None, replying = false, attached = false))
        assertEquals(LoraSizeHint.ROOM_BODY_BYTES, loraBudgetFor(LoraCarry.Room, replying = false, attached = false))
        assertEquals(LoraSizeHint.DM_BODY_BYTES, loraBudgetFor(LoraCarry.Dm, replying = false, attached = false))
        assertEquals(
            LoraSizeHint.DM_BODY_BYTES - LoraSizeHint.REPLY_RESERVE_BYTES,
            loraBudgetFor(LoraCarry.Dm, replying = true, attached = false),
        )
    }
}
