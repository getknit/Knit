package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.sha256Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plane end to end, two members over one in-process spool: §9.1's bidirectional heal, §9.3's
 * invalid-set quarantine, §9.2's outward dead-on-arrival guard, and §9.4's bridge into mesh delivery.
 *
 * This is the correctness oracle for the milestone — the device bench proves the socket, this proves
 * the protocol. Virtual time is stepped rather than drained (`advanceUntilIdle` would never return: the
 * supervisor, the per-spool worker and the tick loop are all deliberately infinite).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScopeSyncTest {
    private val alice = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val carol = "cccccccccccccccccccccccccc"
    private val pairwiseRoot = ByteArray(32) { it.toByte() }
    private val groupRoot = ByteArray(32) { (it + 30).toByte() }
    private val now = 1_000L
    private val url = "ws://spool.test/spool/v1"

    private class Member(
        val custody: FakeCustody,
        val sync: ScopeSync,
        val metrics: MeshMetrics,
        val delivered: MutableList<RelayEnvelope>,
        val blobs: FakeBlobs,
        val obtained: MutableList<String>,
    )

    /** The local content-addressed store, in memory. Content-addressed, so a save is write-once. */
    private class FakeBlobs(
        vararg initial: Pair<String, ByteArray>,
    ) : ScopeBlobs {
        val stored = LinkedHashMap<String, ByteArray>().apply { putAll(initial) }
        val mimes = LinkedHashMap<String, String>()

        override suspend fun has(aHash: String): Boolean = aHash in stored

        override suspend fun bytes(aHash: String): ByteArray? = stored[aHash]

        override suspend fun save(
            aHash: String,
            mime: String,
            bytes: ByteArray,
        ) {
            stored[aHash] = bytes
            mimes[aHash] = mime
        }
    }

    private fun member(
        spool: FakeSpool,
        self: String,
        peer: String,
        custody: FakeCustody = FakeCustody(),
        carryGate: suspend (WireEnvelope, RelayEnvelope) -> Boolean = { _, _ -> true },
        groups: List<GroupScopeRoots> = emptyList(),
        blobs: FakeBlobs = FakeBlobs(),
    ): Member {
        val metrics = MeshMetrics()
        val delivered = mutableListOf<RelayEnvelope>()
        val obtained = mutableListOf<String>()
        val sync =
            ScopeSync(
                registry =
                    ScopeRegistry(
                        selfId = { self },
                        roots = { listOf(ScopeRoots(peer, pairwiseRoot)) },
                        isAccepted = { true },
                        groupRoots = { groups },
                    ),
                dialer = spool,
                store = custody,
                selfId = { self },
                urls = { listOf(url) },
                canCarry = carryGate,
                // Stands in for MeshRouter.handleInbound: deliver, then capture into custody exactly as
                // InboundPipeline.onDeliver does, so the next heal round folds the frame into our digest.
                deliver = { wire, env, _ ->
                    delivered.add(env)
                    custody.store(CarriedFrame(env, wire.sig, wire.signed), ForwardStore.ORIGIN_RELAY, now)
                },
                blobs = blobs,
                onAttachmentObtained = { obtained.add(it) },
                metrics = metrics,
                clock = { now },
                jitter = { 0L },
            )
        return Member(custody, sync, metrics, delivered, blobs, obtained)
    }

    /** Bytes plus their content address — what a frame's cleartext `attachmentHash` names. */
    private fun image(size: Int = 100_000): Pair<String, ByteArray> {
        val bytes = ByteArray(size) { ((it * 13) and 0xFF).toByte() }
        return sha256Hex(bytes) to bytes
    }

    private fun aidHex(
        self: String,
        peer: String,
        aHash: String,
    ): String {
        val id = ScopeCrypto.dmScopeId(pairwiseRoot, self, peer)
        val keys = ScopeCrypto.dmSealKeys(pairwiseRoot, self, peer)
        return hex(ScopeCrypto.attachmentId(keys, id, ScopeAttachments.hashBytes(aHash)!!))
    }

    private fun scopeHex(
        self: String,
        peer: String,
    ) = hex(ScopeCrypto.dmScopeId(pairwiseRoot, self, peer))

    /** Steps virtual time in small slices so the infinite loops make progress without being drained. */
    private fun TestScope.pump(rounds: Int = 8) {
        repeat(rounds) {
            advanceTimeBy(1_000)
            runCurrent()
        }
    }

    @Test
    fun `a frame one member custodies reaches the other through the spool`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            sender.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals("the sender pushed its custody", 1, spool.pushed.size)
            assertEquals(listOf("m1"), receiver.delivered.map { it.id })
            assertTrue("the bridged frame lands in the receiver's custody", receiver.custody.has("m1"))
            assertEquals(1, sender.metrics.snapshot().spoolPushed)
            assertEquals(1, receiver.metrics.snapshot().spoolBridged)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a group frame bridges to another member over the group scope`() =
        runTest {
            val spool = FakeSpool()
            val groupId = "g-00112233445566778899aabb"
            val roster = setOf(alice, bob, carol)
            val group = GroupScopeRoots(groupId, roster, groupRoot, rootVersion = 1)
            val sender = member(spool, alice, bob, groups = listOf(group))
            val receiver = member(spool, bob, alice, groups = listOf(group))
            // A chat from carol that alice merely CARRIES: the bridge is a custody property, not an
            // authorship one, so a third member's frame crosses the Internet through either of the two.
            sender.custody.store(
                groupChatFrame("gc1", from = carol, groupId = groupId, members = roster.toList(), sentAt = now),
                ForwardStore.ORIGIN_RELAY,
                now,
            )
            sender.custody.store(groupLeaveFrame("gl1", from = carol, groupId = groupId, sentAt = now), ForwardStore.ORIGIN_RELAY, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals(setOf("gc1", "gl1"), receiver.delivered.map { it.id }.toSet())
            assertEquals(2, spool.liveIds(hex(ScopeCrypto.groupScopeId(groupRoot, groupId, 1))).size)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a re-minted root moves the group to a fresh scope and drains the old one`() =
        runTest {
            val spool = FakeSpool()
            val groupId = "g-00112233445566778899aabb"
            val roster = setOf(alice, bob, carol)
            val v1 = hex(ScopeCrypto.groupScopeId(groupRoot, groupId, 1))
            val newRoot = ByteArray(32) { (it + 60).toByte() }
            val v2 = hex(ScopeCrypto.groupScopeId(newRoot, groupId, 2))

            // Post-departure state: the rotated root is live, the old lineage is still inside its drain.
            val rotated =
                GroupScopeRoots(
                    groupId = groupId,
                    roster = roster,
                    root = newRoot,
                    rootVersion = 2,
                    prevRoot = groupRoot,
                    prevRootVersion = 1,
                    prevRootExpiresAt = now + 1_000_000L,
                )
            val holder = member(spool, alice, bob, groups = listOf(rotated))
            holder.custody.store(
                groupChatFrame("gc1", from = bob, groupId = groupId, members = roster.toList(), sentAt = now),
                ForwardStore.ORIGIN_RELAY,
                now,
            )

            holder.sync.start(backgroundScope)
            pump()

            // §3.3: old blobs are never migrated. The frame is re-sealed under the new keys into a fresh,
            // unlinkable id; the retiring scope is subscribed and healed but never refilled.
            assertEquals(1, spool.liveIds(v2).size)
            assertTrue("the retiring scope is drained, not refilled", spool.liveIds(v1).isEmpty())
            assertTrue(v1 != v2)
            holder.sync.stop()
        }

    @Test
    fun `heal is bidirectional — a member refills a spool that lost everything`() =
        runTest {
            val spool = FakeSpool()
            val holder = member(spool, alice, bob)
            listOf("m1", "m2", "m3").forEach {
                holder.custody.store(dmFrame(it, from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            }

            holder.sync.start(backgroundScope)
            pump()

            // A fresh spool starts empty and heals from whichever member is connected — the "client union
            // is the federation" property, with no spool-to-spool traffic anywhere.
            assertEquals(3, spool.liveIds(scopeHex(alice, bob)).size)
            assertEquals(3, holder.metrics.snapshot().spoolPushed)
            holder.sync.stop()
        }

    @Test
    fun `both members converge on the same blob ids because the seal is deterministic`() =
        runTest {
            val spool = FakeSpool()
            val one = member(spool, alice, bob)
            val two = member(spool, bob, alice)
            // The same frame in both custodies: a naive random-nonce seal would upload it twice under two
            // ids and the digests would never converge.
            val frame = dmFrame("m1", from = alice, to = bob, sentAt = now)
            one.custody.store(frame, ForwardStore.ORIGIN_SELF, now)
            two.custody.store(frame, ForwardStore.ORIGIN_RELAY, now)

            one.sync.start(backgroundScope)
            two.sync.start(backgroundScope)
            pump()

            assertEquals("one frame, one blob", 1, spool.liveIds(scopeHex(alice, bob)).size)
            assertEquals(1, spool.pushed.size)
            listOf(one, two).forEach { m ->
                val scope =
                    m.sync
                        .status()
                        .single()
                        .scopes
                        .single()
                assertTrue("digests must agree after a heal round", scope.converged)
                assertEquals(1, scope.localCount)
            }
            one.sync.stop()
            two.sync.stop()
        }

    @Test
    fun `a blob delivered by a live event is not delivered again by the heal round that raced it`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            listOf("m1", "m2", "m3").forEach {
                sender.custody.store(dmFrame(it, from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            }

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            // Events and the pull set race by construction — the pull set is computed before the events
            // land — so the same blob legitimately arrives twice. Re-delivering is harmless (the router's
            // SeenSet dedups) but it would double-count the number Diagnostics shows as messages received.
            assertEquals(3, receiver.delivered.size)
            assertEquals(3, receiver.metrics.snapshot().spoolBridged)
            assertEquals(3, receiver.metrics.snapshot().spoolPulled)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a frame swept from custody while the spool still holds it is not re-pulled every heal round`() =
        runTest {
            val spool = FakeSpool()
            val holder = member(spool, alice, bob)
            holder.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            holder.sync.start(backgroundScope)
            pump()
            assertEquals("pushed and converged first", 1, spool.pushed.size)

            // The scope TTL (48 h) deliberately outlives mesh custody (24 h), so a frame we already
            // delivered gets swept locally while the spool keeps it for another day. From then on it is
            // absent from `local` forever and our digest can never match — and re-pulling it achieves
            // nothing, because the custody store refuses it as dead on arrival every single time.
            holder.custody.sweep("m1")
            pump(rounds = 120) // several 60 s heal ticks
            val afterFirstSweep = spool.pulled.size
            pump(rounds = 120)

            assertEquals("pulled at most once after the sweep", 1, afterFirstSweep)
            assertEquals("and never again", afterFirstSweep, spool.pulled.size)
            holder.sync.stop()
        }

    @Test
    fun `a spool that rejects the connection reports why, instead of just looking disconnected`() =
        runTest {
            // A spool with a token configured closes 4001 before saying anything. Without the close code
            // reaching the status, that is indistinguishable from "not connected yet" — which is exactly
            // how a wrong/missing `?k=` token presents in the field.
            val rejecting =
                object : SpoolDialer {
                    override suspend fun dial(url: String): SpoolSocket =
                        object : SpoolSocket {
                            private val ch = Channel<ByteArray>(Channel.UNLIMITED).also { it.close() }

                            override val incoming get() = ch
                            override val closeReason = "close 4001 auth"

                            override fun send(bytes: ByteArray) = false

                            override fun close(
                                code: Int,
                                reason: String,
                            ) = Unit
                        }
                }
            val member =
                member(FakeSpool(), alice, bob).let { base ->
                    ScopeSync(
                        registry = ScopeRegistry({ alice }, { listOf(ScopeRoots(bob, pairwiseRoot)) }, { true }),
                        dialer = rejecting,
                        store = base.custody,
                        selfId = { alice },
                        urls = { listOf(url) },
                        canCarry = { _, _ -> true },
                        deliver = { _, _, _ -> },
                        clock = { now },
                        jitter = { 0L },
                    )
                }

            member.start(backgroundScope)
            pump()

            val status = member.status().single()
            assertFalse(status.connected)
            assertEquals("close 4001 auth", status.lastError)
            member.stop()
        }

    @Test
    fun `a socket that will not open at all is reported as unreachable`() =
        runTest {
            val dead =
                object : SpoolDialer {
                    override suspend fun dial(url: String): SpoolSocket? = null
                }
            val member =
                ScopeSync(
                    registry = ScopeRegistry({ alice }, { listOf(ScopeRoots(bob, pairwiseRoot)) }, { true }),
                    dialer = dead,
                    store = FakeCustody(),
                    selfId = { alice },
                    urls = { listOf(url) },
                    canCarry = { _, _ -> true },
                    deliver = { _, _, _ -> },
                    clock = { now },
                    jitter = { 0L },
                )

            member.start(backgroundScope)
            pump()

            assertEquals(ScopeSync.UNREACHABLE, member.status().single().lastError)
            member.stop()
        }

    @Test
    fun `a garbage blob at the spool is quarantined once, never delivered, never re-pulled`() =
        runTest {
            val spool = FakeSpool()
            val victim = member(spool, bob, alice)
            spool.plantGarbage(scopeHex(bob, alice), "not a sealed frame".toByteArray())

            victim.sync.start(backgroundScope)
            pump()
            val afterFirst = victim.metrics.snapshot().spoolInvalid
            pump()

            assertTrue("the garbage must be quarantined", afterFirst >= 1)
            assertTrue("nothing forged is ever delivered", victim.delivered.isEmpty())
            assertEquals("a quarantined id is never re-pulled", afterFirst, victim.metrics.snapshot().spoolInvalid)
            assertEquals(0, victim.metrics.snapshot().spoolBridged)
            victim.sync.stop()
        }

    @Test
    fun `a blob whose sender fails the mesh carry gate is quarantined, not delivered`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice, carryGate = { _, _ -> false })
            sender.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertTrue(receiver.delivered.isEmpty())
            assertFalse(receiver.custody.has("m1"))
            assertTrue(receiver.metrics.snapshot().spoolInvalid >= 1)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `an expired frame is never pushed — the outward dead-on-arrival guard`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob, custody = FakeCustody(ttlMs = Long.MAX_VALUE / 2))
            // Held locally (the custody TTL here is generous) but already past the scope's 48 h horizon.
            sender.custody.store(
                dmFrame("old", from = alice, to = bob, sentAt = now - ScopeRegistry.DEFAULT_TTL_MS),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            sender.custody.store(dmFrame("fresh", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            pump()

            assertEquals(1, spool.pushed.size)
            assertEquals(1, sender.metrics.snapshot().spoolPushed)
            sender.sync.stop()
        }

    @Test
    fun `a frame that fails the frame-set rule is never sealed into the scope`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val carol = "cccccccccccccccccccccccccc"
            sender.custody.store(dmFrame("mine", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.custody.store(dmFrame("theirs", from = alice, to = carol, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            pump()

            assertEquals("only this scope's own DM may ride it", 1, spool.pushed.size)
            sender.sync.stop()
        }

    @Test
    fun `pull batches at the spool's maxPull and re-pulls the truncated remainder`() =
        runTest {
            val spool = FakeSpool(maxPull = 2)
            val holder = member(spool, alice, bob)
            val ids = (1..5).map { "m$it" }
            ids.forEach { holder.custody.store(dmFrame(it, from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now) }
            holder.sync.start(backgroundScope)
            pump()
            holder.sync.stop()

            val receiver = member(spool, bob, alice)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals("every blob arrives despite the 2-per-PULL cap", ids.toSet(), receiver.delivered.map { it.id }.toSet())
            receiver.sync.stop()
        }

    @Test
    fun `mines a hashcash stamp only when the spool demands one`() =
        runTest {
            val open = FakeSpool(powBits = 0)
            val gated = FakeSpool(powBits = 8)
            val a = member(open, alice, bob)
            val b = member(gated, alice, bob)

            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()

            assertTrue("a PoW-free spool must not be handed a stamp", open.stamps.isEmpty())
            val stamp = gated.stamps[scopeHex(alice, bob)]
            assertTrue("the gated spool must get a valid stamp", stamp != null)
            a.sync.stop()
            b.sync.stop()
        }

    // --- Attachments, spec §4.5/§9.5 ---

    @Test
    fun `an image one member holds reaches the other through the spool`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            val receiver = member(spool, bob, alice)
            sender.custody.store(
                dmFrame("m1", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump(rounds = 16)

            // 100 000 bytes at the spec's 48 KiB chunk: three chunks, uploaded whole.
            val aid = aidHex(alice, bob, aHash)
            assertEquals(3, spool.chunkCount(scopeHex(alice, bob), aid))
            assertEquals(3, sender.metrics.snapshot().spoolAttachPushed)

            // The receiver got the frame, then the bytes it names — verified against that same address.
            assertEquals(listOf("m1"), receiver.delivered.map { it.id })
            assertTrue("the image landed locally", receiver.blobs.stored.containsKey(aHash))
            assertTrue(bytes.contentEquals(receiver.blobs.stored.getValue(aHash)))
            assertEquals(listOf(aHash), receiver.obtained)
            assertEquals(1, receiver.metrics.snapshot().spoolAttachPulled)
            assertEquals(0, receiver.metrics.snapshot().spoolInvalid)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a spool that advertises no attachment support is never sent an attachment record`() =
        runTest {
            val spool = FakeSpool(attachments = false)
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            val receiver = member(spool, bob, alice)
            sender.custody.store(
                dmFrame("m1", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump(rounds = 16)

            // The frame plane is unaffected — that is what makes attachments additive.
            assertEquals(listOf("m1"), receiver.delivered.map { it.id })
            // Not one attachment record went out. A v1 spool would have skipped it without answering,
            // stalling that q until the request timeout.
            assertEquals(emptyList<String>(), spool.skippedRecords)
            assertEquals(emptyList<String>(), spool.chunksPut)
            assertFalse(receiver.blobs.stored.containsKey(aHash))
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `an upload resumes from the spool's bitmap instead of restarting`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            sender.custody.store(
                dmFrame("m1", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            sender.sync.start(backgroundScope)
            pump(rounds = 12)
            val aid = aidHex(alice, bob, aHash)
            assertEquals(3, spool.chunkCount(scopeHex(alice, bob), aid))

            // The spool loses the middle chunk. The bitmap is what tells the client which one.
            spool.dropChunk(scopeHex(alice, bob), aid, index = 1)
            spool.chunksPut.clear()
            sender.sync.onCustodyChanged()
            pump(rounds = 12)

            assertEquals(3, spool.chunkCount(scopeHex(alice, bob), aid))
            assertEquals("only the missing chunk is re-sent", listOf("$aid:1"), spool.chunksPut)
            sender.sync.stop()
        }

    @Test
    fun `a chunk that fails to open quarantines the attachment instead of being refetched forever`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            val receiver = member(spool, bob, alice)
            sender.custody.store(
                dmFrame("m1", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            sender.sync.start(backgroundScope)
            pump(rounds = 12)
            // A spool is untrusted storage: it can serve bytes no member ever sealed.
            spool.corruptChunk(scopeHex(alice, bob), aidHex(alice, bob, aHash), index = 0)

            receiver.sync.start(backgroundScope)
            pump(rounds = 16)
            val afterFirst = spool.chunkGets.size
            pump(rounds = 16)

            assertFalse("the garbage never becomes a stored image", receiver.blobs.stored.containsKey(aHash))
            assertEquals(1, receiver.metrics.snapshot().spoolInvalid)
            // The whole point of the invalid set: an accounted failure, not an infinite re-pull.
            assertEquals("no further aget after the quarantine", afterFirst, spool.chunkGets.size)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `an attachment whose frame has aged out is not uploaded`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            // sentAt far enough back that the scope TTL has lapsed: §9.2's guard, on the frame that
            // references the image. Custody still holds it (its own TTL is longer than this gap).
            val stale = now - ScopeRegistry.DEFAULT_TTL_MS
            sender.custody.store(
                dmFrame("m1", from = alice, to = bob, sentAt = stale, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            pump(rounds = 12)

            assertEquals(emptyList<String>(), spool.chunksPut)
            assertEquals(0, sender.metrics.snapshot().spoolAttachPushed)
            sender.sync.stop()
        }

    @Test
    fun `a group photo crosses on the groupupdate that advertises it`() =
        runTest {
            val spool = FakeSpool()
            val groupId = "g-00112233445566778899aabb"
            val roster = setOf(alice, bob, carol)
            val group = GroupScopeRoots(groupId, roster, groupRoot, rootVersion = 1)
            val (photoHash, bytes) = image(60_000)
            val sender = member(spool, alice, bob, groups = listOf(group), blobs = FakeBlobs(photoHash to bytes))
            val receiver = member(spool, bob, alice, groups = listOf(group))
            sender.custody.store(
                groupUpdateFrame("gu1", from = alice, groupId = groupId, members = roster.toList(), sentAt = now, photoHash = photoHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump(rounds = 16)

            assertEquals(listOf("gu1"), receiver.delivered.map { it.id })
            assertTrue("the group photo landed", receiver.blobs.stored.containsKey(photoHash))
            assertTrue(bytes.contentEquals(receiver.blobs.stored.getValue(photoHash)))
            // GroupInfo carries no mime, so the fetcher's fallback names it.
            assertEquals("image/jpeg", receiver.blobs.mimes[photoHash])
            sender.sync.stop()
            receiver.sync.stop()
        }
}
