package app.getknit.knit.ui.chat

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** A long body is a preview here, not the whole message — the bubble is where you read it. */
private const val MAX_BODY_LINES = 4

/**
 * "Message info" (keyed by [messageId]), reached from a message's long-press menu: everyone who reacted,
 * by display name and with the emoji they left, plus the per-message metadata the bubble has no room for
 * — when it was sent, and how far it got.
 *
 * The reaction chip under a bubble can only say "👍 3"; this is where the *which three* lives. Tapping a
 * reactor opens their profile ([onOpenProfile]); your own row is labelled "You" and isn't tappable, the
 * [app.getknit.knit.ui.group.GroupDetailsScreen] roster rule. Deleting the message while this is open
 * pops back rather than leaving a blank screen.
 */
@Composable
fun MessageDetailsScreen(
    messageId: String,
    onBack: () -> Unit,
    onOpenProfile: (nodeId: String) -> Unit,
    viewModel: MessageDetailsViewModel = koinViewModel { parametersOf(messageId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The message can be deleted from the chat behind us (or reaped by retention); close instead of
    // rendering an empty shell. Only a row we actually saw counts as gone — a read that beats the write
    // (a deep link into a still-seeding build) would otherwise close the screen the moment it opened.
    LaunchedEffect(state.vanished) {
        if (state.vanished) onBack()
    }

    MessageDetailsScreenContent(
        state = state,
        onBack = onBack,
        onOpenProfile = onOpenProfile,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageDetailsScreenContent(
    state: MessageDetailsUiState,
    onBack: () -> Unit = {},
    onOpenProfile: (nodeId: String) -> Unit = {},
) {
    // Which emoji the chip row has selected (null = All). Pure view state: it never leaves the screen,
    // and rememberSaveable keeps the choice across a rotation.
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // A filter whose emoji was just retracted away would silently show an empty list; fall back to All.
    val activeFilter = selected?.takeIf { emoji -> state.filters.any { it.emoji == emoji } }
    val shown = state.reactors.filter { activeFilter == null || it.emoji == activeFilter }

    Scaffold(
        modifier = Modifier.testTag("screen_message_details"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.message_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { MessageSummary(state) }
            item { HorizontalDivider() }
            if (state.reactors.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.message_details_no_reactions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("message_details_no_reactions"),
                    )
                }
            } else {
                item {
                    ReactionFilterRow(
                        filters = state.filters,
                        total = state.reactors.size,
                        selected = activeFilter,
                        onSelect = { selected = it },
                    )
                }
                items(shown, key = { it.nodeId }) { reactor ->
                    ReactorListRow(reactor = reactor, onOpen = onOpenProfile)
                }
            }
        }
    }
}

/** The message itself: body (or a photo placeholder), who sent it, when — absolutely — and how far it got. */
@Composable
private fun MessageSummary(state: MessageDetailsUiState) {
    val context = LocalContext.current
    val sender = if (state.mine) stringResource(R.string.chat_self_name) else state.senderName
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text =
                when {
                    // Match the bubble: a message the content filter collapsed there isn't re-revealed here.
                    state.moderationFlagged -> {
                        stringResource(R.string.message_details_hidden_body)
                    }

                    state.body.isNotBlank() -> {
                        state.body
                    }

                    state.hasAttachment -> {
                        stringResource(
                            if (state.isVoiceNote) {
                                R.string.message_details_voice
                            } else {
                                R.string.message_details_attachment
                            },
                        )
                    }

                    else -> {
                        ""
                    }
                },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = MAX_BODY_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("message_details_body"),
        )
        Text(
            text = stringResource(R.string.message_details_sent_by, sender),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // Absolute, unlike the bubble's relative "5m" — the exact time is the point of this screen.
            text =
                DateUtils.formatDateTime(
                    context,
                    state.sentAt,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("message_details_sent_at"),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.testTag("message_details_delivery"),
        ) {
            Icon(
                imageVector =
                    when {
                        !state.mine && state.plane == DeliveryPlane.Internet -> Icons.Filled.Public
                        !state.mine -> Icons.Filled.DoneAll
                        else -> deliveryIcon(state.delivery)
                    },
                // Decorative: the label beside it says the same thing in words.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(deliveryLabel(state.delivery, state.plane, state.mine)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** All + one chip per emoji, filtering the list below. Chip order is fixed by the ViewModel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionFilterRow(
    filters: List<ReactionFilter>,
    total: Int,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.message_details_filter_all, total)) },
            modifier = Modifier.testTag("message_details_filter_all"),
        )
        filters.forEach { filter ->
            FilterChip(
                selected = selected == filter.emoji,
                onClick = { onSelect(filter.emoji) },
                label = {
                    Text(stringResource(R.string.message_details_filter_emoji, filter.emoji, filter.count))
                },
                modifier = Modifier.testTag("message_details_filter_${filter.emoji}"),
            )
        }
    }
}

/** One reactor: avatar + name (or "You"), and the emoji they left, trailing. */
@Composable
private fun ReactorListRow(
    reactor: ReactorRow,
    onOpen: (nodeId: String) -> Unit,
) {
    val name = if (reactor.isSelf) stringResource(R.string.chat_self_name) else reactor.displayName
    // Your own row is inert (nothing to open), so it carries no click label either — the
    // GroupDetailsScreen roster rule.
    val openLabel = stringResource(R.string.chat_view_profile, name)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (reactor.isSelf) {
                        Modifier
                    } else {
                        // Clickable merges the row's children, so it is announced as one node —
                        // "Sam Rivera, 👍" — with the label naming what a tap does.
                        Modifier.clickable(onClickLabel = openLabel, role = Role.Button) { onOpen(reactor.nodeId) }
                    },
                ).testTag("reactor_row_${reactor.nodeId}")
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatarHash = reactor.avatarHash, name = name, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = reactor.emoji, style = MaterialTheme.typography.titleMedium)
    }
}

@Preview(name = "Message details — reactions")
@Composable
fun MessageDetailsScreenPreview() =
    KnitPreview {
        MessageDetailsScreenContent(
            state =
                MessageDetailsUiState(
                    messageId = "demo-group-4",
                    body = "Works for me. I'll grab snacks.",
                    mine = true,
                    senderName = "You",
                    senderNodeId = "me00",
                    sentAt = PREVIEW_NOW - 10 * 60_000L,
                    delivery = DeliveryStatus.Delivered,
                    plane = DeliveryPlane.Internet,
                    reactors =
                        listOf(
                            ReactorRow("samr1v00", "Sam Rivera", null, "👍", PREVIEW_NOW - 9 * 60_000L, false),
                            ReactorRow("priya001", "Priya Nair", null, "👍", PREVIEW_NOW - 8 * 60_000L, false),
                            ReactorRow("theod001", "Theo Diaz", null, "👍", PREVIEW_NOW - 7 * 60_000L, false),
                            ReactorRow("me00", "You", null, "❤️", PREVIEW_NOW - 6 * 60_000L, true),
                        ),
                    filters = listOf(ReactionFilter("👍", 3), ReactionFilter("❤️", 1)),
                ),
        )
    }

@Preview(name = "Message details — no reactions")
@Composable
fun MessageDetailsScreenEmptyPreview() =
    KnitPreview {
        MessageDetailsScreenContent(
            state =
                MessageDetailsUiState(
                    messageId = "demo-group-1",
                    body = "Trailhead Crew assemble! Saturday 7am?",
                    senderName = "Sam Rivera",
                    senderNodeId = "samr1v00",
                    sentAt = PREVIEW_NOW - 60 * 60_000L,
                    plane = DeliveryPlane.Nearby,
                ),
        )
    }
