package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.relay.AttachmentRelay
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The chat's two relay indicators, and — as much as the assertions — the cases where they must stay
 * quiet. A marker that appears when it should not is worse than none: it teaches people to read a
 * working send as a broken one.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatRelayIndicatorTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(
        mine: Boolean = true,
        attachmentRelay: AttachmentRelay = AttachmentRelay.Silent,
    ) = ChatRow(
        id = "m1",
        body = "look at this",
        mine = mine,
        senderName = if (mine) "You" else "Ana",
        senderNodeId = if (mine) "me" else "ana",
        avatarHash = null,
        sentAt = 1_700_000_000_000L,
        received = false,
        attachmentHash = "h1",
        attachmentReady = true,
        attachmentRelay = attachmentRelay,
    )

    private fun render(
        rows: List<ChatRow> = emptyList(),
        reach: RelayReach = RelayReach.Silent,
        staged: AttachmentRelay = AttachmentRelay.Silent,
    ) {
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = Conversations.NEARBY,
                    state =
                        ChatUiState(
                            rows = rows,
                            isRoom = true,
                            myNodeId = "me",
                            title = "Ana",
                            relayReach = reach,
                        ),
                    inputState = TextFieldState(""),
                    pendingAttachment = null,
                    stagedAttachmentRelay = staged,
                    replyingTo = null,
                    now = 1_700_000_000_000L,
                    onBack = {},
                    onOpenProfile = {},
                    onOpenGroupDetails = {},
                    onSend = {},
                    onAttachClick = {},
                    onClearAttachment = {},
                    onReceiveImage = {},
                    onTyping = {},
                    onMentionAdded = {},
                    onStartReply = {},
                    onCancelReply = {},
                    onReact = { _, _ -> },
                    onDeleteMessage = {},
                    onBlock = {},
                    onUnblock = {},
                    onCopy = {},
                    onSaveAttachment = {},
                )
            }
        }
    }

    @Test
    fun aCoveredThreadShowsNoNotice() {
        render(reach = RelayReach.Covered)
        compose.onNodeWithTag("chat_relay_notice").assertDoesNotExist()
    }

    @Test
    fun aSilentReachShowsNoNotice() {
        // Plane off, or relays unreachable: nothing true to say, so nothing said.
        render(reach = RelayReach.Silent)
        compose.onNodeWithTag("chat_relay_notice").assertDoesNotExist()
    }

    @Test
    fun theRoomSaysItStaysLocal() {
        render(reach = RelayReach.Room)
        compose.onNodeWithTag("chat_relay_notice").assertIsDisplayed()
        compose.onNodeWithText("Nearby is never sent over the Internet").assertIsDisplayed()
    }

    @Test
    fun anUnscopedThreadSaysItIsNotCoveredYet() {
        render(reach = RelayReach.Pending)
        compose.onNodeWithText("Not covered by relays yet").assertIsDisplayed()
    }

    @Test
    fun tappingTheNoticeExplainsWhy() {
        render(reach = RelayReach.Room)
        compose.onNodeWithTag("chat_relay_notice").performClick()
        compose.onNodeWithText("Nearby stays local").assertIsDisplayed()
    }

    @Test
    fun anOversizeAttachmentIsMarkedNearbyOnly() {
        render(rows = listOf(row(attachmentRelay = AttachmentRelay.TooLarge)))
        compose.onNodeWithTag("chat_relay_marker").assertIsDisplayed()
        compose.onNodeWithText("Nearby only").assertIsDisplayed()
    }

    @Test
    fun aRelayableAttachmentIsNotMarked() {
        render(rows = listOf(row(attachmentRelay = AttachmentRelay.Relayable)))
        compose.onNodeWithTag("chat_relay_marker").assertDoesNotExist()
    }

    @Test
    fun aSilentAttachmentIsNotMarked() {
        render(rows = listOf(row(attachmentRelay = AttachmentRelay.Silent)))
        compose.onNodeWithTag("chat_relay_marker").assertDoesNotExist()
    }

    @Test
    fun theMarkerExplainsTheSizeCauseAndNamesTheFallback() {
        render(rows = listOf(row(attachmentRelay = AttachmentRelay.TooLarge)))
        compose.onNodeWithTag("chat_relay_marker").performClick()
        compose.onNodeWithText("Too large for your relays").assertIsDisplayed()
        // The point of the explanation is that the photo still arrives, so the fallback must be in it.
        compose
            .onNodeWithText(
                "This photo is bigger than any of your relays will hold, so it is not being uploaded. " +
                    "It still arrives when you and Ana are in Wi-Fi or Bluetooth range of each other.",
            ).assertIsDisplayed()
    }

    @Test
    fun theMarkerDistinguishesAFramesOnlyRelayFromAnOversizePhoto() {
        render(rows = listOf(row(attachmentRelay = AttachmentRelay.Unsupported)))
        compose.onNodeWithTag("chat_relay_marker").performClick()
        compose.onNodeWithText("Your relays carry messages only").assertIsDisplayed()
    }

    @Test
    fun theDeliveryTickIsUntouchedByAnUnrelayableAttachment() {
        // Reach and delivery are separate facts: a nearby-only photo is still "Sent".
        render(rows = listOf(row(attachmentRelay = AttachmentRelay.TooLarge)))
        compose.onNodeWithContentDescription("Sent").assertIsDisplayed()
    }
}
