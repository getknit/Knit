package app.getknit.knit.transfer

import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.TransferRecord
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.TransferPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** One transfer as the UI sees it: the record's facts plus live progress. Never carries credentials. */
data class TransferState(
    val id: String,
    val peerId: String,
    val outgoing: Boolean,
    val name: String,
    val size: Long,
    val mime: String?,
    val phase: TransferPhase,
    val bytes: Long = 0L,
    val savedUri: String? = null,
    val reason: Int? = null,
    val expiresAt: Long = 0L,
)

/** What [TransferManager.offer] did. */
sealed interface OfferOutcome {
    data class Started(
        val id: String,
    ) : OfferOutcome

    data class Refused(
        val refusal: TransferRefusal,
    ) : OfferOutcome
}

/** Every timer in a transfer, in one place so a test can shrink them. Production uses the defaults. */
data class TransferTimings(
    val offerTtlMs: Long = 180_000L,
    val readyWaitMs: Long = 45_000L,
    val groupUpMs: Long = 15_000L,
    val hostWindowMs: Long = 90_000L,
    /**
     * How long the receiver sits on a READY before it reaches for the radio. It exists so the client does not
     * scan for a group the host has not stood up yet, and it is sized against what the host actually costs:
     * its own settle plus ~650 ms of `createGroup`. This delay and the settle are serial on the receiver, so
     * together they should land it just *after* the host is up, not seconds later.
     */
    val joinStartDelayMs: Long = 1_000L,
    val joinWindowMs: Long = 75_000L,
    val joinAttemptMs: Long = 20_000L,
    val joinRetryDelayMs: Long = 2_000L,
    /**
     * The pause between sending READY and lending the radio out to host the group.
     *
     * It exists because "sent" is not "delivered": the fast plane returns once a frame is queued, and
     * hosting pauses Wi-Fi Aware, which closes the very socket the frame is draining through. Observed on a
     * Pixel 7 — the READY (two fragments) was written at 23:38:46.377 and the Aware link went down at
     * 23:38:46.394, seventeen milliseconds later; the receiver never saw it, sat out its READY wait and both
     * ends failed on a timeout. It survived eleven runs before it lost the twelfth. Bluetooth cannot cover
     * it, since a frame the fast plane has already accepted is not re-queued for another plane.
     */
    val readyGraceMs: Long = 500L,
    val tcpConnectMs: Int = 5_000,
    val tcpConnectTries: Int = 5,
    val tcpConnectRetryMs: Long = 1_000L,
    val soTimeoutMs: Int = 30_000,
    val verdictWaitMs: Int = 20_000,
    val progressEveryMs: Long = 200L,
)

/**
 * The direct-transfer state machine — pure Kotlin, one instance for the app, JVM-tested against fakes of its
 * three seams ([DirectWifi], [TransferFiles], [TransferSignals]) and loopback sockets.
 *
 * A transfer is a sealed-DM conversation (OFFER → ACCEPT → READY, or DECLINE / CANCEL) followed by one TCP
 * stream over a one-shot Wi-Fi Direct group the sender hosts and the receiver joins by credentials. The
 * signaling rides the mesh; the bytes never do. Both sides keep a chat row ([TransferRecord]) that is
 * rewritten on every phase change and read back by the card; progress is live state only ([states]).
 *
 * Ordering that matters: READY is sent *before* the sender hosts, because hosting pauses this phone's Wi-Fi
 * Aware plane and a READY sent after it would strand on a pair with no Bluetooth link. Every terminal path
 * releases the radio ([DirectWifi.release]), and one transfer runs at a time per device.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class TransferManager(
    private val messages: MessageRepository,
    private val signals: TransferSignals,
    private val wifi: DirectWifi,
    private val files: TransferFiles,
    private val scope: CoroutineScope,
    private val selfId: suspend () -> String,
    private val peerNearby: (String) -> Boolean,
    private val timings: TransferTimings = TransferTimings(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val credentials: () -> GroupCredentials = { GroupCredentials.random() },
    private val newKey: () -> ByteArray = { ByteArray(TransferStream.KEY_BYTES).also { SecureRandom().nextBytes(it) } },
    private val newPort: () -> Int = { PORT_MIN + SecureRandom().nextInt(PORT_MAX - PORT_MIN + 1) },
    private val log: (String) -> Unit = {},
) {
    private class Live(
        @Volatile var state: TransferState,
        val offeredAt: Long,
        val sourceUri: String? = null,
    ) {
        var credentials: GroupCredentials? = null
        var key: ByteArray? = null
        var port: Int = 0
        val ready = CompletableDeferred<Unit>()
        var job: Job? = null

        @Volatile var bytes: Long = 0L

        @Volatile var lastPublishAt: Long = 0L

        @Volatile var listener: TransferListener? = null

        @Volatile var socket: Socket? = null
    }

    private class TransferTimeout : IOException("timed out")

    private class NoSpace : IOException("no space for the file")

    private val live = ConcurrentHashMap<String, Live>()
    private val mutex = Mutex()
    private val _states = MutableStateFlow<Map<String, TransferState>>(emptyMap())

    /** Every non-terminal transfer, keyed by id; a terminal one leaves the map once its row is written. */
    val states: StateFlow<Map<String, TransferState>> = _states.asStateFlow()

    @Volatile private var me: String? = null

    // ---- the sender's side ----

    /** Offers the file at [uri] to [peerId]. The peer must be nearby and carry the capability (the caller checks). */
    suspend fun offer(
        peerId: String,
        uri: String,
    ): OfferOutcome {
        val source = files.openSource(uri)
        refusalToOffer(peerId, source)?.let { return OfferOutcome.Refused(it) }
        checkNotNull(source)
        val now = clock()
        val id = FrameId.new()
        val state =
            TransferState(
                id = id,
                peerId = peerId,
                outgoing = true,
                name = source.name,
                size = source.size,
                mime = source.mime,
                phase = TransferPhase.Offered,
                expiresAt = now + timings.offerTtlMs,
            )
        val l = Live(state, offeredAt = now, sourceUri = uri)
        val admitted =
            mutex.withLock {
                if (busy()) {
                    false
                } else {
                    live[id] = l
                    publish()
                    true
                }
            }
        if (!admitted) return OfferOutcome.Refused(TransferRefusal.Busy)
        val sent =
            signals.sendTransferSignal(
                peerId,
                TransferPayload(id = id, phase = TransferPayload.PHASE_OFFER, name = source.name, size = source.size, mime = source.mime),
            )
        if (!sent) {
            mutex.withLock {
                live.remove(id)
                publish()
            }
            return OfferOutcome.Refused(TransferRefusal.NoSession)
        }
        writeRow(l)
        scheduleExpiry(l)
        log("xfer $id offered ${source.size}B '${source.name}' → $peerId")
        return OfferOutcome.Started(id)
    }

    private fun refusalToOffer(
        peerId: String,
        source: TransferSource?,
    ): TransferRefusal? =
        wifi.refusal()
            ?: when {
                !peerNearby(peerId) -> TransferRefusal.NotNearby
                source == null || source.size <= 0L -> TransferRefusal.Unreadable
                source.size > MAX_TRANSFER_BYTES -> TransferRefusal.TooLarge
                else -> null
            }

    /** Ends a transfer from this side, whatever phase it is in. */
    suspend fun cancel(id: String) {
        val l = live[id] ?: return
        val ended =
            mutex.withLock {
                if (l.state.phase.terminal) return
                transitionLocked(l, TransferPhase.Cancelled, reason = TransferPayload.REASON_USER)
                true
            }
        if (ended) {
            abort(l)
            signals.sendTransferSignal(
                l.state.peerId,
                TransferPayload(id = id, phase = TransferPayload.PHASE_CANCEL, reason = TransferPayload.REASON_USER),
            )
        }
    }

    // ---- the receiver's side ----

    /** Accepts an incoming offer; null when the transfer is under way, else why it could not be. */
    suspend fun accept(id: String): TransferRefusal? {
        val l = live[id] ?: return TransferRefusal.Gone
        val refusal = wifi.refusal() ?: if (files.freeBytes() < l.state.size) TransferRefusal.NoSpace else null
        if (refusal != null) return refusal
        val opened =
            mutex.withLock {
                val open = l.isOpenIncomingOffer(clock())
                if (open) transitionLocked(l, TransferPhase.Connecting)
                open
            }
        if (!opened) return TransferRefusal.Gone
        val sent = signals.sendTransferSignal(l.state.peerId, TransferPayload(id = id, phase = TransferPayload.PHASE_ACCEPT))
        return if (sent) {
            l.job = scope.launch { joinAndReceive(l) }
            null
        } else {
            fail(l, TransferPayload.REASON_CONNECTION, tellPeer = false)
            TransferRefusal.NoSession
        }
    }

    /** Turns an incoming offer down. */
    suspend fun decline(id: String) {
        val l = live[id] ?: return
        val declined =
            mutex.withLock {
                if (!l.isOpenIncomingOffer(clock())) return
                transitionLocked(l, TransferPhase.Declined, reason = TransferPayload.REASON_USER)
                true
            }
        if (declined) {
            signals.sendTransferSignal(
                l.state.peerId,
                TransferPayload(id = id, phase = TransferPayload.PHASE_DECLINE, reason = TransferPayload.REASON_USER),
            )
        }
    }

    // ---- the mesh's side ----

    /**
     * A sealed `CTL_TRANSFER` from [senderId] landed. True only for a live OFFER that was admitted — the
     * caller's cue to notify. Never blocks the inbound collector: role work is launched on [scope].
     */
    suspend fun onSignal(
        senderId: String,
        payload: TransferPayload,
        sentAt: Long,
    ): Boolean =
        when (payload.phase) {
            TransferPayload.PHASE_OFFER -> onOffer(senderId, payload, sentAt)
            TransferPayload.PHASE_ACCEPT -> onAccept(senderId, payload.id).let { false }
            TransferPayload.PHASE_READY -> onReady(senderId, payload).let { false }
            TransferPayload.PHASE_DECLINE -> onDecline(senderId, payload).let { false }
            TransferPayload.PHASE_CANCEL -> onCancel(senderId, payload).let { false }
            else -> false
        }

    private suspend fun onOffer(
        senderId: String,
        payload: TransferPayload,
        sentAt: Long,
    ): Boolean {
        val name = payload.name?.takeIf { it.isNotBlank() } ?: return false
        val size = payload.size ?: return false
        if (size !in 1..MAX_TRANSFER_BYTES) return false
        val now = clock()
        if (now - sentAt > STALE_OFFER_MS) return false // a custody replay of an offer nobody can still host
        val l =
            Live(
                TransferState(
                    id = payload.id,
                    peerId = senderId,
                    outgoing = false,
                    name = name,
                    size = size,
                    mime = payload.mime,
                    phase = TransferPhase.Offered,
                    expiresAt = now + timings.offerTtlMs,
                ),
                offeredAt = minOf(sentAt, now),
            )
        val verdict =
            mutex.withLock {
                when {
                    live.containsKey(payload.id) -> {
                        null
                    }

                    busy() -> {
                        false
                    }

                    else -> {
                        live[payload.id] = l
                        publish()
                        true
                    }
                }
            }
        when (verdict) {
            null -> {
                return false
            }

            false -> {
                signals.sendTransferSignal(
                    senderId,
                    TransferPayload(id = payload.id, phase = TransferPayload.PHASE_DECLINE, reason = TransferPayload.REASON_BUSY),
                )
                return false
            }

            true -> {
                writeRow(l)
                scheduleExpiry(l)
                log("xfer ${payload.id} offered by $senderId: ${size}B '$name'")
                return true
            }
        }
    }

    private suspend fun onAccept(
        senderId: String,
        id: String,
    ) {
        val l = live[id]
        val ready =
            l != null &&
                mutex.withLock {
                    val ok = l.isOurOpenOffer(senderId, clock()) && peerNearby(senderId)
                    if (ok) {
                        l.credentials = credentials()
                        l.key = newKey()
                        l.port = newPort()
                        transitionLocked(l, TransferPhase.Connecting)
                    }
                    ok
                }
        if (!ready) {
            // An answer to an offer that is no longer open (expired, cancelled, a custody replay): say so at
            // once rather than leaving the peer to wait out its READY window, and never host for nobody.
            signals.sendTransferSignal(
                senderId,
                TransferPayload(id = id, phase = TransferPayload.PHASE_CANCEL, reason = TransferPayload.REASON_TIMEOUT),
            )
            return
        }
        val creds = checkNotNull(l.credentials)
        val sent =
            signals.sendTransferSignal(
                senderId,
                TransferPayload(
                    id = id,
                    phase = TransferPayload.PHASE_READY,
                    ssid = creds.ssid,
                    passphrase = creds.passphrase,
                    port = l.port,
                    key = Base64.getEncoder().encodeToString(checkNotNull(l.key)),
                ),
            )
        if (!sent) {
            fail(l, TransferPayload.REASON_CONNECTION, tellPeer = false)
            return
        }
        // Let the READY actually leave before the radio is taken away — see readyGraceMs.
        delay(timings.readyGraceMs)
        l.job = scope.launch { hostAndSend(l) }
    }

    private suspend fun onReady(
        senderId: String,
        payload: TransferPayload,
    ) {
        val l = live[payload.id] ?: return
        val key = payload.key?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        val port = payload.port ?: 0
        val credentialsOk = GroupCredentials.isValid(payload.ssid, payload.passphrase)
        val keyOk = key?.size == TransferStream.KEY_BYTES
        val valid = credentialsOk && keyOk && port in PORT_MIN_ANY..PORT_MAX
        mutex.withLock {
            val ours = !l.state.outgoing && l.state.peerId == senderId && l.state.phase == TransferPhase.Connecting
            if (!valid || !ours) return
            if (l.credentials != null) return // a repeat
            l.credentials = GroupCredentials(checkNotNull(payload.ssid), checkNotNull(payload.passphrase))
            l.key = key
            l.port = port
        }
        l.ready.complete(Unit)
    }

    private suspend fun onDecline(
        senderId: String,
        payload: TransferPayload,
    ) {
        val l = live[payload.id] ?: return
        mutex.withLock {
            if (!l.isOurOpenOffer(senderId, Long.MIN_VALUE)) return
            transitionLocked(l, TransferPhase.Declined, reason = payload.reason ?: TransferPayload.REASON_USER)
        }
    }

    private suspend fun onCancel(
        senderId: String,
        payload: TransferPayload,
    ) {
        val l = live[payload.id] ?: return
        val ended =
            mutex.withLock {
                if (l.state.peerId != senderId || l.state.phase.terminal) return
                // A deliberate cancel is one thing; a peer reporting that its side failed is a failure here too.
                val reason = payload.reason ?: TransferPayload.REASON_USER
                val phase = if (reason == TransferPayload.REASON_USER) TransferPhase.Cancelled else TransferPhase.Failed
                transitionLocked(l, phase, reason = reason)
                true
            }
        if (ended) abort(l)
    }

    // ---- the two roles ----

    private suspend fun hostAndSend(l: Live) {
        var failure: Int? = null
        try {
            withContext(io) {
                val group = wifi.host(checkNotNull(l.credentials), timings.groupUpMs)
                log(
                    "xfer ${l.state.id} hosting ${l.credentials?.ssid} " +
                        "on ${group.addresses.joinToString { it.address.hostAddress.orEmpty() }} freq=${group.frequencyMhz}",
                )
                val listener =
                    TransferListener(group, l.port, io, clock, timings.hostWindowMs, timings.soTimeoutMs) {
                        log("xfer ${l.state.id} $it")
                    }
                l.listener = listener
                listener.bind(SOCKET_BUFFER_BYTES)
                val socket = listener.accept(checkNotNull(l.key), l.state.id) ?: throw TransferTimeout()
                l.socket = socket
                transition(l, TransferPhase.Transferring)
                val source = files.openSource(checkNotNull(l.sourceUri)) ?: throw IOException("source is gone")
                val startedAt = clock()
                source.open().use { input ->
                    TransferStream.send(socket.getOutputStream(), input, l.state.id, l.state.size, checkNotNull(l.key), progress(l))
                }
                socket.soTimeout = timings.verdictWaitMs
                val verdict = TransferStream.readVerdict(socket.getInputStream())
                if (verdict == true) {
                    log("xfer ${l.state.id} done bytes=${l.state.size} ms=${clock() - startedAt}")
                    transition(l, TransferPhase.Done)
                } else {
                    failure = TransferPayload.REASON_CORRUPT
                }
            }
        } catch (_: TransferTimeout) {
            failure = TransferPayload.REASON_TIMEOUT
        } catch (e: DirectWifiException) {
            log("xfer ${l.state.id} host failed: ${e.message}")
            failure = reasonFor(e)
        } catch (e: IOException) {
            log("xfer ${l.state.id} send failed: $e")
            failure = TransferPayload.REASON_CONNECTION
        } finally {
            finish(l, failure)
        }
    }

    private suspend fun joinAndReceive(l: Live) {
        var failure: Int? = null
        try {
            withTimeoutOrNull(timings.readyWaitMs) { l.ready.await() } ?: throw TransferTimeout()
            delay(timings.joinStartDelayMs)
            withContext(io) {
                val group = joinWithRetries(l)
                val socket = connectWithRetries(group, l.port).also { l.socket = it }
                socket.soTimeout = timings.soTimeoutMs
                socket.getOutputStream().apply {
                    write(TransferStream.clientProof(checkNotNull(l.key), l.state.id))
                    flush()
                }
                val sink = createSinkOrFail(l)
                transition(l, TransferPhase.Transferring)
                val ok = receiveOrDiscard(l, socket.getInputStream(), sink)
                TransferStream.writeVerdict(socket.getOutputStream(), ok)
                if (ok) {
                    sink.commit()
                    log("xfer ${l.state.id} received bytes=${l.state.size} → ${sink.uri}")
                    transition(l, TransferPhase.Done, savedUri = sink.uri)
                } else {
                    sink.discard()
                    failure = TransferPayload.REASON_CORRUPT
                }
            }
        } catch (_: TransferTimeout) {
            failure = TransferPayload.REASON_TIMEOUT
        } catch (_: NoSpace) {
            failure = TransferPayload.REASON_NO_SPACE
        } catch (e: DirectWifiException) {
            log("xfer ${l.state.id} join failed: ${e.message}")
            failure = reasonFor(e)
        } catch (e: IOException) {
            log("xfer ${l.state.id} receive failed: $e")
            failure = TransferPayload.REASON_CONNECTION
        } finally {
            finish(l, failure)
        }
    }

    /** A radio refusal the user can act on keeps its own outcome code; everything else is a failed link. */
    private fun reasonFor(e: DirectWifiException): Int =
        if (e.refusal == TransferRefusal.Background) TransferPayload.REASON_FOREGROUND else TransferPayload.REASON_JOIN_FAILED

    private suspend fun createSinkOrFail(l: Live): TransferSink =
        files.createSink(l.state.name, l.state.mime, l.state.size) ?: throw NoSpace()

    /** The stream into [sink]; whatever stops it short throws on, after the half-written file is thrown away. */
    private suspend fun receiveOrDiscard(
        l: Live,
        input: InputStream,
        sink: TransferSink,
    ): Boolean =
        try {
            TransferStream.receive(input, sink.stream(), l.state.id, l.state.size, checkNotNull(l.key), progress(l))
        } catch (e: IOException) {
            sink.discard()
            throw e
        }

    private suspend fun joinWithRetries(l: Live): JoinedGroup {
        val creds = checkNotNull(l.credentials)
        val deadline = clock() + timings.joinWindowMs
        while (true) {
            try {
                return wifi.join(creds, timings.joinAttemptMs)
            } catch (e: DirectWifiException) {
                if (clock() >= deadline) throw e
                log("xfer ${l.state.id} join attempt failed (${e.message}); retrying")
                delay(timings.joinRetryDelayMs)
            }
        }
    }

    private suspend fun connectWithRetries(
        group: JoinedGroup,
        port: Int,
    ): Socket {
        var last: IOException? = null
        repeat(timings.tcpConnectTries) {
            try {
                return Socket().apply {
                    receiveBufferSize = SOCKET_BUFFER_BYTES
                    sendBufferSize = SOCKET_BUFFER_BYTES
                    connect(InetSocketAddress(group.ownerAddress, port), timings.tcpConnectMs)
                }
            } catch (e: IOException) {
                last = e
                delay(timings.tcpConnectRetryMs)
            }
        }
        throw last ?: IOException("could not connect")
    }

    // ---- bookkeeping ----

    /** The end of a role coroutine: close what is open, hand the radio back, and record a failure if there was one. */
    private suspend fun finish(
        l: Live,
        failure: Int?,
    ) {
        withContext(NonCancellable) {
            closeSockets(l)
            runCatching { wifi.release() }.onFailure { log("xfer ${l.state.id} release failed: $it") }
            failure?.let { fail(l, it, tellPeer = true) }
        }
    }

    private suspend fun fail(
        l: Live,
        reason: Int,
        tellPeer: Boolean,
    ) {
        val failed =
            mutex.withLock {
                if (l.state.phase.terminal) return
                transitionLocked(l, TransferPhase.Failed, reason = reason)
                true
            }
        if (failed && tellPeer) {
            signals.sendTransferSignal(
                l.state.peerId,
                TransferPayload(id = l.state.id, phase = TransferPayload.PHASE_CANCEL, reason = reason),
            )
        }
    }

    private fun abort(l: Live) {
        l.job?.cancel()
        closeSockets(l)
    }

    private fun closeSockets(l: Live) {
        l.listener?.close()
        l.listener = null
        runCatching { l.socket?.close() }
    }

    private fun scheduleExpiry(l: Live) {
        scope.launch {
            delay(timings.offerTtlMs)
            mutex.withLock {
                if (l.state.phase == TransferPhase.Offered) transitionLocked(l, TransferPhase.Expired)
            }
        }
    }

    private fun progress(l: Live): (Long) -> Unit =
        { bytes ->
            l.bytes = bytes
            val now = clock()
            if (now - l.lastPublishAt >= timings.progressEveryMs) {
                l.lastPublishAt = now
                publish()
            }
        }

    private suspend fun transition(
        l: Live,
        phase: TransferPhase,
        savedUri: String? = null,
    ) = mutex.withLock { transitionLocked(l, phase, savedUri = savedUri) }

    /** Under [mutex]: moves [l] to [phase], writes its row, and drops it from the live map once terminal. */
    private suspend fun transitionLocked(
        l: Live,
        phase: TransferPhase,
        reason: Int? = null,
        savedUri: String? = null,
    ) {
        l.state = l.state.copy(phase = phase, reason = reason, savedUri = savedUri ?: l.state.savedUri, bytes = l.bytes)
        writeRow(l)
        if (phase.terminal) live.remove(l.state.id)
        publish()
    }

    private suspend fun writeRow(l: Live) {
        val s = l.state
        val record =
            TransferRecord(
                id = s.id,
                outgoing = s.outgoing,
                name = s.name,
                size = s.size,
                mime = s.mime,
                phase = s.phase,
                savedUri = s.savedUri,
                reason = s.reason,
            )
        messages.save(record.toEntity(peerId = s.peerId, selfId = me(), sentAt = l.offeredAt))
    }

    private suspend fun me(): String = me ?: selfId().also { me = it }

    private fun busy(): Boolean = live.values.any { !it.state.phase.terminal }

    private fun publish() {
        _states.value = live.values.associate { it.state.id to it.state.copy(bytes = it.bytes) }
    }

    private fun Live.isOurOpenOffer(
        senderId: String,
        now: Long,
    ): Boolean = state.outgoing && state.peerId == senderId && state.phase == TransferPhase.Offered && now < state.expiresAt

    private fun Live.isOpenIncomingOffer(now: Long): Boolean =
        !state.outgoing && state.phase == TransferPhase.Offered && now < state.expiresAt

    companion object {
        /** A sanity bound on what an offer may name, not a promise about storage. */
        const val MAX_TRANSFER_BYTES = 8L shl 30

        /** An OFFER older than this on arrival is a custody replay — its sender has long stopped waiting. */
        const val STALE_OFFER_MS = 15 * 60_000L

        const val SOCKET_BUFFER_BYTES = 1 shl 20
        const val PORT_MIN = 40_000
        const val PORT_MAX = 60_000

        /** What a READY may name: any unprivileged port. */
        private const val PORT_MIN_ANY = 1024
    }
}
