package app.getknit.knit.ui.chat

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.emoji.RecentReactions
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.msg
import app.getknit.knit.ui.peer
import app.getknit.knit.ui.reaction
import app.getknit.knit.ui.voice.VoicePlayer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The richest ViewModel: a 5-way state combine plus the send double-submit guard and the attach room-vs-DM
 * flagged branch. Robolectric-hosted for `context.getString`. Every flow feeding the combine (including the
 * inner `blobState` combine — hashes + flagged + filtering) is stubbed, or the whole state stalls.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("LargeClass") // cohesive single-SUT suite over one shared vm()/stubDm() harness, as MeshManagerTest
class ChatViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val messages = mockk<MessageRepository>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val reactions = mockk<ReactionRepository>(relaxed = true)
    private val receipts = mockk<MessageReceiptRepository>(relaxed = true)
    private val mesh = FakeMeshController()
    private val identity = mockk<Identity>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val notifier = mockk<Notifier>(relaxed = true)
    private val attachments = mockk<AttachmentStore>(relaxed = true)
    private val blobs = mockk<BlobRepository>(relaxed = true)
    private val imageScreening = mockk<ImageScreeningService>(relaxed = true)
    private val gallerySaver = mockk<GallerySaver>(relaxed = true)
    private val voicePlayer = mockk<VoicePlayer>(relaxed = true)

    private val messagesFlow = MutableStateFlow(emptyList<MessageEntity>())
    private val reactionsFlow = MutableStateFlow(emptyList<ReactionEntity>())
    private val recentsFlow = MutableStateFlow(RecentReactions.DEFAULTS)
    private val blockedFlow = MutableStateFlow(emptySet<String>())
    private val sizesFlow = MutableStateFlow(emptyMap<String, Int>())
    private val flaggedFlow = MutableStateFlow(emptyList<String>())
    private val filteringFlow = MutableStateFlow(true)
    private val groupFlow = MutableStateFlow<GroupEntity?>(null)
    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val nameFlow = MutableStateFlow("Alice")
    private val spoolEnabledFlow = MutableStateFlow(false)
    private val spoolUrlsFlow = MutableStateFlow(emptySet<String>())
    private val activeSpoolUrlsFlow = MutableStateFlow(emptySet<String>())
    private val relayFactsFlow = MutableStateFlow(RelayFacts())
    private val relayRoomNoticeDismissedFlow = MutableStateFlow(false)
    private val loraFactsFlow = MutableStateFlow(LoraFacts())
    private val deliveredCountsFlow = MutableStateFlow(emptyMap<String, Int>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { messages.observeMessages(Conversations.NEARBY) } returns messagesFlow
        every { reactions.observeReactions() } returns reactionsFlow
        every { settings.blockedNodeIds } returns blockedFlow
        every { settings.recentReactions } returns recentsFlow
        every { blobs.observeSizes() } returns sizesFlow
        every { imageScreening.observeFlaggedHashes() } returns flaggedFlow
        every { settings.contentFilteringEnabled } returns filteringFlow
        every { groups.observeGroup(Conversations.NEARBY) } returns groupFlow
        every { messages.observeMessages(GROUP) } returns messagesFlow
        every { groups.observeGroup(GROUP) } returns groupFlow
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
        every { settings.displayName } returns nameFlow
        // A relaxed mock would hand back a Flow that never emits, and RelayStatusRepository
        // combines these — one silent flow would stall every state assertion in this class.
        every { settings.spoolEnabled } returns spoolEnabledFlow
        every { settings.spoolUrls } returns spoolUrlsFlow
        every { settings.activeSpoolUrls } returns activeSpoolUrlsFlow
        every { settings.relayRoomNoticeDismissed } returns relayRoomNoticeDismissedFlow
        every { receipts.observeDeliveredCounts(any(), any()) } returns deliveredCountsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun group(
        groupId: String,
        members: List<String>,
    ) = GroupEntity(
        groupId = groupId,
        name = "Trailhead Crew",
        members = GroupMembersStore.encode(members),
        createdBy = "me",
        createdAt = 0L,
    )

    /** The per-thread flows setUp stubs only for the room and the group — a DM thread needs its own. */
    private fun stubDm(peerId: String) {
        every { messages.observeMessages(peerId) } returns messagesFlow
        every { groups.observeGroup(peerId) } returns groupFlow
    }

    private fun vm(conversationId: String = Conversations.NEARBY) =
        ChatViewModel(
            conversationId,
            messages,
            groups,
            peers,
            reactions,
            receipts,
            mesh,
            identity,
            settings,
            notifier,
            attachments,
            blobs,
            imageScreening,
            gallerySaver,
            voicePlayer,
            // A finite flow, not the production poller: RelayStatusRepository emits on an infinite
            // `while(true) { emit; delay }`, and under runTest's virtual clock that delay is instant, so
            // `advanceUntilIdle()` below would never reach idle.
            relayFactsFlow,
            loraFactsFlow,
            context,
        )

    @Test
    fun groupRowsCarryDeliveredCountsExcludingSelf() =
        runTest {
            // The bubble's ✓✓ flips on the first member's ack, so the row carries the ratio the glyph
            // can't. The denominator excludes us — we never ack our own message (the details screen's
            // rule, kept identical so the two screens can't disagree).
            val vm = vm(GROUP)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupFlow.value = group(GROUP, members = listOf("me", "sam", "priya", "theo"))
            messagesFlow.value =
                listOf(
                    msg(senderId = "me", body = "mine", id = "g0", sentAt = 100, conversationId = GROUP),
                    msg(senderId = "sam", body = "theirs", id = "g1", sentAt = 200, conversationId = GROUP),
                )
            deliveredCountsFlow.value = mapOf("g0" to 2)
            advanceUntilIdle()

            val mine =
                vm.state.value.rows
                    .first { it.id == "g0" }
            assertEquals(2, mine.deliveredCount)
            assertEquals(3, mine.recipientTotal)
            // Someone else's message has no "who has it" answer to give.
            val theirs =
                vm.state.value.rows
                    .first { it.id == "g1" }
            assertEquals(0, theirs.deliveredCount)
            assertEquals(0, theirs.recipientTotal)
        }

    @Test
    fun roomRowsCarryNoDeliveredCounts() =
        runTest {
            // The broadcast room has no roster, so there is no denominator and the tick keeps its plain
            // wording — deliveryLabel falls back whenever total is absent.
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(msg(senderId = "me", body = "hi", id = "m0", sentAt = 100, conversationId = Conversations.NEARBY))
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .single()
            assertEquals(0, row.deliveredCount)
            assertEquals(0, row.recipientTotal)
        }

    @Test
    fun rowsProjectMessagesAndResolveSenderNames() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("bob", name = "Bob"))
            messagesFlow.value =
                listOf(
                    msg(senderId = "me", body = "hi", id = "m0", sentAt = 100, conversationId = Conversations.NEARBY),
                    msg(senderId = "bob", body = "yo", id = "m1", sentAt = 200, conversationId = Conversations.NEARBY),
                )
            advanceUntilIdle()

            val rows = vm.state.value.rows
            assertEquals(2, rows.size)
            val mine = rows.first { it.id == "m0" }
            assertTrue(mine.mine)
            assertEquals("Alice", mine.senderName) // own name is the persisted display name
            val theirs = rows.first { it.id == "m1" }
            assertFalse(theirs.mine)
            assertEquals("Bob", theirs.senderName)
            assertTrue(vm.state.value.isRoom)
        }

    /** Two senders who both call themselves Bob are told apart by their alias (ADR 058); a unique name is untouched. */
    @Test
    fun sameNamedSendersAreLabelledWithTheirAliasAndTheQuoteSnapshotStaysPlain() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("bob", name = "Bob"), peer("bob2", name = "bob"), peer("carol", name = "Carol"))
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", body = "yo", id = "m1", sentAt = 100, conversationId = Conversations.NEARBY),
                    msg(senderId = "bob2", body = "also yo", id = "m2", sentAt = 200, conversationId = Conversations.NEARBY),
                    msg(senderId = "carol", body = "hi", id = "m3", sentAt = 300, conversationId = Conversations.NEARBY),
                )
            advanceUntilIdle()

            val rows =
                vm.state.value.rows
                    .associateBy { it.id }
            assertEquals("Bob (${Alias.aliasFor("bob")})", rows.getValue("m1").senderName)
            assertEquals(Alias.aliasFor("bob"), rows.getValue("m1").senderDiscriminator)
            assertEquals("Bob", rows.getValue("m1").senderPlainName) // the reply-quote snapshot never carries the suffix
            assertEquals("bob (${Alias.aliasFor("bob2")})", rows.getValue("m2").senderName)
            assertEquals("Carol", rows.getValue("m3").senderName)
            assertNull(rows.getValue("m3").senderDiscriminator)
            assertEquals("Carol", rows.getValue("m3").senderPlainName)
            // The mention picker inserts the same label, and always knows the alias.
            val candidates =
                vm.state.value.mentionCandidates
                    .associateBy { it.nodeId }
            assertEquals("Bob (${Alias.aliasFor("bob")})", candidates.getValue("bob").displayName)
            assertEquals(Alias.aliasFor("bob"), candidates.getValue("bob").discriminator)
            assertEquals("Carol", candidates.getValue("carol").displayName)
            assertNull(candidates.getValue("carol").discriminator)
            assertEquals(Alias.aliasFor("carol"), candidates.getValue("carol").alias)
        }

    @Test
    fun aDmTitleCarriesTheDiscriminatorWhenAnotherPeerSharesTheName() =
        runTest {
            stubDm("bob")
            val vm = vm("bob")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("bob", name = "Bob"), peer("bob2", name = "Bob"))
            advanceUntilIdle()

            assertEquals("Bob (${Alias.aliasFor("bob")})", vm.state.value.title)
            assertEquals(Alias.aliasFor("bob"), vm.state.value.titleDiscriminator)

            peersFlow.value = listOf(peer("bob", name = "Bob"), peer("bob2", name = "Robert"))
            advanceUntilIdle()
            assertEquals("Bob", vm.state.value.title)
            assertNull(vm.state.value.titleDiscriminator)
        }

    @Test
    fun blockedSendersRowsAreFilteredOut() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY),
                    msg(senderId = "me", id = "m2", conversationId = Conversations.NEARBY),
                )
            blockedFlow.value = setOf("bob")
            advanceUntilIdle()

            assertEquals(
                listOf("m2"),
                vm.state.value.rows
                    .map { it.id },
            )
        }

    @Test
    fun reactionsAreTalliedPerEmojiWithTheMineFlag() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY))
            reactionsFlow.value =
                listOf(
                    reaction("m1", "me", "👍"),
                    reaction("m1", "carol", "👍"),
                    reaction("m1", "dave", "❤️"),
                )
            advanceUntilIdle()

            val tallies =
                vm.state.value.rows
                    .single { it.id == "m1" }
                    .reactions
                    .associateBy { it.emoji }
            assertEquals(2, tallies.getValue("👍").count)
            assertTrue("we reacted with the thumbs-up", tallies.getValue("👍").mine)
            assertEquals(1, tallies.getValue("❤️").count)
            assertFalse(tallies.getValue("❤️").mine)
        }

    @Test
    fun recentReactionsExposeTheNewestSix() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.recentReactions.collect {} }
            recentsFlow.value = (1..RecentReactions.KEPT).map { "e$it" }
            advanceUntilIdle()

            assertEquals((1..RecentReactions.SHOWN).map { "e$it" }, vm.recentReactions.value)
        }

    @Test
    fun reactingWithANewEmojiSendsItAndFrontsTheRecents() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY))
            advanceUntilIdle()

            vm.react("m1", "🦄")
            advanceUntilIdle()

            assertEquals(listOf("m1" to "🦄"), mesh.sentReactions)
            coVerify(exactly = 1) { settings.recordReaction("🦄") }
        }

    @Test
    fun retractingYourOwnReactionSendsButLeavesTheRecentsAlone() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY))
            reactionsFlow.value = listOf(reaction("m1", "me", "👍"))
            advanceUntilIdle()

            vm.react("m1", "👍") // the chip you already own: a toggle-off, not a choice
            vm.react("m1", "❤️") // a replace: a fresh choice
            advanceUntilIdle()

            assertEquals(listOf("m1" to "👍", "m1" to "❤️"), mesh.sentReactions)
            coVerify(exactly = 0) { settings.recordReaction("👍") }
            coVerify(exactly = 1) { settings.recordReaction("❤️") }
        }

    @Test
    fun attachmentReadinessAndFlaggingTrackTheBlobFlowsAndFilteringToggle() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY, attachmentHash = "h1"))
            advanceUntilIdle()
            // Not present yet → loading; not ready.
            assertFalse(
                vm.state.value.rows
                    .single()
                    .attachmentReady,
            )

            sizesFlow.value = mapOf("h1" to 1_024)
            flaggedFlow.value = listOf("h1")
            advanceUntilIdle()
            assertTrue(
                vm.state.value.rows
                    .single()
                    .attachmentReady,
            )
            assertTrue(
                "filtering on → flagged attachment is blurred",
                vm.state.value.rows
                    .single()
                    .attachmentFlagged,
            )

            filteringFlow.value = false
            advanceUntilIdle()
            assertFalse(
                "filtering off → not blurred",
                vm.state.value.rows
                    .single()
                    .attachmentFlagged,
            )
        }

    @Test
    fun moderationFlagIsGatedOnTheContentFilteringToggle() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "bob",
                        id = "m1",
                        conversationId = Conversations.NEARBY,
                        moderation = MessageEntity.MODERATION_TEXT_FLAGGED,
                    ),
                )
            advanceUntilIdle()
            assertTrue(
                vm.state.value.rows
                    .single()
                    .moderationFlagged,
            )

            filteringFlow.value = false
            advanceUntilIdle()
            assertFalse(
                vm.state.value.rows
                    .single()
                    .moderationFlagged,
            )
        }

    @Test
    fun sendGuardBlocksDoubleSubmitUntilTheInputIsCleared() =
        runTest {
            val vm = vm()

            vm.send("hi")
            vm.send("hi") // re-entrant tap while the first send holds the guard
            advanceUntilIdle()
            assertEquals(1, mesh.sentChats.size)

            vm.onInputCleared() // screen reports the field cleared → guard released
            vm.send("again")
            advanceUntilIdle()
            assertEquals(2, mesh.sentChats.size)
        }

    @Test
    fun aBlockedSendReleasesTheGuardAndEmitsTheBlockedEvent() =
        runTest {
            mesh.sendChatResult = false // moderator flags the text
            val vm = vm()
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.send("bad")
            advanceUntilIdle()
            assertTrue(events.contains(R.string.moderation_text_blocked))

            // Guard was released (not held on a rejected send), so a follow-up send goes through.
            vm.send("bad-again")
            advanceUntilIdle()
            assertEquals(2, mesh.sentChats.size)
        }

    @Test
    fun isSendingTracksTheSendLifecycleForTheSpinner() =
        runTest {
            val vm = vm()
            assertFalse("idle before any send", vm.isSending.value)

            vm.send("hi")
            advanceUntilIdle()
            // An accepted send holds the signal (like the double-submit guard it backs) until the screen
            // reports the field cleared, so the send-button spinner stays put across the
            // sendChat → clearInput → clearText hop rather than blinking off mid-send.
            assertTrue("send in flight → spinner shown", vm.isSending.value)

            vm.onInputCleared()
            assertFalse("field cleared → spinner cleared", vm.isSending.value)
        }

    @Test
    fun isSendingClearsWhenASendIsBlocked() =
        runTest {
            mesh.sendChatResult = false // moderator flags the text
            val vm = vm()

            vm.send("bad")
            advanceUntilIdle()
            assertFalse("a blocked send releases the spinner signal", vm.isSending.value)
        }

    @Test
    fun attachingAFlaggedImageInTheRoomBlocksItInsteadOfStaging() =
        runTest {
            val uri = Uri.parse("content://images/1")
            val ingested = AttachmentStore.Ingested(hash = "h1", mime = "image/jpeg")
            coEvery { attachments.ingest(uri) } returns AttachmentStore.IngestResult.Success(ingested, flagged = true)
            val vm = vm() // Nearby room

            vm.attach(uri)
            advanceUntilIdle()

            assertNull("flagged image is not staged in the public room", vm.pendingAttachment.value)
            coVerify { blobs.deleteIfUnreferenced("h1") }
        }

    @Test
    fun attachingACleanImageStagesItForSending() =
        runTest {
            val uri = Uri.parse("content://images/2")
            val ingested = AttachmentStore.Ingested(hash = "h2", mime = "image/jpeg")
            coEvery { attachments.ingest(uri) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            val vm = vm()

            vm.attach(uri)
            advanceUntilIdle()

            assertEquals(ingested, vm.pendingAttachment.value)
        }

    @Test
    fun capturingACleanPhotoStagesItForSending() =
        runTest {
            val jpeg = byteArrayOf(1, 2, 3)
            val ingested = AttachmentStore.Ingested(hash = "h3", mime = "image/jpeg")
            coEvery {
                attachments.ingest(jpeg, "image/jpeg")
            } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            val vm = vm()

            vm.attachCaptured(jpeg)
            advanceUntilIdle()

            assertEquals(ingested, vm.pendingAttachment.value)
        }

    @Test
    fun capturingAFlaggedPhotoInTheRoomBlocksItInsteadOfStaging() =
        runTest {
            val jpeg = byteArrayOf(4, 5, 6)
            val ingested = AttachmentStore.Ingested(hash = "h4", mime = "image/jpeg")
            coEvery {
                attachments.ingest(jpeg, "image/jpeg")
            } returns AttachmentStore.IngestResult.Success(ingested, flagged = true)
            val vm = vm() // Nearby room

            vm.attachCaptured(jpeg)
            advanceUntilIdle()

            assertNull("flagged photo is not staged in the public room", vm.pendingAttachment.value)
            coVerify { blobs.deleteIfUnreferenced("h4") }
        }

    /** A failed capture has to say so: unlike a pick, the shot exists nowhere else to try again from. */
    @Test
    fun aFailedCaptureSurfacesAnErrorWhereAFailedPickStaysSilent() =
        runTest {
            val jpeg = byteArrayOf(7, 8, 9)
            val uri = Uri.parse("content://images/3")
            coEvery { attachments.ingest(jpeg, "image/jpeg") } returns AttachmentStore.IngestResult.Failed
            coEvery { attachments.ingest(uri) } returns AttachmentStore.IngestResult.Failed
            val vm = vm()
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.attach(uri)
            advanceUntilIdle()
            assertTrue("a failed pick stays silent", events.isEmpty())

            vm.attachCaptured(jpeg)
            advanceUntilIdle()

            assertTrue(events.contains(R.string.chat_image_capture_failed))
            assertNull(vm.pendingAttachment.value)
        }

    /**
     * The bug in knit/knit-next#31: a DM/group attachment's stored blob is `iv || ciphertext`, so exporting
     * it verbatim wrote ciphertext into the gallery under an image mime and still toasted success. The saved
     * bytes must be the same plaintext the bubble renders.
     */
    @Test
    fun savingAnEncryptedAttachmentExportsThePlaintext() =
        runTest {
            val plain = byteArrayOf(1, 2, 3, 4, 5)
            val sealed = AttachmentCrypto.seal(plain)
            coEvery { blobs.bytes("ct") } returns sealed.blob
            coEvery { gallerySaver.saveToPictures(any(), any(), any()) } returns true
            val vm = vm()
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.saveAttachment("ct", b64(sealed.key), "image/webp")
            advanceUntilIdle()

            val exported = slot<ByteArray>()
            coVerify { gallerySaver.saveToPictures(capture(exported), "ct", "image/webp") }
            assertArrayEquals("saved the decrypted image, not the stored ciphertext", plain, exported.captured)
            assertTrue(events.contains(R.string.chat_image_saved))
        }

    /** A key-less blob (a Nearby-room attachment) is already plaintext and goes out untouched. */
    @Test
    fun savingAPlaintextAttachmentExportsTheStoredBytes() =
        runTest {
            val jpeg = byteArrayOf(9, 8, 7)
            coEvery { blobs.bytes("h") } returns jpeg
            coEvery { gallerySaver.saveToPictures(any(), any(), any()) } returns true
            val vm = vm()

            vm.saveAttachment("h", null, "image/jpeg")
            advanceUntilIdle()

            val exported = slot<ByteArray>()
            coVerify { gallerySaver.saveToPictures(capture(exported), "h", "image/jpeg") }
            assertArrayEquals(jpeg, exported.captured)
        }

    /** A key that doesn't open the blob must fail the save loudly rather than export the ciphertext. */
    @Test
    fun savingWithAKeyThatDoesNotOpenTheBlobFailsInsteadOfExportingCiphertext() =
        runTest {
            val sealed = AttachmentCrypto.seal(byteArrayOf(1, 2, 3, 4, 5))
            coEvery { blobs.bytes("ct") } returns sealed.blob
            val vm = vm()
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            val wrongKey = ByteArray(32) // a valid AES-256 length, but not the key it was sealed under
            vm.saveAttachment("ct", b64(wrongKey), "image/webp")
            advanceUntilIdle()

            coVerify(exactly = 0) { gallerySaver.saveToPictures(any(), any(), any()) }
            assertTrue(events.contains(R.string.chat_image_save_failed))
        }

    /**
     * ADR 035: the `blobs` row's mime describes the *ciphertext* bytes and is only whatever named the blob
     * when it landed (a fetcher default on the spool path). The message row's mime is the plaintext's own
     * type and wins; the blob row is the fallback for a row that names none.
     */
    @Test
    fun theMessageRowsMimeWinsOverTheBlobRows() =
        runTest {
            coEvery { blobs.bytes("h") } returns byteArrayOf(1)
            coEvery { blobs.mimeFor("h") } returns "image/jpeg"
            coEvery { gallerySaver.saveToPictures(any(), any(), any()) } returns true
            val vm = vm()

            vm.saveAttachment("h", null, "image/webp")
            advanceUntilIdle()

            coVerify { gallerySaver.saveToPictures(any(), "h", "image/webp") }

            vm.saveAttachment("h", null, null) // no row mime: fall back to what the blob calls itself
            advanceUntilIdle()

            coVerify { gallerySaver.saveToPictures(any(), "h", "image/jpeg") }
        }

    @Test
    fun aDmReadsLoraOnlyWhenOnlyTheBoardHearsThePeer() =
        runTest {
            stubDm("ana")
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true)
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa))
            val vm = vm("ana")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(LoraPlane.Live, vm.state.value.loraPlane)
            assertEquals(LoraReach.LoraOnly, vm.state.value.loraReach)
            assertEquals(LoraCarry.Dm, vm.state.value.loraCarry)

            // Bluetooth hears them too: the notice goes quiet, the draft still rides LoRa.
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa, TransportKind.Bluetooth))
            advanceUntilIdle()
            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
            assertEquals(LoraCarry.Dm, vm.state.value.loraCarry)
        }

    @Test
    fun theRoomSaysWhenLoraAirtimeIsSpentAndSomeoneIsOnlyOnTheBoard() =
        runTest {
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true, airtimeSpent = true)
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa))
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(LoraReach.RoomSaturated, vm.state.value.loraReach)
            assertEquals(LoraCarry.Room, vm.state.value.loraCarry)

            // Bluetooth reaches her too, so the spent window delays nobody the room can name.
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa, TransportKind.Bluetooth))
            advanceUntilIdle()
            assertEquals(LoraReach.Silent, vm.state.value.loraReach)

            // She goes back behind the board, then the window frees up: the room stops complaining but the
            // draft still rides LoRa.
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa))
            advanceUntilIdle()
            assertEquals(LoraReach.RoomSaturated, vm.state.value.loraReach)
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true)
            advanceUntilIdle()
            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
            assertEquals(LoraCarry.Room, vm.state.value.loraCarry)
        }

    @Test
    fun aDmIgnoresAnotherPeerSittingBehindTheBoard() =
        runTest {
            stubDm("ana")
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true, airtimeSpent = true)
            // Bo is LoRa-only, which is what the *room* would speak about; this thread is Ana's, and
            // Bluetooth carries her — the room's existential rule must not leak into a DM.
            mesh.peerTransports.value =
                mapOf("ana" to setOf(TransportKind.Bluetooth), "bo" to setOf(TransportKind.LoRa))
            val vm = vm("ana")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
        }

    @Test
    fun aGroupSaysItsMessagesDoNotTravelOverLoraWhileAMemberIsBehindTheBoard() =
        runTest {
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true)
            val vm = vm(GROUP)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupFlow.value = group(GROUP, members = listOf("me", "sam", "priya"))
            // Priya is in the group and only the board hears her.
            mesh.peerTransports.value = mapOf("priya" to setOf(TransportKind.LoRa))
            advanceUntilIdle()

            assertEquals(LoraReach.GroupUnsupported, vm.state.value.loraReach)
            // No composer hint: nothing in a group rides the plane, so there is no budget to overrun.
            assertEquals(LoraCarry.None, vm.state.value.loraCarry)

            // She comes back onto a phone radio and the notice retires itself.
            mesh.peerTransports.value = mapOf("priya" to setOf(TransportKind.Bluetooth))
            advanceUntilIdle()
            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
        }

    @Test
    fun aGroupIgnoresLoraOnlyIdsThatAreNotOnItsRoster() =
        runTest {
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true)
            val vm = vm(GROUP)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupFlow.value = group(GROUP, members = listOf("me", "sam"))
            // A LoRa-only stranger says nothing about whether *this group's* messages land; and our own
            // id is not somebody we can fail to deliver to, even were it ever to appear in the map.
            mesh.peerTransports.value =
                mapOf("theo" to setOf(TransportKind.LoRa), "me" to setOf(TransportKind.LoRa))
            advanceUntilIdle()

            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
        }

    @Test
    fun loraCarryFollowsThePlaneAndTheDmSwitch() =
        runTest {
            stubDm("ana")
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = false)
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa))
            val vm = vm("ana")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(LoraReach.LoraOnlyDmsOff, vm.state.value.loraReach)
            assertEquals(LoraCarry.None, vm.state.value.loraCarry)

            loraFactsFlow.value = LoraFacts(LoraPlane.Down, dms = true)
            advanceUntilIdle()
            assertEquals(LoraPlane.Down, vm.state.value.loraPlane)
            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
            assertEquals(LoraCarry.None, vm.state.value.loraCarry)
        }

    @Test
    fun theRoomsRelayNoticeGoesQuietOnceDismissedAndStaysThatWay() =
        runTest {
            // Live plane, room open: the structural "never over the Internet" notice is up.
            relayFactsFlow.value = RelayFacts(enabled = true, configured = 1, active = 1, connected = 1)
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(RelayReach.Room, vm.state.value.relayReach)

            vm.dismissRelayNotice()
            advanceUntilIdle()
            coVerify(exactly = 1) { settings.dismissRelayRoomNotice() }

            // The write is what makes it stick; the mock's flow is what the VM reads back.
            relayRoomNoticeDismissedFlow.value = true
            advanceUntilIdle()
            assertEquals(RelayReach.Silent, vm.state.value.relayReach)

            // Sticky across a relaunch: a fresh ViewModel over the same stored flag stays quiet.
            val relaunched = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { relaunched.state.collect {} }
            advanceUntilIdle()
            assertEquals(RelayReach.Silent, relaunched.state.value.relayReach)
        }

    @Test
    fun aDismissedRoomNoticeDoesNotSilenceAnUncoveredDm() =
        runTest {
            // The flag is device-wide, so the one thing it must not do is hide the *other* notice — a
            // pending thread's line clears itself and carries different information.
            stubDm("ana")
            relayRoomNoticeDismissedFlow.value = true
            relayFactsFlow.value = RelayFacts(enabled = true, configured = 1, active = 1, connected = 1)
            val vm = vm("ana")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(RelayReach.Pending, vm.state.value.relayReach)
        }

    private companion object {
        const val GROUP = "g-trailhead"
    }
}
