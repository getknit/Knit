package app.getknit.knit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The container a group of [DetailRow]s (and any sibling rows) sits in. The facts used to run as bare text
 * straight on the background, which left a screen's sections reading as one column of loose lines; a
 * surface behind them gives each section its own edge without the ceremony of a titled card.
 *
 * It carries the 16.dp gutter, so the rows inside pad only against the card.
 */
@Composable
fun DetailCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp), content = content)
    }
}

/**
 * One labelled fact — "Node ID", "First met", "LoRa radio" — as a label/value pair. Replaces the centred
 * `"Label: value"` sentences the Profile screens used to stack, which gave a reader no way to tell an
 * identity line from a radio line at a glance.
 *
 * The two halves are weighted rather than given a fixed label column: a translated label that outgrows its
 * share wraps instead of clipping. Carries the same 16.dp inset as [SectionHeader], so the rows line up
 * under the heading that names them and the parent column pads only vertically.
 *
 * [onCopy] adds a copy affordance; the whole row becomes the tap target (merged into one TalkBack stop that
 * reads the label, the value and the action), which is why it takes the 48.dp minimum height only then.
 */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    copyLabel: String? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (onCopy != null) {
                        Modifier
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button, onClickLabel = copyLabel, onClick = onCopy)
                    } else {
                        Modifier
                    },
                ).semantics(mergeDescendants = true) {}
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(LABEL_WEIGHT),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(VALUE_WEIGHT),
        )
        // The trailing slot is reserved whether or not this row copies. The weights split what is left
        // after it, so a row that skipped the icon would give its label column the icon's width and push
        // its value right — which is exactly how the un-copyable "LoRa radio" line came out of line with
        // the two above it.
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.size(18.dp)) {
            if (onCopy != null) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    // Decorative: the row carries the accessible name and the click label.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val LABEL_WEIGHT = 0.38f
private const val VALUE_WEIGHT = 0.62f
