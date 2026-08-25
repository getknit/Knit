package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.link.FragReassembler
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A [MeshTransport] that carries the mesh's small floodable frames over LoRa via a Meshtastic board
 * ([MeshtasticLink]), extending the reach of the **Nearby room** beyond BLE/NAN range. It is a
 * fast-plane-ONLY child of [app.getknit.knit.mesh.CompositeMeshTransport] (added last, lowest preference):
 *
 * - [neighbors] is always empty, so the reliable flood, custody digest sync, key requests, blob pulls and
 *   the `watchNeighbors` hooks never touch a ~1 kbps link — [send]/[sendFile]/[sendDigest] are no-ops.
 * - [fastFanout]/[longRangeFanout]/[fastSend] are the only outbound paths: they decode the envelope (never
 *   re-encoding it — `sig`/`signed` pass through [FastFrameCodec] byte-exact), apply [LoraFramePolicy],
 *   compact/fragment via [LoraFrameCodec], and pace the result ([LoraPacePolicy]) onto the board. The
 *   long-range path is what carries sealed DM-form chat (ADR 039); this plane is the only one it exists for.
 * - inbound packets are decoded/reassembled and injected into [inbound] exactly like the Wi-Fi Aware fast
 *   plane's `emitFastWire`, so the router's dedup/verify/custody/relay all run unchanged.
 * - [shortRange] is false: a LoRa sighting doesn't imply proximity, so siblings ignore its `reachable` set.
 *
 * On first hearing a peer the transport also re-offers the carried DM-form frames addressed to it
 * ([reofferTo]) — the plane's only backfill, since custody's digest sync needs a data path.
 *
 * Key bootstrap over LoRa (the far side has never seen the author's profile) rides two paths: the mesh's
 * existing `watchReachable` reflood, plus a self-profile beacon this transport sends on session-up (under a
 * 5-min floor) and on first hearing a peer (under a 60-s gap, so a two-sided bootstrap completes without a
 * periodic beacon — [beaconProfile]). [clock] is monotonic (pacing, dedup, linger); [wallClock] is the
 * epoch clock a frame's `sentAt` is stamped in, read only by the freshness gate. Pure/Android-free — the
 * only `android.bluetooth.*` sits behind the [MeshtasticLink]/[MeshtasticGattDialer] seam.
 */
@Suppress("TooManyFunctions", "LongParameterList")
internal class LoraMeshTransport(
    private val selfId: suspend () -> String,
    private val link: MeshtasticLink,
    private val config: Flow<LoraConfig?>,
    private val selfProfile: suspend () -> WireEnvelope?,
    private val farFrames: suspend (nodeId: String) -> List<WireEnvelope> = { emptyList() },
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val pace: LoraPacePolicy = LoraPacePolicy(),
) : MeshTransport,
    LoraPlaneStatus {
    override val kind = TransportKind.LoRa
    override val hasFastPlane = true
    override val shortRange = false

    private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
    override val neighbors = _neighbors.asStateFlow() // always empty: no data-path links over LoRa

    private val _reachable = MutableStateFlow<Set<Peer>>(emptySet())
    override val reachable = _reachable.asStateFlow()

    private val _health = MutableStateFlow(TransportHealth.Unavailable)
    override val health = _health.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = INBOUND_BUFFER)
    override val inbound = _inbound.asSharedFlow()

    override val incomingFiles: Flow<ReceivedFile> = emptyFlow() // LoRa carries no files

    private val _status = MutableStateFlow(LoraStatus())

    /** A snapshot for the LoRa settings row + the `…debug.LORA` bridge; derived, never routing-affecting. */
    override val status = _status.asStateFlow()

    // Coordination-plane dedup keyed on the first 8 bytes of the Ed25519 sig: records every frame we send
    // OR receive over LoRa, so (a) a frame heard over LoRa is not re-fanned back over it (the composite
    // re-calls fastFanout on relay), and (b) AckSync's verbatim 24 h tick retries are dropped inside the
    // receiver's own SeenSet window.
    private val sigSeen = SeenSet(ttlMillis = SIG_TTL_MS, clock = clock)
    private val fragSeq = AtomicInteger()
    private val reassembler = FragReassembler<UInt>(now = clock, capacity = FRAG_CAP, timeoutMs = FRAG_TIMEOUT_MS)

    // LoRa-heard senders (a long linger — there are no periodic cues on LoRa), and the profile-beacon floor.
    private val lastHeardAt = ConcurrentHashMap<String, Long>()
    private val heardPeers = ConcurrentHashMap<String, Peer>()
    private val lastSelfProfileAt = AtomicLong(NEVER)

    @Volatile
    private var foreignReachable: Set<String> = emptySet()

    @Volatile
    private var currentConfig: LoraConfig? = null

    // Largest Data.payload a single board packet may carry, sized DOWN from the negotiated BLE MTU on
    // session-up so a full fragment's ToRadio write fits one ATT op (ESP32 boards commonly cap at MTU 255).
    @Volatile
    private var maxPayload: Int = MeshtasticProto.MAX_PAYLOAD

    @Volatile
    private var selfIdCached: String? = null

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val jobs = mutableListOf<Job>()

    override fun start() {
        scope.launch { selfIdCached = selfId() }
        jobs += scope.launch { config.collect(::onConfig) }
        jobs += scope.launch { link.state.collect(::onLinkState) }
        jobs += scope.launch { link.packets.collect(::onLoraPacket) }
        jobs += scope.launch { link.queue.collect { it?.let { q -> pace.onQueueStatus(q.free) } } }
        jobs += scope.launch { link.outcomes.collect(::onNak) }
        jobs += scope.launch { pacerLoop() }
        jobs += scope.launch { lingerSweepLoop() }
    }

    override fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        link.stop()
        lastHeardAt.clear()
        heardPeers.clear()
        _reachable.value = emptySet()
        _health.value = TransportHealth.Unavailable
    }

    override fun heal() {
        // No rescan to trigger — the board session self-heals with its own backoff. Nudge the pacer in case
        // a frame is queued behind a stale gap.
        wake.trySend(Unit)
    }

    override fun onForeignReachable(peers: Set<String>) {
        foreignReachable = peers
    }

    /**
     * Writes the well-known [KnitChannel] onto the connected board — the one-tap alternative to setting up a
     * channel by hand in the Meshtastic app. On [ProvisionResult.Provisioned] the settings VM persists the
     * returned index so this plane binds to it. Requires a Ready link.
     */
    override suspend fun provisionKnitChannel(): ProvisionResult = link.provisionChannel(ProvisionSpec(KnitChannel.NAME, KnitChannel.PSK))

    // --- outbound (fast plane only) ---

    override fun fastFanout(wire: WireEnvelope) = fanout(wire, "fanout")

    override fun longRangeFanout(wire: WireEnvelope) = fanout(wire, "far")

    /**
     * The one fan-out: the composite's coordination-plane blast ([fastFanout] — room + cleartext metadata) and
     * its long-range sibling ([longRangeFanout] — sealed DM-form chat, ADR 039) both land here, and
     * [LoraFramePolicy] is the single gate for what rides.
     */
    private fun fanout(
        wire: WireEnvelope,
        label: String,
    ) {
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return
        if (env.type == FrameType.PROFILE && env.senderId == selfIdCached) {
            sendSelfProfile(wire) // shares the beacon's floor so the two never double-send
            return
        }
        if (!LoraFramePolicy.eligible(env, wire, LoraFramePolicy.Path.FANOUT)) return
        if (LoraFramePolicy.isDmForm(env) && currentConfig?.dms != true) return // the user keeps DMs off this plane
        if (!LoraFramePolicy.isFresh(env, wallClock())) {
            metrics.onLoraSuppressed() // a custody re-serve of an old frame — custody's business, not a live plane's
            return
        }
        if (!sigSeen.add(sigKey(wire))) {
            metrics.onLoraSuppressed() // already sent/received over LoRa within the window
            return
        }
        enqueue(wire, "$label:${env.type}", classOf(env))
    }

    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {
        if (to.nodeId !in _reachable.value.mapTo(HashSet()) { it.nodeId }) return
        if (to.nodeId in foreignReachable) return // another plane already carries this peer's traffic
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return
        if (!LoraFramePolicy.eligible(env, wire, LoraFramePolicy.Path.TARGETED, to.nodeId)) return
        if (!sigSeen.add(sigKey(wire))) return
        enqueue(wire, "send:${env.type}->${to.nodeId}", classOf(env))
    }

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) = Unit // LoRa is fast-plane only; the reliable flood never rides it

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: app.getknit.knit.mesh.FileMeta,
    ): Boolean = false // no bulk transfers over LoRa

    private fun enqueue(
        wire: WireEnvelope,
        label: String,
        klass: FrameClass,
    ) {
        val parts = LoraFrameCodec.encode(wire, fragSeq.getAndIncrement() and FRAG_ID_MASK, maxPayload)
        if (parts == null) {
            metrics.onLoraTooBig()
            log("lora too-big $label")
            return
        }
        if (pace.enqueue(OutboundFrame(parts, label, klass)) != LoraPacePolicy.Admission.ACCEPTED) {
            metrics.onLoraDroppedQueue()
        }
        wake.trySend(Unit)
    }

    /** The pacing class of a frame: the profile is the key bootstrap, a DM outranks ambient room traffic. */
    private fun classOf(env: RelayEnvelope): FrameClass =
        when {
            env.type == FrameType.PROFILE -> FrameClass.BOOTSTRAP
            LoraFramePolicy.isDmForm(env) -> FrameClass.DM
            else -> FrameClass.ROOM
        }

    // --- the profile beacon (key bootstrap) ---

    private fun sendSelfProfile(
        wire: WireEnvelope,
        minGapMs: Long = PROFILE_FLOOR_MS,
    ) {
        if (!profileGapElapsed(clock(), minGapMs)) return
        lastSelfProfileAt.set(clock())
        sigSeen.add(sigKey(wire))
        enqueue(wire, "profile-self", FrameClass.BOOTSTRAP)
    }

    /**
     * Beacons the signed self profile unless one went out within [minGapMs]. One timestamp, two gaps: session-up
     * keeps the 5-min floor, while a first hearing needs only a 60-s gap — the peer that just appeared has
     * demonstrably never heard us, and without a periodic beacon this is the only way a late arrival learns our
     * key (A beaconed two minutes ago, B just came up: A must speak again or B's parked frames expire).
     */
    private suspend fun beaconProfile(minGapMs: Long) {
        if (!profileGapElapsed(clock(), minGapMs)) return // check before the (potentially costly) profile build
        val wire = selfProfile() ?: return
        sendSelfProfile(wire, minGapMs)
    }

    /**
     * Re-offers the carried DM-form frames addressed to [peer] — pulled through [farFrames] (custody, via
     * [app.getknit.knit.mesh.FarPeerFrameSource]) — on first hearing it (ADR 039): this plane has no custody
     * sync, so a DM sent while the peer's board was off is otherwise lost to it until radio contact. Bounded by
     * the source (the newest few), the sig-keyed dedup (a frame fanned inside the window is skipped) and the
     * 45-min linger (a peer is "first heard" at most once per window). Skipped for a peer another plane already
     * carries — it gets custody's real digest sync there.
     */
    private suspend fun reofferTo(peer: Peer) {
        if (currentConfig?.dms != true || peer.nodeId in foreignReachable) return
        farFrames(peer.nodeId).forEach { wire -> reofferOne(wire, peer.nodeId) }
    }

    /** Enqueues one re-offered frame if it is a DM-form chat addressed to [to] and not fanned inside the dedup window. */
    private fun reofferOne(
        wire: WireEnvelope,
        to: String,
    ) {
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return
        if (!LoraFramePolicy.isDmForm(env) || env.recipientId != to) return
        if (!sigSeen.add(sigKey(wire))) return
        enqueue(wire, "reoffer:${env.id}", FrameClass.DM)
        metrics.onLoraReoffered()
    }

    /** Whether [minGapMs] has elapsed since the last self-profile; overflow-safe against the [NEVER] sentinel. */
    private fun profileGapElapsed(
        now: Long,
        minGapMs: Long,
    ): Boolean = lastSelfProfileAt.get().let { it == NEVER || now - it >= minGapMs }

    // --- the pacer ---

    private suspend fun pacerLoop() {
        while (scope.isActive) {
            val frame = pace.take(clock())
            if (frame == null) {
                waitForNextSend()
                continue
            }
            sendFrame(frame)
        }
    }

    private suspend fun waitForNextSend() {
        if (pace.pending == 0) {
            wake.receive()
        } else {
            val wait = (pace.nextDueAt() - clock()).coerceAtLeast(0)
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    private suspend fun sendFrame(frame: OutboundFrame) {
        val ch = currentConfig?.channelIndex ?: return
        for (message in frame.messages) {
            if (!sendMessage(message, ch, frame)) return
        }
        metrics.onLoraSent()
        if (frame.fragmented) metrics.onLoraFragSent()
        if (frame.klass == FrameClass.DM) metrics.onLoraDmSent()
        log("lora tx ${frame.label} parts=${frame.messages.size}")
    }

    /** Sends one fragment; false ends the frame (a NAK, error, or no headroom). */
    private suspend fun sendMessage(
        message: ByteArray,
        channelIndex: Int,
        frame: OutboundFrame,
    ): Boolean =
        when (val result = link.send(message, channelIndex)) {
            is SendResult.Queued -> {
                pace.onQueueStatus(result.queue.free)
                true
            }

            is SendResult.Nak -> {
                metrics.onLoraNak()
                pace.onNak(result.reason, clock())
                false
            }

            SendResult.Busy -> {
                requeue(frame)
                false
            }

            else -> {
                log("lora tx ${frame.label} gave up: $result")
                false
            }
        }

    private fun requeue(frame: OutboundFrame) {
        if (pace.enqueue(frame) != LoraPacePolicy.Admission.ACCEPTED) metrics.onLoraDroppedQueue()
    }

    private fun onNak(outcome: PacketOutcome) {
        metrics.onLoraNak()
        pace.onNak(outcome.reason, clock())
    }

    // --- inbound ---

    private fun onLoraPacket(packet: ReceivedPacket) {
        if (packet.portnum != MeshtasticProto.PORT_PRIVATE_APP || packet.payload.isEmpty()) return
        val fragmented = packet.payload[0] == FastFrameCodec.TAG_FRAG
        val compact = reassemble(packet) ?: return
        val wire = FastFrameCodec.decodeCompact(compact)
        if (wire == null) {
            metrics.onFastDropped(app.getknit.knit.mesh.FastPathDrop.DECODE_FAILED)
            return
        }
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return
        if (env.senderId == selfIdCached) return // our own frame echoed back over the mesh
        sigSeen.add(sigKey(wire)) // so the composite's relay re-fanout doesn't bounce it back over LoRa
        noteReachable(Peer(env.senderId))
        metrics.onLoraReceived()
        if (fragmented) metrics.onLoraReassembled()
        if (LoraFramePolicy.isDmForm(env)) metrics.onLoraDmReceived()
        _inbound.tryEmit(InboundFrame(wire, env, fromNodeId = env.senderId))
        log("lora rx ${env.type} id=${env.id} from ${env.senderId}")
    }

    /** Returns the complete compact frame for [packet]: itself if [FastFrameCodec.TAG_COMPACT], else reassembled. */
    private fun reassemble(packet: ReceivedPacket): ByteArray? =
        when (packet.payload[0]) {
            FastFrameCodec.TAG_COMPACT -> {
                packet.payload
            }

            FastFrameCodec.TAG_FRAG -> {
                val frag = FastFrameCodec.parseFragment(packet.payload) ?: return null
                reassembler.accept(packet.from, frag)?.takeIf { it.firstOrNull() == FastFrameCodec.TAG_COMPACT }
            }

            else -> {
                metrics.onFastDropped(app.getknit.knit.mesh.FastPathDrop.UNKNOWN_TAG)
                null
            }
        }

    private fun noteReachable(peer: Peer) {
        val now = clock()
        val firstHeard = lastHeardAt.put(peer.nodeId, now) == null
        heardPeers[peer.nodeId] = peer
        recomputeReachable(now)
        if (firstHeard) {
            scope.launch {
                beaconProfile(FIRST_HEARING_GAP_MS)
                reofferTo(peer)
            }
        }
    }

    private fun recomputeReachable(now: Long) {
        lastHeardAt.entries.removeAll { now - it.value > REACHABLE_LINGER_MS }
        heardPeers.keys.retainAll(lastHeardAt.keys)
        _reachable.value = heardPeers.values.toSet()
        publishStatus()
    }

    private suspend fun lingerSweepLoop() {
        while (scope.isActive) {
            delay(LINGER_SWEEP_MS)
            recomputeReachable(clock())
        }
    }

    // --- config + state ---

    private fun onConfig(cfg: LoraConfig?) {
        currentConfig = cfg
        if (cfg == null) {
            link.stop()
            _health.value = TransportHealth.Unavailable
        } else {
            link.start(cfg.address)
        }
        publishStatus()
    }

    private fun onLinkState(state: LinkState) {
        _health.value =
            when (state) {
                is LinkState.Ready -> TransportHealth.Healthy
                LinkState.Connecting, LinkState.Bonding, is LinkState.Handshaking, is LinkState.Disconnected -> TransportHealth.Degraded
                LinkState.Idle, LinkState.Unavailable, is LinkState.NeedsPairing, is LinkState.StaleBond -> TransportHealth.Unavailable
            }
        if (state is LinkState.Ready) {
            maxPayload = (state.mtu - TORADIO_OVERHEAD).coerceIn(LoraFrameCodec.MIN_PAYLOAD, MeshtasticProto.MAX_PAYLOAD)
            metrics.onLoraSessionUp()
            log("lora ready board=${state.board.myNodeNum} mtu=${state.mtu} maxPayload=$maxPayload")
            scope.launch { beaconProfile(PROFILE_FLOOR_MS) }
        }
        publishStatus()
    }

    private fun publishStatus() {
        val board = (link.state.value as? LinkState.Ready)?.board
        _status.value =
            LoraStatus(
                state = link.state.value,
                boardAddress = currentConfig?.address,
                boardNodeNum = board?.myNodeNum,
                lastSnr = link.rxQuality.value?.snr,
                lastRssi = link.rxQuality.value?.rssi,
                queueFree = link.queue.value?.free,
                heard = _reachable.value.size,
            )
    }

    private fun sigKey(wire: WireEnvelope): String {
        val n = minOf(SIG_KEY_BYTES, wire.sig.size)
        return buildString(n * 2) { for (i in 0 until n) append("%02x".format(wire.sig[i])) }
    }

    private companion object {
        const val INBOUND_BUFFER = 64
        const val SIG_TTL_MS = 10 * 60_000L // = SeenSet.DEFAULT_TTL_MS
        const val SIG_KEY_BYTES = 8
        const val FRAG_CAP = 16
        const val FRAG_TIMEOUT_MS = 60_000L // LoRa parts are seconds apart; the NAN 5 s default would drop them
        const val FRAG_ID_MASK = 0xFFFF
        const val REACHABLE_LINGER_MS = 45 * 60_000L
        const val LINGER_SWEEP_MS = 60_000L
        const val PROFILE_FLOOR_MS = 5 * 60_000L
        const val FIRST_HEARING_GAP_MS = 60_000L
        const val NEVER = Long.MIN_VALUE

        // Bytes a ToRadio{packet} adds around the Data.payload (3-B ATT header + MeshPacket/Data framing +
        // fixed32 to/id + slack), so `mtu - this` is the payload that still fits one ATT write.
        const val TORADIO_OVERHEAD = 33
    }
}
