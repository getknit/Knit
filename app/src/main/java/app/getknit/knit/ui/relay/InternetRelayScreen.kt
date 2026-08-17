package app.getknit.knit.ui.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.mesh.spool.SpoolErrCode
import app.getknit.knit.ui.preview.KnitPreview
import org.koin.androidx.compose.koinViewModel

/**
 * The Internet relays screen (`docs/SPOOL_PROTOCOL.md`): one global switch, the relay list, and each
 * relay's live health.
 *
 * This screen is what makes the switch shippable in a release build. The app seeds a default relay
 * (`res/values/spools.xml`), so without an editor a user could turn the plane on but never point it
 * somewhere else or remove it — which is why the switch was `BuildConfig.DEBUG`-gated until this
 * existed.
 */
@Composable
fun InternetRelayScreen(
    onBack: () -> Unit,
    viewModel: InternetRelayViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showConsent by viewModel.showConsent.collectAsStateWithLifecycle()

    InternetRelayScreenContent(
        state = state,
        showConsent = showConsent,
        onBack = onBack,
        onToggle = viewModel::onToggle,
        onAcceptConsent = viewModel::acceptConsent,
        onDismissConsent = viewModel::dismissConsent,
        onAddRelay = viewModel::addRelay,
        onRemoveRelay = viewModel::removeRelay,
        isValidUrl = viewModel::isValidUrl,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InternetRelayScreenContent(
    state: InternetRelayUiState,
    showConsent: Boolean = false,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onAcceptConsent: () -> Unit = {},
    onDismissConsent: () -> Unit = {},
    onAddRelay: (String) -> Unit = {},
    onRemoveRelay: (String) -> Unit = {},
    isValidUrl: (String) -> Boolean = { it.startsWith("wss://") },
) {
    var addDialogOpen by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<RelayRow?>(null) }

    Scaffold(
        modifier = Modifier.testTag("screen_internet_relays"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.relays_title)) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MasterSwitchRow(enabled = state.enabled, onToggle = onToggle)

            Text(
                text = stringResource(R.string.relays_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.relays_section_list),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            if (state.relays.isEmpty()) {
                Text(
                    text = stringResource(R.string.relays_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("relays_empty"),
                )
            } else {
                state.relays.forEach { relay ->
                    RelayListRow(
                        relay = relay,
                        planeEnabled = state.enabled,
                        onRemove = { pendingRemoval = relay },
                    )
                }
            }

            TextButton(
                onClick = { addDialogOpen = true },
                modifier = Modifier.testTag("relays_add"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.relays_add))
            }
        }
    }

    if (addDialogOpen) {
        AddRelayDialog(
            existing = state.relays.map { it.url }.toSet(),
            isValidUrl = isValidUrl,
            onAdd = {
                onAddRelay(it)
                addDialogOpen = false
            },
            onDismiss = { addDialogOpen = false },
        )
    }

    pendingRemoval?.let { relay ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.relays_remove)) },
            text = { Text(relay.host) },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveRelay(relay.url)
                    pendingRemoval = null
                }) {
                    Text(stringResource(R.string.relays_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showConsent) {
        ModalBottomSheet(
            onDismissRequest = onDismissConsent,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            RelayConsentBody(onAccept = onAcceptConsent, onDecline = onDismissConsent)
        }
    }
}

/**
 * The master switch. Same shape as the Profile screen's toggle rows: the row owns the `toggleable` and
 * the `Switch` takes a null handler, so a screen reader announces one labelled switch rather than an
 * unlabelled control next to some text.
 */
@Composable
private fun MasterSwitchRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, onValueChange = onToggle, role = Role.Switch)
                .testTag("relays_switch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_internet_relays_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_internet_relays_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/**
 * One relay: a status dot, its host, and a second line saying what it is doing for us.
 *
 * The host is [app.getknit.knit.mesh.spool.SpoolUrl.host]-derived, never the raw URL — a private relay
 * carries its bearer token in the query string, and that token is the whole access control, so it must
 * not survive into a screenshot or a bug report.
 */
@Composable
private fun RelayListRow(
    relay: RelayRow,
    planeEnabled: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot =
            when {
                relay.connected -> MaterialTheme.colorScheme.tertiary
                !planeEnabled -> MaterialTheme.colorScheme.outline
                relay.lastError != null -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            }
        Spacer(
            modifier = Modifier.size(10.dp).clip(CircleShape).background(dot),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = relay.host,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relayStatusLine(relay, planeEnabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A relay that carries frames but no photos is worth calling out here rather than leaving the
            // user to discover it one un-relayed photo at a time.
            if (relay.carriesPhotos == false) {
                Text(
                    text = stringResource(R.string.relays_no_photos),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.relays_remove_desc, relay.host),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The one-line status under a relay's host. */
@Composable
private fun relayStatusLine(
    relay: RelayRow,
    planeEnabled: Boolean,
): String =
    when {
        relay.connected -> {
            stringResource(R.string.relays_status_connected) + " · " +
                pluralStringResource(R.plurals.relays_scope_count, relay.scopeCount ?: 0, relay.scopeCount ?: 0)
        }

        !planeEnabled -> {
            stringResource(R.string.relays_status_off)
        }

        relay.lastError != null -> {
            relayErrorLabel(relay.lastError)
        }

        else -> {
            stringResource(R.string.relays_status_connecting)
        }
    }

/**
 * A spool `err` code turned into something a person can act on. The wire codes are an append-only
 * registry (spec §7.2), so anything unrecognised falls through to the generic form carrying the raw
 * code rather than being swallowed — an unknown refusal the user can quote is worth more than a
 * confident wrong guess.
 */
@Composable
private fun relayErrorLabel(code: String): String =
    when (code) {
        UNREACHABLE -> {
            stringResource(R.string.relays_error_unreachable)
        }

        SpoolErrCode.QUOTA -> {
            stringResource(R.string.relays_error_quota)
        }

        SpoolErrCode.RATE -> {
            stringResource(R.string.relays_error_rate)
        }

        SpoolErrCode.POW -> {
            stringResource(R.string.relays_error_pow)
        }

        else -> {
            if (code.contains(AUTH_CLOSE_CODE)) {
                stringResource(R.string.relays_error_auth)
            } else {
                stringResource(R.string.relays_error_other, code)
            }
        }
    }

@Composable
private fun AddRelayDialog(
    existing: Set<String>,
    isValidUrl: (String) -> Boolean,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Local state, never bound to the DataStore flow: a field fed by an async write races the user's
    // keystrokes (see the rule in .agents/rules/coding.md).
    var url by remember { mutableStateOf("") }
    val trimmed = url.trim()
    val duplicate = trimmed in existing
    val malformed = trimmed.isNotEmpty() && !isValidUrl(trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.relays_add_title)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                isError = malformed || duplicate,
                label = { Text(stringResource(R.string.relays_add_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    when {
                        duplicate -> Text(stringResource(R.string.relays_add_duplicate))
                        malformed -> Text(stringResource(R.string.relays_add_invalid))
                        else -> Text(stringResource(R.string.relays_add_hint))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("relays_add_field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(trimmed) },
                enabled = trimmed.isNotEmpty() && !malformed && !duplicate,
            ) {
                Text(stringResource(R.string.relays_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The first-enable disclosure. Split can/cannot rather than a paragraph because the two halves are
 * exactly what a person needs to weigh, and burying "a relay sees your IP address" mid-sentence would
 * be the kind of technically-true disclosure nobody reads.
 */
@Composable
private fun RelayConsentBody(
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

/** `SpoolStatus.lastError` when the socket could not be opened at all (`ScopeSync.UNREACHABLE`). */
private const val UNREACHABLE = "unreachable"

/** A close-reason string carrying spec §7.1's private-spool token rejection (`close 4001 …`). */
private const val AUTH_CLOSE_CODE = "4001"

@Preview(showBackground = true)
@Composable
fun InternetRelayScreenOnPreview() =
    KnitPreview {
        InternetRelayScreenContent(
            state =
                InternetRelayUiState(
                    enabled = true,
                    relays =
                        listOf(
                            RelayRow(
                                url = "wss://lax.spool.getknit.app/spool/v1",
                                host = "lax.spool.getknit.app",
                                connected = true,
                                scopeCount = 3,
                                carriesPhotos = true,
                            ),
                            RelayRow(
                                url = "wss://frames.example.org/spool/v1",
                                host = "frames.example.org",
                                connected = true,
                                scopeCount = 3,
                                carriesPhotos = false,
                            ),
                            RelayRow(
                                url = "wss://down.example.org/spool/v1",
                                host = "down.example.org",
                                connected = false,
                                lastError = "unreachable",
                            ),
                        ),
                ),
            onBack = {},
            onToggle = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun InternetRelayScreenEmptyPreview() =
    KnitPreview {
        InternetRelayScreenContent(
            state = InternetRelayUiState(enabled = false),
            onBack = {},
            onToggle = {},
        )
    }
