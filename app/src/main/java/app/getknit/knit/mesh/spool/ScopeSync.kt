package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.crypto.scope.SpoolPow
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The local content-addressed blob store, as much of it as the Internet plane needs (spec §9.5).
 * Implemented over `BlobRepository`; kept as a seam so [ScopeSync] stays Android-free and testable.
 */
interface ScopeBlobs {
    suspend fun has(aHash: String): Boolean

    suspend fun bytes(aHash: String): ByteArray?

    suspend fun save(
        aHash: String,
        mime: String,
        bytes: ByteArray,
    )
}

/** Opens WebSocket sessions to spools. The one seam the OkHttp adapter implements. */
interface SpoolDialer {
    /** Connects to [url], or null when the socket could not be opened. */
    suspend fun dial(url: String): SpoolSocket?
}

/** An open spool socket: a [SpoolLink] to write to plus its inbound stream, closed when the socket dies. */
interface SpoolSocket : SpoolLink {
    val incoming: ReceiveChannel<ByteArray>

    /**
     * Why the socket ended, once [incoming] is closed — a protocol close code (`4001 auth`), a transport
     * failure, or null while it is still open. Without this the most common field failures are invisible:
     * a spool that rejects our token closes 4001 before saying anything, which otherwise looks exactly
     * like "not connected yet".
     */
    val closeReason: String? get() = null
}

/**
 * One scope's convergence state at one spool: the two digests that must agree, plus the quarantine size.
 * [label] is what the scope is *of* — a DM peer's node id or a group id.
 *
 * [converged] is plain digest equality, so read it together with [retiring]: a retiring scope is drained
 * but never refilled (spec §3.1/§3.3), so it legitimately sits at `local > spool` and reports **false**
 * for the whole drain window. That is the scope working as designed, not divergence.
 */
class ScopeStatus(
    val scopeHex: String,
    val label: String,
    val localCount: Int,
    val spoolCount: Int,
    val converged: Boolean,
    val invalidCount: Int,
    val retiring: Boolean,
)

/**
 * One spool's live state, for the Diagnostics screen and the debug bridge. [lastError] is the most
 * recent `err` code this spool answered with — the difference between "connected but idle" and
 * "connected and refusing us", which is otherwise invisible and is exactly what a field test needs
 * (`quota`, `pow` and `rate` all present as a scope that simply never converges).
 */
class SpoolStatus(
    val url: String,
    val connected: Boolean,
    val powBits: Int,
    val lastError: String?,
    val scopes: List<ScopeStatus>,
)

/**
 * The Internet plane: `docs/SPOOL_PROTOCOL.md`'s member half. A custody-plane sibling of
 * [app.getknit.knit.mesh.ForwardSync] — deliberately **not** a third `MeshTransport`, because that seam
 * is peer-addressed and radio-shaped while a scope has no neighbors (ADR 019).
 *
 * One multiplexed connection per configured spool, every scope subscribed on each, and the §9.1 heal
 * loop running per (spool, scope): compare the spool's digest against ours, LIST on mismatch, PULL what
 * we lack, PUSH what it lacks. Inbound blobs that survive §4.4 validation re-enter delivery through the
 * ordinary custody re-serve path, so flood-dedup, roster vetting, persistence, receipts and the onward
 * mesh relay are all unchanged — one Internet-connected member bridges a whole radio island in both
 * directions with zero new delivery semantics (§9.4).
 *
 * Pure: the socket, the custody store, identity, the carry-authentication gate and the delivery sink are
 * all injected, so the whole plane runs against an in-process fake spool in unit tests.
 */
@Suppress("LongParameterList") // the collaborator set is the seam list; every one is injected for testability
class ScopeSync(
    private val registry: ScopeRegistry,
    private val dialer: SpoolDialer,
    private val store: ForwardStore,
    private val selfId: suspend () -> String,
    // Configured spool URLs; empty (the plane switched off, or nothing configured) parks every worker.
    private val urls: suspend () -> List<String>,
    // The mesh's own carry gate — pinned sender key, not blocked, signature valid. Reused rather than
    // re-implemented so a spool-delivered frame is authenticated by exactly the rule the radios use.
    private val canCarry: suspend (WireEnvelope, RelayEnvelope) -> Boolean,
    // The mesh bridge: `MeshRouter.handleInbound`. Dedup, delivery, custody, and relay live behind it.
    private val deliver: suspend (WireEnvelope, RelayEnvelope, String) -> Unit,
    // The local blob store, for attachments (§4.5/§9.5). Null switches the attachment half off
    // entirely — the frame plane is unaffected, which is what keeps this additive.
    private val blobs: ScopeBlobs? = null,
    // Fired once an attachment's bytes are in hand, so screening, the message row and the UI run the
    // same path a radio pull takes. `InboundPipeline::onObtained` in the app.
    private val onAttachmentObtained: suspend (String) -> Unit = {},
    private val metrics: MeshMetrics = MeshMetrics(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val jitter: () -> Long = { Random.nextLong(RECONNECT_JITTER_MS) },
) {
    private val workers = ConcurrentHashMap<String, Worker>()
    private val sealCache = SealCache(SEAL_CACHE_MAX)

    @Volatile
    private var session: CoroutineScope? = null

    @Volatile
    private var supervisor: Job? = null

    @Volatile
    private var scopes: List<Scope> = emptyList()

    /** Starts the plane on the mesh session scope. Restart-safe, like the other services under `MeshManager`. */
    fun start(session: CoroutineScope) {
        if (supervisor?.isActive == true) return
        this.session = session
        supervisor =
            session.launch {
                while (isActive) {
                    reconcile()
                    delay(RECONCILE_INTERVAL_MS)
                }
            }
    }

    /** Stops every worker. The session scope's own cancellation stops the supervisor. */
    fun stop() {
        supervisor?.cancel()
        supervisor = null
        workers.values.forEach { it.stop() }
        workers.clear()
        scopes = emptyList()
        session = null
    }

    /**
     * Local custody changed (we originated or carried a frame): wake every worker so an eligible frame is
     * pushed now rather than at the next tick. Cheap and idempotent — the heal loop re-derives the diff.
     */
    fun onCustodyChanged() {
        workers.values.forEach { it.wake() }
    }

    fun status(): List<SpoolStatus> {
        val current = scopes
        return workers.values.map { it.status(current) }
    }

    /** Starts a worker per configured URL, stops the ones that fell out of the config, refreshes scopes. */
    private suspend fun reconcile() {
        val host = session ?: return
        val wanted = urls().toSet()
        workers.keys.filterNot { it in wanted }.forEach { workers.remove(it)?.stop() }
        if (wanted.isEmpty()) {
            scopes = emptyList()
            return
        }
        scopes = registry.scopes(clock())
        wanted.forEach { url ->
            val worker = workers.computeIfAbsent(url) { Worker(it) }
            worker.ensureRunning(host)
            worker.wake() // a scope may have appeared since this worker last subscribed
        }
    }

    /** Every custody frame that may ride [scope], keyed by the blob id it deterministically seals to. */
    private suspend fun held(
        scope: Scope,
        frames: List<CarriedFrame>,
    ): Map<String, Held> {
        val me = selfId()
        return frames
            .filter { ScopeFrames.eligibleFor(it.envelope, me, scope) }
            .associate { carried ->
                val sealed = sealCache.get(scope, carried)
                hex(sealed.blobId) to Held(sealed, carried)
            }
    }

    /** A local custody frame together with its sealed form for one scope. */
    private class Held(
        val sealed: ScopeFrames.Sealed,
        val carried: CarriedFrame,
    )

    /**
     * One spool: its connection, its per-scope digest anchors, and its own invalid set — §9.3 is
     * explicitly per-spool, so a garbage blob at one spool cannot poison the others.
     */
    @Suppress("TooManyFunctions") // one small method per protocol step; collapsing them would hide the §9 loop
    private inner class Worker(
        val url: String,
    ) {
        private val spoolDigests = ConcurrentHashMap<String, Long>()
        private val spoolCounts = ConcurrentHashMap<String, Int>()
        private val localDigests = ConcurrentHashMap<String, Long>()
        private val localCounts = ConcurrentHashMap<String, Int>()
        private val invalid = ConcurrentHashMap<String, LinkedHashSet<String>>()
        private val accepted = ConcurrentHashMap<String, LinkedHashSet<String>>()
        private val stamps = ConcurrentHashMap<String, PowStamp>()
        private val invalidAttachments = ConcurrentHashMap<String, LinkedHashSet<String>>()

        // Partially-received attachments, keyed "scopeHex|aHash". In memory by design (§9.5): the
        // plane persists nothing, and the spool's bitmap makes a restarted download cheap to resume.
        private val assemblies = ConcurrentHashMap<String, ScopeAttachments.Assembly>()
        private val wakeup = Channel<Unit>(Channel.CONFLATED)

        @Volatile
        private var job: Job? = null

        @Volatile
        private var connection: SpoolConnection? = null

        // The most recent `err` code this spool answered with, for diagnostics. Cleared on a clean
        // connect so a stale refusal doesn't outlive the condition that caused it.
        @Volatile
        private var lastError: String? = null

        fun ensureRunning(host: CoroutineScope) {
            if (job?.isActive == true) return
            job = host.launch { runLoop() }
        }

        fun stop() {
            job?.cancel()
            job = null
            connection?.close(NORMAL_CLOSE, "stopping")
            connection = null
        }

        fun wake() {
            wakeup.trySend(Unit)
        }

        fun status(all: List<Scope>): SpoolStatus =
            SpoolStatus(
                url = url,
                connected = connection != null,
                powBits = connection?.powBits ?: 0,
                lastError = lastError,
                scopes =
                    all.map { scope ->
                        ScopeStatus(
                            scopeHex = scope.idHex,
                            label = scope.label,
                            localCount = localCounts[scope.idHex] ?: 0,
                            spoolCount = spoolCounts[scope.idHex] ?: 0,
                            converged = spoolDigests[scope.idHex] == localDigests[scope.idHex],
                            invalidCount = invalid[scope.idHex]?.size ?: 0,
                            retiring = scope.retiring,
                        )
                    },
            )

        private suspend fun runLoop() {
            var backoff = MIN_BACKOFF_MS
            while (currentCoroutineContext().isActive) {
                val reached = session()
                backoff = if (reached) MIN_BACKOFF_MS else (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                delay(backoff + jitter())
            }
        }

        /** One connection lifetime. Returns whether the handshake completed — that drives the backoff. */
        private suspend fun session(): Boolean {
            val host = this@ScopeSync.session ?: return false
            val socket = dialer.dial(url)
            if (socket == null) {
                lastError = UNREACHABLE
                return false
            }
            val conn = connect(socket)
            connection = conn
            val pump = host.launch { for (bytes in socket.incoming) conn.onMessage(bytes) }
            // The socket dying before (or instead of) the spool's hello must resolve the handshake rather
            // than park this worker forever; so must a spool that opens the socket and then says nothing.
            pump.invokeOnCompletion { conn.onClosed() }
            val ready = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { conn.awaitReady() } == true
            if (ready) {
                lastError = null
                subscribe(conn, scopes)
                while (pump.isActive && currentCoroutineContext().isActive) {
                    withTimeoutOrNull(TICK_INTERVAL_MS) { wakeup.receive() }
                    healAll(conn)
                }
            }
            pump.cancel()
            conn.onClosed()
            // A close code is the only explanation we get for auth/version/abuse rejections, and the
            // handshake itself never fails "loudly" — record it before the socket is discarded.
            socket.closeReason?.let { lastError = it }
            socket.close(NORMAL_CLOSE, "done")
            connection = null
            spoolDigests.clear()
            accepted.clear()
            return ready
        }

        private fun connect(socket: SpoolSocket) =
            SpoolConnection(
                url = url,
                link = socket,
                onDigest = { handleDigest(it) },
                onEvent = { handleEvent(it) },
                onScopeError = { scopeHex, code, _ -> handleScopeError(scopeHex, code) },
            )

        private suspend fun subscribe(
            conn: SpoolConnection,
            wanted: List<Scope>,
        ) {
            if (wanted.isEmpty()) return
            conn.sub(wanted.map { ScopeSub(scope = it.id, bounds = it.bounds, pow = stampFor(it, conn.powBits)) })
        }

        private suspend fun healAll(conn: SpoolConnection) {
            val current = scopes
            // Sub-before-use is per connection, and the scope table changes as sessions are established.
            subscribe(conn, current.filterNot { conn.isSubscribed(it.idHex) })
            current.forEach { heal(conn, it) }
        }

        /** §9.1: one scope's heal round against this spool. A no-op while the two digests agree. */
        private suspend fun heal(
            conn: SpoolConnection,
            scope: Scope,
        ) {
            // One custody read serves both halves of the round: the frames, and the attachments they
            // reference. Attachments are healed even when the frame digests already agree — they are
            // outside the digest by design (§6.5), so it can never signal them.
            val frames = store.liveFrames(clock())
            val local = held(scope, frames)
            val localFold = ScopeCrypto.scopeDigest(local.values.map { it.sealed.blobId })
            localDigests[scope.idHex] = localFold
            localCounts[scope.idHex] = local.size
            val anchor = spoolDigests[scope.idHex] ?: return // the SUB hasn't been answered yet
            if (anchor == localFold) {
                healAttachments(conn, scope, frames)
                return
            }
            val listing = conn.list(scope.id) ?: return
            val quarantined = invalid[scope.idHex].orEmpty()
            val spoolIds = listing.blobIds.associateBy { hex(it) }
            val tombstoned = listing.tombstones.mapTo(mutableSetOf()) { hex(it) }
            // Skip what we already processed on this connection, not just what we still hold. The scope
            // TTL (48 h) deliberately outlives mesh custody (24 h), so a frame we delivered and then swept
            // still sits at the spool for another day: it is absent from `local` forever, our digest never
            // matches, and without this the heal round re-pulls it every tick for that whole second day —
            // silently, since `accept` short-circuits before the counters move.
            val processed = accepted[scope.idHex].orEmpty()
            val wanted =
                spoolIds.filterKeys { it !in local && it !in quarantined && it !in processed }.values.toList()
            val gone = pullMissing(conn, scope, wanted)
            val pushed = pushMissing(conn, scope, local, spoolIds.keys, tombstoned, quarantined)
            reanchor(scope, spoolIds, gone, pushed)
            healAttachments(conn, scope, frames)
        }

        /** Pulls the ids we lack, in `maxPull` batches (an overshoot is silently truncated, never an error). */
        private suspend fun pullMissing(
            conn: SpoolConnection,
            scope: Scope,
            ids: List<ByteArray>,
        ): Set<String> {
            val gone = mutableSetOf<String>()
            if (ids.isEmpty()) return gone
            val cap = (conn.limits?.maxPull ?: DEFAULT_MAX_PULL).coerceAtLeast(1)
            ids.chunked(cap).forEach { batch ->
                val outcome = conn.pull(scope.id, batch) ?: return gone
                outcome.blobs.forEach { blob -> accept(scope, blob.blobId, blob.data) }
                outcome.missing.forEach { gone.add(hex(it)) }
            }
            return gone
        }

        /** Pushes what the spool lacks, skipping tombstones, quarantine, oversize and expired frames (§9.2). */
        private suspend fun pushMissing(
            conn: SpoolConnection,
            scope: Scope,
            local: Map<String, Held>,
            spoolIds: Set<String>,
            tombstoned: Set<String>,
            quarantined: Set<String>,
        ): List<ByteArray> {
            if (scope.retiring) return emptyList() // a retiring scope is drained, never refilled
            val pushed = mutableListOf<ByteArray>()
            for (entry in pushable(conn, scope, local, spoolIds, tombstoned, quarantined)) {
                val reply = conn.push(scope.id, entry.sealed.blobId, entry.sealed.blob, stampFor(scope, conn.powBits))
                if (reply is SpoolReply.Ok) {
                    pushed.add(entry.sealed.blobId)
                    metrics.onSpoolPushed()
                } else {
                    if (reply is SpoolReply.Failed) {
                        metrics.onSpoolError()
                        lastError = reply.code
                    }
                    // A per-blob refusal is worth stepping over; anything else (quota, a dead socket) ends
                    // the round.
                    if (reply !is SpoolReply.Failed || !survivable(reply)) break
                }
            }
            return pushed
        }

        /** The frames this spool lacks and will accept: not held, not tombstoned, not quarantined, live, in size. */
        private fun pushable(
            conn: SpoolConnection,
            scope: Scope,
            local: Map<String, Held>,
            spoolIds: Set<String>,
            tombstoned: Set<String>,
            quarantined: Set<String>,
        ): List<Held> {
            val maxBlob = conn.limits?.maxBlob ?: scope.bounds.maxBlob
            val now = clock()
            return local
                .filterKeys { it !in spoolIds && it !in tombstoned && it !in quarantined }
                .values
                .filterNot { ScopeFrames.deadOnArrival(it.carried.envelope, scope.bounds.ttlMs, now) }
                .filter { it.sealed.blob.size <= maxBlob }
        }

        /**
         * Whether the rest of this round is worth attempting after a refused PUSH. A rate limit is worth
         * waiting out (the spool disconnects a persistent offender, so we spend the hint rather than the
         * strike budget); a quota refusal means the scope is full and the round is over.
         */
        private suspend fun survivable(reply: SpoolReply.Failed): Boolean =
            when (reply.code) {
                SpoolErrCode.RATE -> {
                    delay(reply.retryMs?.coerceIn(0L, MAX_RATE_WAIT_MS) ?: MAX_RATE_WAIT_MS)
                    true
                }

                SpoolErrCode.TOMBSTONED, SpoolErrCode.TOO_LARGE, SpoolErrCode.BAD_ID -> {
                    true
                }

                // per-blob, not fatal
                else -> {
                    false
                }
            }

        /**
         * Re-anchor after a heal round without a second round trip: the spool's live set, minus the ids it
         * reported gone, plus what we pushed. Exact because the digest is an XOR fold of per-id hashes —
         * and an unsolicited `digest` overwrites it the moment the spool knows better.
         */
        private fun reanchor(
            scope: Scope,
            spoolIds: Map<String, ByteArray>,
            gone: Set<String>,
            pushed: List<ByteArray>,
        ) {
            val remaining = spoolIds.filterKeys { it !in gone }.values
            var fold = ScopeCrypto.scopeDigest(remaining)
            pushed.forEach { fold = fold xor ScopeCrypto.fnv64(it) }
            spoolDigests[scope.idHex] = fold
            spoolCounts[scope.idHex] = remaining.size + pushed.size
        }

        /**
         * §9.5: fetch the attachments this scope's frames reference and we lack, and refill the ones we
         * hold and the spool lacks. Bounded per round so one 8 MiB image cannot starve every other
         * scope on this connection.
         *
         * Gated on the spool advertising the §7.3 limits. That gate is mechanical, not cosmetic: a spool
         * without attachment support *skips* an unknown record without answering, so an optimistic
         * `ahave` would sit until the 30 s request timeout — once per attachment, per scope, per round.
         */
        private suspend fun healAttachments(
            conn: SpoolConnection,
            scope: Scope,
            frames: List<CarriedFrame>,
        ) {
            val blobStore = blobs ?: return
            if (conn.limits?.attachments != true) return
            val quarantined = invalidAttachments[scope.idHex].orEmpty()
            val candidates =
                ScopeAttachments
                    .references(frames, scope, selfId())
                    .filterNot { it.aHash in quarantined }
                    .mapNotNull { ref -> ScopeAttachments.hashBytes(ref.aHash)?.let { ref to it } }
                    .take(ATTACHMENTS_PER_ROUND)
            for ((ref, aHashBytes) in candidates) {
                val aid = ScopeCrypto.attachmentId(scope.keys, scope.id, aHashBytes)
                // A dead socket ends the round; there is nothing useful to try against it.
                val presence = conn.ahave(scope.id, aid) ?: return
                if (blobStore.has(ref.aHash)) {
                    pushAttachment(conn, scope, ref, aid, aHashBytes, presence, blobStore)
                } else {
                    fetchAttachment(conn, scope, ref, aid, presence, blobStore)
                }
            }
        }

        /** Pulls the chunks we still lack and, once whole, verifies and stores the attachment. */
        @Suppress("LongParameterList") // the §9.5 step's inputs; a parameter object would only relocate them
        private suspend fun fetchAttachment(
            conn: SpoolConnection,
            scope: Scope,
            ref: ScopeAttachments.Ref,
            aid: ByteArray,
            presence: SpoolReply.Presence,
            blobStore: ScopeBlobs,
        ) {
            if (presence.dead || presence.total <= 0) return
            val key = "${scope.idHex}|${ref.aHash}"
            val assembly = assemblyFor(scope, key, ref, presence) ?: return
            if (!pullChunks(conn, scope, ref, aid, presence, assembly, key)) return
            if (!assembly.isComplete()) return
            assemblies.remove(key)
            // The decisive check: the bytes must hash to the address the frame named.
            val bytes = assembly.finish() ?: return quarantineAttachment(scope, ref.aHash)
            blobStore.save(ref.aHash, ref.mime ?: FALLBACK_MIME, bytes)
            metrics.onSpoolAttachmentPulled()
            onAttachmentObtained(ref.aHash)
        }

        /**
         * The in-flight buffer for one attachment: the existing one when it still agrees with the spool's
         * chunk count, else a fresh one. Null when the concurrency cap is reached (try again next round)
         * or the declared `total` is out of range — a member lying inside the AEAD, so quarantine.
         */
        private fun assemblyFor(
            scope: Scope,
            key: String,
            ref: ScopeAttachments.Ref,
            presence: SpoolReply.Presence,
        ): ScopeAttachments.Assembly? {
            assemblies[key]?.let { held -> if (held.total == presence.total) return held }
            if (assemblies.size >= MAX_ASSEMBLIES && !assemblies.containsKey(key)) return null
            val fresh = runCatching { ScopeAttachments.Assembly(ref.aHash, presence.total) }.getOrNull()
            if (fresh == null) quarantineAttachment(scope, ref.aHash) else assemblies[key] = fresh
            return fresh
        }

        /** Fetches the windows we still need. False means stop — a dead socket or an unusable chunk. */
        @Suppress("LongParameterList") // one hop below fetchAttachment; the same inputs, threaded once
        private suspend fun pullChunks(
            conn: SpoolConnection,
            scope: Scope,
            ref: ScopeAttachments.Ref,
            aid: ByteArray,
            presence: SpoolReply.Presence,
            assembly: ScopeAttachments.Assembly,
            key: String,
        ): Boolean {
            val maxAget = (conn.limits?.maxAget ?: DEFAULT_MAX_AGET).coerceAtLeast(1)
            val wanted = (0 until presence.total).filter { it !in assembly.held && ScopeAttachments.bitSet(presence.bits, it) }
            for (window in ScopeAttachments.windows(wanted, maxAget)) {
                val chunks = conn.aget(scope.id, aid, window.first, window.last - window.first + 1) ?: return false
                if (chunks.isEmpty()) return false // nothing served; the next round re-reads the bitmap
                for (chunk in chunks) {
                    if (!absorb(scope, ref, assembly, presence.total, chunk)) {
                        assemblies.remove(key)
                        return false
                    }
                }
            }
            return true
        }

        /** Opens one chunk and files it, or reports the failure that quarantines the whole attachment. */
        private fun absorb(
            scope: Scope,
            ref: ScopeAttachments.Ref,
            assembly: ScopeAttachments.Assembly,
            total: Int,
            chunk: SpoolAchunk,
        ): Boolean {
            val opened = runCatching { ScopeCrypto.openChunk(scope.keys, scope.id, chunk.data) }.getOrNull()
            if (opened == null || !inPosition(opened, ref, total, chunk)) {
                quarantineAttachment(scope, ref.aHash)
                return false
            }
            // `put` also refuses a duplicate, which is not a fault — only a genuinely bad chunk is.
            return assembly.put(opened.index, opened.data) || opened.index in assembly.held
        }

        /**
         * Whether a chunk's sealed header says what we asked for. The header is inside the AEAD, so this
         * catches a *member* replaying a chunk out of position — the only party able to produce one.
         */
        private fun inPosition(
            opened: ScopeCrypto.AttachChunk,
            ref: ScopeAttachments.Ref,
            total: Int,
            chunk: SpoolAchunk,
        ): Boolean = hex(opened.aHash) == ref.aHash && opened.total == total && opened.index == chunk.idx

        /** Uploads the chunks this spool's bitmap says it lacks, inside the scope's liveness window. */
        @Suppress("LongParameterList") // mirrors fetchAttachment
        private suspend fun pushAttachment(
            conn: SpoolConnection,
            scope: Scope,
            ref: ScopeAttachments.Ref,
            aid: ByteArray,
            aHashBytes: ByteArray,
            presence: SpoolReply.Presence,
            blobStore: ScopeBlobs,
        ) {
            if (!worthPushing(scope, ref, presence)) return
            val bytes = blobStore.bytes(ref.aHash) ?: return
            if (bytes.isEmpty() || bytes.size > ScopeAttachments.MAX_ATTACHMENT_BYTES) return
            val total = ScopeAttachments.chunkCount(bytes.size)
            // A spool holding a different chunk count for this id is serving another member's
            // disagreement; first write wins there, so adding ours would only collect `conflict`s.
            if (presence.total != 0 && presence.total != total) return
            val stamp = stampFor(scope, conn.powBits)
            val missing = (0 until total).filter { presence.total == 0 || !ScopeAttachments.bitSet(presence.bits, it) }
            for (index in missing) {
                val sealed =
                    ScopeCrypto.sealChunk(scope.keys, scope.id, aHashBytes, index, total, ScopeAttachments.sliceAt(bytes, index))
                if (!putChunk(conn, scope, aid, index, total, sealed, stamp)) return
            }
        }

        /** A retiring scope is drained, a dead attachment is gone, and §9.2 bars an aged-out frame's bytes. */
        private fun worthPushing(
            scope: Scope,
            ref: ScopeAttachments.Ref,
            presence: SpoolReply.Presence,
        ): Boolean = !scope.retiring && !presence.dead && ref.sentAt + scope.bounds.ttlMs > clock()

        /** Stores one sealed chunk; false means the rest of this attachment is not worth attempting. */
        @Suppress("LongParameterList") // the aput record's own field list
        private suspend fun putChunk(
            conn: SpoolConnection,
            scope: Scope,
            aid: ByteArray,
            index: Int,
            total: Int,
            sealed: ByteArray,
            stamp: PowStamp?,
        ): Boolean {
            val reply = conn.aput(scope.id, aid, index, total, ScopeCrypto.blobId(sealed), sealed, stamp)
            if (reply is SpoolReply.Ok) {
                metrics.onSpoolAttachmentPushed()
                return true
            }
            if (reply !is SpoolReply.Failed) return false
            metrics.onSpoolError()
            lastError = reply.code
            return survivable(reply)
        }

        private fun quarantineAttachment(
            scope: Scope,
            aHash: String,
        ) {
            if (remember(invalidAttachments, scope.idHex, aHash)) metrics.onSpoolInvalid()
        }

        private fun handleDigest(digest: SpoolDigest) {
            val scopeHex = hex(digest.scope)
            spoolDigests[scopeHex] = ScopeCrypto.digestValue(digest.digest)
            spoolCounts[scopeHex] = digest.count
            wake()
        }

        private suspend fun handleEvent(event: SpoolEvent) {
            val scope = scopes.firstOrNull { it.idHex == hex(event.scope) } ?: return
            if (accept(scope, event.blobId, event.data)) wake()
        }

        private fun handleScopeError(
            scopeHex: String?,
            code: String,
        ) {
            metrics.onSpoolError()
            lastError = code
            // A refused stamp is stale (day rollover, or the spool raised its difficulty): drop it so the
            // next SUB/PUSH mines a fresh one instead of replaying the rejected counter forever.
            if (scopeHex != null && code == SpoolErrCode.POW) stamps.remove(scopeHex)
        }

        /**
         * §4.4 validation, then the mesh carry gate, then §9.4's bridge. Any failure quarantines the id.
         *
         * A blob arrives twice whenever a live `event` races the heal round that was already pulling it —
         * routine, since the pull set is computed before the events land. Re-delivering is *harmless*
         * (the router's SeenSet dedups), but it would re-run the AEAD and inflate the bridged count that
         * Diagnostics presents as "messages received via relays", so an accepted id is remembered for the
         * life of the connection. The memory is per (spool, scope) and dropped on reconnect, so a custody
         * wipe still re-converges by the ordinary route.
         */
        private suspend fun accept(
            scope: Scope,
            blobId: ByteArray,
            data: ByteArray,
        ): Boolean {
            val idHex = hex(blobId)
            if (!remember(accepted, scope.idHex, idHex)) return false
            val opened = ScopeFrames.open(scope, selfId(), blobId, data)
            if (opened == null || !canCarry(opened.wire, opened.env)) {
                quarantine(scope, idHex)
                return false
            }
            metrics.onSpoolPulled()
            deliver(opened.wire, opened.env, SPOOL_SOURCE_PREFIX + url)
            metrics.onSpoolBridged()
            return true
        }

        private fun quarantine(
            scope: Scope,
            blobIdHex: String,
        ) {
            if (remember(invalid, scope.idHex, blobIdHex)) metrics.onSpoolInvalid()
        }

        /**
         * Records [blobIdHex] under [scopeHex] in a bounded, oldest-first-evicting per-scope set. Returns
         * whether it was new — false means "already known", which is the skip signal for both the invalid
         * set (§9.3: never re-pull, never re-count) and the accepted set (don't re-deliver a raced blob).
         */
        private fun remember(
            sets: ConcurrentHashMap<String, LinkedHashSet<String>>,
            scopeHex: String,
            blobIdHex: String,
        ): Boolean =
            synchronized(sets) {
                val set = sets.getOrPut(scopeHex) { LinkedHashSet() }
                while (set.size >= BLOB_SET_MAX) set.remove(set.first())
                set.add(blobIdHex)
            }

        /** A cached hashcash stamp for [scope], mined only when the spool demands one (§8). */
        private fun stampFor(
            scope: Scope,
            bits: Int,
        ): PowStamp? {
            if (bits <= 0) return null
            val day = SpoolPow.utcDay(clock())
            stamps[scope.idHex]?.let { if (it.d == day) return it }
            val n = SpoolPow.stamp(scope.id, day, bits, POW_BUDGET) ?: return null
            return PowStamp(n = n, d = day).also { stamps[scope.idHex] = it }
        }
    }

    /**
     * Memoizes the deterministic seal per (scope, frame). Sealing is the only per-frame cost in a heal
     * round, and the result never changes — that is what makes the local blob-id set derivable on demand
     * instead of persisted, so this milestone needs no `forward_store` column and no DB migration.
     */
    private class SealCache(
        private val max: Int,
    ) : LinkedHashMap<String, ScopeFrames.Sealed>(INITIAL_CACHE_CAPACITY, LOAD_FACTOR, true) {
        @Synchronized
        fun get(
            scope: Scope,
            carried: CarriedFrame,
        ): ScopeFrames.Sealed = getOrPut("${scope.idHex}|${carried.envelope.id}") { ScopeFrames.seal(scope, carried.sig, carried.signed) }

        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScopeFrames.Sealed>?): Boolean = size > max
    }

    companion object {
        /** Marks a frame as spool-sourced inbound; it names no peer, so it excludes nobody from the relay. */
        const val SPOOL_SOURCE_PREFIX = "spool:"

        private const val RECONCILE_INTERVAL_MS = 15_000L
        private const val TICK_INTERVAL_MS = 60_000L
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val RECONNECT_JITTER_MS = 750L
        private const val MAX_RATE_WAIT_MS = 5_000L
        private const val DEFAULT_MAX_PULL = 64
        private const val DEFAULT_MAX_AGET = 32

        /** Attachments touched per (spool, scope) per round — one big image must not starve the rest. */
        private const val ATTACHMENTS_PER_ROUND = 4

        /** Concurrent in-memory reassemblies per spool; each is bounded by `MAX_CHUNKS` (8 MiB). */
        private const val MAX_ASSEMBLIES = 2

        /** Used when a frame names an attachment but no mime; Coil sniffs the real format anyway. */
        private const val FALLBACK_MIME = "image/jpeg"
        private const val BLOB_SET_MAX = 512
        private const val SEAL_CACHE_MAX = 2_048
        private const val INITIAL_CACHE_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
        private const val NORMAL_CLOSE = 1000

        /** [SpoolStatus.lastError] when the socket could not be opened at all (bad URL, DNS, refused). */
        const val UNREACHABLE = "unreachable"

        /** Hashcash budget per stamp: ~67 M hashes, comfortably above the spec's 20-bit recommendation. */
        private const val POW_BUDGET = 1L shl 26
    }
}
