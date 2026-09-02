package app.getknit.knit.ui.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview

/*
 * Shared plumbing for this app's two camera surfaces — the identity-QR scanner (`ui/scan/`) and the
 * in-chat photo capture (`PhotoCaptureContent`). Both are plain composables rendered *in place of*
 * the calling screen's content rather than an Activity or a `Dialog`: see ADR 015 for why (screens
 * here take lambdas, `KnitApp` owns navigation, and a camera `SurfaceView` inside a `Dialog` window
 * has z-ordering quirks on exactly the hardware that rewrite exists to support).
 */

/**
 * Hardware probe + `CAMERA` permission gate, rendering [content] only once the camera is actually
 * usable and one of the [CameraMessage] states otherwise. [noCameraMessage] and [deniedMessage] are
 * per-surface copy — the QR scanner talks about codes, the capture screen about photos.
 *
 * Handles Back itself via [onCancel], so callers don't repeat it.
 */
@Composable
internal fun CameraGate(
    noCameraMessage: String,
    deniedMessage: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    // Separates "we haven't asked yet" from "the user said no", so the denial copy only appears after an
    // actual refusal instead of flashing behind the system dialog.
    var asked by remember { mutableStateOf(false) }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            asked = true
        }

    BackHandler(onBack = onCancel)
    LaunchedEffect(hasCamera) {
        if (hasCamera && !granted) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            !hasCamera -> {
                CameraMessage(noCameraMessage, onCancel)
            }

            granted -> {
                content()
            }

            asked -> {
                CameraMessage(
                    message = deniedMessage,
                    onCancel = onCancel,
                    onOpenSettings = { openAppSettings(context) },
                )
            }

            else -> {
                // The system permission dialog is up — leave the surface empty rather than flash a denial.
            }
        }
    }
}

/** Non-camera states (no hardware, permission refused, camera failed to open) — all previewable. */
@Composable
internal fun CameraMessage(
    message: String,
    onCancel: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onOpenSettings != null) {
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_open_settings))
            }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

internal fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Opens this app's system settings page — the only route back from a "don't ask again" camera denial. */
internal fun openAppSettings(context: Context) {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Preview(showBackground = true)
@Composable
fun CameraDeniedPreview() =
    KnitPreview {
        CameraMessage(
            message = "Knit needs camera access to take a photo.",
            onCancel = {},
            onOpenSettings = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun CameraUnavailablePreview() =
    KnitPreview {
        CameraMessage(
            message = "The camera couldn't be opened. Close any other app using it and try again.",
            onCancel = {},
        )
    }
