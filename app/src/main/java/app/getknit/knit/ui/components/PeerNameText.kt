package app.getknit.knit.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.getknit.knit.ui.preview.KnitPreview

/**
 * A person's name as one line of text, with the collision [discriminator] — the ` (Alias)` suffix a
 * `PeerLabel` appends when another known identity renders to the same name (ADR 058) — drawn muted so
 * the name stays the eye-catcher. [text] is the already-formatted label (`PeerLabel.text`), so every row
 * model keeps a plain string for its accessibility and string sinks; when [discriminator] is null, or
 * [text] does not end in it, this is exactly a [Text].
 *
 * One `Text`, one semantics node: the announced text is the full label, and nothing here sets a
 * `contentDescription`, so the accessibility suite sees no redundant description.
 */
@Composable
fun PeerNameText(
    text: String,
    discriminator: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val mutedSize = MaterialTheme.typography.labelMedium.fontSize
    val annotated =
        remember(text, discriminator, muted, mutedSize) {
            buildAnnotatedString {
                append(text)
                val suffix = discriminator?.let { " ($it)" }
                if (suffix != null && text.endsWith(suffix)) {
                    addStyle(
                        SpanStyle(color = muted, fontSize = mutedSize, fontWeight = FontWeight.Normal),
                        text.length - suffix.length,
                        text.length,
                    )
                }
            }
        }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Preview(showBackground = true)
@Composable
fun PeerNameTextPreview() =
    KnitPreview {
        PeerNameText(
            text = "Alice (JoyfulFerret)",
            discriminator = "JoyfulFerret",
            style = MaterialTheme.typography.titleMedium,
        )
    }

@Preview(showBackground = true)
@Composable
fun PeerNameTextPlainPreview() =
    KnitPreview {
        PeerNameText(text = "Alice", discriminator = null, style = MaterialTheme.typography.titleMedium)
    }
