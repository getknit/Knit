package app.getknit.knit.ui.lora

import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.BoardInfo
import app.getknit.knit.mesh.lora.BoardRef
import app.getknit.knit.mesh.lora.ChannelInfo
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import app.getknit.knit.mesh.lora.LoraStatus
import app.getknit.knit.mesh.lora.ProvisionResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The LoRa settings ViewModel over a fake directory and a fake plane: the picker rules and the channel verdict. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoraRadioViewModelTest {
    private val enabled = MutableStateFlow(true)
    private val dms = MutableStateFlow(true)
    private val address = MutableStateFlow<String?>("AA:BB:CC:DD:EE:01")
    private val channel = MutableStateFlow(1)
    private val settings =
        mockk<SettingsStore>(relaxed = true) {
            every { loraEnabled } returns enabled
            every { loraDmEnabled } returns dms
            every { loraDeviceAddress } returns address
            every { loraChannelIndex } returns channel
        }
    private val status = MutableStateFlow(LoraStatus())
    private val lora =
        object : LoraPlaneStatus {
            override val status: StateFlow<LoraStatus> = this@LoraRadioViewModelTest.status

            override suspend fun provisionKnitChannel(): ProvisionResult = ProvisionResult.NotReady(LinkState.Idle)
        }
    private var bonded =
        listOf(
            BoardRef("AA:BB:CC:DD:EE:01", "Meshtastic_ee01"),
            BoardRef("11:22:33:44:55:66", "Pixel Buds", meshtastic = false),
        )
    private var reads = 0
    private val boards =
        object : BoardDirectory {
            override fun bonded(): List<BoardRef> {
                reads++
                return bonded
            }
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.start(): LoraRadioViewModel {
        val vm = LoraRadioViewModel(settings, lora, boards)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    private fun ready(channels: List<ChannelInfo>) =
        LinkState.Ready(
            board = BoardInfo(myNodeNum = 42u, pioEnv = "heltec-v4", firmwareVersion = "2.5.0"),
            channels = channels,
            mtu = 512,
        )

    @Test
    fun `the picker hides devices that do not look like boards, and show-all reveals them`() =
        runTest {
            val vm = start()

            assertEquals(
                listOf("Meshtastic_ee01"),
                vm.state.value.boards
                    .map { it.name },
            )
            assertEquals(1, vm.state.value.hiddenBoards)
            assertTrue(vm.state.value.anyBonded)
            assertFalse(vm.state.value.showAllBoards)

            vm.setShowAllBoards(true)
            advanceUntilIdle()

            assertEquals(
                listOf("Meshtastic_ee01", "Pixel Buds"),
                vm.state.value.boards
                    .map { it.name },
            )
            assertTrue(vm.state.value.showAllBoards)
        }

    @Test
    fun `link churn never re-reads the bonded list, a refresh does`() =
        runTest {
            val vm = start()
            val readsAtStart = reads

            // The bonded list is a Binder call: a heard-peer tick or a reconnect must not re-issue it.
            status.value = LoraStatus(state = LinkState.Connecting)
            status.value = LoraStatus(state = LinkState.Connecting, heard = 2)
            advanceUntilIdle()
            assertEquals(readsAtStart, reads)

            // Back from the system Bluetooth settings with a freshly paired (renamed) board.
            bonded = bonded + BoardRef("AA:BB:CC:DD:EE:02", "WALT_ee02")
            vm.refreshBoards()
            advanceUntilIdle()

            assertEquals(readsAtStart + 1, reads)
            assertEquals(
                listOf("Meshtastic_ee01", "WALT_ee02"),
                vm.state.value.boards
                    .map { it.name },
            )
        }

    @Test
    fun `a connected board names the selected channel and flags a slot that is not Knit`() =
        runTest {
            val vm = start()
            status.value =
                LoraStatus(
                    state =
                        ready(
                            channels = listOf(ChannelInfo(index = 0, name = "", role = 1), ChannelInfo(index = 1, name = "Knit", role = 2)),
                        ),
                    heard = 3,
                )
            advanceUntilIdle()

            assertEquals(LoraConnState.Ready, vm.state.value.connection)
            assertEquals("Knit", vm.state.value.channelName)
            assertFalse(vm.state.value.channelMismatch)
            assertEquals("2.5.0", vm.state.value.firmware)
            assertEquals(3, vm.state.value.heard)

            // Slot 0 is the board's unnamed primary — never Knit.
            channel.value = 0
            advanceUntilIdle()
            assertNull(vm.state.value.channelName)
            assertTrue(vm.state.value.channelMismatch)

            // A slot the board has no entry for at all.
            channel.value = 2
            advanceUntilIdle()
            assertNull(vm.state.value.channelName)
            assertTrue(vm.state.value.channelMismatch)
        }

    @Test
    fun `no connected board means no channel verdict`() =
        runTest {
            val vm = start()

            assertEquals(LoraConnState.Off, vm.state.value.connection)
            assertNull(vm.state.value.channelName)
            assertFalse(vm.state.value.channelMismatch)
            assertNull(vm.state.value.firmware)
        }

    @Test
    fun `a connected board reports its battery, a disconnected one does not`() =
        runTest {
            val vm = start()
            val battery = BoardBattery(percent = 78, voltage = 3.92f, powered = false)
            status.value = LoraStatus(state = ready(emptyList()), battery = battery)
            advanceUntilIdle()
            assertEquals(battery, vm.state.value.battery)

            status.value = LoraStatus(state = LinkState.Disconnected("gatt", retryAtMs = 5_000, streak = 1), battery = battery)
            advanceUntilIdle()
            assertNull(vm.state.value.battery)
        }
}
