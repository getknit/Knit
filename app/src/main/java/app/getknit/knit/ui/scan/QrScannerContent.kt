package app.getknit.knit.ui.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.camera.core.Preview as CameraXPreview

/**
 * Full-screen identity-QR scanner, rendered *in place of* the calling screen's content (see
 * [app.getknit.knit.ui.verify.VerifyContactScreen] and [app.getknit.knit.ui.profile.ProfileDetailsScreen]).
 * It is a plain composable rather than an Activity or a `Dialog`: screens in this app take lambdas and
 * `KnitApp` owns navigation, and a camera `SurfaceView` inside a `Dialog` window has z-ordering quirks on
 * exactly the kind of hardware this rewrite exists to support.
 *
 * Owns the Android-only pieces (camera hardware probe, the `CAMERA` permission launcher, the CameraX
 * binding); [QrScannerMessage] is the previewable non-camera state. Decoding is [QrDecoder], which is
 * Android-free and cannot throw. See ADR 015 for why this replaced zxing-android-embedded.
 *
 * [onResult] fires at most once, on the main thread, with the decoded text.
 */
@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
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

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .testTag("screen_scan"),
    ) {
        when {
            !hasCamera -> {
                QrScannerMessage(stringResource(R.string.scan_no_camera), onCancel)
            }

            granted -> {
                CameraFeed(onResult = onResult, onCancel = onCancel)
            }

            asked -> {
                QrScannerMessage(
                    message = stringResource(R.string.scan_permission_denied),
                    onCancel = onCancel,
                    onOpenSettings = { openAppSettings(context) },
                )
            }

            // The system permission dialog is up — leave the surface empty rather than flash a denial.
            else -> {
                Unit
            }
        }
    }
}

/** Live camera preview with the QR analyzer bound to it for as long as this composable is in the tree. */
@Composable
private fun CameraFeed(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // rememberUpdatedState so the analyzer — captured once, when the effect runs — always calls the
    // current lambda rather than a stale one from an earlier recomposition.
    val currentResult by rememberUpdatedState(onResult)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val delivered = AtomicBoolean(false)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        future.addListener({
            runCatching {
                val resolved = future.get()
                provider = resolved
                val preview =
                    CameraXPreview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
                val analysis =
                    qrAnalysis(analysisExecutor, delivered) { text -> previewView.post { currentResult(text) } }
                resolved.unbindAll()
                resolved.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { failed = true }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { provider?.unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    if (failed) {
        QrScannerMessage(stringResource(R.string.scan_camera_unavailable), onCancel)
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.scan_hint),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

/**
 * The analysis use case. [delivered] latches on the first frame carrying a code — the analyzer keeps
 * running until CameraX tears it down, so without it a lingering frame could fire [onText] twice.
 */
private fun qrAnalysis(
    executor: Executor,
    delivered: AtomicBoolean,
    onText: (String) -> Unit,
): ImageAnalysis =
    ImageAnalysis
        .Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .apply {
            setAnalyzer(executor) { image ->
                scan(image)?.let { text ->
                    if (delivered.compareAndSet(false, true)) onText(text)
                }
            }
        }

/**
 * Pulls the luminance plane off one CameraX frame and hands it to [QrDecoder]. Always closes the
 * [ImageProxy] — a leaked frame stalls the analyzer for good — and never lets a throwable escape: this
 * runs on CameraX's analysis executor, where an escape would kill the process (which is exactly how the
 * zxing-android-embedded scanner this replaced failed).
 */
private fun scan(image: ImageProxy): String? =
    image.use {
        runCatching {
            val plane = it.planes.firstOrNull() ?: return@runCatching null
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            QrDecoder.decode(bytes, plane.rowStride, plane.pixelStride, it.width, it.height)
        }.getOrNull()
    }

/** Non-camera states (no hardware, permission refused, camera failed to open) — all previewable. */
@Composable
internal fun QrScannerMessage(
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
                Text(stringResource(R.string.scan_open_settings))
            }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Opens this app's system settings page — the only route back from a "don't ask again" camera denial. */
private fun openAppSettings(context: Context) {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Preview(showBackground = true)
@Composable
fun QrScannerDeniedPreview() =
    KnitPreview {
        QrScannerMessage(
            message = "Knit needs camera access to scan a contact's code.",
            onCancel = {},
            onOpenSettings = {},
        )
    }
