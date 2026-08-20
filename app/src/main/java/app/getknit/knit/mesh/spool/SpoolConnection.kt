package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.crypto.scope.SpoolPow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The byte pipe under a [SpoolConnection] — one WebSocket, already open. Kept as a seam so the whole
 * record state machine stays pure and JVM-testable, and so the only file in the app that imports
 * `okhttp3` is the adapter that implements this (see `rules/mesh.md`).
 */
interface SpoolLink {
    /** Sends one binary WebSocket message; false when the socket is already gone. */
    fun send(bytes: ByteArray): Boolean

    fun close(
        code: Int,
        reason: String,
    )
}

/** A terminal response to a client-stamped `q`, or the connection dying under it. */
sealed interface SpoolReply {
    /** The spool's LIST answer: its live ids plus the tombstones we must not re-push. */
    class Listing(
        val blobIds: List<ByteArray>,
        val tombstones: List<ByteArray>,
    ) : SpoolReply

    /** `ok` — for a PULL, [missing] are the requested ids the spool no longer holds. */
    class Ok(
        val missing: List<ByteArray>,
    ) : SpoolReply

    /** `ahas` — one attachment's presence at this spool (§7.3). */
    class Presence(
        val total: Int,
        val bits: ByteArray,
        val dead: Boolean,
    ) : SpoolReply

    /** `err` — [code] is from the append-only registry; unknown codes are terminal-generic. */
    class Failed(
        val code: String,
        val retryMs: Long?,
    ) : SpoolReply

    /** The connection closed before the response arrived. */
    data object Closed : SpoolReply
}

/**
 * What a PULL actually yielded: the blobs that arrived, the ids the spool no longer holds, and the ids
 * it answered with something unusable.
 *
 * [oversize] exists so a refusal at the record layer is still *accounted*. A blob we asked for and got a
 * junk answer for is a blob we can never hold, so if it were merely dropped it would stay in the spool's
 * digest and out of ours — permanent divergence and a re-pull every heal round, the exact failure §9.3's
 * invalid set is there to prevent. Ids we never asked for are not reported here: they were never pulled,
 * so quarantining them would hand the spool a way to evict genuine entries from a bounded set.
 */
class PullOutcome(
    val blobs: List<SpoolBlob>,
    val missing: List<ByteArray>,
    val oversize: List<ByteArray>,
)

/**
 * What an AGET yielded. [rejected] means at least one answer was outside the window we asked for or over
 * the structural chunk size — the attachment's `aid` must be quarantined per §9.5 (C-9.5-4), exactly as
 * it is when a chunk fails its AEAD.
 */
class AgetOutcome(
    val chunks: List<SpoolAchunk>,
    val rejected: Boolean,
)

/**
 * The client half of `docs/SPOOL_PROTOCOL.md` §7 over one connection: hello negotiation, the monotonic
 * per-connection `q`, request/response correlation, the per-connection subscription set, and the
 * routing of server-initiated records. Pure and transport-agnostic — it only ever touches a [SpoolLink].
 *
 * Three v1 behaviors are load-bearing and easy to get wrong, so they are handled here rather than in
 * callers: **the spool speaks first** (its unprompted hello opens the conversation and we answer with
 * nothing but the chosen version); **sub-before-use is per connection**, so a reconnect must re-`sub`
 * every scope even though the spool still holds them; and **`blob` records carry no `q`**, so they are
 * attributed to the single in-flight PULL for their scope.
 *
 * Records are consumed one at a time by [onMessage] from a single reader coroutine; requests may be
 * issued concurrently, so the correlation table and subscription set are concurrent structures.
 */
class SpoolConnection(
    val url: String,
    private val link: SpoolLink,
    private val onDigest: suspend (SpoolDigest) -> Unit,
    private val onEvent: suspend (SpoolEvent) -> Unit,
    // Errors that don't correlate to an outstanding `q` — a scoped SUB refusal (pow/quota) or a
    // connection-scoped fault. The scope is hex, or null when the error is connection-wide.
    private val onScopeError: suspend (scopeHex: String?, code: String, retryMs: Long?) -> Unit = { _, _, _ -> },
) {
    /**
     * One outstanding `q`. For the two requests answered by un-`q`'d records (`pull` → `blob`*,
     * `aget` → `achunk`*, spec B-7.1-8) it also carries **what the request named**, and that is the only
     * bound on this plane the client actually controls: a spool cannot inflate an id list we composed
     * ourselves. One slot per named id, consumed on arrival, is simultaneously the unsolicited-record
     * check, the duplicate filter and the cap on list length.
     *
     * Concurrency: the mutable state here is written only on the reader coroutine (`onMessage`) and
     * seeded on the requesting coroutine *before* the entry is published into [pending]. The terminal
     * handlers remove the entry from [pending] **before** completing [reply], so by the time the
     * requester resumes, no handler can still reach it. Reading these lists on the timeout path would
     * break that and reintroduce a data race — don't.
     */
    private class Pending(
        val scopeHex: String?,
        // The ids this PULL named, in hex. Drained by `claimBlob`, so its initial size is the hard cap
        // on [blobs] — and an entry that named none (LIST, PUSH, AHAVE, APUT) collects nothing at all,
        // which is what the old `collectsBlobs` flag said less directly.
        private val wantedBlobs: MutableSet<String> = mutableSetOf(),
        // The aid this AGET named: routing, not a bound — two attachments in one scope can legitimately
        // be in flight at once.
        private val chunksFor: String? = null,
        // The index window this AGET named, `from until from + n`. A range rather than a set, so nothing
        // is allocated on the strength of the caller's `n`.
        private val chunkWindow: IntRange = IntRange.EMPTY,
        private val filledChunks: MutableSet<Int> = mutableSetOf(),
        val reply: CompletableDeferred<SpoolReply> = CompletableDeferred(),
        val blobs: MutableList<SpoolBlob> = mutableListOf(),
        val chunks: MutableList<SpoolAchunk> = mutableListOf(),
        // Ids this PULL named that came back unusable, for §9.3 to account. Bounded by `wantedBlobs`.
        val oversize: MutableList<ByteArray> = mutableListOf(),
        var rejectedChunk: Boolean = false,
    ) {
        // The ids as named, kept whole: `wantedBlobs` is drained by claiming, so it cannot answer
        // "did this request mention that id" once a blob for it has arrived.
        private val namedBlobs: Set<String> = wantedBlobs.toSet()

        /** True at most once per id this PULL named — an unsolicited id, or a repeat, is false. */
        fun claimBlob(idHex: String): Boolean = wantedBlobs.remove(idHex)

        /** Whether this request named [idHex] at all, however its blob then fared. */
        fun wasRequested(idHex: String): Boolean = idHex in namedBlobs

        /** Whether this entry is the collector for [aidHex] at all, however the chunk then fares. */
        fun collectsChunksFor(aidHex: String): Boolean = aidHex == chunksFor

        /** Whether [idx] is one this AGET actually named. */
        fun inWindow(idx: Int): Boolean = idx in chunkWindow

        /** True at most once per index, so a repeat is dropped rather than buffered twice. */
        fun claimChunk(idx: Int): Boolean = filledChunks.add(idx)
    }

    private val nextQ = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending>()

    // Scope hex -> the bounds *we* declared for it at SUB. Kept rather than a bare id set because those
    // bounds are the one `maxBlob` on this connection the spool did not choose (see [inboundCap]).
    private val subscriptions = ConcurrentHashMap<String, ScopeBounds>()
    private val handshake = CompletableDeferred<Boolean>()

    /** The spool's advertised caps, available once [awaitReady] returns true. */
    @Volatile
    var limits: SpoolLimits? = null
        private set

    /** The spool's PoW difficulty; 0 disables it. Read after [awaitReady]. */
    @Volatile
    var powBits: Int = 0
        private set

    @Volatile
    private var negotiated = false

    @Volatile
    private var closed = false

    /** Suspends until the hello exchange settles: true when the connection is usable. */
    suspend fun awaitReady(): Boolean = handshake.await()

    fun isSubscribed(scopeHex: String): Boolean = scopeHex in subscriptions.keys

    /**
     * Subscribes [subs] and declares their bounds. Deliberately **not** awaited: the spool answers with
     * one `digest` per scope (which carries no `q`) or a scoped `err`, so the digests arrive through
     * [onDigest] and a refusal through [onScopeError], which also un-marks the scope here.
     */
    fun sub(subs: List<ScopeSub>): Boolean {
        if (subs.isEmpty()) return true
        val q = nextQ.getAndIncrement()
        subs.forEach { subscriptions[hex(it.scope)] = it.bounds }
        val sent = send(SpoolCodec.encode(SpoolSub(t = SpoolRecordType.SUB, q = q, subs = subs)))
        if (!sent) subs.forEach { subscriptions.remove(hex(it.scope)) }
        return sent
    }

    /** Requests the id exchange behind a digest mismatch. Null when the connection died under it. */
    suspend fun list(scope: ByteArray): SpoolReply.Listing? {
        val reply =
            request(Pending(hex(scope))) { q -> SpoolCodec.encode(SpoolList(t = SpoolRecordType.LIST, q = q, scope = scope)) }
        return reply as? SpoolReply.Listing
    }

    /**
     * Pulls [blobIds] (the caller batches at the spool's `maxPull`; an overshoot is silently truncated
     * and the remainder must be re-pulled). Null when the pull failed or the connection died.
     */
    suspend fun pull(
        scope: ByteArray,
        blobIds: List<ByteArray>,
    ): PullOutcome? {
        val entry = Pending(hex(scope), wantedBlobs = blobIds.mapTo(mutableSetOf(), ::hex))
        val ok =
            request(entry) { q ->
                SpoolCodec.encode(SpoolPull(t = SpoolRecordType.PULL, q = q, scope = scope, blobIds = blobIds))
            } as? SpoolReply.Ok ?: return null
        return PullOutcome(blobs = entry.blobs.toList(), missing = ok.missing, oversize = entry.oversize.toList())
    }

    /** Stores one sealed blob. Returns the terminal reply so the caller can react to `tombstoned`/`rate`. */
    suspend fun push(
        scope: ByteArray,
        blobId: ByteArray,
        data: ByteArray,
        pow: PowStamp? = null,
    ): SpoolReply =
        request(Pending(hex(scope))) { q ->
            SpoolCodec.encode(
                SpoolPush(t = SpoolRecordType.PUSH, q = q, scope = scope, blobId = blobId, data = data, pow = pow),
            )
        }

    /**
     * Asks what this spool holds for one attachment (§7.3). Callers must gate on
     * [SpoolLimits.attachments] first: a spool without attachment support skips the record silently,
     * and this would then block until the request timeout.
     */
    suspend fun ahave(
        scope: ByteArray,
        aid: ByteArray,
    ): SpoolReply.Presence? {
        val reply =
            request(Pending(hex(scope))) { q ->
                SpoolCodec.encode(SpoolAhave(t = SpoolRecordType.AHAVE, q = q, scope = scope, aid = aid))
            }
        return reply as? SpoolReply.Presence
    }

    /**
     * Fetches up to [n] chunks from [from]; an overshoot of `maxAget` is truncated, never refused.
     *
     * The window kept for filtering is the range this request *named*, not the sparse set of indices the
     * caller still lacks: a conforming spool serves every index it holds in the range, including ones
     * already in hand, and `ScopeSync.absorb` already tolerates those duplicates.
     */
    suspend fun aget(
        scope: ByteArray,
        aid: ByteArray,
        from: Int,
        n: Int,
    ): AgetOutcome? {
        val entry = Pending(hex(scope), chunksFor = hex(aid), chunkWindow = from until from + n)
        val reply =
            request(entry) { q ->
                SpoolCodec.encode(SpoolAget(t = SpoolRecordType.AGET, q = q, scope = scope, aid = aid, from = from, n = n))
            }
        if (reply !is SpoolReply.Ok) return null
        return AgetOutcome(chunks = entry.chunks.toList(), rejected = entry.rejectedChunk)
    }

    /** Stores one sealed attachment chunk. The terminal reply carries `conflict`/`quota`/`tombstoned`. */
    @Suppress("LongParameterList") // the record's own field list; a parameter object would only relocate it
    suspend fun aput(
        scope: ByteArray,
        aid: ByteArray,
        idx: Int,
        total: Int,
        cid: ByteArray,
        data: ByteArray,
        pow: PowStamp? = null,
    ): SpoolReply =
        request(Pending(hex(scope))) { q ->
            SpoolCodec.encode(
                SpoolAput(
                    t = SpoolRecordType.APUT,
                    q = q,
                    scope = scope,
                    aid = aid,
                    idx = idx,
                    total = total,
                    cid = cid,
                    data = data,
                    pow = pow,
                ),
            )
        }

    /** Consumes one inbound record. Malformed bytes are dropped — a client never closes on them. */
    @Suppress("ReturnCount") // a flat dispatch over the record registry; nesting it would read worse
    suspend fun onMessage(bytes: ByteArray) {
        when (SpoolCodec.peekType(bytes)) {
            SpoolRecordType.HELLO -> onHello(bytes)
            SpoolRecordType.DIGEST -> SpoolCodec.decode<SpoolDigest>(bytes)?.let { onDigest(it) }
            SpoolRecordType.EVENT -> SpoolCodec.decode<SpoolEvent>(bytes)?.let { onEvent(it) }
            SpoolRecordType.BLOB -> SpoolCodec.decode<SpoolBlob>(bytes)?.let(::onBlob)
            SpoolRecordType.LIST -> SpoolCodec.decode<SpoolList>(bytes)?.let(::onListing)
            SpoolRecordType.OK -> SpoolCodec.decode<SpoolOk>(bytes)?.let(::onOk)
            SpoolRecordType.AHAS -> SpoolCodec.decode<SpoolAhas>(bytes)?.let(::onAhas)
            SpoolRecordType.ACHUNK -> SpoolCodec.decode<SpoolAchunk>(bytes)?.let(::onAchunk)
            SpoolRecordType.ERR -> SpoolCodec.decode<SpoolErr>(bytes)?.let { onErr(it) }
            else -> Unit // unknown `t`: additive evolution — ignore, never close
        }
    }

    /** The socket is gone: fail every outstanding request and forget the per-connection subscriptions. */
    fun onClosed() {
        closed = true
        if (!handshake.isCompleted) handshake.complete(false)
        subscriptions.clear()
        pending.keys.toList().forEach { q -> pending.remove(q)?.reply?.complete(SpoolReply.Closed) }
    }

    fun close(
        code: Int,
        reason: String,
    ) {
        link.close(code, reason)
        onClosed()
    }

    private suspend fun onHello(bytes: ByteArray) {
        if (negotiated) return // a post-negotiation hello is in-band noise; ignore rather than tear down
        val hello = SpoolCodec.decode<SpoolHello>(bytes)
        val theirMax = hello?.v ?: 0
        val theirMin = hello?.min ?: theirMax
        if (hello == null || SPOOL_RECORD_VERSION !in theirMin..theirMax) {
            // No overlap: the spool would close 4002 the moment we answered; say so ourselves.
            close(SpoolCloseCode.VERSION, "no version overlap")
            return
        }
        limits = hello.limits?.clamped()
        // A difficulty beyond what a phone will attempt is refused outright rather than mined for: the
        // work is spent per scope on every round that re-subscribes, so believing an absurd one is a
        // remote battery denial that costs the spool a single integer.
        powBits = (hello.powBits ?: 0).coerceAtMost(SpoolPow.MAX_BITS)
        negotiated = true
        val sent = send(SpoolCodec.encode(SpoolHello(t = SpoolRecordType.HELLO, v = SPOOL_RECORD_VERSION)))
        handshake.complete(sent)
    }

    private fun onBlob(blob: SpoolBlob) {
        // BLOB carries no `q`, so it is attributed by scope *and* by whether a PULL actually named the
        // id. The claim succeeds once per named id, so an id we never asked for, a second copy of one
        // already served, and a flood of either all fall out of the same check — and `blobs` can never
        // outgrow the caller's own request list.
        val scopeHex = hex(blob.scope)
        val idHex = hex(blob.blobId)
        for (entry in pending.values) {
            if (entry.scopeHex != scopeHex || !entry.claimBlob(idHex)) continue
            // Over the bound we ourselves declared at SUB, this is not an answer to what we asked for.
            // Deliberately not `limits.maxBlob`: the spool advertised that in its own hello, so trusting
            // it would let the attacker set its own budget. Report it so §9.3 still accounts the id.
            if (blob.data.size > inboundCap(scopeHex)) entry.oversize.add(blob.blobId) else entry.blobs.add(blob)
            return
        }
    }

    private fun onAhas(ahas: SpoolAhas) {
        pending.remove(ahas.q)?.reply?.complete(
            SpoolReply.Presence(total = ahas.total, bits = ahas.bits, dead = ahas.dead),
        )
    }

    private fun onAchunk(chunk: SpoolAchunk) {
        // ACHUNK carries no `q` either; the same rule, one level finer. A sealed chunk's size is
        // structural rather than tunable (§4.5 pins it at `1 + nonce + header + 48 KiB + tag`), so
        // nothing legitimate is a byte larger and there is no advertised number to be tempted by.
        val scopeHex = hex(chunk.scope)
        val aidHex = hex(chunk.aid)
        for (entry in pending.values) {
            if (entry.scopeHex != scopeHex || !entry.collectsChunksFor(aidHex)) continue
            // Outside the window we named, or oversize: the spool answered with something other than what
            // was asked for, which §9.5 (C-9.5-4) quarantines the whole `aid` for. A merely *repeated*
            // index is not that — it is dropped, since the window already bounds how many can land.
            if (!entry.inWindow(chunk.idx) || chunk.data.size > ScopeCrypto.SEALED_CHUNK_BYTES) {
                entry.rejectedChunk = true
            } else if (entry.claimChunk(chunk.idx)) {
                entry.chunks.add(chunk)
            }
            return
        }
    }

    private fun onListing(listing: SpoolList) {
        // Ids are `bstr32` by definition (§12.1), and every one of these is hexed and held by the caller —
        // so drop the malformed ones here rather than letting an arbitrary-length byte string through.
        pending.remove(listing.q)?.reply?.complete(
            SpoolReply.Listing(listing.blobIds.orEmpty().filter(::isId), listing.tombstones.orEmpty().filter(::isId)),
        )
    }

    private fun isId(bytes: ByteArray): Boolean = bytes.size == SPOOL_ID_BYTES

    private fun onOk(ok: SpoolOk) {
        // `missing` answers *this* request, so ids it never named are not ours to act on: `ScopeSync`
        // folds them out of the spool's digest anchor, which an invented id would then corrupt.
        val entry = pending.remove(ok.q) ?: return
        entry.reply.complete(SpoolReply.Ok(ok.missing.orEmpty().filter { entry.wasRequested(hex(it)) }))
    }

    private suspend fun onErr(err: SpoolErr) {
        val entry = err.q?.let { pending.remove(it) }
        if (entry != null) {
            entry.reply.complete(SpoolReply.Failed(err.code.take(SPOOL_ERR_CODE_MAX), err.retryMs))
            return
        }
        // No correlation: a scoped SUB refusal (pow/quota) or a connection-scoped fault. A refused
        // scope is not subscribed, whatever we optimistically recorded when the SUB went out.
        val scopeHex = err.scope?.let(::hex)
        if (scopeHex != null) subscriptions.remove(scopeHex)
        onScopeError(scopeHex, err.code.take(SPOOL_ERR_CODE_MAX), err.retryMs)
    }

    private suspend fun request(
        entry: Pending,
        encode: (Long) -> ByteArray,
    ): SpoolReply {
        val q = nextQ.getAndIncrement()
        pending[q] = entry
        if (!send(encode(q))) {
            pending.remove(q)
            return SpoolReply.Closed
        }
        // A spool that accepts a record and never answers must not wedge the heal loop: time out, drop the
        // correlation, and let the next round re-derive the diff (a re-PUSH is byte-identical, a re-PULL is
        // idempotent, so nothing is lost by giving up on a response).
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { entry.reply.await() }
            ?: SpoolReply.Closed.also { pending.remove(q) }
    }

    /**
     * The largest `blob` we will hold for [scopeHex]: the `maxBlob` **we** declared for it at SUB, which
     * is also the size `ScopeSync.pushable` refuses to exceed on the way out. What we will not send, we
     * will not accept — and unlike `limits.maxBlob`, it is not a number the spool chose.
     */
    private fun inboundCap(scopeHex: String): Int = subscriptions[scopeHex]?.maxBlob ?: ScopeRegistry.DEFAULT_MAX_BLOB

    private fun send(bytes: ByteArray): Boolean {
        if (closed) return false
        val cap = limits?.maxRecord
        if (cap != null && bytes.size > cap) return false // never hand the spool a record it must refuse
        return link.send(bytes)
    }

    private companion object {
        /** How long a `q`-correlated request waits for its terminal `ok`/`err`/`list` before giving up. */
        const val REQUEST_TIMEOUT_MS = 30_000L

        /**
         * A `pull` batch's ceiling. Every value here is one the *spool* advertised, so treating them as
         * hints rather than facts is the whole lesson of this file: at 256 ids of [ScopeRegistry
         * .DEFAULT_MAX_BLOB] each, a pull's worst-case buffer is 16 MiB, and the spec only suggests 64.
         */
        const val MAX_PULL_CEILING = 256

        /** Two maximal attachments' worth — well past any honest `maxAttachBytes` (§12.2 suggests 16 MiB). */
        const val MAX_ATTACH_BYTES_CEILING = 4 * ScopeAttachments.MAX_ATTACHMENT_BYTES

        /**
         * HELLO limits are the spool's own claim about itself, taken verbatim until now — a hostile one
         * could advertise `Int.MAX_VALUE` and set its own budget for every check written against them.
         * Clamping on ingest is the one place that says *these numbers are a hint*.
         *
         * Only the upper end matters. A spool that declares an unusably *small* cap needs no defense: our
         * own hello reply then fails [send], the handshake completes false, and the worker backs off.
         */
        fun SpoolLimits.clamped() =
            SpoolLimits(
                maxBlob = maxBlob.coerceAtMost(MAX_INBOUND_RECORD),
                maxRecord = maxRecord.coerceAtMost(MAX_INBOUND_RECORD),
                maxScopes = maxScopes,
                maxPull = maxPull.coerceIn(1, MAX_PULL_CEILING),
                maxFramesCap = maxFramesCap,
                maxTtlMs = maxTtlMs,
                // Null-preserving: `attachments` is all-three-or-none, and collapsing one to a default
                // would silently claim a v1 spool speaks §7.3.
                maxAttachBytes = maxAttachBytes?.coerceAtMost(MAX_ATTACH_BYTES_CEILING),
                maxAChunk = maxAChunk?.coerceAtMost(ScopeCrypto.SEALED_CHUNK_BYTES),
                maxAget = maxAget?.coerceIn(1, ScopeAttachments.MAX_CHUNKS),
            )
    }
}
