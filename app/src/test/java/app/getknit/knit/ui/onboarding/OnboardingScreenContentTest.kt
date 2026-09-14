package app.getknit.knit.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.ui.MeshPermissionTier
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The app's front door. Every test renders one fixed step of the stateless content — no transition is
 * driven, the ViewModel's ordering is `OnboardingViewModelTest`'s job — and the load-bearing case is that
 * Start needs the **radio** grants only: notifications and battery are rows, never the gate.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Calls {
        var next = 0
        var back = 0
        var radio = 0
        var notifications = 0
        var battery = 0
        var settings = 0
        var ready = 0
        var typed = ""
    }

    private fun render(
        step: OnboardingStep,
        name: String = "",
        rows: PermissionRows = PermissionRows.FRESH,
        tier: MeshPermissionTier = MeshPermissionTier.NEARBY_DEVICES,
        meshSupported: Boolean = true,
    ): Calls {
        val calls = Calls()
        compose.setContent {
            KnitTheme {
                OnboardingScreenContent(
                    step = step,
                    name = name,
                    alias = "SmartlyBrightSparrow",
                    nodeId = "node-test",
                    rows = rows,
                    tier = tier,
                    meshSupported = meshSupported,
                    onNext = { calls.next++ },
                    onBack = { calls.back++ },
                    onNameChange = { calls.typed = it },
                    onNameCommit = {},
                    onRequestRadio = { calls.radio++ },
                    onRequestNotifications = { calls.notifications++ },
                    onAllowBattery = { calls.battery++ },
                    onOpenSettings = { calls.settings++ },
                    onReady = { calls.ready++ },
                )
            }
        }
        return calls
    }

    @Test
    fun welcomeGetStartedAdvances() {
        val calls = render(OnboardingStep.WELCOME)
        compose.onNodeWithText(context.getString(R.string.onboarding_title)).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_next").performClick()
        assertEquals(1, calls.next)
    }

    @Test
    fun nameStepReadsSkipUntilSomethingIsTyped() {
        val calls = render(OnboardingStep.NAME)
        compose.onNodeWithTag("onboarding_next").assertTextEquals(context.getString(R.string.onboarding_name_skip))
        compose.onNodeWithTag("onboarding_name").performTextInput("Alice")
        assertEquals("Alice", calls.typed)
    }

    @Test
    fun nameStepReadsContinueOnceTyped() {
        render(OnboardingStep.NAME, name = "Alice")
        compose.onNodeWithTag("onboarding_next").assertTextEquals(context.getString(R.string.onboarding_continue))
    }

    @Test
    fun nameStepShowsTheAvatarPreview() {
        // The initial itself is semantics-cleared inside Avatar (decorative; AvatarTest pins the letter),
        // so only the preview's presence is observable here — in the unmerged tree, under the page column.
        render(OnboardingStep.NAME, name = "alice")
        compose.onNodeWithTag("onboarding_avatar", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun nameStepImeDoneContinues() {
        val calls = render(OnboardingStep.NAME)
        compose.onNodeWithTag("onboarding_name").performImeAction()
        assertEquals(1, calls.next)
    }

    @Test
    fun startIsDisabledUntilTheRadioGrantsAreHeld() {
        val calls = render(OnboardingStep.PERMISSIONS)
        compose.onNodeWithTag("onboarding_start").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding_grant").performClick()
        assertEquals(1, calls.radio)
    }

    /** The decision this makeover pins: notifications and battery are optional, so Start needs the radios alone. */
    @Test
    fun startNeedsOnlyTheRadios() {
        val calls =
            render(
                OnboardingStep.PERMISSIONS,
                rows = PermissionRows.FRESH.copy(radioGranted = true),
            )
        compose.onNodeWithTag("onboarding_start").assertIsEnabled()
        // The optional rows are still there, still asking, and still not in the way.
        compose.onNodeWithTag("onboarding_notifications").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_battery").performScrollTo().performClick()
        assertEquals(1, calls.notifications)
        assertEquals(1, calls.battery)
        compose.onNodeWithTag("onboarding_start").performClick()
        assertEquals(1, calls.ready)
    }

    @Test
    fun grantedRowsShowTheCheckAndNoButton() {
        render(OnboardingStep.PERMISSIONS, rows = PermissionRows.ALL)
        // The row merges its descendants into one TalkBack item, so the check's tag lives in the unmerged tree.
        compose.onNodeWithTag("onboarding_grant_granted", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_grant").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_notifications").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_battery").assertDoesNotExist()
    }

    @Test
    fun aRadioGrantAndroidWontAskForAgainOffersSettings() {
        val calls = render(OnboardingStep.PERMISSIONS, rows = PermissionRows.FRESH.copy(radioNeedsSettings = true))
        compose.onNodeWithTag("onboarding_grant").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_denied_hint)).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_grant_settings").performClick()
        assertEquals(1, calls.settings)
        assertEquals(0, calls.radio)
    }

    @Test
    fun unsupportedHardwareDoesNotBlockStart() {
        render(
            OnboardingStep.PERMISSIONS,
            rows = PermissionRows.FRESH.copy(radioGranted = true),
            meshSupported = false,
        )
        // No mesh radio hardware (the radio-less Firebase Test Lab / single-radio-missing reality), yet
        // Start gates only on the grants, independent of meshSupported — the app degrades gracefully rather
        // than dead-ending, so the user can still reach what they already have.
        compose.onNodeWithTag("onboarding_start").assertIsEnabled()
        // And the notice names the gate that fired it: neither radio, not "no Wi-Fi Aware" (work item 18).
        compose.onNodeWithText(context.getString(R.string.onboarding_unsupported)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theNotificationsRowExistsOnlyWhereTheGrantIsRuntime() {
        render(OnboardingStep.PERMISSIONS, tier = MeshPermissionTier.LOCATION)
        compose.onNodeWithTag("onboarding_notifications").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_location_title)).assertIsDisplayed()
    }

    @Test
    fun android12NamesLocationAndBluetooth() {
        render(OnboardingStep.PERMISSIONS, tier = MeshPermissionTier.LOCATION_AND_BLUETOOTH)
        compose.onNodeWithTag("onboarding_notifications").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_location_bt_title)).assertIsDisplayed()
    }
}
