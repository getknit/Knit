package app.getknit.knit.mesh

import app.getknit.knit.data.settings.ContributionJournal
import app.getknit.knit.data.settings.ContributionTotals
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update

/**
 * Counts what this phone does for other people's messages — the lifetime numbers on the Your mesh screen.
 *
 * A credit happens at a **hand-off** and nowhere else: the moment this phone *sent* someone else's chat
 * frame to at least one peer. Two places do that — the router's flood fan-out (`MeshRouter.onRelayed`)
 * and a custody re-serve to a peer whose digest showed it lacked the frame (`ForwardSync.onServed`) — and
 * both report the fact through [onHandedOff]. Taking a frame into custody is not a credit (it may sit
 * there until it expires), and neither is a coordination-plane `fastSend` or a LoRa fan-out, which report
 * nothing about whether anything left the radio. Under-claiming is the right side to err on: every number
 * here is shown to the user as something their phone did.
 *
 * The rules, each of which keeps a number honest:
 *  - **chat frames only** ([FrameType.CHAT]): a profile, a cleartext reaction or receipt, a group update
 *    is mesh housekeeping, not "a message". A sealed receipt or reaction rides *as* chat and a carrier
 *    cannot tell it apart (ADR 018) — it counts as a message frame, and the screen's copy says so.
 *  - **other people's frames only**: not one this phone authored, and not a DM addressed to it — custody
 *    holds both (ADR 018, the self-frame re-serve), and neither is help for anyone else.
 *  - **to someone other than the author**: re-serving an author its own frame is how a wiped store
 *    reconverges, not a delivery.
 *  - **once per frame**: the custody re-offer loop runs every 60 s for as long as a frame lives, and a
 *    peer that can never store a frame (it blocks the sender) is offered it every round for 24 h — without
 *    the memo one such frame would credit ~1,440 hand-offs a day. Two [SeenSet]s (the custody TTL, so a
 *    frame past it can no longer be re-served) make [passedAlong] the first hand-off to anyone and
 *    [deliveredToRecipient] the first hand-off to the addressee, which keeps the second ≤ the first. The
 *    memo is in-memory, so a re-serve straight after a restart may credit a frame a second time; accepted.
 *
 * Persistence is deliberately slow: increments land in an in-memory delta and [flush] hands the delta to
 * the [journal] on the caller's tick (the 60 s metrics tick and `MeshManager.stop()`), because every
 * DataStore write re-emits every settings collector in the app. [totals] adds the unflushed delta to the
 * persisted value, so the screen moves the moment a hand-off happens rather than a minute later.
 *
 * Pure — no Android, no Room — so it is unit-tested against a fake journal and runs unchanged inside the
 * `mesh/lab` nodes, whose real DataStore-backed `SettingsStore` is the journal.
 */
class ContributionLedger(
    private val journal: ContributionJournal,
    // Our own node id, so a frame we authored or one addressed to us is never credited. Suspend because
    // `Identity.nodeId()` is; the ledger asks only when a frame passes the type gate.
    private val selfId: suspend () -> String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Pending(
        val passedAlong: Long = 0L,
        val deliveredToRecipient: Long = 0L,
        // The clock at the first unflushed credit, so "since" reads the moment the phone first helped rather
        // than the minute the tick banked it; 0 when nothing is pending.
        val since: Long = 0L,
    ) {
        val isZero get() = passedAlong == 0L && deliveredToRecipient == 0L
    }

    private val unflushed = MutableStateFlow(Pending())
    private val passedMemo = SeenSet(ttlMillis = CREDIT_MEMO_TTL_MS, clock = clock)
    private val handedMemo = SeenSet(ttlMillis = CREDIT_MEMO_TTL_MS, clock = clock)

    /** The persisted totals plus what this session has not flushed yet — what the screen shows. */
    val totals: Flow<ContributionTotals> =
        combine(journal.contributionTotals, unflushed) { saved, pending ->
            ContributionTotals(
                passedAlong = saved.passedAlong + pending.passedAlong,
                deliveredToRecipient = saved.deliveredToRecipient + pending.deliveredToRecipient,
                since = if (saved.since > 0L) saved.since else pending.since,
            )
        }.distinctUntilChanged()

    /**
     * This phone just sent [envelope] to the peers in [to] — the router's relay fan-out or a custody
     * re-serve. Credits under the rules above, or not at all.
     */
    suspend fun onHandedOff(
        envelope: RelayEnvelope,
        to: Collection<String>,
    ) {
        if (envelope.type != FrameType.CHAT || to.isEmpty()) return
        val me = selfId()
        if (envelope.senderId == me || envelope.recipientId == me) return
        val others = to.filterTo(HashSet()) { it != envelope.senderId }
        if (others.isEmpty()) return
        if (passedMemo.add(envelope.id)) {
            unflushed.update { it.copy(passedAlong = it.passedAlong + 1, since = it.since.orNow()) }
        }
        val recipient = envelope.recipientId ?: return
        if (recipient in others && handedMemo.add(envelope.id)) {
            unflushed.update { it.copy(deliveredToRecipient = it.deliveredToRecipient + 1, since = it.since.orNow()) }
        }
    }

    private fun Long.orNow(): Long = if (this > 0L) this else clock()

    /**
     * Hands the unflushed delta to the journal in one write; a no-op when there is none. The delta is
     * subtracted only after the write returns, so a hand-off credited mid-write is kept for the next flush
     * and [totals] over-reads by at most that write's duration — it never dips.
     */
    suspend fun flush() {
        val delta = unflushed.value
        if (delta.isZero) return
        journal.addContributions(delta.passedAlong, delta.deliveredToRecipient, delta.since.orNow())
        unflushed.update {
            it.copy(
                passedAlong = it.passedAlong - delta.passedAlong,
                deliveredToRecipient = it.deliveredToRecipient - delta.deliveredToRecipient,
                since = 0L, // the journal holds it now
            )
        }
    }

    private companion object {
        /**
         * The custody TTL (`ForwardRepository.DEFAULT_TTL_MS`): a frame older than this is refused at store
         * time everywhere (the dead-on-arrival guard), so nothing can re-serve it and the memo can let go.
         */
        const val CREDIT_MEMO_TTL_MS = 24 * 60 * 60_000L
    }
}
