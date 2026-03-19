package com.example.clearscanocr.data

import android.graphics.Bitmap
import com.example.clearscanocr.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Service that handles communication with the Google Gemini Vision API.
 */
class GeminiOcrService {

    private val generativeModel: GenerativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-2.0-flash",
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

    private val promptText = """
        Analyze this image and extract all text and structured data.

        Return ONLY a JSON object with these fields:
        {
          "title": "A short descriptive title (e.g., 'Book Page', 'Receipt', 'Industrial Panel')",
          "raw_text": "ALL text found in the image, preserving layout where possible",
          "date": "Extracted date if present",
          "time": "Extracted time if present",
          "low_set_temp": "Numeric value for 'Low Set' if present",
          "target_temp": "Numeric value for 'Target' if present",
          "high_set_temp": "Numeric value for 'High Set' if present",
          "low_temp_count": "Numeric value for 'Low Count' if present",
          "ok_temp_count": "Numeric value for 'OK Count' if present",
          "high_temp_count": "Numeric value for 'High Count' if present",
          "total_temp_count": "Numeric value for 'Total Count' if present",
          "peak_temp": "Numeric value for 'Peak' if present"
        }

        Rules:
        * If a field is not found, return an empty string "".
        * Do NOT return 'null' as a value.
        * Ensure 'raw_text' is as complete as possible.
    """.trimIndent()

    suspend fun extractStructuredData(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        try {
            // Compress bitmap to JPEG byte array for upload (optional, but good for limit size)
            // But generativeai accepts Android Bitmap directly!
            // generativeai SDK: content { image(bitmap) }
            val inputContent = content {
                image(bitmap)
                text(promptText)
            }

            val response = generativeModel.generateContent(inputContent)
            var responseText = response.text ?: throw Exception("Empty response from Gemini")

            // Clean up Markdown code blocks if present
            if (responseText.contains("```json")) {
                responseText = responseText.substringAfter("```json")
            }
            if (responseText.contains("```")) {
                responseText = responseText.substringBeforeLast("```")
            }
            responseText = responseText.trim()

            val json = JSONObject(responseText)

            fun optStr(key: String): String {
                val value = json.optString(key, "")
                return if (value == "null") "" else value
            }

            OcrResult(
                title = optStr("title"),
                rawText = optStr("raw_text"),
                date = optStr("date"),
                time = optStr("time"),
                lowSetTemp = optStr("low_set_temp"),
                targetTemp = optStr("target_temp"),
                highSetTemp = optStr("high_set_temp"),
                lowTempCount = optStr("low_temp_count"),
                okTempCount = optStr("ok_temp_count"),
                highTempCount = optStr("high_temp_count"),
                totalTempCount = optStr("total_temp_count"),
                peakTemp = optStr("peak_temp"),
                isValid = true,
                errorMessage = null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            OcrResult(isValid = false, errorMessage = e.message ?: "Unknown API error")
        }
    }
}
