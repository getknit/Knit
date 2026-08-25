package app.getknit.knit.mesh.lora

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What [app.getknit.knit.mesh.lora.LoraMeshTransport] consumes: a managed link to one Meshtastic board.
 * The link owns connection lifecycle (dial, bond-on-demand, the config handshake, the keep-alive
 * heartbeat, and reconnect-with-backoff), surfaces inbound packets and back-pressure evidence, and
 * accepts one packet at a time to send. **Pacing is the transport's job** — the link never sleeps
 * between sends; it only reports what the board tells it (`queue` free/maxlen, `Busy`, NAKs).
 *
 * Implemented by [MeshtasticSession] (pure, over the [MeshtasticGattDialer] seam).
 */
internal interface MeshtasticLink {
    val state: StateFlow<LinkState>

    /** Inbound mesh packets, already decoded; `tryEmit`ted like the radio transports' `_inbound`. */
    val packets: SharedFlow<ReceivedPacket>

    /** Late NAKs (seconds after the send returned) keyed by our packet id — e.g. a duty-cycle refusal. */
    val outcomes: SharedFlow<PacketOutcome>

    /** The board's transmit-queue headroom from the latest `queueStatus`; null until first reported. */
    val queue: StateFlow<QueueInfo?>

    /** Last received signal quality — separate from [state] so it doesn't churn the state on every packet. */
    val rxQuality: StateFlow<RxQuality?>

    /** Enqueues one packet on the board. Returns synchronously once the board acknowledges (or refuses) it. */
    suspend fun send(
        payload: ByteArray,
        channelIndex: Int,
        portnum: Int = MeshtasticProto.PORT_PRIVATE_APP,
        hopLimit: Int? = null,
    ): SendResult

    /** (Re)connects to [address], retrying with backoff while started. Idempotent for the same address. */
    fun start(address: String)

    fun stop()
}

/** The link's lifecycle. Terminal states ([NeedsPairing], [StaleBond]) stop retrying until [MeshtasticLink.start]. */
internal sealed interface LinkState {
    data object Idle : LinkState

    data object Connecting : LinkState

    /** The stack is pairing (the board is showing its PIN); the first protected op waits this out. */
    data object Bonding : LinkState

    data class Handshaking(
        val board: BoardInfo?,
    ) : LinkState

    data class Ready(
        val board: BoardInfo,
        val channels: List<ChannelInfo>,
        val mtu: Int,
    ) : LinkState

    data class Disconnected(
        val reason: String,
        val retryAtMs: Long,
        val streak: Int,
    ) : LinkState

    /** The adapter is off (or absent) — nothing to do but wait for the user to turn Bluetooth on. */
    data object Unavailable : LinkState

    /** The board is bonded-away or never paired — the user must pair it in the picker; retries stop. */
    data class NeedsPairing(
        val address: String,
    ) : LinkState

    /** A stale bond the stack keeps rejecting — the user must forget the device in Settings and re-pair. */
    data class StaleBond(
        val address: String,
    ) : LinkState
}

/** Identity of the connected board, learned from `my_info`/`metadata` during the handshake. */
internal data class BoardInfo(
    val myNodeNum: UInt,
    val pioEnv: String?,
    val firmwareVersion: String?,
)

/** One inbound packet the board handed the phone. */
internal class ReceivedPacket(
    val from: UInt,
    val to: UInt,
    val id: UInt,
    val channelIndex: Int,
    val portnum: Int,
    val payload: ByteArray,
    val rxSnr: Float?,
    val rxRssi: Int?,
    val hopsAway: Int?,
)

/** The board's transmit-queue headroom. */
internal data class QueueInfo(
    val free: Int,
    val maxlen: Int,
    val atMs: Long,
)

/** Last-received signal quality, surfaced for the diagnostics/settings row. */
internal data class RxQuality(
    val snr: Float?,
    val rssi: Int?,
    val atMs: Long,
)

/** The synchronous outcome of a [MeshtasticLink.send]. */
internal sealed interface SendResult {
    data class Queued(
        val id: UInt,
        val queue: QueueInfo,
    ) : SendResult

    /** The mesh refused the packet immediately (e.g. NO_CHANNEL, TOO_LARGE) — folded in from the drain. */
    data class Nak(
        val id: UInt,
        val reason: RoutingError,
    ) : SendResult

    /** The board's `queueStatus.res` was non-zero (a firmware error code). */
    data class Rejected(
        val id: UInt,
        val res: Int,
    ) : SendResult

    /** No headroom (`queue.free == 0`) — nothing was written; the transport paces and retries. */
    data object Busy : SendResult

    /** Larger than [MeshtasticProto.MAX_PAYLOAD]; refused locally, never written. */
    data object TooLarge : SendResult

    data class NotReady(
        val state: LinkState,
    ) : SendResult

    /** The write went out but no correlated `queueStatus` came back in time. */
    data object Timeout : SendResult
}

/** A NAK that arrived after [MeshtasticLink.send] had already returned. */
internal data class PacketOutcome(
    val id: UInt,
    val reason: RoutingError,
)

/** The board + channel the LoRa plane is bound to, derived from settings; null means the plane is off. */
internal data class LoraConfig(
    val address: String,
    val channelIndex: Int,
)
