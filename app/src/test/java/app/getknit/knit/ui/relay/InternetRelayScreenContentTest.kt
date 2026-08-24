package app.getknit.knit.ui.relay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The relay editor's load-bearing behaviours: the switch reflects the stored setting, a relay's live
 * state reaches its row, the empty list explains itself, and — the one that protects a release build —
 * an unacceptable URL cannot be added.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InternetRelayScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        state: InternetRelayUiState,
        showConsent: Boolean = false,
        onToggle: (Boolean) -> Unit = {},
        onAddRelay: (String) -> Unit = {},
        onRemoveRelay: (String) -> Unit = {},
        onAcceptConsent: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                InternetRelayScreenContent(
                    state = state,
                    showConsent = showConsent,
                    onBack = {},
                    onToggle = onToggle,
                    onAcceptConsent = onAcceptConsent,
                    onAddRelay = onAddRelay,
                    onRemoveRelay = onRemoveRelay,
                    isValidUrl = { it.startsWith("wss://") },
                )
            }
        }
    }

    private fun relay(
        host: String = "lax.spool.getknit.app",
        connected: Boolean = true,
        scopeCount: Int? = 3,
        carriesPhotos: Boolean? = true,
        lastError: String? = null,
    ) = RelayRow(
        url = "wss://$host/spool/v1",
        host = host,
        connected = connected,
        scopeCount = scopeCount,
        carriesPhotos = carriesPhotos,
        lastError = lastError,
    )

    @Test
    fun switchReflectsTheStoredSetting() {
        render(InternetRelayUiState(enabled = true, relays = listOf(relay())))
        compose.onNodeWithTag("relays_switch").assertIsOn()
    }

    @Test
    fun switchIsOffByDefault() {
        render(InternetRelayUiState())
        compose.onNodeWithTag("relays_switch").assertIsOff()
    }

    @Test
    fun togglingReportsTheRequestedState() {
        var requested: Boolean? = null
        render(InternetRelayUiState(), onToggle = { requested = it })
        compose.onNodeWithTag("relays_switch").performClick()
        assertEquals(true, requested)
    }

    @Test
    fun anEmptyListExplainsItselfRatherThanLookingBroken() {
        // Removing the seeded default is a legitimate thing to do, so the empty state has to read as a
        // state rather than as a failure.
        render(InternetRelayUiState(enabled = true))
        compose.onNodeWithTag("relays_empty").assertIsDisplayed()
    }

    @Test
    fun aConnectedRelayShowsItsHostAndScopeCount() {
        render(InternetRelayUiState(enabled = true, relays = listOf(relay())))
        compose.onNodeWithText("lax.spool.getknit.app").assertIsDisplayed()
        compose.onNodeWithText("Connected · 3 conversations").assertIsDisplayed()
    }

    @Test
    fun aFramesOnlyRelaySaysSoBeforeAPhotoFailsToUpload() {
        render(InternetRelayUiState(enabled = true, relays = listOf(relay(carriesPhotos = false))))
        compose.onNodeWithText("Carries messages only — no photos").assertIsDisplayed()
    }

    @Test
    fun anUnreachableRelayShowsWhy() {
        render(
            InternetRelayUiState(
                enabled = true,
                relays = listOf(relay(connected = false, scopeCount = null, carriesPhotos = null, lastError = "unreachable")),
            ),
        )
        compose.onNodeWithText("Cannot be reached").assertIsDisplayed()
    }

    @Test
    fun aBusyRelayIsNotConfusedWithABrokenOne() {
        // A spool at its connection cap refuses the upgrade with 503 rather than a close code (spec §7.1
        // has no "come back later"), and it comes back on its own. That asks nothing of the user, while
        // "cannot be reached" asks them to go check the URL — so the two must not read alike.
        render(
            InternetRelayUiState(
                enabled = true,
                relays = listOf(relay(connected = false, scopeCount = null, carriesPhotos = null, lastError = "http 503")),
            ),
        )
        compose.onNodeWithText("Busy — it is not taking new connections right now").assertIsDisplayed()
    }

    @Test
    fun anUnknownTransportFailureStillQuotesWhatHappened() {
        render(
            InternetRelayUiState(
                enabled = true,
                relays = listOf(relay(connected = false, scopeCount = null, carriesPhotos = null, lastError = "http 404")),
            ),
        )
        compose.onNodeWithText("Refused a request (http 404)").assertIsDisplayed()
    }

    @Test
    fun aRelayIsNotAddedUntilItsSchemeIsAcceptable() {
        // The dialer refuses a non-wss URL at dial time in a release build, so storing one would leave a
        // row that can never connect. The editor refuses it at entry instead.
        var added: String? = null
        render(InternetRelayUiState(enabled = true), onAddRelay = { added = it })
        compose.onNodeWithTag("relays_add").performClick()
        compose.onNodeWithTag("relays_add_field").performTextInput("ws://plain.example.org/spool/v1")
        compose.onNodeWithText("Must start with wss://").assertIsDisplayed()
        assertEquals(null, added)
    }

    @Test
    fun anAcceptableRelayIsAdded() {
        var added: String? = null
        render(InternetRelayUiState(enabled = true), onAddRelay = { added = it })
        compose.onNodeWithTag("relays_add").performClick()
        compose.onNodeWithTag("relays_add_field").performTextInput("wss://new.example.org/spool/v1")
        // The dialog's confirm button carries the same label as the row that opened it.
        compose.onAllNodesWithText("Add relay").onLast().performClick()
        assertEquals("wss://new.example.org/spool/v1", added)
    }

    @Test
    fun theConsentSheetIsShownBeforeTheFirstEnable() {
        var accepted = false
        render(InternetRelayUiState(), showConsent = true, onAcceptConsent = { accepted = true })
        // The disclosure has to name the residual leak outright; a sheet that only reassured would be
        // the dishonest version of this screen.
        compose.onNodeWithText("A relay can see").assertIsDisplayed()
        compose.onNodeWithText("A relay cannot see").assertIsDisplayed()
        compose.onNodeWithTag("relays_consent_accept").performClick()
        assertTrue(accepted)
    }
}
