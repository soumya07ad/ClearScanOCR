package com.example.clearscanocr.presentation

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.clearscanocr.data.OcrResult
import com.example.clearscanocr.domain.EdgeDetectionResult
import com.example.clearscanocr.domain.OcrUseCase
import com.example.clearscanocr.domain.OpenCvEdgeDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.opencv.core.Point
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "OcrViewModel"

sealed interface OcrUiState {
    data object Idle : OcrUiState
    data object CameraPreview : OcrUiState
}

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Result(val ocrResult: OcrResult) : ScanState
}

enum class AutoCaptureStatus {
    Idle, Searching, Stable, Capturing, Cooldown
}

class OcrViewModel : ViewModel() {

    private val ocrUseCase = OcrUseCase()

    private val _uiState = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val uiState: StateFlow<OcrUiState> = _uiState.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _captureNextFrame = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)

    val shouldCaptureFrame: Boolean
        get() = _captureNextFrame.get()

    // ── Edge-detection state ──────────────────────────────────────────
    private val _detectedCorners = MutableStateFlow<Array<Point>?>(null)
    val detectedCorners: StateFlow<Array<Point>?> = _detectedCorners.asStateFlow()

    private val _analyzerDim = MutableStateFlow(android.util.Size(720, 1280))
    val analyzerDim: StateFlow<android.util.Size> = _analyzerDim.asStateFlow()

    private val _guidanceMessage = MutableStateFlow("📄 Align document")
    val guidanceMessage: StateFlow<String> = _guidanceMessage.asStateFlow()

    private val _captureConfidence = MutableStateFlow(0f)
    val captureConfidence: StateFlow<Float> = _captureConfidence.asStateFlow()

    // ── Auto-capture state ────────────────────────────────────────────
    private val _autoCaptureEnabled = MutableStateFlow(true)
    val autoCaptureEnabled: StateFlow<Boolean> = _autoCaptureEnabled.asStateFlow()

    private val _autoCaptureStatus = MutableStateFlow(AutoCaptureStatus.Idle)
    val autoCaptureStatus: StateFlow<AutoCaptureStatus> = _autoCaptureStatus.asStateFlow()

    private var stableFrameCount = 0
    private val stableFrameThreshold = 4
    private var isAutoCaptureCooldown = false
    private val messageDebounceMs = 400L
    private var lastMessageUpdateTime = 0L
    private var lastMessage = ""

    // ── Navigation ────────────────────────────────────────────────────
    fun onStartOcr() {
        _scanState.value = ScanState.Idle
        _uiState.value = OcrUiState.CameraPreview
        _autoCaptureStatus.value = if (_autoCaptureEnabled.value) AutoCaptureStatus.Searching else AutoCaptureStatus.Idle
    }

    fun onBackToHome() {
        _scanState.value = ScanState.Idle
        _uiState.value = OcrUiState.Idle
        _detectedCorners.value = null
        OpenCvEdgeDetector.reset()
    }

    // ── Scanning ──────────────────────────────────────────────────────
    fun onScanRequested() {
        if (isProcessing.get()) return
        _scanState.value = ScanState.Scanning
        _captureNextFrame.set(true)
    }

    fun onBitmapCaptured(
        bitmap: Bitmap,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
        analyzerWidth: Int,
        analyzerHeight: Int
    ) {
        if (!isProcessing.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }

        viewModelScope.launch {
            try {
                val corners = OpenCvEdgeDetector.getLastCorners()
                val rotatedBitmap = OpenCvEdgeDetector.imageProxyToBitmap(bitmap, rotationDegrees)

                val ocrBitmap: Bitmap = if (corners != null && analyzerWidth > 0) {
                    OpenCvEdgeDetector.warpAndEnhance(rotatedBitmap, analyzerWidth, analyzerHeight, corners) ?: rotatedBitmap
                } else {
                    rotatedBitmap
                }

                val result = ocrUseCase.execute(ocrBitmap)
                _scanState.value = if (result.isValid) ScanState.Result(result) 
                                   else ScanState.Result(OcrResult(isValid = false, errorMessage = result.errorMessage ?: "Failed to read data."))
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
                _scanState.value = ScanState.Result(OcrResult(isValid = false, errorMessage = "Error connecting to AI service."))
            } finally {
                bitmap.recycle()
                isProcessing.set(false)
            }
        }
    }

    fun onScanAgain() {
        _scanState.value = ScanState.Idle
        _autoCaptureStatus.value = if (_autoCaptureEnabled.value) AutoCaptureStatus.Searching else AutoCaptureStatus.Idle
    }

    fun toggleAutoCapture(enabled: Boolean) {
        _autoCaptureEnabled.value = enabled
        _autoCaptureStatus.value = if (enabled) AutoCaptureStatus.Searching else AutoCaptureStatus.Idle
    }

    fun markFrameCaptured() {
        _captureNextFrame.set(false)
    }

    fun onEdgeFrame(result: EdgeDetectionResult, width: Int, height: Int) {
        _detectedCorners.value = result.corners
        _analyzerDim.value = android.util.Size(width, height)
        _captureConfidence.value = result.confidence

        var displayMsg = result.message
        if (_autoCaptureStatus.value == AutoCaptureStatus.Capturing) displayMsg = "📸 Capturing..."

        val now = System.currentTimeMillis()
        if (displayMsg != lastMessage && now - lastMessageUpdateTime > messageDebounceMs) {
            _guidanceMessage.value = displayMsg
            lastMessage = displayMsg
            lastMessageUpdateTime = now
        }

        if (!_autoCaptureEnabled.value || _scanState.value != ScanState.Idle || isAutoCaptureCooldown) {
            stableFrameCount = 0
            return
        }

        if (result.isStable && result.areaValid) {
            stableFrameCount++
            _autoCaptureStatus.value = AutoCaptureStatus.Stable
            if (stableFrameCount >= stableFrameThreshold) {
                triggerAutoCapture()
            }
        } else {
            stableFrameCount = 0
            _autoCaptureStatus.value = AutoCaptureStatus.Searching
        }
    }

    private fun triggerAutoCapture() {
        _autoCaptureStatus.value = AutoCaptureStatus.Capturing
        onScanRequested()
        isAutoCaptureCooldown = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            isAutoCaptureCooldown = false
            if (_scanState.value == ScanState.Idle) _autoCaptureStatus.value = AutoCaptureStatus.Searching
        }
    }
}
