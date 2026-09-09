package app.getknit.knit.ui.chat

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
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/*
 * The location half of what `ui/voice/MicGate.kt` does for the microphone: a gate handed back to the caller,
 * because the composer stays where it is and simply starts looking once the grant lands. It is asked for on
 * the pin's first use and never at onboarding (`ui/Permissions.kt` keeps the 33+ tier location-free).
 */

/** Whether reading the position is allowed right now, and the way to ask when it is not. */
class LocationGate(
    private val isGranted: () -> Boolean,
    private val request: (onGranted: () -> Unit) -> Unit,
) {
    /**
     * Runs [onGranted] now when either location permission is held, otherwise asks for both and runs it once
     * the user allows. Unlike the mic gate this **does** carry the action across the dialog: nothing here is
     * timed to a finger, and a second tap after saying yes would read as the first one not having worked.
     */
    fun runOrRequest(onGranted: () -> Unit) {
        if (isGranted()) onGranted() else request(onGranted)
    }
}

/**
 * Remembers a [LocationGate] for the calling composable. Both permissions are requested together — Android 12
 * requires the pair for its precise/approximate toggle — and either grant counts; the ViewModel reads which
 * one it got. [onDenied] fires only on an actual refusal, with `permanently` true once the system will not
 * show the dialog again, so the caller can point at Settings instead of asking into the void.
 */
@Composable
fun rememberLocationGate(onDenied: (permanently: Boolean) -> Unit): LocationGate {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val currentOnDenied by rememberUpdatedState(onDenied)
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val action = pending.value
            pending.value = null
            val allowed = LOCATION_PERMISSIONS.any { result[it] == true }
            if (allowed) {
                action?.invoke()
            } else {
                val canAskAgain =
                    activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION) }
                        ?: true
                currentOnDenied(!canAskAgain)
            }
        }
    return remember(launcher) {
        LocationGate(
            // Re-read on every use rather than cached: the user can revoke the grant in Settings between taps.
            isGranted = { hasLocationPermission(context) },
            request = { action ->
                pending.value = action
                launcher.launch(LOCATION_PERMISSIONS)
            },
        )
    }
}

internal fun hasLocationPermission(context: Context): Boolean =
    LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
