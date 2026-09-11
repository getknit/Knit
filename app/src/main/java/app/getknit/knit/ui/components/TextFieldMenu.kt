package app.getknit.knit.ui.components

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.ui.Modifier

/**
 * Drops "Autofill" from a text field's floating toolbar.
 *
 * Compose offers the item on every editable field whenever the cursor is collapsed, without asking
 * whether an autofill service is enabled or the field has a content type, and pins it inline next to
 * Paste (the platform `EditText` tucks it in the overflow). Nothing Knit asks for — a message, a name,
 * a status, a search, a relay address, a contact link — is a saved credential, so no field here wants it.
 */
fun Modifier.noAutofillMenu(): Modifier = filterTextContextMenuComponents { it.key != TextContextMenuKeys.AutofillKey }
