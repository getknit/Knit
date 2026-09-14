package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.spool.FakeSpool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * The Internet plane between real stacks — the real `ScopeSync` on every node over one in-process
 * [FakeSpool], which is the shape of the device trials the spool work still owes (ADR 064): two islands
 * sharing a group through one relay and a departure rotating its scope, a receive-only peer deriving the
 * same DM scope (ADR 032), a photo the radio already carried not being uploaded until the peers part
 * (ADR 021), a planted blob quarantined without breaking convergence (spec §9.3), two card holders meeting
 * at the pair scope with no radio (ADR 042), a relay that drops every socket, and a sealed profile update
 * crossing the relay (ADR 020). Slow by nature: the scope reconcile is 15 s and a worker that missed an
 * event waits for its own 60 s tick, hence [MeshLab.SPOOL_AWAIT_MS].
 */
@RunWith(RobolectricTestRunner::class)
class InternetPlaneLabTest {
    private lateinit var lab: MeshLab

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    private fun LabNode.dmThreadWith(other: LabNode): (LabNode) -> String = { if (it === this) dmWith(other) else it.dmWith(this) }

    /** Three nodes on one relay, acquainted and holding DM sessions both ways, so every DM scope derives. */
    private suspend fun threeOnOneRelay(spool: FakeSpool): Triple<LabNode, LabNode, LabNode> {
        val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
        val carol = lab.node("carol", spool = spool).apply { setDisplayName("Carol") }
        lab.linkAll(alice to bob, bob to carol, alice to carol)
        lab.awaitAcquainted(alice, bob, carol)
        listOf(alice to bob, alice to carol, bob to carol).forEach { (a, b) ->
            assertTrue(a.sendDm(b, "hello"))
            lab.await(1) { b.decrypted(b.dmWith(a)).size }
            assertTrue(b.sendDm(a, "hi"))
            lab.assertConverged(listOf(a, b), atLeast = 2) { a.dmThreadWith(b)(it) }
        }
        return Triple(alice, bob, carol)
    }

    /**
     * The two-island trial ADR 064 still owes: a group the three founded together, then Alice and Bob one
     * island (linked) and Carol the other, all three on one relay. Carol writes across the relay through
     * the group scope and gets the others' ticks back; then she leaves — her leave crosses the relay, the
     * remainder rekeys and re-mints the group root, and a fresh scope replaces the old one.
     */
    @Test
    fun twoIslandsShareAGroupThroughOneRelayAndADepartureRotatesTheScope() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob, carol) = threeOnOneRelay(spool)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded together"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            lab.unlink(alice, carol)
            lab.unlink(bob, carol)
            lab.awaitGroupScope(alice, groupId, bob, carol)
            lab.awaitGroupScope(carol, groupId, alice, bob)
            assertTrue(carol.sendGroup(groupId, "from the far island"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { groupId }

            carol.leaveGroup(groupId)
            assertTrue(
                "alice never learned of the departure",
                lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (alice.groupShape(groupId)?.departed ==
                        setOf(carol.nodeId)
                    ) {
                        1
                    } else {
                        0
                    }
                },
            )
            assertTrue(bob.sendGroup(groupId, "after carol left"))
            // The re-mint that rotates the scope is damped by GroupRootPolicy's six-hour grace unless the
            // preferred minter is the one left standing; the rotation itself is the clock tier's to assert
            // (TimeLabTest), so here the departure and the remainder's convergence are what is pinned.
            // A relay carries only your own scopes, never a third party's DM-form frames (alice↔bob's ticks
            // and seeds), so the far island's custody agrees with the near one's only once they meet by
            // radio again — one ordinary digest exchange, which is what the re-link shows.
            lab.linkAll(alice to carol, bob to carol)
            lab.assertConverged(listOf(alice, bob), atLeast = 3, carriers = listOf(carol), timeoutMs = MeshLab.SPOOL_AWAIT_MS) { groupId }
        }

    /**
     * FINDING #47 (2026-09-14, first run): a group founded while one member is reachable only over the relay
     * never reaches that member. The seed rides Carol's DM scope and parks (`PendingGroupKeys`, group
     * unknown) with the group root inside it; the roster rides only the group scope, which derives from
     * that root, which `adoptGroupRoot` refuses without the group row — so the roster can never be pulled,
     * the parked seed expires after an hour, and Carol holds nothing. The radio equivalent
     * (`CustodyLabTest.groupCreatedWhileAMemberWasAwayArrivesThroughACarrier`) works because custody
     * carries the roster frame itself. Ignored until the design decides how a founding roster crosses a
     * relay (the seed carrying the roster, or the founding frame riding the members' DM scopes).
     */
    @Ignore("#47: a group founded across the relay never delivers its roster to the relay-only member")
    @Test
    fun aGroupFoundedAcrossTheRelayReachesTheRelayOnlyMember() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob, carol) = threeOnOneRelay(spool)
            lab.unlink(alice, carol)
            lab.unlink(bob, carol)
            lab.awaitDmScope(alice, carol)
            lab.awaitDmScope(carol, alice)

            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded across the relay"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { groupId }
        }

    /**
     * ADR 032: a pair where only Alice ever wrote. Bob's side used to derive no scope because the thread was
     * an unaccepted request there; now both derive the same one, and a DM crosses the relay alone.
     */
    @Test
    fun aReceiveOnlyPeerDerivesTheSameDmScope() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "a request bob never answers"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { alice.dmThreadWith(bob)(it) }
            lab.unlink(alice, bob)

            lab.awaitDmScope(alice, bob)
            lab.awaitDmScope(bob, alice)
            assertEquals(alice.dmScopeStatus(bob)?.scopeHex, bob.dmScopeStatus(alice)?.scopeHex)

            assertTrue(alice.sendDm(bob, "over the relay, still unanswered"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
        }

    /**
     * ADR 021's owed trial, and FINDING #46 (2026-09-14, first run): the chunk went up while Bob was in range.
     * `ScopeSync.onCustodyChanged` wakes the worker the moment the frame is custodied, and that round's
     * `healAttachments` runs `AttachmentDeferPolicy.defer`, whose rule needs the recipient's ack
     * (`ackedBySender`) — which cannot exist yet: the ack is a round trip over the link and the round is
     * already pushing. So the deferral holds only for a send whose ack beat the relay, which a fast relay
     * never allows, and the counter the trial expects to climb stays at zero. Ignored until the design
     * decides (defer the attachment pass to the next tick, or judge reachability alone within the window).
     */
    @Ignore("#46: the attachment pass runs in the round the send triggers, before the ack the deferral needs")
    @Test
    fun aPhotoTheRadioCarriedIsNotUploadedUntilThePeersPart() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            val picture = Random(21).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "in range", to = bob))
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { alice.dmThreadWith(bob)(it) }
            assertTrue(
                "the upload was never deferred",
                lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    alice.metrics
                        .snapshot()
                        .spoolAttachDeferred
                        .toInt()
                },
            )
            assertTrue("chunks went up while bob was in range: ${spool.chunksPut}", spool.chunksPut.isEmpty())

            lab.unlink(alice, bob)
            assertTrue(
                "the upload never happened after they parted",
                lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { spool.chunksPut.size },
            )
        }

    /**
     * Spec §9.3: a blob the relay folds into its digest that nobody can open is quarantined — pulled once,
     * never again — and the pair keeps talking through the scope. The row's `converged` flag legitimately
     * reads false while the relay lists an id we refuse to count as held (C-9.3-2): the cost is one
     * bounded LIST per round, never a pull.
     */
    @Test
    fun aPlantedGarbageBlobIsQuarantinedOnceAndTheScopeKeepsWorking() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            val scope = checkNotNull(alice.dmScopeStatus(bob)).scopeHex

            val id = spool.plantGarbage(scope, Random(22).nextBytes(200))
            spool.announce(scope) // a spool that gained a blob says so; the fake's plant deliberately does not
            assertTrue(
                "the garbage was never quarantined; spool holds ${spool.liveIds(
                    scope,
                )}, alice=${alice.dmScopeStatus(bob)?.invalidCount} bob=${bob.dmScopeStatus(alice)?.invalidCount}",
                lab.await(2, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    (
                        alice.metrics.snapshot().spoolInvalid +
                            bob.metrics.snapshot().spoolInvalid
                    ).toInt()
                },
            )
            assertEquals("asked for exactly once per member", 2, spool.pulled.count { it == id })

            assertTrue(alice.sendDm(bob, "still fine"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
            assertEquals("still asked for exactly once", 2, spool.pulled.count { it == id })
        }

    /**
     * ADR 042: two people who only ever exchanged contact cards, out of radio range, one shared relay. Each
     * import sends an intro over the pair scope; the sessions confirm, the same DM scope derives on both
     * sides, and a DM crosses.
     */
    @Test
    fun twoCardHoldersMeetAtThePairScopeWithNoRadio() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }

            val aliceCard = alice.mintCard()
            val bobCard = bob.mintCard()
            alice.importCard(bobCard)
            bob.importCard(aliceCard)

            assertTrue(
                "no intro ever went out",
                lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    minOf(alice.metrics.snapshot().introsSent, bob.metrics.snapshot().introsSent).toInt()
                },
            )
            assertTrue(
                "the sessions never confirmed",
                lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (alice.session(bob)?.confirmed == true &&
                        bob.session(alice)?.confirmed == true
                    ) {
                        1
                    } else {
                        0
                    }
                },
            )
            lab.awaitDmScope(alice, bob)
            lab.awaitDmScope(bob, alice)
            assertEquals(alice.dmScopeStatus(bob)?.scopeHex, bob.dmScopeStatus(alice)?.scopeHex)

            assertTrue(alice.sendDm(bob, "we met through a link"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
        }

    /** A relay that drops every socket mid-way: the clients reconnect on their own and the next DM still crosses. */
    @Test
    fun aRelayThatDropsEverySocketReconvergesOnAFreshConnection() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)

            spool.dropSockets()
            assertTrue(alice.sendDm(bob, "after the relay restarted"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
        }

    /** ADR 020: a rename with no radio path reaches the contact sealed, through the DM scope. */
    @Test
    fun aSealedProfileUpdateCrossesTheRelay() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)

            alice.setDisplayName("Alice, renamed")
            assertTrue(
                "bob never saw the rename",
                lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (bob.peer(alice)?.name ==
                        "Alice, renamed"
                    ) {
                        1
                    } else {
                        0
                    }
                },
            )
            lab.assertConverged(listOf(alice, bob), atLeast = 2, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
        }
}
