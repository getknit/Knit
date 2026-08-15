package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import java.util.concurrent.ConcurrentHashMap

/**
 * Delay-tolerant delivery of a **broadcast/group** message's "delivered" tick back to its author.
 *
 * A DM receipt floods and is store-and-forward custodied, so it reaches the sender across hops and time.
 * Broadcast and group messages have no single recipient, so their receipt is deliberately a lighter,
 * unicast, point-to-point tick — `relay = false`, so it neither floods nor is custodied — sent straight to
 * the author over [MeshTransport.fastSend]. That one-shot best-effort tick is lost for good if the author
 * isn't reachable from us at the instant we deliver the message: exactly the store-and-forward / out-of-range
 * case, where the *message* converges via custody but the tick never does, so the author's UI shows no ✓✓
 * even after everything else caught up.
 *
 * This closes that gap **without an ack storm**: we remember the ticks we owe ([owe]) and re-send them —
 * still unicast, still `relay = false`, never flooded — until the author becomes reachable or the entry ages
 * out. When the author is a *live neighbor* we send over that link (reliable) and drop the entry; otherwise we
 * best-effort fast-send over the coordination plane and keep retrying on every newcomer ([onNeighborAdded])
 * and heartbeat ([retryPending]). [MessageRepository.markReceived] is idempotent, so a duplicate tick
 * (multiple recipients, or a retry after one already landed) is harmless — one surviving receipt is all the
 * "≥1 person received it" tick needs, and [ForwardSync.onAck] is a no-op for a receipt whose acked message
 * has no DM recipient, so retries can never evict custody.
 *
 * A **capable author gets a sealed tick instead** (docs/ENCRYPTED_RECEIPTS_REACTIONS.md): [sealTick]
 * builds a v2 ctl chat frame (still `relay = false` — never flooded, never custodied) so the tick is
 * indistinguishable from chat on the wire. Sealing consumes a ratchet chain key, so an owed tick is sealed
 * **once**, at [owe] time, and every retry re-sends those bytes verbatim — a duplicate is router-deduped
 * inside the author's SeenSet window and a benign RATCHET_DUPLICATE beyond it; re-sealing per retry would
 * burn a key per heartbeat and starve real DMs of the receiver's skipped-key budget. One caveat rides with
 * the sealed form: it outgrows the coordination plane's frame cap, so `fastSend` no-ops and it only lands
 * over a live link — the entry just stays owed until one exists (the author needed radio proximity for
 * fastSend anyway). Deliberately NOT downgraded to cleartext in that case: the form would become an
 * on-path observable of link state.
 *
 * In-memory and bounded (global [cap], per-entry [ttlMs]) like [KeyExchange]/[PendingInbound]: an unsent owed
 * tick self-repopulates when the message re-serves through the deliver path (which re-calls [owe]), so a
 * restart before convergence loses nothing durable — the replacement entry seals fresh, which is the same
 * one-key-per-owed-tick budget. Pure (transport/identity/signer/clock injected) ⇒ unit-tested with
 * [FakeLoopTransport] (see `AckSyncTest`).
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
    // Seals a CTL_RECEIPT ctl DM for (authorId, ackId), signed relay = false, or null when the author
    // can't read one (falls back to the per-attempt cleartext receipt). Injected as a lambda so the
    // MeshManager wiring stays cycle-free (the `originate` precedent); the default keeps this class —
    // and every pre-existing test — cleartext-only.
    private val sealTick: suspend (authorId: String, ackId: String) -> WireEnvelope? = { _, _ -> null },
) {
    private data class Owed(
        val authorId: String,
        val recordedAt: Long,
        /** The once-sealed tick these retries re-send verbatim; null = cleartext form (built per attempt). */
        val sealed: WireEnvelope? = null,
    )

    // messageId -> the author we owe a broadcast/group delivery tick, and when we recorded it (for the TTL).
    private val owed = ConcurrentHashMap<String, Owed>()

    /**
     * We delivered a broadcast/group message [messageId] authored by [authorId]: tick it now (best-effort) and
     * remember it so we retry until the tick lands or it ages out. Never acks our own message. If the author is
     * already a live neighbor the tick goes over that link and isn't stored (nothing to retry).
     */
    suspend fun owe(
        messageId: String,
        authorId: String,
    ) {
        val me = selfId()
        if (authorId == me) return
        sweep()
        // A re-delivery re-owes an id we may already hold: keep the existing entry (and its cached
        // seal) rather than sealing again — the one-key-per-owed-tick budget.
        val entry =
            owed[messageId] ?: run {
                if (owed.size >= cap) evictOldest()
                Owed(authorId, now(), sealed = sealTick(authorId, messageId))
            }
        owed[messageId] = entry
        if (attempt(me, messageId, entry)) owed.remove(messageId) // sent over a live link → done
    }

    /**
     * A live neighbor appeared (a fresh join, or the periodic re-offer for a persistent link): send every tick
     * we owe it over that link and drop those entries — a live link is a reliable path home for the receipt.
     */
    suspend fun onNeighborAdded(peer: Peer) {
        if (owed.isEmpty()) return
        val me = selfId()
        owed.filterValues { it.authorId == peer.nodeId }.toList().forEach { (messageId, entry) ->
            attempt(me, messageId, entry)
            metrics.onReceiptResent()
            owed.remove(messageId)
        }
    }

    /**
     * Heartbeat/heal hook: drop aged-out entries, then re-attempt every remaining owed tick. An author reachable
     * only over the coordination plane (cues, no live link) gets a best-effort fast-send and is kept for the next
     * try; one that has since become a live neighbor is sent reliably and dropped.
     */
    suspend fun retryPending() {
        sweep()
        if (owed.isEmpty()) return
        val me = selfId()
        // Oldest-first: the entries closest to their TTL get their retry before any newcomer's.
        owed.toMap().entries.sortedBy { it.value.recordedAt }.forEach { (messageId, entry) ->
            val overLiveLink = attempt(me, messageId, entry)
            metrics.onReceiptResent()
            if (overLiveLink) owed.remove(messageId)
        }
    }

    /**
     * Send the tick for [messageId] to its author. A sealed entry re-sends its cached bytes verbatim; a
     * cleartext one is rebuilt per attempt (fresh id, so the author's SeenSet never dedups a retry).
     * Returns true if it went over a **live link** (routed to a child holding a data-path link to the
     * author — reliable, so the owed entry can be dropped); false if it could only be best-effort
     * fast-sent over the coordination plane (kept for a later retry — a no-op for the sealed form,
     * which outgrows the coordination frame cap; see the class doc).
     */
    private suspend fun attempt(
        me: String,
        messageId: String,
        entry: Owed,
    ): Boolean {
        val wire = entry.sealed ?: receipt(me, messageId)
        val linked = transport.neighbors.value.firstOrNull { it.nodeId == entry.authorId }
        return if (linked != null) {
            transport.send(wire, linked)
            true
        } else {
            transport.fastSend(wire, Peer(entry.authorId))
            false
        }
    }

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

    private fun sweep() {
        val cutoff = now() - ttlMs
        owed.entries.removeAll { it.value.recordedAt < cutoff }
    }

    private fun evictOldest() {
        owed.entries.minByOrNull { it.value.recordedAt }?.let { owed.remove(it.key) }
    }

    private companion object {
        /** Keep retrying an unlanded broadcast/group tick for at most this long — matches the DM/group carry TTL. */
        const val OWED_TTL_MS = 24 * 60 * 60_000L

        /** Cap on distinct owed ticks held at once (evict oldest); each is tiny (two ids + a timestamp). */
        const val OWED_CAP = 500
    }
}
