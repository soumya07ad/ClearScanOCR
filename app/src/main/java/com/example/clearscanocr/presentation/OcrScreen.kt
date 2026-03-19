package com.example.clearscanocr.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.clearscanocr.data.OcrResult
import com.example.clearscanocr.domain.EdgeDetectionResult
import com.example.clearscanocr.domain.OpenCvEdgeDetector
import java.util.concurrent.Executors

private const val TAG = "CameraPreview"

// ── Home Screen ─────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    onStartOcr: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ClearScan OCR",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Extract text from images using your camera",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onStartOcr,
                modifier = Modifier.size(width = 200.dp, height = 56.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = "Start OCR",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ── Clipboard & Share helpers ───────────────────────────────────────

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("OCR Result", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share OCR Text"))
}

// ── Camera Preview Screen ───────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraPreviewScreen(
    viewModel: OcrViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanState by viewModel.scanState.collectAsState()

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // ── CameraX lifecycle binding ───────────────────────────────────
    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        /** Timestamp of the last processed edge-detection frame (ms). */
        var lastEdgeFrameTime = 0L
        /** Minimum interval between edge-detection frames (~15 FPS). */
        val edgeFrameIntervalMs = 66L

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            // ── Path A: OCR capture (user tapped "Scan Text") ───
                            if (viewModel.shouldCaptureFrame) {
                                viewModel.markFrameCaptured()
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                val pvWidth = previewView.width
                                val pvHeight = previewView.height

                                // Compute post-rotation analyzer dimensions for corner scaling
                                val analyzerW: Int
                                val analyzerH: Int
                                if (rotation == 90 || rotation == 270) {
                                    analyzerW = imageProxy.height
                                    analyzerH = imageProxy.width
                                } else {
                                    analyzerW = imageProxy.width
                                    analyzerH = imageProxy.height
                                }

                                val bitmap: Bitmap? = try {
                                    imageProxy.toBitmap()
                                } catch (_: Exception) {
                                    null
                                }
                                // Close proxy immediately after extracting bitmap
                                imageProxy.close()

                                if (bitmap != null && pvWidth > 0 && pvHeight > 0) {
                                    viewModel.onBitmapCaptured(
                                        bitmap, rotation,
                                        pvWidth, pvHeight,
                                        analyzerW, analyzerH
                                    )
                                } else {
                                    bitmap?.recycle()
                                }
                                return@setAnalyzer
                            }

                            // ── Path B: Continuous edge detection ───────────────
                            val now = System.currentTimeMillis()
                            if (now - lastEdgeFrameTime < edgeFrameIntervalMs) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            lastEdgeFrameTime = now

                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val rawBitmap: Bitmap? = try {
                                imageProxy.toBitmap()
                            } catch (_: Exception) {
                                null
                            }
                            imageProxy.close()

                            if (rawBitmap != null) {
                                // Rotate to match screen orientation
                                val rotated = OpenCvEdgeDetector
                                    .imageProxyToBitmap(rawBitmap, rotation)
                                // Run edge detection pipeline
                                val edgeResult = OpenCvEdgeDetector.processFrame(rotated)
                                val rw = rotated.width
                                val rh = rotated.height
                                rotated.recycle()
                                // Post result to ViewModel
                                viewModel.onEdgeFrame(edgeResult, rw, rh)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Analyzer error", e)
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalysis
                )
                Log.d(TAG, "Camera bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
            analysisExecutor.shutdown()
            // Clear edge overlay and reset detector state
            viewModel.onEdgeFrame(EdgeDetectionResult(null, "", 0f), 720, 1280)
            OpenCvEdgeDetector.reset()
            Log.d(TAG, "Camera unbound & executor shut down")
        }
    }

    // ── UI ────────────────────────────────────────────────────────────
    val detectedCorners by viewModel.detectedCorners.collectAsState()
    val guidanceMsg by viewModel.guidanceMessage.collectAsState()
    val autoCaptureEnabled by viewModel.autoCaptureEnabled.collectAsState()
    val autoCaptureStatus by viewModel.autoCaptureStatus.collectAsState()

    // Flash effect animation
    val flashAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(autoCaptureStatus) {
        if (autoCaptureStatus == AutoCaptureStatus.Capturing) {
            flashAlpha.snapTo(0.8f)
            flashAlpha.animateTo(0f, androidx.compose.animation.core.tween(400))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // Layer 1: Camera feed
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: Real-time edge detection overlay (Canvas based)
        detectedCorners?.let { corners ->
            val dim by viewModel.analyzerDim.collectAsState()
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pts = corners.map { p ->
                    Offset(
                        x = (p.x.toFloat() / dim.width.toFloat()) * size.width,
                        y = (p.y.toFloat() / dim.height.toFloat()) * size.height
                    )
                }
                
                if (pts.size == 4) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        lineTo(pts[1].x, pts[1].y)
                        lineTo(pts[2].x, pts[2].y)
                        lineTo(pts[3].x, pts[3].y)
                        close()
                    }
                    drawPath(
                        path = path,
                        color = Color.Green,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                    )
                }
            }
        }

        // Layer 3: Alignment guide
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.85f)
                .aspectRatio(4f / 3f)
                .border(
                    BorderStroke(2.dp, Color.White.copy(alpha = 0.6f)),
                    RoundedCornerShape(12.dp)
                )
        )

        // Layer 4: Dynamic guidance message (only when idle)
        if (scanState is ScanState.Idle && guidanceMsg.isNotBlank()) {
            val isCapturing = autoCaptureStatus == AutoCaptureStatus.Capturing
            Text(
                text = guidanceMsg,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .background(
                        if (isCapturing) Color(0xFF2196F3).copy(alpha = 0.8f) 
                        else Color.Black.copy(alpha = 0.55f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        // Layer 4.5: Flash Effect
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = flashAlpha.value))
        )

        // Layer 5: Scanning indicator
        if (scanState is ScanState.Scanning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Scanning…",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Layer 6: Result overlay with bounding boxes + action buttons
        if (scanState is ScanState.Result) {
            val result = (scanState as ScanState.Result).ocrResult

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scan Result",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (result.isValid) {
                        StructuredDataCard(result)
                    } else {
                        // Display error message
                        Text(
                            text = result.errorMessage ?: "Unknown error occurred.",
                            color = Color(0xFFFF5252),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    val formattedText = """
                        Date: ${result.date}
                        Time: ${result.time}
                        Low Set: ${result.lowSetTemp} | Target: ${result.targetTemp} | High Set: ${result.highSetTemp} | Peak: ${result.peakTemp}
                        Counts -> Low: ${result.lowTempCount} | OK: ${result.okTempCount} | High: ${result.highTempCount} | Total: ${result.totalTempCount}
                    """.trimIndent()

                    // Action buttons — using FlowRow for wrapping
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = onBack,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                        ) {
                            Text("Back")
                        }
                        OutlinedButton(
                            onClick = { copyToClipboard(context, formattedText) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                        ) {
                            Text("📋 Copy")
                        }
                        OutlinedButton(
                            onClick = { shareText(context, formattedText) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                        ) {
                            Text("📤 Share")
                        }
                        Button(
                            onClick = { viewModel.onScanAgain() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Scan Again")
                        }
                    }
                }
            }
        }

        // Layer 7: Bottom controls (only when idle)
        if (scanState is ScanState.Idle) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onBack,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    )
                ) {
                    Text("Back")
                }

                // Auto Capture Toggle
                IconButton(
                    onClick = { viewModel.toggleAutoCapture(!autoCaptureEnabled) },
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            if (autoCaptureEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    Icon(
                        imageVector = if (autoCaptureEnabled) Icons.Default.AutoMode else Icons.Default.TouchApp,
                        contentDescription = "Toggle Auto Capture",
                        tint = if (autoCaptureEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }

                Button(
                    onClick = { viewModel.onScanRequested() },
                    modifier = Modifier.height(56.dp),
                    enabled = autoCaptureStatus != AutoCaptureStatus.Capturing,
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "📷  Scan Text",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
/**
 * Card displaying structured OCR data from Gemini API.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StructuredDataCard(result: com.example.clearscanocr.data.OcrResult) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Date & Time
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (result.date.isNotBlank()) {
                    InfoRow(Icons.Default.Event, result.date, Color(0xFF81C784))
                }
                if (result.time.isNotBlank()) {
                    InfoRow(Icons.Default.AccessTime, result.time, Color(0xFF64B5F6))
                }
            }

            if (result.date.isNotBlank() || result.time.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.Divider(color = Color.White.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(12.dp))
            }

            Text("Temperatures", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            
            // Grid for temperatures
            TempRow("Low Set", result.lowSetTemp, "Target", result.targetTemp)
            Spacer(modifier = Modifier.height(8.dp))
            TempRow("High Set", result.highSetTemp, "Peak", result.peakTemp)

            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.material3.Divider(color = Color.White.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(12.dp))

            Text("Counts", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            
            // Grid for counts
            TempRow("Low", result.lowTempCount, "OK", result.okTempCount)
            Spacer(modifier = Modifier.height(8.dp))
            TempRow("High", result.highTempCount, "Total", result.totalTempCount)
        }
    }
}

@Composable
private fun TempRow(label1: String, val1: String, label2: String, val2: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label1, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            InfoRow(Icons.Default.DeviceThermostat, val1.ifBlank { "N/A" }, Color(0xFFFFB74D))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label2, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            InfoRow(Icons.Default.DeviceThermostat, val2.ifBlank { "N/A" }, Color(0xFFBA68C8))
        }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}
