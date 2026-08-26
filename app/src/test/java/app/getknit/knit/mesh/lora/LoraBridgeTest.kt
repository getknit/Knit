package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-pocket bridge (ADR 044), end to end on the JVM.
 *
 * The scenario the plane exists for: two groups of phones, each meshed over BLE/NAN, too far apart to see
 * each other, with one board-holder in each. Both boards hear each other over LoRa. In this rig the LoRa
 * "air" reaches everyone (that is the whole point — the boards ARE in range); what separates the pockets is
 * `onForeignReachable`, which the composite transport populates only from short-range siblings and which
 * therefore means exactly "who is in my BLE/NAN clique". A board-less pocket member needs no transport here:
 * it exists only as a name in that set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoraBridgeTest {
    private var sigCounter = 0

    private fun frame(
        sender: String,
        body: String = "hello",
        sentAt: Long = 0L,
        type: String = FrameType.CHAT,
        recipientId: String? = null,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = type,
                id = "id-$sigCounter",
                senderId = sender,
                sentAt = sentAt,
                recipientId = recipientId,
                payload = WireCodec.encodePayload(ChatContent(body = body)),
            )
        val sig = ByteArray(64)
        sig[0] = (sigCounter shr 8).toByte()
        sig[1] = sigCounter.toByte()
        sigCounter++
        return WireEnvelope(sig = sig, signed = WireCodec.encodeEnvelope(env))
    }

    private fun idOf(wire: WireEnvelope) = WireCodec.decodeEnvelope(wire.signed)!!.id

    private fun prefixOf(wire: WireEnvelope) = LoraCtl.prefixOf(StoreDigest.hash64(idOf(wire)))

    /**
     * Stands in for `MeshManager`'s [app.getknit.knit.mesh.BridgeFrameSource] over `ForwardStore.liveFrames`:
     * a held set, the same prefix computation, and the same "what does their offer not name" diff.
     */
    private class FakeCustody {
        val held = mutableListOf<WireEnvelope>()
        var served = 0

        fun prefixes(limit: Int): IntArray = held.takeLast(limit).map { LoraCtl.prefixOf(StoreDigest.hash64(idFor(it))) }.toIntArray()

        fun missing(
            theirs: IntArray,
            limit: Int,
        ): List<WireEnvelope> {
            val have = theirs.toHashSet()
            return held
                .filter { LoraCtl.prefixOf(StoreDigest.hash64(idFor(it))) !in have }
                .sortedBy { LoraFramePolicy.backfillRank(WireCodec.decodeEnvelope(it.signed)!!) }
                .take(limit)
                .also { served += it.size }
        }

        private fun idFor(w: WireEnvelope) = WireCodec.decodeEnvelope(w.signed)!!.id
    }

    private class Rig(
        val node: String,
        val transport: LoraMeshTransport,
        val link: FakeMeshtasticLink,
        val metrics: MeshMetrics,
        val custody: FakeCustody,
        val received: MutableList<InboundFrame>,
    ) {
        fun status() = transport.status.value
    }

    private fun rig(
        air: FakeMeshtasticAir,
        nodeNum: UInt,
        node: String,
        scope: CoroutineScope,
        wallClock: (() -> Long)? = null,
        now: () -> Long,
    ): Rig {
        val link = FakeMeshtasticLink(nodeNum, air)
        val metrics = MeshMetrics()
        val custody = FakeCustody()
        // A node's own profile is in its own custody (`ORIGIN_SELF`), which is why its offer names it and a
        // far gateway never serves it back. One stable frame, like `MeshManager.signedProfile`'s stable id.
        val ownProfile = frame(node, type = FrameType.PROFILE, body = "p")
        custody.held += ownProfile
        val transport =
            LoraMeshTransport(
                selfId = { node },
                link = link,
                config = MutableStateFlow(LoraConfig("AA:$nodeNum", 0)),
                selfProfile = { ownProfile },
                scope = scope,
                metrics = metrics,
                clock = now,
                wallClock = wallClock ?: now,
                pace = LoraPacePolicy(minGapMs = 0),
                // No jitter: an offer goes out at exactly the midpoint of its interval.
                gossip = LoraGossipPolicy(random = { 0 }),
                offerPrefixes = { custody.prefixes(it) },
                framesMissing = { theirs, limit, _ -> custody.missing(theirs, limit) },
            )
        val received = mutableListOf<InboundFrame>()
        scope.launch {
            transport.inbound.collect {
                received += it
                // What `ForwardSync.onSeen` does for real on every first-seen relayed frame. Modelling it
                // matters here: it is what makes the far gateway's *next* offer name the frame, which is what
                // stops the bridge serving it again every round.
                custody.held += it.wire
            }
        }
        return Rig(node, transport, link, metrics, custody, received)
    }

    /** Long enough for the first gossip interval's midpoint (5 min / 2) plus a few pacer turns. */
    private val toFirstOffer = LoraGossipPolicy.MIN_INTERVAL_MS / 2 + 10_000

    @Test
    fun aFrameAuthoredByABoardLessPocketMemberAlreadyCrossesLive() =
        runTest {
            // The half of the bridge that shipped with ADR 038/039 and has never been pinned: onDeliver
            // re-fans a *relayed* frame, and nothing on the fan-out path checks authorship.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            a.transport.onForeignReachable(setOf("a2"))
            b.transport.onForeignReachable(setOf("b2"))
            runCurrent()

            val fromA2 = frame("a2", body = "posted in pocket A", sentAt = testScheduler.currentTime)
            a.transport.fastFanout(fromA2)
            advanceTimeBy(5_000)
            runCurrent()

            assertTrue(
                "pocket B's gateway heard a frame nobody in its pocket authored",
                b.received.any { it.envelope.id == idOf(fromA2) },
            )
        }

    @Test
    fun aStaleFrameTheLiveFanOutRefusesStillCrossesViaTheBridge() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            a.transport.onForeignReachable(setOf("a2"))
            b.transport.onForeignReachable(setOf("b2"))
            runCurrent()

            // Said in pocket A while B's board was off, and now well past the freshness gate.
            val old = frame("a2", body = "said an hour ago", sentAt = 0L)
            advanceTimeBy(LoraFramePolicy.FRESH_MS + 60_000)
            runCurrent()
            a.transport.fastFanout(old)
            advanceTimeBy(5_000)
            runCurrent()
            assertFalse("the live plane refuses a custody re-serve", b.received.any { it.envelope.id == idOf(old) })
            assertTrue(a.metrics.snapshot().loraSuppressed > 0)

            // Let the two pockets converge on what they already hold, then introduce the stale frame.
            repeat(2) {
                advanceTimeBy(LoraGossipPolicy.MAX_INTERVAL_MS)
                runCurrent()
            }
            assertTrue("bob offered", b.metrics.snapshot().loraOfferSent > 0)
            assertTrue("alice heard it", a.metrics.snapshot().loraOfferReceived > 0)
            val bridgedBefore = a.metrics.snapshot().loraBridged

            // The bridge reads it from custody instead, on the next round of offers.
            a.custody.held += old
            repeat(3) {
                advanceTimeBy(LoraGossipPolicy.MAX_INTERVAL_MS)
                runCurrent()
            }

            assertTrue("which lands in pocket B", b.received.any { it.envelope.id == idOf(old) })
            assertEquals(
                "served once and not again, because bob's next offer names it",
                bridgedBefore + 1,
                a.metrics.snapshot().loraBridged,
            )
        }

    @Test
    fun theBridgeServesOnlyWhatTheOfferDoesNotName() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            val shared = frame("a2", body = "both pockets have this")
            val onlyA = frame("a2", body = "only pocket A has this")
            a.custody.held += listOf(shared, onlyA)
            b.custody.held += shared

            advanceTimeBy(toFirstOffer)
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            assertTrue("the frame B lacks crosses", b.received.any { it.envelope.id == idOf(onlyA) })
            assertFalse(
                "the frame B already holds is not re-sent",
                b.received.any { it.envelope.id == idOf(shared) },
            )
        }

    @Test
    fun aSecondBoardInThePocketGoesPassiveAndPutsNothingOnTheAir() =
        runTest {
            val air = FakeMeshtasticAir()
            // Two board-holders in pocket A. They see each other over BLE/NAN, so exactly one should speak.
            val a1 = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val a2 = rig(air, 3u, "amber", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a1.transport.start()
            a2.transport.start()
            b.transport.start()
            a1.transport.onForeignReachable(setOf("amber"))
            a2.transport.onForeignReachable(setOf("alice"))
            b.transport.onForeignReachable(emptySet())
            runCurrent()

            // Let the offers settle the election.
            advanceTimeBy(toFirstOffer)
            runCurrent()
            advanceTimeBy(2 * LoraGossipPolicy.MIN_INTERVAL_MS)
            runCurrent()

            val roles = listOf(a1, a2).map { it.status().role }
            assertEquals(
                "exactly one board in the pocket is active",
                1,
                roles.count { it == LoraGatewayPolicy.Role.ACTIVE },
            )
            val passive = listOf(a1, a2).first { it.status().role == LoraGatewayPolicy.Role.PASSIVE }
            val active = listOf(a1, a2).first { it.status().role == LoraGatewayPolicy.Role.ACTIVE }
            val passiveSentBefore = passive.link.sent.size

            // A frame the whole pocket sees is fanned by both phones; only one of them may reach the air.
            val post = frame("a4", body = "one copy please", sentAt = testScheduler.currentTime)
            a1.transport.fastFanout(post)
            a2.transport.fastFanout(post)
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals("the passive board transmitted nothing", passiveSentBefore, passive.link.sent.size)
            assertTrue(passive.metrics.snapshot().loraPassive > 0)
            assertTrue("the active one carried it", active.metrics.snapshot().loraSent > 0)
            assertEquals("bob got exactly one copy", 1, b.received.count { it.envelope.id == idOf(post) })

            // And bob, in the other pocket, is never suppressed by either of them.
            assertEquals(LoraGatewayPolicy.Role.ACTIVE, b.status().role)
        }

    @Test
    fun aFarGatewayLeavingTheAirDoesNotStopTheOtherPocketBridging() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            advanceTimeBy(toFirstOffer)
            runCurrent()

            b.transport.stop()
            advanceTimeBy(60_000)
            runCurrent()

            // Alice's board is now alone; she must stay active rather than deferring to a gateway that left.
            assertEquals(LoraGatewayPolicy.Role.ACTIVE, a.status().role)
            val post = frame("a2", body = "still bridging", sentAt = testScheduler.currentTime)
            val before = a.link.sent.size
            a.transport.fastFanout(post)
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(a.link.sent.size > before)
        }

    @Test
    fun aTransientListenerIsBackfilledWithoutMultiplyingWhatTheGatewaysSay() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            a.custody.held += frame("a2", body = "pocket A history")
            runCurrent()

            // Someone wanders into range with a board and an empty store, in nobody's pocket.
            val t = rig(air, 9u, "trav", backgroundScope) { testScheduler.currentTime }
            t.transport.start()
            runCurrent()
            advanceTimeBy(toFirstOffer)
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            assertTrue("the newcomer is served the history it lacks", t.received.isNotEmpty())
            assertTrue(
                "and it is served the author's profile first, so it can verify anything at all",
                a.metrics.snapshot().loraSent > 0,
            )
        }

    @Test
    fun onePublishersRepeatedOffersCannotDragTheWholeStoreOntoTheAir() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            repeat(40) { a.custody.held += frame("a2", body = "history $it") }

            // Bob keeps announcing what he holds, inside one serve window.
            repeat(6) {
                advanceTimeBy(LoraGossipPolicy.MIN_INTERVAL_MS)
                runCurrent()
            }

            val bridged = a.metrics.snapshot().loraBridged
            assertTrue("some history crossed", bridged > 0)
            assertTrue(
                "but one publisher cannot exceed its hourly allowance ($bridged)",
                bridged <= LoraMeshTransport.SERVE_CAP_PER_HOUR,
            )
        }
}
