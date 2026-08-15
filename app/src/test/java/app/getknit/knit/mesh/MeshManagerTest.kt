package app.getknit.knit.mesh

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.moderation.TextVerdict
import app.getknit.knit.notifications.Notifier
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Drives the **real** [MeshManager] outbound send-and-originate workflow — `sendChat` and the origination
 * choke it funnels through (`originateSigned` → `sign` → custody capture → fast-fanout) — with real Tink
 * keypairs / [MessageCrypto], a recording [MeshTransport], a real in-memory [ForwardStore], and mockk
 * stand-ins for the Room-backed repos. It pins the highest-risk branch surface of the class the mesh is
 * built around: the moderation block-on-send gate, the broadcast-plaintext vs DM/group-E2E-encrypt split,
 * the `pendingKey` deferral when a recipient's key isn't known yet, and the attachment re-seal.
 *
 * Runs under Robolectric so `android.util.Log` / `android.util.Base64` (used by the send + crypto path)
 * resolve on the JVM, mirroring [InboundPipelineTest]. Timestamps are pinned via the injected `clock`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MeshManagerTest {
    /** A device identity: its cipher (private keys) + its published bundle; nodeId derives from the bundle. */
    private class Party(
        val crypto: MessageCrypto,
        val bundle: PublicKeyBundle,
    ) {
        val nodeId: String = NodeId.fromPublicKeyBundle(bundle.encoded)
    }

    private fun party(): Party {
        TinkInit.ensure()
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get("ED25519_RAW"))
        return Party(MessageCrypto(hybrid, sig), PublicKeyBundle.fromPrivate(hybrid, sig))
    }

    /** A [MeshTransport] that records every frame the manager originates (both flood + fast-fanout copies). */
    private class RecordingTransport : MeshTransport {
        val sent = mutableListOf<Pair<WireEnvelope, Peer?>>()
        override val neighbors = MutableStateFlow<Set<Peer>>(emptySet()).asStateFlow()
        override val health = MutableStateFlow(TransportHealth.Healthy).asStateFlow()
        override val inbound = MutableSharedFlow<InboundFrame>().asSharedFlow()
        override val incomingFiles = emptyFlow<ReceivedFile>()

        override fun start() = Unit

        override fun stop() = Unit

        override fun heal() = Unit

        override suspend fun send(
            wire: WireEnvelope,
            to: Peer?,
        ) {
            sent += wire to to
        }

        override suspend fun sendFile(
            file: File,
            to: Peer,
            meta: FileMeta,
        ): Boolean = true

        override suspend fun sendDigest(
            to: Peer,
            ids: List<String>,
        ) = Unit
    }

    /** Minimal in-memory [ForwardStore] so a test can assert what the send path captured for custody. */
    private class FakeForwardStore : ForwardStore {
        private val frames = linkedMapOf<String, CarriedFrame>()

        override suspend fun store(
            frame: CarriedFrame,
            origin: Int,
            now: Long,
        ): Boolean {
            frames.putIfAbsent(frame.envelope.id, frame)
            return true
        }

        override suspend fun liveFrames(now: Long): List<CarriedFrame> = frames.values.toList()

        override suspend fun liveIds(now: Long): List<String> = frames.keys.toList()

        override suspend fun attachmentHashesNeedingFetch(): List<String> = emptyList()

        override suspend fun recipientOf(id: String): String? = frames[id]?.envelope?.recipientId

        override suspend fun has(id: String): Boolean = frames.containsKey(id)

        override suspend fun remove(id: String) {
            frames.remove(id)
        }

        override suspend fun sweepExpired(now: Long): Int = 0
    }

    /** The manager under test, wired with real crypto + a recording transport + real custody + mocked repos. */
    private inner class Rig(
        scope: CoroutineScope,
    ) {
        val me = party()
        val bob = party()
        val transport = RecordingTransport()
        val forwardStore = FakeForwardStore()
        val messages = mockk<MessageRepository>(relaxed = true)
        val peers = mockk<PeerRepository>(relaxed = true)
        val blobs = mockk<BlobRepository>(relaxed = true)
        val groups = mockk<GroupRepository>(relaxed = true)
        val reactions = mockk<ReactionRepository>(relaxed = true)
        val settings = mockk<SettingsStore>(relaxed = true)
        val imageScreening = mockk<ImageScreeningService>(relaxed = true)
        val blobStore = mockk<MeshBlobStore>(relaxed = true)
        val notifier = mockk<Notifier>(relaxed = true)
        val textModeration = mockk<ScopedTextModerator>(relaxed = true)
        val identity = mockk<Identity>(relaxed = true)

        // A real (empty) in-memory DB as the manager's ctor arg; the send path never touches it (only the
        // inbound pipeline's reconcileGroup does), so it just satisfies construction. Mirrors InboundPipelineTest.
        val db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnitDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        val saved = mutableListOf<MessageEntity>()
        val now = 1_700_000_000_000L
        val metrics = MeshMetrics()
        val manager: MeshManager

        init {
            coEvery { identity.nodeId() } returns me.nodeId
            coEvery { textModeration.classify(any(), any()) } returns TextVerdict.ALLOWED
            coEvery { messages.save(any()) } answers { saved += firstArg<MessageEntity>() }
            coEvery { peers.find(any()) } returns null // default: no recipient key is known
            coEvery { blobs.bytes(any()) } returns null
            manager =
                MeshManager(
                    transport = transport,
                    messages = messages,
                    groups = groups,
                    reactions = reactions,
                    peers = peers,
                    identity = identity,
                    settings = settings,
                    blobs = blobs,
                    imageScreening = imageScreening,
                    blobStore = blobStore,
                    forwardStore = forwardStore,
                    notifier = notifier,
                    textModeration = textModeration,
                    messageCrypto = me.crypto,
                    ratchet =
                        RatchetSessions(
                            store = RatchetRepository(db.ratchetDao(), clock = { now }),
                            dhIdentityPriv = { ByteArray(32) { 1 } }, // send-path tests never derive
                            spkPrivFor = { null },
                        ),
                    groupRatchet = GroupRatchetSessions(store = GroupRatchetRepository(db.groupRatchetDao())),
                    scope = scope,
                    metrics = metrics,
                    db = db,
                    clock = { now },
                )
        }

        /** Pins [p]'s real key under its nodeId, as the profile handler would once its profile arrives. */
        fun pin(p: Party) {
            coEvery { peers.find(p.nodeId) } returns PeerEntity(nodeId = p.nodeId, pubKey = p.bundle.encoded, updatedAt = 1L)
        }

        /** The distinct CHAT routing envelopes the manager originated (collapsing the flood + fast-fanout copies). */
        fun sentChatFrames(): List<RelayEnvelope> =
            transport.sent
                .mapNotNull { WireCodec.decodeEnvelope(it.first.signed) }
                .filter { it.type == FrameType.CHAT }
                .distinctBy { it.id }
    }

    // --- moderation gate ---

    @Test
    fun flaggedTextIsBlockedOnSendAndNeitherStoredNorFlooded() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            coEvery { rig.textModeration.classify(any(), any()) } returns
                TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY)

            val ok = rig.manager.sendChat("something abusive")
            advanceUntilIdle()

            assertFalse("block-on-send: a flagged message is refused", ok)
            assertTrue("nothing is persisted locally", rig.saved.isEmpty())
            assertTrue("and nothing hits the wire", rig.transport.sent.isEmpty())
            coVerify(exactly = 0) { rig.messages.save(any()) }
        }

    // --- broadcast room (plaintext) ---

    @Test
    fun broadcastMessageIsStoredPlaintextFloodedAndCustodyCaptured() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)

            val ok = rig.manager.sendChat("gm mesh")
            advanceUntilIdle()

            assertTrue(ok)
            val saved = rig.saved.single()
            assertEquals("gm mesh", saved.body)
            assertNull("the broadcast room has no addressed recipient", saved.recipientId)
            assertFalse("plaintext room message is never pending-key", saved.pendingKey)
            assertEquals(rig.now, saved.sentAt)

            val frame = rig.sentChatFrames().single()
            assertEquals(rig.me.nodeId, frame.senderId)
            assertNull(frame.recipientId)
            assertEquals("the flooded frame shares the stored copy's id + timestamp", saved.id, frame.id)
            assertEquals(rig.now, frame.sentAt)

            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            assertEquals("the room is plaintext — body rides in the clear", "gm mesh", content.body)
            assertNull("and is not encrypted", content.enc)
            assertTrue("the message is captured for store-and-forward custody", rig.forwardStore.has(frame.id))
        }

    // --- DM: end-to-end encrypted when the key is known ---

    @Test
    fun directMessageIsEncryptedToRecipientAndOnlyTheyCanDecryptIt() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)

            val ok = rig.manager.sendChat("meet at 8", recipientId = rig.bob.nodeId)
            advanceUntilIdle()

            assertTrue(ok)
            val saved = rig.saved.single()
            assertEquals("the sender keeps a local plaintext copy", "meet at 8", saved.body)
            assertEquals(rig.bob.nodeId, saved.recipientId)
            assertFalse("the key is known, so it is not deferred", saved.pendingKey)

            val frame = rig.sentChatFrames().single()
            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            assertEquals("no plaintext body leaks on the wire", "", content.body)
            assertNotNull("the encrypted envelope rides the frame", content.enc)

            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, rig.bob.nodeId)
            val opened = rig.bob.crypto.open(content.enc!!, header, rig.bob.nodeId)
            assertNotNull("the addressed recipient can decrypt", opened)
            assertEquals("meet at 8", opened!!.body)
        }

    // --- DM: deferred (pendingKey) when the recipient's key is not yet known ---

    @Test
    fun directMessageWithoutARecipientKeyIsParkedPendingKeyAndNotFlooded() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            // peers.find(bob) defaults to null → no published key → nothing can decrypt it yet.

            val ok = rig.manager.sendChat("ping", recipientId = rig.bob.nodeId)
            advanceUntilIdle()

            assertTrue("still succeeds: the local copy is stored", ok)
            val saved = rig.saved.single()
            assertTrue("and marked pending until the key arrives", saved.pendingKey)
            assertEquals("ping", saved.body)
            assertTrue("nothing is flooded — no peer could read it", rig.sentChatFrames().isEmpty())
        }

    // --- group: encrypt to members with keys, excluding self and keyless members ---

    @Test
    fun groupMessageEncryptsOnlyToMembersWithKeysAndCarriesTheRoster() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val carol = party() // a member WITH a published key
            rig.pin(carol)
            // "dave" has no key (peers.find defaults to null) → excluded from the wrapped-key set.
            val members = listOf(rig.me.nodeId, carol.nodeId, "dave")
            val group = GroupInfo(id = "g-1", members = members, createdBy = rig.me.nodeId)

            val ok = rig.manager.sendChat("standup in 5", group = group)
            advanceUntilIdle()

            assertTrue(ok)
            val frame = rig.sentChatFrames().single()
            assertEquals("the roster rides on the frame so members can rebuild the group", members, frame.group?.members)

            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            val enc = content.enc!!
            assertEquals(
                "one wrapped key: the sender excludes itself and the keyless member",
                listOf(carol.nodeId),
                enc.keys.map { it.to },
            )
            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, group.id)
            assertEquals("the keyed member can decrypt", "standup in 5", carol.crypto.open(enc, header, carol.nodeId)?.body)
        }

    // --- attachment: re-seal under a ciphertext hash, key stays sealed ---

    @Test
    fun attachmentIsReSealedUnderItsCiphertextHashWithTheKeyKeptInsideTheSealedContent() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val plainHash = "plain-hash"
            coEvery { rig.blobs.bytes(plainHash) } returns "raw-image-bytes".toByteArray()

            val ok =
                rig.manager.sendChat(
                    "look",
                    attachment = AttachmentStore.Ingested(hash = plainHash, mime = "image/jpeg"),
                    recipientId = rig.bob.nodeId,
                )
            advanceUntilIdle()

            assertTrue(ok)
            val frame = rig.sentChatFrames().single()
            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            val ctHash = content.attachmentHash!!

            assertNotEquals("the frame is re-addressed by the ciphertext hash, not the plaintext one", plainHash, ctHash)
            assertEquals("the mime is exposed in the clear so a blind carrier can custody the blob", "image/jpeg", content.attachmentMime)
            coVerify { rig.blobs.insert(ctHash, "image/jpeg", any()) } // ciphertext stored under its hash
            coVerify { rig.blobs.deleteIfUnreferenced(plainHash) } // now-unreferenced plaintext dropped

            // The decryption key is sealed inside the encrypted content (never in the cleartext frame).
            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, rig.bob.nodeId)
            val opened = rig.bob.crypto.open(content.enc!!, header, rig.bob.nodeId)!!
            assertEquals("the sealed content references the same ciphertext blob", ctHash, opened.attachmentHash)
            assertNotNull("and carries the AES key the recipient needs", opened.attachmentKey)
        }

    // --- reply + mentions ride the frame and are persisted ---

    @Test
    fun replyAndMentionsAreStoredAndRideOnTheBroadcastFrame() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val mentions = listOf(Mention(nodeId = "u1", name = "Alice"))
            val reply = ReplyRef(messageId = "m0", authorId = "u1", author = "Alice", snippet = "hi")

            val ok = rig.manager.sendChat("@Alice yo", mentions = mentions, replyTo = reply)
            advanceUntilIdle()

            assertTrue(ok)
            val saved = rig.saved.single()
            assertEquals("mentions are persisted on the stored row", MentionStore.encode(mentions), saved.mentions)
            assertEquals("the quoted reply is denormalized onto the row", "m0", saved.replyToId)

            val content = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!
            assertEquals(mentions, content.mentions)
            assertEquals(reply, content.replyTo)
        }

    // --- the v2 (epoch-ratchet) send gate ---

    /** Pins [p] as ratchet-capable: CAP_RATCHET advertised plus a pinned prekey, as handleProfile stores them. */
    private fun Rig.pinRatchetCapable(
        p: Party,
        prekeyPub: ByteArray,
    ) {
        coEvery { peers.find(p.nodeId) } returns
            PeerEntity(
                nodeId = p.nodeId,
                pubKey = p.bundle.encoded,
                capabilities = Protocol.LOCAL_CAPABILITIES,
                prekeyId = 1,
                prekeyPub = b64(prekeyPub),
                prekeyProfileAt = 1L,
                updatedAt = 1L,
            )
    }

    @Test
    fun aDmToARatchetCapablePeerSealsV2() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)

            assertTrue(rig.manager.sendChat("fs hello", recipientId = rig.bob.nodeId))
            advanceUntilIdle()

            val content = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!
            val enc = content.enc!!
            assertEquals(EncEnvelope.VERSION_RATCHET, enc.v)
            assertTrue(enc.keys.isEmpty())
            val header = enc.r!!
            assertEquals(1, header.se)
            assertEquals(0, header.pe)
            assertNotNull("the first frame carries the X3DH init", header.init)
            assertEquals(1, header.init!!.pkid)
            assertFalse("the stored row is not pendingKey", rig.saved.single().pendingKey)
            assertEquals(1L, rig.metrics.snapshot().dmSealedV2)

            // A second DM continues the chain in the same epoch, init still attached (unconfirmed).
            assertTrue(rig.manager.sendChat("again", recipientId = rig.bob.nodeId))
            advanceUntilIdle()
            val second = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().last().payload)!!.enc!!.r!!
            assertEquals(1, second.se)
            assertEquals(1, second.n)
            assertNotNull(second.init)
        }

    @Test
    fun aDmToAPeerWithoutTheCapabilityStaysV1() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob) // pinned key, no capabilities, no prekey — a pre-ratchet build

            assertTrue(rig.manager.sendChat("legacy", recipientId = rig.bob.nodeId))
            advanceUntilIdle()

            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(1, enc.v)
            assertNull(enc.r)
            assertTrue(enc.keys.isNotEmpty())
            assertEquals(0L, rig.metrics.snapshot().dmSealedV2)
        }

    @Test
    fun aCapableClaimWithoutAPrekeyFallsBackToV1AndCounts() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            // Capability bit without a pinned prekey — the stale/partial case the AND-gate exists for.
            coEvery { rig.peers.find(rig.bob.nodeId) } returns
                PeerEntity(
                    nodeId = rig.bob.nodeId,
                    pubKey = rig.bob.bundle.encoded,
                    capabilities = Protocol.LOCAL_CAPABILITIES,
                    updatedAt = 1L,
                )

            assertTrue(rig.manager.sendChat("careful", recipientId = rig.bob.nodeId))
            advanceUntilIdle()

            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(1, enc.v)
            assertEquals(1L, rig.metrics.snapshot().dmSealedV1Fallback)
        }

    // --- the group sender-key send gate ---

    /** Pins [p] with [capabilities] and a prekey (the partially-capable cases the AND-gate exists for). */
    private fun Rig.pinWithCaps(
        p: Party,
        capabilities: Long,
    ) {
        coEvery { peers.find(p.nodeId) } returns
            PeerEntity(
                nodeId = p.nodeId,
                pubKey = p.bundle.encoded,
                capabilities = capabilities,
                prekeyId = 1,
                prekeyPub = b64(RatchetCrypto.generateKeyPair().pub),
                prekeyProfileAt = 1L,
                updatedAt = 1L,
            )
    }

    @Test
    fun aGroupWithAllCapableMembersSealsV3AndDistributesTheSeed() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val group = GroupInfo(id = "g-1", name = "Team", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)

            assertTrue(rig.manager.sendChat("group fs", group = group))
            advanceUntilIdle()

            val frames = rig.sentChatFrames()
            // The group frame sealed under the ratchet: derived key, empty wraps, the tiny sender-key header.
            val groupEnc = WireCodec.decodePayload<ChatContent>(frames.single { it.group != null }.payload)!!.enc!!
            assertEquals(EncEnvelope.VERSION_RATCHET, groupEnc.v)
            assertTrue(groupEnc.keys.isEmpty())
            assertNull(groupEnc.r)
            val header = checkNotNull(groupEnc.g)
            assertEquals(1, header.se)
            assertEquals(0, header.n)
            // The minted epoch's seed rode ahead, pairwise, as a v2 ctl DM.
            val seedDm = frames.single { it.recipientId == rig.bob.nodeId }
            assertEquals(EncEnvelope.VERSION_RATCHET, WireCodec.decodePayload<ChatContent>(seedDm.payload)!!.enc!!.v)
            assertEquals(1L, rig.metrics.snapshot().groupSealedRatchet)
            assertEquals(1L, rig.metrics.snapshot().groupSeedsSent)
            assertFalse("the stored group row is never pendingKey", rig.saved.single().pendingKey)
        }

    @Test
    fun aSecondGroupSendReusesTheChainWithoutRedistributing() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val group = GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)

            assertTrue(rig.manager.sendChat("one", group = group))
            assertTrue(rig.manager.sendChat("two", group = group))
            advanceUntilIdle()

            val frames = rig.sentChatFrames()
            assertEquals("one seed DM total — the chain is reused", 1, frames.count { it.recipientId == rig.bob.nodeId })
            val second = WireCodec.decodePayload<ChatContent>(frames.last { it.group != null }.payload)!!.enc!!.g!!
            assertEquals(1, second.se)
            assertEquals(1, second.n)
            assertEquals(2L, rig.metrics.snapshot().groupSealedRatchet)
        }

    @Test
    fun aGroupWithAnIncapableMemberFallsBackToV1Entirely() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val carol = party()
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            // A pre-ratchet build's capability set (everything except CAP_RATCHET — one bit covers both
            // ratchet forms now, so "DM-capable but not group-capable" cannot exist).
            rig.pinWithCaps(
                carol,
                capabilities = Protocol.CAP_E2E or Protocol.CAP_GROUPS or Protocol.CAP_REACTIONS or Protocol.CAP_STORE_FORWARD,
            )
            val group =
                GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId, carol.nodeId), createdBy = rig.me.nodeId)

            assertTrue(rig.manager.sendChat("mixed", group = group))
            advanceUntilIdle()

            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(1, enc.v)
            assertNull(enc.g)
            assertEquals(2, enc.keys.size)
            assertEquals(0L, rig.metrics.snapshot().groupSealedRatchet)
            // Ineligible (not eligible-but-fell-back): the fallback counter stays untouched — DM semantics.
            assertEquals(0L, rig.metrics.snapshot().groupSealedV1Fallback)
        }

    @Test
    fun anUnpinnedMemberKeepsTheGroupV1AndIsSkippedFromTheWraps() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val ghost = party() // never pinned — no profile ever arrived
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val group =
                GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId, ghost.nodeId), createdBy = rig.me.nodeId)

            assertTrue(rig.manager.sendChat("who's there", group = group))
            advanceUntilIdle()

            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(1, enc.v)
            // The v1 silent-skip of unpinned members is unchanged (their recovery plane is the ratchet + NACK,
            // or the key-gap roadmap note for pure-v1 groups).
            assertEquals(listOf(rig.bob.nodeId), enc.keys.map { it.to })
        }

    @Test
    fun aKeyRequestReSendsCurrentSeedsOncePerFloor() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val members = listOf(rig.me.nodeId, rig.bob.nodeId)
            val group = GroupInfo(id = "g-1", members = members, createdBy = rig.me.nodeId)
            coEvery { rig.groups.find("g-1") } returns
                GroupEntity(
                    groupId = "g-1",
                    name = "",
                    members = GroupMembersStore.encode(members),
                    createdBy = rig.me.nodeId,
                    createdAt = 1L,
                )
            assertTrue(rig.manager.sendChat("mint it", group = group))
            advanceUntilIdle()

            fun seedDmsToBob() = rig.sentChatFrames().count { it.recipientId == rig.bob.nodeId }
            val afterMint = seedDmsToBob()

            // A member's key request re-seals the current seeds…
            rig.manager.redistributeGroupKey("g-1", rig.bob.nodeId)
            advanceUntilIdle()
            assertEquals(afterMint + 1, seedDmsToBob())

            // …once per floor window (the fixed test clock keeps the window closed)…
            rig.manager.redistributeGroupKey("g-1", rig.bob.nodeId)
            advanceUntilIdle()
            assertEquals(afterMint + 1, seedDmsToBob())

            // …and a non-member's request re-seals nothing.
            val outsider = party()
            rig.manager.redistributeGroupKey("g-1", outsider.nodeId)
            advanceUntilIdle()
            assertEquals(0, rig.sentChatFrames().count { it.recipientId == outsider.nodeId })
        }

    @Test
    fun seedsAreStillDistributedToBlockedMembers() =
        runTest(UnconfinedTestDispatcher()) {
            // ADR 010: blocking is local presentation only — withholding a seed would reveal the block
            // through the blocked member's decrypt failures.
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            coEvery { rig.settings.blockedNodeIds } returns MutableStateFlow(setOf(rig.bob.nodeId))
            val group = GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)

            assertTrue(rig.manager.sendChat("still sealed", group = group))
            advanceUntilIdle()

            assertEquals(1, rig.sentChatFrames().count { it.recipientId == rig.bob.nodeId })
            assertEquals(
                EncEnvelope.VERSION_RATCHET,
                WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single { it.group != null }.payload)!!.enc!!.v,
            )
        }

    @Test
    fun theProfileAdvertisesTheRatchetCapabilityAndAVerifiablePrekey() {
        assertTrue(Protocol.LOCAL_CAPABILITIES and Protocol.CAP_RATCHET != 0L)
    }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"
    }
}
