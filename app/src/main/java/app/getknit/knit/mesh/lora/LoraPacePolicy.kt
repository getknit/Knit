package app.getknit.knit.mesh.lora

/**
 * The outbound pacer for the LoRa plane: a bounded queue with a minimum inter-packet gap and a NAK
 * back-off, all pure and clock-driven by the caller (the transport owns the actual `delay`). LoRa is a
 * ~1 kbps shared medium — a 233-B packet is ~2.5 s on air and the board floods each one three hops — so
 * unpaced sends would swamp it and draw duty-cycle refusals.
 *
 * The queue sheds by **class** when full ([FrameClass]): from the lowest class present — the newcomer
 * included, so a frame that is alone at the bottom yields instead of evicting anything — and the **oldest
 * whole frame** within it (never a lone fragment, which would strand a half-delivered message). So a room
 * post never evicts a DM, and nothing evicts the profile bootstrap. A rate/duty-cycle NAK widens the gap for
 * a cool-down window, and since ADR 044 an injected [LoraAirtime] holds an hourly budget the queue is drained
 * against — so a frame can also wait because the plane has spent its share of the medium, not just because
 * the gap has not elapsed. Dequeue runs the same class order forwards (see [take]). Tested on the JVM
 * ([app.getknit.knit.mesh.lora.LoraPacePolicyTest]).
 */
internal class LoraPacePolicy(
    private val minGapMs: Long = MIN_GAP_MS,
    private val queueCap: Int = QUEUE_CAP_FRAMES,
    private val nakBackoffMs: Long = NAK_BACKOFF_MS,
    /** The airtime ledger; a frame is only taken if its whole cost fits its bucket's rolling budget. */
    val airtime: LoraAirtime = LoraAirtime(),
) {
    private val queue = ArrayDeque<OutboundFrame>()
    private var lastSentAt = Long.MIN_VALUE
    private var boardFree: Int? = null
    private var nakUntil = 0L

    /** Frames left in the queue that the airtime budget refused on the last [take]; diagnostics only. */
    var lastAirtimeRefusals = 0
        private set

    /**
     * How a frame was admitted: [DROPPED_OLDEST] means the queue was full and the oldest frame of the lowest
     * class present was evicted to make room; [REFUSED] means the newcomer itself was that lowest class, alone,
     * and was dropped instead (everything queued outranks it).
     */
    enum class Admission { ACCEPTED, DROPPED_OLDEST, REFUSED }

    val pending: Int get() = queue.size

    fun enqueue(frame: OutboundFrame): Admission {
        if (queue.size < queueCap) {
            queue.addLast(frame)
            return Admission.ACCEPTED
        }
        // Full: shed from the lowest class present, newcomer included (enum order = priority, highest first).
        val shed = maxOf(queue.maxOf { it.klass }, frame.klass)
        val victim = queue.indexOfFirst { it.klass == shed }
        if (victim < 0) return Admission.REFUSED // the newcomer alone is the lowest class: it yields
        queue.removeAt(victim)
        queue.addLast(frame)
        return Admission.DROPPED_OLDEST
    }

    /** The earliest time the next frame may go out (min gap since the last send, and any NAK cool-down). */
    fun nextDueAt(): Long = maxOf(if (lastSentAt == Long.MIN_VALUE) 0L else lastSentAt + minGapMs, nakUntil)

    /**
     * The next frame to send, or null when the queue is empty, the gap/cool-down has not elapsed, the board
     * has no queue headroom, or every queued frame is over its airtime budget. Stamps the send time so the
     * next frame waits a full gap.
     *
     * Dequeue is **by class, then FIFO within it** — the same order the queue sheds in, run forwards. ADR 039
     * left dequeue purely FIFO, which was right while everything on this plane was something a human had just
     * typed. The bridge (ADR 044) broke that assumption: gossip offers and backfill arrive in bursts nobody is
     * waiting for, and at a 3-second gap a burst of four puts a live message twelve seconds behind. Since the
     * class order already states what matters most, running it on the way out too costs nothing and stops the
     * background traffic from queue-jumping the foreground.
     *
     * A frame the airtime budget refuses is **skipped, not dropped** — it stays queued for a later window
     * while a frame from a bucket with headroom goes now. Without the skip, one refused BRIDGE frame would
     * block everything behind it until its budget recovered. A frame that never gets a window eventually ages
     * out through the ordinary class shedding instead.
     */
    fun take(now: Long): OutboundFrame? {
        if (queue.isEmpty()) return null
        if (now < nextDueAt()) return null
        if (boardFree == 0) return null
        var refused = 0
        var best = -1
        for (i in queue.indices) {
            val frame = queue[i]
            if (!airtime.admits(frame.bucket, frame.klass, frame.messages.map { it.size }, now)) {
                refused++
                continue
            }
            // Strictly-better only, so the earliest frame of the winning class keeps its place in line.
            if (best < 0 || frame.klass < queue[best].klass) best = i
        }
        lastAirtimeRefusals = refused
        if (best < 0) return null
        lastSentAt = now
        return queue.removeAt(best)
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

        // Raised from 12 with the bridge (ADR 044): backfill can enqueue a small burst behind live traffic,
        // and class shedding — not the cap — is what protects the live frames.
        const val QUEUE_CAP_FRAMES = 16
        const val NAK_BACKOFF_MS = 60_000L
    }
}

/**
 * The pacing class of a queued frame, highest first: the profile is the key bootstrap (nothing verifies
 * without it), the gossip offer is one packet that decides what the *next* several will be (dropping it
 * costs more air than sending it), a sealed DM outranks ambient room traffic, and the Nearby room is the
 * class the queue sheds first under airtime pressure.
 *
 * This is the **queue-shedding** order only. What a frame costs against the hourly budget is
 * [AirBucket], which is orthogonal: a backfilled DM keeps DM class here — so a room post cannot evict it —
 * while spending from the bridge budget there.
 */
internal enum class FrameClass { BOOTSTRAP, GOSSIP, DM, ROOM }

/** A whole frame queued for the LoRa hop: its already-encoded fragment messages, a diagnostic label, its class. */
internal class OutboundFrame(
    val messages: List<ByteArray>,
    val label: String,
    val klass: FrameClass = FrameClass.ROOM,
    /** Which hourly budget this frame spends from; see [AirBucket]. */
    val bucket: AirBucket = AirBucket.LIVE,
) {
    val fragmented: Boolean get() = messages.size > 1
}
