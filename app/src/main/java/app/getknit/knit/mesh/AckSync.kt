package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Delay-tolerant delivery of a **broadcast/group** message's "delivered" tick back to its author.
 *
 * A DM receipt floods and is store-and-forward custodied, so it reaches the sender across hops and time.
 * Broadcast and group messages have no single recipient, so their receipt is deliberately lighter, with a
 * three-way rule keyed on the author's reachability and capability at [owe] time:
 *
 * - **Live-linked author** — a unicast, point-to-point tick (`relay = false`, so it neither floods nor is
 *   custodied) straight over that link: reliable, cheap, and zero custody load for the co-present case.
 *   A capable author's tick is sealed (a v2 `CTL_RECEIPT` ctl chat frame, indistinguishable from chat on
 *   the wire); sealing consumes a ratchet chain key, so an owed tick is sealed **once** and every retry
 *   re-sends those bytes verbatim — a duplicate is router-deduped inside the author's SeenSet window and
 *   a benign RATCHET_DUPLICATE beyond it. That window (10 min) is *shorter* than the heal heartbeat
 *   (15 min), so a flat retry cleared it every single time; a sealed entry that never finds a live link
 *   now retries on a doubling backoff ([backOff]) instead — ~8 re-sends across the 24 h TTL, not ~96.
 *   A live link overrides the schedule: it is the reliable path home, and it ends the entry.
 * - **Absent, sealed-capable author** — the acks *batch* per author ([enqueue]) and, after [debounceMs]
 *   (a best-effort [flushScope] wake; [retryPending] on the heal heartbeat is the backstop), escalate as
 *   ONE sealed tick carrying every pending id (`MessageContent.acks`) handed to [originateTick] — signed
 *   `relay = true`, flooded, custodied, and spool-eligible, so the tick converges to an out-of-range
 *   author exactly like the message it acks. One chain key per batch, however many messages it covers.
 *   Escalated ids are remembered ([escalated]) so a custody re-serve's re-[owe] no-ops instead of
 *   re-sealing. An author who links *during* the debounce gets the batch over the live link instead
 *   ([onNeighborAdded] — still `relay = false`, no custody rows). Batches never ride the coordination
 *   plane: a batched tick outgrows even the compact-fragment budget by construction, which is why
 *   escalation goes through origination, never [MeshTransport.fastSend].
 * - **Legacy (cleartext) author** — today's unicast best-effort tick, kept and retried ([retryPending],
 *   [onNeighborAdded]) until it lands or ages out. Deliberately NEVER flooded or custodied: a cleartext
 *   receipt in custody would re-leak the delivery event ADR 018 sealed away, and downgrading a sealed
 *   tick to cleartext would make the form an on-path observable of link state. The cleartext form is
 *   rebuilt per attempt (fresh id, so the author's SeenSet never dedups a retry); toward such an author
 *   `fastSend` no-ops for the sealed form and the entry just stays owed until a live link exists.
 *
 * [MessageRepository.markReceived] is idempotent, so a duplicate tick (multiple recipients, a retry after
 * one already landed, or a custody re-serve of an escalated batch) is harmless — one surviving receipt is
 * all the "≥1 person received it" tick needs, and [ForwardSync.onAck] is a no-op for a receipt whose acked
 * message has no DM recipient, so retries can never evict custody.
 *
 * In-memory and bounded (global [cap]/[PENDING_CAP]/[ESCALATED_CAP], per-entry [ttlMs]) like
 * [KeyExchange]/[PendingInbound]: an unsent owed tick self-repopulates when the message re-serves through
 * the deliver path (which re-calls [owe]), so a restart before convergence loses nothing durable — the
 * replacement entry seals fresh (worst case, one duplicate custodied batch, absorbed idempotently). Pure
 * (transport/identity/signer/clock injected) ⇒ unit-tested with [FakeLoopTransport] (see `AckSyncTest`).
 */
class AckSync(
    private val transport: MeshTransport,
    private val selfId: suspend () -> String,
    // Raw Ed25519 over the canonical RelayEnvelope bytes — the same signer MeshManager.sign uses, injected so
    // the receipt authenticates like every other frame while this class stays free of the crypto stack.
    private val signRaw: (ByteArray) -> ByteArray,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newFrameId: () -> String = { FrameId.new() },
    private val metrics: MeshMetrics = MeshMetrics(),
    private val ttlMs: Long = OWED_TTL_MS,
    private val cap: Int = OWED_CAP,
    // Seals a CTL_RECEIPT ctl DM for (authorId, ackIds), signed relay = false — the live-link form — or
    // null when the author can't read one (falls back to the per-attempt cleartext receipt). Injected as
    // a lambda so the MeshManager wiring stays cycle-free (the `originate` precedent); the default keeps
    // this class — and every pre-existing test — cleartext-only.
    private val sealTick: suspend (authorId: String, ackIds: List<String>) -> WireEnvelope? = { _, _ -> null },
    // Whether the author could read a sealed tick right now — the escalation gate. A stale `true` only
    // costs a failed [originateTick] at flush time (which falls back to cleartext entries), so callers
    // keep this a cheap pin + capability check. The default disables escalation entirely.
    private val canSeal: suspend (authorId: String) -> Boolean = { false },
    // Seals AND originates (relay = true → flooded/custodied/spool) one tick covering [ackIds]; false =
    // seal failed, re-materialize the ids as cleartext entries.
    private val originateTick: suspend (authorId: String, ackIds: List<String>) -> Boolean = { _, _ -> false },
    // Best-effort scope for the debounce wake; null (stopped session, plain tests) leaves flushing to
    // [retryPending] on the heal heartbeat.
    private val flushScope: () -> CoroutineScope? = { null },
    private val debounceMs: Long = TICK_BATCH_DEBOUNCE_MS,
) {
    private data class Owed(
        val authorId: String,
        val recordedAt: Long,
        /** The once-sealed tick these retries re-send verbatim; null = cleartext form (built per attempt). */
        val sealed: WireEnvelope? = null,
        /** Best-effort re-sends already spent on this entry — the doubling exponent (see [backOff]). */
        val retries: Int = 0,
        /** Earliest clock at which a best-effort re-send may go out; 0 = due now. Sealed form only. */
        val nextAttemptAt: Long = 0,
    )

    /**
     * Acks accumulating toward one absent capable author. [ids] maps ackId → recordedAt in arrival
     * order; all access is guarded by `synchronized(this)`.
     */
    private class PendingBatch {
        val ids = LinkedHashMap<String, Long>()
        var wakeArmed = false
    }

    // messageId -> the author we owe a broadcast/group delivery tick, and when we recorded it (for the TTL).
    private val owed = ConcurrentHashMap<String, Owed>()

    // authorId -> the batch of acks waiting out the debounce before escalating into custody.
    private val pending = ConcurrentHashMap<String, PendingBatch>()

    // ackId -> when its batch was originated into custody: the done-but-remembered ledger that absorbs the
    // exists-gate's re-ack on every custody re-serve of the message (else each re-serve would re-seal).
    private val escalated = ConcurrentHashMap<String, Long>()

    /**
     * We delivered a broadcast/group message [messageId] authored by [authorId]: tick it now (best-effort) and
     * remember it so we retry until the tick lands or it ages out. Never acks our own message. If the author is
     * already a live neighbor the tick goes over that link and isn't stored (nothing to retry); when
     * [escalatable] (a **group** tick — the broadcast room deliberately never escalates: it is the ambient,
     * shorter-lived class and its ticks stay best-effort) an absent sealed-capable author's ack joins that
     * author's pending batch instead (see the class doc).
     */
    suspend fun owe(
        messageId: String,
        authorId: String,
        escalatable: Boolean = false,
    ) {
        val me = selfId()
        if (authorId == me) return
        sweep()
        if (escalated.containsKey(messageId)) return
        pending[authorId]?.let { batch -> if (synchronized(batch) { batch.ids.containsKey(messageId) }) return }
        val existing = owed[messageId]
        val batchable = escalatable && existing == null
        val absent = transport.neighbors.value.none { it.nodeId == authorId }
        if (batchable && absent && canSeal(authorId)) {
            enqueue(authorId, messageId)
            return
        }
        // A re-delivery re-owes an id we may already hold: keep the existing entry (and its cached
        // seal) rather than sealing again — the one-key-per-owed-tick budget.
        val entry =
            existing ?: run {
                if (owed.size >= cap) evictOldest()
                Owed(authorId, now(), sealed = sealTick(authorId, listOf(messageId)))
            }
        owed[messageId] = entry
        // A re-owe (custody re-served the message, so the deliver path re-acked it) rides the same schedule
        // as the heartbeat retry: the cached sealed bytes are identical every time, so an off-schedule
        // re-send is one more RATCHET_DUPLICATE at the author. A fresh entry is always due.
        sendOwedIfDue(me, messageId, entry, now())
    }

    /**
     * A live neighbor appeared (a fresh join, or the periodic re-offer for a persistent link): first flush any
     * batch pending toward it over that link (pre-escalation — saves the custody rows), then send every tick
     * we owe it and drop those entries — a live link is a reliable path home for the receipt.
     */
    suspend fun onNeighborAdded(peer: Peer) {
        flushBatchOverLink(peer)
        if (owed.isEmpty()) return
        val me = selfId()
        owed.filterValues { it.authorId == peer.nodeId }.toList().forEach { (messageId, entry) ->
            attempt(me, messageId, entry)
            metrics.onReceiptResent()
            owed.remove(messageId)
        }
    }

    /**
     * Heartbeat/heal hook: escalate any batch past its debounce (the backstop for a dead [flushScope] wake),
     * drop aged-out entries, then re-attempt every remaining owed tick. An author reachable only over the
     * coordination plane (cues, no live link) gets a best-effort fast-send and is kept for the next try; one
     * that has since become a live neighbor is sent reliably and dropped.
     */
    suspend fun retryPending() {
        sweep() // before the due check, so an aged-out batch is dropped, never escalated
        flushDueBatches()
        if (owed.isEmpty()) return
        val me = selfId()
        val nowMs = now()
        // Oldest-first: the entries closest to their TTL get their retry before any newcomer's.
        owed.toMap().entries.sortedBy { it.value.recordedAt }.forEach { (messageId, entry) ->
            // Counted only when something actually went out, so `receiptsResent` stays a re-send tally
            // rather than a heartbeat tally — an entry still inside its backoff is skipped silently.
            if (sendOwedIfDue(me, messageId, entry, nowMs)) metrics.onReceiptResent()
        }
    }

    /** Adds [messageId] to [authorId]'s pending batch, arming the debounce wake / flushing a full batch. */
    private suspend fun enqueue(
        authorId: String,
        messageId: String,
    ) {
        while (true) {
            val batch = pending.computeIfAbsent(authorId) { PendingBatch() }
            var full = false
            var armWake = false
            val inserted =
                synchronized(batch) {
                    // A concurrent flush may have detached this batch between computeIfAbsent and here —
                    // detachment happens before the flush copies ids (both under this lock), so an id landed
                    // in a detached batch would be lost. Retry into a fresh batch instead.
                    if (pending[authorId] !== batch) return@synchronized false
                    batch.ids[messageId] = now()
                    full = batch.ids.size >= MAX_BATCH_ACKS
                    if (!full && !batch.wakeArmed) {
                        batch.wakeArmed = true
                        armWake = true
                    }
                    true
                }
            if (!inserted) continue
            when {
                full -> {
                    flushAuthor(authorId)
                }

                // overflow: escalate now; the next ack opens a fresh batch
                pendingTotal() >= PENDING_CAP -> {
                    flushOldestBatch()
                }

                // bounded memory — escalate early, never drop
                armWake -> {
                    flushScope()?.launch {
                        delay(debounceMs)
                        flushDueBatches()
                    }
                }
            }
            return
        }
    }

    /** Escalates every batch whose oldest ack has waited out the debounce. */
    private suspend fun flushDueBatches() {
        val cutoff = now() - debounceMs
        pending.keys.toList().forEach { authorId ->
            val batch = pending[authorId] ?: return@forEach
            val due =
                synchronized(batch) {
                    batch.ids.values
                        .firstOrNull()
                        ?.let { it <= cutoff } == true
                }
            if (due) flushAuthor(authorId)
        }
    }

    /**
     * Escalates [authorId]'s batch: one originated (`relay = true` → custodied) sealed tick covering every
     * pending id — deliberately with no liveness re-check (origination floods to current neighbors anyway,
     * and custody covers everyone else). A failed seal (author unpinned meanwhile) re-materializes the ids
     * as today's per-id cleartext entries with their original timestamps, tried once now.
     */
    private suspend fun flushAuthor(authorId: String) {
        val batch = pending.remove(authorId) ?: return
        val ids = synchronized(batch) { LinkedHashMap(batch.ids) }
        if (ids.isEmpty()) return
        if (originateTick(authorId, ids.keys.toList())) {
            val at = now()
            ids.keys.forEach { escalated[it] = at }
            trimEscalated()
        } else {
            restoreAsCleartext(authorId, ids)
        }
    }

    /**
     * The author linked while its batch was still debouncing: send the whole batch over the link as one
     * sealed `relay = false` tick — reliable, and no custody rows. Deliberately NOT marked escalated: a
     * live-link send is today's drop-the-entry semantics, so a custody re-serve re-owes and re-sends,
     * which markReceived absorbs idempotently.
     */
    private suspend fun flushBatchOverLink(peer: Peer) {
        val batch = pending.remove(peer.nodeId) ?: return
        val ids = synchronized(batch) { LinkedHashMap(batch.ids) }
        if (ids.isEmpty()) return
        val wire = sealTick(peer.nodeId, ids.keys.toList())
        if (wire != null) {
            transport.send(wire, peer)
            metrics.onReceiptResent()
        } else {
            restoreAsCleartext(peer.nodeId, ids)
        }
    }

    /** The escalation-failed fallback: per-id cleartext owed entries (original timestamps), tried once. */
    private suspend fun restoreAsCleartext(
        authorId: String,
        ids: LinkedHashMap<String, Long>,
    ) {
        val me = selfId()
        ids.forEach { (messageId, recordedAt) ->
            if (owed.size >= cap) evictOldest()
            val entry = Owed(authorId, recordedAt)
            owed[messageId] = entry
            if (attempt(me, messageId, entry)) owed.remove(messageId)
        }
    }

    /**
     * Send the tick for [messageId] to its author. A sealed entry re-sends its cached bytes verbatim; a
     * cleartext one is rebuilt per attempt (fresh id, so the author's SeenSet never dedups a retry).
     * Returns true if it went over a **live link** (routed to a child holding a data-path link to the
     * author — reliable, so the owed entry can be dropped); false if it could only be best-effort
     * fast-sent over the coordination plane (kept for a later retry — for the sealed form that means
     * compact fragments toward a capable peer and a no-op toward a legacy one; see the class doc).
     */
    private suspend fun attempt(
        me: String,
        messageId: String,
        entry: Owed,
        linked: Peer? = linkedTo(entry.authorId),
    ): Boolean {
        val wire = entry.sealed ?: receipt(me, messageId)
        return if (linked != null) {
            transport.send(wire, linked)
            true
        } else {
            transport.fastSend(wire, Peer(entry.authorId))
            false
        }
    }

    /**
     * Sends one owed tick and settles its entry: a live-link send is reliable and drops the entry, a
     * best-effort coordination-plane send re-arms the backoff. Returns false when the entry was not due and
     * nothing went out. A live link always makes it due — it is the reliable path home the backoff is
     * waiting for, so it never waits.
     */
    private suspend fun sendOwedIfDue(
        me: String,
        messageId: String,
        entry: Owed,
        now: Long,
    ): Boolean {
        val linked = linkedTo(entry.authorId)
        if (linked == null && now < entry.nextAttemptAt) return false
        if (attempt(me, messageId, entry, linked)) {
            owed.remove(messageId) // sent over a live link → done
        } else {
            backOff(messageId, entry, now)
        }
        return true
    }

    /** The author's live link, if any — the reliable path home, and the one thing that overrides [backOff]. */
    private fun linkedTo(authorId: String): Peer? = transport.neighbors.value.firstOrNull { it.nodeId == authorId }

    /**
     * Schedules the next best-effort re-send of a **sealed** owed entry. The cleartext form is exempt and
     * returns unchanged: it is rebuilt with a fresh id per attempt, so a retry costs the author a SeenSet
     * dedup, never a decrypt.
     *
     * The sealed form re-sends one frame id verbatim for the entry's whole life. The router suppresses a
     * repeat for only 10 minutes ([SeenSet]) while the heal heartbeat runs every 15, so every flat retry
     * cleared the window and landed on a consumed ratchet chain index — ~96 `RATCHET_DUPLICATE` drops at
     * the author per stuck tick, across the 24 h TTL. Doubling from one heartbeat up to [RETRY_CAP_MS]
     * holds the same horizon at ~8. Nothing here extends the entry's life: [sweep] still ages it out on
     * [Owed.recordedAt], and the tick self-heals anyway when the message re-serves and re-[owe]s.
     */
    private fun backOff(
        messageId: String,
        entry: Owed,
        now: Long,
    ) {
        if (entry.sealed == null) return
        val retries = entry.retries + 1
        // Compare-and-set: never resurrect an entry a concurrent live-link send has just removed.
        owed.replace(messageId, entry, entry.copy(retries = retries, nextAttemptAt = now + retryDelayMs(retries)))
    }

    /** Doubling delay from one heal heartbeat, capped — 15 m, 30 m, 1 h, 2 h, 4 h, then [RETRY_CAP_MS]. */
    private fun retryDelayMs(retries: Int): Long =
        (RETRY_BASE_MS shl (retries - 1).coerceIn(0, MAX_BACKOFF_SHIFT)).coerceAtMost(RETRY_CAP_MS)

    /**
     * A signed, point-to-point (`relay = false`) delivery receipt for [messageId] — MeshRouter never floods it
     * and [MeshManager.onDeliver] never custodies it. A fresh id each send so the author's SeenSet never dedups
     * a retry (the payload's ackId is what flips the tick, idempotently).
     */
    private fun receipt(
        me: String,
        messageId: String,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.RECEIPT,
                id = newFrameId(),
                senderId = me,
                payload = WireCodec.encodePayload(ReceiptContent(messageId)),
            )
        val signed = WireCodec.encodeEnvelope(env)
        return WireEnvelope(relay = false, sig = signRaw(signed), signed = signed)
    }

    private fun pendingTotal(): Int = pending.values.sumOf { synchronized(it) { it.ids.size } }

    private suspend fun flushOldestBatch() {
        val oldest =
            pending.entries.minByOrNull { (_, batch) ->
                synchronized(batch) { batch.ids.values.firstOrNull() ?: Long.MAX_VALUE }
            } ?: return
        flushAuthor(oldest.key)
    }

    private fun trimEscalated() {
        while (escalated.size > ESCALATED_CAP) {
            // Loss = at worst one duplicate custodied batch on a later re-owe, absorbed idempotently.
            escalated.entries.minByOrNull { it.value }?.let { escalated.remove(it.key) } ?: return
        }
    }

    private fun sweep() {
        val cutoff = now() - ttlMs
        owed.entries.removeAll { it.value.recordedAt < cutoff }
        escalated.entries.removeAll { it.value < cutoff }
        pending.entries.removeAll { (_, batch) ->
            synchronized(batch) {
                batch.ids.entries.removeAll { it.value < cutoff }
                batch.ids.isEmpty()
            }
        }
    }

    private fun evictOldest() {
        owed.entries.minByOrNull { it.value.recordedAt }?.let { owed.remove(it.key) }
    }

    companion object {
        /** Keep retrying an unlanded broadcast/group tick for at most this long — matches the DM/group carry TTL. */
        const val OWED_TTL_MS = 24 * 60 * 60_000L

        /** Cap on distinct owed ticks held at once (evict oldest); each is tiny (two ids + a timestamp). */
        const val OWED_CAP = 500

        /** How long an absent author's acks accumulate before the batch escalates into custody. */
        const val TICK_BATCH_DEBOUNCE_MS = 45_000L

        /** Most ids one escalated tick carries (the receiver applies up to 2× this); overflow flushes early. */
        const val MAX_BATCH_ACKS = 64

        /** Cap on pending (not yet escalated) ids across all batches — beyond it the oldest batch escalates early. */
        const val PENDING_CAP = 500

        /** Cap on remembered escalated ids (the re-owe dedup ledger); evict oldest. */
        const val ESCALATED_CAP = 1_000

        /** First backoff step for a sealed owed tick — one heal heartbeat (`MeshService`'s 15 min alarm). */
        const val RETRY_BASE_MS = 15 * 60_000L

        /** Ceiling on the doubling, so even a day-old entry still gets a few attempts before it ages out. */
        const val RETRY_CAP_MS = 8 * 60 * 60_000L

        /** Bounds the shift so a long-lived entry cannot overflow the doubling. */
        private const val MAX_BACKOFF_SHIFT = 10
    }
}
