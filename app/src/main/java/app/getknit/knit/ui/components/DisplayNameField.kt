package app.getknit.knit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.getknit.knit.R
import app.getknit.knit.TextLimits
import app.getknit.knit.ui.preview.KnitPreview

/**
 * The one display-name field, shared by the Profile screen and onboarding's name page so the two cannot
 * drift: capped at [TextLimits.DISPLAY_NAME] with a counter, the auto-alias as the placeholder and — once a
 * name is typed and the placeholder vanishes — still named on the supporting line, since the alias is what
 * tells two same-named people apart (ADR 058). [onCommit] fires when focus leaves, for the owner to snap
 * stray whitespace; the caller decides when anything is persisted.
 */
@Composable
fun DisplayNameField(
    value: String,
    alias: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    aliasMore: String = "",
    aliasLineModifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .noAutofillMenu()
                .onFocusChanged { if (!it.isFocused) onCommit() },
        label = { Text(stringResource(R.string.profile_display_name_label)) },
        placeholder = { if (alias.isNotEmpty()) Text(alias) },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        supportingText = {
            Column {
                if (alias.isNotEmpty()) {
                    AliasLine(alias, aliasMore, aliasLineModifier)
                }
                CharCounter(value.length, TextLimits.DISPLAY_NAME)
            }
        },
    )
}

/**
 * `Alias: **SmartlyBrightSparrow** ElegantlyCheeryPlover` on one line: the alias bold, since it is what
 * tells two same-named people apart (ADR 058) and usually all the owner needs to know; the token after it
 * in the supporting text's own muted colour, for the day a label grows past the alias on someone else's
 * phone (ADR 2026-09.wuqj). One `Text`, one semantics node.
 */
@Composable
private fun AliasLine(
    alias: String,
    aliasMore: String,
    modifier: Modifier = Modifier,
) {
    val line = stringResource(R.string.profile_alias, alias)
    val emphasis = MaterialTheme.colorScheme.onSurface
    val annotated =
        remember(line, alias, aliasMore, emphasis) {
            buildAnnotatedString {
                append(line)
                val start = line.indexOf(alias)
                if (start >= 0) {
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = emphasis), start, start + alias.length)
                }
                if (aliasMore.isNotEmpty()) append(" $aliasMore")
            }
        }
    Text(text = annotated, modifier = modifier)
}

/** Right-aligned "used / limit" counter shown beneath a capped single-line field. */
@Composable
fun CharCounter(
    length: Int,
    limit: Int,
) {
    Text(
        text = "$length / $limit",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview(showBackground = true)
@Composable
fun DisplayNameFieldPreview() =
    KnitPreview {
        Column(modifier = Modifier.fillMaxWidth()) {
            DisplayNameField(value = "", alias = "SmartlyBrightSparrow", onValueChange = {}, onCommit = {})
            DisplayNameField(
                value = "Sam Rivera",
                alias = "SmartlyBrightSparrow",
                aliasMore = "ElegantlyCheeryPlover",
                onValueChange = {},
                onCommit = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

@Preview(showBackground = true)
@Composable
fun CharCounterPreview() =
    KnitPreview {
        Column(modifier = Modifier.fillMaxWidth()) {
            CharCounter(length = 12, limit = 40)
            CharCounter(length = 40, limit = 40)
        }
    }
