@file:OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle is an experimental kotlinx API

package app.getknit.knit.mesh

import app.getknit.knit.data.settings.ContributionJournal
import app.getknit.knit.data.settings.ContributionTotals
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the counting rules behind the Your mesh screen's lifetime numbers, against an in-memory journal:
 * chat frames only, other people's only, to someone other than the author, once per frame across both
 * hand-off paths, and "handed straight to" only when the addressee was among the targets — so it can
 * never exceed "passed along". Plus the flush contract: totals move before a flush, a flush writes once
 * and is a no-op at zero, and a credit that lands mid-write survives it.
 */
class ContributionLedgerTest {
    private class FakeJournal : ContributionJournal {
        val saved = MutableStateFlow(ContributionTotals.NONE)
        var writes = 0

        /** When set, [addContributions] parks until it completes — models a slow DataStore write. */
        var gate: CompletableDeferred<Unit>? = null

        override val contributionTotals: Flow<ContributionTotals> get() = saved

        override suspend fun addContributions(
            passedAlong: Long,
            deliveredToRecipient: Long,
            now: Long,
        ) {
            gate?.await()
            writes++
            saved.value =
                saved.value.let {
                    it.copy(
                        passedAlong = it.passedAlong + passedAlong,
                        deliveredToRecipient = it.deliveredToRecipient + deliveredToRecipient,
                        since = if (it.since == 0L) now else it.since,
                    )
                }
        }
    }

    private val journal = FakeJournal()
    private var clock = 1_000L
    private val ledger = ContributionLedger(journal, selfId = { ME }, clock = { clock })

    private fun frame(
        id: String,
        type: String = FrameType.CHAT,
        sender: String = "alice",
        recipient: String? = "carol",
    ) = RelayEnvelope(type = type, id = id, senderId = sender, sentAt = 1L, recipientId = recipient, payload = ByteArray(0))

    private suspend fun totals() = ledger.totals.first()

    @Test
    fun `only a chat frame counts`() =
        runTest {
            for (type in listOf(FrameType.PROFILE, FrameType.RECEIPT, FrameType.REACTION, FrameType.GROUP_UPDATE, FrameType.KEY_REQ)) {
                ledger.onHandedOff(frame("f-$type", type = type), setOf("carol"))
            }
            assertEquals(ContributionTotals.NONE, totals())
            ledger.onHandedOff(frame("chat"), setOf("dave"))
            assertEquals(1L, totals().passedAlong)
        }

    @Test
    fun `our own frame and a DM addressed to us are never credited`() =
        runTest {
            ledger.onHandedOff(frame("mine", sender = ME), setOf("carol"))
            ledger.onHandedOff(frame("to-me", recipient = ME), setOf("carol"))
            assertEquals(ContributionTotals.NONE, totals())
        }

    @Test
    fun `sending nobody, or only the author, is not a hand-off`() =
        runTest {
            ledger.onHandedOff(frame("f1"), emptySet())
            ledger.onHandedOff(frame("f1"), setOf("alice")) // re-serving the author its own frame (a wipe reconverge)
            assertEquals(ContributionTotals.NONE, totals())
        }

    @Test
    fun `a frame is credited once across a relay and a later custody re-serve`() =
        runTest {
            ledger.onHandedOff(frame("f1"), setOf("bob", "dave")) // the flood relay
            ledger.onHandedOff(frame("f1"), setOf("erin")) // a 60 s re-offer round
            ledger.onHandedOff(frame("f1"), setOf("erin")) // and another
            assertEquals(1L, totals().passedAlong)
        }

    @Test
    fun `handed straight to needs the addressee among the targets and is a subset of passed along`() =
        runTest {
            ledger.onHandedOff(frame("f1"), setOf("bob")) // relayed past, not to, carol
            assertEquals(ContributionTotals(passedAlong = 1, deliveredToRecipient = 0, since = 1_000L), totals())
            ledger.onHandedOff(frame("f1"), setOf("carol")) // later re-served straight to carol
            assertEquals(ContributionTotals(passedAlong = 1, deliveredToRecipient = 1, since = 1_000L), totals())
            ledger.onHandedOff(frame("f1"), setOf("carol")) // and again: once per frame
            assertEquals(ContributionTotals(passedAlong = 1, deliveredToRecipient = 1, since = 1_000L), totals())
            // A broadcast/group frame has no addressee, so it can only ever be "passed along".
            ledger.onHandedOff(frame("room", recipient = null), setOf("bob", "carol"))
            assertEquals(ContributionTotals(passedAlong = 2, deliveredToRecipient = 1, since = 1_000L), totals())
        }

    @Test
    fun `totals add the unflushed delta to what the journal holds`() =
        runTest {
            journal.saved.value = ContributionTotals(passedAlong = 10, deliveredToRecipient = 4, since = 5L)
            ledger.onHandedOff(frame("f1"), setOf("carol"))
            assertEquals(ContributionTotals(passedAlong = 11, deliveredToRecipient = 5, since = 5L), totals())
            assertEquals("nothing is written until a flush", 0, journal.writes)
        }

    /** "Since" is the moment the phone first helped, not the minute the tick happened to bank it. */
    @Test
    fun `since reads the first credit before and after the flush`() =
        runTest {
            assertEquals(0L, totals().since)
            ledger.onHandedOff(frame("f1"), setOf("carol"))
            assertEquals(1_000L, totals().since)
            clock = 60_000L
            ledger.flush()
            assertEquals(1_000L, journal.saved.value.since)
            assertEquals(1_000L, totals().since)
        }

    @Test
    fun `flush writes the delta once and is a no-op at zero`() =
        runTest {
            ledger.flush()
            assertEquals(0, journal.writes)
            ledger.onHandedOff(frame("f1"), setOf("carol"))
            ledger.onHandedOff(frame("f2"), setOf("bob"))
            ledger.flush()
            assertEquals(1, journal.writes)
            assertEquals(ContributionTotals(passedAlong = 2, deliveredToRecipient = 1, since = 1_000L), journal.saved.value)
            assertEquals("the delta is cleared, so the total does not double", ContributionTotals(2, 1, 1_000L), totals())
            ledger.flush()
            assertEquals(1, journal.writes)
        }

    @Test
    fun `a credit that lands during a flush is kept for the next one`() =
        runTest {
            ledger.onHandedOff(frame("f1"), setOf("carol"))
            val gate = CompletableDeferred<Unit>()
            journal.gate = gate
            val flushing = launch { ledger.flush() }
            advanceUntilIdle() // the flush is parked inside the journal write
            ledger.onHandedOff(frame("f2"), setOf("bob"))
            gate.complete(Unit)
            flushing.join()
            assertEquals(ContributionTotals(passedAlong = 1, deliveredToRecipient = 1, since = 1_000L), journal.saved.value)
            assertEquals("f2 is still pending", ContributionTotals(passedAlong = 2, deliveredToRecipient = 1, since = 1_000L), totals())
            journal.gate = null
            ledger.flush()
            assertEquals(ContributionTotals(passedAlong = 2, deliveredToRecipient = 1, since = 1_000L), journal.saved.value)
        }

    @Test
    fun `the once-per-frame memo lets go after the custody TTL`() =
        runTest {
            ledger.onHandedOff(frame("f1"), setOf("carol"))
            clock += 24 * 60 * 60_000L + 1
            ledger.onHandedOff(frame("f1"), setOf("carol"))
            assertEquals(ContributionTotals(passedAlong = 2, deliveredToRecipient = 2, since = 1_000L), totals())
        }

    private companion object {
        const val ME = "me"
    }
}
