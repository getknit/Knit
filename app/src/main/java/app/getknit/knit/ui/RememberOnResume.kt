package app.getknit.knit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A system probe read once on first composition and again on every `ON_RESUME` — the shape every
 * settings-page read needs (`backgroundBattery`, `deviceSupervision`), because the app-info page, the
 * exemption dialog and the permission dialog are all separate activities, and coming back from any of them
 * is a resume on the entry that asked. Kept as a plain observer rather than `LifecycleResumeEffect` so the
 * first read happens synchronously inside `remember` and the row never draws a frame in the wrong state
 * (`SettingsScreenContentTest` renders through the preview renderer, which has no lifecycle at all).
 */
@Composable
fun <T> rememberOnResume(probe: () -> T): T {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentProbe by rememberUpdatedState(probe)
    var value by remember { mutableStateOf(probe()) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) value = currentProbe()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return value
}
