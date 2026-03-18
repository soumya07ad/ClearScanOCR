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
 * Holds a singleton recognizer instance and a [RoiOcrProcessor] for
 * structured data extraction.
 */
class OcrProcessor {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val roiProcessor = RoiOcrProcessor()

    companion object {
        private const val MIN_CONFIDENCE = 0.7f
    }

    /**
     * Run text recognition on a [Bitmap].
     *
     * When [isAlreadyWarped] is **true** (perspective-corrected + enhanced bitmap),
     * the crop-to-guide and preprocessing steps are skipped; the image is fed
     * directly into [RoiOcrProcessor] for structured extraction.
     *
     * When **false**, the legacy pipeline (crop → enhance → full OCR) is used.
     *
     * @param bitmap          Camera frame or pre-processed bitmap.
     * @param rotationDegrees Rotation from the camera sensor (ignored when already warped).
     * @param viewWidth       PreviewView width in pixels.
     * @param viewHeight      PreviewView height in pixels.
     * @param isAlreadyWarped Whether [bitmap] has already been warped and enhanced.
     */
    suspend fun processBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
        isAlreadyWarped: Boolean = false
    ): OcrResult {

        return if (isAlreadyWarped) {
            // ── Fast path: bitmap is ready — extract ROI + structured data ──
            processWithRoi(bitmap)
        } else {
            // ── Legacy path: crop to guide → enhance → full OCR ─────────────
            val cropped = ImagePreprocessor.cropToGuide(
                source = bitmap,
                rotationDegrees = rotationDegrees,
                viewWidth = viewWidth,
                viewHeight = viewHeight
            )
            val enhanced = ImagePreprocessor.preprocess(cropped)
            cropped.recycle()
            return try {
                val inputImage = InputImage.fromBitmap(enhanced, 0)
                processImageFull(inputImage, enhanced.width, enhanced.height)
            } finally {
                enhanced.recycle()
            }
        }
    }

    /**
     * Fast path: run ROI extraction on an already-prepared [bitmap].
     */
    private suspend fun processWithRoi(bitmap: Bitmap): OcrResult {
        val (blocks, structured) = roiProcessor.extractStructured(bitmap, isAlreadyWarped = true)
        val rawText = blocks.joinToString("\n") { it.text }
        val cleanedText = TextCleaner.clean(rawText)
        return OcrResult(
            text = cleanedText,
            textBlocks = blocks,
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            structuredData = structured
        )
    }

    /**
     * Legacy full OCR with confidence filtering and bounding boxes.
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
