package com.spendsnap.ai

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AiException(message: String) : Exception(message)

/**
 * Minimal Gemini API client (REST generateContent). The key is compiled in from api.txt;
 * there is no backend. Callers decide what data goes into [prompt].
 */
class GeminiClient(private val apiKey: String, private val model: String) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** Returns the model's text; with [schema] the text is JSON matching it. */
    suspend fun generate(system: String, prompt: String, schema: JSONObject? = null): String = withContext(Dispatchers.IO) {
        if (!isConfigured) throw AiException("No Gemini API key in this build")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put(
                "generationConfig",
                JSONObject().put("temperature", 0.2).apply {
                    if (schema != null) {
                        put("responseMimeType", "application/json")
                        put("responseSchema", schema)
                    }
                },
            )
        val conn = (URL("$BASE/$model:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw AiException(errorMessage(code, text))
            extractText(text)
        } catch (e: java.io.IOException) {
            throw AiException("Couldn’t reach Gemini. Check your internet connection.")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"

        fun extractText(response: String): String {
            val json = JSONObject(response)
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw AiException(json.optJSONObject("promptFeedback")?.optString("blockReason")?.let { "Gemini declined the request ($it)" } ?: "Gemini returned no answer")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val text = (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() }.trim()
            if (text.isEmpty()) throw AiException("Gemini returned an empty answer (${candidate.optString("finishReason", "unknown")})")
            return text
        }

        fun errorMessage(code: Int, body: String): String {
            val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            return when (code) {
                400, 403 -> "Gemini rejected the API key or request" + (message?.let { ": $it" } ?: "")
                404 -> "Gemini model not found. Check “model” in api.txt."
                429 -> "Gemini quota reached. Try again later."
                else -> "Gemini error $code" + (message?.let { ": $it" } ?: "")
            }
        }
    }
}
