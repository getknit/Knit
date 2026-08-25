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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview
import org.koin.androidx.compose.koinViewModel

/**
 * LoRa radio settings: the master switch, the bonded-board picker, a channel-index selector, and the live
 * link status. Structurally the sibling of the Internet-relays screen. Pairing itself happens in the system
 * Bluetooth settings (the board shows a PIN on its OLED); this screen picks from already-bonded boards.
 */
@Composable
fun LoraRadioScreen(onBack: () -> Unit) {
    val viewModel: LoraRadioViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoraRadioScreenContent(
        state = state,
        onBack = onBack,
        onToggle = viewModel::onToggle,
        onPickBoard = viewModel::pickBoard,
        onForgetBoard = viewModel::forgetBoard,
        onSetChannel = viewModel::setChannel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoraRadioScreenContent(
    state: LoraRadioUiState,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onPickBoard: (BoardOption) -> Unit = {},
    onForgetBoard: () -> Unit = {},
    onSetChannel: (Int) -> Unit = {},
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

            Text(
                text = stringResource(R.string.lora_board_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.boards.isEmpty()) {
                Text(
                    text = stringResource(R.string.lora_board_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.boards.forEach { board ->
                    BoardRow(board = board, onClick = { onPickBoard(board) })
                }
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
                text = stringResource(R.string.lora_channel_label, state.channel),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (state.connection == LoraConnState.Ready) {
            state.boardNodeNum?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.snr != null || state.rssi != null) {
                Text(
                    text = stringResource(R.string.lora_signal, state.snr ?: 0f, state.rssi ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    boards = listOf(BoardOption("AA:BB:CC:DD:EE:FF", "Meshtastic_1a2b", selected = true)),
                ),
            onBack = {},
            onToggle = {},
        )
    }
