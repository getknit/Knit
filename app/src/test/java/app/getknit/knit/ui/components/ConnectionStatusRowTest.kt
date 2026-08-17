package app.getknit.knit.ui.components

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.data.relay.RelayPlane
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The connection header's two-plane copy rules. Assertions go through the row's merged content
 * description rather than its text, because the row deliberately collapses dot + label + glyph into a
 * single semantics node — so the description is both what TalkBack reads and the only place the glyph's
 * state is spelled out in words. Follows the Compose-on-Robolectric pattern in
 * `DiagnosticsScreenContentTest`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionStatusRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun show(
        neighborCount: Int,
        health: TransportHealth,
        relay: RelayPlane,
    ) {
        compose.setContent {
            KnitTheme {
                ConnectionStatusRow(neighborCount = neighborCount, health = health, relay = relay)
            }
        }
    }

    private fun described(
        label: String,
        plane: Int?,
    ): String = plane?.let { context.getString(R.string.chat_connection_desc, label, context.getString(it)) } ?: label

    @Test
    fun aParkedPlaneLeavesTheMeshLineExactlyAsItWas() {
        show(3, TransportHealth.Healthy, RelayPlane.Off)

        val mesh = context.resources.getQuantityString(R.plurals.chat_connection_count, 3, 3)
        compose.onNodeWithContentDescription(described(mesh, plane = null)).assertIsDisplayed()
    }

    @Test
    fun bothPlanesUpKeepsTheMeshCountAndAddsOnlyTheGlyph() {
        show(3, TransportHealth.Healthy, RelayPlane.Live)

        val mesh = context.resources.getQuantityString(R.plurals.chat_connection_count, 3, 3)
        compose
            .onNodeWithContentDescription(described(mesh, R.string.chat_connection_relay_live_desc))
            .assertIsDisplayed()
    }

    @Test
    fun relaysCarryingWithNobodyNearbyReplacesTheEmptyMeshLine() {
        // "No mesh nodes connected" would be false here: messages are still moving, over the Internet.
        show(0, TransportHealth.Healthy, RelayPlane.Live)

        compose
            .onNodeWithContentDescription(
                described(
                    context.getString(R.string.chat_connection_relay_only),
                    R.string.chat_connection_relay_live_desc,
                ),
            ).assertIsDisplayed()
    }

    @Test
    fun radiosOffButRelaysUpDropsTheTurnThemOnHint() {
        show(0, TransportHealth.Unavailable, RelayPlane.Live)

        compose
            .onNodeWithContentDescription(
                described(
                    context.getString(R.string.chat_connection_relay_no_radios),
                    R.string.chat_connection_relay_live_desc,
                ),
            ).assertIsDisplayed()
    }

    @Test
    fun aSeizedRadioWithRelaysUpReadsTheSameWayAsRadiosOff() {
        // Degraded and Unavailable differ in cause but not in consequence once the Internet is carrying.
        show(0, TransportHealth.Degraded, RelayPlane.Live)

        compose
            .onNodeWithContentDescription(
                described(
                    context.getString(R.string.chat_connection_relay_no_radios),
                    R.string.chat_connection_relay_live_desc,
                ),
            ).assertIsDisplayed()
    }

    @Test
    fun aDownPlaneNeverClaimsToBeRelaying() {
        show(0, TransportHealth.Unavailable, RelayPlane.Down)

        // Nothing crosses the plane, so the radio hint is still the useful thing to say.
        compose
            .onNodeWithContentDescription(
                described(
                    context.getString(R.string.chat_connection_radio_off),
                    R.string.chat_connection_relay_down_desc,
                ),
            ).assertIsDisplayed()
    }
}
