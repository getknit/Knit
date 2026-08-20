package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine.OpenOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The epoch-ratchet state machine, driven through an in-memory two-party harness that persists deltas
 * exactly the way `RatchetSessions` will (session snapshot, local/recv epoch maps, skipped keys). Every
 * scenario the mesh forces — reordering, holes, duplicate re-serves, both-initiate races, wipe-and-reset
 * — is a plain-JVM case here.
 */
class RatchetEngineTest {
    private val engine = RatchetEngine()

    private class Frame(
        val header: RatchetEngine.FrameHeader,
        val nonce: ByteArray,
        val ct: ByteArray,
    )

    /** One device: identity + signed prekey + the stored ratchet state the facade would own. */
    private inner class Side(
        val nodeId: String,
    ) {
        val ik = RatchetCrypto.generateKeyPair()
        var spk = RatchetCrypto.generateKeyPair()
        var spkResolvable = true
        var session: RatchetEngine.SessionState? = null
        val localEpochs = mutableMapOf<Int, RatchetEngine.LocalEpoch>()
        val recvEpochs = mutableMapOf<Int, RatchetEngine.RecvEpoch>()
        val skipped = mutableMapOf<Pair<Int, Int>, ByteArray>()
        lateinit var peer: Side
        var lastDelta: RatchetEngine.OpenDelta? = null

        fun initiate(now: Long) {
            val initiation =
                engine.initiate(peer.nodeId, ik.priv, peer.ik.pub, RatchetEngine.PeerPrekey(id = 1, pub = peer.spk.pub), now)
            session = initiation.session
            localEpochs[initiation.epoch.epoch] = initiation.epoch
        }

        fun seal(
            plain: String,
            now: Long,
            force: Boolean = false,
        ): Frame {
            val result = checkNotNull(engine.seal(checkNotNull(session), plain.toByteArray(), AAD, peer.spk.pub, now, force))
            session = result.session
            result.newLocalEpoch?.let { localEpochs[it.epoch] = it }
            return Frame(result.header, result.nonce, result.ct)
        }

        fun open(
            frame: Frame,
            now: Long,
            resetRequested: Boolean = false,
        ): OpenOutcome {
            val ctx =
                RatchetEngine.OpenContext(
                    selfNodeId = nodeId,
                    peerId = peer.nodeId,
                    session = session,
                    recvEpoch = recvEpochs[frame.header.se],
                    skippedMsgKey = skipped[frame.header.se to frame.header.n],
                    ownBasePriv = localEpochs[frame.header.pe]?.priv,
                    ownIkPriv = ik.priv,
                    peerIkPub = peer.ik.pub,
                    spkPrivForInit =
                        frame.header.init
                            ?.takeIf { spkResolvable && it.pkid == 1 }
                            ?.let { spk.priv },
                    resetRequested = resetRequested,
                )
            val outcome = engine.open(ctx, frame.header, frame.nonce, frame.ct, AAD, now)
            if (outcome is OpenOutcome.Opened) apply(outcome.delta, frame.header)
            return outcome
        }

        private fun apply(
            delta: RatchetEngine.OpenDelta,
            header: RatchetEngine.FrameHeader,
        ) {
            lastDelta = delta
            if (delta.purgePeerRecvState) {
                recvEpochs.clear()
                skipped.clear()
            }
            session = delta.session
            delta.recvEpoch?.let { recvEpochs[it.epoch] = it }
            delta.skippedInserts.forEach { skipped[it.epoch to it.idx] = it.msgKey }
            if (delta.consumedSkippedIdx != null) skipped.remove(header.se to header.n)
        }

        /** A device wipe: ratchet state gone, identity + prekeys (identity.key) intact. */
        fun wipe() {
            session = null
            localEpochs.clear()
            recvEpochs.clear()
            skipped.clear()
        }
    }

    private fun pair(
        firstId: String = "aaaaaaaa",
        secondId: String = "bbbbbbbb",
    ): Pair<Side, Side> {
        val a = Side(firstId)
        val b = Side(secondId)
        a.peer = b
        b.peer = a
        return a to b
    }

    private fun text(outcome: OpenOutcome): String = String((outcome as OpenOutcome.Opened).plaintext)

    // --- establishment ---

    @Test
    fun initFirstMessageAndReplyConfirmBothSides() {
        val (a, b) = pair()
        a.initiate(NOW)

        val first = a.seal("hello", NOW)
        assertEquals(1, first.header.se)
        assertEquals(0, first.header.pe)
        assertNotNull(first.header.init)

        assertEquals("hello", text(b.open(first, NOW)))
        assertTrue(checkNotNull(b.session).confirmed)
        assertFalse(checkNotNull(b.session).weAreInitiator)

        val reply = b.seal("hi back", NOW)
        assertEquals(1, reply.header.se)
        assertEquals(1, reply.header.pe)
        assertNull(reply.header.init)

        assertEquals("hi back", text(a.open(reply, NOW)))
        assertTrue(checkNotNull(a.session).confirmed)
        assertNull(a.seal("post-confirm", NOW).header.init)
    }

    @Test
    fun bothSidesDeriveTheSamePairwiseExportRoot() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("x", NOW), NOW)

        assertArrayEquals(
            RatchetCrypto.exportRoot(checkNotNull(a.session).root),
            RatchetCrypto.exportRoot(checkNotNull(b.session).root),
        )
    }

    // --- reordering, duplicates, holes ---

    @Test
    fun outOfOrderArrivalUsesSkippedKeysExactlyOnce() {
        val (a, b) = pair()
        a.initiate(NOW)
        val m0 = a.seal("m0", NOW)
        val m1 = a.seal("m1", NOW)
        val m2 = a.seal("m2", NOW)

        assertEquals("m2", text(b.open(m2, NOW)))
        assertEquals(2, b.skipped.size)

        assertEquals("m0", text(b.open(m0, NOW)))
        assertEquals(1, b.skipped.size)
        assertTrue(b.open(m0, NOW) === OpenOutcome.Failed.DUPLICATE)

        assertEquals("m1", text(b.open(m1, NOW)))
        assertTrue(b.skipped.isEmpty())
    }

    @Test
    fun skippedKeyStillOpensAfterItsEpochRowWasSwept() {
        val (a, b) = pair()
        a.initiate(NOW)
        val m0 = a.seal("m0", NOW)
        b.open(a.seal("m1", NOW).also { a.seal("m2", NOW) }, NOW)

        b.recvEpochs.clear()
        assertEquals("m0", text(b.open(m0, NOW)))
        assertTrue(b.recvEpochs.isEmpty())
    }

    @Test
    fun aWhollyLostEpochLosesOnlyItself() {
        val (a, b) = pair()
        a.initiate(NOW)
        a.seal("lost-0", NOW)
        a.seal("lost-1", NOW)

        val fresh = a.seal("epoch-2", NOW, force = true)
        assertEquals(2, fresh.header.se)
        assertEquals("epoch-2", text(b.open(fresh, NOW)))
    }

    // --- epoch advance rules ---

    @Test
    fun epochAdvancesAtTheMessageCountCap() {
        val (a, _) = pair()
        a.initiate(NOW)
        repeat(RatchetEngine.MAX_EPOCH_MESSAGES) { assertEquals(1, a.seal("m$it", NOW).header.se) }
        assertEquals(2, a.seal("overflow", NOW).header.se)
    }

    @Test
    fun epochAdvancesAtTheAgeCap() {
        val (a, _) = pair()
        a.initiate(NOW)
        assertEquals(1, a.seal("young", NOW).header.se)
        assertEquals(2, a.seal("old", NOW + RatchetEngine.MAX_EPOCH_AGE_MS).header.se)
    }

    @Test
    fun epochAdvancesOnTheFirstSendAfterAPeerContribution() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        a.open(b.seal("reply", NOW), NOW)

        val healed = a.seal("healed", NOW)
        assertEquals(2, healed.header.se)
        assertEquals(1, healed.header.pe)
        assertEquals("healed", text(b.open(healed, NOW)))
    }

    // --- typed failures ---

    @Test
    fun aFrameWithoutInitToAFreshDeviceIsNoSession() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("establish", NOW), NOW)
        val confirmed = b.seal("no init attached", NOW)

        val (_, stranger) = pair(firstId = a.nodeId, secondId = "cccccccc")
        stranger.peer = b
        assertTrue(stranger.open(confirmed, NOW) === OpenOutcome.Failed.NO_SESSION)
    }

    @Test
    fun anInitAgainstAPrunedPrekeyIsEpochGone() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.spkResolvable = false
        assertTrue(b.open(a.seal("hello", NOW), NOW) === OpenOutcome.Failed.EPOCH_GONE)
    }

    @Test
    fun aFrameAgainstADeletedOwnEpochIsEpochGone() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        val reply = b.seal("reply", NOW)

        a.localEpochs.clear()
        assertTrue(a.open(reply, NOW) === OpenOutcome.Failed.EPOCH_GONE)
    }

    @Test
    fun structurallyInvalidHeadersAreBadHeader() {
        val (a, b) = pair()
        a.initiate(NOW)
        val frame = a.seal("hello", NOW)

        val noInitAtPeZero =
            Frame(RatchetEngine.FrameHeader(se = 1, ek = frame.header.ek, pe = 0, n = 0, init = null), frame.nonce, frame.ct)
        assertTrue(b.open(noInitAtPeZero, NOW) === OpenOutcome.Failed.BAD_HEADER)

        val overflowIndex =
            Frame(
                RatchetEngine.FrameHeader(
                    se = 1,
                    ek = frame.header.ek,
                    pe = 0,
                    n = RatchetEngine.MAX_EPOCH_MESSAGES,
                    init = frame.header.init,
                ),
                frame.nonce,
                frame.ct,
            )
        assertTrue(b.open(overflowIndex, NOW) === OpenOutcome.Failed.BAD_HEADER)
    }

    @Test
    fun aTamperedCiphertextIsAeadFail() {
        val (a, b) = pair()
        a.initiate(NOW)
        val frame = a.seal("hello", NOW)
        val tampered = Frame(frame.header, frame.nonce, frame.ct.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertTrue(b.open(tampered, NOW) === OpenOutcome.Failed.AEAD_FAIL)
    }

    // --- both-initiate race ---

    @Test
    fun bothInitiateRaceConvergesOnTheLowerNodeIdsSession() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW)
        val fromA = a.seal("from a", NOW)
        val fromB = b.seal("from b", NOW)

        // A (lower nodeId) wins on both ends: A keeps its root and archives B's; B adopts A's.
        assertEquals("from b", text(a.open(fromB, NOW)))
        assertTrue(checkNotNull(a.session).weAreInitiator)
        assertNotNull(checkNotNull(a.session).prevRoot)

        assertEquals("from a", text(b.open(fromA, NOW)))
        assertTrue(checkNotNull(b.session).confirmed)
        assertFalse(checkNotNull(b.session).weAreInitiator)
        assertArrayEquals(checkNotNull(a.session).root, checkNotNull(b.session).root)

        // Post-race traffic flows both ways under the winning root; epoch numbering stayed monotone.
        val bNext = b.seal("under the winning root", NOW)
        assertEquals(2, bNext.header.se)
        assertEquals("under the winning root", text(a.open(bNext, NOW)))
        assertTrue(checkNotNull(a.session).confirmed)
        assertEquals("ack", text(b.open(a.seal("ack", NOW), NOW)))
    }

    @Test
    fun theRaceLoserDropsTheRecvStateOfTheEraItAbandons() {
        val (a, b) = pair()
        // Consume a couple of indices under the pre-race era, so B holds recv rows for epoch 1 that a
        // reset-restarted numbering will later collide with.
        a.initiate(NOW)
        assertEquals("one", text(b.open(a.seal("one", NOW), NOW)))
        assertEquals("two", text(b.open(a.seal("two", NOW), NOW)))
        assertEquals(setOf(1), b.recvEpochs.keys)

        // Both sides now re-initiate at each other — two peers resetting each other, which is what the
        // recovery path produces once every undecryptable outcome can request one.
        a.initiate(NOW + 1)
        b.initiate(NOW + 1)
        val fromA = a.seal("after the race", NOW + 1)

        // B loses the nodeId tiebreak and adopts A's root. The rows above describe chains under the era it
        // just abandoned; keeping them makes A's restarted epoch 1 land on a consumed index and read as a
        // duplicate — benign per frame, and a permanent one-way deadlock in aggregate.
        assertEquals("after the race", text(b.open(fromA, NOW + 1)))
        assertTrue("adopting the winner's root must drop the loser's recv state", checkNotNull(b.lastDelta).purgePeerRecvState)
        assertEquals("only the freshly-derived epoch survives", setOf(fromA.header.se), b.recvEpochs.keys)
    }

    @Test
    fun raceLosersInFlightFramesStillDrainViaThePreviousRoot() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW)
        val early0 = b.seal("early 0", NOW)
        val early1 = b.seal("early 1", NOW)

        assertEquals("early 1", text(a.open(early1, NOW)))
        b.open(a.seal("from a", NOW), NOW)

        // A late copy of the loser-root epoch still opens: its receive chain was derived before the
        // race resolved, and chains never need the root again.
        assertEquals("early 0", text(a.open(early0, NOW)))
    }

    @Test
    fun aReservedRaceInitNeverReplacesTheResolvedSession() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW + 5_000)
        val fromB = b.seal("from b", NOW + 5_000)
        val fromA = a.seal("from a", NOW)

        assertEquals("from b", text(a.open(fromB, NOW)))
        assertEquals("from a", text(b.open(fromA, NOW)))
        assertEquals("settle", text(a.open(b.seal("settle", NOW), NOW)))
        assertTrue(checkNotNull(a.session).confirmed)
        val rootAfterRace = checkNotNull(a.session).root

        // The loser's init re-served from custody hours later. Its timestamp (NOW + 5s) is NEWER than
        // the winning session's establishedAt (NOW), so a timestamp-based rule would treat it as a
        // fresh replacement and wreck the session on every re-serve for the whole custody TTL; the
        // ephemeral-key idempotence match must classify it as already-resolved instead.
        val reServed = a.open(fromB, NOW + 60_000)
        assertTrue(reServed === OpenOutcome.Failed.DUPLICATE)
        assertArrayEquals(rootAfterRace, checkNotNull(a.session).root)
        assertTrue(checkNotNull(a.session).confirmed)
    }

    @Test
    fun aLateRaceInitAfterConfirmationNeverDefectsToTheLosingRoot() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW + 5_000)
        val loserInit = b.seal("from b, losing root", NOW + 5_000)

        // The race resolves entirely through A's frames: B adopts A's root and replies; A confirms
        // WITHOUT ever having processed B's init (its idempotence anchor was never recorded).
        b.open(a.seal("from a", NOW), NOW)
        a.open(b.seal("reply under the winner", NOW), NOW)
        assertTrue(checkNotNull(a.session).confirmed)
        assertNull(checkNotNull(a.session).peerInitEphPub)
        val winningRoot = checkNotNull(a.session).root

        // B's original losing-root frame finally re-serves from custody, init timestamp NEWER than the
        // session A confirmed. It must fail benignly — never replace the session both sides share.
        val outcome = a.open(loserInit, NOW + 60_000)
        assertTrue(outcome === OpenOutcome.Failed.AEAD_FAIL)
        assertArrayEquals(winningRoot, checkNotNull(a.session).root)
        assertTrue(checkNotNull(a.session).confirmed)
    }

    @Test
    fun anExplicitResetIsAdoptedEvenByTheUnanchoredRaceWinner() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW + 5_000)

        // A wins the race on nodeId and confirms WITHOUT ever processing B's init, so it holds no
        // idempotence anchor — the state the race-remnant guard exists for.
        b.open(a.seal("from a", NOW), NOW)
        a.open(b.seal("reply under the winner", NOW), NOW)
        assertTrue(checkNotNull(a.session).confirmed)
        assertNull(checkNotNull(a.session).peerInitEphPub)
        assertTrue("precondition: B is the higher nodeId the guard refuses", b.nodeId > a.nodeId)

        // B loses its state and explicitly asks to re-establish. Unflagged this is indistinguishable
        // from a re-served race remnant and is refused (aLateRaceInitAfterConfirmationNeverDefects...);
        // flagged, it must be adopted, because refusing it leaves B unable to recover from its own side
        // at all — only A's 6 h reset heuristic could ever clear it, and that is the six-hour
        // one-directional blackout ADR 024 was opened for.
        b.wipe()
        b.initiate(NOW + 600_000)
        val outcome = a.open(b.seal("re-establish", NOW + 600_000), NOW + 600_000, resetRequested = true)

        assertEquals("re-establish", text(outcome))
        assertFalse("adopting a reset makes us the responder", checkNotNull(a.session).weAreInitiator)
        assertArrayEquals(checkNotNull(b.session).root, checkNotNull(a.session).root)
    }

    @Test
    fun adoptingAResetAnchorsItSoItsOwnReServesAreInert() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW + 5_000)
        b.open(a.seal("from a", NOW), NOW)
        a.open(b.seal("reply under the winner", NOW), NOW)

        b.wipe()
        b.initiate(NOW + 600_000)
        val reset = b.seal("re-establish", NOW + 600_000)
        a.open(reset, NOW + 600_000, resetRequested = true)
        val adopted = checkNotNull(a.session).root

        // Custody re-serves the reset for a full TTL. The ephemeral recorded on adoption is what makes
        // every one of those inert, so the exemption cannot be turned into a re-rooting loop.
        val again = a.open(reset, NOW + 900_000, resetRequested = true)
        assertTrue(again === OpenOutcome.Failed.DUPLICATE)
        assertArrayEquals(adopted, checkNotNull(a.session).root)
    }

    @Test
    fun theSideThatAdoptedAReplacementCanStillSendBack() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        a.open(b.seal("hi", NOW), NOW)

        // A re-initiates (a reset): B must adopt it, which nulls B's peer base epoch.
        a.initiate(NOW + 600_000)
        assertEquals("re-established", text(b.open(a.seal("re-established", NOW + 600_000), NOW + 600_000)))

        // B, the adopter, replies. Its peerBaseEpoch is 0, so the frame carries pe=0 and no init.
        val back = a.open(b.seal("reply after adopting", NOW + 601_000), NOW + 601_000)
        assertEquals("reply after adopting", text(back))
    }

    // --- wipe and replacement ---

    @Test
    fun aWipedPeersReInitReplacesTheSessionAndPurgesStaleRecvState() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("before the wipe", NOW), NOW)
        a.open(b.seal("reply", NOW), NOW)

        a.wipe()
        a.initiate(NOW + 10_000)
        val reborn = a.seal("after the wipe", NOW + 10_000)

        assertEquals("after the wipe", text(b.open(reborn, NOW + 10_000)))
        assertTrue(checkNotNull(b.lastDelta).purgePeerRecvState)
        assertEquals(NOW + 10_000, checkNotNull(b.session).establishedAt)
        assertEquals(setOf(1), b.recvEpochs.keys)

        // B's next send reaches the reborn A; B's own epoch numbering never reset.
        val toReborn = b.seal("welcome back", NOW + 10_000)
        assertTrue(toReborn.header.se >= 2)
        assertEquals("welcome back", text(a.open(toReborn, NOW + 10_000)))
    }

    @Test
    fun oldEraFramesDrainViaPrevRootWhenEpochNumbersDoNotCollide() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("establish", NOW), NOW)
        a.seal("burn epoch 1", NOW)
        val oldEra = a.seal("old era, epoch 2", NOW, force = true)

        a.wipe()
        a.initiate(NOW + 10_000)
        b.open(a.seal("new era", NOW + 10_000), NOW + 10_000)

        // The pre-wipe frame's epoch (2) does not collide with the new era's (1): the purged recv state
        // forces a fresh derivation, which fails under the new root and succeeds under the draining one.
        assertEquals("old era, epoch 2", text(b.open(oldEra, NOW + 10_000)))
    }

    @Test
    fun oldEraFramesWhoseEpochNumberCollidesFailBenignly() {
        val (a, b) = pair()
        a.initiate(NOW)
        val old0 = a.seal("old era, epoch 1, n=0", NOW)
        val old1 = a.seal("old era, epoch 1, n=1", NOW)

        a.wipe()
        a.initiate(NOW + 10_000)
        b.open(a.seal("new era, epoch 1", NOW + 10_000), NOW + 10_000)

        // Both eras used se=1 and the new era owns the recv row now, so the old frames cannot decrypt:
        // an index below the new chain's cursor reads as a duplicate, one at/above it fails the AEAD.
        // Benign by design — anything delivered pre-wipe is skipped by the exists-gate upstream, and
        // the reset path re-seals undelivered traffic; this asserts the failure is contained, not silent.
        assertTrue(b.open(old0, NOW + 10_000) === OpenOutcome.Failed.DUPLICATE)
        assertTrue(b.open(old1, NOW + 10_000) === OpenOutcome.Failed.AEAD_FAIL)
    }

    /**
     * The invariant `InboundPipeline.isLiveEvidence` rests on (ADR 026): [RatchetEngine.SessionState.establishedAt]
     * is OUR clock exactly when [RatchetEngine.SessionState.weAreInitiator], and the peer's `InitPayload.at` otherwise.
     * That is what lets the era gate know whether it may compare `establishedAt` against a frame's
     * `sentAt` at all — the responder half is single-clock and exact, the initiator half is not.
     *
     * Every site that writes `establishedAt` is walked here, each with a *local* clock deliberately
     * different from the init's `at`, so a site that starts sourcing the wrong one cannot pass by
     * coincidence. A fifth site added without honouring this silently disarms the heuristic under skew.
     */
    @Test
    fun establishedAtIsOurOwnClockExactlyWhenWeAreTheInitiator() {
        val aEra = NOW
        val bLocal = NOW + 3 * 60 * 60_000L

        // 1. initiate: our own clock.
        val (a, b) = pair()
        a.initiate(aEra)
        assertTrue(checkNotNull(a.session).weAreInitiator)
        assertEquals(aEra, checkNotNull(a.session).establishedAt)

        // 2. responder establish: the peer's init.at, NOT the clock we opened it on.
        b.open(a.seal("hello", aEra), bLocal)
        assertFalse(checkNotNull(b.session).weAreInitiator)
        assertEquals(aEra, checkNotNull(b.session).establishedAt)

        // 3. replacement adopt (the peer lost its state and re-initiated): the new init.at.
        val reEra = aEra + 10_000
        a.wipe()
        a.initiate(reEra)
        b.open(a.seal("after my wipe", reEra), bLocal + 10_000)
        assertFalse(checkNotNull(b.session).weAreInitiator)
        assertEquals(reEra, checkNotNull(b.session).establishedAt)

        // 4. both-initiate race. The smaller nodeId wins, so `c` keeps its own stamp and `d` — adopting
        //    the winner's root — takes the winner's clock with it.
        val (c, d) = pair()
        val cEra = NOW + 60_000
        val dEra = NOW + 90_000
        c.initiate(cEra)
        d.initiate(dEra)
        val fromC = c.seal("mine", cEra)
        val fromD = d.seal("no, mine", dEra)
        d.open(fromC, dEra)
        c.open(fromD, cEra)

        assertFalse("the larger nodeId adopts the winner's root", checkNotNull(d.session).weAreInitiator)
        assertEquals(cEra, checkNotNull(d.session).establishedAt)
        assertTrue("the race winner stays the initiator", checkNotNull(c.session).weAreInitiator)
        assertEquals(cEra, checkNotNull(c.session).establishedAt)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        val AAD = "id|sender|1700000000000|thread".toByteArray()
    }
}
