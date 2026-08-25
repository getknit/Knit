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
 * The pure state machine that turns a [MeshtasticGattDialer] into a managed [MeshtasticLink]. An actor:
 * a single driver coroutine owns the open [GattChannel] and issues every GATT op sequentially, fed by one
 * inbox [Channel] that merges send commands, GATT events, and heartbeat ticks. It handles the config
 * handshake, the drain-until-empty read on every FromNum, the keep-alive heartbeat, packet-id/queueStatus
 * correlation, and reconnect-with-backoff — all against injected `now`/`rand`/`nonce`, so it runs on the
 * JVM under virtual time ([app.getknit.knit.mesh.lora.MeshtasticSessionTest]).
 *
 * Android-free by construction (the only `android.bluetooth.*` lives behind the dialer seam), honouring
 * `.agents/rules/mesh.md`.
 */
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
        _state.value = LinkState.Ready(requireNotNull(board), channels, mtu)
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
                    when (val fr = MeshtasticProto.decodeFromRadio(read.value)) {
                        is FromRadio.ConfigComplete -> if (fr.id == nonce) return null else Unit
                        is FromRadio.MyInfo -> board = BoardInfo(fr.myNodeNum, fr.pioEnv, board?.firmwareVersion)
                        is FromRadio.Metadata -> board = (board ?: BLANK_BOARD).copy(firmwareVersion = fr.firmwareVersion)
                        is FromRadio.Channel -> channels = channels + fr.channel
                        is FromRadio.NodeInfo -> onNodeInfo(fr)
                        else -> Unit
                    }
                }

                else -> {
                    return classify(address, read) ?: SessionEnd(reason = "handshake read $read")
                }
            }
        }
        return SessionEnd(reason = "handshake timeout")
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
     * Writes [Cmd.Provision.spec] as a **secondary** channel: reuse an existing same-named one, else pick a
     * free slot, GET a session passkey, then begin→set→commit echoing it. The commit reboots the board to
     * apply the edit, so on success this ends the session (resetting the backoff) for a fresh handshake that
     * reloads the channel table. Runs inside the actor, so its GATT ops stay serialized with sends.
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
        channels.firstOrNull { it.name == cmd.spec.name && it.index != PRIMARY_INDEX }?.let { existing ->
            log("lora provision reuse ch${existing.index} '${cmd.spec.name}'")
            cmd.reply.complete(ProvisionResult.Provisioned(existing.index, alreadyPresent = true))
            return null
        }
        val slot = freeSecondarySlot()
        if (slot == null) {
            cmd.reply.complete(ProvisionResult.NoFreeSlot)
            return null
        }
        return applyChannel(channel, myNode, ChannelWrite(index = slot, name = cmd.spec.name, psk = cmd.spec.psk), cmd.reply)
    }

    /** The lowest secondary index (1..7) not already holding a live channel, or null when all are taken. */
    private fun freeSecondarySlot(): Int? {
        val used = channels.filter { it.role != ROLE_DISABLED && it.name.isNotEmpty() }.map { it.index }.toSet()
        return (FIRST_SECONDARY..LAST_SECONDARY).firstOrNull { it !in used }
    }

    private suspend fun applyChannel(
        channel: GattChannel,
        myNode: UInt,
        write: ChannelWrite,
        reply: CompletableDeferred<ProvisionResult>,
    ): SessionEnd? {
        var outcome = writeChannel(channel, myNode, adminGet(channel, myNode)?.passkey, write)
        if (outcome == AdminOutcome.BadSessionKey) {
            outcome = writeChannel(channel, myNode, adminGet(channel, myNode)?.passkey, write) // one fresh-key retry
        }
        return when (outcome) {
            AdminOutcome.Applied -> {
                log("lora provision wrote ch${write.index} '${write.name}'")
                reply.complete(ProvisionResult.Provisioned(write.index, alreadyPresent = false))
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

    /** begin_edit → set_channel → commit_edit, each echoing [passkey]; the transaction saves+reboots at commit. */
    private suspend fun writeChannel(
        channel: GattChannel,
        myNode: UInt,
        passkey: ByteArray?,
        write: ChannelWrite,
    ): AdminOutcome {
        val begin = writeAdmin(channel, myNode, MeshtasticProto.encodeAdminBeginEdit(passkey))
        if (begin == AdminOutcome.BadSessionKey) return begin
        val set = writeAdmin(channel, myNode, MeshtasticProto.encodeAdminSetChannel(write, passkey))
        if (set != AdminOutcome.Applied) return set
        // commit triggers the implicit save+reboot: the routing reply may never arrive, so don't wait on it.
        writeAdmin(channel, myNode, MeshtasticProto.encodeAdminCommitEdit(passkey), expectReply = false)
        return AdminOutcome.Applied
    }

    /** Sends `get_channel_request(0)` to the local node and returns the reply carrying a fresh session passkey. */
    private suspend fun adminGet(
        channel: GattChannel,
        myNode: UInt,
    ): AdminReply? {
        val id = ids.next()
        val packet =
            OutboundPacket(
                to = myNode,
                channelIndex = 0,
                id = id,
                portnum = MeshtasticProto.PORT_ADMIN,
                payload = MeshtasticProto.encodeAdminGetChannel(0),
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

        // Channel provisioning: index 0 is the board's primary; Knit writes into a free secondary (1..7).
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
