package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupLeaveContent
import app.getknit.knit.mesh.protocol.GroupRatchetHeader
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-process spool that models the reference daemon's observable v1 semantics: unprompted server
 * hello, SUB → one DIGEST per scope, LIST with tombstones, PULL truncated at `maxPull` with a `missing`
 * list, PUSH with content-address re-verification / tombstone refusal / oldest-first eviction, and
 * EVENT fan-out that excludes the uploader.
 *
 * The §7.3 attachment family is modelled too: presence bitmaps, first-write-wins at a position, the
 * per-scope byte quota with whole-attachment eviction, and `aget` truncation at `maxAget`. Set
 * [attachments] to false to model a v1 spool — one that advertises no attachment limits, and which the
 * client must therefore never send an attachment record to.
 *
 * It exists so the whole client plane — including the bidirectional heal loop — is exercised in plain
 * JVM tests. It is deliberately *not* a conformance oracle: `knit-spool`'s own suite is that, and the
 * byte-level record vectors are pinned by [SpoolRecordsTest] against `docs/SPOOL_PROTOCOL.md`.
 */
class FakeSpool(
    private val maxPull: Int = 64,
    private val maxFrames: Int = 400,
    private val maxBlob: Int = 65_536,
    private val powBits: Int = 0,
    // Version the server advertises; a value below SPOOL_RECORD_VERSION exercises the no-overlap close.
    private val version: Int = SPOOL_RECORD_VERSION,
    // false = a v1 spool: no attachment limits in HELLO, and any attachment record is silently skipped
    // (never answered), which is exactly the stall a conforming client must avoid provoking.
    private val attachments: Boolean = true,
    private val maxAget: Int = 32,
    private val maxAChunk: Int = 49_221,
    private val maxAttachBytes: Int = 16_777_216,
) : SpoolDialer {
    private class Attachment(
        val total: Int,
        val arrivedAt: Int,
    ) {
        val chunks = HashMap<Int, ByteArray>()
        val cids = HashMap<Int, String>()
        val bytes: Int get() = chunks.values.sumOf { it.size }
    }

    private class ScopeState {
        val live = LinkedHashMap<String, ByteArray>()
        val tombstones = LinkedHashSet<String>()
        val attachments = LinkedHashMap<String, Attachment>()
        val attachTombstones = LinkedHashSet<String>()
    }

    private val scopes = ConcurrentHashMap<String, ScopeState>()
    private val sockets = mutableListOf<FakeSocket>()

    /** Every PUSH the spool accepted, in order — the outbound-side assertion hook. */
    val pushed = mutableListOf<String>()

    /** Every blob id a PULL asked for — how a test proves the client stopped re-requesting something. */
    val pulled = mutableListOf<String>()

    /** SUB stamps the spool was handed, by scope hex — lets a test assert PoW actually rode along. */
    val stamps = ConcurrentHashMap<String, PowStamp>()

    override suspend fun dial(url: String): SpoolSocket {
        val socket = FakeSocket()
        synchronized(sockets) { sockets.add(socket) }
        socket.emit(
            SpoolCodec.encode(
                SpoolHello(
                    t = SpoolRecordType.HELLO,
                    v = version,
                    min = version,
                    limits =
                        SpoolLimits(
                            maxBlob = maxBlob,
                            maxRecord = maxBlob + 512,
                            maxScopes = 64,
                            maxPull = maxPull,
                            maxFramesCap = maxFrames,
                            maxTtlMs = 7 * 24 * 60 * 60_000L,
                            maxAttachBytes = maxAttachBytes.takeIf { attachments },
                            maxAChunk = maxAChunk.takeIf { attachments },
                            maxAget = maxAget.takeIf { attachments },
                        ),
                    powBits = powBits,
                ),
            ),
        )
        return socket
    }

    /** Plants a blob the spool folds into its digest but no member can open — the §9.3 divergence trap. */
    fun plantGarbage(
        scopeHex: String,
        data: ByteArray,
    ): String {
        val id = hex(sha256(data))
        scopes.getOrPut(scopeHex) { ScopeState() }.live[id] = data
        return id
    }

    /** Every `aput` the spool accepted, as "aidHex:index" — the outbound attachment assertion hook. */
    val chunksPut = mutableListOf<String>()

    /** Every `aget` window the spool was asked for, as "aidHex:from+n" — proves the client stopped asking. */
    val chunkGets = mutableListOf<String>()

    /** Every `ahave` the spool answered, as aidHex — proves a deferral costs no round trip at all. */
    val presenceAsks = mutableListOf<String>()

    /** Records this spool was sent but does not implement; must stay empty against a v1 spool. */
    val skippedRecords = mutableListOf<String>()

    /** How many chunks the spool holds for [aidHex] — the simplest "did the upload land" assertion. */
    fun chunkCount(
        scopeHex: String,
        aidHex: String,
    ): Int =
        scopes[scopeHex]
            ?.attachments
            ?.get(aidHex)
            ?.chunks
            ?.size ?: 0

    /** Drops one chunk so a test can model a spool that only ever half-received an attachment. */
    fun dropChunk(
        scopeHex: String,
        aidHex: String,
        index: Int,
    ) {
        scopes[scopeHex]?.attachments?.get(aidHex)?.let {
            it.chunks.remove(index)
            it.cids.remove(index)
        }
    }

    /** Replaces a stored chunk with bytes no member can open — the attachment analogue of [plantGarbage]. */
    fun corruptChunk(
        scopeHex: String,
        aidHex: String,
        index: Int,
    ) {
        scopes[scopeHex]?.attachments?.get(aidHex)?.let {
            val garbage = ByteArray(120) { i -> (i * 3 + 1).toByte() }
            it.chunks[index] = garbage
            it.cids[index] = hex(sha256(garbage))
        }
    }

    fun liveIds(scopeHex: String): Set<String> =
        scopes[scopeHex]
            ?.live
            ?.keys
            ?.toSet()
            .orEmpty()

    private inner class FakeSocket : SpoolSocket {
        private val channel = Channel<ByteArray>(Channel.UNLIMITED)
        private val subscribed = mutableSetOf<String>()

        @Volatile
        private var open = true

        override val incoming: ReceiveChannel<ByteArray> get() = channel

        fun emit(bytes: ByteArray) {
            channel.trySend(bytes)
        }

        fun deliverEvent(
            scope: ByteArray,
            blobId: String,
            data: ByteArray,
        ) {
            if (hex(scope) !in subscribed) return
            emit(SpoolCodec.encode(SpoolEvent(t = SpoolRecordType.EVENT, scope = scope, blobId = unhex(blobId), data = data)))
        }

        override fun send(bytes: ByteArray): Boolean {
            if (!open) return false
            handle(bytes)
            return true
        }

        override fun close(
            code: Int,
            reason: String,
        ) {
            if (!open) return
            open = false
            synchronized(sockets) { sockets.remove(this) }
            channel.close()
        }

        @Synchronized
        private fun handle(bytes: ByteArray) {
            when (SpoolCodec.peekType(bytes)) {
                SpoolRecordType.HELLO -> {
                    Unit
                }

                // the client's answer; nothing to do
                SpoolRecordType.SUB -> {
                    SpoolCodec.decode<SpoolSub>(bytes)?.let(::onSub)
                }

                SpoolRecordType.LIST -> {
                    SpoolCodec.decode<SpoolList>(bytes)?.let(::onList)
                }

                SpoolRecordType.PULL -> {
                    SpoolCodec.decode<SpoolPull>(bytes)?.let(::onPull)
                }

                SpoolRecordType.PUSH -> {
                    SpoolCodec.decode<SpoolPush>(bytes)?.let(::onPush)
                }

                SpoolRecordType.AHAVE -> {
                    if (attachments) SpoolCodec.decode<SpoolAhave>(bytes)?.let(::onAhave) else skip(SpoolRecordType.AHAVE)
                }

                SpoolRecordType.AGET -> {
                    if (attachments) SpoolCodec.decode<SpoolAget>(bytes)?.let(::onAget) else skip(SpoolRecordType.AGET)
                }

                SpoolRecordType.APUT -> {
                    if (attachments) SpoolCodec.decode<SpoolAput>(bytes)?.let(::onAput) else skip(SpoolRecordType.APUT)
                }

                else -> {
                    Unit
                }
            }
        }

        private fun onSub(sub: SpoolSub) {
            sub.subs.forEach { entry ->
                val scopeHex = hex(entry.scope)
                entry.pow?.let { stamps[scopeHex] = it }
                subscribed.add(scopeHex)
                scopes.getOrPut(scopeHex) { ScopeState() }
                emit(SpoolCodec.encode(digestFor(entry.scope)))
            }
        }

        private fun onList(list: SpoolList) {
            val state = scopes[hex(list.scope)] ?: ScopeState()
            emit(
                SpoolCodec.encode(
                    SpoolList(
                        t = SpoolRecordType.LIST,
                        q = list.q,
                        scope = list.scope,
                        blobIds = state.live.keys.map(::unhex),
                        tombstones = state.tombstones.map(::unhex),
                    ),
                ),
            )
        }

        private fun onPull(pull: SpoolPull) {
            val state = scopes[hex(pull.scope)] ?: ScopeState()
            val missing = mutableListOf<ByteArray>()
            // The daemon truncates an overshoot rather than erroring; ids past the cap appear in neither
            // the blobs nor `missing`, so a client that overshoots must re-pull the remainder.
            pull.blobIds.take(maxPull).forEach { id ->
                pulled.add(hex(id))
                val data = state.live[hex(id)]
                if (data == null) {
                    missing.add(id)
                } else {
                    emit(SpoolCodec.encode(SpoolBlob(t = SpoolRecordType.BLOB, scope = pull.scope, blobId = id, data = data)))
                }
            }
            emit(SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = pull.q, missing = missing.ifEmpty { null })))
        }

        private fun onPush(push: SpoolPush) {
            val scopeHex = hex(push.scope)
            val state = scopes.getOrPut(scopeHex) { ScopeState() }
            val idHex = hex(push.blobId)
            val refusal =
                when {
                    !sha256(push.data).contentEquals(push.blobId) -> SpoolErrCode.BAD_ID
                    push.data.size > maxBlob -> SpoolErrCode.TOO_LARGE
                    idHex in state.tombstones -> SpoolErrCode.TOMBSTONED
                    else -> null
                }
            if (refusal != null) {
                emit(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = refusal, q = push.q, scope = push.scope)))
                return
            }
            val fresh = state.live.put(idHex, push.data) == null
            if (fresh) pushed.add(idHex)
            while (state.live.size > maxFrames) {
                val eldest = state.live.keys.first()
                state.live.remove(eldest)
                state.tombstones.add(eldest)
            }
            emit(SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = push.q)))
            // Live fan-out to every *other* subscriber; a duplicate push never re-fans.
            if (fresh) {
                synchronized(sockets) { sockets.toList() }
                    .filterNot { it === this }
                    .forEach { it.deliverEvent(push.scope, idHex, push.data) }
            }
        }

        /** A v1 spool skips an unknown record without answering — the stall a client must never provoke. */
        private fun skip(type: String) {
            skippedRecords.add(type)
        }

        private fun onAhave(ahave: SpoolAhave) {
            val state = scopes.getOrPut(hex(ahave.scope)) { ScopeState() }
            val aidHex = hex(ahave.aid)
            presenceAsks.add(aidHex)
            val held = state.attachments[aidHex]
            val dead = aidHex in state.attachTombstones
            emit(
                SpoolCodec.encode(
                    SpoolAhas(
                        t = SpoolRecordType.AHAS,
                        q = ahave.q,
                        scope = ahave.scope,
                        aid = ahave.aid,
                        total = if (dead || held == null) 0 else held.total,
                        bits = if (dead || held == null) ByteArray(0) else ScopeAttachments.bitmap(held.chunks.keys, held.total),
                        dead = dead,
                    ),
                ),
            )
        }

        private fun onAget(aget: SpoolAget) {
            chunkGets.add("${hex(aget.aid)}:${aget.from}+${aget.n}")
            val held = scopes[hex(aget.scope)]?.attachments?.get(hex(aget.aid))
            if (held != null) {
                val last = minOf(aget.from + minOf(aget.n, maxAget), held.total)
                for (index in aget.from until last) {
                    val data = held.chunks[index] ?: continue
                    emit(
                        SpoolCodec.encode(
                            SpoolAchunk(
                                t = SpoolRecordType.ACHUNK,
                                scope = aget.scope,
                                aid = aget.aid,
                                idx = index,
                                total = held.total,
                                cid = unhex(held.cids.getValue(index)),
                                data = data,
                            ),
                        ),
                    )
                }
            }
            emit(SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = aget.q)))
        }

        @Suppress("ReturnCount") // one guard per §6.5 rejection reason
        private fun onAput(aput: SpoolAput) {
            val state = scopes.getOrPut(hex(aput.scope)) { ScopeState() }
            val aidHex = hex(aput.aid)
            val cidHex = hex(aput.cid)
            val refusal =
                when {
                    !sha256(aput.data).contentEquals(aput.cid) -> SpoolErrCode.BAD_ID

                    aput.data.size > maxAChunk -> SpoolErrCode.TOO_LARGE

                    aidHex in state.attachTombstones -> SpoolErrCode.TOMBSTONED

                    state.attachments[aidHex]?.let { it.total != aput.total } == true -> SpoolErrCode.CONFLICT

                    state.attachments[aidHex]
                        ?.cids
                        ?.get(aput.idx)
                        ?.let { it != cidHex } == true -> SpoolErrCode.CONFLICT

                    else -> null
                }
            if (refusal != null) {
                emit(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = refusal, q = aput.q, scope = aput.scope)))
                return
            }
            val held = state.attachments.getOrPut(aidHex) { Attachment(aput.total, state.attachments.size) }
            if (held.chunks.put(aput.idx, aput.data) == null) chunksPut.add("$aidHex:${aput.idx}")
            held.cids[aput.idx] = cidHex
            // Whole-attachment, oldest-first eviction once the scope's byte budget is exceeded (§6.5).
            while (state.attachments.values.sumOf { it.bytes } > maxAttachBytes) {
                val victim =
                    state.attachments.entries
                        .filter { it.key != aidHex }
                        .minByOrNull { it.value.arrivedAt }
                if (victim == null) {
                    state.attachments.remove(aidHex)
                    emit(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.QUOTA, q = aput.q, scope = aput.scope)))
                    return
                }
                state.attachments.remove(victim.key)
                state.attachTombstones.add(victim.key)
            }
            emit(SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = aput.q)))
        }

        private fun digestFor(scope: ByteArray): SpoolDigest {
            val state = scopes.getOrPut(hex(scope)) { ScopeState() }
            return SpoolDigest(
                t = SpoolRecordType.DIGEST,
                scope = scope,
                digest = ScopeCrypto.digestBytes(ScopeCrypto.scopeDigest(state.live.keys.map(::unhex))),
                count = state.live.size,
                full = state.live.size >= maxFrames,
                bounds = ScopeBounds(maxFrames = maxFrames, ttlMs = ScopeRegistry.DEFAULT_TTL_MS, maxBlob = maxBlob),
            )
        }
    }
}

/** Parses the lowercase-hex display form back to bytes. Test-side inverse of [hex]. */
fun unhex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/** An in-memory [ForwardStore] with the frame-global expiry rule the real repository enforces. */
class FakeCustody(
    private val ttlMs: Long = 24 * 60 * 60_000L,
) : ForwardStore {
    private val rows = ConcurrentHashMap<String, CarriedFrame>()
    private val swept = ConcurrentHashMap.newKeySet<String>()

    /**
     * Drops [id] and refuses it from then on — the real store's answer once a frame passes its
     * frame-global expiry. Models the 24 h custody sweep while a 48 h scope still holds the blob.
     */
    fun sweep(id: String) {
        rows.remove(id)
        swept.add(id)
    }

    override suspend fun store(
        frame: CarriedFrame,
        origin: Int,
        now: Long,
    ): Boolean {
        if (frame.envelope.id in swept) return false
        if (frame.envelope.sentAt + ttlMs < now) return false
        rows.putIfAbsent(frame.envelope.id, frame)
        return true
    }

    override suspend fun liveFrames(now: Long): List<CarriedFrame> = rows.values.filter { it.envelope.sentAt + ttlMs >= now }

    override suspend fun liveIds(now: Long): List<String> = liveFrames(now).map { it.envelope.id }

    override suspend fun recipientOf(id: String): String? = rows[id]?.envelope?.recipientId

    override suspend fun has(id: String): Boolean = rows.containsKey(id)

    override suspend fun remove(id: String) {
        rows.remove(id)
    }

    override suspend fun sweepExpired(now: Long): Int = 0

    override suspend fun attachmentHashesNeedingFetch(): List<String> = emptyList()
}

/**
 * Builds a v2-sealed DM chat frame — the only shape a DM scope carries (spec §4.4). [attachmentHash]
 * fills the cleartext reference an E2E frame has carried since DB v19, which is what the attachment
 * plane keys off.
 */
fun dmFrame(
    id: String,
    from: String,
    to: String,
    sentAt: Long = 1_000L,
    body: ByteArray = byteArrayOf(1, 2, 3),
    attachmentHash: String? = null,
    attachmentMime: String? = if (attachmentHash == null) null else "image/jpeg",
): CarriedFrame {
    val enc =
        EncEnvelope(
            v = EncEnvelope.VERSION_RATCHET,
            nonce = ByteArray(12) { it.toByte() },
            ct = body,
            keys = emptyList(),
            r = RatchetHeader(se = 1, ek = ByteArray(32) { 7 }, pe = 0, n = 0),
        )
    val env =
        RelayEnvelope(
            type = FrameType.CHAT,
            id = id,
            senderId = from,
            sentAt = sentAt,
            recipientId = to,
            payload = WireCodec.encodePayload(ChatContent(enc = enc, attachmentHash = attachmentHash, attachmentMime = attachmentMime)),
        )
    val signed = WireCodec.encodeEnvelope(env)
    return CarriedFrame(envelope = env, sig = ByteArray(ScopeCrypto.SIG_BYTES) { id.hashCode().toByte() }, signed = signed)
}

/** Builds a v2 group-form chat frame — the sender-key header, no DM header (spec §4.4's group rule). */
fun groupChatFrame(
    id: String,
    from: String,
    groupId: String,
    members: List<String>,
    sentAt: Long = 1_000L,
    body: ByteArray = byteArrayOf(4, 5, 6),
    attachmentHash: String? = null,
    attachmentMime: String? = if (attachmentHash == null) null else "image/jpeg",
): CarriedFrame =
    carried(
        id,
        RelayEnvelope(
            type = FrameType.CHAT,
            id = id,
            senderId = from,
            sentAt = sentAt,
            group = GroupInfo(id = groupId, members = members, createdBy = members.first()),
            payload =
                WireCodec.encodePayload(
                    ChatContent(
                        attachmentHash = attachmentHash,
                        attachmentMime = attachmentMime,
                        enc =
                            EncEnvelope(
                                v = EncEnvelope.VERSION_RATCHET,
                                nonce = ByteArray(12) { it.toByte() },
                                ct = body,
                                keys = emptyList(),
                                g = GroupRatchetHeader(se = 1, n = 0),
                            ),
                    ),
                ),
        ),
    )

/**
 * Builds a signed `groupleave`. Deliberately mirrors `MeshManager.sendGroupLeave`: the group id rides in
 * the PAYLOAD and `RelayEnvelope.group` stays null, which is the case the group frame-set rule has to
 * read specially.
 */
fun groupLeaveFrame(
    id: String,
    from: String,
    groupId: String,
    sentAt: Long = 1_000L,
): CarriedFrame =
    carried(
        id,
        RelayEnvelope(
            type = FrameType.GROUP_LEAVE,
            id = id,
            senderId = from,
            sentAt = sentAt,
            payload = WireCodec.encodePayload(GroupLeaveContent(groupId)),
        ),
    )

/** Builds a `groupupdate` (the roster rides in `group`; no per-type content, as MeshManager sends it). */
fun groupUpdateFrame(
    id: String,
    from: String,
    groupId: String,
    members: List<String>,
    sentAt: Long = 1_000L,
    photoHash: String? = null,
): CarriedFrame =
    carried(
        id,
        RelayEnvelope(
            type = FrameType.GROUP_UPDATE,
            id = id,
            senderId = from,
            sentAt = sentAt,
            group = GroupInfo(id = groupId, members = members, createdBy = members.first(), photoHash = photoHash),
            payload = ByteArray(0),
        ),
    )

private fun carried(
    id: String,
    env: RelayEnvelope,
) = CarriedFrame(
    envelope = env,
    sig = ByteArray(ScopeCrypto.SIG_BYTES) { id.hashCode().toByte() },
    signed = WireCodec.encodeEnvelope(env),
)
