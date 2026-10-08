package dev.herdroid.ui.hosts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.herdroid.data.HostLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val qrOptions = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
private const val INVALID_QR = "This isn't a Wooloo machine QR. Open Remote Access on your Mac and scan its QR."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanQrScreen(onBack: () -> Unit, onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    fun cameraGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(cameraGranted()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraAttempt by remember { mutableStateOf(0) }
    var readingImage by remember { mutableStateOf(false) }
    var selectingImage by remember { mutableStateOf(false) }
    var paste by rememberSaveable { mutableStateOf(false) }
    var pastedLink by rememberSaveable { mutableStateOf("") }
    var pastedError by remember { mutableStateOf(false) }
    val accepted = remember { AtomicBoolean(false) }
    val active = remember { AtomicBoolean(true) }
    val latestScanned by rememberUpdatedState(onScanned)

    fun accept(raw: String): Boolean {
        if (!active.get() || accepted.get()) return false
        if (HostLink.parse(raw) == null) return false
        if (accepted.compareAndSet(false, true)) latestScanned(raw.trim())
        return true
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) {
        if (!granted && !requested) {
            requested = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    DisposableEffect(lifecycle) {
        active.set(true)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = cameraGranted()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            active.set(false)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        selectingImage = false
        if (uri != null && !readingImage && !accepted.get()) {
            readingImage = true
            error = null
            scope.launch {
                val input = withContext(Dispatchers.IO) {
                    runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()
                }
                if (input == null) {
                    readingImage = false
                    error = "Couldn't read that image. Choose another QR image or paste the machine link."
                } else {
                    val scanner = BarcodeScanning.getClient(qrOptions)
                    scanner.process(input)
                        .addOnSuccessListener { codes ->
                            if (active.get() && !accepted.get()) {
                                if (codes.none { it.rawValue?.let(::accept) == true }) {
                                    error = if (codes.isEmpty()) "No QR found. Choose a clear image of the whole code." else INVALID_QR
                                }
                            }
                        }
                        .addOnFailureListener {
                            if (active.get()) error = "Couldn't read that QR. Try another image or paste the machine link."
                        }
                        .addOnCompleteListener {
                            scanner.close()
                            if (active.get()) readingImage = false
                        }
                }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Scan QR") },
            expandedHeight = maxOf(64.dp, 48.dp * LocalDensity.current.fontScale),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Connect to your Mac", style = MaterialTheme.typography.headlineSmall)
            Text("In wooloo on your Mac, open Remote Access and start the tunnel. Scan the QR to load your machine and session.")
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (granted && cameraError == null) {
                    CameraQrPreview(
                        attempt = cameraAttempt,
                        onCodes = { codes ->
                            if (!readingImage && !selectingImage && !paste &&
                                lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                !accepted.get() && codes.isNotEmpty()) {
                                if (codes.none { accept(it) }) error = INVALID_QR
                            }
                        },
                        onError = { cameraError = "Camera unavailable. Retry, choose a QR image or paste the machine link." },
                    )
                } else {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(cameraError ?: "Allow camera access to scan the QR on your Mac.")
                        Button(onClick = {
                            if (granted) { cameraError = null; cameraAttempt++ }
                            else { requested = true; permission.launch(Manifest.permission.CAMERA) }
                        }) { Text(if (granted) "Retry camera" else "Allow camera") }
                        if (!granted && requested) TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }) { Text("App settings") }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (readingImage) {
                CircularProgressIndicator()
                Text("Reading QR image…")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !readingImage, onClick = {
                    selectingImage = true
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Choose QR image") }
                TextButton(enabled = !readingImage, onClick = { paste = true; pastedError = false }) { Text("Paste link") }
            }
            Text("You'll review the connection and choose an SSH key or password before connecting.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (paste) AlertDialog(
        onDismissRequest = { paste = false },
        title = { Text("Wooloo machine link") },
        text = {
            OutlinedTextField(
                value = pastedLink,
                onValueChange = { pastedLink = it; pastedError = false },
                label = { Text("Link from Remote Access") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = pastedError,
                supportingText = { if (pastedError) Text("Paste the machine link copied from wooloo on your Mac.") },
            )
        },
        confirmButton = { TextButton(onClick = { if (accept(pastedLink)) paste = false else pastedError = true }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { paste = false }) { Text("Cancel") } },
    )
}

@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
private fun CameraQrPreview(attempt: Int, onCodes: (List<String>) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val latestCodes by rememberUpdatedState(onCodes)
    val latestError by rememberUpdatedState(onError)
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    DisposableEffect(lifecycle, attempt) {
        val active = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(qrOptions)
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        // Mac links include several host key fingerprints; give dense QR codes enough pixels.
        val resolution = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
        ).build()
        val analysis = ImageAnalysis.Builder().setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        var provider: ProcessCameraProvider? = null
        analysis.setAnalyzer(executor) { proxy ->
            val media = proxy.image
            if (!active.get() || media == null) proxy.close()
            else {
                val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner.process(input)
                    .addOnSuccessListener { if (active.get()) latestCodes(it.mapNotNull { code -> code.rawValue }) }
                    .addOnFailureListener { if (active.get()) latestError() }
                    .addOnCompleteListener { proxy.close() }
            }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (active.get()) {
                runCatching {
                    provider = future.get()
                    val camera = if (provider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
                        else CameraSelector.DEFAULT_FRONT_CAMERA
                    provider!!.bindToLifecycle(lifecycle, camera, preview, analysis)
                }.onFailure { latestError() }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            active.set(false)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            executor.shutdown()
            scanner.close()
        }
    }
}
