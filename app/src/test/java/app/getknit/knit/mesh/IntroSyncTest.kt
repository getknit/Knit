package app.getknit.knit.mesh

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contact-card intro driver on plain JVM: every collaborator is a fake, so the tests pin the three
 * rules (send when sealable then re-send on the floor; answer an init-bearing peer once per floor; grace
 * after confirmation) and the bounds, without a transport, a ratchet or a database.
 */
class IntroSyncTest {
    private class FakeStore : IntroStore {
        var pending = mapOf<String, Long>()
        var grace = mapOf<String, Long>()
        var writes = 0

        override suspend fun pending(): Map<String, Long> = pending

        override suspend fun grace(): Map<String, Long> = grace

        override suspend fun write(
            pending: Map<String, Long>,
            grace: Map<String, Long>,
        ) {
            this.pending = pending
            this.grace = grace
            writes++
        }
    }

    private class Rig(
        maxPending: Int = IntroSync.MAX_PENDING,
    ) {
        var now = 1_000_000L
        val store = FakeStore()
        val sealable = mutableSetOf<String>()
        val confirmed = mutableSetOf<String>()
        val sent = mutableListOf<String>()
        var refuseSend = false
        val metrics = MeshMetrics()
        val sync =
            IntroSync(
                store = store,
                canSeal = { it in sealable },
                sendIntro = { peer ->
                    if (refuseSend) {
                        false
                    } else {
                        sent += peer
                        true
                    }
                },
                sessionConfirmed = { it in confirmed },
                metrics = metrics,
                clock = { now },
                maxPending = maxPending,
            )
    }

    @Test
    fun `an intro waits for the prekey, then goes out exactly once`() =
        runTest {
            val rig = Rig()
            rig.sync.want(BOB)
            assertEquals(emptyList<String>(), rig.sent)
            assertEquals(IntroState.AWAITING_PREKEY, rig.sync.state(BOB).first())

            rig.sealable += BOB
            rig.sync.onProfilePinned(BOB)
            assertEquals(listOf(BOB), rig.sent)
            assertEquals(IntroState.SENT, rig.sync.state(BOB).first())
            assertEquals(1L, rig.metrics.snapshot().introsSent)

            // A second pin (a re-flooded profile) inside the floor does not re-send.
            rig.sync.onProfilePinned(BOB)
            rig.sync.retry()
            assertEquals(listOf(BOB), rig.sent)
        }

    @Test
    fun `a pinned peer is introduced at import time`() =
        runTest {
            val rig = Rig()
            rig.sealable += BOB
            rig.sync.want(BOB)
            assertEquals(listOf(BOB), rig.sent)
        }

    @Test
    fun `an unconfirmed intro is re-sent only once the floor has elapsed`() =
        runTest {
            val rig = Rig()
            rig.sealable += BOB
            rig.sync.want(BOB)
            rig.now += IntroSync.RESEND_FLOOR_MS - 1
            rig.sync.retry()
            assertEquals(1, rig.sent.size)
            rig.now += 1
            rig.sync.retry()
            assertEquals(2, rig.sent.size)
        }

    @Test
    fun `a refused seal records no floor, so the next cue tries again`() =
        runTest {
            val rig = Rig()
            rig.sealable += BOB
            rig.refuseSend = true
            rig.sync.want(BOB)
            assertEquals(IntroState.AWAITING_PREKEY, rig.sync.state(BOB).first())
            rig.refuseSend = false
            rig.sync.onProfilePinned(BOB)
            assertEquals(listOf(BOB), rig.sent)
        }

    @Test
    fun `confirmation moves the peer into grace and the pair scope outlives it by the grace window`() =
        runTest {
            val rig = Rig()
            rig.sealable += BOB
            rig.sync.want(BOB)
            rig.confirmed += BOB
            rig.sync.onPeerFrameOpened(BOB, carriesInit = false)
            assertEquals(IntroState.CONNECTED, rig.sync.state(BOB).first())
            assertEquals(setOf(BOB), rig.sync.pairPeers())
            assertTrue(BOB !in rig.store.pending)

            rig.now += IntroSync.GRACE_MS - 1
            rig.sync.retry()
            assertEquals(setOf(BOB), rig.sync.pairPeers())
            rig.now += 1
            rig.sync.retry()
            assertEquals(emptySet<String>(), rig.sync.pairPeers())
            assertNull(rig.sync.state(BOB).first())
            // No re-send once confirmed, however long it has been.
            assertEquals(1, rig.sent.size)
        }

    @Test
    fun `an init-bearing frame is answered once per floor, and only when sealable`() =
        runTest {
            val rig = Rig()
            rig.sync.onPeerFrameOpened(CAROL, carriesInit = true)
            assertEquals(emptyList<String>(), rig.sent) // no prekey yet — nothing to answer with

            rig.sealable += CAROL
            rig.sync.onPeerFrameOpened(CAROL, carriesInit = true)
            rig.sync.onPeerFrameOpened(CAROL, carriesInit = true)
            assertEquals(listOf(CAROL), rig.sent)
            assertEquals(1L, rig.metrics.snapshot().introsAnswered)

            rig.now += IntroSync.ANSWER_FLOOR_MS
            rig.sync.onPeerFrameOpened(CAROL, carriesInit = true)
            assertEquals(listOf(CAROL, CAROL), rig.sent)

            // A frame without the init is a confirmed peer — never answered.
            rig.now += IntroSync.ANSWER_FLOOR_MS
            rig.sync.onPeerFrameOpened(CAROL, carriesInit = false)
            assertEquals(2, rig.sent.size)
        }

    @Test
    fun `both sides importing each other converges with no extra sends`() =
        runTest {
            // Us: pending intro to Bob, Bob's prekey known → sent. Bob's own intro then arrives with its
            // init (both initiated); the engine resolved the race and our session is confirmed.
            val rig = Rig()
            rig.sealable += BOB
            rig.sync.want(BOB)
            rig.confirmed += BOB
            rig.sync.onPeerFrameOpened(BOB, carriesInit = true)
            assertEquals(IntroState.CONNECTED, rig.sync.state(BOB).first())
            // The answer to Bob's init is the confirming frame for his side — one send, not a storm.
            assertEquals(listOf(BOB, BOB), rig.sent)
            rig.sync.retry()
            assertEquals(2, rig.sent.size)
        }

    @Test
    fun `an already-confirmed peer needs no intro`() =
        runTest {
            val rig = Rig()
            rig.confirmed += BOB
            rig.sealable += BOB
            rig.sync.want(BOB)
            assertEquals(emptyList<String>(), rig.sent)
            assertNull(rig.sync.state(BOB).first())
            assertEquals(emptySet<String>(), rig.sync.pairPeers())
        }

    @Test
    fun `pending intros are capped, oldest first`() =
        runTest {
            val rig = Rig(maxPending = 2)
            rig.sync.want("a-peer")
            rig.now += 1
            rig.sync.want("b-peer")
            rig.now += 1
            rig.sync.want("c-peer")
            assertEquals(setOf("b-peer", "c-peer"), rig.store.pending.keys)
            assertEquals(setOf("b-peer", "c-peer"), rig.sync.pairPeers())
        }

    @Test
    fun `the store is the source of truth across a restart`() =
        runTest {
            val first = Rig()
            first.sync.want(BOB)
            val second = Rig().also { it.store.pending = first.store.pending }
            second.sync.prime()
            assertEquals(IntroState.AWAITING_PREKEY, second.sync.state(BOB).first())
            second.sealable += BOB
            second.sync.retry()
            assertEquals(listOf(BOB), second.sent)
        }

    private companion object {
        const val BOB = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val CAROL = "cccccccccccccccccccccccccc"
    }
}
