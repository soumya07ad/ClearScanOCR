package com.example.clearscanocr.data

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ROI-based OCR processor that:
 * 1. Runs full-image ML Kit recognition.
 * 2. Sorts blocks by vertical position and assigns region labels.
 * 3. Applies semantic keyword matching and regex extraction.
 * 4. Optionally re-runs OCR on critical ROI crops if values are missing.
 */
class RoiOcrProcessor {

    companion object {
        private const val TAG = "RoiOcrProcessor"

        /** Minimum block height to avoid tiny noise. */
        private const val MIN_BLOCK_HEIGHT_PX = 10

        /** Confidence threshold for individual OCR lines. */
        private const val MIN_CONFIDENCE = 0.7f

        /** Region splits (relative to image height). */
        private const val TOP_END = 0.25f
        private const val BOTTOM_START = 0.75f

        // ── Regex patterns ────────────────────────────────────────────
        /** Date: dd/mm/yyyy or d/m/yy style. */
        private val DATE_REGEX = Regex("""\b\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4}\b""")

        /** Time: hh:mm or hh:mm:ss (12/24-hour). */
        private val TIME_REGEX = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\s*([AaPp][Mm])?\b""")

        /** Numeric value: 2-4 digits, optional decimal (temperatures, counts). */
        private val NUMBER_REGEX = Regex("""\b\d{2,4}(\.\d+)?\b""")

        /** Degree/temperature suffix hint. */
        private val TEMP_HINT_REGEX = Regex("""(\d{2,4}(\.\d+)?)\s*°?[CF]\b""", RegexOption.IGNORE_CASE)

        // ── Semantic keywords ─────────────────────────────────────────
        private val PEAK_KEYWORDS = setOf("PEAK", "MAX", "HIGH", "HIGHEST")
        private val TEMP_KEYWORDS = setOf("TEMP", "TEMPERATURE", "°C", "°F", "CELSIUS", "FAHRENHEIT")
        private val COUNT_KEYWORDS = setOf("COUNT", "CNT", "NUM", "TOTAL", "QTY")
    }

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Run ROI-based structured extraction on an already-enhanced [bitmap].
     * - If [isAlreadyWarped] is true the bitmap is used as-is (no further crop/enhance).
     * - Returns a [StructuredData] filled from position + keyword analysis.
     */
    suspend fun extractStructured(
        bitmap: Bitmap,
        isAlreadyWarped: Boolean
    ): Pair<List<DetectedTextBlock>, StructuredData> {

        val visionText = runRecognition(bitmap)
        val srcH = bitmap.height.toFloat()
        val srcW = bitmap.width.toFloat()

        // ── Step 1: Sort all blocks by vertical centre ────────────────
        val sortedBlocks = visionText.textBlocks
            .filter { block ->
                val box = block.boundingBox ?: return@filter false
                val confidenceOk = block.lines.any { (it.confidence ?: 1f) >= MIN_CONFIDENCE }
                val heightOk = box.height() >= MIN_BLOCK_HEIGHT_PX
                confidenceOk && heightOk
            }
            .sortedBy { it.boundingBox!!.centerY() }

        // ── Step 2: Assign regions and build DetectedTextBlocks ────────
        val detectedBlocks = sortedBlocks.map { block ->
            val box = block.boundingBox!!
            val relY = box.centerY() / srcH
            val region = when {
                relY < TOP_END -> RegionLabel.TOP
                relY > BOTTOM_START -> RegionLabel.BOTTOM
                else -> RegionLabel.MIDDLE
            }
            // Collect confident lines only
            val lines = block.lines
                .filter { (it.confidence ?: 1f) >= MIN_CONFIDENCE }
                .map { it.text }
            DetectedTextBlock(
                text = lines.joinToString(" "),
                boundingBox = box,
                region = region
            )
        }.filter { it.text.isNotBlank() }

        // ── Step 3: Extract structured data ───────────────────────────
        val structured = extractFromBlocks(detectedBlocks, srcH, srcW, bitmap, isAlreadyWarped)

        Log.d(TAG, "Structured: $structured")
        return detectedBlocks to structured
    }

    // ── Private extraction logic ───────────────────────────────────────

    private suspend fun extractFromBlocks(
        blocks: List<DetectedTextBlock>,
        srcH: Float,
        srcW: Float,
        bitmap: Bitmap,
        isAlreadyWarped: Boolean
    ): StructuredData {

        val topBlocks    = blocks.filter { it.region == RegionLabel.TOP }
        val middleBlocks = blocks.filter { it.region == RegionLabel.MIDDLE }
        val bottomBlocks = blocks.filter { it.region == RegionLabel.BOTTOM }

        // Date & Time from header (top region first, then whole doc)
        val headerText = topBlocks.joinToString(" ") { it.text }
        val date = DATE_REGEX.find(headerText)?.value
            ?: DATE_REGEX.find(blocks.joinToString(" ") { it.text })?.value

        val time = TIME_REGEX.find(headerText)?.value
            ?: TIME_REGEX.find(blocks.joinToString(" ") { it.text })?.value

        // Temperatures from middle (or keyword-flagged blocks anywhere)
        val tempBlocks = (middleBlocks + blocks.filter { block ->
            val upper = block.text.uppercase()
            TEMP_KEYWORDS.any { upper.contains(it) }
        }).distinctBy { it.boundingBox }

        val temperatures = extractTemperatures(tempBlocks)

        // Peak temp: keyword-based first, then bottom region
        var peakTemp = extractPeakFromKeyword(blocks)
        if (peakTemp == null) {
            val bottomText = bottomBlocks.joinToString(" ") { it.text }
            peakTemp = TEMP_HINT_REGEX.find(bottomText)?.groupValues?.get(1)
                ?: NUMBER_REGEX.findAll(bottomText).lastOrNull()?.value
        }

        // Counts: keyword-matched blocks anywhere
        val countBlocks = blocks.filter { block ->
            val upper = block.text.uppercase()
            COUNT_KEYWORDS.any { upper.contains(it) }
        }
        val counts = extractCounts(countBlocks)

        // ── Re-OCR critical ROI if values still missing ────────────────
        val finalDate: String?
        val finalTime: String?

        if ((date == null || time == null) && isAlreadyWarped && topBlocks.isNotEmpty()) {
            Log.d(TAG, "Re-OCR: attempting header crop for missing date/time")
            val headerRegion = growRect(topBlocks.mapNotNull { it.boundingBox }
                .fold(topBlocks[0].boundingBox!!) { acc, r -> unionRect(acc, r) },
                padding = 8, srcW = srcW.toInt(), srcH = srcH.toInt())
            val croppedHeader = Bitmap.createBitmap(
                bitmap,
                headerRegion.left, headerRegion.top,
                headerRegion.width(), headerRegion.height()
            )
            val reText = runRecognition(croppedHeader).text
            croppedHeader.recycle()
            finalDate = date ?: DATE_REGEX.find(reText)?.value
            finalTime = time ?: TIME_REGEX.find(reText)?.value
        } else {
            finalDate = date
            finalTime = time
        }

        return StructuredData(
            date = finalDate,
            time = finalTime,
            temperatures = temperatures,
            peakTemp = peakTemp,
            counts = counts
        )
    }

    private fun extractTemperatures(blocks: List<DetectedTextBlock>): List<String> {
        val result = mutableListOf<String>()
        for (block in blocks) {
            // Prefer explicit temperature notation (e.g. "36.6°C")
            val tempMatches = TEMP_HINT_REGEX.findAll(block.text).map { it.groupValues[1] }.toList()
            if (tempMatches.isNotEmpty()) {
                result.addAll(tempMatches)
            } else {
                // Fall back to any number in a temp-keyword block
                val upper = block.text.uppercase()
                if (TEMP_KEYWORDS.any { upper.contains(it) }) {
                    NUMBER_REGEX.findAll(block.text).forEach { result.add(it.value) }
                }
            }
        }
        return result.distinct()
    }

    private fun extractPeakFromKeyword(blocks: List<DetectedTextBlock>): String? {
        for (block in blocks) {
            val upper = block.text.uppercase()
            if (PEAK_KEYWORDS.any { upper.contains(it) }) {
                return TEMP_HINT_REGEX.find(block.text)?.groupValues?.get(1)
                    ?: NUMBER_REGEX.find(block.text)?.value
            }
        }
        return null
    }

    private fun extractCounts(blocks: List<DetectedTextBlock>): List<String> =
        blocks.flatMap { NUMBER_REGEX.findAll(it.text).map { m -> m.value } }.distinct()

    // ── ML Kit helper ──────────────────────────────────────────────────

    private suspend fun runRecognition(bitmap: Bitmap): Text =
        suspendCancellableCoroutine { cont ->
            val input = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(input)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    // ── Rect helpers ───────────────────────────────────────────────────

    private fun unionRect(a: Rect, b: Rect) = Rect(
        minOf(a.left, b.left), minOf(a.top, b.top),
        maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)
    )

    private fun growRect(r: Rect, padding: Int, srcW: Int, srcH: Int) = Rect(
        (r.left - padding).coerceAtLeast(0),
        (r.top  - padding).coerceAtLeast(0),
        (r.right + padding).coerceAtMost(srcW),
        (r.bottom + padding).coerceAtMost(srcH)
    )
}
