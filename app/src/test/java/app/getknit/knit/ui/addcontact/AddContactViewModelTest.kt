package app.getknit.knit.ui.addcontact

import app.getknit.knit.contacts.ContactCards
import app.getknit.knit.contacts.ContactImporter
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.ContactCard
import app.getknit.knit.mesh.crypto.VerifyPayload
import app.getknit.knit.ui.peer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

/**
 * The Add-contact screen's driver, both halves: **by link** — an inbox link auto-previews, confirm imports
 * and lands on the peer, refusals surface as events — and **in person** — a scanned self-certifying code
 * pins + verifies its peer, a forged/malformed code is refused, and our own code is a no-op. Codes are
 * built the way a real device's QR is: [VerifyPayload.encode] of a bundle and the node id it
 * self-certifies to ([NodeId.fromPublicKeyBundle]).
 */
class AddContactViewModelTest {
    private val importer = mockk<ContactImporter>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)
    private val cards = mockk<ContactCards>(relaxed = true)
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

    // A self-consistent local identity: the node id derives from the bundle, so a "scan my own code" case
    // (encode(myId, myBundle)) passes the self-certifying check and hits the SELF branch.
    private val myBundle = "MY-BUNDLE"
    private val myId = NodeId.fromPublicKeyBundle(myBundle)

    // A peer's self-consistent identity.
    private val peerBundle = "PEER-BUNDLE"
    private val peerId = NodeId.fromPublicKeyBundle(peerBundle)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns myId
        every { identity.publicKeyBundle() } returns myBundle
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = AddContactViewModel(inbox, importer, peers, identity, cards)

    @Test
    fun aLinkInTheInboxIsPreviewedAndConfirmImportsIt() =
        runTest {
            coEvery { importer.preview(any()) } returns ready
            inbox.offer(link)
            val vm = vm()
            val events = collect(vm)
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
            val vm = vm()
            val events = collect(vm)
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
            val vm = vm()
            vm.setInput(link)
            vm.lookup()
            advanceUntilIdle()
            assertEquals(AddContactUiState.Preview(ready), vm.state.value)
            vm.setInput(link + "x")
            assertEquals(AddContactUiState.Idle, vm.state.value)
        }

    @Test
    fun scanningANewPeerPinsAndVerifiesItWithoutClobberingLwwOrder() =
        runTest {
            val vm = vm()
            val events = collect(vm)
            coEvery { peers.find(peerId) } returns null

            vm.onScanned(VerifyPayload.encode(peerId, peerBundle))
            advanceUntilIdle()

            assertEquals(listOf<AddContactEvent>(AddContactEvent.Scanned(VerifyResult.VERIFIED)), events)
            // Pinned + verified, with updatedAt left at 0 so a later real profile frame still wins the
            // last-writer-wins check in handleProfile and can fill in the name/avatar.
            coVerify {
                peers.upsert(
                    match { it.nodeId == peerId && it.pubKey == peerBundle && it.verified && it.updatedAt == 0L },
                )
            }
        }

    @Test
    fun scanningAKnownPeerWithTheSameKeyJustMarksVerified() =
        runTest {
            val vm = vm()
            val events = collect(vm)
            coEvery { peers.find(peerId) } returns peer(peerId, pubKey = peerBundle)

            vm.onScanned(VerifyPayload.encode(peerId, peerBundle))
            advanceUntilIdle()

            assertEquals(listOf<AddContactEvent>(AddContactEvent.Scanned(VerifyResult.VERIFIED)), events)
            coVerify { peers.setVerified(peerId, true) }
            coVerify(exactly = 0) { peers.upsert(any()) }
        }

    @Test
    fun scanningAPeerWhoseKeyDiffersFromThePinnedOneIsRefused() =
        runTest {
            val vm = vm()
            val events = collect(vm)
            coEvery { peers.find(peerId) } returns peer(peerId, pubKey = "A-DIFFERENT-PINNED-KEY")

            vm.onScanned(VerifyPayload.encode(peerId, peerBundle))
            advanceUntilIdle()

            assertEquals(listOf<AddContactEvent>(AddContactEvent.Scanned(VerifyResult.MISMATCH)), events)
            coVerify(exactly = 0) { peers.upsert(any()) }
            coVerify(exactly = 0) { peers.setVerified(any(), any()) }
        }

    @Test
    fun scanningOurOwnCodeIsANoOp() =
        runTest {
            val vm = vm()
            val events = collect(vm)

            vm.onScanned(VerifyPayload.encode(myId, myBundle))
            advanceUntilIdle()

            assertEquals(listOf<AddContactEvent>(AddContactEvent.Scanned(VerifyResult.SELF)), events)
            coVerify(exactly = 0) { peers.upsert(any()) }
            coVerify(exactly = 0) { peers.setVerified(any(), any()) }
        }

    @Test
    fun aMalformedCodeIsInvalid() =
        runTest {
            val vm = vm()
            val events = collect(vm)

            vm.onScanned("definitely-not-a-knit-code")
            advanceUntilIdle()

            assertEquals(listOf<AddContactEvent>(AddContactEvent.Scanned(VerifyResult.INVALID)), events)
            coVerify(exactly = 0) { peers.upsert(any()) }
            coVerify(exactly = 0) { peers.setVerified(any(), any()) }
        }

    @Test
    fun aCodeWhoseKeyDoesNotDeriveToItsNodeIdIsInvalid() =
        runTest {
            val vm = vm()
            val events = collect(vm)

            // Claims peerId but carries someone else's key — not self-certifying, so it's refused.
            vm.onScanned(VerifyPayload.encode(peerId, "SOMEONE-ELSES-KEY"))
            advanceUntilIdle()

            assertEquals(listOf<AddContactEvent>(AddContactEvent.Scanned(VerifyResult.INVALID)), events)
            coVerify(exactly = 0) { peers.upsert(any()) }
            coVerify(exactly = 0) { peers.setVerified(any(), any()) }
        }

    @Test
    fun theContactLinkIsMintedForTheShareSheetAndTheClipboard() =
        runTest {
            coEvery { cards.mint() } returns
                ContactCards.Minted(compact = "compact", url = link, schemeUrl = "knit://c/compact")
            val vm = vm()
            val events = collect(vm)

            vm.shareLink()
            vm.copyLink()
            advanceUntilIdle()

            assertEquals(
                listOf<AddContactEvent>(AddContactEvent.ShareLink(link), AddContactEvent.CopyLink(link)),
                events,
            )
        }

    /** Records every one-shot event the screen would react to; the flow has no replay, so collect first. */
    private fun TestScope.collect(vm: AddContactViewModel): List<AddContactEvent> {
        val events = mutableListOf<AddContactEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        return events
    }
}
