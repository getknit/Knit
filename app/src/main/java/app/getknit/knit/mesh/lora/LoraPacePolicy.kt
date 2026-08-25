package app.getknit.knit.mesh.lora

/**
 * The outbound pacer for the LoRa plane: a bounded queue with a minimum inter-packet gap and a NAK
 * back-off, all pure and clock-driven by the caller (the transport owns the actual `delay`). LoRa is a
 * ~1 kbps shared medium — a 233-B packet is ~2.5 s on air and the board floods each one three hops — so
 * unpaced sends would swamp it and draw duty-cycle refusals.
 *
 * The queue drops the **oldest whole frame** when full (never a lone fragment, which would strand a
 * half-delivered message), and a rate/duty-cycle NAK widens the gap for a cool-down window. A profile
 * frame — the key bootstrap — is never dropped for pacing reasons by the transport; the pacer itself is
 * frame-agnostic. Tested on the JVM ([app.getknit.knit.mesh.lora.LoraPacePolicyTest]).
 */
internal class LoraPacePolicy(
    private val minGapMs: Long = MIN_GAP_MS,
    private val queueCap: Int = QUEUE_CAP_FRAMES,
    private val nakBackoffMs: Long = NAK_BACKOFF_MS,
) {
    private val queue = ArrayDeque<OutboundFrame>()
    private var lastSentAt = Long.MIN_VALUE
    private var boardFree: Int? = null
    private var nakUntil = 0L

    /** How a frame was admitted — [DROPPED_OLDEST] means the queue was full and its oldest frame was evicted. */
    enum class Admission { ACCEPTED, DROPPED_OLDEST }

    val pending: Int get() = queue.size

    fun enqueue(frame: OutboundFrame): Admission {
        val dropped = queue.size >= queueCap
        if (dropped) queue.removeFirst()
        queue.addLast(frame)
        return if (dropped) Admission.DROPPED_OLDEST else Admission.ACCEPTED
    }

    /** The earliest time the next frame may go out (min gap since the last send, and any NAK cool-down). */
    fun nextDueAt(): Long = maxOf(if (lastSentAt == Long.MIN_VALUE) 0L else lastSentAt + minGapMs, nakUntil)

    /**
     * The next frame to send, or null when the queue is empty, the gap/cool-down has not elapsed, or the
     * board has no queue headroom. Stamps the send time so the next frame waits a full gap.
     */
    fun take(now: Long): OutboundFrame? {
        if (queue.isEmpty()) return null
        if (now < nextDueAt()) return null
        if (boardFree == 0) return null
        lastSentAt = now
        return queue.removeFirst()
    }

    fun onQueueStatus(free: Int) {
        boardFree = free
    }

    /** A rate-limit or duty-cycle NAK widens the gap for a cool-down; other NAKs don't pace. */
    fun onNak(
        reason: RoutingError,
        now: Long,
    ) {
        if (reason == RoutingError.RATE_LIMIT_EXCEEDED || reason == RoutingError.DUTY_CYCLE_LIMIT) {
            nakUntil = now + nakBackoffMs
        }
    }

    private companion object {
        const val MIN_GAP_MS = 3_000L
        const val QUEUE_CAP_FRAMES = 12
        const val NAK_BACKOFF_MS = 60_000L
    }
}

/** A whole frame queued for the LoRa hop: its already-encoded fragment messages plus a diagnostic label. */
internal class OutboundFrame(
    val messages: List<ByteArray>,
    val label: String,
) {
    val fragmented: Boolean get() = messages.size > 1
}
