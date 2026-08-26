package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoraMeshTransportTest {
    private var sigCounter = 0

    /** A decodable signed frame with a unique 64-byte sig (the transport never verifies it — only decodes). */
    private fun frame(
        type: String,
        sender: String,
        recipientId: String? = null,
        group: GroupInfo? = null,
        relay: Boolean = true,
        body: String = "hi there",
        sentAt: Long = 0L,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = type,
                id = "id-$sigCounter",
                senderId = sender,
                sentAt = sentAt,
                recipientId = recipientId,
                group = group,
                payload = WireCodec.encodePayload(ChatContent(body = body)),
            )
        val sig = ByteArray(64)
        sig[0] = (sigCounter shr 8).toByte()
        sig[1] = sigCounter.toByte()
        sigCounter++
        return WireEnvelope(relay = relay, sig = sig, signed = WireCodec.encodeEnvelope(env))
    }

    private fun profile(sender: String): WireEnvelope = frame(FrameType.PROFILE, sender, body = "x".repeat(20))

    /** A high-entropy body that will not deflate below the LoRa packet cap, so the frame truly fragments. */
    private fun incompressibleBody(chars: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val rng = kotlin.random.Random(1234)
        return buildString(chars) { repeat(chars) { append(alphabet[rng.nextInt(alphabet.length)]) } }
    }

    private fun profileSource(sender: String): suspend () -> WireEnvelope = { profile(sender) }

    private class Rig(
        val transport: LoraMeshTransport,
        val link: FakeMeshtasticLink,
        val metrics: MeshMetrics,
        val received: MutableList<InboundFrame>,
    )

    private fun rig(
        air: FakeMeshtasticAir,
        nodeNum: UInt,
        selfNode: String,
        scope: kotlinx.coroutines.CoroutineScope,
        config: kotlinx.coroutines.flow.Flow<LoraConfig?> = MutableStateFlow(LoraConfig("AA:$nodeNum", 0)),
        farFrames: suspend (String) -> List<WireEnvelope> = { emptyList() },
        now: () -> Long,
    ): Rig {
        val link = FakeMeshtasticLink(nodeNum, air)
        val metrics = MeshMetrics()
        val transport =
            LoraMeshTransport(
                selfId = { selfNode },
                link = link,
                config = config,
                selfProfile = profileSource(selfNode),
                farFrames = farFrames,
                scope = scope,
                metrics = metrics,
                clock = now,
                wallClock = now,
                pace = LoraPacePolicy(minGapMs = 0),
            )
        val received = mutableListOf<InboundFrame>()
        scope.launch { transport.inbound.collect { received += it } }
        return Rig(transport, link, metrics, received)
    }

    @Test
    fun readyMakesTheTransportHealthyAndBeaconsAProfile() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(TransportHealth.Healthy, a.transport.health.value)
            assertEquals(1L, a.metrics.snapshot().loraSessionUps)
            assertTrue("a self-profile beacon went out on session up", a.link.sent.isNotEmpty())
            a.transport.stop()
        }

    @Test
    fun aRoomChatCrossesToTheOtherNodeAndMarksItReachable() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten"))
            runCurrent()

            val delivered = b.received.firstOrNull { it.envelope.type == FrameType.CHAT && it.envelope.senderId == "alice" }
            assertTrue("bob received alice's room chat over LoRa", delivered != null)
            assertEquals("fromNodeId is the frame's senderId", "alice", delivered!!.fromNodeId)
            assertTrue(
                "bob now sees alice as reachable",
                b.transport.reachable.value
                    .any { it.nodeId == "alice" },
            )
            assertTrue("bob received at least the chat", b.metrics.snapshot().loraReceived >= 1)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aLongRoomChatFragmentsAndReassembles() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = incompressibleBody(400)))
            runCurrent()
            assertTrue("a 300-char post arrives reassembled", b.received.any { it.envelope.senderId == "alice" })
            assertEquals(1L, b.metrics.snapshot().loraReassembled)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aFrameReceivedOverLoraIsNotReFannedBackOverLora() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            val wire = frame(FrameType.CHAT, "alice", body = "echo test")
            a.transport.fastFanout(wire)
            runCurrent()
            val bSentBefore = b.link.sent.size
            // The composite re-calls fastFanout on relay of a received frame; bob must NOT bounce it back.
            b.transport.fastFanout(b.received.first { it.envelope.senderId == "alice" }.wire)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("bob does not re-send a LoRa-received frame over LoRa", bSentBefore, b.link.sent.size)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aSealedDmCrossesOverLoraAndIsNotReFannedBack() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            // The long-range fan-out is the DM's only path onto this plane (ADR 039).
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "sealed bytes"))
            runCurrent()

            val delivered = b.received.firstOrNull { it.envelope.type == FrameType.CHAT && it.envelope.recipientId == "bob" }
            assertTrue("bob received alice's DM over LoRa", delivered != null)
            assertEquals("fromNodeId is the frame's senderId", "alice", delivered!!.fromNodeId)
            assertEquals(1L, a.metrics.snapshot().loraDmSent)
            assertEquals(1L, b.metrics.snapshot().loraDmReceived)

            // The pipeline re-fans a relayed DM over the long-range plane; a copy heard over LoRa must not bounce.
            val bSentBefore = b.link.sent.size
            b.transport.longRangeFanout(delivered.wire)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("bob does not re-send a LoRa-received DM over LoRa", bSentBefore, b.link.sent.size)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aStaleChatIsNotFannedButAProfileAndAFreshChatAre() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(20 * 60_000) // 20 min into the session
            val baseline = a.link.sent.size

            // A custody re-serve: a room post / DM stamped 20 min ago re-enters the pipeline and is re-fanned.
            a.transport.fastFanout(frame(FrameType.CHAT, "carol", body = "old room post", sentAt = 0L))
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "alice", body = "old dm", sentAt = 0L))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("stale chat never rides a live plane", baseline, a.link.sent.size)
            assertEquals(2L, a.metrics.snapshot().loraSuppressed)

            // A peer's profile carries its publish stamp (hours old) and is the key bootstrap — never refused.
            a.transport.fastFanout(frame(FrameType.PROFILE, "carol", body = "x".repeat(20), sentAt = 0L))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("an old profile still rides", baseline + 1, a.link.sent.size)

            val now = testScheduler.currentTime
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "alice", body = "fresh dm", sentAt = now))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("a fresh DM rides", baseline + 2, a.link.sent.size)
            a.transport.stop()
        }

    @Test
    fun aFirstHearingBeaconsAfterASixtySecondGapWhileSessionUpKeepsTheFloor() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            assertEquals("session-up beacon", 1, a.link.sent.size)

            // Two minutes later bob comes up and beacons; alice has never heard him, so she beacons again —
            // her last beacon was inside the 5-min floor but past the 60-s first-hearing gap.
            advanceTimeBy(2 * 60_000)
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            b.transport.start()
            runCurrent()
            assertEquals("alice re-beacons for a newly heard peer", 2, a.link.sent.size)
            assertEquals("bob's own session-up beacon; alice's reply is inside his gap", 1, b.link.sent.size)

            // Ten seconds later carol comes up: inside everyone's 60-s gap, so nobody re-beacons.
            advanceTimeBy(10_000)
            val c = rig(air, 3u, "carol", backgroundScope) { testScheduler.currentTime }
            c.transport.start()
            runCurrent()
            assertEquals(2, a.link.sent.size)
            assertEquals(1, b.link.sent.size)

            // A reconnect one minute later is a session-up trigger and keeps the 5-min floor.
            advanceTimeBy(60_000)
            a.link.drop()
            runCurrent()
            a.link.start("AA:1")
            runCurrent()
            assertEquals("no session-up beacon inside the 5-min floor", 2, a.link.sent.size)
            a.transport.stop()
            b.transport.stop()
            c.transport.stop()
        }

    private fun idOf(wire: WireEnvelope): String = WireCodec.decodeEnvelope(wire.signed)!!.id

    @Test
    fun firstHearingALoraOnlyPeerReoffersItsCarriedDms() =
        runTest {
            val air = FakeMeshtasticAir()
            val fannedLive = frame(FrameType.CHAT, "alice", recipientId = "bob", body = "sent while bob was off")
            val carried = frame(FrameType.CHAT, "carol", recipientId = "bob", body = "relayed for carol")
            val notForBob = frame(FrameType.CHAT, "alice", recipientId = "dave", body = "custody's mistake")
            val asked = mutableListOf<String>()
            val a =
                rig(air, 1u, "alice", backgroundScope, farFrames = { peer ->
                    asked += peer
                    listOf(fannedLive, carried, notForBob)
                }) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            // alice fanned the first DM live a moment ago (bob's board was off) — still inside the dedup window.
            a.transport.longRangeFanout(fannedLive)
            advanceTimeBy(2 * 60_000)
            runCurrent()
            val aSentBefore = a.link.sent.size

            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            b.transport.start() // bob beacons; alice hears him for the first time
            advanceTimeBy(4_000)
            runCurrent()

            assertEquals("custody is asked once, for bob", listOf("bob"), asked)
            // The addressee is double-checked, and the frame fanned inside the dedup window is skipped.
            assertEquals(1L, a.metrics.snapshot().loraReoffered)
            assertEquals("alice's first-hearing beacon + one re-offered frame", aSentBefore + 2, a.link.sent.size)
            assertTrue("bob received the re-offered DM", b.received.any { it.envelope.id == idOf(carried) })
            assertFalse(b.received.any { it.envelope.id == idOf(notForBob) })

            // Hearing bob again inside the linger is not a first hearing.
            b.transport.fastFanout(frame(FrameType.CHAT, "bob", body = "hi", sentAt = testScheduler.currentTime))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(listOf("bob"), asked)

            // Once bob ages out of reachable and reappears, custody is asked again (and the dedup window has lapsed).
            advanceTimeBy(46 * 60_000)
            b.transport.fastFanout(frame(FrameType.CHAT, "bob", body = "back", sentAt = testScheduler.currentTime))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(listOf("bob", "bob"), asked)
            assertEquals(3L, a.metrics.snapshot().loraReoffered)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun withDmsOffTheRoomStillRidesButDmsAndReoffersDoNot() =
        runTest {
            val air = FakeMeshtasticAir()
            val asked = mutableListOf<String>()
            val a =
                rig(
                    air,
                    1u,
                    "alice",
                    backgroundScope,
                    config = MutableStateFlow(LoraConfig("AA:1", 0, dms = false)),
                    farFrames = { peer ->
                        asked += peer
                        listOf(frame(FrameType.CHAT, "alice", recipientId = "bob"))
                    },
                ) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val baseline = a.link.sent.size
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "private"))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("a DM stays off the plane", baseline, a.link.sent.size)
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "room post"))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("the room keeps riding", baseline + 1, a.link.sent.size)

            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            b.transport.start()
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("no re-offer either", asked.isEmpty())
            assertEquals(0L, a.metrics.snapshot().loraDmSent)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aReofferIsSkippedForAPeerAnotherPlaneAlreadyCarries() =
        runTest {
            val air = FakeMeshtasticAir()
            val asked = mutableListOf<String>()
            val a =
                rig(air, 1u, "alice", backgroundScope, farFrames = { peer ->
                    asked += peer
                    listOf(frame(FrameType.CHAT, "alice", recipientId = "bob"))
                }) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            // Bob is on a live BLE/NAN link — custody's digest exchange syncs to him there for real.
            a.transport.suppressDataPath(setOf("bob"))
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            b.transport.start()
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("custody is not even asked", asked.isEmpty())
            assertEquals(0L, a.metrics.snapshot().loraReoffered)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aVerbatimResendIsSuppressedInsideTheWindowThenAllowedAfter() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val baseline = a.link.sent.size
            val wire = frame(FrameType.CHAT, "alice", body = "same frame")
            a.transport.fastFanout(wire)
            runCurrent()
            val afterFirst = a.link.sent.size
            assertTrue("first send goes out", afterFirst > baseline)

            a.transport.fastFanout(wire) // verbatim retry (AckSync re-sends these for 24 h)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("suppressed inside the 10-min dedup window", afterFirst, a.link.sent.size)

            advanceTimeBy(10 * 60_000)
            a.transport.fastFanout(wire)
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("allowed again after the window", a.link.sent.size > afterFirst)
            a.transport.stop()
        }

    @Test
    fun fastSendOnlyReachesLoraReachablePeersNotServedByAnotherPlane() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            // bob hears alice, so alice becomes reachable to bob.
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "ping"))
            runCurrent()
            val bSentBefore = b.link.sent.size

            // A tick toward a peer bob has never heard is dropped.
            b.transport.fastSend(frame(FrameType.RECEIPT, "bob"), Peer("stranger"))
            runCurrent()
            assertEquals("no send to an unreachable peer", bSentBefore, b.link.sent.size)

            // A tick toward alice, whom another plane holds a LIVE LINK to, is skipped — she gets it there.
            b.transport.suppressDataPath(setOf("alice"))
            b.transport.fastSend(frame(FrameType.RECEIPT, "bob"), Peer("alice"))
            runCurrent()
            assertEquals("no send to a peer another plane covers", bSentBefore, b.link.sent.size)

            // But a peer merely *sighted* on BLE/NAN is covered by nothing, so the tick must still ride.
            // Read as coverage, a sighting refused a far peer's only path to its ✓✓ (field, 2026-08-25).
            b.transport.suppressDataPath(emptySet())
            b.transport.onForeignReachable(setOf("alice"))
            b.transport.fastSend(frame(FrameType.RECEIPT, "bob"), Peer("alice"))
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("a tick to a LoRa-reachable, uncovered peer rides", b.link.sent.size > bSentBefore)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun ineligibleFramesAreNeverSent() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val baseline = a.link.sent.size
            a.transport.fastFanout(
                frame(FrameType.CHAT, "alice", group = GroupInfo("g-x", members = listOf("alice", "bob"), createdBy = "alice")),
            )
            a.transport.fastFanout(frame(FrameType.TYPING, "alice"))
            a.transport.fastFanout(frame(FrameType.GROUP_UPDATE, "alice"))
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals("none of the ineligible frames ride LoRa", baseline, a.link.sent.size)
            a.transport.stop()
        }

    @Test
    fun aNullConfigStopsTheLinkAndReportsUnavailable() =
        runTest {
            val air = FakeMeshtasticAir()
            val cfg = MutableStateFlow<LoraConfig?>(LoraConfig("AA", 0))
            val a = rig(air, 1u, "alice", backgroundScope, config = cfg) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            assertEquals(TransportHealth.Healthy, a.transport.health.value)

            cfg.value = null // the user turned the plane off / unpaired the board
            runCurrent()
            assertEquals(TransportHealth.Unavailable, a.transport.health.value)
            val baseline = a.link.sent.size
            a.transport.fastFanout(frame(FrameType.CHAT, "alice"))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("nothing sends while the plane is off", baseline, a.link.sent.size)
            a.transport.stop()
        }

    @Test
    fun aDisconnectDegradesAndReadyRestoresHealthy() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            assertEquals(TransportHealth.Healthy, a.transport.health.value)
            a.link.drop()
            runCurrent()
            assertEquals(TransportHealth.Degraded, a.transport.health.value)
            a.link.start("AA") // reconnects
            runCurrent()
            assertEquals(TransportHealth.Healthy, a.transport.health.value)
            a.transport.stop()
        }

    @Test
    fun aNakIsCountedAndPacesWithoutBlockingForever() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            a.link.emitNak(id = 5u, reason = RoutingError.DUTY_CYCLE_LIMIT)
            runCurrent()
            assertEquals(1L, a.metrics.snapshot().loraNak)
            a.transport.stop()
        }

    @Test
    fun theReachableLingerExpiresAPeer() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "hello"))
            runCurrent()
            assertTrue(
                b.transport.reachable.value
                    .any { it.nodeId == "alice" },
            )

            advanceTimeBy(46 * 60_000) // past the 45-min linger
            runCurrent()
            assertFalse(
                "alice ages out of reachable after the linger",
                b.transport.reachable.value
                    .any { it.nodeId == "alice" },
            )
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun provisionKnitChannelDelegatesToTheLinkWithTheDerivedKnitChannel() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.link.provisionResult = ProvisionResult.Provisioned(index = 3, alreadyPresent = false)

            val result = a.transport.provisionKnitChannel()

            assertEquals(ProvisionResult.Provisioned(3, false), result)
            assertEquals(1, a.link.provisioned.size)
            assertEquals(
                KnitChannel.NAME,
                a.link.provisioned
                    .single()
                    .name,
            )
            assertArrayEquals(
                KnitChannel.PSK,
                a.link.provisioned
                    .single()
                    .psk,
            )
            a.transport.stop()
        }

    @Test
    fun theStatusSnapshotCarriesTheBoardsBattery() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            assertNull(a.transport.status.value.battery)

            val battery = BoardBattery(percent = 42, voltage = 3.7f, powered = false)
            a.link.battery.value = battery
            runCurrent()
            assertEquals(battery, a.transport.status.value.battery)
        }
}
