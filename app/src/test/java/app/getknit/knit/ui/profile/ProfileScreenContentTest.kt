package app.getknit.knit.ui.profile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Save mirrors the ViewModel's `isDirty`: disabled with no unsaved edits, enabled (and firing) once
 * dirty. Plus the Internet-relay row, which is present only in a build that introduces the plane.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun form(
        isDirty: Boolean,
        relay: RelaySummary = RelaySummary(),
        lora: LoraSummary = LoraSummary(),
    ) = ProfileFormState(
        name = "Alice",
        status = "Hiking",
        nodeId = "node-abc",
        alias = "Cool Fox",
        avatarHash = null,
        contentFilteringEnabled = true,
        relay = relay,
        lora = lora,
        isDirty = isDirty,
    )

    private fun render(
        isDirty: Boolean,
        onSave: () -> Unit = {},
        relay: RelaySummary = RelaySummary(),
        onOpenRelays: () -> Unit = {},
        showInternetRelays: Boolean = true,
        lora: LoraSummary = LoraSummary(),
    ) {
        compose.setContent {
            KnitTheme {
                ProfileScreenContent(
                    form = form(isDirty, relay, lora),
                    batteryExempt = true,
                    onBack = {},
                    onNameChange = {},
                    onNameCommit = {},
                    onStatusChange = {},
                    onStatusCommit = {},
                    onToggleContentFiltering = {},
                    onOpenRelays = onOpenRelays,
                    showInternetRelays = showInternetRelays,
                    onPickPhoto = {},
                    onClearPhoto = {},
                    onAllowBattery = {},
                    onSave = onSave,
                )
            }
        }
    }

    /** The alias stays under the name field once a name is typed — it is how two same-named people tell each other apart (ADR 058). */
    @Test
    fun theAliasHintStaysVisibleWhileANameIsSet() {
        render(isDirty = false)
        // The supporting line is merged into the text field's semantics (hence the unmerged finder), and the
        // field sits in a vertically-scrolled column, so bring it on-screen first.
        val hint = compose.onNodeWithTag("profile_alias", useUnmergedTree = true)
        hint.performScrollTo().assertIsDisplayed()
        hint.assertTextContains("Cool Fox", substring = true)
    }

    @Test
    fun saveIsDisabledWithNoUnsavedEdits() {
        render(isDirty = false)
        compose.onNodeWithTag("profile_save").assertIsNotEnabled()
    }

    @Test
    fun saveIsEnabledAndFiresWhenDirty() {
        var saves = 0
        render(isDirty = true, onSave = { saves++ })

        compose.onNodeWithTag("profile_save").assertIsEnabled()
        // The save button is the last item in a vertically-scrolled column, so bring it on-screen before clicking.
        compose.onNodeWithTag("profile_save").performScrollTo().performClick()
        assertEquals(1, saves)
    }

    @Test
    fun internetRelayRowNavigatesWhenThePlaneIsIntroduced() {
        var opened = 0
        render(isDirty = false, onOpenRelays = { opened++ }, showInternetRelays = true)

        compose.onNodeWithTag("profile_relays").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    /**
     * The build-flag half of hiding the Internet plane: with `BuildConfig.INTERNET_PLANE` off there is no
     * row, so a release user has no way into a screen whose switch would be inert anyway (the other half
     * is `SettingsStore.spoolEnabled`, which reads false on the same flag).
     */
    @Test
    fun internetRelayRowIsAbsentWhenThePlaneIsDark() {
        render(isDirty = false, showInternetRelays = false)

        compose.onNodeWithTag("profile_relays").assertDoesNotExist()
    }

    @Test
    fun theLoraRowSaysWhetherTheBoardIsConnected() {
        render(isDirty = false, lora = LoraSummary(enabled = true, boardName = "Meshtastic_1a2b", plane = LoraPlane.Live))
        compose.onNodeWithText("On · Meshtastic_1a2b · connected").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theLoraRowSaysWhenTheBoundBoardIsNotConnected() {
        render(isDirty = false, lora = LoraSummary(enabled = true, boardName = "Meshtastic_1a2b", plane = LoraPlane.Down))
        compose.onNodeWithText("On · Meshtastic_1a2b · not connected").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theLoraRowShowsTheBoardsBattery() {
        val battery = BoardBattery(percent = 78, voltage = 3.92f, powered = false)
        render(
            isDirty = false,
            lora = LoraSummary(enabled = true, boardName = "Meshtastic_1a2b", plane = LoraPlane.Live, battery = battery),
        )
        compose.onNodeWithText("On · Meshtastic_1a2b · connected · battery 78%").performScrollTo().assertIsDisplayed()
    }
}
