package app.getknit.knit.mesh.crypto.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-level tests for the group sender-key ratchet: an N-party in-memory harness (the
 * [RatchetEngineTest] `Side` discipline, minus sessions — there are none) driving real seals, seed
 * adoptions, and opens through the mesh's delivery pathology: loss, reordering, duplication, forced
 * churn, and a wiped sender's re-mint.
 */
class GroupRatchetEngineTest {
    private val engine = GroupRatchetEngine()

    private class Frame(
        val id: String,
        val senderId: String,
        val header: GroupRatchetEngine.FrameHeader,
        val nonce: ByteArray,
        val ct: ByteArray,
    )

    /** One member's persisted state for [GROUP], as the store would hold it. */
    private inner class Member(
        val nodeId: String,
    ) {
        var send: GroupRatchetEngine.SendChain? = null
        val recv = mutableMapOf<Triple<String, Int, Long>, GroupRatchetEngine.RecvChain>()
        val skipped = mutableMapOf<Triple<String, Int, Int>, MutableList<GroupRatchetEngine.SkippedKey>>()

        fun mint(now: Long): GroupRatchetEngine.SendChain =
            engine.mint(GROUP, nodeId, prevEpoch = send?.epoch ?: 0, now = now).also { send = it }

        fun adopt(
            from: Member,
            chain: GroupRatchetEngine.SendChain,
            now: Long,
        ): GroupRatchetEngine.AdoptOutcome {
            val newest =
                recv.entries
                    .filter { it.key.first == from.nodeId && it.key.second == chain.epoch }
                    .maxByOrNull { it.key.third }
                    ?.value
            val outcome = engine.adoptSeed(newest, GROUP, from.nodeId, chain.epoch, chain.seed, chain.mintedAt, now)
            if (outcome is GroupRatchetEngine.AdoptOutcome.Adopt) {
                recv[Triple(from.nodeId, chain.epoch, chain.mintedAt)] = outcome.recv
            }
            return outcome
        }

        fun seal(
            id: String,
            body: String,
            now: Long,
        ): Frame {
            if (engine.needsNewEpoch(send, now)) mint(now)
            val result = checkNotNull(engine.seal(checkNotNull(send), body.toByteArray(), aad(id)))
            send = result.chain
            return Frame(id, nodeId, result.header, result.nonce, result.ct)
        }

        fun open(
            frame: Frame,
            now: Long,
        ): GroupRatchetEngine.OpenOutcome {
            val chains =
                recv.entries
                    .filter { it.key.first == frame.senderId && it.key.second == frame.header.se }
                    .sortedByDescending { it.key.third }
                    .map { it.value }
            val keys = skipped[Triple(frame.senderId, frame.header.se, frame.header.n)].orEmpty()
            val outcome =
                engine.open(
                    GroupRatchetEngine.OpenContext(chains = chains, skippedMsgKeys = keys),
                    frame.header,
                    frame.nonce,
                    frame.ct,
                    aad(frame.id),
                    now,
                )
            if (outcome is GroupRatchetEngine.OpenOutcome.Opened) {
                val delta = outcome.delta
                delta.recvChain?.let { recv[Triple(frame.senderId, it.epoch, it.mintedAt)] = it }
                delta.skippedInserts.forEach {
                    skipped.getOrPut(Triple(frame.senderId, it.epoch, it.idx)) { mutableListOf() } += it
                }
                delta.consumedSkippedIdx?.let { skipped.remove(Triple(frame.senderId, frame.header.se, it)) }
            }
            return outcome
        }

        fun openedText(
            frame: Frame,
            now: Long,
        ): String = String((open(frame, now) as GroupRatchetEngine.OpenOutcome.Opened).plaintext)

        fun wipe() {
            send = null
            recv.clear()
            skipped.clear()
        }
    }

    private fun aad(id: String): ByteArray = "$id|$GROUP".toByteArray()

    @Test
    fun sealAndOpenRoundTripAcrossThreeParties() {
        val (alice, bob, carol) = Triple(Member("alice000"), Member("bob00000"), Member("carol000"))
        val seed = alice.mint(now = 1L)
        bob.adopt(alice, seed, now = 2L)
        carol.adopt(alice, seed, now = 2L)

        val frames = (0..2).map { alice.seal("m$it", "hello $it", now = 3L) }

        frames.forEachIndexed { i, frame ->
            assertEquals("hello $i", bob.openedText(frame, now = 4L))
            assertEquals("hello $i", carol.openedText(frame, now = 4L))
        }
    }

    @Test
    fun outOfOrderFramesGapFillAndSkippedKeysConsumeOnce() {
        val alice = Member("alice000")
        val bob = Member("bob00000")
        bob.adopt(alice, alice.mint(now = 1L), now = 1L)
        val frames = (0..3).map { alice.seal("m$it", "msg $it", now = 2L) }

        // Newest first: indices 0..2 are banked as skipped keys.
        assertEquals("msg 3", bob.openedText(frames[3], now = 3L))
        // A banked index opens from its stored key…
        assertEquals("msg 1", bob.openedText(frames[1], now = 4L))
        // …exactly once: the re-served copy is a benign duplicate.
        assertEquals(GroupRatchetEngine.OpenOutcome.Failed.DUPLICATE, bob.open(frames[1], now = 5L))
        assertEquals("msg 0", bob.openedText(frames[0], now = 6L))
        assertEquals("msg 2", bob.openedText(frames[2], now = 7L))
    }

    @Test
    fun aReDeliveredInOrderFrameIsDuplicate() {
        val alice = Member("alice000")
        val bob = Member("bob00000")
        bob.adopt(alice, alice.mint(now = 1L), now = 1L)
        val frame = alice.seal("m0", "hi", now = 2L)

        assertEquals("hi", bob.openedText(frame, now = 3L))
        assertEquals(GroupRatchetEngine.OpenOutcome.Failed.DUPLICATE, bob.open(frame, now = 4L))
    }

    @Test
    fun aFrameBeforeItsSeedIsNoKey() {
        val alice = Member("alice000")
        val bob = Member("bob00000")
        alice.mint(now = 1L)
        val frame = alice.seal("m0", "early", now = 2L)

        assertEquals(GroupRatchetEngine.OpenOutcome.Failed.NO_KEY, bob.open(frame, now = 3L))
    }

    @Test
    fun aWhollyLostEpochNeverWedgesLaterEpochs() {
        val alice = Member("alice000")
        val bob = Member("bob00000")
        val epoch1 = alice.mint(now = 1L)
        alice.seal("lost0", "never arrives", now = 2L)
        alice.seal("lost1", "never arrives", now = 2L)
        val epoch2 = alice.mint(now = 3L)
        // bob never got epoch 1's seed nor frames; only epoch 2 reaches him.
        bob.adopt(alice, epoch2, now = 4L)

        val frame = alice.seal("m0", "fresh epoch", now = 5L)

        assertEquals(2, epoch2.epoch)
        assertFalse(epoch1.seed.contentEquals(epoch2.seed))
        assertEquals("fresh epoch", bob.openedText(frame, now = 6L))
    }

    @Test
    fun adoptionIsIdempotentAndNeverRewindsTheChain() {
        val alice = Member("alice000")
        val bob = Member("bob00000")
        val seed = alice.mint(now = 1L)
        assertTrue(bob.adopt(alice, seed, now = 2L) is GroupRatchetEngine.AdoptOutcome.Adopt)
        val frame0 = alice.seal("m0", "one", now = 3L)
        assertEquals("one", bob.openedText(frame0, now = 4L))

        // A custody re-serve of the same distribution: recognized, chain position untouched.
        assertEquals(GroupRatchetEngine.AdoptOutcome.AlreadyKnown, bob.adopt(alice, seed, now = 5L))
        assertEquals(GroupRatchetEngine.OpenOutcome.Failed.DUPLICATE, bob.open(frame0, now = 6L))
    }

    @Test
    fun anOlderMintOfAHeldEpochIsStale() {
        val alice = Member("alice000")
        val bob = Member("bob00000")
        val old = alice.mint(now = 10L)
        alice.wipe()
        val remint = alice.mint(now = 20L)
        bob.adopt(alice, remint, now = 21L)

        assertEquals(GroupRatchetEngine.AdoptOutcome.Stale, bob.adopt(alice, old, now = 22L))
    }

    @Test
    fun aWipedSendersReMintDrainsTheOldEraInsteadOfBreakingIt() {
        val alice = Member("alice000")
        val bob = Member("bob00000")
        bob.adopt(alice, alice.mint(now = 1L), now = 1L)
        val preWipe = (0..1).map { alice.seal("old$it", "old $it", now = 2L) }
        assertEquals("old 0", bob.openedText(preWipe[0], now = 3L))

        alice.wipe()
        bob.adopt(alice, alice.mint(now = 10L), now = 11L) // re-mint: epoch 1 again, newer mintedAt
        val postWipe = alice.seal("new0", "new 0", now = 12L)

        // New era opens on the newer chain; the old era's in-flight frame still opens on the draining row.
        assertEquals("new 0", bob.openedText(postWipe, now = 13L))
        assertEquals("old 1", bob.openedText(preWipe[1], now = 14L))
    }

    @Test
    fun advanceRulesFireAtCountAndAge() {
        val alice = Member("alice000")
        val chain = alice.mint(now = 0L)

        assertFalse(engine.needsNewEpoch(chain, now = 1L))
        assertTrue(engine.needsNewEpoch(null, now = 1L))
        assertTrue(engine.needsNewEpoch(chain.copy(count = GroupRatchetEngine.MAX_EPOCH_MESSAGES), now = 1L))
        assertTrue(engine.needsNewEpoch(chain, now = GroupRatchetEngine.MAX_EPOCH_AGE_MS))
    }

    @Test
    fun sealRefusesAnExhaustedChain() {
        val alice = Member("alice000")
        val chain = alice.mint(now = 0L).copy(count = GroupRatchetEngine.MAX_EPOCH_MESSAGES)

        assertNull(engine.seal(chain, "over".toByteArray(), aad("m0")))
    }

    @Test
    fun malformedHeadersAreRefused() {
        val alice = Member("alice000")
        val bob = Member("bob00000")
        bob.adopt(alice, alice.mint(now = 1L), now = 1L)
        val frame = alice.seal("m0", "hi", now = 2L)

        fun tamper(
            se: Int,
            n: Int,
        ) = bob.open(Frame(frame.id, frame.senderId, GroupRatchetEngine.FrameHeader(se, n), frame.nonce, frame.ct), now = 3L)

        assertEquals(GroupRatchetEngine.OpenOutcome.Failed.BAD_HEADER, tamper(se = 0, n = 0))
        assertEquals(GroupRatchetEngine.OpenOutcome.Failed.BAD_HEADER, tamper(se = 1, n = -1))
        assertEquals(GroupRatchetEngine.OpenOutcome.Failed.BAD_HEADER, tamper(se = 1, n = GroupRatchetEngine.MAX_EPOCH_MESSAGES))
        assertEquals(
            GroupRatchetEngine.OpenOutcome.Failed.BAD_HEADER,
            tamper(se = GroupRatchetEngine.MAX_EPOCH_NUMBER + 1, n = 0),
        )
    }

    @Test
    fun aTamperedIndexFailsTheAeadNotTheLadder() {
        val alice = Member("alice000")
        val bob = Member("bob00000")
        bob.adopt(alice, alice.mint(now = 1L), now = 1L)
        val frame = alice.seal("m0", "hi", now = 2L)

        // Claiming a later index derives a different message key — AEAD refuses, nothing advances.
        val tampered = Frame(frame.id, frame.senderId, GroupRatchetEngine.FrameHeader(se = 1, n = 5), frame.nonce, frame.ct)
        assertEquals(GroupRatchetEngine.OpenOutcome.Failed.AEAD_FAIL, bob.open(tampered, now = 3L))
        // The genuine frame still opens.
        assertEquals("hi", bob.openedText(frame, now = 4L))
    }

    @Test
    fun crossSenderChainsAreIndependent() {
        val alice = Member("alice000")
        val bob = Member("bob00000")
        val carol = Member("carol000")
        alice.adopt(bob, bob.mint(now = 1L), now = 1L)
        alice.adopt(carol, carol.mint(now = 1L), now = 1L)

        val fromBob = bob.seal("b0", "from bob", now = 2L)
        val fromCarol = carol.seal("c0", "from carol", now = 2L)

        assertEquals("from bob", alice.openedText(fromBob, now = 3L))
        assertEquals("from carol", alice.openedText(fromCarol, now = 3L))
        assertArrayEquals(fromBob.header.let { intArrayOf(it.se, it.n) }, fromCarol.header.let { intArrayOf(it.se, it.n) })
    }

    private companion object {
        const val GROUP = "g-0123456789abcdef01234567"
    }
}
