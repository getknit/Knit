package app.getknit.knit.ui.diagnostics

import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshMetrics
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
import org.junit.Before
import org.junit.Test

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
                )

            vm.rescan()
            vm.restartMesh()

            assertEquals(1, controller.healCount)
            assertEquals(1, controller.restartCount)
        }
}
