package app.getknit.knit.mesh

import app.getknit.knit.mesh.lora.LoraFramePolicy
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The age rule both store-and-forward planes key presence on. The bug it exists for: LoRa and the
 * Internet plane each report presence by frame **author**, and on both of them a frame is routinely
 * handed over by somebody else — so a phone switched off for days read as a live neighbour.
 */
class FramePresenceTest {
    private fun env(
        type: String,
        sentAt: Long,
    ) = RelayEnvelope(type = type, id = "id", senderId = "alice", sentAt = sentAt, payload = ByteArray(0))

    /** Well past every window, so a frame with an unset `sentAt` can never pass by luck. */
    private val now = 10 * 24 * 60 * 60_000L

    @Test
    fun liveTrafficProvesItsAuthorIsThereAndAReServeDoesNot() {
        assertTrue("a chat sent a minute ago", isPresenceEvidence(env(FrameType.CHAT, now - 60_000L), now))
        assertTrue("a reaction sent a minute ago", isPresenceEvidence(env(FrameType.REACTION, now - 60_000L), now))
        assertTrue("exactly at the window is still fresh", isPresenceEvidence(env(FrameType.CHAT, now - PRESENCE_FRESH_MS), now))
        // The ADR 044 bridge backfill, the ADR 039 re-offer and a spool pull of a 48-hour-old blob all put
        // old frames in front of us on purpose. They say where a frame has been, not where its author is.
        assertFalse(
            "a backfilled chat is not its author standing there",
            isPresenceEvidence(env(FrameType.CHAT, now - PRESENCE_FRESH_MS - 1), now),
        )
    }

    @Test
    fun aProfileGetsTheRepublishWindowNotTheFreshOne() {
        // A profile's sentAt is a publish stamp refreshed every 12 h while the version stays put, so it is
        // routinely far older than PRESENCE_FRESH_MS on a node that is merely idle.
        assertTrue(
            "an idle node's beacon still counts",
            isPresenceEvidence(env(FrameType.PROFILE, now - PRESENCE_FRESH_MS - 1), now),
        )
        assertTrue(
            "right at the republish window",
            isPresenceEvidence(env(FrameType.PROFILE, now - PRESENCE_PROFILE_MS), now),
        )
        // A node that stopped republishing stopped being there — the switched-off phone that used to show
        // as directly connected off a profile the Internet plane had re-fanned onto LoRa.
        assertFalse(
            "a node that has stopped republishing is gone",
            isPresenceEvidence(env(FrameType.PROFILE, now - PRESENCE_PROFILE_MS - 1), now),
        )
    }

    @Test
    fun presenceIsNotFreshness() {
        // LoraFramePolicy.isFresh short-circuits true for every non-chat type — right for "may this ride",
        // wrong for "does this prove anyone is there". The two share a number and must not share a rule.
        val ancientProfile = env(FrameType.PROFILE, 0L)
        assertTrue("a profile always rides the fan-out", LoraFramePolicy.isFresh(ancientProfile, now))
        assertFalse("but an ancient one proves nothing", isPresenceEvidence(ancientProfile, now))
    }
}
