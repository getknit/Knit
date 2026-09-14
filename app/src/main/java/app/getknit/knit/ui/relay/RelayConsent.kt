package app.getknit.knit.ui.relay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.getknit.knit.R

/**
 * The first-enable disclosure's text, on its own so the master switch's sheet and the relay invite sheet
 * show the same words — one disclosure, two doors (ADR 063). Split can/cannot rather than a paragraph
 * because the two halves are exactly what a person needs to weigh, and burying "a relay sees your IP
 * address" mid-sentence would be the kind of technically-true disclosure nobody reads.
 */
@Composable
internal fun RelayConsentText(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.testTag("relays_consent_text"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.relays_consent_can_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.relays_consent_can_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.relays_consent_cannot_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.relays_consent_cannot_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.relays_consent_scope),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The first-enable disclosure as the master switch raises it: title, [RelayConsentText], decline and accept. */
@Composable
internal fun RelayConsentBody(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.relays_consent_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        RelayConsentText()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDecline) { Text(stringResource(R.string.relays_consent_decline)) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onAccept, modifier = Modifier.testTag("relays_consent_accept")) {
                Text(stringResource(R.string.relays_consent_accept))
            }
        }
    }
}
