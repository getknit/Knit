package app.getknit.knit.mesh.lab

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.room3.withWriteTransaction
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.StatusNotices
import app.getknit.knit.data.peer.MetPeerRepository
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.data.ratchet.GroupRootRepository
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.data.settings.ContributionTotals
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.ContributionLedger
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.IngressBudget
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.SessionTransactor
import app.getknit.knit.moderation.ImageModerator
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.moderation.TextVerdict
import app.getknit.knit.notifications.Notifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.file.Files

/**
 * **Mesh in a box**: N complete, real Knit stacks in one JVM — the real [MeshManager] (and so the real
 * `InboundPipeline`, `MeshRouter`, `ForwardSync`, `KeyExchange`, `AckSync`, ratchets), the Room-backed
 * repositories over Robolectric's in-memory SQLite, a real [IdentityKeyStore] over an in-memory secret, and a
 * DataStore-backed [SettingsStore] — linked through [LabTransport], an in-process [app.getknit.knit.mesh.MeshTransport]
 * with no radio behind it. The only doubles are the leaves with a hardware or UI side: the notifier, the tflite
 * text moderators and the image moderator.
 *
 * What it is for: the bug that lives *between* two nodes — A's real send order meeting B's real state — which
 * neither `MeshManagerTest` (real sender, recording transport, mocked repos) nor `InboundPipelineTest` (real
 * receiver, hand-built frames, mocked repos) can see, because each half is checked against a stand-in for the
 * other. The seed-before-roster race (cf94a06: the creator floods a group's sender-key seed before the frame
 * that carries its roster, and the receiver's DM ratchet consumed the seed for good) is the founding case.
 * Scenarios are written at user level (`createGroup`, `sendGroup`) and end in [assertConverged], the
 * universal oracle — every member holds the same decrypted messages — so a scenario catches what its author
 * did not think to assert.
 *
 * Time is real: `MeshManager.start` builds its session on `Dispatchers.Default`, so a scenario waits with
 * [await] rather than virtual time (the same reason `MeshManagerTest.await` exists). Nothing here is Koin;
 * the wiring mirrors `di/AppModule` + `di/MeshModule` by hand, so a constructor change shows up as a compile
 * error in this file rather than as a silently narrower rig.
 */
class MeshLab {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File = Files.createTempDirectory("meshlab").toFile()
    private val nodes = mutableListOf<LabNode>()

    /**
     * Creates and starts a node — its own identity, database and settings file — and returns once its router
     * is listening, so a link made next cannot lose the profile push (see [LabTransport.collecting]).
     */
    suspend fun node(
        name: String,
        limits: LabLimits = LabLimits(),
    ): LabNode {
        val node = LabNode(name, context, File(dir, name).apply { mkdirs() }, limits)
        nodes += node
        node.boot()
        return node
    }

    /** Links two started nodes both ways, as a data path coming up would; each side then pushes its profile. */
    fun link(
        a: LabNode,
        b: LabNode,
    ) {
        a.transport.connect(b.transport)
    }

    /**
     * Takes a link down (out of range). Frames parked on it are lost, as a torn-down link loses them. Settles
     * for [SETTLE_MS] afterwards so every peer's neighbor collector observes the departure: `neighbors` is a
     * conflating `StateFlow`, and a link that comes back inside the collector's wake-up is a link that never
     * went down — no newcomer, so no profile push, no digest exchange, no re-send of an owed group seed. No
     * radio flaps that fast; the lab can.
     */
    suspend fun unlink(
        a: LabNode,
        b: LabNode,
    ) {
        a.transport.disconnect(b.transport)
        settle()
    }

    /** See [unlink]. */
    internal suspend fun settle() = withContext(Dispatchers.Default) { delay(SETTLE_MS) }

    /**
     * Brings a whole topology up at once: every link exists before any node's `neighbors` moves. With links
     * made one at a time, a relay fired in the gap between two of them (the router's 0–150 ms jitter) can
     * miss a node that is not linked yet, and the frame then waits for the 60 s custody re-offer — real-world
     * latency, but a stall in a scenario that only wants the topology to exist.
     */
    fun linkAll(vararg links: Pair<LabNode, LabNode>) {
        links.forEach { (a, b) -> a.transport.connect(b.transport, publish = false) }
        links.flatMap { it.toList() }.distinct().forEach { it.transport.publishNeighbors() }
    }

    /** Stops every node and drops its state. */
    fun close() {
        nodes.forEach { it.shutdown() }
        dir.deleteRecursively()
    }

    /**
     * Polls [have] until it reaches [count] or [timeoutMs] elapses, on a real dispatcher (the session scopes
     * run on `Dispatchers.Default`, so virtual time can't see them). Returns whether it got there; the caller
     * asserts, with a message that says what the node actually holds.
     */
    suspend fun await(
        count: Int,
        timeoutMs: Long = AWAIT_MS,
        have: suspend () -> Int,
    ): Boolean =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs) {
                while (have() < count) delay(POLL_MS)
                true
            } == true
        }

    /**
     * The universal oracle, in four parts, each awaited (the thing under test is exactly whether it happens)
     * and then asserted with a per-node listing so a failure reads as a diff:
     *
     * 1. **Messages.** Every node in [nodes] holds the same set of ordinary (decrypted) messages in the thread
     *    [conversation] names on it (a group id is the same everywhere; a DM thread is named after the *other*
     *    party, so it differs per node) — at least [atLeast] of them, none stranded as `pendingKey`.
     * 2. **Ticks.** Every message a node authored has been acked by every other node in [nodes] — the sealed
     *    receipt (ADR 018) is a second cross-node protocol under every message, and "the sender never saw a
     *    tick" was cf94a06's user-visible half.
     * 3. **Custody.** The store-and-forward stores of [nodes] and [carriers] hold the same live id set
     *    (`liveFingerprint` parity, the same oracle the device soaks use). Two stores that quietly disagree
     *    while delivery looks fine are the "NAN churns forever" class (the self-frame wipe divergence).
     * 4. No node is sitting on a parked group seed that never replayed.
     *
     * [carriers] are nodes that relayed and custodied but are not party to the thread — they take part in the
     * custody check only.
     */
    suspend fun assertConverged(
        nodes: List<LabNode>,
        atLeast: Int,
        carriers: List<LabNode> = emptyList(),
        timeoutMs: Long = AWAIT_MS,
        conversation: (LabNode) -> String,
    ) {
        val names = nodes.map { it.name }
        val landed =
            await(1, timeoutMs) {
                val sets = nodes.map { it.decrypted(conversation(it)) }
                if (sets.all { it.size >= atLeast } && sets.distinct().size == 1) 1 else 0
            }
        assertTrue(
            "messages did not converge across $names within ${timeoutMs}ms:\n${listing(nodes, conversation)}\n" +
                nodes.joinToString("\n") { it.metricsLine() },
            landed,
        )
        nodes.forEach { n ->
            val pending =
                n.messages
                    .observeNewestMessages(conversation(n), WINDOW)
                    .first()
                    .filter { it.pendingKey }
            assertTrue("${n.name} has messages stranded on pendingKey: ${pending.map { it.id }}", pending.isEmpty())
        }

        val ticked = await(1, timeoutMs) { if (nodes.all { n -> n.missingAcks(conversation(n), nodes).isEmpty() }) 1 else 0 }
        val owed = nodes.map { n -> "  ${n.name}: ${n.missingAcks(conversation(n), nodes)}" }.joinToString("\n")
        assertTrue("delivery ticks did not converge across $names within ${timeoutMs}ms (message → who never acked):\n$owed", ticked)

        val stores = nodes + carriers
        val custodied = await(1, timeoutMs) { if (stores.map { it.custodyFingerprint() }.distinct().size == 1) 1 else 0 }
        val ids = stores.map { n -> "  ${n.name}: ${n.custodyIds().sorted()}" }.joinToString("\n")
        assertTrue("custody did not converge across ${stores.map { it.name }} within ${timeoutMs}ms:\n$ids", custodied)

        stores.forEach { n ->
            val snap = n.metrics.snapshot()
            assertEquals("${n.name} parked a group seed that never replayed", snap.groupSeedsHeld, snap.groupSeedsReplayed)
            // A node never pins its own key: its own profile loops back through every peer's custody, and a
            // self row turns every seal-to-a-pinned-peer path on ourselves (the hourly self-addressed frames
            // found in the lab fleet's custody, 2026-09-13).
            assertTrue("${n.name} pinned a peer row for itself", n.peers.find(n.nodeId) == null)
        }
    }

    private suspend fun listing(
        nodes: List<LabNode>,
        conversation: (LabNode) -> String,
    ): String = nodes.map { n -> "  ${n.name}: ${n.decrypted(conversation(n)).map { it.second }}" }.joinToString("\n")

    /** Waits until every pair in [nodes] has pinned the other's key — the profile exchange a link-up starts. */
    suspend fun awaitAcquainted(vararg nodes: LabNode) {
        val pairs = nodes.flatMap { a -> nodes.filter { it !== a }.map { b -> a to b } }
        val ok = await(1) { if (pairs.all { (a, b) -> a.knows(b) }) 1 else 0 }
        val missing = pairs.filterNot { (a, b) -> a.knows(b) }.map { (a, b) -> "${a.name}→${b.name}" }
        assertTrue(
            "profiles never exchanged among ${nodes.map { it.name }}; missing $missing\n${nodes.joinToString("\n") { it.metricsLine() }}",
            ok,
        )
    }

    companion object {
        const val AWAIT_MS = 15_000L
        const val POLL_MS = 25L
        const val WINDOW = 500

        /**
         * A group tick toward an absent author batches this long before it escalates into custody. The field
         * value is 45 s ([app.getknit.knit.mesh.AckSync.TICK_BATCH_DEBOUNCE_MS]); the lab shortens it so a tick
         * crossing a relay converges inside [AWAIT_MS] without changing which path it takes.
         */
        const val TICK_DEBOUNCE_MS = 300L

        /** How long a departure is left visible before the next topology change ([unlink], [LabNode.restart]). */
        const val SETTLE_MS = 100L
    }
}

/**
 * The storage and ingress policy numbers a node boots with. Production takes the field defaults; a flood
 * scenario shrinks them so a handful of posts is a flood — same rules, same paths, smaller numbers.
 */
data class LabLimits(
    /** Newest posts a room keeps ([MessageRepository]'s `nearbyMaxMessages`). */
    val roomMaxMessages: Int = 2_000,
    /** Newest posts a room keeps per stranger ([MessageRepository]'s `roomMaxPerStranger`). */
    val roomMaxPerStranger: Int = 200,
    /** Room posts one link may hand over at once ([IngressBudget]'s `burst`). */
    val ingressBurst: Int = IngressBudget.DEFAULT_BURST,
    /** Room posts per minute one link may sustain ([IngressBudget]'s `perMinute`). */
    val ingressPerMinute: Int = IngressBudget.DEFAULT_PER_MINUTE,
)

/**
 * One phone. The persistent half (identity secret, database, settings file) outlives [boot]/[shutdown], so
 * [restart] is a real process death: every in-memory structure — `PendingInbound`, `PendingGroupKeys`, the
 * ratchet caches, the seen set — is rebuilt from what was committed.
 */
class LabNode internal constructor(
    val name: String,
    private val context: Context,
    private val dir: File,
    private val limits: LabLimits,
) {
    // --- persistent across restarts ---

    // The keystore-wrapped identity file, held as bytes: IdentityKeyStore only ever calls load()/store().
    private var secretBytes: ByteArray? = null
    private val secret =
        mockk<KeystoreSecret> {
            every { exists() } answers { secretBytes != null }
            every { load() } answers { secretBytes }
            every { store(any()) } answers { secretBytes = firstArg<ByteArray>().copyOf() }
            every { delete() } answers { secretBytes = null }
        }
    private val keyStore = IdentityKeyStore(secret)
    val identity = Identity(keyStore) { "device-$name" }

    val db: KnitDatabase =
        Room
            .inMemoryDatabaseBuilder(context, KnitDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = settingsScope) { File(dir, "settings.preferences_pb") })

    // --- the live stack, rebuilt by boot() ---

    lateinit var transport: LabTransport
        private set
    lateinit var manager: MeshManager
        private set
    lateinit var messages: MessageRepository
        private set
    lateinit var groups: GroupRepository
        private set
    lateinit var peers: PeerRepository
        private set
    lateinit var metrics: MeshMetrics
        private set

    /** What this node has done for other people's messages — the Your mesh screen's lifetime numbers. */
    lateinit var ledger: ContributionLedger
        private set
    private lateinit var receipts: MessageReceiptRepository
    private lateinit var forwardStore: ForwardRepository
    private var scope: CoroutineScope? = null

    /** The self-certifying id, computed once from the bundle exactly as [Identity.nodeId] does. */
    val nodeId: String = NodeId.fromPublicKeyBundle(identity.publicKeyBundle())

    @Suppress("LongMethod") // the DI module's wiring, mirrored in one place on purpose
    internal suspend fun boot() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { this.scope = it }
        transport = LabTransport(nodeId)
        metrics = MeshMetrics()
        // The real DataStore-backed settings are the journal, so a restart() proves the totals persist.
        ledger = ContributionLedger(journal = settings, selfId = { nodeId })
        val keys = keyStore.keys()
        val messageCrypto = MessageCrypto(keys.hybridPrivate, keys.sigPrivate)
        messages =
            MessageRepository(
                db.messageDao(),
                nearbyMaxMessages = limits.roomMaxMessages,
                roomMaxPerStranger = limits.roomMaxPerStranger,
            )
        peers = PeerRepository(db.peerDao(), settings, identity)
        val reactions = ReactionRepository(db.reactionDao(), db)
        receipts = MessageReceiptRepository(db.messageReceiptDao(), messages, db)
        val blobs =
            BlobRepository(db.blobDao(), db.messageDao(), db.peerDao(), settings, db.blobVerdictDao(), db.groupDao(), db.forwardDao(), db)
        val groupRatchetStore = GroupRatchetRepository(db.groupRatchetDao())
        val groupRoots = GroupRootRepository(db.groupRootDao())
        groups = GroupRepository(db.groupDao(), messages, db, groupRatchetStore, groupRoots)
        forwardStore = ForwardRepository(db.forwardDao(), StoreDigest(), db)
        // The leaves with a hardware or UI side. Text allowed, images never classified, notifications swallowed.
        val allowAll = mockk<ScopedTextModerator> { coEvery { classify(any(), any()) } returns TextVerdict.ALLOWED }
        val imageScreening = ImageScreeningService(mockk<ImageModerator>(relaxed = true), db.blobVerdictDao(), allowAll)
        val blobStore = MeshBlobStore(blobs, messages, imageScreening, File(dir, "blobtx"))
        val notifier = mockk<Notifier>(relaxed = true)
        // THE ratchet lock + the transaction that encloses it, shared by both session services (di/MeshModule).
        val ratchetMutex = Mutex()
        val transactor =
            object : SessionTransactor {
                override suspend fun <T> transact(block: suspend () -> T): T = db.withWriteTransaction { block() }
            }
        manager =
            MeshManager(
                transport = transport,
                messages = messages,
                receipts = receipts,
                groups = groups,
                reactions = reactions,
                peers = peers,
                metPeers = MetPeerRepository(db.metPeerDao(), db),
                identity = identity,
                settings = settings,
                blobs = blobs,
                imageScreening = imageScreening,
                blobStore = blobStore,
                forwardStore = forwardStore,
                notifier = notifier,
                textModeration = allowAll,
                messageCrypto = messageCrypto,
                ratchet =
                    RatchetSessions(
                        store = RatchetRepository(db.ratchetDao()),
                        dhIdentityPriv = keyStore::dhIdentityPrivate,
                        spkPrivFor = keyStore::prekeyPrivFor,
                        mutex = ratchetMutex,
                        transact = transactor,
                    ),
                groupRatchet = GroupRatchetSessions(store = groupRatchetStore, mutex = ratchetMutex, transact = transactor),
                groupRoots = groupRoots,
                scope = scope,
                metrics = metrics,
                ledger = ledger,
                db = db,
                tickDebounceMs = MeshLab.TICK_DEBOUNCE_MS,
                ingressBudget = IngressBudget(burst = limits.ingressBurst, perMinute = limits.ingressPerMinute),
            )
        manager.start()
        // The session's collectors subscribe asynchronously; a frame sent before that is emitted into nobody.
        // The startup seed of our own profile into custody is asynchronous too, and the lab has no cue plane:
        // a custody row that lands after a link's first digest exchange is not offered again until the 60 s
        // re-offer, so a scenario that links straight after boot would sometimes leave one node's seed
        // unconverged inside the oracle's window. Wait for the seed, so every link starts from a settled store.
        withContext(Dispatchers.Default) {
            withTimeout(MeshLab.AWAIT_MS) {
                while (!transport.collecting) delay(1)
                while (custodyIds().none { it.startsWith("profile-$nodeId-") }) delay(1)
            }
        }
    }

    /**
     * Process death and relaunch: the live stack goes, the identity, database and settings stay. The links
     * go too, and the peers get [MeshLab.SETTLE_MS] to notice before the node is back (see [MeshLab.unlink]).
     */
    suspend fun restart() {
        shutdownLive()
        withContext(Dispatchers.Default) { delay(MeshLab.SETTLE_MS) }
        boot()
    }

    internal fun shutdown() {
        shutdownLive()
        settingsScope.cancel()
        db.close()
    }

    private fun shutdownLive() {
        transport.disconnectAll()
        // stop() banks the ledger on the app scope, which the next line cancels; in the lab the "app" scope is
        // this session's, so bank it here first — the process-death the restart models is the orderly kind.
        runBlocking { ledger.flush() }
        manager.stop()
        scope?.cancel()
        scope = null
    }

    // --- what a user does ---

    suspend fun setDisplayName(value: String) {
        settings.setDisplayName(value)
    }

    /** Posts in the Nearby room; the frame the app's composer would send. */
    suspend fun sendRoom(text: String): Boolean = manager.sendChat(text = text)

    /** Runs the local-storage sweep the 10-minute prune loop runs, now. */
    suspend fun sweepLocalStorage() = manager.sweepLocalStorage()

    /** Sends a DM; the frame the app's composer would send. */
    suspend fun sendDm(
        to: LabNode,
        text: String,
    ): Boolean = manager.sendChat(text = text, recipientId = to.nodeId)

    /**
     * Creates a group with [others], the way `ContactsViewModel.createGroup` does (mirrored here because the
     * ViewModel needs a Main dispatcher; the body is the same three writes). Returns the group id — which is
     * the hash of the member set, so the same people are always the same group.
     */
    suspend fun createGroup(vararg others: LabNode): String {
        val me = nodeId
        val members = (others.map { it.nodeId } + me).distinct()
        val groupId = Conversations.groupIdFor(members)
        val existing = groups.find(groupId)
        if (existing != null && !existing.left) return groupId
        val createdAt = System.currentTimeMillis()
        groups.upsert(
            GroupEntity(
                groupId = groupId,
                name = "",
                members = GroupMembersStore.encode(members),
                createdBy = me,
                createdAt = createdAt,
                nameUpdatedAt = 0L,
                left = false,
            ),
        )
        messages.save(StatusNotices.groupCreated(groupId, me, createdAt))
        manager.mintGroupRoots()
        return groupId
    }

    /** Sends into a group this node holds; the frame the chat screen's composer would send. */
    suspend fun sendGroup(
        groupId: String,
        text: String,
    ): Boolean {
        val group = checkNotNull(groups.find(groupId)) { "$name holds no group $groupId" }
        return manager.sendChat(text = text, group = group.toGroupInfo())
    }

    // --- what a test reads back ---

    /** The ordinary (decrypted, non-notice) messages this node holds in [conversationId], as (id, body). */
    suspend fun decrypted(conversationId: String): Set<Pair<String, String>> =
        messages
            .observeNewestMessages(conversationId, MeshLab.WINDOW)
            .first()
            .filter { it.kind == MessageEntity.KIND_NORMAL && !it.pendingKey }
            .map { it.id to it.body }
            .toSet()

    /**
     * For every ordinary message this node authored in [conversationId]: the members of [among] (other than
     * itself) whose delivery receipt has not reached it, keyed by body. Empty when every tick has landed.
     */
    suspend fun missingAcks(
        conversationId: String,
        among: List<LabNode>,
    ): Map<String, List<String>> {
        val others = among.filter { it !== this }
        return messages
            .observeNewestMessages(conversationId, MeshLab.WINDOW)
            .first()
            .filter { it.kind == MessageEntity.KIND_NORMAL && it.senderId == nodeId }
            .associate { m ->
                val ackers =
                    receipts
                        .observeForMessage(m.id)
                        .first()
                        .map { it.ackerNodeId }
                        .toSet()
                m.body to others.filter { it.nodeId !in ackers }.map { it.name }
            }.filterValues { it.isNotEmpty() }
    }

    /** The ordinary Nearby-room posts this node holds, keyed by author node id → bodies. */
    suspend fun roomPosts(): Map<String, Set<String>> =
        messages
            .observeNewestMessages(Conversations.NEARBY, MeshLab.WINDOW)
            .first()
            .filter { it.kind == MessageEntity.KIND_NORMAL }
            .groupBy({ it.senderId }, { it.body })
            .mapValues { it.value.toSet() }

    /** How many inbound frames this node refused for [reason] this session. */
    fun drops(reason: DropReason): Long = metrics.snapshot().dropsByReason[reason] ?: 0L

    /** The custody store's live id set, as the digest exchange advertises it. */
    suspend fun custodyIds(): Set<String> = forwardStore.liveIds(System.currentTimeMillis()).toSet()

    /** `liveFingerprint`: the digest recomputed over the live rows — what two converged stores share. */
    suspend fun custodyFingerprint(): Long = StoreDigest.fingerprint(custodyIds())

    /** Whether this node has pinned [peer]'s key (its profile arrived). */
    suspend fun knows(peer: LabNode): Boolean = peers.find(peer.nodeId)?.pubKey != null

    /** How many distinct phones this node has been in range of — the met-peers table's count. */
    suspend fun peopleMet(): Int = db.metPeerDao().count()

    /** The lifetime contribution numbers as the Your mesh screen would read them (persisted + unflushed). */
    suspend fun contributions(): ContributionTotals = ledger.totals.first()

    /** The router counters that explain a frame that never arrived: delivered / relayed / deduped / suppressed. */
    fun metricsLine(): String =
        metrics.snapshot().let {
            "  $name: originated=${it.framesOriginated} delivered=${it.framesDelivered} relayed=${it.framesRelayed} " +
                "deduped=${it.framesDeduped} suppressed=${it.framesSuppressed} drops=${it.dropsByReason} " +
                "seedsSent=${it.groupSeedsSent} seedsAdopted=${it.groupSeedsAdopted} seedsHeld=${it.groupSeedsHeld} seedsReplayed=${it.groupSeedsReplayed} keyReq=${it.groupKeyRequestsSent}"
        }

    /** The DM thread id between this node and [peer], as this node names it. */
    fun dmWith(peer: LabNode): String = Conversations.idFor(nodeId, peer.nodeId, nodeId)
}
