// The file's single top-level class (SettingsFormState, the content composable's state holder) rides
// along with the screen composable that is the file's real subject.
@file:Suppress("MatchingDeclarationName")

package app.getknit.knit.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.isIgnoringBatteryOptimizations
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.requestIgnoreBatteryOptimizations
import app.getknit.knit.ui.theme.DYNAMIC_COLOR_SUPPORTED
import app.getknit.knit.ui.theme.THEME_MODE_SUPPORTED
import app.getknit.knit.ui.theme.ThemeMode
import org.koin.androidx.compose.koinViewModel

/** UI-local projection of [SettingsViewModel]'s per-setting flows for the stateless content. */
internal data class SettingsFormState(
    val header: ProfileHeader = ProfileHeader(),
    val contentFilteringEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val linkPreviewsEnabled: Boolean = false,
    val dynamicColor: Boolean = false,
    val relay: RelaySummary = RelaySummary(),
    val lora: LoraSummary = LoraSummary(),
)

/**
 * App settings. Everything here persists the moment it is touched — there is no Save button, because the
 * one thing that batches its writes is the profile editor behind the header row at the top.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenRelays: () -> Unit = {},
    onOpenLora: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val contentFilteringEnabled by viewModel.contentFilteringEnabled.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val linkPreviewsEnabled by viewModel.linkPreviewsEnabled.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val relay by viewModel.relaySummary.collectAsStateWithLifecycle()
    val lora by viewModel.loraSummary.collectAsStateWithLifecycle()

    val context = LocalContext.current
    SettingsScreenContent(
        form =
            SettingsFormState(
                header = header,
                contentFilteringEnabled = contentFilteringEnabled,
                themeMode = themeMode,
                linkPreviewsEnabled = linkPreviewsEnabled,
                dynamicColor = dynamicColor,
                relay = relay,
                lora = lora,
            ),
        batteryExempt = rememberBatteryExempt(),
        onBack = onBack,
        onOpenProfile = onOpenProfile,
        onToggleContentFiltering = viewModel::setContentFilteringEnabled,
        onSelectThemeMode = viewModel::setThemeMode,
        onToggleLinkPreviews = viewModel::setLinkPreviewsEnabled,
        onToggleDynamicColor = viewModel::setDynamicColor,
        onOpenRelays = onOpenRelays,
        onOpenLora = onOpenLora,
        onAllowBattery = { requestIgnoreBatteryOptimizations(context) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    form: SettingsFormState,
    batteryExempt: Boolean,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onToggleContentFiltering: (Boolean) -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit = {},
    onToggleLinkPreviews: (Boolean) -> Unit = {},
    onToggleDynamicColor: (Boolean) -> Unit = {},
    onOpenRelays: () -> Unit,
    onOpenLora: () -> Unit = {},
    // Whether the Internet-relay plane is introduced at all in this build. A parameter rather than a
    // bare BuildConfig read so the hidden case is previewable and testable; see app/build.gradle.kts.
    showInternetRelays: Boolean = BuildConfig.INTERNET_PLANE,
    // Same, for the LoRa plane.
    showLoraRadio: Boolean = BuildConfig.LORA_PLANE,
    // Whether the platform can do wallpaper colours at all (API 31+). A parameter for the same reason as
    // the two above: the hidden case stays previewable and testable rather than depending on the device.
    showDynamicColor: Boolean = DYNAMIC_COLOR_SUPPORTED,
    // Same, for the platform's per-app night mode — a different fact that happens to carry the same
    // API level; see ui/theme/ThemeMode.kt.
    showThemeMode: Boolean = THEME_MODE_SUPPORTED,
    onAllowBattery: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("screen_settings"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfileHeaderRow(header = form.header, onClick = onOpenProfile)

            ToggleRow(
                title = stringResource(R.string.settings_content_filtering_title),
                subtitle = stringResource(R.string.settings_content_filtering_subtitle),
                enabled = form.contentFilteringEnabled,
                onToggle = onToggleContentFiltering,
                modifier = Modifier.testTag("settings_content_filtering"),
            )

            // Both appearance controls are hidden rather than disabled below API 31, matching the two plane
            // rows: a control that can never move needs a reason next to it, and there is nowhere here to
            // put one. They sit together because they answer the same question about how the app looks.
            if (showThemeMode) {
                ThemeModeRow(
                    selected = form.themeMode,
                    onSelect = onSelectThemeMode,
                    modifier = Modifier.testTag("settings_theme_mode"),
                )
            }

            if (showDynamicColor) {
                ToggleRow(
                    title = stringResource(R.string.settings_dynamic_color_title),
                    subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                    enabled = form.dynamicColor,
                    onToggle = onToggleDynamicColor,
                    modifier = Modifier.testTag("settings_dynamic_color"),
                )
            }

            // Link previews are the one other thing that uses the Internet, so they ship under the same build
            // switch as the relay plane; the subtitle carries the disclosure that would otherwise need a sheet.
            if (showInternetRelays) {
                ToggleRow(
                    title = stringResource(R.string.settings_link_previews_title),
                    subtitle = stringResource(R.string.settings_link_previews_subtitle),
                    enabled = form.linkPreviewsEnabled,
                    onToggle = onToggleLinkPreviews,
                    modifier = Modifier.testTag("settings_link_previews"),
                )
            }

            if (showInternetRelays) InternetRelayRow(summary = form.relay, onClick = onOpenRelays)

            if (showLoraRadio) LoraRadioRow(summary = form.lora, onClick = onOpenLora)

            BatteryOptimizationRow(exempt = batteryExempt, onAllow = onAllowBattery)
        }
    }
}

/**
 * You, at the top of Settings: photo, name, alias, and a chevron into the profile editor. One tap target
 * carrying its own name, so a screen reader announces the person rather than an unlabelled row of parts.
 */
@Composable
private fun ProfileHeaderRow(
    header: ProfileHeader,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick, role = Role.Button)
                .testTag("settings_profile_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            avatarHash = header.avatarHash,
            name = header.name,
            size = 56.dp,
            background = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            textStyle = MaterialTheme.typography.titleLarge,
            // Decorative: the row carries the accessible name, and tapping it does the same thing.
            contentDescription = null,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = header.name,
                style = MaterialTheme.typography.titleMedium,
            )
            header.alias?.let { alias ->
                Text(
                    text = stringResource(R.string.profile_alias, alias),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The light/dark choice: a title, a line of explanation, and one segmented control carrying all three
 * answers at once.
 *
 * Segmented buttons rather than the dialog the neighbouring `NavigatingRow`s would suggest, because picking
 * one applies a configuration change and recreates the Activity — a dialog would be torn down as you chose
 * from it. The explicit 48dp height is the accessibility touch target; Material's segmented container is
 * 40dp, which the ATF suite flags. The labels are centred and hold still as the check appears; see the two
 * comments in the button below for how.
 */
@Composable
private fun ThemeModeRow(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = stringResource(R.string.settings_theme_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_theme_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            THEME_MODES.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = THEME_MODES.size),
                    modifier = Modifier.height(48.dp),
                    icon = {
                        SegmentedButtonDefaults.Icon(
                            active = mode == selected,
                            // An empty box of the same size when inactive, rather than the default null.
                            // Null makes the unselected icon measure zero wide, and the row's measure policy
                            // then slides the label half an icon-width left to re-centre it — so the word
                            // slides under your finger as the check appears. A constant-width slot holds the
                            // word still and crossfades the check in beside it.
                            inactiveContent = { Spacer(Modifier.size(SegmentedButtonDefaults.IconSize)) },
                        )
                    },
                    // Material centres the check and the word *together*, which leaves the word itself right
                    // of centre — most visible on the two buttons showing no check. Matching the check's slot
                    // on the far side of the word puts the word's midpoint back on the pair's midpoint, so it
                    // is centred in the button whichever mode is selected. Padding rather than an offset:
                    // the space is really there, so nothing can be pushed outside the button and clipped.
                    label = { Text(stringResource(label), modifier = Modifier.padding(end = CHECK_SLOT)) },
                )
            }
        }
    }
}

/**
 * The width a segmented button keeps for its check: Material's [SegmentedButtonDefaults.IconSize] plus the
 * 8dp `IconSpacing` its `SegmentedButton.kt` holds privately, with no public constant to read it from.
 */
private val CHECK_SLOT = SegmentedButtonDefaults.IconSize + 8.dp

/** The three choices in the order they read, each with the string that names it. */
private val THEME_MODES =
    listOf(
        ThemeMode.System to R.string.settings_theme_system,
        ThemeMode.Light to R.string.settings_theme_light,
        ThemeMode.Dark to R.string.settings_theme_dark,
    )

/** A titled switch row — every switch on this screen shares it. */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                // One toggle target: the row owns the switch so a screen reader announces the title +
                // subtitle as the label with an on/off state, instead of an unlabelled switch node.
                .toggleable(value = enabled, onValueChange = onToggle, role = Role.Switch)
                .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // null handler: the row's toggleable owns the interaction (avoids a duplicate focus stop).
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/**
 * Entry point to the Internet (spool) plane's own screen, with its current state as the subtitle.
 *
 * A navigating row rather than the switch it used to be: the switch alone was `BuildConfig.DEBUG`-gated,
 * because the app seeds a default relay and a release user who could enable the plane but not edit the
 * list would be stuck with an endpoint they could not remove. The editor lives behind this row, which is
 * what makes the control shippable.
 */
@Composable
private fun InternetRelayRow(
    summary: RelaySummary,
    onClick: () -> Unit,
) {
    // "None added" and "none in use" both mean nothing crosses the plane, but they ask different things
    // of the user — add a relay, or switch one back on — so the empty list keeps its own line.
    val subtitle =
        when {
            !summary.enabled -> stringResource(R.string.relays_summary_off)
            summary.configured == 0 -> stringResource(R.string.relays_summary_none)
            summary.active == 0 -> stringResource(R.string.relays_summary_none_active)
            else -> stringResource(R.string.relays_summary_on, summary.connected, summary.active)
        }
    NavigatingRow(
        title = stringResource(R.string.relays_title),
        subtitle = subtitle,
        onClick = onClick,
        modifier = Modifier.testTag("settings_relays"),
    )
}

/** Entry point to the LoRa radio screen, with its current state as the subtitle. */
@Composable
private fun LoraRadioRow(
    summary: LoraSummary,
    onClick: () -> Unit,
) {
    val subtitle =
        when {
            !summary.enabled -> {
                stringResource(R.string.lora_summary_off)
            }

            summary.plane == LoraPlane.Live && summary.boardName != null -> {
                loraConnectedSubtitle(summary.boardName, summary.battery)
            }

            summary.boardName != null -> {
                stringResource(R.string.lora_summary_disconnected, summary.boardName)
            }

            else -> {
                stringResource(R.string.lora_summary_connecting)
            }
        }
    NavigatingRow(
        title = stringResource(R.string.lora_title),
        subtitle = subtitle,
        onClick = onClick,
        modifier = Modifier.testTag("settings_lora"),
    )
}

/** A titled row that hands off to another screen: title, live subtitle, chevron. One tap target. */
@Composable
private fun NavigatingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick, role = Role.Button)
                .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "On · <board> · connected", with the board's battery appended once it has reported one. */
@Composable
private fun loraConnectedSubtitle(
    boardName: String,
    battery: BoardBattery?,
): String =
    when {
        battery == null -> stringResource(R.string.lora_summary_connected, boardName)
        battery.percent != null -> stringResource(R.string.lora_summary_connected_battery, boardName, battery.percent)
        else -> stringResource(R.string.lora_summary_connected_powered, boardName)
    }

/**
 * Whether the app is currently exempt from battery optimization, refreshed on every screen resume.
 * Lives in the stateful wrapper (not [BatteryOptimizationRow]) because the `PowerManager` read is not
 * available to the preview renderer.
 */
@Composable
private fun rememberBatteryExempt(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    exempt = isIgnoringBatteryOptimizations(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return exempt
}

/** Shows whether the app is exempt from battery optimization, with an "allow" affordance when not. */
@Composable
private fun BatteryOptimizationRow(
    exempt: Boolean,
    onAllow: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text =
                if (exempt) {
                    stringResource(R.string.battery_allowed)
                } else {
                    stringResource(R.string.battery_restricted)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!exempt) {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.battery_allow_button))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ToggleRowPreview() =
    KnitPreview {
        Column {
            ToggleRow(
                title = stringResource(R.string.settings_content_filtering_title),
                subtitle = stringResource(R.string.settings_content_filtering_subtitle),
                enabled = true,
                onToggle = {},
            )
            ToggleRow(
                title = stringResource(R.string.settings_dynamic_color_title),
                subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                enabled = false,
                onToggle = {},
            )
        }
    }

@Preview(showBackground = true)
@Composable
fun ThemeModeRowPreview() =
    KnitPreview {
        Column {
            ThemeModeRow(selected = ThemeMode.System, onSelect = {})
            ThemeModeRow(selected = ThemeMode.Dark, onSelect = {})
        }
    }

@Preview(showBackground = true)
@Composable
fun BatteryOptimizationRowPreview() =
    KnitPreview {
        Column {
            BatteryOptimizationRow(exempt = true, onAllow = {})
            BatteryOptimizationRow(exempt = false, onAllow = {})
        }
    }

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() =
    KnitPreview {
        SettingsScreenContent(
            form =
                SettingsFormState(
                    header =
                        ProfileHeader(
                            name = "Ada Lovelace",
                            alias = "GentlyRustlingRabbit",
                        ),
                    contentFilteringEnabled = true,
                    relay = RelaySummary(enabled = true, configured = 2, active = 2, connected = 1),
                    lora = LoraSummary(enabled = true, boardName = "Meshtastic_1a2b", plane = LoraPlane.Live),
                ),
            batteryExempt = false,
            onBack = {},
            onToggleContentFiltering = {},
            onOpenRelays = {},
            onAllowBattery = {},
        )
    }

// A fresh install: no name yet, so the header falls back to the generated alias — and drops the alias line
// under it, which would otherwise print the same word twice. Both planes are off.
@Preview(showBackground = true)
@Composable
fun SettingsScreenNewUserPreview() =
    KnitPreview {
        SettingsScreenContent(
            form = SettingsFormState(header = ProfileHeader(name = "GentlyRustlingRabbit")),
            batteryExempt = true,
            onBack = {},
            onToggleContentFiltering = {},
            onOpenRelays = {},
            onAllowBattery = {},
        )
    }
