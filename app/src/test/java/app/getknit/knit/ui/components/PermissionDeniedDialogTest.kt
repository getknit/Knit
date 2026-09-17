package app.getknit.knit.ui.components

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.ui.DeviceSupervision
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The gate dialog's one variable: on an administered phone the body ends with the line that names who may
 * have turned the grant off and where they hold it; on a plain phone it is the caller's body alone
 * (ADR 2026-09.a8ud). Follows the Compose-on-Robolectric pattern in `DiagnosticsScreenContentTest`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PermissionDeniedDialogTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun show(
        supervision: DeviceSupervision,
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                PermissionDeniedDialog(
                    icon = Icons.Filled.LocationOn,
                    title = R.string.chat_location_denied_title,
                    body = R.string.chat_location_denied_settings,
                    onDismiss = onDismiss,
                    supervision = supervision,
                )
            }
        }
    }

    @Test
    fun aPlainPhoneShowsTheBodyAlone() {
        show(DeviceSupervision.None)
        compose.onNodeWithTag("permission_denied_body").assertTextEquals(context.getString(R.string.chat_location_denied_settings))
    }

    @Test
    fun aFamilyLinkPhoneNamesTheParentAfterTheBody() {
        show(DeviceSupervision.FamilyLink)
        compose.onNodeWithTag("permission_denied_body").assertTextEquals(
            context.getString(R.string.chat_location_denied_settings) + "\n\n" +
                context.getString(R.string.perm_denied_hint_family_link),
        )
    }

    @Test
    fun aManagedPhoneNamesTheAdministrator() {
        show(DeviceSupervision.Managed)
        compose.onNodeWithText(context.getString(R.string.perm_denied_hint_managed), substring = true).assertIsDisplayed()
    }

    @Test
    fun cancelDismisses() {
        var dismissed = 0
        show(DeviceSupervision.None, onDismiss = { dismissed++ })
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertEquals(1, dismissed)
    }
}
