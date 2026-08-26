package app.getknit.knit.mesh.lora

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A tiny in-memory LoRa "air": every registered [FakeMeshtasticLink] floods each send to every OTHER
 * registered link (a board never echoes the phone's own packet back), so two [LoraMeshTransport]s can be
 * exercised end-to-end on the JVM with no radio and no GATT. Single-threaded by test contract.
 */
internal class FakeMeshtasticAir {
    private val links = mutableListOf<FakeMeshtasticLink>()
    var lossy: (from: UInt, to: UInt) -> Boolean = { _, _ -> false }

    fun register(link: FakeMeshtasticLink) {
        links += link
    }

    fun unregister(link: FakeMeshtasticLink) {
        links -= link
    }

    fun broadcast(
        from: UInt,
        channelIndex: Int,
        portnum: Int,
        payload: ByteArray,
    ) {
        links
            .filter { it.nodeNum != from && !lossy(from, it.nodeNum) }
            .forEach { it.deliver(from, channelIndex, portnum, payload) }
    }
}

/** A [MeshtasticLink] backed by [FakeMeshtasticAir]; goes Ready on start and floods sends to the air. */
internal class FakeMeshtasticLink(
    val nodeNum: UInt,
    private val air: FakeMeshtasticAir,
    private val channelName: String = KnitChannel.NAME,
) : MeshtasticLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state = _state

    private val _packets = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = 256)
    override val packets = _packets

    private val _outcomes = MutableSharedFlow<PacketOutcome>(extraBufferCapacity = 64)
    override val outcomes = _outcomes

    private val _queue = MutableStateFlow<QueueInfo?>(QueueInfo(free = 16, maxlen = 16, atMs = 0))
    override val queue = _queue

    private val _rxQuality = MutableStateFlow<RxQuality?>(null)
    override val rxQuality = _rxQuality

    override val battery = MutableStateFlow<BoardBattery?>(null)

    var free = 16
    private var nextId = 1u
    val sent = mutableListOf<ByteArray>()

    override suspend fun send(
        payload: ByteArray,
        channelIndex: Int,
        portnum: Int,
        hopLimit: Int?,
    ): SendResult {
        if (free == 0) return SendResult.Busy
        sent += payload
        val id = nextId++
        air.broadcast(nodeNum, channelIndex, portnum, payload)
        return SendResult.Queued(id, QueueInfo(free, 16, 0))
    }

    /** What [provisionChannel] returns; a test can script a different outcome. */
    var provisionResult: ProvisionResult = ProvisionResult.Provisioned(index = 1, alreadyPresent = false)
    val provisioned = mutableListOf<ProvisionSpec>()

    override suspend fun provisionChannel(spec: ProvisionSpec): ProvisionResult {
        provisioned += spec
        return provisionResult
    }

    override fun start(address: String) {
        _state.value = LinkState.Ready(BoardInfo(nodeNum, "heltec-v4", "2.5.0"), listOf(ChannelInfo(0, channelName, 1)), 512)
        air.register(this)
    }

    override fun stop() {
        air.unregister(this)
        _state.value = LinkState.Idle
    }

    /** A packet arriving from the air (another board's broadcast). */
    fun deliver(
        from: UInt,
        channelIndex: Int,
        portnum: Int,
        payload: ByteArray,
    ) {
        _packets.tryEmit(
            ReceivedPacket(
                from = from,
                to = MeshtasticProto.BROADCAST,
                id = nextId++,
                channelIndex = channelIndex,
                portnum = portnum,
                payload = payload,
                rxSnr = 6.5f,
                rxRssi = -85,
                hopsAway = 0,
            ),
        )
    }

    fun emitNak(
        id: UInt,
        reason: RoutingError,
    ) {
        _outcomes.tryEmit(PacketOutcome(id, reason))
    }

    fun updateHeadroom(value: Int) {
        free = value
        _queue.value = QueueInfo(value, 16, 0)
    }

    fun drop() {
        _state.value = LinkState.Disconnected("test", retryAtMs = 0, streak = 1)
    }
}
