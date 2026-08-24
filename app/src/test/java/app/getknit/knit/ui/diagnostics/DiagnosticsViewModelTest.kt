package app.getknit.knit.ui.diagnostics

import app.getknit.knit.R
import app.getknit.knit.crash.CrashReportRef
import app.getknit.knit.crash.CrashReports
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.ModelLoadJournal
import app.getknit.knit.data.settings.ModelLoadState
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.ModelLoadPolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Demonstrates finding #15's payoff: with the mesh behind [MeshController], a ViewModel is now testable
 * against the shared [FakeMeshController] fixture instead of the concrete, un-constructable `MeshManager`.
 * Verifies the Diagnostics actions route to the controller.
 */
class DiagnosticsViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A guard over an empty journal — nothing latched, nothing to reset. */
    private fun unlatchedGuard(journal: ModelLoadJournal = EmptyJournal()) = ModelLoadGuard(journal, { null }, STAMP)

    private open class EmptyJournal : ModelLoadJournal {
        val cleared = mutableListOf<String>()

        override fun observeModelLoad(model: String): Flow<ModelLoadState> = MutableStateFlow(ModelLoadState.NONE)

        override suspend fun modelLoadState(model: String) = ModelLoadState.NONE

        override suspend fun setModelLoadState(
            model: String,
            state: ModelLoadState,
        ) {
            cleared += model
        }
    }

    @Test
    fun `a latched model surfaces, and resetting it clears every model and reports back`() =
        runTest {
            val controller = FakeMeshController()
            val settings = mockk<SettingsStore>(relaxed = true)
            every { settings.spoolEnabled } returns MutableStateFlow(false)
            every { settings.spoolUrls } returns MutableStateFlow(emptySet())
            val journal =
                object : EmptyJournal() {
                    override fun observeModelLoad(model: String): Flow<ModelLoadState> =
                        MutableStateFlow(
                            if (model == ModelLoadGuard.TOXICITY) {
                                ModelLoadState(STAMP, 0L, ModelLoadPolicy.MAX_FAILS)
                            } else {
                                ModelLoadState.NONE
                            },
                        )
                }
            val vm =
                DiagnosticsViewModel(
                    peers = mockk(relaxed = true),
                    meshManager = controller,
                    identity = mockk(relaxed = true),
                    settings = settings,
                    metrics = MeshMetrics(),
                    relayStatus = RelayStatusRepository(settings, controller),
                    crashes = mockk(relaxed = true),
                    modelGuard = unlatchedGuard(journal),
                )

            val seen = mutableListOf<Int>()
            val events = backgroundScope.launch { vm.events.collect { seen += it } }
            val latch = backgroundScope.launch { vm.moderationLatched.collect { } }
            // WhileSubscribed: the StateFlow only starts sharing once a collector is actually running.
            runCurrent()

            assertTrue(vm.moderationLatched.value)

            vm.resetModerationLatch()
            runCurrent()

            // Both models, not just the latched one: the row is a single "reduced" state, so the reset
            // has to match it or the user could clear the row and still be latched.
            assertEquals(ModelLoadGuard.ALL, journal.cleared)
            assertEquals(listOf(R.string.diagnostics_moderation_reset_done), seen)
            events.cancel()
            latch.cancel()
        }

    @Test
    fun rescanAndRestartRouteToTheController() =
        runTest {
            val controller = FakeMeshController()
            val settings = mockk<SettingsStore>(relaxed = true)
            every { settings.spoolEnabled } returns MutableStateFlow(false)
            every { settings.spoolUrls } returns MutableStateFlow(emptySet())
            val vm =
                DiagnosticsViewModel(
                    peers = mockk(relaxed = true),
                    meshManager = controller,
                    identity = mockk(relaxed = true),
                    settings = settings,
                    metrics = MeshMetrics(),
                    relayStatus = RelayStatusRepository(settings, controller),
                    crashes = mockk(relaxed = true),
                    modelGuard = unlatchedGuard(),
                )

            vm.rescan()
            vm.restartMesh()

            assertEquals(1, controller.healCount)
            assertEquals(1, controller.restartCount)
        }

    @Test
    fun lastCrashSurfacesTheNewestReportAndClearsAfterADelete() =
        runTest {
            val controller = FakeMeshController()
            val settings = mockk<SettingsStore>(relaxed = true)
            every { settings.spoolEnabled } returns MutableStateFlow(false)
            every { settings.spoolUrls } returns MutableStateFlow(emptySet())
            val ref =
                CrashReportRef(
                    at = 1_700_000_000_000L,
                    summary = "IllegalStateException at MeshRouter.kt:91",
                    appVersion = "2.3.0 (13) debug",
                    device = "Google Pixel 8 (shiba)",
                    androidVersion = "16 (SDK 36)",
                    file = File("crash-1700000000000-deadbeef.txt"),
                )
            val crashes = mockk<CrashReports>(relaxed = true)
            coEvery { crashes.latest() } returns ref
            val vm =
                DiagnosticsViewModel(
                    peers = mockk(relaxed = true),
                    meshManager = controller,
                    identity = mockk(relaxed = true),
                    settings = settings,
                    metrics = MeshMetrics(),
                    relayStatus = RelayStatusRepository(settings, controller),
                    crashes = crashes,
                    modelGuard = unlatchedGuard(),
                )

            assertEquals(ref, vm.lastCrash.value)

            coEvery { crashes.latest() } returns null
            vm.refreshLastCrash()

            assertNull(vm.lastCrash.value)
        }

    private companion object {
        const val STAMP = "16|test"
    }
}
