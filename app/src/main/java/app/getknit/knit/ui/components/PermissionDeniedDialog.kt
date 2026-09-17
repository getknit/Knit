package app.getknit.knit.ui.components

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.getknit.knit.R
import app.getknit.knit.ui.DeviceSupervision
import app.getknit.knit.ui.camera.openAppSettings
import app.getknit.knit.ui.deviceSupervision

/**
 * The dialog a feature gate (location pin, voice-note mic) shows once Android will not ask for its grant
 * again: what turning it on gets you, and the one route left, this app's settings page. On a phone somebody
 * else administers the body ends with [permissionDeniedHint] — the same instant, dialog-less refusal may be
 * a parent's or an administrator's, and the greyed-out switch on that page is theirs (ADR 2026-09.a8ud).
 *
 * [supervision] is read once per showing rather than kept live: the dialog is up for a tap, and the fact
 * cannot change under it.
 */
@Composable
fun PermissionDeniedDialog(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes body: Int,
    onDismiss: () -> Unit,
    supervision: DeviceSupervision = rememberDeviceSupervision(),
) {
    val context = LocalContext.current
    val hint = permissionDeniedHint(supervision)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(stringResource(title)) },
        text = {
            Text(
                text = listOfNotNull(stringResource(body), hint).joinToString("\n\n"),
                modifier = Modifier.testTag("permission_denied_body"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    openAppSettings(context)
                },
            ) { Text(stringResource(R.string.action_open_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The sentence every "Open settings" surface adds on an administered phone: who may have turned the grant
 * off and where they hold it. `null` on a plain phone — there is nobody to name.
 */
@Composable
fun permissionDeniedHint(supervision: DeviceSupervision): String? =
    when (supervision) {
        DeviceSupervision.None -> null
        DeviceSupervision.FamilyLink -> stringResource(R.string.perm_denied_hint_family_link)
        DeviceSupervision.Managed -> stringResource(R.string.perm_denied_hint_managed)
    }

/** [deviceSupervision] read once for the calling composable's lifetime — enough for a dialog or a denial surface. */
@Composable
fun rememberDeviceSupervision(): DeviceSupervision {
    val context = LocalContext.current
    return remember { deviceSupervision(context) }
}
