package app.getknit.knit.ui.lora

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The LoRa radio screen's stateless content, rendered on Robolectric: the two empty states, the show-all
 * toggle that only appears when the board filter hid something, and the channel verdict a connected board
 * earns. Mirrors `InternetRelayScreenContentTest`; the content scrolls, so the lower rows are scrolled to first.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoraRadioScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        state: LoraRadioUiState,
        onToggle: (Boolean) -> Unit = {},
        onShowAllBoards: (Boolean) -> Unit = {},
        onToggleBridge: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                LoraRadioScreenContent(
                    state = state,
                    onBack = {},
                    onToggle = onToggle,
                    onToggleBridge = onToggleBridge,
                    onShowAllBoards = onShowAllBoards,
                )
            }
        }
    }

    private fun connected(
        channelName: String? = "Knit",
        channelMismatch: Boolean = false,
    ) = LoraRadioUiState(
        enabled = true,
        boardName = "Meshtastic_1a2b",
        boardAddress = "AA:BB:CC:DD:EE:FF",
        channel = 1,
        connection = LoraConnState.Ready,
        boardNodeNum = "!0000002a",
        snr = 6.5f,
        rssi = -85,
        heard = 2,
        firmware = "2.5.0",
        channelName = channelName,
        channelMismatch = channelMismatch,
        boards = listOf(BoardOption("AA:BB:CC:DD:EE:FF", "Meshtastic_1a2b", selected = true)),
        anyBonded = true,
    )

    @Test
    fun theSwitchReflectsTheStoredSettingAndReportsATap() {
        var toggled: Boolean? = null
        render(LoraRadioUiState(enabled = false), onToggle = { toggled = it })
        compose.onNodeWithTag("lora_switch").assertIsOff()
        compose.onNodeWithTag("lora_switch").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun noPairedDeviceAtAllAsksToPairOne() {
        render(LoraRadioUiState(enabled = true, anyBonded = false))
        compose.onNodeWithText("No paired Meshtastic boards found", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_show_all_boards").assertDoesNotExist()
    }

    @Test
    fun onlyNonBoardsPairedSaysSoAndOffersToShowThem() {
        var showAll: Boolean? = null
        render(LoraRadioUiState(enabled = true, anyBonded = true, hiddenBoards = 2), onShowAllBoards = { showAll = it })
        compose.onNodeWithTag("lora_board_none_meshtastic").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 other paired devices hidden").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_show_all_boards").assertIsOff()
        compose.onNodeWithTag("lora_show_all_boards").performClick()
        assertEquals(true, showAll)
    }

    @Test
    fun theShowAllToggleStaysAwayWhenNothingIsHidden() {
        render(connected())
        compose.onNodeWithTag("lora_board_AA:BB:CC:DD:EE:FF").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_show_all_boards").assertDoesNotExist()
    }

    @Test
    fun theShowAllToggleStaysWhileItIsOn() {
        // Everything revealed means nothing is hidden any more — the toggle must not vanish under the finger.
        render(connected().copy(hiddenBoards = 0, showAllBoards = true))
        compose.onNodeWithTag("lora_show_all_boards").assertIsOn()
    }

    @Test
    fun aConnectedBoardNamesItsChannelFirmwareAndPeers() {
        render(connected())
        compose.onNodeWithText("Channel 1 · Knit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Firmware 2.5.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 peers heard over LoRa").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_channel_warning").assertDoesNotExist()
        compose.onNodeWithTag("lora_provision").assertIsEnabled()
    }

    @Test
    fun aSlotThatIsNotTheKnitChannelIsFlagged() {
        render(connected(channelName = null, channelMismatch = true))
        compose.onNodeWithText("Channel 1 · unnamed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_channel_warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_provision").assertIsEnabled()
    }

    @Test
    fun provisioningNeedsAConnectedBoard() {
        render(LoraRadioUiState(enabled = true, boardAddress = "AA:BB:CC:DD:EE:FF", connection = LoraConnState.Connecting))
        compose.onNodeWithText("Channel index: 0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_provision").assertIsNotEnabled()
        compose.onNodeWithTag("lora_peers_heard").assertDoesNotExist()
    }

    @Test
    fun aConnectedBoardShowsItsBattery() {
        render(connected().copy(battery = BoardBattery(percent = 78, voltage = 3.92f, powered = false)))
        compose.onNodeWithTag("lora_battery").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Battery 78% · 3.92 V").assertIsDisplayed()
    }

    @Test
    fun aPluggedInBoardSaysSo() {
        render(connected().copy(battery = BoardBattery(percent = null, voltage = 4.1f, powered = true)))
        compose.onNodeWithText("Plugged in · 4.10 V").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun noBatteryReadingMeansNoBatteryLine() {
        render(connected())
        compose.onNodeWithTag("lora_battery").assertDoesNotExist()
    }

    @Test
    fun theBridgeSwitchReflectsTheStoredSettingAndReportsATap() {
        var toggled: Boolean? = null
        render(connected().copy(bridgeEnabled = false), onToggleBridge = { toggled = it })
        compose.onNodeWithTag("lora_bridge_switch").performScrollTo().assertIsOff()
        compose.onNodeWithTag("lora_bridge_switch").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun theAirtimeLedgerShowsOnceTheBoardIsConnected() {
        render(connected().copy(airtimePercent = 42))
        compose.onNodeWithTag("lora_airtime").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("42%", substring = true).assertIsDisplayed()
    }

    @Test
    fun aSpareBoardSaysItIsListeningRatherThanLookingBroken() {
        render(connected().copy(bridgePassive = true))
        compose.onNodeWithTag("lora_role_passive").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theActiveGatewaySaysNothingAboutItsRole() {
        render(connected())
        compose.onNodeWithTag("lora_role_passive").assertDoesNotExist()
    }
}
