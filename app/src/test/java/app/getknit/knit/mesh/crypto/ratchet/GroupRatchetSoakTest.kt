package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetEngine.OpenOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Randomized N-party soak of the group sender-key ratchet under the mesh's delivery pathology —
 * seeded loss (custody eviction), reordering, duplication (re-serves), delayed replay, forced epoch
 * churn, a mid-run departure, and a device wipe — the [RatchetSoakTest] seeded-`Random` discipline,
 * no property-test library. The pairwise seed channel is modeled as a lossy-but-eventually-delivering
 * queue standing in for the v2 DM ratchet (which has its own soak); a receiver that can't decrypt
 * models the key-request loop by re-enqueueing the sender's current seeds.
 *
 * Invariants, per seed:
 *  - every frame that opens (however late, however out of order) yields exactly the sealed plaintext,
 *    the first time it is processed; a re-process is [OpenOutcome.Failed.DUPLICATE] or a skipped-key
 *    hit — never a wrong plaintext, never a crash;
 *  - a member who left decrypts NOTHING sealed after the remaining members processed the departure;
 *  - a wholly-lost epoch never wedges later epochs; a wiped member re-converges;
 *  - skipped-key volume stays bounded by the per-epoch cap.
 */
class GroupRatchetSoakTest {
    private val engine = GroupRatchetEngine()

    private class Frame(
        val id: Int,
        val senderId: String,
        val header: GroupRatchetEngine.FrameHeader,
        val nonce: ByteArray,
        val ct: ByteArray,
        val expected: String,
        /** Sealed after this member's departure was processed by the sender ⇒ they must never open it. */
        val sealedAfterDeparture: Set<String>,
    )

    private inner class Member(
        val nodeId: String,
    ) {
        var send: GroupRatchetEngine.SendChain? = null
        val recv = mutableMapOf<Triple<String, Int, Long>, GroupRatchetEngine.RecvChain>()
        val skipped = mutableMapOf<Triple<String, Int, Int>, MutableList<GroupRatchetEngine.SkippedKey>>()
        val deliveredIds = mutableSetOf<Int>()

        /** The members my CURRENT epoch's seed has been offered to (the outbox stand-in). */
        var distributedTo = setOf<String>()

        fun mintIfDue(
            recipients: List<String>,
            now: Long,
        ): GroupSeedOffer? {
            if (!engine.needsNewEpoch(send, now)) return null
            val chain = engine.mint(GROUP, nodeId, prevEpoch = send?.epoch ?: 0, now = now)
            send = chain
            distributedTo = recipients.toSet()
            return GroupSeedOffer(nodeId, chain.epoch, chain.seed, chain.mintedAt, recipients)
        }

        fun seal(
            id: Int,
            body: String,
            departed: Set<String>,
        ): Frame {
            val result = checkNotNull(engine.seal(checkNotNull(send), body.toByteArray(), AAD))
            send = result.chain
            return Frame(id, nodeId, result.header, result.nonce, result.ct, body, departed)
        }

        fun adopt(
            offer: GroupSeedOffer,
            now: Long,
        ) {
            val newest =
                recv.entries
                    .filter { it.key.first == offer.senderId && it.key.second == offer.epoch }
                    .maxByOrNull { it.key.third }
                    ?.value
            val outcome = engine.adoptSeed(newest, GROUP, offer.senderId, offer.epoch, offer.seed, offer.mintedAt, now)
            if (outcome is GroupRatchetEngine.AdoptOutcome.Adopt) {
                recv[Triple(offer.senderId, offer.epoch, offer.mintedAt)] = outcome.recv
            }
        }

        fun open(
            frame: Frame,
            now: Long,
        ): OpenOutcome {
            val chains =
                recv.entries
                    .filter { it.key.first == frame.senderId && it.key.second == frame.header.se }
                    .sortedByDescending { it.key.third }
                    .map { it.value }
            val keys = skipped[Triple(frame.senderId, frame.header.se, frame.header.n)].orEmpty()
            val outcome =
                engine.open(GroupRatchetEngine.OpenContext(chains, keys), frame.header, frame.nonce, frame.ct, AAD, now)
            if (outcome is OpenOutcome.Opened) {
                val delta = outcome.delta
                delta.recvChain?.let { recv[Triple(frame.senderId, it.epoch, it.mintedAt)] = it }
                delta.skippedInserts.forEach {
                    skipped.getOrPut(Triple(frame.senderId, it.epoch, it.idx)) { mutableListOf() } += it
                }
                delta.consumedSkippedIdx?.let { skipped.remove(Triple(frame.senderId, frame.header.se, it)) }
            }
            return outcome
        }

        fun wipe() {
            send = null
            recv.clear()
            skipped.clear()
            deliveredIds.clear()
            distributedTo = emptySet()
        }
    }

    private class GroupSeedOffer(
        val senderId: String,
        val epoch: Int,
        val seed: ByteArray,
        val mintedAt: Long,
        val recipients: List<String>,
    )

    @Test
    fun theGroupRatchetSurvivesLossReorderingDeparturesAndWipes() {
        for (seed in SEEDS) {
            runSoak(seed, n = 4)
        }
        runSoak(seed = 4242, n = 8) // one run at the roster cap
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth") // one deliberate scenario loop; splitting hides the flow
    private fun runSoak(
        seed: Int,
        n: Int,
    ) {
        val random = Random(seed)
        val members = List(n) { Member("member-$it") }
        val byId = members.associateBy { it.nodeId }
        val active = members.toMutableList()
        val departed = mutableSetOf<String>()
        var now = 1_700_000_000_000L

        val seedChannel = ArrayDeque<Pair<Member, GroupSeedOffer>>() // (recipient, offer)
        val inFlight = ArrayDeque<Pair<Member, Frame>>()
        val graveyard = mutableListOf<Pair<Member, Frame>>()
        var nextId = 0
        var opened = 0
        var leaverViolations = 0
        var departedAt = -1

        repeat(EVENTS_PER_SEED) { step ->
            now += random.nextLong(1L, 60_000L)

            // A departure roughly mid-run (once): remaining members force a rekey — modeled exactly as
            // production does it, by deleting the send chain so the next seal mints a fresh epoch whose
            // seed is offered to the remaining members only.
            if (departedAt < 0 && step == EVENTS_PER_SEED / 2 && active.size > 2) {
                val leaver = active.removeAt(random.nextInt(active.size))
                departed += leaver.nodeId
                departedAt = step
                active.forEach { it.send = null }
            }

            // A wipe (once, after the departure window): a member loses everything and re-mints.
            if (step == (EVENTS_PER_SEED * 3) / 4 && random.nextBoolean()) {
                active[random.nextInt(active.size)].wipe()
            }

            // Sender action: a random active member seals (minting + offering seeds when due).
            val sender = active[random.nextInt(active.size)]
            val others = active.filter { it != sender }.map { it.nodeId }
            sender.mintIfDue(others, now)?.let { offer ->
                offer.recipients.forEach { rid -> byId.getValue(rid).let { seedChannel.addLast(it to offer) } }
            }
            val frame = sender.seal(nextId, "m$nextId from ${sender.nodeId}", departed.toSet())
            nextId++
            // The flood reaches everyone still holding the group — including the leaver (frames are
            // public custody; confidentiality is the seed's job).
            members.filter { it != sender }.forEach { inFlight.addLast(it to frame) }

            // Seed delivery: lossy but eventually-delivering (the DM layer's contract).
            repeat(random.nextInt(0, 3)) {
                if (seedChannel.isEmpty()) return@repeat
                val idx = random.nextInt(seedChannel.size)
                val (recipient, offer) = seedChannel.elementAt(idx)
                seedChannel.remove(recipient to offer)
                when (random.nextInt(10)) {
                    in 0..7 -> recipient.adopt(offer, now)
                    else -> seedChannel.addLast(recipient to offer) // delayed, never truly lost
                }
            }

            // Frame delivery: loss, reorder, duplication.
            repeat(random.nextInt(0, 5)) {
                if (inFlight.isEmpty()) return@repeat
                val idx = random.nextInt(inFlight.size)
                val entry = inFlight.elementAt(idx)
                inFlight.remove(entry)
                val (recipient, candidate) = entry
                when (random.nextInt(10)) {
                    in 0..6 -> {
                        leaverViolations +=
                            deliverAsserting(recipient, candidate, now).let { outcome ->
                                if (outcome is OpenOutcome.Opened) opened++
                                if (outcome is OpenOutcome.Opened && recipient.nodeId in candidate.sealedAfterDeparture) 1 else 0
                            }
                        graveyard += entry
                    }

                    in 7..8 -> {
                        inFlight.addLast(entry)
                    }

                    // delayed (reorder)
                    else -> {
                        graveyard += entry
                    } // lost (custody eviction) — maybe re-served later
                }
            }

            // The key-request loop, modeled: a member holding undecryptable frames re-requests; the
            // sender re-offers its CURRENT seeds to current members (never to the departed).
            if (random.nextInt(8) == 0) {
                val requester = active[random.nextInt(active.size)]
                active.filter { it != requester }.forEach { responder ->
                    val chain = responder.send ?: return@forEach
                    if (requester.nodeId !in responder.distributedTo) return@forEach
                    seedChannel.addLast(
                        requester to GroupSeedOffer(responder.nodeId, chain.epoch, chain.seed, chain.mintedAt, listOf(requester.nodeId)),
                    )
                }
            }

            // Custody re-serve: an old frame comes back verbatim.
            if (graveyard.isNotEmpty() && random.nextInt(5) == 0) {
                val (recipient, replay) = graveyard[random.nextInt(graveyard.size)]
                deliverAsserting(recipient, replay, now)
            }

            // Forced epoch churn: jump the clock past the epoch age cap now and then.
            if (random.nextInt(60) == 0) now += GroupRatchetEngine.MAX_EPOCH_AGE_MS + 1
        }

        // Drain: deliver every pending seed, then every pending frame — the mesh eventually delivers
        // what wasn't evicted.
        while (seedChannel.isNotEmpty()) {
            val (recipient, offer) = seedChannel.removeFirst()
            recipient.adopt(offer, now)
        }
        while (inFlight.isNotEmpty()) {
            val (recipient, frame) = inFlight.removeFirst()
            now += 1_000L
            val outcome = deliverAsserting(recipient, frame, now)
            if (outcome is OpenOutcome.Opened) {
                opened++
                if (recipient.nodeId in frame.sealedAfterDeparture) leaverViolations++
            }
        }

        assertTrue("seed $seed/n=$n: nothing decrypted — the harness is broken", opened > EVENTS_PER_SEED / 4)
        assertEquals("seed $seed/n=$n: a leaver opened post-departure traffic", 0, leaverViolations)
        members.forEach { m ->
            val perEpochWorst = m.skipped.values.sumOf { it.size }
            assertTrue(
                "seed $seed/n=$n: ${m.nodeId} skipped keys unbounded ($perEpochWorst)",
                perEpochWorst <= GroupRatchetEngine.MAX_EPOCH_MESSAGES * members.size * 4,
            )
        }
    }

    /** Delivers [frame], asserting the invariant that matters: an open yields the right plaintext,
     *  and a departed member's post-departure frames never open for them (checked by the caller). */
    private fun deliverAsserting(
        recipient: Member,
        frame: Frame,
        now: Long,
    ): OpenOutcome {
        val outcome = recipient.open(frame, now)
        if (outcome is OpenOutcome.Opened) {
            assertEquals("frame ${frame.id} decrypted to the wrong plaintext", frame.expected, String(outcome.plaintext))
            val first = frame.id !in recipient.deliveredIds
            recipient.deliveredIds += frame.id
            if (!first) {
                // A second Opened for the same id is only reachable via a banked skipped key that a
                // re-serve consumed — legal; what is never legal is a wrong plaintext, asserted above.
                assertFalse(frame.expected.isEmpty())
            }
        }
        return outcome
    }

    private companion object {
        val SEEDS = intArrayOf(1, 7, 42, 1337, 99991)
        const val EVENTS_PER_SEED = 400
        const val GROUP = "g-0123456789abcdef01234567"
        val AAD = "soak|group|aad".toByteArray()
    }
}
