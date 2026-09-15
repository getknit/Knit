package app.getknit.knit.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The heading that names a block of rows — Diagnostics' sections, Search's result groups, and both Profile
 * screens. A primary-coloured `titleSmall` rather than a card header: the app groups by label and spacing,
 * and only two screens in the whole build draw a Card at all.
 *
 * Carries [heading] semantics so TalkBack's heading navigation can jump between sections; a screen that
 * draws several of these is otherwise one long undifferentiated sweep.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                .semantics { heading() },
    )
}
