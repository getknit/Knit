package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
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
    private val pairwiseRoot = ByteArray(32) { it.toByte() }
    private val now = 1_000L
    private val url = "ws://spool.test/spool/v1"

    private class Member(
        val custody: FakeCustody,
        val sync: ScopeSync,
        val metrics: MeshMetrics,
        val delivered: MutableList<RelayEnvelope>,
    )

    private fun member(
        spool: FakeSpool,
        self: String,
        peer: String,
        custody: FakeCustody = FakeCustody(),
        carryGate: suspend (WireEnvelope, RelayEnvelope) -> Boolean = { _, _ -> true },
    ): Member {
        val metrics = MeshMetrics()
        val delivered = mutableListOf<RelayEnvelope>()
        val sync =
            ScopeSync(
                registry =
                    ScopeRegistry(
                        selfId = { self },
                        roots = { listOf(ScopeRoots(peer, pairwiseRoot)) },
                        isAccepted = { true },
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
                metrics = metrics,
                clock = { now },
                jitter = { 0L },
            )
        return Member(custody, sync, metrics, delivered)
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
}
