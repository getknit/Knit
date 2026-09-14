@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.relay

import app.getknit.knit.data.commons.CommonsEntity
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.relay.RelayInviteApplier
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.spool.RelayInvite
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The relays screen's driver, the invite half: a link in the inbox is consumed and previewed onto the
 * sheet, junk is refused as an event, confirming applies and clears, and the row's share action mints
 * the stored URL — token and all — plus the room this device has joined there.
 */
class InternetRelayViewModelTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val relayStatus = mockk<RelayStatusRepository>(relaxed = true)
    private val commons = mockk<CommonsRepository>(relaxed = true)
    private val mesh = mockk<MeshController>(relaxed = true)
    private val applier = mockk<RelayInviteApplier>(relaxed = true)
    private val inbox = RelayInviteInbox()

    private val rooms = MutableStateFlow(emptyList<CommonsEntity>())
    private val secret = ByteArray(32) { (it * 3).toByte() }
    private val preview =
        RelayInviteApplier.Preview(
            url = TOKENED,
            host = "home.example.org",
            private = true,
            alreadyAdded = false,
            parked = false,
            replaces = null,
            planeOff = true,
            consentNeeded = true,
            room = null,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.spoolEnabled } returns flowOf(true)
        every { settings.spoolUrls } returns flowOf(setOf(TOKENED))
        every { settings.disabledSpoolUrls } returns flowOf(emptySet())
        every { relayStatus.statuses } returns flowOf(emptyList())
        every { commons.observeAll() } returns rooms
        coEvery { applier.preview(any(), any()) } returns preview
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = InternetRelayViewModel(settings, relayStatus, commons, mesh, inbox = inbox, applier = applier)

    @Test
    fun aLinkInTheInboxIsPreviewedAndConfirmAppliesIt() =
        runTest {
            inbox.offer(RelayInvite.url(RelayInvite.mint(TOKENED)))
            val vm = vm()
            val events = collect(vm)
            advanceUntilIdle()

            assertNull(inbox.pending.value)
            assertSame(preview, vm.invitePreview.value)
            val parsed = slot<RelayInvite.Parsed.Invite>()
            coVerify { applier.preview(capture(parsed), null) }
            assertEquals(TOKENED, parsed.captured.url)

            vm.confirmInvite()
            advanceUntilIdle()
            coVerify { applier.apply(preview) }
            assertNull(vm.invitePreview.value)
            assertEquals(listOf<InternetRelayEvent>(InternetRelayEvent.InviteApplied("home.example.org")), events)
        }

    @Test
    fun aLinkArrivingWhileTheScreenIsUpStillRaisesTheSheet() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            assertNull(vm.invitePreview.value)
            inbox.offer(RelayInvite.schemeUrl(RelayInvite.mint(TOKENED)))
            advanceUntilIdle()
            assertSame(preview, vm.invitePreview.value)
            vm.dismissInvite()
            assertNull(vm.invitePreview.value)
            coVerify(exactly = 0) { applier.apply(any()) }
        }

    @Test
    fun junkIsRefusedAsAnEventAndNeverPreviewed() =
        runTest {
            val vm = vm()
            val events = collect(vm)
            vm.previewInvite("not a link")
            vm.previewInvite(RelayInvite.url("AAAA"))
            advanceUntilIdle()
            assertEquals(
                listOf<InternetRelayEvent>(
                    InternetRelayEvent.InviteRefused(RelayInvite.Reason.NOT_AN_INVITE),
                    InternetRelayEvent.InviteRefused(RelayInvite.Reason.MALFORMED),
                ),
                events,
            )
            assertNull(vm.invitePreview.value)
            coVerify(exactly = 0) { applier.preview(any(), any()) }
        }

    @Test
    fun sharingARowMintsItsStoredUrlAndTheRoomJoinedThere() =
        runTest {
            rooms.value = listOf(CommonsEntity(conversationId = "c-1", spoolUrl = TOKENED, secret = secret, name = "Home", joinedAt = 1L))
            val vm = vm()
            val events = collect(vm)
            vm.shareInvite(TOKENED)
            vm.copyInvite(OTHER)
            advanceUntilIdle()

            val shared = (events[0] as InternetRelayEvent.ShareInvite).url
            assertTrue(shared.startsWith(RelayInvite.URL_PREFIX))
            val full = RelayInvite.parse(shared, allowCleartext = false) as RelayInvite.Parsed.Invite
            assertEquals(TOKENED, full.url)
            assertArrayEquals(secret, full.secret)
            assertEquals("Home", full.name)

            val copied = (events[1] as InternetRelayEvent.CopyInvite).url
            val bare = RelayInvite.parse(copied, allowCleartext = false) as RelayInvite.Parsed.Invite
            assertEquals(OTHER, bare.url)
            assertNull(bare.secret)
            assertNull(bare.name)
        }

    private fun TestScope.collect(vm: InternetRelayViewModel): List<InternetRelayEvent> {
        val events = mutableListOf<InternetRelayEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        return events
    }

    private companion object {
        const val TOKENED = "wss://home.example.org/spool/v1?k=t0ken"
        const val OTHER = "wss://lax.spool.getknit.app/spool/v1"
    }
}
