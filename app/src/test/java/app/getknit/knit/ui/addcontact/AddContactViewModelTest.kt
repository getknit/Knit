package app.getknit.knit.ui.addcontact

import app.getknit.knit.contacts.ContactImporter
import app.getknit.knit.mesh.crypto.ContactCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Add-contact screen's driver: an inbox link auto-previews, confirm imports and lands on the peer, refusals surface as events. */
class AddContactViewModelTest {
    private val importer = mockk<ContactImporter>(relaxed = true)
    private val inbox = ContactCardInbox()
    private val link = ContactCard.url("A".repeat(260))
    private val ready =
        ContactImporter.Preview.Ready(
            nodeId = "bbbbbbbbbbbbbbbbbbbbbbbbbb",
            displayName = "Bob",
            safetyNumber = "00000 11111",
            alreadyContact = false,
            blocked = false,
            relaysOff = false,
            unknownRelays = emptyList(),
            card = mockk(relaxed = true),
        )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aLinkInTheInboxIsPreviewedAndConfirmImportsIt() =
        runTest {
            coEvery { importer.preview(any()) } returns ready
            inbox.offer(link)
            val vm = AddContactViewModel(inbox, importer)
            val events = mutableListOf<AddContactEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            advanceUntilIdle()

            assertEquals(link, vm.input.value)
            assertEquals(AddContactUiState.Preview(ready), vm.state.value)
            assertEquals(null, inbox.pending.value)

            vm.confirm()
            advanceUntilIdle()
            coVerify { importer.import(ready, false) }
            assertEquals(listOf<AddContactEvent>(AddContactEvent.Imported(ready.nodeId)), events)
            assertEquals(AddContactUiState.Idle, vm.state.value)
        }

    @Test
    fun refusalsSurfaceAsEventsAndLeaveTheFieldEditable() =
        runTest {
            coEvery { importer.preview(any()) } returns ContactImporter.Preview.Invalid(ContactCard.Reason.NOT_A_CARD)
            val vm = AddContactViewModel(inbox, importer)
            val events = mutableListOf<AddContactEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            vm.setInput("junk")
            vm.lookup()
            advanceUntilIdle()
            assertEquals(listOf<AddContactEvent>(AddContactEvent.Invalid), events)
            assertEquals(AddContactUiState.Idle, vm.state.value)

            coEvery { importer.preview(any()) } returns ContactImporter.Preview.Mismatch("Bob")
            vm.lookup()
            advanceUntilIdle()
            assertTrue(events.last() is AddContactEvent.Mismatch)
            coVerify(exactly = 0) { importer.import(any(), any()) }
        }

    @Test
    fun editingTheFieldDropsAStalePreview() =
        runTest {
            coEvery { importer.preview(any()) } returns ready
            val vm = AddContactViewModel(inbox, importer)
            vm.setInput(link)
            vm.lookup()
            advanceUntilIdle()
            assertEquals(AddContactUiState.Preview(ready), vm.state.value)
            vm.setInput(link + "x")
            assertEquals(AddContactUiState.Idle, vm.state.value)
        }
}
