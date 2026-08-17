package app.getknit.knit.ui.components

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.data.relay.RelayPlane
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.preview.KnitPreview
import kotlinx.coroutines.delay

/**
 * Connectivity indicator shared by the chat list and chat screens: a colored dot, a status label, and —
 * only once the Internet plane is armed — a trailing cloud glyph.
 *
 * [neighborCount] is the number of directly-connected mesh neighbors (the radio-level reach, identical
 * across conversations); [health] lets the row distinguish a genuine "nobody nearby" from the radios being
 * switched off or seized, so a user who turned Wi-Fi/Bluetooth off (or is in airplane mode) gets an
 * actionable hint instead of a bare "No mesh nodes connected".
 *
 * The two planes divide the row rather than sharing it: the **dot** is always the radios, the **glyph** is
 * always the Internet, and the **label** answers the one question both planes bear on — whether anything
 * can be reached at all. So [relay] adds words only when it changes that answer (radios dark but relays
 * carrying); when both planes work the mesh count stands unchanged and the glyph reports the Internet on
 * its own, which is what keeps a second plane from turning a one-line subtitle into two.
 *
 * [relay] is deliberately the whole-device [RelayPlane], not this thread's `RelayReach` — per-conversation
 * coverage is already spoken by the chat's relay notice, and repeating it in the header would state the
 * same fact twice on one screen.
 */
@Composable
fun ConnectionStatusRow(
    neighborCount: Int,
    health: TransportHealth,
    relay: RelayPlane = RelayPlane.Off,
    modifier: Modifier = Modifier,
) {
    val plane = settledPlane(relay)
    val dotColor =
        when (health) {
            // Radios off is user-actionable, not a fault — a muted dot, not an alarming red one.
            TransportHealth.Unavailable -> {
                MaterialTheme.colorScheme.outline
            }

            TransportHealth.Degraded -> {
                MaterialTheme.colorScheme.error
            }

            TransportHealth.Healthy -> {
                if (neighborCount > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
            }
        }
    val label = connectionLabel(neighborCount, health, plane)
    val planeDescription =
        when (plane) {
            RelayPlane.Off -> null
            RelayPlane.Down -> stringResource(R.string.chat_connection_relay_down_desc)
            RelayPlane.Live -> stringResource(R.string.chat_connection_relay_live_desc)
        }
    // One semantics node for the whole row: dot, label and glyph are three views of a single status, and
    // left unmerged TalkBack stops on each in turn (and on the glyph with nothing to say).
    val description =
        planeDescription?.let { stringResource(R.string.chat_connection_desc, label, it) } ?: label
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(color = dotColor, shape = CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (plane != RelayPlane.Off) {
            Spacer(Modifier.width(6.dp))
            // Struck-through glyph for Down rather than only a paler tint: at 14dp a tint change alone is
            // the kind of distinction that disappears for a color-blind reader, and this row has no room
            // to spell the state out in words.
            Icon(
                imageVector = if (plane == RelayPlane.Live) Icons.Outlined.Cloud else Icons.Outlined.CloudOff,
                contentDescription = null,
                tint =
                    if (plane == RelayPlane.Live) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun connectionLabel(
    count: Int,
    health: TransportHealth,
    plane: RelayPlane,
): String {
    val relaying = plane == RelayPlane.Live
    return when (health) {
        TransportHealth.Unavailable -> {
            when {
                relaying -> stringResource(R.string.chat_connection_relay_no_radios)
                isAirplaneModeOn() -> stringResource(R.string.chat_connection_airplane)
                else -> stringResource(R.string.chat_connection_radio_off)
            }
        }

        TransportHealth.Degraded -> {
            if (relaying) {
                stringResource(R.string.chat_connection_relay_no_radios)
            } else {
                stringResource(R.string.chat_connection_degraded)
            }
        }

        TransportHealth.Healthy -> {
            when {
                // Both planes up: the mesh count stands and the glyph carries the Internet by itself.
                count > 0 -> pluralStringResource(R.plurals.chat_connection_count, count, count)

                relaying -> stringResource(R.string.chat_connection_relay_only)

                else -> stringResource(R.string.chat_connection_none)
            }
        }
    }
}

/**
 * [plane], with its fall out of [RelayPlane.Live] held back by [RELAY_DOWN_GRACE_MS].
 *
 * A relay that merely reconnects reports zero live spools for a poll or two — `RelayStatusRepository`
 * re-reads on a 5s ticker and `ScopeSync` resets to a 1s backoff after a completed handshake — and a glyph
 * that strikes itself out and heals on every reconnect reads as a fault rather than as the steady coverage
 * it is. A relay that is genuinely gone backs off toward a minute, so it crosses the window and dims.
 *
 * Only the Live -> Down edge waits. Reaching Live is good news and applies at once, and Off is a settings
 * change rather than a flap.
 */
@Composable
private fun settledPlane(plane: RelayPlane): RelayPlane {
    var settled by remember { mutableStateOf(plane) }
    LaunchedEffect(plane) {
        if (plane == RelayPlane.Down && settled == RelayPlane.Live) delay(RELAY_DOWN_GRACE_MS)
        settled = plane
    }
    return settled
}

private const val RELAY_DOWN_GRACE_MS = 12_000L

/**
 * Whether airplane mode is currently on, read from [Settings.Global] (no permission needed). Read at
 * composition — accurate whenever [TransportHealth.Unavailable] arrives, since toggling airplane mode
 * flips the radios and so re-drives health, recomposing this row.
 */
@Composable
private fun isAirplaneModeOn(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
}

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowConnectedPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 5, health = TransportHealth.Healthy)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowSinglePreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 1, health = TransportHealth.Healthy)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowDisconnectedPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Healthy)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowRadioOffPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Unavailable)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowDegradedPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Degraded)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowBothPlanesPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 3, health = TransportHealth.Healthy, relay = RelayPlane.Live)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowRelayOnlyPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Healthy, relay = RelayPlane.Live)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowRelayWithRadiosOffPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Unavailable, relay = RelayPlane.Live)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowRelayDownPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 2, health = TransportHealth.Healthy, relay = RelayPlane.Down)
    }
