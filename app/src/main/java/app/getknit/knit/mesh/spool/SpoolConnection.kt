package app.getknit.knit.mesh.spool

import kotlinx.coroutines.CompletableDeferred
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

    /** `err` — [code] is from the append-only registry; unknown codes are terminal-generic. */
    class Failed(
        val code: String,
        val retryMs: Long?,
    ) : SpoolReply

    /** The connection closed before the response arrived. */
    data object Closed : SpoolReply
}

/** What a PULL actually yielded: the blobs that arrived, and the ids the spool no longer holds. */
class PullOutcome(
    val blobs: List<SpoolBlob>,
    val missing: List<ByteArray>,
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
    private class Pending(
        val scopeHex: String?,
        // Only a PULL collects blobs: they carry no `q`, so a LIST or PUSH outstanding on the same
        // scope must not swallow them.
        val collectsBlobs: Boolean = false,
        val reply: CompletableDeferred<SpoolReply> = CompletableDeferred(),
        val blobs: MutableList<SpoolBlob> = mutableListOf(),
    )

    private val nextQ = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val subscriptions = ConcurrentHashMap.newKeySet<String>()
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

    fun isSubscribed(scopeHex: String): Boolean = scopeHex in subscriptions

    /**
     * Subscribes [subs] and declares their bounds. Deliberately **not** awaited: the spool answers with
     * one `digest` per scope (which carries no `q`) or a scoped `err`, so the digests arrive through
     * [onDigest] and a refusal through [onScopeError], which also un-marks the scope here.
     */
    fun sub(subs: List<ScopeSub>): Boolean {
        if (subs.isEmpty()) return true
        val q = nextQ.getAndIncrement()
        subs.forEach { subscriptions.add(hex(it.scope)) }
        val sent = send(SpoolCodec.encode(SpoolSub(t = SpoolRecordType.SUB, q = q, subs = subs)))
        if (!sent) subs.forEach { subscriptions.remove(hex(it.scope)) }
        return sent
    }

    /** Requests the id exchange behind a digest mismatch. Null when the connection died under it. */
    suspend fun list(scope: ByteArray): SpoolReply.Listing? {
        val reply = request(hex(scope)) { q -> SpoolCodec.encode(SpoolList(t = SpoolRecordType.LIST, q = q, scope = scope)) }
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
        var pendingEntry: Pending? = null
        val reply =
            request(hex(scope), collectsBlobs = true, register = { pendingEntry = it }) { q ->
                SpoolCodec.encode(SpoolPull(t = SpoolRecordType.PULL, q = q, scope = scope, blobIds = blobIds))
            }
        val ok = reply as? SpoolReply.Ok ?: return null
        return PullOutcome(blobs = pendingEntry?.blobs.orEmpty().toList(), missing = ok.missing)
    }

    /** Stores one sealed blob. Returns the terminal reply so the caller can react to `tombstoned`/`rate`. */
    suspend fun push(
        scope: ByteArray,
        blobId: ByteArray,
        data: ByteArray,
        pow: PowStamp? = null,
    ): SpoolReply =
        request(hex(scope)) { q ->
            SpoolCodec.encode(
                SpoolPush(t = SpoolRecordType.PUSH, q = q, scope = scope, blobId = blobId, data = data, pow = pow),
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
        limits = hello.limits
        powBits = hello.powBits ?: 0
        negotiated = true
        val sent = send(SpoolCodec.encode(SpoolHello(t = SpoolRecordType.HELLO, v = SPOOL_RECORD_VERSION)))
        handshake.complete(sent)
    }

    private fun onBlob(blob: SpoolBlob) {
        // BLOB carries no `q`; it belongs to the one in-flight PULL for its scope.
        val scopeHex = hex(blob.scope)
        pending.values
            .firstOrNull { it.collectsBlobs && it.scopeHex == scopeHex }
            ?.blobs
            ?.add(blob)
    }

    private fun onListing(listing: SpoolList) {
        pending.remove(listing.q)?.reply?.complete(
            SpoolReply.Listing(listing.blobIds.orEmpty(), listing.tombstones.orEmpty()),
        )
    }

    private fun onOk(ok: SpoolOk) {
        pending.remove(ok.q)?.reply?.complete(SpoolReply.Ok(ok.missing.orEmpty()))
    }

    private suspend fun onErr(err: SpoolErr) {
        val entry = err.q?.let { pending.remove(it) }
        if (entry != null) {
            entry.reply.complete(SpoolReply.Failed(err.code, err.retryMs))
            return
        }
        // No correlation: a scoped SUB refusal (pow/quota) or a connection-scoped fault. A refused
        // scope is not subscribed, whatever we optimistically recorded when the SUB went out.
        val scopeHex = err.scope?.let(::hex)
        if (scopeHex != null) subscriptions.remove(scopeHex)
        onScopeError(scopeHex, err.code, err.retryMs)
    }

    private suspend fun request(
        scopeHex: String?,
        collectsBlobs: Boolean = false,
        register: (Pending) -> Unit = {},
        encode: (Long) -> ByteArray,
    ): SpoolReply {
        val q = nextQ.getAndIncrement()
        val entry = Pending(scopeHex, collectsBlobs)
        register(entry)
        pending[q] = entry
        if (!send(encode(q))) {
            pending.remove(q)
            return SpoolReply.Closed
        }
        return entry.reply.await()
    }

    private fun send(bytes: ByteArray): Boolean {
        if (closed) return false
        val cap = limits?.maxRecord
        if (cap != null && bytes.size > cap) return false // never hand the spool a record it must refuse
        return link.send(bytes)
    }
}
