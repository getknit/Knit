package app.getknit.knit.ui.addcontact

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.contacts.ContactImporter
import app.getknit.knit.mesh.spool.SpoolUrl
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.preview.KnitPreview
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * "Add by link": paste (or arrive with) a contact link, preview who it is — name, node id, the safety
 * number to compare over a call — and add them. Lands on the new contact's profile, where the intro's
 * progress is shown (docs/CONTACT_CARD.md). Reached from the chat-list overflow, the New-message picker,
 * and any tapped `getknit.app/c` / `knit://c` link.
 */
@Composable
fun AddContactScreen(
    onBack: () -> Unit,
    onImported: (nodeId: String) -> Unit,
    viewModel: AddContactViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val selfMessage = stringResource(R.string.verify_self)
    val mismatchMessage = stringResource(R.string.add_contact_mismatch)
    val invalidMessage = stringResource(R.string.add_contact_invalid)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AddContactEvent.Imported -> onImported(event.nodeId)
                AddContactEvent.Self -> snackbarHostState.showSnackbar(selfMessage)
                is AddContactEvent.Mismatch -> snackbarHostState.showSnackbar(mismatchMessage.format(event.displayName))
                AddContactEvent.Invalid -> snackbarHostState.showSnackbar(invalidMessage)
            }
        }
    }

    AddContactScreenContent(
        state = state,
        input = input,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onInputChange = viewModel::setInput,
        onPaste = {
            scope.launch {
                val text = clipboard.getClipEntry()?.clipData?.let { clip -> clipText(clip) }
                if (!text.isNullOrBlank()) {
                    viewModel.setInput(text)
                    viewModel.lookup()
                }
            }
        },
        onLookup = viewModel::lookup,
        onConfirm = { unblock -> viewModel.confirm(unblock) },
    )
}

private fun clipText(clip: ClipData): String? =
    (0 until clip.itemCount)
        .asSequence()
        .mapNotNull { clip.getItemAt(it).coerceToText(null)?.toString() }
        .firstOrNull { it.isNotBlank() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddContactScreenContent(
    state: AddContactUiState,
    input: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onPaste: () -> Unit,
    onLookup: () -> Unit,
    onConfirm: (unblock: Boolean) -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("screen_add_contact"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_contact_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.add_contact_caption),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text(stringResource(R.string.add_contact_input_label)) },
                placeholder = { Text(stringResource(R.string.add_contact_input_hint)) },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("add_contact_input"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f).testTag("add_contact_paste")) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_contact_paste))
                }
                Button(
                    onClick = onLookup,
                    enabled = input.isNotBlank() && state !is AddContactUiState.Importing,
                    modifier = Modifier.weight(1f).testTag("add_contact_lookup"),
                ) {
                    Text(stringResource(R.string.add_contact_lookup))
                }
            }

            when (state) {
                is AddContactUiState.Preview -> PreviewCard(state.ready, onConfirm = onConfirm)
                AddContactUiState.Importing -> Text(stringResource(R.string.add_contact_importing))
                AddContactUiState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun PreviewCard(
    ready: ContactImporter.Preview.Ready,
    onConfirm: (unblock: Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("add_contact_preview"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Avatar(avatarHash = null, name = ready.displayName, size = 72.dp)
        Text(
            text = stringResource(R.string.add_contact_preview_title, ready.displayName),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.profile_node_id, ready.nodeId),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.verify_safety_number),
            style = MaterialTheme.typography.titleSmall,
        )
        Card {
            Text(
                text = ready.safetyNumber,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
        Text(
            text = stringResource(R.string.add_contact_safety_caption, ready.displayName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val note =
            when {
                ready.blocked -> stringResource(R.string.add_contact_blocked)
                ready.alreadyContact -> stringResource(R.string.add_contact_already, ready.displayName)
                ready.relaysOff -> stringResource(R.string.add_contact_relays_off)
                else -> null
            }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (ready.unknownRelays.isNotEmpty()) {
            Text(
                text =
                    stringResource(
                        R.string.add_contact_relay_hint,
                        ready.displayName,
                        ready.unknownRelays.joinToString { SpoolUrl.host(it) },
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Button(
            onClick = { onConfirm(ready.blocked) },
            modifier = Modifier.fillMaxWidth().testTag("add_contact_confirm"),
        ) {
            Icon(Icons.Filled.PersonAdd, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (ready.blocked) R.string.add_contact_confirm_unblock else R.string.add_contact_confirm))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AddContactScreenIdlePreview() =
    KnitPreview {
        AddContactScreenContent(
            state = AddContactUiState.Idle,
            input = "",
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onInputChange = {},
            onPaste = {},
            onLookup = {},
            onConfirm = {},
        )
    }
