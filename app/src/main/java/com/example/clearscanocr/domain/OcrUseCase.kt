package com.example.clearscanocr.domain

import android.graphics.Bitmap
import com.example.clearscanocr.data.OcrProcessor
import com.example.clearscanocr.data.OcrResult

/**
 * Use-case that delegates OCR processing to [OcrProcessor].
 *
 * Sits between the ViewModel and the data layer, enforcing
 * clean architecture separation.
 */
class OcrUseCase(
    private val ocrProcessor: OcrProcessor = OcrProcessor()
) {

    /**
     * Execute text recognition on a raw [bitmap] with full
     * preprocessing pipeline (rotate, crop, enhance, OCR, filter, clean).
     *
     * @param bitmap          The raw camera frame.
     * @param rotationDegrees Image rotation from the camera sensor.
     * @param viewWidth       PreviewView width in pixels.
     * @param viewHeight      PreviewView height in pixels.
     * @return [OcrResult] with cleaned text and bounding boxes.
     */
    suspend fun execute(
        bitmap: Bitmap,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int
    ): OcrResult {
        return ocrProcessor.processBitmap(bitmap, rotationDegrees, viewWidth, viewHeight)
    }
}
