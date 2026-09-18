package app.getknit.knit.ui.relay

import android.content.ClipData
import android.os.Build
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
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
import app.getknit.knit.data.relay.RelayInviteApplier
import app.getknit.knit.mesh.spool.RelayInvite
import app.getknit.knit.mesh.spool.ScopeSync
import app.getknit.knit.mesh.spool.SpoolConnection
import app.getknit.knit.mesh.spool.SpoolErrCode
import app.getknit.knit.ui.components.noAutofillMenu
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.shareText
import app.getknit.knit.ui.theme.knitColors
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
    val invitePreview by viewModel.invitePreview.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current

    // Share / copy of a relay invite, and the sheet's outcomes. Android 13+ shows its own copy
    // confirmation, so the snackbar only fires below it (the Add-contact screen's idiom).
    val shareMessage = stringResource(R.string.relays_invite_share_text)
    val shareTitle = stringResource(R.string.relays_invite_chooser_title)
    val copiedMessage = stringResource(R.string.relays_invite_copied)
    val refusedMessage = stringResource(R.string.relays_invite_refused)
    val refusedUrlMessage = stringResource(R.string.relays_invite_refused_url)
    val appliedMessage = stringResource(R.string.relays_invite_applied)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is InternetRelayEvent.ShareInvite -> {
                    shareText(context, shareMessage.format(event.url), shareTitle)
                }

                is InternetRelayEvent.CopyInvite -> {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Knit relay invite", event.url)))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbarHostState.showSnackbar(copiedMessage)
                }

                is InternetRelayEvent.InviteRefused -> {
                    snackbarHostState.showSnackbar(if (event.reason == RelayInvite.Reason.BAD_URL) refusedUrlMessage else refusedMessage)
                }

                is InternetRelayEvent.InviteApplied -> {
                    snackbarHostState.showSnackbar(appliedMessage.format(event.host))
                }
            }
        }
    }

    InternetRelayScreenContent(
        state = state,
        showConsent = showConsent,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onToggle = viewModel::onToggle,
        onAcceptConsent = viewModel::acceptConsent,
        onDismissConsent = viewModel::dismissConsent,
        onAddRelay = viewModel::addRelay,
        onRemoveRelay = viewModel::removeRelay,
        onSetRelayEnabled = viewModel::setRelayEnabled,
        isValidUrl = viewModel::isValidUrl,
        onJoinCommons = viewModel::joinCommons,
        onLeaveCommons = viewModel::leaveCommons,
        isValidInvite = viewModel::isValidInvite,
        invitePreview = invitePreview,
        onConfirmInvite = viewModel::confirmInvite,
        onDismissInvite = viewModel::dismissInvite,
        onShareInvite = viewModel::shareInvite,
        onCopyInvite = viewModel::copyInvite,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod") // one screen's wiring: the list, four dialogs and two sheets, mirrored in one place
@Composable
internal fun InternetRelayScreenContent(
    state: InternetRelayUiState,
    showConsent: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onAcceptConsent: () -> Unit = {},
    onDismissConsent: () -> Unit = {},
    onAddRelay: (String) -> Unit = {},
    onRemoveRelay: (String) -> Unit = {},
    onSetRelayEnabled: (String, Boolean) -> Unit = { _, _ -> },
    isValidUrl: (String) -> Boolean = { it.startsWith("wss://") },
    onJoinCommons: (String, String) -> Unit = { _, _ -> },
    onLeaveCommons: (String) -> Unit = {},
    isValidInvite: (String) -> Boolean = { it.startsWith("knit-commons:v1:") },
    invitePreview: RelayInviteApplier.Preview? = null,
    onConfirmInvite: () -> Unit = {},
    onDismissInvite: () -> Unit = {},
    onShareInvite: (String) -> Unit = {},
    onCopyInvite: (String) -> Unit = {},
) {
    var addDialogOpen by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<RelayRow?>(null) }
    var joining by remember { mutableStateOf<RelayRow?>(null) }
    var leaving by remember { mutableStateOf<RelayRow?>(null) }

    Scaffold(
        modifier = Modifier.testTag("screen_internet_relays"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        offline = state.offline,
                        onSetEnabled = { onSetRelayEnabled(relay.url, it) },
                        onRemove = { pendingRemoval = relay },
                        onJoinCommons = { joining = relay },
                        onLeaveCommons = { leaving = relay },
                        onShareInvite = { onShareInvite(relay.url) },
                        onCopyInvite = { onCopyInvite(relay.url) },
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

    joining?.let { relay ->
        JoinCommonsDialog(
            relay = relay,
            isValidInvite = isValidInvite,
            onJoin = {
                onJoinCommons(relay.url, it)
                joining = null
            },
            onDismiss = { joining = null },
        )
    }

    leaving?.let { relay ->
        val joinedId = relay.commons?.joinedId
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text(stringResource(R.string.relays_commons_leave)) },
            text = { Text(stringResource(R.string.relays_commons_leave_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (joinedId != null) onLeaveCommons(joinedId)
                        leaving = null
                    },
                    modifier = Modifier.testTag("relay_commons_leave_confirm"),
                ) {
                    Text(stringResource(R.string.relays_commons_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { leaving = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
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

    invitePreview?.let { preview ->
        RelayInviteSheet(preview = preview, onConfirm = onConfirmInvite, onDismiss = onDismissInvite)
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
 * One relay: a status dot, its host, a second line saying what it is doing for us, its own switch, and
 * the way to remove it.
 *
 * The host is [app.getknit.knit.mesh.spool.SpoolUrl.host]-derived, never the raw URL — a private relay
 * carries its bearer token in the query string, and that token is the whole access control, so it must
 * not survive into a screenshot or a bug report.
 *
 * The `toggleable` sits on an **inner** row holding the dot, the text and the switch, with the delete
 * button as a sibling outside it. That split is what keeps the house pattern usable in a list: putting
 * `toggleable` on the outer row would nest the delete `IconButton` inside a toggle, which announces as
 * one control that does two things. As written, a screen reader gets one labelled switch and one
 * labelled button per relay.
 */
@Composable
private fun RelayListRow(
    relay: RelayRow,
    planeEnabled: Boolean,
    offline: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onJoinCommons: () -> Unit = {},
    onLeaveCommons: () -> Unit = {},
    onShareInvite: () -> Unit = {},
    onCopyInvite: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RelayHeadRow(relay, planeEnabled, offline, onSetEnabled, onRemove, onShareInvite, onCopyInvite)
        // The relay's commons (§7.4), once its HELLO has advertised one: the room's name and one verb —
        // Join while this device is outside it, Leave once inside. Below the head row rather than in it, so
        // the switch and the delete button stay the two controls a screen reader already knows there.
        relay.commons?.let { room ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.relays_commons_label, room.name ?: stringResource(R.string.commons_title)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (room.joinedId == null) {
                    TextButton(onClick = onJoinCommons, modifier = Modifier.testTag("relay_commons_join_${relay.host}")) {
                        Text(stringResource(R.string.relays_commons_join))
                    }
                } else {
                    TextButton(onClick = onLeaveCommons, modifier = Modifier.testTag("relay_commons_leave_${relay.host}")) {
                        Text(stringResource(R.string.relays_commons_leave))
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList") // one row's facts and its verbs; a holder would only rename the list
private fun RelayHeadRow(
    relay: RelayRow,
    planeEnabled: Boolean,
    offline: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onShareInvite: () -> Unit,
    onCopyInvite: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .testTag("relay_row_${relay.host}")
                    .toggleable(
                        value = relay.enabled,
                        enabled = planeEnabled,
                        onValueChange = onSetEnabled,
                        role = Role.Switch,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Intent outranks liveness: a relay the user just parked keeps its worker for up to one
            // ScopeSync reconcile tick, and a row that still said "Connected" for those seconds would
            // read as the switch not having worked.
            // And the phone's own lack of a route is not the relay's fault: no red dot for it.
            val dot =
                when {
                    !planeEnabled || !relay.enabled -> MaterialTheme.colorScheme.outline
                    relay.connected -> MaterialTheme.knitColors.positive
                    offline -> MaterialTheme.colorScheme.outline
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
                    text = relayStatusLine(relay, planeEnabled, offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A relay that carries frames but no photos is worth calling out here rather than leaving
                // the user to discover it one un-relayed photo at a time.
                if (relay.carriesPhotos == false) {
                    Text(
                        text = stringResource(R.string.relays_no_photos),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = relay.enabled, onCheckedChange = null, enabled = planeEnabled)
        }
        // The invite (docs/RELAY_INVITE.md §4): a sibling of the delete button, outside the toggle for the
        // same reason — one labelled button per verb. A menu rather than two buttons keeps the row's width.
        RelayInviteMenu(relay = relay, onShare = onShareInvite, onCopy = onCopyInvite)
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.relays_remove_desc, relay.host),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun RelayInviteMenu(
    relay: RelayRow,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp).testTag("relay_invite_${relay.host}")) {
        Icon(
            Icons.Filled.Share,
            contentDescription = stringResource(R.string.relays_invite_share_desc, relay.host),
            modifier = Modifier.size(20.dp),
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.relays_invite_share)) },
            onClick = {
                open = false
                onShare()
            },
            modifier = Modifier.testTag("relay_invite_share_${relay.host}"),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.relays_invite_copy)) },
            onClick = {
                open = false
                onCopy()
            },
            modifier = Modifier.testTag("relay_invite_copy_${relay.host}"),
        )
    }
}

/**
 * The one-line status under a relay's host.
 *
 * The two off states are tested before liveness, and are separate strings: "the plane is off" is a fact
 * about the whole screen and repeats down every row, while "you turned this one off" is a fact about
 * this relay that only its own row can state. Collapsing them would leave a parked relay indistinguishable
 * from its neighbours the moment the master switch went off.
 *
 * [offline] — the platform reports no validated route at all — is tested after liveness and before the
 * relay's own last error: a socket that is up outranks the platform's verdict for the seconds they
 * disagree, and a phone with no Internet must not send its user to check a relay's address. It does not
 * cover the black-hole case (a route the platform still calls validated that swallows the socket);
 * that one arrives as `unreachable`, which is the relay's row saying "cannot be reached" honestly.
 */
@Composable
private fun relayStatusLine(
    relay: RelayRow,
    planeEnabled: Boolean,
    offline: Boolean,
): String =
    when {
        !planeEnabled -> {
            stringResource(R.string.relays_status_off)
        }

        !relay.enabled -> {
            stringResource(R.string.relays_status_paused)
        }

        relay.connected -> {
            stringResource(R.string.relays_status_connected) + " · " +
                pluralStringResource(R.plurals.relays_scope_count, relay.scopeCount ?: 0, relay.scopeCount ?: 0)
        }

        offline -> {
            stringResource(R.string.relays_status_offline)
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
 *
 * The reason is not always an `err` code: a socket that never opened reports a transport failure
 * instead, and the one worth separating is a spool at its connection cap. "Busy, it will come back on
 * its own" and "broken, check the URL" ask opposite things of the user and would otherwise read alike.
 * The client's own verdicts (`ScopeSync.NO_HELLO`, `SpoolConnection.UNRESPONSIVE`, and the two that say
 * the relay's numbers do not fit this app) each get a sentence too, so none of them lands in the generic
 * form as a refusal nobody made.
 */
@Composable
private fun relayErrorLabel(code: String): String =
    when (code) {
        UNREACHABLE -> {
            stringResource(R.string.relays_error_unreachable)
        }

        ScopeSync.NO_HELLO, SpoolConnection.UNRESPONSIVE -> {
            stringResource(R.string.relays_error_unresponsive)
        }

        SpoolErrCode.TOO_LARGE, ScopeSync.OVERLONG_LISTING -> {
            stringResource(R.string.relays_error_incompatible)
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

        BUSY_STATUS -> {
            stringResource(R.string.relays_error_busy)
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
                modifier = Modifier.fillMaxWidth().testTag("relays_add_field").noAutofillMenu(),
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
 * Pasting a commons invite for one relay. The invite is the room's whole key, so the field validates the
 * exact grammar the daemon mints (`CommonsInvite`) and never lets a near-miss through: a typo would be a
 * subscription to a room nobody else is in. The copy says the one thing worth knowing before joining —
 * everyone in the room becomes a contact.
 */
@Composable
private fun JoinCommonsDialog(
    relay: RelayRow,
    isValidInvite: (String) -> Boolean,
    onJoin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var invite by remember { mutableStateOf("") }
    val trimmed = invite.trim()
    val malformed = trimmed.isNotEmpty() && !isValidInvite(trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.relays_commons_join_title, relay.commons?.name ?: stringResource(R.string.commons_title))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.relays_commons_join_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = invite,
                    onValueChange = { invite = it },
                    singleLine = true,
                    isError = malformed,
                    label = { Text(stringResource(R.string.relays_commons_invite_label)) },
                    supportingText = {
                        if (malformed) {
                            Text(stringResource(R.string.relays_commons_invite_invalid))
                        } else {
                            Text(stringResource(R.string.relays_commons_invite_hint))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("relay_commons_invite_field").noAutofillMenu(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onJoin(trimmed) },
                enabled = trimmed.isNotEmpty() && !malformed,
                modifier = Modifier.testTag("relay_commons_join_confirm"),
            ) {
                Text(stringResource(R.string.relays_commons_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** `SpoolStatus.lastError` when nothing answered the dial at all (`ScopeSync.UNREACHABLE`). */
private const val UNREACHABLE = ScopeSync.UNREACHABLE

/**
 * `SpoolStatus.lastError` when the spool refused the WebSocket upgrade because it is at its connection
 * cap. Deliberately an HTTP status and not a close code: spec §7.1 defines four, and none of them means
 * "come back later" — a full spool is a property of the box, not of the protocol.
 */
private const val BUSY_STATUS = "http 503"

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
                                enabled = true,
                                connected = true,
                                scopeCount = 3,
                                carriesPhotos = true,
                            ),
                            RelayRow(
                                url = "wss://frames.example.org/spool/v1",
                                host = "frames.example.org",
                                enabled = true,
                                connected = true,
                                scopeCount = 3,
                                carriesPhotos = false,
                            ),
                            RelayRow(
                                url = "wss://parked.example.org/spool/v1",
                                host = "parked.example.org",
                                enabled = false,
                                connected = false,
                            ),
                            RelayRow(
                                url = "wss://down.example.org/spool/v1",
                                host = "down.example.org",
                                enabled = true,
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
