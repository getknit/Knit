package app.getknit.knit.ui.lora

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview
import org.koin.androidx.compose.koinViewModel

/**
 * LoRa radio settings: the master switch, the bonded-board picker, a channel-index selector, and the live
 * link status. Structurally the sibling of the Internet-relays screen. Pairing itself happens in the system
 * Bluetooth settings (the board shows a PIN on its OLED); this screen picks from already-bonded boards —
 * re-listed on every resume, so a board paired over there is offered the moment the user comes back.
 */
@Composable
fun LoraRadioScreen(onBack: () -> Unit) {
    val viewModel: LoraRadioViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBoards()
        onPauseOrDispose {}
    }
    LoraRadioScreenContent(
        state = state,
        onBack = onBack,
        onToggle = viewModel::onToggle,
        onToggleDms = viewModel::onToggleDms,
        onPickBoard = viewModel::pickBoard,
        onForgetBoard = viewModel::forgetBoard,
        onShowAllBoards = viewModel::setShowAllBoards,
        onSetChannel = viewModel::setChannel,
        onProvision = viewModel::provisionChannel,
        onDismissProvision = viewModel::dismissProvisionOutcome,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoraRadioScreenContent(
    state: LoraRadioUiState,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onToggleDms: (Boolean) -> Unit = {},
    onPickBoard: (BoardOption) -> Unit = {},
    onForgetBoard: () -> Unit = {},
    onShowAllBoards: (Boolean) -> Unit = {},
    onSetChannel: (Int) -> Unit = {},
    onProvision: () -> Unit = {},
    onDismissProvision: () -> Unit = {},
) {
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.testTag("screen_lora_radio"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.lora_title)) },
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
                text = stringResource(R.string.lora_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DmSwitchRow(enabled = state.dmEnabled, active = state.enabled, onToggle = onToggleDms)

            Text(
                text = stringResource(R.string.lora_board_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            when {
                state.boards.isNotEmpty() -> {
                    state.boards.forEach { board ->
                        BoardRow(board = board, onClick = { onPickBoard(board) })
                    }
                }

                // Two empty states, because they ask for different things: pair a board at all, or reveal
                // the paired devices the board filter is hiding.
                !state.anyBonded -> {
                    Text(
                        text = stringResource(R.string.lora_board_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        text = stringResource(R.string.lora_board_none_meshtastic),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("lora_board_none_meshtastic"),
                    )
                }
            }
            if (state.hiddenBoards > 0 || state.showAllBoards) {
                ShowAllBoardsRow(hidden = state.hiddenBoards, checked = state.showAllBoards, onToggle = onShowAllBoards)
            }
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                modifier = Modifier.testTag("lora_open_bt_settings"),
            ) {
                Text(stringResource(R.string.lora_open_settings))
            }
            if (state.boardAddress != null) {
                TextButton(onClick = onForgetBoard) { Text(stringResource(R.string.lora_forget)) }
            }

            Text(
                // The slot's name once the board has told us (the index alone is opaque — "Knit" is the whole point).
                text =
                    when {
                        state.channelName != null -> stringResource(R.string.lora_channel_named, state.channel, state.channelName)
                        state.connection == LoraConnState.Ready -> stringResource(R.string.lora_channel_unnamed, state.channel)
                        else -> stringResource(R.string.lora_channel_label, state.channel)
                    },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("lora_channel_title"),
            )
            ProvisionSection(
                state = state,
                onProvision = onProvision,
                onDismissProvision = onDismissProvision,
            )
            Text(
                text = stringResource(R.string.lora_channel_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChannelRow(channel = state.channel, onSetChannel = onSetChannel)

            StatusRow(state = state)
        }
    }
}

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
                .testTag("lora_switch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_lora_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.settings_lora_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/**
 * The private-messages switch (ADR 039): DMs stay end-to-end encrypted over LoRa, but their metadata becomes
 * visible at kilometre range, so the user can keep them on the phone radios. Inert while the plane is off.
 */
@Composable
private fun DmSwitchRow(
    enabled: Boolean,
    active: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, enabled = active, onValueChange = onToggle, role = Role.Switch)
                .testTag("lora_dm_switch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.lora_dm_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.lora_dm_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null, enabled = active)
    }
}

/** Reveals the bonded devices the board filter hides — a heuristic, so the user can always see everything. */
@Composable
private fun ShowAllBoardsRow(
    hidden: Int,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, onValueChange = onToggle, role = Role.Switch)
                .testTag("lora_show_all_boards"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.lora_show_all_boards), style = MaterialTheme.typography.bodyMedium)
            if (hidden > 0) {
                Text(
                    text = pluralStringResource(R.plurals.lora_boards_hidden, hidden, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun BoardRow(
    board: BoardOption,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = board.selected, onClick = onClick, role = Role.RadioButton)
                .testTag("lora_board_${board.address}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = board.selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(board.name, style = MaterialTheme.typography.bodyLarge)
            Text(board.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Int,
    onSetChannel: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { if (channel > 0) onSetChannel(channel - 1) },
            enabled = channel > 0,
        ) { Text("−") }
        Spacer(Modifier.width(16.dp))
        Text("$channel", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(16.dp))
        OutlinedButton(
            onClick = { if (channel < MAX_CHANNEL) onSetChannel(channel + 1) },
            enabled = channel < MAX_CHANNEL,
        ) { Text("+") }
    }
}

@Composable
private fun ProvisionSection(
    state: LoraRadioUiState,
    onProvision: () -> Unit,
    onDismissProvision: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Connected, but the selected slot is not the Knit channel: the one setup step most people still owe
        // (both boards must be provisioned before a frame crosses), so it is said out loud and the button below
        // is the filled, emphasized one. Once the slot is Knit the button drops to a tonal "done" weight.
        if (state.channelMismatch) {
            Text(
                text = stringResource(R.string.lora_channel_mismatch, state.channel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("lora_channel_warning"),
            )
        }
        val enabled = state.connection == LoraConnState.Ready && !state.provisioning
        val label: @Composable () -> Unit = {
            if (state.provisioning) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.lora_provision_running))
            } else {
                Text(stringResource(R.string.lora_provision_button))
            }
        }
        if (state.channelMismatch) {
            Button(onClick = onProvision, enabled = enabled, modifier = Modifier.testTag("lora_provision")) { label() }
        } else {
            FilledTonalButton(onClick = onProvision, enabled = enabled, modifier = Modifier.testTag("lora_provision")) { label() }
        }
        state.provisionOutcome?.let { outcome ->
            val (message, isError) = outcome.messageAndSeverity()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("lora_provision_outcome"),
                )
                TextButton(onClick = onDismissProvision) { Text(stringResource(R.string.action_dismiss)) }
            }
        }
    }
}

private fun LoraProvisionOutcome.messageAndSeverity(): Pair<Int, Boolean> =
    when (this) {
        LoraProvisionOutcome.Provisioned -> R.string.lora_provisioned to false
        LoraProvisionOutcome.AlreadyPresent -> R.string.lora_provision_already to false
        LoraProvisionOutcome.NoFreeSlot -> R.string.lora_provision_no_slot to true
        LoraProvisionOutcome.Failed -> R.string.lora_provision_failed to true
        LoraProvisionOutcome.NotReady -> R.string.lora_provision_not_ready to true
    }

@Composable
private fun StatusRow(state: LoraRadioUiState) {
    val label =
        when (state.connection) {
            LoraConnState.Ready -> stringResource(R.string.lora_status_connected)
            LoraConnState.Connecting -> stringResource(R.string.lora_status_connecting)
            LoraConnState.Reconnecting -> stringResource(R.string.lora_status_reconnecting)
            LoraConnState.NeedsPairing -> stringResource(R.string.lora_status_needs_pairing)
            LoraConnState.Unavailable -> stringResource(R.string.lora_status_bt_off)
            LoraConnState.Off -> stringResource(R.string.lora_status_off)
        }
    Column(modifier = Modifier.fillMaxWidth().testTag("lora_status")) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (state.connection == LoraConnState.Ready) {
            val detail = MaterialTheme.typography.bodySmall
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            state.boardNodeNum?.let { Text(it, style = detail, color = muted) }
            state.firmware?.let { Text(stringResource(R.string.lora_firmware, it), style = detail, color = muted) }
            if (state.snr != null || state.rssi != null) {
                Text(
                    text = stringResource(R.string.lora_signal, state.snr ?: 0f, state.rssi ?: 0),
                    style = detail,
                    color = muted,
                )
            }
            // Who the board has heard speak Knit (the plane's 45-min reachable linger): the only sign, short
            // of a message, that the far side is provisioned and in range.
            Text(
                text = pluralStringResource(R.plurals.lora_peers_heard, state.heard, state.heard),
                style = detail,
                color = muted,
                modifier = Modifier.testTag("lora_peers_heard"),
            )
        }
    }
}

private const val MAX_CHANNEL = 7

@Preview(showBackground = true)
@Composable
private fun LoraRadioScreenPreview() =
    KnitPreview {
        LoraRadioScreenContent(
            state =
                LoraRadioUiState(
                    enabled = true,
                    boardName = "Meshtastic_1a2b",
                    boardAddress = "AA:BB:CC:DD:EE:FF",
                    channel = 1,
                    connection = LoraConnState.Ready,
                    boardNodeNum = "!12345678",
                    snr = 6.5f,
                    rssi = -85,
                    heard = 2,
                    firmware = "2.5.0",
                    channelName = "Knit",
                    boards = listOf(BoardOption("AA:BB:CC:DD:EE:FF", "Meshtastic_1a2b", selected = true)),
                    hiddenBoards = 1,
                    anyBonded = true,
                ),
            onBack = {},
            onToggle = {},
        )
    }
