package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.StoreDigest
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
    private val offerPrefixes: suspend (limit: Int) -> IntArray = { IntArray(0) },
    private val framesMissing: suspend (prefixes: IntArray, limit: Int, dms: Boolean) -> List<WireEnvelope> =
        { _, _, _ -> emptyList() },
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val pace: LoraPacePolicy = LoraPacePolicy(),
    private val gateway: LoraGatewayPolicy = LoraGatewayPolicy(),
    private val gossip: LoraGossipPolicy = LoraGossipPolicy(),
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
    // NOTE these are frame **authors**, not radios: a frame is authored by one node and may be put on air by
    // another's board, relayed or backfilled out of its custody. That is the right key for `reachable` (who
    // can I reach through this mesh) and the wrong one for "how many radios can I hear" — see boardsHeardAt.
    private val lastHeardAt = ConcurrentHashMap<String, Long>()

    // Meshtastic node numbers we have heard transmit on our channel: one entry per **radio**, which is what
    // the settings row is actually asking. Counted for every Knit packet including control packets and
    // incomplete fragments — a board that only publishes offers is still a board in range.
    private val boardsHeardAt = ConcurrentHashMap<UInt, Long>()
    private val heardPeers = ConcurrentHashMap<String, Peer>()
    private val lastSelfProfileAt = AtomicLong(NEVER)

    // Peers a short-range sibling has *sighted*. Diagnostics only — deliberately nothing routes on it.
    // Every routing decision here reads `linkedPeers` instead; the two differ exactly when a peer is heard
    // but not linked, which is the state that silenced a board in the field, so it is worth being able to see.
    @Volatile
    private var foreignReachable: Set<String> = emptySet()

    // Peers a higher-preference plane holds a **live link** to right now (BLE/NAN, via suppressDataPath).
    // The gateway election reads this and NOT `foreignReachable`: a sighting is not a data path, and the
    // whole premise of standing down is that the other board will carry our traffic for us.
    @Volatile
    private var linkedPeers: Set<String> = emptySet()

    @Volatile
    private var currentConfig: LoraConfig? = null

    // Largest Data.payload a single board packet may carry, sized DOWN from the negotiated BLE MTU on
    // session-up so a full fragment's ToRadio write fits one ATT op (ESP32 boards commonly cap at MTU 255).
    @Volatile
    private var maxPayload: Int = MeshtasticProto.MAX_PAYLOAD

    @Volatile
    private var selfIdCached: String? = null

    // Our gateway role, recomputed whenever an OFFER or a foreign-reachable update could change it. ACTIVE
    // until proven otherwise, so a lone board bridges from the first packet rather than after a gossip round.
    @Volatile
    private var role = LoraGatewayPolicy.Role.ACTIVE

    // The prefix set our last OFFER announced — kept so an inbound OFFER can be recognised as announcing the
    // same set (the only genuinely redundant one, see LoraGossipPolicy) without re-querying custody.
    @Volatile
    private var lastOfferPrefixes: IntArray = IntArray(0)

    // How many frames we have served each far gateway inside the current hour, so one publisher cannot walk
    // a gateway through its whole custody set by re-offering. The airtime budget is the real bound; this
    // stops a single peer monopolising it.
    private val servedTo = ConcurrentHashMap<Long, ServeBudget>()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val gossipWake = Channel<Unit>(Channel.CONFLATED)
    private val jobs = mutableListOf<Job>()

    /**
     * A rolling hourly allowance of frames served to one publisher. Not a security boundary — the airtime
     * budget is that — but it stops a single peer's repeated offers absorbing the whole bridge share while
     * another pocket goes unserved.
     */
    private class ServeBudget {
        private var windowStart = Long.MIN_VALUE
        private var spent = 0

        @Synchronized
        fun take(
            want: Int,
            now: Long,
        ): Int {
            if (windowStart == Long.MIN_VALUE || now - windowStart >= SERVE_WINDOW_MS) {
                windowStart = now
                spent = 0
            }
            val grant = minOf(want, SERVE_CAP_PER_HOUR - spent).coerceAtLeast(0)
            spent += grant
            return grant
        }

        @Synchronized
        fun refund(n: Int) {
            spent = (spent - n).coerceAtLeast(0)
        }
    }

    override fun start() {
        scope.launch {
            selfIdCached = selfId()
            recomputeRole()
        }
        jobs += scope.launch { config.collect(::onConfig) }
        jobs += scope.launch { link.state.collect(::onLinkState) }
        jobs += scope.launch { link.packets.collect(::onLoraPacket) }
        jobs += scope.launch { link.queue.collect { it?.let { q -> pace.onQueueStatus(q.free) } } }
        jobs += scope.launch { link.outcomes.collect(::onNak) }
        jobs += scope.launch { link.battery.collect { publishStatus() } }
        jobs += scope.launch { pacerLoop() }
        jobs += scope.launch { lingerSweepLoop() }
        jobs += scope.launch { gossipLoop() }
    }

    override fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        link.stop()
        lastHeardAt.clear()
        heardPeers.clear()
        boardsHeardAt.clear()
        gateway.forget()
        servedTo.clear()
        // A restart must not inherit a deferral to a board that may no longer be there.
        role = LoraGatewayPolicy.Role.ACTIVE
        lastOfferPrefixes = IntArray(0)
        _reachable.value = emptySet()
        _health.value = TransportHealth.Unavailable
    }

    override fun heal() {
        // No rescan to trigger — the board session self-heals with its own backoff. Nudge the pacer in case
        // a frame is queued behind a stale gap.
        wake.trySend(Unit)
    }

    /** Sightings from the short-range planes. Recorded for the diagnostics dump; never routed on — see the field. */
    override fun onForeignReachable(peers: Set<String>) {
        foreignReachable = peers
        publishStatus()
    }

    /**
     * The peers BLE/NAN currently hold a live link to. This plane has no data path of its own, so the hint's
     * usual meaning (don't bring up a redundant sync) is moot — what it is read for here is the **gateway
     * election**: standing down is only safe toward a board that can actually be handed our traffic.
     */
    override fun suppressDataPath(peers: Set<String>) {
        linkedPeers = peers
        // A co-pocket gateway gaining or losing its link is exactly what changes who speaks for this pocket.
        recomputeRole()
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
        if (!mayTransmit()) return // another board in this pocket is the gateway; it will carry this frame
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
        val parts = encodeOrNull(wire, "$label:${env.type}") ?: return
        if (!sigSeen.add(sigKey(wire))) {
            metrics.onLoraSuppressed() // already sent/received over LoRa within the window
            return
        }
        enqueue(parts, "$label:${env.type}", classOf(env))
    }

    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {
        // Deliberately NOT gated on the gateway role. A targeted send is `relay = false`: only this node owes
        // it, it is never flooded, and a co-pocket gateway therefore has no copy of it to relay and no copy to
        // duplicate. Suppressing it was pure loss — it stranded AckSync's ✓✓ ticks on a passive board, which
        // retries them for 24 h and never lands one.
        if (to.nodeId !in _reachable.value.mapTo(HashSet()) { it.nodeId }) return
        // "Another plane carries this peer's traffic" (ADR 039) means a **link**, not a sighting: a peer BLE
        // has merely heard advertise, or one Wi-Fi Aware still lists 150 s after its last cue, is being
        // carried by nothing. Read as a sighting this refused the one path a far peer's ✓✓ had — the same
        // reachable-vs-linked error as the gateway election, and the second reason the field test lost its
        // receipts even once the role was right.
        if (to.nodeId in linkedPeers) return
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return
        if (!LoraFramePolicy.eligible(env, wire, LoraFramePolicy.Path.TARGETED, to.nodeId)) return
        val label = "send:${env.type}->${to.nodeId}"
        val parts = encodeOrNull(wire, label) ?: return
        if (!sigSeen.add(sigKey(wire))) return
        enqueue(parts, label, classOf(env))
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

    /**
     * Compacts + fragments [wire] for the hop, or null when it can't ride (counted `loraTooBig`). Deliberately
     * separate from [enqueue] and called **before** the sig dedup: recording the sig first would burn the
     * frame's 10-minute dedup slot on a frame that never went out, so a later re-offer or backfill of the
     * same frame would be silently suppressed rather than retried.
     */
    private fun encodeOrNull(
        wire: WireEnvelope,
        label: String,
    ): List<ByteArray>? {
        val parts = LoraFrameCodec.encode(wire, fragSeq.getAndIncrement() and FRAG_ID_MASK, maxPayload)
        if (parts == null) {
            metrics.onLoraTooBig()
            log("lora too-big $label")
        }
        return parts
    }

    private fun enqueue(
        parts: List<ByteArray>,
        label: String,
        klass: FrameClass,
        bucket: AirBucket = AirBucket.LIVE,
    ) {
        if (pace.enqueue(OutboundFrame(parts, label, klass, bucket)) != LoraPacePolicy.Admission.ACCEPTED) {
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
        if (!mayTransmit()) return
        if (!profileGapElapsed(clock(), minGapMs)) return
        val parts = encodeOrNull(wire, "profile-self") ?: return
        lastSelfProfileAt.set(clock())
        sigSeen.add(sigKey(wire))
        enqueue(parts, "profile-self", FrameClass.BOOTSTRAP)
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
        if (!mayTransmit()) return
        // Links, not sightings, for the same reason: the re-offer is skipped because custody syncs to this
        // peer for real elsewhere, and `ForwardSync`'s digest exchange runs off `neighbors` — a sighting
        // never triggers it, so skipping on one strands the very DMs this path exists to deliver.
        if (currentConfig?.dms != true || peer.nodeId in linkedPeers) return
        farFrames(peer.nodeId).forEach { wire -> reofferOne(wire, peer.nodeId) }
    }

    /** Enqueues one re-offered frame if it is a DM-form chat addressed to [to] and not fanned inside the dedup window. */
    private fun reofferOne(
        wire: WireEnvelope,
        to: String,
    ) {
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return
        if (!LoraFramePolicy.isDmForm(env) || env.recipientId != to) return
        val parts = encodeOrNull(wire, "reoffer:${env.id}") ?: return
        if (!sigSeen.add(sigKey(wire))) return
        // DM class so a room post can never evict it, BRIDGE bucket so it is metered as the backfill it is.
        enqueue(parts, "reoffer:${env.id}", FrameClass.DM, AirBucket.BRIDGE)
        metrics.onLoraReoffered()
    }

    // --- the bridge: gateway role, gossip, backfill (ADR 044) ---

    /**
     * Recomputes whether this phone speaks for its pocket. Cheap and idempotent, so it runs on every input
     * that could change the answer: an inbound OFFER (a rival appeared) and a foreign-reachable update (one
     * arrived in, or left, our BLE/NAN clique).
     */
    private fun recomputeRole() {
        val self = selfIdCached ?: return
        val next = gateway.roleFor(StoreDigest.hash64(self), pocketKeys(), clock())
        if (next == role) return
        role = next
        log("lora role $next (links=${linkedPeers.size} gateways=${gateway.heard})")
        publishStatus()
    }

    /**
     * The pocket, for election purposes: peers a short-range plane holds a **live link** to.
     *
     * Deliberately [linkedPeers] and not [foreignReachable]. The latter is a *sighting* — BLE publishes
     * presence adverts ∪ links, and Wi-Fi Aware keeps a 150-second ghost after the last cue — and its own
     * kdoc says "not necessarily linked here". Electing on it strands a board: two phones that can hear each
     * other's adverts across a field, with no L2CAP link between them, would have the higher-keyed one stand
     * down for a peer that never receives, let alone relays, a single frame of its traffic. Standing down is
     * only ever safe toward a board our frames can actually be handed to.
     */
    private fun pocketKeys(): Set<Long> = linkedPeers.mapTo(HashSet()) { StoreDigest.hash64(it) }

    /** Whether we may put anything on the air at all. A passive gateway listens and relays, but never transmits. */
    private fun mayTransmit(): Boolean {
        if (role == LoraGatewayPolicy.Role.ACTIVE) return true
        metrics.onLoraPassive()
        return false
    }

    /**
     * Publishes a [LoraCtl] OFFER on the gossip policy's schedule. One packet says what we hold, so a far
     * gateway can serve exactly what we lack — no request round trip, and no blind re-transmission of history
     * the other pocket already has.
     */
    private suspend fun gossipLoop() {
        while (scope.isActive) {
            val wait = (gossip.nextDueAt(clock()) - clock()).coerceAtLeast(0)
            // The slot is consumed before the link is consulted, and that ordering is load-bearing: skipping
            // the take while the board is down leaves the transmit point in the past, so the next pass
            // computes a zero wait and the loop spins at full tilt until the board returns.
            if (wait > 0) withTimeoutOrNull(wait) { gossipWake.receive() } else delay(IDLE_TICK_MS)
            if (!gossip.takeTransmitSlot(clock())) continue
            if (link.state.value is LinkState.Ready) publishOffer()
        }
    }

    private suspend fun publishOffer() {
        if (currentConfig?.bridge != true || !mayTransmit()) return
        val self = selfIdCached ?: return
        val prefixes = runCatching { offerPrefixes(LoraCtl.MAX_PREFIXES) }.getOrDefault(IntArray(0))
        val payload = LoraCtl.encodeOffer(StoreDigest.hash64(self), prefixes, maxPayload)
        lastOfferPrefixes = LoraCtl.decodeOffer(payload)?.prefixes ?: IntArray(0)
        enqueue(listOf(payload), "offer:${lastOfferPrefixes.size}", FrameClass.GOSSIP, AirBucket.BRIDGE)
        metrics.onLoraOfferSent()
    }

    /**
     * A gateway's OFFER. Three things follow from one packet: it proves the publisher has a board (so the
     * election has a rival to weigh), it tells the gossip timer whether our own OFFER would be redundant,
     * and — if the publisher is in another pocket — it says exactly what to send them.
     */
    private fun onCtlPacket(packet: ReceivedPacket) {
        val offer = LoraCtl.decodeOffer(packet.payload) ?: return
        val self = selfIdCached
        if (self != null && offer.publisher == StoreDigest.hash64(self)) return // our own, echoed by the mesh
        val now = clock()
        metrics.onLoraOfferReceived()
        gateway.onOffer(offer.publisher, now)
        gossip.onOffer(sameSet = offer.prefixes.contentEquals(lastOfferPrefixes), now = now)
        recomputeRole()
        // Note an OFFER does NOT mark its publisher `reachable`: the packet carries a hash, not a node id,
        // so there is no Peer to record. The first actual frame from that node does it, which is the right
        // moment anyway — a gateway is a relay, not necessarily someone you can address.
        //
        // A co-pocket gateway is not a bridge peer: custody syncs to it for real over BLE/NAN, so serving it
        // over LoRa would spend air on frames already crossing a link that costs nothing.
        if (!gateway.isFarGateway(offer.publisher, pocketKeys())) return
        scope.launch { serveBackfill(offer) }
    }

    /**
     * Serves a far gateway the frames its OFFER shows it is missing. Bounded four ways, deliberately: the
     * per-publisher hourly cap below, the per-sighting [BACKFILL_LIMIT], the sig dedup (a frame already on
     * the air this window is skipped), and — the one that actually matters — the BRIDGE airtime budget in
     * [LoraAirtime]. Without the first, a node that re-offers an empty set could walk us through our whole
     * custody set on the air; without the last, a busy bridge would crowd out live chat.
     */
    private suspend fun serveBackfill(offer: LoraCtl.Offer) {
        if (currentConfig?.bridge != true || !mayTransmit()) return
        val now = clock()
        val budget = servedTo.getOrPut(offer.publisher) { ServeBudget() }
        val allowance = budget.take(BACKFILL_LIMIT, now)
        if (allowance == 0) {
            metrics.onLoraBridgeRefused()
            return
        }
        val dms = currentConfig?.dms == true
        val candidates = runCatching { framesMissing(offer.prefixes, allowance * CANDIDATE_SLACK, dms) }.getOrDefault(emptyList())
        // The far side may never have seen our key, and a frame it cannot verify is airtime thrown away — but
        // beacon only when we are actually about to send it something. An offer arrives every few minutes from
        // every gateway in range; beaconing on each one would spend more air on profiles than on messages.
        if (candidates.isNotEmpty()) beaconProfile(FIRST_HEARING_GAP_MS)
        var served = 0
        for (wire in candidates) {
            if (served >= allowance) break
            if (serveOne(wire)) served++
        }
        budget.refund(allowance - served)
        if (served > 0) {
            metrics.onLoraBridged(served)
            // Something crossed, so the far side's picture just changed: gossip again soon rather than at the
            // backed-off interval, and let the next OFFER carry what is still missing.
            gossip.reset(clock())
            gossipWake.trySend(Unit)
        }
        log("lora bridge served=$served/$allowance to ${offer.publisher.toULong().toString(HEX)}")
    }

    /** Enqueues one backfilled frame; false when it can't ride (too big, or already on the air this window). */
    private fun serveOne(wire: WireEnvelope): Boolean {
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return false
        val label = "bridge:${env.id}"
        val parts = encodeOrNull(wire, label) ?: return false
        if (!sigSeen.add(sigKey(wire))) {
            metrics.onLoraSuppressed()
            return false
        }
        // Its natural class, so a room post still cannot evict a DM in the queue, but the BRIDGE bucket, so
        // every byte of it is metered as the backfill it is.
        enqueue(parts, label, classOf(env), AirBucket.BRIDGE)
        return true
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
                pace.airtime.record(frame.bucket, message.size, clock())
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
        // Outbound is pinned to the bound channel; inbound was not, so a board carrying a second channel with
        // Knit traffic on it used to ingest both. Ignore anything off the channel this plane is bound to.
        val bound = currentConfig?.channelIndex
        if (bound != null && packet.channelIndex != bound) return
        noteBoard(packet.from)
        if (LoraCtl.isCtl(packet.payload)) onCtlPacket(packet) else onFramePacket(packet)
    }

    /** One inbound mesh frame off the air: reassemble, decode, dedup, and hand it to the router. */
    private fun onFramePacket(packet: ReceivedPacket) {
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
        // Our held set just changed, so the next OFFER carries new information: snap the gossip timer back
        // to its floor rather than announcing a stale picture at the backed-off interval.
        gossip.reset(clock())
        gossipWake.trySend(Unit)
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

    /**
     * Records that the radio [from] is on air. Separate from [noteReachable] because they answer different
     * questions: this one counts **radios**, that one counts the **people** whose frames reached us — and the
     * two diverge the moment a gateway relays or backfills somebody else's frame, which is the whole point of
     * the bridge. Reporting the second as the first read as a phantom radio in the field.
     */
    private fun noteBoard(from: UInt) {
        if (from == 0u) return // undecoded `from`; counting it would invent a radio
        if (from == (link.state.value as? LinkState.Ready)?.board?.myNodeNum) return // our own board's echo
        boardsHeardAt[from] = clock()
        publishStatus()
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
        boardsHeardAt.entries.removeAll { now - it.value > REACHABLE_LINGER_MS }
        heardPeers.keys.retainAll(lastHeardAt.keys)
        _reachable.value = heardPeers.values.toSet()
        publishStatus()
    }

    private suspend fun lingerSweepLoop() {
        while (scope.isActive) {
            delay(LINGER_SWEEP_MS)
            recomputeReachable(clock())
            // Also re-run the election on a timer. Both its event triggers can go quiet at once — a passive
            // board stops offering, and if the gateway it stood down for goes silent too, nothing would ever
            // expire it from the heard set. Being wrongly passive is total silence, so it must not need an
            // event to recover from.
            recomputeRole()
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
            pace.airtime.onRadioConfig(state.radio)
            log(
                "lora ready board=${state.board.myNodeNum} mtu=${state.mtu} maxPayload=$maxPayload " +
                    "radio=${state.radio?.region}/${state.radio?.modemPreset}",
            )
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
                boardsHeard = boardsHeardAt.size,
                battery = link.battery.value,
                airtime = pace.airtime.snapshot(clock()),
                role = role,
                pocketLinks = linkedPeers.size,
                pocketSightings = foreignReachable.size,
                gatewaysHeard = gateway.heard,
            )
    }

    private fun sigKey(wire: WireEnvelope): String {
        val n = minOf(SIG_KEY_BYTES, wire.sig.size)
        return buildString(n * 2) { for (i in 0 until n) append("%02x".format(wire.sig[i])) }
    }

    /** Tuning constants; not private so the JVM tests can assert against the numbers rather than restate them. */
    companion object {
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

        // The bridge (ADR 044).
        const val BACKFILL_LIMIT = 4 // frames per offer heard
        const val SERVE_CAP_PER_HOUR = 12 // frames per far gateway per hour
        const val SERVE_WINDOW_MS = 60 * 60_000L
        const val CANDIDATE_SLACK = 3 // ask custody for more than we can send: some won't encode or are deduped
        const val HEX = 16

        /** A floor on the gossip loop's wait, so a zero-length wait can never become a busy loop. */
        const val IDLE_TICK_MS = 1_000L

        // Bytes a ToRadio{packet} adds around the Data.payload (3-B ATT header + MeshPacket/Data framing +
        // fixed32 to/id + slack), so `mtu - this` is the payload that still fits one ATT write.
        const val TORADIO_OVERHEAD = 33
    }
}
