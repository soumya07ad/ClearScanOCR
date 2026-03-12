package com.example.clearscanocr.data

import android.graphics.Rect

/**
 * Result of an OCR scan containing the cleaned text and
 * bounding-box metadata for each detected text block.
 *
 * @property text The cleaned, formatted recognized text.
 * @property textBlocks Individual blocks with bounding rectangles.
 * @property sourceWidth  Width of the processed bitmap (for coordinate scaling).
 * @property sourceHeight Height of the processed bitmap (for coordinate scaling).
 */
data class OcrResult(
    val text: String,
    val textBlocks: List<DetectedTextBlock>,
    val sourceWidth: Int,
    val sourceHeight: Int
)

/**
 * A single detected text block from ML Kit that passed
 * confidence filtering (>= 0.7).
 *
 * @property text    Recognized text for this block.
 * @property boundingBox Bounding rectangle in source-image coordinates.
 */
data class DetectedTextBlock(
    val text: String,
    val boundingBox: Rect
)
