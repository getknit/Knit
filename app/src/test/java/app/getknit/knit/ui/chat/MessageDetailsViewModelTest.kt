package app.getknit.knit.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.ui.msg
import app.getknit.knit.ui.peer
import app.getknit.knit.ui.reaction
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen that answers "who are those three 👍?". The contract worth pinning: the per-reactor identity
 * survives (the chat's [ReactionSummary] tally aggregates it away), an unknown reactor still reads as a
 * stable alias rather than a raw node id, and the filter chips agree with the row count they filter.
 */
@RunWith(AndroidJUnit4::class)
class MessageDetailsViewModelTest {
    private val messages = mockk<MessageRepository>(relaxed = true)
    private val reactions = mockk<ReactionRepository>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)

    private val messageFlow = MutableStateFlow<MessageEntity?>(msg(senderId = "me", body = "snacks", id = MSG))
    private val reactionsFlow = MutableStateFlow(emptyList<ReactionEntity>())
    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val filteringFlow = MutableStateFlow(true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { messages.observeMessage(MSG) } returns messageFlow
        every { reactions.observeReactionsFor(MSG) } returns reactionsFlow
        every { peers.observePeers() } returns peersFlow
        every { settings.contentFilteringEnabled } returns filteringFlow
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = MessageDetailsViewModel(MSG, messages, reactions, peers, settings, identity)

    @Test
    fun `every reactor keeps its identity, resolved to a display name`() =
        runTest {
            peersFlow.value = listOf(peer("sam", name = "Sam Rivera"))
            reactionsFlow.value =
                listOf(
                    reaction(MSG, "sam", "👍", updatedAt = 10L),
                    reaction(MSG, "me", "❤️", updatedAt = 20L),
                )

            val state = collect(viewModel())

            assertEquals(listOf("Sam Rivera", "You"), state.reactors.map { if (it.isSelf) "You" else it.displayName })
            assertEquals(listOf("👍", "❤️"), state.reactors.map { it.emoji })
            assertEquals(listOf(false, true), state.reactors.map { it.isSelf })
        }

    @Test
    fun `a reactor whose profile never arrived reads as an alias, not a node id`() =
        runTest {
            reactionsFlow.value = listOf(reaction(MSG, "stranger01", "👍", updatedAt = 1L))

            val state = collect(viewModel())

            val name = state.reactors.single().displayName
            assertEquals(Alias.aliasFor("stranger01"), name)
            assertFalse(name.contains("stranger01"))
        }

    @Test
    fun `filters count per emoji, most-reacted first`() =
        runTest {
            reactionsFlow.value =
                listOf(
                    reaction(MSG, "a", "❤️", updatedAt = 1L),
                    reaction(MSG, "b", "👍", updatedAt = 2L),
                    reaction(MSG, "c", "👍", updatedAt = 3L),
                )

            val state = collect(viewModel())

            assertEquals(listOf("👍" to 2, "❤️" to 1), state.filters.map { it.emoji to it.count })
            // The All chip's total is the row count it filters — the two can't disagree.
            assertEquals(state.reactors.size, state.filters.sumOf { it.count })
        }

    @Test
    fun `metadata comes off the stored row — sender, sent time, delivery, plane`() =
        runTest {
            messageFlow.value =
                msg(senderId = "sam", sentAt = 4_242L, received = true, id = MSG)
                    .copy(receivedVia = DeliveryPlane.Internet.code)
            peersFlow.value = listOf(peer("sam", name = "Sam Rivera"))

            val state = collect(viewModel())

            assertEquals("Sam Rivera", state.senderName)
            assertEquals(4_242L, state.sentAt)
            assertFalse(state.mine)
            assertEquals(DeliveryStatus.Delivered, state.delivery)
            assertEquals(DeliveryPlane.Internet, state.plane)
        }

    @Test
    fun `a never-sealed DM reports Pending, not Sent`() =
        runTest {
            messageFlow.value = msg(senderId = "me", recipientId = "bob", id = MSG).copy(pendingKey = true)

            assertEquals(DeliveryStatus.Pending, collect(viewModel()).delivery)
        }

    @Test
    fun `deleting a message we were looking at marks it vanished so the screen can close`() =
        runTest {
            val vm = viewModel()
            assertFalse(collect(vm).vanished)

            messageFlow.value = null

            assertTrue(collect(vm).vanished)
        }

    @Test
    fun `a row that has not been written yet is not vanished — the screen must stay open`() =
        runTest {
            // A deep link can outrun the write (the seeded build does exactly this). Reading null before
            // ever seeing the row must not read as a delete, or the screen closes the moment it opens.
            messageFlow.value = null
            val vm = viewModel()
            assertFalse(collect(vm).vanished)

            messageFlow.value = msg(senderId = "me", body = "snacks", id = MSG)
            val arrived = collect(vm)
            assertFalse(arrived.vanished)
            assertEquals("snacks", arrived.body)
        }

    /** Subscribes (the state is `WhileSubscribed`) and lets the combine settle. */
    private fun TestScope.collect(vm: MessageDetailsViewModel): MessageDetailsUiState {
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        job.cancel()
        return vm.state.value
    }

    private companion object {
        const val MSG = "m1"
    }
}
