package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngressBudgetTest {
    private var now = 0L
    private val budget = IngressBudget(burst = 3, perMinute = 60, clock = { now })

    private fun env(
        type: String = FrameType.CHAT,
        recipientId: String? = null,
        group: GroupInfo? = null,
    ) = RelayEnvelope(type = type, id = "x", senderId = "s", recipientId = recipientId, group = group, payload = ByteArray(0))

    @Test
    fun `meters only the cleartext room post`() {
        assertTrue(budget.meters(env()))
        assertFalse(budget.meters(env(recipientId = "peer"))) // a DM
        assertFalse(budget.meters(env(group = GroupInfo(id = "g-1", members = listOf("s", "t"), createdBy = "s")))) // a group message
        assertFalse(budget.meters(env(type = FrameType.PROFILE)))
        assertFalse(budget.meters(env(type = FrameType.RECEIPT)))
    }

    @Test
    fun `a link spends its burst, then is refused until the bucket refills`() {
        repeat(3) { assertTrue(budget.admit("link")) }
        assertFalse(budget.admit("link"))

        now += 1_000L // 60/min = one token per second
        assertTrue(budget.admit("link"))
        assertFalse(budget.admit("link"))
    }

    @Test
    fun `refill never overshoots the burst`() {
        repeat(3) { budget.admit("link") }
        now += 60 * 60_000L
        assertEquals(3, (1..10).count { budget.admit("link") })
    }

    @Test
    fun `links are budgeted independently`() {
        repeat(3) { assertTrue(budget.admit("mallory")) }
        assertFalse(budget.admit("mallory"))
        assertTrue(budget.admit("bob")) // a fresh link starts full
    }

    @Test
    fun `no refill rate means the burst is the whole budget`() {
        val fixed = IngressBudget(burst = 2, perMinute = 0, clock = { now })
        repeat(2) { assertTrue(fixed.admit("link")) }
        now += 24 * 60 * 60_000L
        assertFalse(fixed.admit("link"))
    }
}
