package app.getknit.knit.ui.diagnostics

import app.getknit.knit.crash.CrashReportRef
import app.getknit.knit.crash.CrashReports
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshMetrics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                )

            assertEquals(ref, vm.lastCrash.value)

            coEvery { crashes.latest() } returns null
            vm.refreshLastCrash()

            assertNull(vm.lastCrash.value)
        }
}
