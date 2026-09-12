package app.getknit.knit.mesh.spool

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The commons (spec §7.4) end to end, members over an in-process spool that runs one: profiles ride the
 * ordinary door and pin, posts ride the commons door and never custody, the room is bound to its relay,
 * and the digest converges although nothing a member pulls is ever held. The convergence harness for
 * the feature — nothing here crosses the radio mesh, so `mesh/lab/` has no scenario to add.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommonsSyncTest {
    private val alice = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val carol = "cccccccccccccccccccccccccc"
    private val secret = ByteArray(32) { (it + 5).toByte() }
    private val otherSecret = ByteArray(32) { (it + 77).toByte() }
    private val now = 1_000L
    private val url = "ws://spool.test/spool/v1"
    private val otherUrl = "ws://other.test/spool/v1"
    private val scopeId = ScopeCrypto.commonsScopeId(secret)
    private val scopeHex = hex(scopeId)
    private val conversationId = Conversations.commonsIdFor(scopeHex)
    private val advertised = SpoolCommonsInfo(name = "Home", maxFrames = 5, ttlMs = 24 * 60 * 60_000L, maxBlob = 65_536)

    private fun spool(
        powBits: Int = 0,
        commons: Pair<ByteArray, SpoolCommonsInfo>? = scopeId to advertised,
    ) = FakeSpool(powBits = powBits, commons = commons)

    /** The in-memory [CommonsStore]: one joined room, its outbox, its members. */
    private class FakeCommons(
        // Read live, so a test can leave a room mid-run.
        private val rooms: List<CommonsRoots>,
    ) : CommonsStore {
        val outbox = LinkedHashMap<String, MutableList<CarriedFrame>>()
        val members = mutableListOf<Pair<String, String>>()

        override suspend fun roots(): List<CommonsRoots> = rooms.toList()

        override suspend fun find(conversationId: String): CommonsRoom? =
            rooms.firstOrNull { it.conversationId == conversationId }?.let { CommonsRoom(it.conversationId, it.spoolUrl, it.secret, null) }

        override suspend fun frames(conversationId: String): List<CarriedFrame> = outbox[conversationId].orEmpty()

        override suspend fun post(
            row: MessageEntity,
            sig: ByteArray,
            signed: ByteArray,
        ) = error("the plane never writes the outbox")

        override suspend fun recordMember(
            conversationId: String,
            nodeId: String,
            now: Long,
        ) {
            members.add(conversationId to nodeId)
        }

        override suspend fun allMembers(): List<CommonsMember> = members.map { CommonsMember(it.first, it.second, 0L) }

        override suspend fun sweepOutbox(before: Long) = Unit
    }

    private class Member(
        val custody: FakeCustody,
        val commons: FakeCommons,
        val sync: ScopeSync,
        val metrics: MeshMetrics,
        val delivered: MutableList<RelayEnvelope>,
        val posts: MutableList<Pair<RelayEnvelope, ChatContent>>,
        val pinned: MutableSet<String>,
        val tombstoneReports: MutableList<Unit>,
    )

    private fun member(
        spool: FakeSpool,
        self: String,
        rooms: List<CommonsRoots> = listOf(CommonsRoots(conversationId, url, secret)),
        urls: List<String> = listOf(url),
        dialer: SpoolDialer = spool,
        custody: FakeCustody = FakeCustody(),
        // Who this member has pinned. The carry gate accepts a profile from anyone (self-certifying) and
        // anything else only from a pinned sender — the shape of `InboundPipeline.canCarry` — and a
        // delivered profile pins its author, the shape of `handleProfile`.
        pinned: MutableSet<String> = mutableSetOf(self),
        refuse: Set<String> = emptySet(),
    ): Member {
        val metrics = MeshMetrics()
        val delivered = mutableListOf<RelayEnvelope>()
        val posts = mutableListOf<Pair<RelayEnvelope, ChatContent>>()
        val commons = FakeCommons(rooms)
        val reports = mutableListOf<Unit>()
        val sync =
            ScopeSync(
                registry = ScopeRegistry(selfId = { self }, roots = { emptyList() }, commons = { commons.roots() }),
                dialer = dialer,
                store = custody,
                selfId = { self },
                urls = { urls },
                canCarry = { _, env -> env.id !in refuse && (env.type == FrameType.PROFILE || env.senderId in pinned) },
                deliver = { wire, env, _ ->
                    delivered.add(env)
                    if (env.type == FrameType.PROFILE) pinned.add(env.senderId)
                    custody.store(CarriedFrame(env, wire.sig, wire.signed), ForwardStore.ORIGIN_RELAY, now)
                },
                commons = commons,
                hasKey = { it in pinned },
                deliverCommons = { env, chat, _, _ -> posts.add(env to chat) },
                onCommonsMember = { id, node -> commons.recordMember(id, node, now) },
                onOwnProfileTombstoned = { reports.add(Unit) },
                metrics = metrics,
                clock = { now },
                jitter = { 0L },
            )
        return Member(custody, commons, sync, metrics, delivered, posts, pinned, reports)
    }

    private suspend fun Member.seedOwnProfile(
        self: String,
        id: String = "profile-$self",
    ) {
        custody.store(profileFrame(id, self), ForwardStore.ORIGIN_SELF, now)
    }

    private fun Member.post(frame: CarriedFrame) {
        commons.outbox.getOrPut(conversationId) { mutableListOf() }.add(frame)
        sync.onCustodyChanged()
    }

    private fun Member.status(): ScopeStatus =
        sync
            .status()
            .single { it.url == url }
            .scopes
            .single { it.scopeHex == scopeHex }

    private fun TestScope.pump(rounds: Int = 8) {
        repeat(rounds) {
            advanceTimeBy(1_000)
            runCurrent()
        }
    }

    /** One 60 s heal tick, for the rounds nothing wakes. */
    private fun TestScope.tick(times: Int = 1) {
        repeat(times) {
            advanceTimeBy(61_000)
            runCurrent()
        }
    }

    private fun sealedPost(
        id: String,
        from: String,
        scope: ByteArray = scopeId,
        keys: ScopeCrypto.SealKeys = ScopeCrypto.commonsSealKeys(secret),
        sealAs: ByteArray = scopeId,
    ): Pair<String, ByteArray> {
        val frame = commonsFrame(id, from, scope)
        val blob = ScopeCrypto.seal(keys, sealAs, frame.sig, frame.signed)
        return hex(ScopeCrypto.blobId(blob)) to blob
    }

    @Test
    fun `a post reaches the other member through the commons door and never through custody`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            val b = member(spool, bob)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.post(commonsFrame("p1", alice, scopeId))
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()

            // The profile rode the ordinary door: delivered, pinned, custodied. The post rode its own.
            assertEquals(listOf("profile-$alice"), b.delivered.map { it.id })
            assertTrue(b.custody.has("profile-$alice"))
            assertEquals(listOf("p1"), b.posts.map { it.first.id })
            assertEquals(
                "hello room",
                b.posts
                    .single()
                    .second.body,
            )
            assertFalse(b.custody.has("p1"))
            assertTrue(alice in b.pinned)
            // Both frames named Alice a member; Bob's own profile did not name Bob one on his own device.
            assertEquals(setOf(conversationId to alice), b.commons.members.toSet())
            assertEquals(setOf(conversationId to bob), a.commons.members.toSet())
            // Everything pulled is accounted (nothing a member pulls from a commons is held), so both converge.
            assertTrue(a.status().converged)
            assertTrue(b.status().converged)
            assertEquals(2, b.status().accountedCount)
            assertEquals(1, a.status().accountedCount)
            assertEquals(0, b.status().invalidCount)
        }

    @Test
    fun `a post pulled ahead of its author's profile waits for it instead of being quarantined`() =
        runTest {
            val spool = spool()
            // Alice's post lands on the spool first, then her profile — the order a listing hands Bob.
            val (postHex, blob) = sealedPost("p1", alice)
            spool.plantGarbage(scopeHex, blob)
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            val before = spool.pulled.count { it == postHex }
            val b = member(spool, bob)
            b.seedOwnProfile(bob)
            b.sync.start(backgroundScope)
            pump()

            assertEquals(listOf("p1"), b.posts.map { it.first.id })
            assertEquals(0, b.status().invalidCount)
            // The diagnostics fold is recomputed at the start of a round, so one quiet tick reads the truth.
            tick()
            assertTrue(b.status().converged)
            // Delivered on the round's second pass, so Bob pulled the blob exactly once.
            assertEquals(1, spool.pulled.count { it == postHex } - before)
        }

    @Test
    fun `a post whose author never shows a profile is given up on after a bounded wait`() =
        runTest {
            val spool = spool()
            val (postHex, blob) = sealedPost("p1", carol)
            spool.plantGarbage(scopeHex, blob)
            val b = member(spool, bob)
            b.seedOwnProfile(bob)
            b.sync.start(backgroundScope)
            pump()
            assertTrue(b.posts.isEmpty())
            assertEquals(0, b.status().invalidCount)

            tick(times = 9)

            assertTrue(b.posts.isEmpty())
            assertEquals(1, b.status().invalidCount)
            // A quarantined blob keeps the two digests apart for good (§9.3); what the invalid set buys is quiet.
            val pulls = spool.pulled.count { it == postHex }
            tick(times = 3)
            assertEquals("quarantined, so never asked for again", pulls, spool.pulled.count { it == postHex })
        }

    @Test
    fun `a reconnect does not re-pull what was already accounted`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            val b = member(spool, bob)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.post(commonsFrame("p1", alice, scopeId))
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()
            val pulls = spool.pulled.size

            spool.dropSockets()
            pump(16)

            assertTrue(b.status().converged)
            assertEquals(pulls, spool.pulled.size)
            assertEquals(1, b.posts.size)
        }

    @Test
    fun `the commons is subscribed only at the relay that runs it`() =
        runTest {
            val home = spool()
            val other = spool(commons = null)
            val dialer =
                object : SpoolDialer {
                    override suspend fun dial(url: String): SpoolSocket? =
                        if (url ==
                            this@CommonsSyncTest.url
                        ) {
                            home.dial(url)
                        } else {
                            other.dial(url)
                        }
                }
            val a = member(home, alice, urls = listOf(url, otherUrl), dialer = dialer)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()

            assertTrue(scopeHex in home.subscribedScopes)
            assertFalse(scopeHex in other.subscribedScopes)
            assertTrue(
                a.sync
                    .status()
                    .single { it.url == otherUrl }
                    .scopes
                    .isEmpty(),
            )
            assertEquals(
                "Home",
                a.sync
                    .status()
                    .single { it.url == url }
                    .commons
                    ?.name,
            )
        }

    @Test
    fun `a relay that advertises no commons is never asked for one`() =
        runTest {
            val spool = spool(commons = null)
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            assertFalse(scopeHex in spool.subscribedScopes)
        }

    @Test
    fun `no proof of work is mined for the commons`() =
        runTest {
            val spool = spool(powBits = 8)
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            assertTrue(scopeHex in spool.subscribedScopes)
            assertFalse(spool.stamps.containsKey(scopeHex))
            assertTrue(spool.pushed.isNotEmpty())
        }

    @Test
    fun `the spool's pinned bounds win over what the scope table guessed`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            // Six posts into a room pinned at five: the spool evicts the oldest and Bob still converges,
            // which only holds if the client sized its accounted set and its digest against the pinned cap.
            val b = member(spool, bob)
            b.seedOwnProfile(bob)
            b.sync.start(backgroundScope)
            repeat(6) { i -> a.post(commonsFrame("p$i", alice, scopeId, sentAt = now + i)) }
            pump(16)
            assertTrue(b.status().converged)
            assertEquals(5, spool.liveIds(scopeHex).size)
        }

    @Test
    fun `a post re-sealed from another room and a post that fails the carry gate are quarantined`() =
        runTest {
            val spool = spool()
            // Carol is in this room and another; she re-seals a post she signed for the other room into this one.
            val (crossHex, crossBlob) = sealedPost("x1", carol, scope = ScopeCrypto.commonsScopeId(otherSecret))
            spool.plantGarbage(scopeHex, crossBlob)
            val b = member(spool, bob, pinned = mutableSetOf(bob, carol, alice), refuse = setOf("bad1"))
            b.seedOwnProfile(bob)
            val (badHex, badBlob) = sealedPost("bad1", alice)
            spool.plantGarbage(scopeHex, badBlob)
            b.sync.start(backgroundScope)
            pump()

            assertTrue(b.posts.isEmpty())
            assertEquals(2, b.status().invalidCount)
            tick(times = 2)
            assertEquals(1, spool.pulled.count { it == crossHex })
            assertEquals(1, spool.pulled.count { it == badHex })
        }

    @Test
    fun `our own profile tombstoned by the room asks for one fresh stamp`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            val profileHex = spool.pushed.single()

            spool.expire(scopeHex, profileHex)
            pump()
            tick(times = 3)

            assertEquals(1, a.tombstoneReports.size)
        }

    @Test
    fun `only our own profile leaves for the room, never a pinned peer's`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            // Carol's profile sits in Alice's custody from the radios; it must not be re-published into the room.
            a.custody.store(profileFrame("profile-$carol", carol), ForwardStore.ORIGIN_RELAY, now)
            a.sync.start(backgroundScope)
            pump()
            assertEquals(1, spool.pushed.size)
            assertTrue(a.status().converged)
        }

    @Test
    fun `an event from the room delivers a post without a heal round`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            val b = member(spool, bob)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()
            assertTrue(b.posts.isEmpty())

            a.post(commonsFrame("p1", alice, scopeId))
            pump(2)

            assertEquals(listOf("p1"), b.posts.map { it.first.id })
            assertTrue(b.status().converged)
        }

    @Test
    fun `leaving drops the subscription at the next reconcile, and rejoining pulls the room again`() =
        runTest {
            val spool = spool()
            val (postHex, blob) = sealedPost("p1", alice)
            spool.plantGarbage(scopeHex, blob)
            val rooms = mutableListOf(CommonsRoots(conversationId, url, secret))
            val b = member(spool, bob, rooms = rooms, pinned = mutableSetOf(bob, alice))
            b.seedOwnProfile(bob)
            b.sync.start(backgroundScope)
            pump()
            assertEquals(
                1,
                b.sync
                    .status()
                    .single()
                    .scopes.size,
            )
            assertEquals(listOf("p1"), b.posts.map { it.first.id })

            rooms.clear()
            b.sync.onScopeTableChanged()
            pump(2)
            assertTrue(
                b.sync
                    .status()
                    .single()
                    .scopes
                    .isEmpty(),
            )
            // A post landing while we are out is not delivered: the connection is still subscribed (no
            // `unsub` exists), so the spool fans it out, and the worker must drop it on the floor.
            val (lateHex, lateBlob) = sealedPost("p2", alice)
            spool.plantGarbage(scopeHex, lateBlob)
            pump(2)
            assertEquals(listOf("p1"), b.posts.map { it.first.id })

            // Rejoin on the same connection: leaving deleted the history, so the room is pulled afresh —
            // including the post this worker had already accounted before the leave.
            rooms.add(CommonsRoots(conversationId, url, secret))
            b.sync.onScopeTableChanged()
            pump(4)
            assertEquals(listOf("p1", "p1", "p2"), b.posts.map { it.first.id })
            assertEquals(2, spool.pulled.count { it == postHex })
            assertEquals(1, spool.pulled.count { it == lateHex })
            tick()
            assertTrue(b.status().converged)
        }
}
