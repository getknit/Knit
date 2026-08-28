package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.lora.LoraSizeHint
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The chat's two LoRa indicators — the pinned "LoRa only" notice and the composer's length hint — and the
 * cases where they must stay quiet. Sibling of [ChatRelayIndicatorTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatLoraIndicatorTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        conversationId: String = "ana",
        loraReach: LoraReach = LoraReach.Silent,
        loraCarry: LoraCarry = LoraCarry.None,
        draft: String = "",
        replyingTo: ReplyRef? = null,
    ) {
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = conversationId,
                    state =
                        ChatUiState(
                            isRoom = conversationId == Conversations.NEARBY,
                            myNodeId = "me",
                            title = "Ana",
                            loraReach = loraReach,
                            loraCarry = loraCarry,
                        ),
                    inputState = TextFieldState(draft),
                    pendingAttachment = null,
                    replyingTo = replyingTo,
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
                    onSaveAttachment = { _, _, _ -> },
                )
            }
        }
    }

    @Test
    fun aLoraOnlyDmShowsThePinnedNoticeAndItsExplanation() {
        render(loraReach = LoraReach.LoraOnly)
        compose.onNodeWithTag("chat_lora_notice").assertIsDisplayed()
        compose.onNodeWithText("Reachable over LoRa only").assertIsDisplayed()

        compose.onNodeWithTag("chat_lora_notice").performClick()
        compose.onNodeWithText("Over LoRa only").assertIsDisplayed()
    }

    @Test
    fun theDmsOffVariantSaysNothingReachesThem() {
        render(loraReach = LoraReach.LoraOnlyDmsOff)
        compose.onNodeWithText("In LoRa range only — private messages over LoRa are off").assertIsDisplayed()
    }

    @Test
    fun theSaturatedVariantSaysMessagesAreDelayedAndExplainsWhy() {
        render(loraReach = LoraReach.LoraOnlySaturated)
        compose.onNodeWithText("Over LoRa only — airtime is used up, messages are delayed").assertIsDisplayed()

        compose.onNodeWithTag("chat_lora_notice").performClick()
        compose.onNodeWithText("LoRa airtime is used up").assertIsDisplayed()
    }

    @Test
    fun aQuietReachShowsNoNotice() {
        render(loraReach = LoraReach.Silent, loraCarry = LoraCarry.Dm)
        compose.onNodeWithTag("chat_lora_notice").assertDoesNotExist()
    }

    @Test
    fun aDraftInsideTheBudgetShowsNoHint() {
        render(loraCarry = LoraCarry.Dm, draft = "a".repeat(LoraSizeHint.DM_BODY_BYTES))
        compose.onNodeWithTag("chat_lora_size_hint").assertDoesNotExist()
    }

    @Test
    fun aDraftPastTheBudgetShowsTheHint() {
        render(loraCarry = LoraCarry.Dm, draft = "a".repeat(LoraSizeHint.DM_BODY_BYTES + 1))
        compose.onNodeWithTag("chat_lora_size_hint").assertIsDisplayed()
        compose.onNodeWithText("Long message — it may not reach people over LoRa").assertIsDisplayed()
    }

    @Test
    fun theRoomGetsItsLargerBudget() {
        render(conversationId = Conversations.NEARBY, loraCarry = LoraCarry.Room, draft = "a".repeat(LoraSizeHint.DM_BODY_BYTES + 1))
        compose.onNodeWithTag("chat_lora_size_hint").assertDoesNotExist()
    }

    @Test
    fun aReplyShrinksTheBudget() {
        val reply = ReplyRef(messageId = "m0", authorId = "ana", author = "Ana", snippet = "see you at the gate")
        val body = "a".repeat(LoraSizeHint.DM_BODY_BYTES - LoraSizeHint.REPLY_RESERVE_BYTES + 1)
        render(loraCarry = LoraCarry.Dm, draft = body, replyingTo = reply)
        compose.onNodeWithTag("chat_lora_size_hint").assertIsDisplayed()
    }

    @Test
    fun noHintWhenTheDraftWouldNotRideLora() {
        // A group thread, a DM with private messages kept off LoRa, or no board at all: carry is None.
        render(loraCarry = LoraCarry.None, draft = "a".repeat(LoraSizeHint.ROOM_BODY_BYTES * 2))
        compose.onNodeWithTag("chat_lora_size_hint").assertDoesNotExist()
    }
}
