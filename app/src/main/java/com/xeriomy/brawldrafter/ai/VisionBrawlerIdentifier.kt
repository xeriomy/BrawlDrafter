package com.xeriomy.brawldrafter.ai

import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xeriomy.brawldrafter.data.api.LlmException
import com.xeriomy.brawldrafter.data.api.LlmProvider
import com.xeriomy.brawldrafter.data.model.DraftState
import com.xeriomy.brawldrafter.data.model.MapInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Result of vision-based brawler identification from a draft screenshot.
 */
data class VisionDraftResult(
    val teamPicks: List<String> = emptyList(),
    val enemyPicks: List<String> = emptyList(),
    val teamBans: List<String> = emptyList(),
    val enemyBans: List<String> = emptyList(),
    val mapName: String = "",
    val gameMode: String = ""
) {
    fun toDraftState(ocrGameMode: MapInfo.GameMode = MapInfo.GameMode.UNKNOWN): DraftState {
        val resolvedGameMode = if (gameMode.isNotBlank()) {
            parseGameMode(gameMode) ?: ocrGameMode
        } else {
            ocrGameMode
        }

        return DraftState(
            mapName = mapName,
            mapGameMode = resolvedGameMode,
            teamPicks = teamPicks,
            enemyPicks = enemyPicks,
            teamBans = teamBans,
            enemyBans = enemyBans,
            isYourTurn = true,
            draftPhase = when {
                (teamBans.isNotEmpty() || enemyBans.isNotEmpty()) && teamPicks.isEmpty() && enemyPicks.isEmpty()
                    -> DraftState.DraftPhase.BAN_PHASE
                teamPicks.isNotEmpty() || enemyPicks.isNotEmpty()
                    -> DraftState.DraftPhase.PICK_PHASE
                else -> DraftState.DraftPhase.UNKNOWN
            }
        )
    }

    private fun parseGameMode(text: String): MapInfo.GameMode? {
        val keywords = mapOf(
            "gem grab" to MapInfo.GameMode.GEM_GRAB,
            "gem" to MapInfo.GameMode.GEM_GRAB,
            "showdown" to MapInfo.GameMode.SHOWDOWN,
            "brawl ball" to MapInfo.GameMode.BRAWL_BALL,
            "heist" to MapInfo.GameMode.HEIST,
            "bounty" to MapInfo.GameMode.BOUNTY,
            "hot zone" to MapInfo.GameMode.HOT_ZONE,
            "knockout" to MapInfo.GameMode.KNOCKOUT,
            "blind pick" to MapInfo.GameMode.BLIND_PICK,
            "capture the flag" to MapInfo.GameMode.CAPTURE_THE_FLAG,
            "takedown" to MapInfo.GameMode.TAKEDOWN,
        )
        val lower = text.lowercase()
        return keywords.entries.firstOrNull { (k, _) -> lower.contains(k) }?.value
    }
}

/**
 * Identifies brawlers from a Brawl Stars draft screenshot using vision-capable LLMs.
 *
 * Brawl Stars draft screens show brawler portrait icons (not text names),
 * so traditional OCR cannot detect picks. This class sends the screenshot
 * to a vision AI model (GPT-4o-mini, Gemini Flash) to identify brawlers.
 *
 * Supported providers: OpenAI (GPT-4o-mini), Gemini (Flash).
 * Claude Haiku does NOT support vision.
 */
class VisionBrawlerIdentifier(
    private val provider: LlmProvider,
    private val apiKey: String,
    private val baseUrl: String? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Identify brawlers from a draft screen screenshot.
     *
     * @param bitmap Screenshot of the Brawl Stars draft screen
     * @return VisionDraftResult with identified team/enemy picks, map name, and game mode
     */
    suspend fun identify(bitmap: Bitmap): VisionDraftResult {
        val base64 = bitmapToBase64(bitmap)
        return when (provider) {
            LlmProvider.OPENAI -> identifyViaOpenAi(base64)
            LlmProvider.GEMINI -> identifyViaGemini(base64)
            LlmProvider.CLAUDE -> throw LlmException(
                "Claude Haiku does not support vision. Switch to OpenAI or Gemini for icon detection."
            )
        }
    }

    // --- OpenAI (GPT-4o-mini) ---

    private suspend fun identifyViaOpenAi(base64: String): VisionDraftResult =
        suspendCancellableCoroutine { cont ->
            val body = buildOpenAiBody(base64)
            val url = "${baseUrl ?: "https://api.openai.com/"}v1/chat/completions"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(LlmException("Vision request failed: ${e.message}"))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val responseBody = response.body?.string()
                            ?: throw LlmException("Empty vision response")
                        val result = parseOpenAiResponse(responseBody)
                        cont.resume(result)
                    } catch (e: Exception) {
                        cont.resumeWithException(LlmException(e.message ?: "Parse error"))
                    }
                }
            })

            cont.invokeOnCancellation { client.newCall(request).cancel() }
        }

    private fun buildOpenAiBody(base64: String): String {
        val root = JsonObject()
        root.addProperty("model", "gpt-4o-mini")
        root.addProperty("max_tokens", 500)
        root.addProperty("temperature", 0.1)

        val messages = JsonArray()
        val userMsg = JsonObject()
        userMsg.addProperty("role", "user")

        val content = JsonArray()

        val textPart = JsonObject()
        textPart.addProperty("type", "text")
        textPart.addProperty("text", PromptBuilder.VISION_PROMPT)
        content.add(textPart)

        val imagePart = JsonObject()
        imagePart.addProperty("type", "image_url")
        val imageUrl = JsonObject()
        imageUrl.addProperty("url", "data:image/jpeg;base64,$base64")
        imagePart.add("image_url", imageUrl)
        content.add(imagePart)

        userMsg.add("content", content)
        messages.add(userMsg)
        root.add("messages", messages)

        return gson.toJson(root)
    }

    private fun parseOpenAiResponse(body: String): VisionDraftResult {
        val json = gson.fromJson(body, JsonObject::class.java)
        if (json.has("error")) {
            val errMsg = json.getAsJsonObject("error").get("message").asString
            throw LlmException(errMsg)
        }

        val content = json.getAsJsonArray("choices")
            .get(0).asJsonObject
            .getAsJsonObject("message")
            .get("content").asString

        return PromptBuilder.parseVisionResponse(content)
    }

    // --- Gemini (Flash) ---

    private suspend fun identifyViaGemini(base64: String): VisionDraftResult =
        suspendCancellableCoroutine { cont ->
            val body = buildGeminiBody(base64)
            val url = "${baseUrl ?: "https://generativelanguage.googleapis.com/"}" +
                    "v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(LlmException("Gemini vision failed: ${e.message}"))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val responseBody = response.body?.string()
                            ?: throw LlmException("Empty Gemini response")
                        val result = parseGeminiResponse(responseBody)
                        cont.resume(result)
                    } catch (e: Exception) {
                        cont.resumeWithException(LlmException(e.message ?: "Parse error"))
                    }
                }
            })

            cont.invokeOnCancellation { client.newCall(request).cancel() }
        }

    private fun buildGeminiBody(base64: String): String {
        val root = JsonObject()
        val contents = JsonArray()
        val part = JsonObject()

        part.addProperty("text", PromptBuilder.VISION_PROMPT)

        val inlineData = JsonObject()
        inlineData.addProperty("mime_type", "image/jpeg")
        inlineData.addProperty("data", base64)
        part.add("inline_data", inlineData)

        contents.add(JsonObject().apply { add("parts", JsonArray().apply { add(part) }) })
        root.add("contents", contents)

        val generationConfig = JsonObject().apply {
            addProperty("temperature", 0.1)
            addProperty("maxOutputTokens", 500)
        }
        root.add("generationConfig", generationConfig)

        return gson.toJson(root)
    }

    private fun parseGeminiResponse(body: String): VisionDraftResult {
        val json = gson.fromJson(body, JsonObject::class.java)
        if (json.has("error")) {
            val errMsg = json.getAsJsonObject("error").get("message").asString
            throw LlmException(errMsg)
        }

        val content = json.getAsJsonArray("candidates")
            .get(0).asJsonObject
            .getAsJsonObject("content")
            .getAsJsonArray("parts")
            .get(0).asJsonObject
            .get("text").asString

        return PromptBuilder.parseVisionResponse(content)
    }

    // --- Utilities ---

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}