package app.getknit.knit.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/*
 * The microphone half of what `ui/camera/CameraSupport.kt` does for the camera. It is a *gate returned to
 * the caller* rather than a screen that replaces the composer, because there is nothing to show: a camera
 * needs a viewfinder in place of the chat, a microphone needs the composer to stay exactly where it is and
 * simply start recording once permission lands.
 */

/** Whether this device can record at all, and whether it is currently allowed to. */
class MicGate(
    val hasMicrophone: Boolean,
    val granted: Boolean,
    private val request: () -> Unit,
) {
    /**
     * Runs [onGranted] when recording is already permitted, otherwise asks for the permission and returns
     * false. Deliberately does **not** queue [onGranted] to run after the grant: the user is holding a
     * button, and starting to record when the system dialog closes — seconds later, finger long since
     * lifted — would capture the wrong moment. They press again, and the second press records.
     */
    fun runOrRequest(onGranted: () -> Unit): Boolean {
        if (!hasMicrophone) return false
        if (!granted) {
            request()
            return false
        }
        onGranted()
        return true
    }
}

/**
 * Remembers a [MicGate] for the calling composable: probes for microphone hardware once, tracks whether
 * `RECORD_AUDIO` is granted, and exposes the request launcher.
 *
 * [onDenied] fires when the user actually refuses, so the caller can explain why the button did nothing —
 * with `permanently` true once the system will not show the dialog again, so the caller can point at
 * Settings instead of asking into the void (the `rememberLocationGate` split). As in `CameraGate`, "we
 * haven't asked yet" is kept distinct from "they said no" — without that split the denial copy flashes
 * behind the system dialog on the very first press.
 */
@Composable
fun rememberMicGate(onDenied: (permanently: Boolean) -> Unit = {}): MicGate {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val currentOnDenied by rememberUpdatedState(onDenied)
    val hasMicrophone = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }
    var granted by remember { mutableStateOf(hasRecordAudioPermission(context)) }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            granted = allowed
            if (!allowed) {
                val canAskAgain =
                    activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO) }
                        ?: true
                currentOnDenied(!canAskAgain)
            }
        }

    return remember(hasMicrophone, granted, launcher) {
        MicGate(
            hasMicrophone = hasMicrophone,
            granted = granted,
            request = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
        )
    }
}

internal fun hasRecordAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
