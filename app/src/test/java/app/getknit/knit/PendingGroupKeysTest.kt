package app.getknit.knit

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PendingGroupKeys
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [PendingGroupKeys]'s park/replay buffer on the JVM — the [app.getknit.knit.mesh.PendingInbound]
 * sibling keyed by group id. A pure data structure, so park/release/TTL/cap are asserted directly with an
 * injected clock; the end-to-end "a seed that outran its roster still gets adopted" lives in
 * `InboundPipelineTest`.
 */
class PendingGroupKeysTest {
    @Test
    fun heldFrameIsReleasedForItsGroup() {
        val metrics = MeshMetrics()
        val buffer = PendingGroupKeys(now = { 0L }, metrics = metrics)

        assertTrue(buffer.hold("g-1", frame("s1", "alice", kind = TransportKind.LoRa)))

        val released = buffer.release("g-1")
        assertEquals(listOf("s1"), released.map { it.env.id })
        assertEquals("alice", released.single().fromNodeId)
        // The plane it was parked with rides along, exactly as PendingInbound keeps it.
        assertEquals(TransportKind.LoRa, released.single().kind)
        assertEquals(1L, metrics.snapshot().groupSeedsHeld)
        // Once released it's gone — a second release yields nothing.
        assertTrue(buffer.release("g-1").isEmpty())
    }

    @Test
    fun releaseReturnsOnlyTheMatchingGroupOldestFirst() {
        var clock = 0L
        val buffer = PendingGroupKeys(now = { clock })
        buffer.hold("g-1", frame("a1", "alice"))
        clock += 1
        buffer.hold("g-2", frame("b1", "bob"))
        clock += 1
        buffer.hold("g-1", frame("c1", "carol"))

        assertEquals(listOf("a1", "c1"), buffer.release("g-1").map { it.env.id })
        assertEquals(listOf("b1"), buffer.release("g-2").map { it.env.id })
    }

    @Test
    fun aReServedHeldFrameIsStillReportedHeld() {
        // Custody re-serves the parked seed while we still lack the group: the caller must keep skipping the
        // ratchet commit (true), and the buffer must not count or store it twice.
        val metrics = MeshMetrics()
        val buffer = PendingGroupKeys(now = { 0L }, metrics = metrics)
        assertTrue(buffer.hold("g-1", frame("s1", "alice")))

        assertTrue(buffer.hold("g-1", frame("s1", "alice")))

        assertEquals(1, buffer.release("g-1").size)
        assertEquals(1L, metrics.snapshot().groupSeedsHeld)
    }

    @Test
    fun perGroupCapRefusesBeyondTheLimit() {
        var clock = 0L
        val buffer = PendingGroupKeys(now = { clock }, maxPerGroup = 2)
        assertTrue(buffer.hold("g-1", frame("s0", "alice")))
        clock += 1
        assertTrue(buffer.hold("g-1", frame("s1", "bob")))
        clock += 1
        // Refused (false): the caller lets this one take the ordinary path instead of parking it.
        assertFalse(buffer.hold("g-1", frame("s2", "carol")))
        // Another group is not crowded out by g-1's cap.
        assertTrue(buffer.hold("g-2", frame("s3", "dave")))

        assertEquals(listOf("s0", "s1"), buffer.release("g-1").map { it.env.id })
    }

    @Test
    fun globalCapEvictsOldestFirst() {
        var clock = 0L
        val buffer = PendingGroupKeys(now = { clock }, maxFrames = 2)
        buffer.hold("g-0", frame("s0", "alice"))
        clock += 1
        buffer.hold("g-1", frame("s1", "alice"))
        clock += 1
        buffer.hold("g-2", frame("s2", "alice")) // evicts the oldest, s0

        assertTrue("oldest frame evicted under the global cap", buffer.release("g-0").isEmpty())
        assertEquals(listOf("s1"), buffer.release("g-1").map { it.env.id })
        assertEquals(listOf("s2"), buffer.release("g-2").map { it.env.id })
    }

    @Test
    fun sweepExpiredDropsFramesPastTtlButKeepsFresh() {
        var clock = 0L
        val buffer = PendingGroupKeys(now = { clock }, holdTtlMs = 100)
        buffer.hold("g-1", frame("old", "alice"))
        clock = 80
        buffer.hold("g-1", frame("new", "bob"))

        clock = 150 // "old" parked at 0 is past the 100 ms TTL; "new" parked at 80 is not
        assertEquals(1, buffer.sweepExpired())
        assertEquals(listOf("new"), buffer.release("g-1").map { it.env.id })
    }

    private fun frame(
        id: String,
        senderId: String,
        kind: TransportKind = TransportKind.Other,
    ): InboundFrame {
        val env = RelayEnvelope(type = FrameType.CHAT, id = id, senderId = senderId, payload = ByteArray(0))
        val wire = WireEnvelope(sig = byteArrayOf(1), signed = WireCodec.encodeEnvelope(env))
        return InboundFrame(wire, env, fromNodeId = senderId, kind = kind)
    }
}
