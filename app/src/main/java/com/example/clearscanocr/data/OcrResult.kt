package com.example.clearscanocr.data

import android.graphics.Rect

/**
 * Cleanly parsed data from Gemini OCR.
 */
data class OcrResult(
    val date: String = "",
    val time: String = "",
    val lowSetTemp: String = "",
    val targetTemp: String = "",
    val highSetTemp: String = "",
    val lowTempCount: String = "",
    val okTempCount: String = "",
    val highTempCount: String = "",
    val totalTempCount: String = "",
    val peakTemp: String = "",
    val isValid: Boolean = true,
    val errorMessage: String? = null
)
