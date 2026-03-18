package com.example.clearscanocr.data

import android.graphics.Rect

/**
 * Vertical region a text block belongs to.
 */
enum class RegionLabel { TOP, MIDDLE, BOTTOM }

/**
 * Structured values extracted from a scanned document.
 */
data class StructuredData(
    val date: String?         = null,
    val time: String?         = null,
    val temperatures: List<String> = emptyList(),
    val peakTemp: String?     = null,
    val counts: List<String>  = emptyList()
)

/**
 * Result of an OCR scan.
 */
data class OcrResult(
    val text: String,
    val textBlocks: List<DetectedTextBlock>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val structuredData: StructuredData? = null
)

/**
 * A single detected text block from ML Kit that passed
 * confidence filtering (>= 0.7).
 */
data class DetectedTextBlock(
    val text: String,
    val boundingBox: Rect,
    val region: RegionLabel = RegionLabel.MIDDLE
)
