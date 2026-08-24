package app.getknit.knit.moderation

import app.getknit.knit.crash.ProcessExitEvidence
import app.getknit.knit.data.settings.ModelLoadJournal
import app.getknit.knit.data.settings.ModelLoadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The side-effect half of the poison-pill (ADR 037) — ordering, cancellation, and failing open. The
 * decision table itself is [ModelLoadPolicyTest].
 *
 * A fake journal rather than a real DataStore: what these assert is *when* writes happen relative to the
 * load, which a map records exactly and a Preferences file only obscures.
 */
class ModelLoadGuardTest {
    private val stamp = "16|Pixel/rel/1"
    private val model = ModelLoadGuard.TOXICITY

    private class FakeJournal(
        var failOn: String? = null,
    ) : ModelLoadJournal {
        val states = mutableMapOf<String, MutableStateFlow<ModelLoadState>>()
        val writes = mutableListOf<ModelLoadState>()

        private fun slot(model: String) = states.getOrPut(model) { MutableStateFlow(ModelLoadState.NONE) }

        override fun observeModelLoad(model: String): Flow<ModelLoadState> = slot(model)

        override suspend fun modelLoadState(model: String): ModelLoadState {
            if (failOn == "read") error("datastore unreadable")
            return slot(model).value
        }

        override suspend fun setModelLoadState(
            model: String,
            state: ModelLoadState,
        ) {
            if (failOn == "write") error("datastore unwritable")
            writes += state
            slot(model).value = state
        }
    }

    private fun guard(
        journal: ModelLoadJournal,
        exit: ProcessExitEvidence? = null,
        now: Long = 1_700_000_000_000L,
    ) = ModelLoadGuard(journal, { exit }, stamp) { now }

    @Test
    fun `marks the attempt before the load runs and clears it after`() =
        runTest {
            val journal = FakeJournal()
            var markedWhenLoadRan: Long? = null
            val result =
                guard(journal).guard(model) {
                    markedWhenLoadRan =
                        journal.states
                            .getValue(model)
                            .value.pendingSince
                    "engine"
                }

            assertEquals("engine", result)
            // The whole mechanism rests on this ordering: if the marker were written after the load, a
            // native crash inside it would leave nothing behind and the loop would be invisible.
            assertEquals(1_700_000_000_000L, markedWhenLoadRan)
            assertEquals(ModelLoadState(stamp, pendingSince = 0L, fails = 0), journal.states.getValue(model).value)
        }

    @Test
    fun `a load that finds no asset still clears the marker`() =
        runTest {
            // Every build shipped without the models takes this path on every launch. Counting it would
            // latch the classifier off on a phone where nothing is wrong.
            val journal = FakeJournal()
            assertNull(guard(journal).guard(model) { null })
            assertEquals(
                0L,
                journal.states
                    .getValue(model)
                    .value.pendingSince,
            )
            assertEquals(
                0,
                journal.states
                    .getValue(model)
                    .value.fails,
            )
        }

    @Test
    fun `a load that throws still clears the marker`() =
        runTest {
            val journal = FakeJournal()
            val thrown =
                runCatching {
                    guard(journal).guard<String>(model) { error("interpreter refused the flatbuffer") }
                }
            assertTrue(thrown.isFailure)
            assertEquals(
                0L,
                journal.states
                    .getValue(model)
                    .value.pendingSince,
            )
        }

    @Test
    fun `a cancelled load still clears the marker`() =
        runTest {
            // Back out of a chat while the 17 MB image model is loading and viewModelScope cancels
            // mid-flight. Ordinary use, and it must not read as evidence.
            val journal = FakeJournal()
            val started = CompletableDeferred<Unit>()
            val job =
                launch {
                    guard(journal).guard(model) {
                        started.complete(Unit)
                        "engine"
                    }
                }
            started.await()
            job.cancel()
            job.join()
            assertEquals(
                0L,
                journal.states
                    .getValue(model)
                    .value.pendingSince,
            )
        }

    @Test
    fun `a native fault recorded against the marker latches the model, and the load never runs again`() =
        runTest {
            val journal = FakeJournal()
            journal.states[model] = MutableStateFlow(ModelLoadState(stamp, pendingSince = 1_699_999_999_000L, fails = 0))
            var ran = false
            val result =
                guard(journal, exit = ProcessExitEvidence(1_699_999_999_500L, nativeFault = true, explained = false))
                    .guard(model) {
                        ran = true
                        "engine"
                    }

            assertNull(result)
            assertFalse(ran)
            assertTrue(guard(journal).observeLatched(model).first())
        }

    @Test
    fun `a latched model is skipped without touching the loader`() =
        runTest {
            val journal = FakeJournal()
            journal.states[model] = MutableStateFlow(ModelLoadState(stamp, 0L, ModelLoadPolicy.MAX_FAILS))
            var ran = false
            assertNull(
                guard(journal).guard(model) {
                    ran = true
                    "engine"
                },
            )
            assertFalse(ran)
        }

    @Test
    fun `clear un-latches the model`() =
        runTest {
            val journal = FakeJournal()
            journal.states[model] = MutableStateFlow(ModelLoadState(stamp, 0L, ModelLoadPolicy.MAX_FAILS))
            val guard = guard(journal)
            guard.clear(model)
            assertFalse(guard.observeLatched(model).first())
        }

    @Test
    fun `a latch earned under a different build does not read as latched`() =
        runTest {
            val journal = FakeJournal()
            journal.states[model] = MutableStateFlow(ModelLoadState("15|old", 0L, ModelLoadPolicy.MAX_FAILS))
            assertFalse(guard(journal).observeLatched(model).first())
        }

    @Test
    fun `an unreadable journal fails open and still loads`() =
        runTest {
            // classify sits on the no-throw inbound path; a DataStore hiccup must not disable moderation.
            assertEquals("engine", guard(FakeJournal(failOn = "read")).guard(model) { "engine" })
        }

    @Test
    fun `an unwritable journal fails open and still loads`() =
        runTest {
            assertEquals("engine", guard(FakeJournal(failOn = "write")).guard(model) { "engine" })
        }

    @Test
    fun `models latch independently`() =
        runTest {
            val journal = FakeJournal()
            journal.states[ModelLoadGuard.NSFW] = MutableStateFlow(ModelLoadState(stamp, 0L, ModelLoadPolicy.MAX_FAILS))
            val guard = guard(journal)
            yield()
            assertTrue(guard.observeLatched(ModelLoadGuard.NSFW).first())
            assertFalse(guard.observeLatched(ModelLoadGuard.TOXICITY).first())
        }
}
