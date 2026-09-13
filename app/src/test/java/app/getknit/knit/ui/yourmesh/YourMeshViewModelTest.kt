@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceTimeBy are experimental kotlinx APIs

package app.getknit.knit.ui.yourmesh

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.peer.MetPeerRepository
import app.getknit.knit.data.settings.ContributionJournal
import app.getknit.knit.data.settings.ContributionTotals
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.ContributionLedger
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The Your mesh state folds its five sources and moves with each: a hand-off shows before the ledger has
 * banked it, a first sighting moves "met", and the carrying count is re-asked with a fresh clock on the tick
 * so an expired-but-unswept custody row ages out of the number.
 *
 * `runCurrent`, never `advanceUntilIdle`: the ViewModel's ticker is periodic, so "idle" never comes and the
 * latter spins the virtual clock forever.
 */
class YourMeshViewModelTest {
    private class FakeJournal : ContributionJournal {
        val saved = MutableStateFlow(ContributionTotals.NONE)
        override val contributionTotals: Flow<ContributionTotals> get() = saved

        override suspend fun addContributions(
            passedAlong: Long,
            deliveredToRecipient: Long,
            now: Long,
        ) {
            saved.value =
                ContributionTotals(saved.value.passedAlong + passedAlong, saved.value.deliveredToRecipient + deliveredToRecipient, now)
        }
    }

    private val mesh = FakeMeshController()
    private val journal = FakeJournal()
    private var clock = 1_000L
    private val ledger = ContributionLedger(journal, selfId = { ME }, clock = { clock })
    private val metCount = MutableStateFlow(0)
    private val carrying = MutableStateFlow(0)
    private val carryingNows = mutableListOf<Long>()
    private val metPeers = mockk<MetPeerRepository> { every { observeCount() } returns metCount }
    private val forward =
        mockk<ForwardRepository> {
            every { observeCarriedForOthers(ME, any()) } answers {
                carryingNows += secondArg<Long>()
                carrying
            }
        }
    private val identity = mockk<Identity> { coEvery { nodeId() } returns ME }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = YourMeshViewModel(mesh, ledger, metPeers, forward, identity, clock = { clock })

    @Test
    fun theStateFoldsEverySourceAndMovesWithEach() =
        runTest {
            journal.saved.value = ContributionTotals(passedAlong = 10, deliveredToRecipient = 4, since = 500L)
            mesh.neighborCount.value = 2
            mesh.transportHealth.value = TransportHealth.Degraded
            metCount.value = 7
            carrying.value = 3
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            assertEquals(
                YourMeshUiState(
                    nearbyCount = 2,
                    health = TransportHealth.Degraded,
                    carryingNow = 3,
                    passedAlong = 10,
                    handedDirect = 4,
                    peopleMet = 7,
                    since = 500L,
                ),
                vm.state.value,
            )

            // A hand-off is visible at once, before any flush banks it.
            ledger.onHandedOff(
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = "f1",
                    senderId = "alice",
                    sentAt = 1L,
                    recipientId = "carol",
                    payload = ByteArray(0),
                ),
                setOf("carol"),
            )
            runCurrent()
            assertEquals(11L, vm.state.value.passedAlong)
            assertEquals(5L, vm.state.value.handedDirect)

            metCount.value = 8
            mesh.neighborCount.value = 3
            carrying.value = 4
            runCurrent()
            assertEquals(8, vm.state.value.peopleMet)
            assertEquals(3, vm.state.value.nearbyCount)
            assertEquals(4, vm.state.value.carryingNow)
        }

    @Test
    fun theCarryingCountIsReaskedWithAFreshClockOnTheTick() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            assertEquals(listOf(1_000L), carryingNows)
            clock = 2_000L
            advanceTimeBy(YourMeshViewModel.REFRESH_MS + 1)
            assertEquals(listOf(1_000L, 2_000L), carryingNows)
        }

    private companion object {
        const val ME = "me"
    }
}
