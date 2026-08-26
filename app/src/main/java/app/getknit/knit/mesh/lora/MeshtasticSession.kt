package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.bluetooth.BackoffConfig
import app.getknit.knit.mesh.bluetooth.ConnectBackoffPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * One write inside a provisioning transaction, built late because the [MeshtasticProto] `session_passkey`
 * it must echo is only known once the board has issued one — and is re-issued on a fresh-key retry.
 */
private typealias AdminStep = (ByteArray?) -> ByteArray

/**
 * The pure state machine that turns a [MeshtasticGattDialer] into a managed [MeshtasticLink]. An actor:
 * a single driver coroutine owns the open [GattChannel] and issues every GATT op sequentially, fed by one
 * inbox [Channel] that merges send commands, GATT events, and heartbeat ticks. It handles the config
 * handshake, the drain-until-empty read on every FromNum, the keep-alive heartbeat, packet-id/queueStatus
 * correlation, and reconnect-with-backoff — all against injected `now`/`rand`/`nonce`, so it runs on the
 * JVM under virtual time ([app.getknit.knit.mesh.lora.MeshtasticSessionTest]).
 *
 * Android-free by construction (the only `android.bluetooth.*` lives behind the dialer seam), honouring
 * `.agents/rules/mesh.md`.
 *
 * `LargeClass` is suppressed because one actor owns the open GATT channel, so every op it serializes —
 * handshake, sends, heartbeat, admin provisioning — has to live here; splitting it would mean a second
 * owner of the same channel.
 */
@Suppress("LargeClass")
internal class MeshtasticSession(
    private val dialer: MeshtasticGattDialer,
    private val scope: CoroutineScope,
    private val backoff: BackoffConfig = BackoffConfig(baseMs = BASE_BACKOFF_MS, maxMs = MAX_BACKOFF_MS),
    private val now: () -> Long,
    private val rand: () -> Double = { Random.nextDouble() },
    private val nonce: () -> UInt = { Random.nextInt().toUInt().let { if (it == 0u) 1u else it } },
    private val ids: PacketIdSource = PacketIdSource(Random.nextLong()),
    private val log: (String) -> Unit = {},
) : MeshtasticLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state = _state.asStateFlow()

    private val _packets = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = PACKET_BUFFER)
    override val packets = _packets.asSharedFlow()

    private val _outcomes = MutableSharedFlow<PacketOutcome>(extraBufferCapacity = OUTCOME_BUFFER)
    override val outcomes = _outcomes.asSharedFlow()

    private val _queue = MutableStateFlow<QueueInfo?>(null)
    override val queue = _queue.asStateFlow()

    private val _rxQuality = MutableStateFlow<RxQuality?>(null)
    override val rxQuality = _rxQuality.asStateFlow()

    private val _battery = MutableStateFlow<BoardBattery?>(null)
    override val battery = _battery.asStateFlow()

    private val inbox = Channel<Cmd>(Channel.BUFFERED)
    private var loopJob: Job? = null

    @Volatile
    private var address: String? = null

    // The board's identity + channels, set at handshake; read when building the Ready state and for logs.
    private var board: BoardInfo? = null
    private var channels: List<ChannelInfo> = emptyList()

    // The board's radio settings (region + modem preset) from the handshake's Config stream — what the
    // airtime governor needs. Null until a board reports them; kept across a re-handshake of the same board.
    private var radio: LoraRadioConfig? = null
    private var lastWriteAt = 0L

    // Pending sends awaiting their queueStatus, keyed by our packet id, so a late NAK can still be matched.
    private val pending = HashMap<UInt, CompletableDeferred<SendResult>>()

    override fun start(address: String) {
        if (this.address == address && loopJob?.isActive == true) return
        stop()
        this.address = address
        loopJob = scope.launch { connectLoop(address) }
    }

    override fun stop() {
        loopJob?.cancel()
        loopJob = null
        address = null
        failAllPending(SendResult.NotReady(LinkState.Idle))
        _battery.value = null
        _state.value = LinkState.Idle
    }

    override suspend fun send(
        payload: ByteArray,
        channelIndex: Int,
        portnum: Int,
        hopLimit: Int?,
    ): SendResult {
        if (payload.size > MeshtasticProto.MAX_PAYLOAD) return SendResult.TooLarge
        val st = _state.value
        if (st !is LinkState.Ready) return SendResult.NotReady(st)
        val reply = CompletableDeferred<SendResult>()
        inbox.send(Cmd.Send(channelIndex, portnum, hopLimit, payload, reply))
        return reply.await()
    }

    override suspend fun provisionChannel(spec: ProvisionSpec): ProvisionResult {
        val st = _state.value
        if (st !is LinkState.Ready) return ProvisionResult.NotReady(st)
        val reply = CompletableDeferred<ProvisionResult>()
        inbox.send(Cmd.Provision(spec, reply))
        return reply.await()
    }

    // --- the reconnect loop (one coroutine per start) ---

    private suspend fun connectLoop(address: String) {
        var streak = 0
        while (scope.isActive) {
            if (!dialer.adapterOn.value) {
                _state.value = LinkState.Unavailable
                dialer.adapterOn.first { it }
                streak = 0
                continue
            }
            _state.value = LinkState.Connecting
            when (val result = dialer.dial(address)) {
                is DialResult.Opened -> {
                    val end =
                        try {
                            runSession(address, result.channel, result.mtu)
                        } finally {
                            result.channel.close() // closes on a normal end AND on stop()'s cancellation
                        }
                    if (end.terminal != null) {
                        _state.value = end.terminal
                        return
                    }
                    if (end.resetStreak) streak = 0
                    streak = backoffAndWait(end.reason, streak)
                }

                DialResult.NoHardware -> {
                    _state.value = LinkState.Unavailable
                    return
                }

                // Adapter went off mid-dial: fall through and let the loop re-check adapterOn at the top.
                DialResult.AdapterOff -> {
                    Unit
                }

                is DialResult.Failed -> {
                    streak = backoffAndWait("dial ${result.phase} ${result.status}", streak)
                }

                DialResult.Timeout -> {
                    streak = backoffAndWait("dial timeout", streak)
                }
            }
        }
    }

    private suspend fun backoffAndWait(
        reason: String,
        streak: Int,
    ): Int {
        val next = streak + 1
        val wait = ConnectBackoffPolicy.nextDelayMs(next, backoff, rand)
        log("lora backoff streak=$next ms=$wait ($reason)")
        _state.value = LinkState.Disconnected(reason, now() + wait, next)
        delay(wait)
        return next
    }

    // --- one connected session ---

    private suspend fun runSession(
        address: String,
        channel: GattChannel,
        mtu: Int,
    ): SessionEnd {
        val handshake = handshake(address, channel)
        if (handshake != null) return handshake
        _state.value = LinkState.Ready(requireNotNull(board), channels, mtu, radio)
        lastWriteAt = now()
        val heartbeat = scope.launch { heartbeatTicker() }
        try {
            return sessionLoop(address, channel)
        } finally {
            heartbeat.cancel()
            failAllPending(SendResult.NotReady(_state.value))
        }
    }

    private suspend fun sessionLoop(
        address: String,
        channel: GattChannel,
    ): SessionEnd {
        while (scope.isActive) {
            val outcome =
                select {
                    channel.events.onReceive { it }
                    inbox.onReceive { it }
                }
            val end = handleOutcome(address, channel, outcome)
            if (end != null) return end
        }
        return SessionEnd(reason = "cancelled", terminal = LinkState.Idle)
    }

    /** Handles one merged event; returns a [SessionEnd] to end the session, or null to keep looping. */
    private suspend fun handleOutcome(
        address: String,
        channel: GattChannel,
        outcome: Any,
    ): SessionEnd? =
        when (outcome) {
            is GattEvent.Disconnected -> SessionEnd(reason = "gatt disconnect ${outcome.status}")
            is GattEvent.Notified -> drain(address, channel, awaitId = null)
            is Cmd.Send -> doSend(address, channel, outcome)
            is Cmd.Provision -> runProvision(channel, outcome)
            Cmd.Heartbeat -> maybeHeartbeat(address, channel)
            else -> null
        }

    // --- handshake ---

    private suspend fun handshake(
        address: String,
        channel: GattChannel,
    ): SessionEnd? {
        _state.value = LinkState.Handshaking(null)
        board = null
        channels = emptyList()
        _battery.value = null
        classify(address, channel.subscribeFromNum(SUBSCRIBE_TIMEOUT_MS))?.let { return it }
        // Drain any stale queue from a previous phone session before our want_config nonce.
        drainQuietly(channel)
        val n = nonce()
        val writeTimeout = if (dialer.bondState(address) == BondState.BONDED) WRITE_TIMEOUT_MS else BONDING_TIMEOUT_MS
        if (dialer.bondState(address) == BondState.BONDING) _state.value = LinkState.Bonding
        classify(address, channel.writeToRadio(MeshtasticProto.encodeWantConfig(n), writeTimeout))?.let { return it }
        return awaitConfigComplete(address, channel, n)
    }

    private suspend fun awaitConfigComplete(
        address: String,
        channel: GattChannel,
        nonce: UInt,
    ): SessionEnd? {
        val deadline = now() + HANDSHAKE_TIMEOUT_MS
        while (now() < deadline) {
            when (val read = channel.readFromRadio(READ_TIMEOUT_MS)) {
                is GattResult.Ok -> {
                    if (absorb(MeshtasticProto.decodeFromRadio(read.value), nonce)) return null
                }

                else -> {
                    return classify(address, read) ?: SessionEnd(reason = "handshake read $read")
                }
            }
        }
        return SessionEnd(reason = "handshake timeout")
    }

    /**
     * Folds one handshake `FromRadio` into the session's picture of the board — identity, firmware, channel
     * table, radio settings, its own battery. Returns true on the `config_complete` that matches [nonce],
     * which is what ends the handshake. Anything else (including a variant we don't read) is absorbed
     * silently: the board streams its whole config, and an unknown entry must never stall the handshake.
     */
    private fun absorb(
        fr: FromRadio?,
        nonce: UInt,
    ): Boolean {
        when (fr) {
            is FromRadio.ConfigComplete -> return fr.id == nonce
            is FromRadio.MyInfo -> board = BoardInfo(fr.myNodeNum, fr.pioEnv, board?.firmwareVersion)
            is FromRadio.Metadata -> board = (board ?: BLANK_BOARD).copy(firmwareVersion = fr.firmwareVersion)
            is FromRadio.Channel -> channels = channels + fr.channel
            is FromRadio.Config -> fr.lora?.let { radio = it }
            is FromRadio.NodeInfo -> onNodeInfo(fr)
            else -> Unit
        }
        return false
    }

    // --- draining reads ---

    /**
     * Reads FromRadio until the queue drains. When [awaitId] is set (a send), returns as soon as its
     * `queueStatus` arrives so [doSend] can complete the caller; otherwise drains fully. A `rebooted`
     * or unsolicited `my_info` ends the session for a fresh handshake.
     */
    private suspend fun drain(
        address: String,
        channel: GattChannel,
        awaitId: UInt?,
    ): SessionEnd? {
        while (true) {
            when (val read = channel.readFromRadio(READ_TIMEOUT_MS)) {
                is GattResult.Ok -> {
                    if (read.value.isEmpty()) return null
                    val signal = dispatch(MeshtasticProto.decodeFromRadio(read.value), awaitId)
                    when (signal) {
                        Signal.Rehandshake -> return SessionEnd(reason = "rebooted", resetStreak = true)
                        Signal.MatchedAwait -> return null
                        Signal.Continue -> Unit
                    }
                }

                else -> {
                    return classify(address, read) ?: SessionEnd(reason = "drain read $read")
                }
            }
        }
    }

    private suspend fun drainQuietly(channel: GattChannel) {
        repeat(MAX_STALE_READS) {
            val read = channel.readFromRadio(READ_TIMEOUT_MS)
            if (read !is GattResult.Ok || read.value.isEmpty()) return
        }
    }

    private suspend fun dispatch(
        fr: FromRadio?,
        awaitId: UInt?,
    ): Signal =
        when (fr) {
            is FromRadio.Packet -> {
                onPacket(fr.packet)
                Signal.Continue
            }

            is FromRadio.QueueStatus -> {
                onQueueStatus(fr, awaitId)
            }

            FromRadio.Rebooted -> {
                Signal.Rehandshake
            }

            is FromRadio.MyInfo -> {
                if (_state.value is LinkState.Ready) Signal.Rehandshake else Signal.Continue
            }

            is FromRadio.NodeInfo -> {
                onNodeInfo(fr)
                Signal.Continue
            }

            is FromRadio.Config -> {
                // The firmware pushes a Config when the user edits the radio on the board; keep the
                // governor current without waiting for the next handshake.
                fr.lora?.let {
                    radio = it
                    (_state.value as? LinkState.Ready)?.let { ready -> _state.value = ready.copy(radio = it) }
                }
                Signal.Continue
            }

            else -> {
                Signal.Continue
            }
        }

    private suspend fun onPacket(packet: MeshPacket) {
        packet.rxSnr?.let { _rxQuality.value = RxQuality(it, packet.rxRssi, now()) }
        val data = packet.decoded ?: return // an encrypted packet on a foreign channel — not for us
        // Routing NAKs originate from our OWN board's node (it generates the error), so they must be
        // handled before the self-echo guard below — otherwise `from == myNodeNum` would swallow them.
        if (data.portnum == MeshtasticProto.PORT_ROUTING) {
            routeNak(data)
            return
        }
        // The board's own device telemetry (its battery) is addressed from itself too: read, never surfaced.
        if (data.portnum == MeshtasticProto.PORT_TELEMETRY && packet.from == board?.myNodeNum) {
            MeshtasticProto.decodeTelemetry(data.payload)?.let(::onSelfMetrics)
            return
        }
        if (board?.myNodeNum == packet.from) return // our own broadcast echoed back (belt-and-suspenders)
        _packets.tryEmit(
            ReceivedPacket(
                from = packet.from,
                to = packet.to,
                id = packet.id,
                channelIndex = packet.channel,
                portnum = data.portnum,
                payload = data.payload,
                rxSnr = packet.rxSnr,
                rxRssi = packet.rxRssi,
                hopsAway = packet.hopsAway,
            ),
        )
    }

    /** The handshake streams the whole NodeDB; only the board's own entry carries *its* battery. */
    private fun onNodeInfo(info: FromRadio.NodeInfo) {
        if (info.num == board?.myNodeNum) info.metrics?.let(::onSelfMetrics)
    }

    private fun onSelfMetrics(metrics: DeviceMetrics) {
        _battery.value = BoardBattery.of(metrics.batteryLevel, metrics.voltage)
    }

    private fun routeNak(data: MeshData) {
        val reason = MeshtasticProto.decodeRouting(data.payload) ?: RoutingError.UNKNOWN
        val waiter = pending.remove(data.requestId)
        if (waiter != null) {
            waiter.complete(SendResult.Nak(data.requestId, reason))
        } else {
            _outcomes.tryEmit(PacketOutcome(data.requestId, reason))
        }
    }

    private fun onQueueStatus(
        qs: FromRadio.QueueStatus,
        awaitId: UInt?,
    ): Signal {
        _queue.value = QueueInfo(qs.free, qs.maxlen, now())
        val id = qs.meshPacketId
        val waiter = if (id != 0u) pending.remove(id) else null
        if (waiter != null) {
            waiter.complete(
                if (qs.res != 0) SendResult.Rejected(id, qs.res) else SendResult.Queued(id, QueueInfo(qs.free, qs.maxlen, now())),
            )
        }
        return if (awaitId != null && id == awaitId) Signal.MatchedAwait else Signal.Continue
    }

    // --- sending ---

    private suspend fun doSend(
        address: String,
        channel: GattChannel,
        cmd: Cmd.Send,
    ): SessionEnd? {
        if (_queue.value?.free == 0) {
            cmd.reply.complete(SendResult.Busy)
            return null
        }
        val id = ids.next()
        pending[id] = cmd.reply
        val packet =
            OutboundPacket(channelIndex = cmd.channelIndex, id = id, portnum = cmd.portnum, payload = cmd.payload, hopLimit = cmd.hopLimit)
        when (val write = channel.writeToRadio(MeshtasticProto.encodePacket(packet), WRITE_TIMEOUT_MS)) {
            is GattResult.Ok -> {
                lastWriteAt = now()
            }

            else -> {
                pending.remove(id)
                cmd.reply.complete(SendResult.NotReady(_state.value))
                return classify(address, write) ?: SessionEnd(reason = "send write $write")
            }
        }
        val end = drain(address, channel, awaitId = id)
        // Still pending after the drain (no matching queueStatus came back) → time it out; the transport retries.
        pending.remove(id)?.complete(SendResult.Timeout)
        return end
    }

    // --- channel provisioning (an AdminMessage to the local node, over portnum ADMIN) ---

    /**
     * Runs one [ProvisionSpec] against the board: GET a session passkey, then begin→writes→commit echoing it.
     * The commit reboots the board to apply the edit, so on success this ends the session (resetting the
     * backoff) for a fresh handshake that reloads the channel table. Runs inside the actor, so its GATT ops
     * stay serialized with sends.
     */
    private suspend fun runProvision(
        channel: GattChannel,
        cmd: Cmd.Provision,
    ): SessionEnd? {
        val myNode = board?.myNodeNum
        if (_state.value !is LinkState.Ready || myNode == null) {
            cmd.reply.complete(ProvisionResult.NotReady(_state.value))
            return null
        }
        return when (cmd.spec.mode) {
            ProvisionMode.Rendezvous -> runRendezvous(channel, myNode, cmd)
            ProvisionMode.Dedicate -> runDedicate(channel, myNode, cmd)
            ProvisionMode.Restore -> runRestore(channel, myNode, cmd)
        }
    }

    /** The original write: Knit into a free secondary slot, the board's own primary left exactly as it is. */
    private suspend fun runRendezvous(
        channel: GattChannel,
        myNode: UInt,
        cmd: Cmd.Provision,
    ): SessionEnd? {
        // A dedicated board already carries Knit as its primary; writing a second copy would put two
        // channels with the same name+PSK — and so the same hash — on the air.
        channels.firstOrNull { it.name == cmd.spec.name }?.let { existing ->
            log("lora provision reuse ch${existing.index} '${cmd.spec.name}'")
            cmd.reply.complete(ProvisionResult.Provisioned(existing.index, alreadyPresent = true))
            return null
        }
        val slot = freeSecondarySlot()
        if (slot == null) {
            cmd.reply.complete(ProvisionResult.NoFreeSlot)
            return null
        }
        val write = ChannelWrite(index = slot, name = cmd.spec.name, psk = cmd.spec.psk)
        return applySteps(
            channel = channel,
            myNode = myNode,
            steps = listOf(channelStep(write)),
            index = slot,
            label = "wrote ch$slot '${cmd.spec.name}'",
            reply = cmd.reply,
        )
    }

    /**
     * Hands the whole board to Knit (ADR 045): Knit becomes the **primary**, which is what moves the radio
     * onto a Knit-derived RF slot, any earlier Knit secondary is disabled so only one channel carries the
     * name, and the three housekeeping intervals are stretched.
     *
     * Every config write is a read-modify-write — the firmware assigns the whole sub-config, so a write
     * built from scratch would silently reset `role`, `rebroadcast_mode`, `gps_mode` and everything else this
     * codec does not model. The reads therefore run **before** `begin_edit_settings`, and a read that fails
     * aborts with nothing written.
     */
    private suspend fun runDedicate(
        channel: GattChannel,
        myNode: UInt,
        cmd: Cmd.Provision,
    ): SessionEnd? {
        channels.firstOrNull { it.index == PRIMARY_INDEX && it.name == cmd.spec.name }?.let {
            log("lora provision already dedicated ch$PRIMARY_INDEX '${cmd.spec.name}'")
            cmd.reply.complete(ProvisionResult.Provisioned(PRIMARY_INDEX, alreadyPresent = true))
            return null
        }
        val raws = readBoardConfigs(channel, myNode)
        if (raws == null) {
            cmd.reply.complete(ProvisionResult.Failed("board did not return its config"))
            return null
        }
        val steps =
            buildList {
                add(
                    channelStep(
                        ChannelWrite(
                            index = PRIMARY_INDEX,
                            name = cmd.spec.name,
                            psk = cmd.spec.psk,
                            role = MeshtasticProto.ROLE_PRIMARY,
                            positionPrecision = MeshtasticProto.POSITION_PRECISION_NONE,
                        ),
                    ),
                )
                channels
                    .filter { it.index != PRIMARY_INDEX && it.name == cmd.spec.name }
                    .forEach { add(channelStep(ChannelWrite(it.index, name = "", psk = ByteArray(0), role = ROLE_DISABLED))) }
                raws.forEach { (config, raw) ->
                    val spliced = spliceVarintFields(raw, BoardQuiet.quiet(config))
                    if (spliced == null) {
                        cmd.reply.complete(ProvisionResult.Failed("board sent a malformed ${config.name} config"))
                        return null
                    }
                    add(configStep(config, spliced))
                }
            }
        return applySteps(
            channel = channel,
            myNode = myNode,
            steps = steps,
            index = PRIMARY_INDEX,
            label = "dedicated ch$PRIMARY_INDEX '${cmd.spec.name}'",
            reply = cmd.reply,
            previous = BoardQuiet.recorded(raws),
        )
    }

    /**
     * Undoes [runDedicate]: the primary goes back to the stock public channel — an empty name makes the
     * firmware fall back to the modem-preset name, which is the frequency slot the board shipped on — Knit
     * moves down to a secondary so the plane keeps working, and the intervals return to
     * [ProvisionSpec.previous] (the board's own values, recorded at dedicate time).
     */
    private suspend fun runRestore(
        channel: GattChannel,
        myNode: UInt,
        cmd: Cmd.Provision,
    ): SessionEnd? {
        val raws = readBoardConfigs(channel, myNode)
        if (raws == null) {
            cmd.reply.complete(ProvisionResult.Failed("board did not return its config"))
            return null
        }
        val slot = channels.firstOrNull { it.name == cmd.spec.name && it.index != PRIMARY_INDEX }?.index ?: freeSecondarySlot()
        if (slot == null) {
            cmd.reply.complete(ProvisionResult.NoFreeSlot)
            return null
        }
        val steps =
            buildList {
                add(
                    channelStep(
                        ChannelWrite(
                            index = PRIMARY_INDEX,
                            name = "",
                            psk = MeshtasticProto.DEFAULT_PSK,
                            role = MeshtasticProto.ROLE_PRIMARY,
                        ),
                    ),
                )
                add(channelStep(ChannelWrite(index = slot, name = cmd.spec.name, psk = cmd.spec.psk)))
                raws.forEach { (config, raw) ->
                    val spliced = spliceVarintFields(raw, BoardQuiet.restore(config, cmd.spec.previous))
                    if (spliced == null) {
                        cmd.reply.complete(ProvisionResult.Failed("board sent a malformed ${config.name} config"))
                        return null
                    }
                    add(configStep(config, spliced))
                }
            }
        return applySteps(
            channel = channel,
            myNode = myNode,
            steps = steps,
            index = slot,
            label = "restored the stock primary, Knit at ch$slot",
            reply = cmd.reply,
        )
    }

    /** Reads every sub-config the quieting touches, as raw bytes; null if the board fails to return one. */
    private suspend fun readBoardConfigs(
        channel: GattChannel,
        myNode: UInt,
    ): Map<BoardConfig, ByteArray>? {
        val out = LinkedHashMap<BoardConfig, ByteArray>()
        for (config in BoardConfig.entries) {
            val reply = adminRequest(channel, myNode, MeshtasticProto.encodeAdminGetConfig(config)) ?: return null
            val raw = reply.config?.takeIf { it.config == config }?.raw ?: return null
            out[config] = raw
        }
        return out
    }

    private fun channelStep(write: ChannelWrite): AdminStep = { passkey -> MeshtasticProto.encodeAdminSetChannel(write, passkey) }

    private fun configStep(
        config: BoardConfig,
        raw: ByteArray,
    ): AdminStep = { passkey -> MeshtasticProto.encodeAdminSetConfig(config, raw, passkey) }

    /** The lowest secondary index (1..7) not already holding a live channel, or null when all are taken. */
    private fun freeSecondarySlot(): Int? {
        val used = channels.filter { it.role != ROLE_DISABLED && it.name.isNotEmpty() }.map { it.index }.toSet()
        return (FIRST_SECONDARY..LAST_SECONDARY).firstOrNull { it !in used }
    }

    private suspend fun applySteps(
        channel: GattChannel,
        myNode: UInt,
        steps: List<AdminStep>,
        index: Int,
        label: String,
        reply: CompletableDeferred<ProvisionResult>,
        previous: BoardIntervals? = null,
    ): SessionEnd? {
        var outcome = writeSteps(channel, myNode, adminGet(channel, myNode)?.passkey, steps)
        if (outcome == AdminOutcome.BadSessionKey) {
            outcome = writeSteps(channel, myNode, adminGet(channel, myNode)?.passkey, steps) // one fresh-key retry
        }
        return when (outcome) {
            AdminOutcome.Applied -> {
                log("lora provision $label")
                reply.complete(ProvisionResult.Provisioned(index, alreadyPresent = false, previous = previous))
                SessionEnd(reason = "provisioned", resetStreak = true) // the commit reboots; reconnect reloads channels
            }

            AdminOutcome.BadSessionKey -> {
                reply.complete(ProvisionResult.Failed("admin session key rejected"))
                null
            }

            AdminOutcome.Failed -> {
                reply.complete(ProvisionResult.Failed("board refused the channel write"))
                null
            }
        }
    }

    /**
     * begin_edit → every [steps] write → commit_edit, each echoing [passkey]. One transaction so the board's
     * implicit save-and-reboot happens once, at the commit, no matter how many settings the mode rewrites.
     */
    private suspend fun writeSteps(
        channel: GattChannel,
        myNode: UInt,
        passkey: ByteArray?,
        steps: List<AdminStep>,
    ): AdminOutcome {
        val begin = writeAdmin(channel, myNode, MeshtasticProto.encodeAdminBeginEdit(passkey))
        if (begin == AdminOutcome.BadSessionKey) return begin
        for (step in steps) {
            val outcome = writeAdmin(channel, myNode, step(passkey))
            if (outcome != AdminOutcome.Applied) return outcome
        }
        // commit triggers the implicit save+reboot: the routing reply may never arrive, so don't wait on it.
        writeAdmin(channel, myNode, MeshtasticProto.encodeAdminCommitEdit(passkey), expectReply = false)
        return AdminOutcome.Applied
    }

    /** Sends `get_channel_request(0)` to the local node and returns the reply carrying a fresh session passkey. */
    private suspend fun adminGet(
        channel: GattChannel,
        myNode: UInt,
    ): AdminReply? = adminRequest(channel, myNode, MeshtasticProto.encodeAdminGetChannel(0))

    /** One admin read addressed to the local node: write it, then wait for the matching admin reply. */
    private suspend fun adminRequest(
        channel: GattChannel,
        myNode: UInt,
        payload: ByteArray,
    ): AdminReply? {
        val id = ids.next()
        val packet =
            OutboundPacket(
                to = myNode,
                channelIndex = 0,
                id = id,
                portnum = MeshtasticProto.PORT_ADMIN,
                payload = payload,
                wantResponse = true,
            )
        if (channel.writeToRadio(MeshtasticProto.encodePacket(packet), WRITE_TIMEOUT_MS) !is GattResult.Ok) return null
        return (awaitAdminResponse(channel, id, now() + ADMIN_TIMEOUT_MS) as? AdminResp.Admin)?.reply
    }

    /** Writes one admin message to [myNode]; when [expectReply], classifies the routing reply (ack / bad key / error). */
    private suspend fun writeAdmin(
        channel: GattChannel,
        myNode: UInt,
        adminBytes: ByteArray,
        expectReply: Boolean = true,
    ): AdminOutcome {
        val id = ids.next()
        val packet =
            OutboundPacket(
                to = myNode,
                channelIndex = 0,
                id = id,
                portnum = MeshtasticProto.PORT_ADMIN,
                payload = adminBytes,
                wantResponse = expectReply,
            )
        if (channel.writeToRadio(MeshtasticProto.encodePacket(packet), WRITE_TIMEOUT_MS) !is GattResult.Ok) return AdminOutcome.Failed
        if (!expectReply) return AdminOutcome.Applied
        return when (val resp = awaitAdminResponse(channel, id, now() + ADMIN_TIMEOUT_MS)) {
            is AdminResp.Routing -> {
                when (resp.reason) {
                    RoutingError.ADMIN_BAD_SESSION_KEY -> AdminOutcome.BadSessionKey
                    RoutingError.NONE -> AdminOutcome.Applied
                    else -> AdminOutcome.Failed
                }
            }

            // Local admin often sends no routing ack; a reboot, an admin echo, or a quiet drain all mean "processed".
            else -> {
                AdminOutcome.Applied
            }
        }
    }

    /**
     * Reads FromRadio (waiting on FromNum notifies) until a packet addressed to us settles the admin request
     * [reqId]: an ADMIN reply, the matching ROUTING outcome, a reboot, or the deadline. Other traffic seen in
     * the window is dropped — provisioning is a rare, brief, user-initiated action.
     */
    private suspend fun awaitAdminResponse(
        channel: GattChannel,
        reqId: UInt,
        deadlineMs: Long,
    ): AdminResp {
        while (now() < deadlineMs) {
            when (val read = channel.readFromRadio(READ_TIMEOUT_MS)) {
                is GattResult.Ok -> {
                    if (read.value.isEmpty()) {
                        val wait = (deadlineMs - now()).coerceAtLeast(0)
                        when (withTimeoutOrNull(wait) { channel.events.receiveCatching().getOrNull() }) {
                            is GattEvent.Notified -> Unit

                            // more to read
                            else -> return AdminResp.None // disconnect, closed, or deadline
                        }
                    } else {
                        matchAdminResponse(read.value, reqId)?.let { return it }
                    }
                }

                else -> {
                    return AdminResp.None
                }
            }
        }
        return AdminResp.None
    }

    /** Classifies one FromRadio against admin request [reqId]; null means "not the reply — keep reading". */
    private fun matchAdminResponse(
        bytes: ByteArray,
        reqId: UInt,
    ): AdminResp? =
        when (val fr = MeshtasticProto.decodeFromRadio(bytes)) {
            FromRadio.Rebooted -> {
                AdminResp.Reboot
            }

            is FromRadio.QueueStatus -> {
                _queue.value = QueueInfo(fr.free, fr.maxlen, now())
                null
            }

            is FromRadio.Packet -> {
                val data = fr.packet.decoded
                when {
                    data == null -> {
                        null
                    }

                    data.portnum == MeshtasticProto.PORT_ADMIN -> {
                        AdminResp.Admin(MeshtasticProto.decodeAdmin(data.payload) ?: AdminReply(null, null))
                    }

                    data.portnum == MeshtasticProto.PORT_ROUTING && data.requestId == reqId -> {
                        AdminResp.Routing(MeshtasticProto.decodeRouting(data.payload) ?: RoutingError.UNKNOWN)
                    }

                    else -> {
                        null
                    }
                }
            }

            else -> {
                null
            }
        }

    private enum class AdminOutcome { Applied, BadSessionKey, Failed }

    private sealed interface AdminResp {
        data class Admin(
            val reply: AdminReply,
        ) : AdminResp

        data class Routing(
            val reason: RoutingError,
        ) : AdminResp

        data object Reboot : AdminResp

        data object None : AdminResp
    }

    // --- heartbeat ---

    private suspend fun heartbeatTicker() {
        while (scope.isActive) {
            delay(HEARTBEAT_MS)
            inbox.send(Cmd.Heartbeat)
        }
    }

    private suspend fun maybeHeartbeat(
        address: String,
        channel: GattChannel,
    ): SessionEnd? {
        if (now() - lastWriteAt < HEARTBEAT_MS) return null
        return when (val write = channel.writeToRadio(MeshtasticProto.encodeHeartbeat(), WRITE_TIMEOUT_MS)) {
            is GattResult.Ok -> {
                lastWriteAt = now()
                null
            }

            else -> {
                classify(address, write) ?: SessionEnd(reason = "heartbeat write $write")
            }
        }
    }

    // --- classification + housekeeping ---

    /** Turns a failed [GattResult] into a terminal pairing state where appropriate, else null (backoff). */
    private fun classify(
        address: String,
        result: GattResult<*>,
    ): SessionEnd? {
        if (result is GattResult.Ok) return null
        val bonded = dialer.bondState(address) == BondState.BONDED
        return when {
            result is GattResult.Failed && (result.status == GATT_AUTH_FAIL) -> {
                SessionEnd(reason = "auth", terminal = if (bonded) LinkState.StaleBond(address) else LinkState.NeedsPairing(address))
            }

            result is GattResult.Failed && (result.status == GATT_INSUFFICIENT_AUTH || result.status == GATT_INSUFFICIENT_ENC) -> {
                SessionEnd(
                    reason = "auth ${result.status}",
                    terminal = if (bonded) LinkState.StaleBond(address) else LinkState.NeedsPairing(address),
                )
            }

            else -> {
                null
            }
        }
    }

    private fun failAllPending(result: SendResult) {
        pending.values.forEach { it.complete(result) }
        pending.clear()
    }

    /** The result of a session: why it ended, whether to keep retrying, and whether the streak should reset. */
    private class SessionEnd(
        val reason: String,
        val terminal: LinkState? = null,
        val resetStreak: Boolean = false,
    )

    private enum class Signal { Continue, Rehandshake, MatchedAwait }

    private sealed interface Cmd {
        class Send(
            val channelIndex: Int,
            val portnum: Int,
            val hopLimit: Int?,
            val payload: ByteArray,
            val reply: CompletableDeferred<SendResult>,
        ) : Cmd

        class Provision(
            val spec: ProvisionSpec,
            val reply: CompletableDeferred<ProvisionResult>,
        ) : Cmd

        data object Heartbeat : Cmd
    }

    private companion object {
        const val BASE_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 180_000L
        const val SUBSCRIBE_TIMEOUT_MS = 10_000L
        const val WRITE_TIMEOUT_MS = 10_000L
        const val BONDING_TIMEOUT_MS = 90_000L
        const val READ_TIMEOUT_MS = 30_000L
        const val HANDSHAKE_TIMEOUT_MS = 120_000L
        const val HEARTBEAT_MS = 180_000L
        const val ADMIN_TIMEOUT_MS = 8_000L
        const val MAX_STALE_READS = 32

        // Channel provisioning: index 0 is the board's primary. The rendezvous write takes a free secondary
        // (1..7) and leaves it alone; the dedicate write claims it, which is what moves the RF slot (ADR 045).
        const val PRIMARY_INDEX = 0
        const val FIRST_SECONDARY = 1
        const val LAST_SECONDARY = 7
        const val ROLE_DISABLED = 0
        const val PACKET_BUFFER = 256
        const val OUTCOME_BUFFER = 64

        // Android BluetoothGatt status codes classified as a bond problem (values match the platform).
        const val GATT_INSUFFICIENT_AUTH = 5
        const val GATT_INSUFFICIENT_ENC = 15
        const val GATT_AUTH_FAIL = 137

        val BLANK_BOARD = BoardInfo(0u, null, null)
    }
}
