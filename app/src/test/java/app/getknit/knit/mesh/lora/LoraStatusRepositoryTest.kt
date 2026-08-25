package app.getknit.knit.mesh.lora

import app.getknit.knit.data.settings.SettingsStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pushed sibling of `RelayStatusRepository`: settings + the transport's live status folded into the
 * facts the chat header reads. Runs against the debug build, where `BuildConfig.LORA_PLANE` is on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoraStatusRepositoryTest {
    private val enabled = MutableStateFlow(true)
    private val address = MutableStateFlow<String?>("AA:BB:CC:DD:EE:FF")
    private val dms = MutableStateFlow(true)
    private val status = MutableStateFlow(LoraStatus())
    private val settings =
        mockk<SettingsStore> {
            every { loraEnabled } returns enabled
            every { loraDeviceAddress } returns address
            every { loraDmEnabled } returns dms
        }
    private val lora =
        object : LoraPlaneStatus {
            override val status: StateFlow<LoraStatus> = this@LoraStatusRepositoryTest.status

            override suspend fun provisionKnitChannel(): ProvisionResult = ProvisionResult.NotReady(LinkState.Idle)
        }
    private val repo = LoraStatusRepository(settings, lora)
    private val ready =
        LinkState.Ready(
            board = BoardInfo(myNodeNum = 7u, pioEnv = "heltec-v4", firmwareVersion = "2.5.0"),
            channels = emptyList(),
            mtu = 512,
        )

    @Test
    fun `facts follow the link and the switches`() =
        runTest {
            // Bound but idle: the board is expected to be working, so the glyph is drawn struck through.
            assertEquals(LoraFacts(LoraPlane.Down, dms = true), repo.facts.first())

            status.value = LoraStatus(state = ready)
            assertEquals(LoraFacts(LoraPlane.Live, dms = true), repo.facts.first())

            dms.value = false
            assertEquals(LoraFacts(LoraPlane.Live, dms = false), repo.facts.first())

            // Off outranks a still-ready link, and the DM switch reads false once the plane is off.
            dms.value = true
            enabled.value = false
            assertEquals(LoraFacts(LoraPlane.Off, dms = false), repo.facts.first())
        }

    @Test
    fun `status churn that leaves the facts unchanged emits nothing`() =
        runTest {
            val seen = mutableListOf<LoraFacts>()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.facts.collect { seen += it } }

            status.value = LoraStatus(state = LinkState.Connecting) // still Down
            status.value = LoraStatus(state = LinkState.Connecting, heard = 3) // still Down — a peer count is not the header's business
            status.value = LoraStatus(state = ready)

            assertEquals(listOf(LoraFacts(LoraPlane.Down, dms = true), LoraFacts(LoraPlane.Live, dms = true)), seen)
            job.cancel()
        }

    @Test
    fun `the battery rides the facts only while the link is live`() =
        runTest {
            val battery = BoardBattery(percent = 78, voltage = 3.92f, powered = false)
            status.value = LoraStatus(state = LinkState.Idle, battery = battery)
            assertEquals(LoraFacts(LoraPlane.Down, dms = true), repo.facts.first())
            status.value = LoraStatus(state = ready, battery = battery)
            assertEquals(LoraFacts(LoraPlane.Live, dms = true, battery = battery), repo.facts.first())
        }
}
