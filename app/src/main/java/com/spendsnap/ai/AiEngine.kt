package com.spendsnap.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/** Which model writes the Insights text. Chosen in Settings. */
enum class AiEngineKind(val label: String) {
    ON_DEVICE("On-device"),
    CLOUD("Cloud"),
}

/** A text model. [generate] returns plain text, or JSON matching [schema] when one is given. */
interface AiEngine {
    val onDevice: Boolean
    val isConfigured: Boolean
    suspend fun generate(system: String, prompt: String, schema: JSONObject? = null): String
}

object AiJson {
    /** Strips markdown fences and prose around a JSON value; small on-device models add both. */
    fun extract(text: String): String {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = t.indexOfFirst { it == '{' || it == '[' }
        val end = t.indexOfLast { it == '}' || it == ']' }
        return if (start >= 0 && end > start) t.substring(start, end + 1) else t
    }
}

enum class NanoState(val label: String) {
    READY("Ready on this phone"),
    DOWNLOADABLE("Model not downloaded yet"),
    DOWNLOADING("Downloading model…"),
    UNAVAILABLE("Not supported on this phone"),
}

/**
 * Gemini Nano through Android AICore (ML Kit GenAI Prompt API). Runs on the phone: nothing is
 * sent to a server. The model lives in the AICore system service, not in this app's memory.
 */
class GeminiNanoEngine : AiEngine {
    private val model by lazy { Generation.getClient() }

    override val onDevice = true
    override val isConfigured = true

    suspend fun state(): NanoState = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoState.READY
            FeatureStatus.DOWNLOADABLE -> NanoState.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> NanoState.DOWNLOADING
            else -> NanoState.UNAVAILABLE
        }
    } catch (_: Exception) {
        NanoState.UNAVAILABLE
    }

    fun download(): Flow<DownloadStatus> = model.download()

    override suspend fun generate(system: String, prompt: String, schema: JSONObject?): String {
        when (state()) {
            NanoState.READY -> Unit
            NanoState.DOWNLOADABLE, NanoState.DOWNLOADING ->
                throw AiException("Gemini Nano isn’t downloaded yet. Download it in Settings → AI Insights.")
            NanoState.UNAVAILABLE -> throw AiException("Gemini Nano isn’t available on this phone. Switch the AI engine to Cloud in Settings.")
        }
        // No server-side schema here: ask for JSON in the instruction and parse leniently.
        val instruction = if (schema == null) system else "$system\nReply with only JSON, no markdown, matching this JSON schema: $schema"
        val request = try {
            if (model.isSystemPromptAvailable()) {
                generateContentRequest(SystemInstruction(instruction), TextPart(prompt)) { temperature = 0.2f }
            } else {
                generateContentRequest(TextPart("$instruction\n\n$prompt")) { temperature = 0.2f }
            }
        } catch (e: GenAiException) {
            throw AiException("Gemini Nano error: ${e.message}")
        }
        val text = try {
            model.generateContent(request).candidates.firstOrNull()?.text
        } catch (e: GenAiException) {
            throw AiException("Gemini Nano error: ${e.message}")
        }
        return text?.trim()?.takeIf { it.isNotEmpty() } ?: throw AiException("Gemini Nano returned no answer")
    }
}
