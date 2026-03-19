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
        Extract structured data from this industrial control panel image.

        Return ONLY valid JSON:
        {
        "date": "",
        "time": "",
        "low_set_temp": "",
        "target_temp": "",
        "high_set_temp": "",
        "low_temp_count": "",
        "ok_temp_count": "",
        "high_temp_count": "",
        "total_temp_count": "",
        "peak_temp": ""
        }

        Rules:
        * Each label corresponds to a nearby numeric value
        * Do not mix rows or columns
        * Ignore noise text
        * Ensure numeric accuracy
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

            OcrResult(
                date = json.optString("date", ""),
                time = json.optString("time", ""),
                lowSetTemp = json.optString("low_set_temp", ""),
                targetTemp = json.optString("target_temp", ""),
                highSetTemp = json.optString("high_set_temp", ""),
                lowTempCount = json.optString("low_temp_count", ""),
                okTempCount = json.optString("ok_temp_count", ""),
                highTempCount = json.optString("high_temp_count", ""),
                totalTempCount = json.optString("total_temp_count", ""),
                peakTemp = json.optString("peak_temp", ""),
                isValid = true,
                errorMessage = null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            OcrResult(isValid = false, errorMessage = e.message ?: "Unknown API error")
        }
    }
}
