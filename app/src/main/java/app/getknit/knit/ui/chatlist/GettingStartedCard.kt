package app.getknit.knit.ui.chatlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview

/**
 * The first-run nudge, drawn directly under the Nearby row. The chat list is never literally empty — the
 * Nearby room always has a row — so a fresh install reads as a finished screen with nothing to do on it.
 * This card names the two ways in: broadcast to everyone in range, or add one specific person. The
 * ViewModel retires it ([ChatListUiState.showGettingStarted]) the moment there is a real thread to open.
 */
@Composable
fun GettingStartedCard(
    onSayHello: () -> Unit,
    onAddContact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { testTag = "chatlist_hint" },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.chat_list_hint_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.chat_list_hint_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                // M3 card actions: the recessive action first, the one we'd rather they tap on the end.
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onAddContact,
                    modifier = Modifier.semantics { testTag = "chatlist_hint_add_contact" },
                ) {
                    Text(stringResource(R.string.chat_list_hint_add_contact))
                }
                FilledTonalButton(
                    onClick = onSayHello,
                    modifier = Modifier.semantics { testTag = "chatlist_hint_nearby" },
                ) {
                    Text(stringResource(R.string.chat_list_hint_nearby))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GettingStartedCardPreview() =
    KnitPreview {
        GettingStartedCard(onSayHello = {}, onAddContact = {})
    }
