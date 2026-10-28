package com.loopjet.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live QR scanner.
 *
 * The receiving phone already shows a QR code (Receive › QrPanel, and the Invite/Web Share page).
 * Until now the only way to use it on the sending phone was to retype the link, so this closes the
 * loop: point the camera at the other phone and the pairing link is used directly.
 *
 * What it accepts, all decoded from the same frame:
 *  - `loopjet://ip:port#fingerprint#device` → pairs and sends the current selection
 *  - a bare six-digit code → the same path as "pair without discovery"
 *  - an `http(s)://` link (the Web Share page) → copied to the clipboard, since it belongs in a browser
 *
 * Rendering notes: this screen is deliberately dark in both themes (camera preview), so every colour
 * here is explicit instead of coming from the theme — the opposite of the bug fixed in the rest of
 * the app, and the reason nothing on this screen can end up black-on-black.
 */
@Composable
fun QrScannerScreen(vm: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember { mutableStateOf(hasCamera(context)) }
    var blockedForever by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    // One payload per visit: the analyzer fires on every frame, and a second delivery would start a
    // second pairing while the first is still connecting.
    val delivered = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        // Once the dialog has been refused twice Android stops showing it: the only way back is the
        // app's settings page, so stop offering a button that does nothing.
        if (!ok) blockedForever = !(context as? android.app.Activity)
            ?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ?: false
    }

    // Asked only here, never during onboarding: scanning is optional.
    LaunchedEffect(granted) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // One entry point for every code type (v1, v2 with a network, WIFI:, six digits, web link).
    // The routing lives in the view model so the scanner stays a camera and nothing else.
    val onPayload: (String) -> Unit = { raw ->
        onClose()
        vm.acceptScanned(raw)
    }

    val deliver: (String) -> Unit = { text ->
        if (delivered.compareAndSet(false, true)) mainHandler.post { onPayload(text) }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(granted) {
        if (!granted) {
            return@DisposableEffect onDispose { }
        }
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val reader = MultiFormatReader().apply {
                    setHints(
                        mapOf(
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                            DecodeHintType.TRY_HARDER to true
                        )
                    )
                }
                analysis.setAnalyzer(executor) { proxy -> decodeFrame(proxy, reader, deliver) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (t: Throwable) {
                // No camera module, another app holding it, or a device without Camera2: say so and
                // leave the manual link/code path reachable instead of showing a black rectangle.
                cameraError = "Camera unavailable on this device (${t.javaClass.simpleName})"
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            runCatching { executor.shutdown() }
        }
    }

    LaunchedEffect(torchOn) {
        runCatching { camera?.cameraControl?.enableTorch(torchOn) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Scan the other phone's QR",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close scanner", tint = Color.White)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                cameraError != null -> ScannerFallback(onClose, cameraError!!, null)
                !granted -> ScannerFallback(
                    onClose = onClose,
                    message = if (blockedForever) {
                        "The camera permission is blocked for LoopJet. Android will not ask again — open the app settings to allow it."
                    } else {
                        "LoopJet needs the camera to read a QR code. Nothing is recorded or uploaded."
                    },
                    action = if (blockedForever) {
                        "Open app settings" to {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null)
                            )
                            runCatching { context.startActivity(intent) }
                        }
                    } else {
                        "Allow camera" to { permissionLauncher.launch(Manifest.permission.CAMERA) }
                    }
                )
                else -> {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    // Framing guide: a dashed square, sized to the shorter edge so it stays square on
                    // any screen, with the hint underneath rather than overlapping the code.
                    Canvas(Modifier.fillMaxSize()) {
                        val side = size.minDimension * 0.62f
                        val left = (size.width - side) / 2f
                        val top = (size.height - side) / 2f
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.92f),
                            topLeft = Offset(left, top),
                            size = Size(side, side),
                            cornerRadius = CornerRadius(28f, 28f),
                            style = Stroke(
                                width = 5f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(34f, 26f), 0f)
                            )
                        )
                    }
                    Text(
                        "Point the camera at the code on the other phone's Receive screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 28.dp, vertical = 24.dp)
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
            Text(
                "Camera stays on this phone",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f)
            )
            if (granted && cameraError == null) {
                TextButton(onClick = { torchOn = !torchOn }) {
                    Text(
                        if (torchOn) "Torch off" else "Torch",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            TextButton(onClick = onClose) {
                Text("Cancel", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Shown when the camera is missing, refused or broken: the manual path still works. */
@Composable
private fun ScannerFallback(
    onClose: () -> Unit,
    message: String,
    action: Pair<String, () -> Unit>?
) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            GradientButton(action.first, Modifier.fillMaxWidth(), onClick = action.second)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onClose) {
            Text(
                "Enter the code or link by hand instead",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private fun hasCamera(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * Decodes one CameraX frame. Only the luma plane is read — a QR code is monochrome, so the chroma
 * planes add nothing but work — and `rowStride` is passed through because many sensors pad each row,
 * which would otherwise skew every frame by a few pixels per row.
 */
private fun decodeFrame(proxy: ImageProxy, reader: MultiFormatReader, onText: (String) -> Unit) {
    try {
        val image = proxy.image ?: return
        if (image.planes.isEmpty()) return
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val source = PlanarYUVLuminanceSource(
            data,
            plane.rowStride.coerceAtLeast(image.width),
            image.height,
            0,
            0,
            image.width,
            image.height,
            false
        )
        val text = runCatching {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        }.getOrNull()?.text
        if (!text.isNullOrBlank()) onText(text)
    } catch (t: Throwable) {
        // A single unreadable or malformed frame must never stop the analyzer.
    } finally {
        runCatching { reader.reset() }
        runCatching { proxy.close() }
    }
}
