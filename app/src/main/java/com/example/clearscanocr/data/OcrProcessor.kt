package com.example.clearscanocr.data

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Data-layer wrapper around ML Kit [TextRecognizer].
 *
 * Holds a singleton recognizer instance to avoid repeated initialization.
 * Supports confidence filtering and bounding-box extraction.
 */
class OcrProcessor {

    /** Singleton ML Kit recognizer — reused across all frames. */
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Minimum confidence threshold for including a text line. */
    companion object {
        private const val MIN_CONFIDENCE = 0.7f
    }

    /**
     * Run text recognition on a [Bitmap] with full preprocessing pipeline:
     * rotate → crop to visible guide → grayscale/contrast → ML Kit →
     * confidence filter → text cleaning.
     *
     * @param bitmap          Raw camera frame (NOT recycled by this call).
     * @param rotationDegrees Rotation from the camera sensor.
     * @param viewWidth       PreviewView width in pixels.
     * @param viewHeight      PreviewView height in pixels.
     * @return [OcrResult] with cleaned text and bounding boxes.
     */
    suspend fun processBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int
    ): OcrResult {
        // 1. Crop to the exact guide-overlay region (rotation-aware)
        val cropped = ImagePreprocessor.cropToGuide(
            source = bitmap,
            rotationDegrees = rotationDegrees,
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )

        // 2. Enhance (grayscale + contrast)
        val enhanced = ImagePreprocessor.preprocess(cropped)
        cropped.recycle()

        return try {
            // rotationDegrees = 0 because we already rotated in cropToGuide
            val inputImage = InputImage.fromBitmap(enhanced, 0)
            processImageFull(inputImage, enhanced.width, enhanced.height)
        } finally {
            enhanced.recycle()
        }
    }

    /**
     * Full OCR processing with confidence filtering and bounding boxes.
     */
    private suspend fun processImageFull(
        inputImage: InputImage,
        srcWidth: Int,
        srcHeight: Int
    ): OcrResult = suspendCancellableCoroutine { cont ->
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val blocks = mutableListOf<DetectedTextBlock>()
                val filteredLines = mutableListOf<String>()

                for (block in visionText.textBlocks) {
                    val blockLines = mutableListOf<String>()

                    for (line in block.lines) {
                        val confidence = line.confidence ?: 1.0f
                        if (confidence >= MIN_CONFIDENCE) {
                            blockLines.add(line.text)
                        }
                    }

                    if (blockLines.isNotEmpty()) {
                        val box = block.boundingBox
                            ?: Rect(0, 0, srcWidth, srcHeight)

                        blocks.add(
                            DetectedTextBlock(
                                text = blockLines.joinToString("\n"),
                                boundingBox = box
                            )
                        )
                        filteredLines.addAll(blockLines)
                    }
                }

                val rawText = filteredLines.joinToString("\n")
                val cleanedText = TextCleaner.clean(rawText)

                cont.resume(
                    OcrResult(
                        text = cleanedText,
                        textBlocks = blocks,
                        sourceWidth = srcWidth,
                        sourceHeight = srcHeight
                    )
                )
            }
            .addOnFailureListener { exception ->
                cont.resumeWithException(exception)
            }
    }
}
