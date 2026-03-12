package com.example.clearscanocr.presentation

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.clearscanocr.data.OcrResult
import com.example.clearscanocr.domain.OcrUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
     */
    fun onBitmapCaptured(
        bitmap: Bitmap,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (!isProcessing.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }

        viewModelScope.launch {
            try {
                val result = ocrUseCase.execute(
                    bitmap, rotationDegrees, viewWidth, viewHeight
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
                bitmap.recycle()
                isProcessing.set(false)
            }
        }
    }

    fun onScanAgain() {
        _scanState.value = ScanState.Idle
    }

    fun markFrameCaptured() {
        _captureNextFrame.set(false)
    }
}
