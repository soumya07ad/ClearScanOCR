package com.example.clearscanocr.domain

import android.graphics.Bitmap
import com.example.clearscanocr.data.GeminiOcrService
import com.example.clearscanocr.data.OcrResult

/**
 * Use-case that delegates OCR processing to [GeminiOcrService].
 */
class OcrUseCase(
    private val geminiOcrService: GeminiOcrService = GeminiOcrService()
) {

    /**
     * Executes the Gemini OCR call with a single retry mechanism.
     * Validates that all numeric fields are within the [0, 1500] range.
     */
    suspend fun execute(bitmap: Bitmap): OcrResult {
        var lastResult: OcrResult? = null
        
        // Attempt at most 2 times (1 initial try + 1 retry)
        for (attempt in 1..2) {
            val result = geminiOcrService.extractStructuredData(bitmap)
            
            if (result.isValid && validateNumericRanges(result)) {
                return result
            }
            lastResult = result
        }
        
        // Return the failed result (with error message) if both attempts fail
        return lastResult ?: OcrResult(isValid = false, errorMessage = "Unknown execution error")
    }

    private fun validateNumericRanges(result: OcrResult): Boolean {
        // Collect all fields that are expected to be numeric
        val numericFields = listOf(
            result.lowSetTemp, result.targetTemp, result.highSetTemp,
            result.lowTempCount, result.okTempCount, result.highTempCount, 
            result.totalTempCount, result.peakTemp
        )
        
        for (field in numericFields) {
            if (field.isBlank()) continue // Skip empty fields (or fail if required? Requirements say "numeric accuracy", so we validate non-empty numbers)
            
            val number = field.toDoubleOrNull()
            if (number == null || number < 0 || number > 1500) {
                return false
            }
        }
        return true
    }
}
