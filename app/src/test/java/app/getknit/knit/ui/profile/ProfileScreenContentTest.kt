package app.getknit.knit.ui.profile

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Save mirrors the ViewModel's `isDirty`: disabled with no unsaved edits, enabled (and firing) once
 * dirty — from the top app bar, where it no longer sits under a switch that saves itself. Plus the alias
 * line and the open-to-chat flag, the two things on this screen that peers see, and the node-id row.
 * The app's own settings live on their own screen now — see `ui/settings/SettingsScreenContentTest`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun form(
        isDirty: Boolean,
        openToChat: Boolean = false,
    ) = ProfileFormState(
        name = "Alice",
        status = "Hiking",
        nodeId = "node-abc",
        alias = "Cool Fox",
        aliasMore = "Warm Owl",
        avatarHash = null,
        openToChat = openToChat,
        isDirty = isDirty,
    )

    private fun render(
        isDirty: Boolean,
        onSave: () -> Unit = {},
        openToChat: Boolean = false,
        onToggleOpenToChat: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                ProfileScreenContent(
                    form = form(isDirty, openToChat),
                    onBack = {},
                    onNameChange = {},
                    onNameCommit = {},
                    onStatusChange = {},
                    onStatusCommit = {},
                    onToggleOpenToChat = onToggleOpenToChat,
                    onPickPhoto = {},
                    onClearPhoto = {},
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

    /**
     * The owner's continuation sits beside the alias, muted: a label grows on the other person's phone, so
     * this is where they read what to say.
     */
    @Test
    fun theAliasLineCarriesTheContinuation() {
        render(isDirty = false)
        val line = compose.onNodeWithTag("profile_alias", useUnmergedTree = true)
        line.performScrollTo().assertIsDisplayed()
        line.assertTextContains("Warm Owl", substring = true)
    }

    /** The open-to-chat row is one toggle target (the row owns its switch) and reports the flipped value. */
    @Test
    fun theOpenToChatRowTogglesTheFlagOn() {
        var toggled: Boolean? = null
        render(isDirty = false, onToggleOpenToChat = { toggled = it })
        compose.onNodeWithTag("profile_open_to_chat").performScrollTo().performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun theOpenToChatRowTogglesTheFlagOff() {
        var toggled: Boolean? = null
        render(isDirty = false, openToChat = true, onToggleOpenToChat = { toggled = it })
        compose.onNodeWithTag("profile_open_to_chat").performScrollTo().performClick()
        assertEquals(false, toggled)
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
        // Save lives in the top app bar now, so it is always on screen — no performScrollTo (which would
        // throw, having no scrollable ancestor there).
        compose.onNodeWithTag("profile_save").performClick()
        assertEquals(1, saves)
    }

    /**
     * The node id sits under its own heading as a labelled row rather than the centred "Node ID: x"
     * sentence it used to be, and copying it is a first-class action.
     */
    @Test
    fun theNodeIdIsALabelledRowThatCopies() {
        render(isDirty = false)
        val row = compose.onNodeWithTag("profile_node_id")
        row.performScrollTo().assertIsDisplayed()
        row.assertTextContains("node-abc", substring = true)
        // The row is one merged tap target carrying the copy action, not an unlabelled icon beside a Text.
        row.assertHasClickAction()
    }
}
