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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "OcrViewModel"

/**
 * Sealed interface representing the top-level navigation state.
 */
sealed interface OcrUiState {
    data object Idle : OcrUiState
    data object CameraPreview : OcrUiState
}

/**
 * Sealed interface representing the scan workflow state
 * within the camera preview screen.
 */
sealed interface ScanState {
    /** Camera is live — waiting for user to tap "Scan Text". */
    data object Idle : ScanState

    /** A frame has been captured and OCR is in progress. */
    data object Scanning : ScanState

    /** OCR completed — display the result with bounding boxes. */
    data class Result(val ocrResult: OcrResult) : ScanState
}

/** Represents the status of the auto-capture engine. */
enum class AutoCaptureStatus {
    Idle, Searching, Stable, Capturing, Cooldown
}

/**
 * ViewModel for the OCR feature.
 *
 * Manages navigation via [uiState] and the manual-scan workflow
 * via [scanState]. OCR runs only when the user taps "Scan Text".
 */
class OcrViewModel : ViewModel() {

    private val ocrUseCase = OcrUseCase()

    // ── Navigation state ──────────────────────────────────────────────
    private val _uiState = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val uiState: StateFlow<OcrUiState> = _uiState.asStateFlow()

    // ── Scan workflow state ───────────────────────────────────────────
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _captureNextFrame = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)

    val shouldCaptureFrame: Boolean
        get() = _captureNextFrame.get()

    // ── Edge-detection overlay ─────────────────────────────────────────
    private val _edgeOverlayBitmap = MutableStateFlow<Bitmap?>(null)
    val edgeOverlayBitmap: StateFlow<Bitmap?> = _edgeOverlayBitmap.asStateFlow()

    /** Holds previous bitmap for safe recycling from any thread. */
    private val previousEdgeBitmap = AtomicReference<Bitmap?>(null)

    // ── User guidance ─────────────────────────────────────────────────
    private val _guidanceMessage = MutableStateFlow("📄 Align document")
    val guidanceMessage: StateFlow<String> = _guidanceMessage.asStateFlow()

    /** Debounce interval for guidance message updates (ms). */
    private val messageDebounceMs = 400L
    private var lastMessageUpdateTime = 0L
    private var lastMessage = "📄 Align document"

    // ── Auto-capture state ────────────────────────────────────────────
    private val _autoCaptureEnabled = MutableStateFlow(true)
    val autoCaptureEnabled: StateFlow<Boolean> = _autoCaptureEnabled.asStateFlow()

    private val _autoCaptureStatus = MutableStateFlow(AutoCaptureStatus.Idle)
    val autoCaptureStatus: StateFlow<AutoCaptureStatus> = _autoCaptureStatus.asStateFlow()

    private var stableFrameCount = 0
    private val stableFrameThreshold = 6 // ~400ms at 15 FPS
    private var isAutoCaptureCooldown = false
    private val cooldownDurationMs = 3000L

    // ── Navigation actions ────────────────────────────────────────────

    fun onStartOcr() {
        _scanState.value = ScanState.Idle
        _uiState.value = OcrUiState.CameraPreview
    }

    fun onBackToHome() {
        _scanState.value = ScanState.Idle
        _uiState.value = OcrUiState.Idle
    }

    // ── Scan actions ──────────────────────────────────────────────────

    fun onScanRequested() {
        if (isProcessing.get()) return
        _scanState.value = ScanState.Scanning
        _captureNextFrame.set(true)
    }

    /**
     * Called by the analyzer after capturing a frame.
     *
     * @param bitmap          Raw camera bitmap (caller must NOT recycle).
     * @param rotationDegrees Camera sensor rotation.
     * @param viewWidth       PreviewView width in pixels.
     * @param viewHeight      PreviewView height in pixels.
     * @param analyzerWidth   Width of the (rotated) analyzer frame used for edge detection.
     * @param analyzerHeight  Height of the (rotated) analyzer frame.
     */
    fun onBitmapCaptured(
        bitmap: Bitmap,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
        analyzerWidth: Int = 0,
        analyzerHeight: Int = 0
    ) {
        if (!isProcessing.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }

        viewModelScope.launch {
            try {
                // ── Perspective warp + enhancement (if corners available) ───────────
                val corners = if (analyzerWidth > 0 && analyzerHeight > 0)
                    OpenCvEdgeDetector.getLastCorners() else null

                // Rotate the raw bitmap first so it matches the orientation used for detection
                val rotatedBitmap = OpenCvEdgeDetector.imageProxyToBitmap(bitmap, rotationDegrees)

                val ocrBitmap: Bitmap = if (corners != null) {
                    val warped = OpenCvEdgeDetector.warpAndEnhance(
                        rotatedBitmap, analyzerWidth, analyzerHeight, corners
                    )
                    if (warped != null) {
                        Log.d(TAG, "Perspective warp applied successfully")
                        // rotate was applied above; recycle original if it's a new bitmap
                        if (rotatedBitmap !== bitmap) rotatedBitmap.recycle()
                        warped
                    } else {
                        Log.w(TAG, "Warp failed, falling back to rotated bitmap")
                        rotatedBitmap
                    }
                } else {
                    Log.d(TAG, "No corners available, using rotated bitmap for OCR")
                    rotatedBitmap
                }

                // ── OCR (viewWidth/viewHeight are not needed for pixel coords anymore, ──────
                //    but kept for backward compat with ocrUseCase signature) ──────────
                val result = ocrUseCase.execute(
                    ocrBitmap, 0, viewWidth, viewHeight,
                    isAlreadyWarped = (corners != null)
                )
                _scanState.value = if (result.text.isNotBlank()) {
                    Log.d(TAG, "Scan result: ${result.text}")
                    ScanState.Result(result)
                } else {
                    ScanState.Result(
                        OcrResult(
                            text = "No text detected. Try again.",
                            textBlocks = emptyList(),
                            sourceWidth = 0,
                            sourceHeight = 0
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                _scanState.value = ScanState.Result(
                    OcrResult(
                        text = "Scan failed — please try again.",
                        textBlocks = emptyList(),
                        sourceWidth = 0,
                        sourceHeight = 0
                    )
                )
            } finally {
                // bitmap was rotated in-place; ocrBitmap will be recycled separately
                bitmap.recycle()
                isProcessing.set(false)
            }
        }
    }

    fun onScanAgain() {
        _scanState.value = ScanState.Idle
        _autoCaptureStatus.value = AutoCaptureStatus.Searching
    }

    fun toggleAutoCapture(enabled: Boolean) {
        _autoCaptureEnabled.value = enabled
        if (!enabled) {
            _autoCaptureStatus.value = AutoCaptureStatus.Idle
            stableFrameCount = 0
        } else if (_scanState.value == ScanState.Idle) {
            _autoCaptureStatus.value = AutoCaptureStatus.Searching
        }
    }

    fun markFrameCaptured() {
        _captureNextFrame.set(false)
    }

    // ── Edge overlay ──────────────────────────────────────────────────

    /**
     * Called by the analyzer with the latest edge-detection result.
     * Safely recycles the previous bitmap to avoid memory leaks.
     * Applies debounce logic to the guidance message.
     */
    fun onEdgeFrame(result: EdgeDetectionResult) {
        val old = previousEdgeBitmap.getAndSet(result.bitmap)
        _edgeOverlayBitmap.value = result.bitmap
        old?.recycle()

        // Debounce guidance message updates
        val now = System.currentTimeMillis()
        var displayMessage = result.message

        // Override message if capturing
        if (_autoCaptureStatus.value == AutoCaptureStatus.Capturing) {
            displayMessage = "📸 Capturing..."
        }

        if (displayMessage != lastMessage &&
            now - lastMessageUpdateTime >= messageDebounceMs
        ) {
            lastMessage = displayMessage
            lastMessageUpdateTime = now
            _guidanceMessage.value = displayMessage
        }

        // ── Auto-capture Logic ────────────────────────────────────────
        if (!_autoCaptureEnabled.value || 
            _scanState.value != ScanState.Idle || 
            isAutoCaptureCooldown
        ) {
            stableFrameCount = 0
            if (_autoCaptureEnabled.value && !isAutoCaptureCooldown) {
                _autoCaptureStatus.value = AutoCaptureStatus.Searching
            }
            return
        }

        // Readiness condition per requirements
        val isReady = result.confidence > 0.8f && 
                     result.isStable && 
                     !result.isBlurry && 
                     result.isWellLit && 
                     result.areaValid

        if (isReady) {
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
        if (_scanState.value != ScanState.Idle) return
        
        _autoCaptureStatus.value = AutoCaptureStatus.Capturing
        onScanRequested()
        
        // Start cooldown
        isAutoCaptureCooldown = true
        stableFrameCount = 0
        viewModelScope.launch {
            kotlinx.coroutines.delay(cooldownDurationMs)
            isAutoCaptureCooldown = false
            if (_autoCaptureEnabled.value && _scanState.value == ScanState.Idle) {
                _autoCaptureStatus.value = AutoCaptureStatus.Searching
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        previousEdgeBitmap.getAndSet(null)?.recycle()
        _edgeOverlayBitmap.value = null
        _guidanceMessage.value = ""
    }
}
