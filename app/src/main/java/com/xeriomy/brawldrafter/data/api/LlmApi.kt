package com.xeriomy.brawldrafter.data.api

import com.xeriomy.brawldrafter.data.model.DraftAnalysis
import com.xeriomy.brawldrafter.ai.PromptBuilder
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * LLM API integration for AI-powered draft recommendations.
 * 
 * Supports multiple providers:
 * - OpenAI (GPT-4o-mini) - fastest, cheapest, great reasoning
 * - Google Gemini Flash - free tier, fast
 * - Anthropic Claude Haiku
 * 
 * Provider is selected via LlmProvider enum.
 * Default: GPT-4o-mini (best speed/quality/price ratio).
 */
enum class LlmProvider {
    OPENAI,
    GEMINI,
    CLAUDE
}

/**
 * OpenAI-compatible chat completion API interface.
 * Also works with any OpenAI-compatible endpoint (Together AI, Groq, etc.)
 */
interface LlmChatApi {

    @POST("v1/chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authHeader: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}

// --- Request/Response models ---

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
    val max_tokens: Int = 2000,
    val response_format: ResponseFormat? = null
)

data class ChatMessage(
    val role: String,   // "system", "user", "assistant"
    val content: String
)

data class ResponseFormat(
    val type: String = "json_object"
)

data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList(),
    val error: ChatError? = null
)

data class ChatChoice(
    val message: ChatMessage? = null,
    val finish_reason: String? = null
)

data class ChatError(
    val message: String = "",
    val type: String = ""
)

/**
 * Factory to create LLM API instances based on provider.
 */
object LlmApiFactory {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun create(provider: LlmProvider, apiKey: String, baseUrl: String? = null): LlmChatApi {
        val url = baseUrl ?: when (provider) {
            LlmProvider.OPENAI -> "https://api.openai.com/"
            LlmProvider.GEMINI -> "https://generativelanguage.googleapis.com/"
            LlmProvider.CLAUDE -> "https://api.anthropic.com/"
        }
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LlmChatApi::class.java)
    }
}

/**
 * High-level LLM client that handles provider-specific differences
 * and parses the response into a DraftAnalysis.
 */
class LlmClient(
    private val provider: LlmProvider,
    private val apiKey: String,
    private val baseUrl: String? = null
) {
    private val api = LlmApiFactory.create(provider, apiKey, baseUrl)

    suspend fun getRecommendations(prompt: String): DraftAnalysis {
        val model = when (provider) {
            LlmProvider.OPENAI -> "gpt-4o-mini"
            LlmProvider.GEMINI -> "gemini-2.0-flash"
            LlmProvider.CLAUDE -> "claude-3-haiku-20240307"
        }

        val authHeader = when (provider) {
            LlmProvider.OPENAI, LlmProvider.CLAUDE -> "Bearer $apiKey"
            LlmProvider.GEMINI -> apiKey
        }

        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = PromptBuilder.SYSTEM_PROMPT),
                ChatMessage(role = "user", content = prompt)
            ),
            temperature = 0.3,
            max_tokens = 2000,
            response_format = ResponseFormat()
        )

        val response = api.chatCompletion(authHeader, request)
        
        if (response.error != null) {
            throw LlmException(response.error.message)
        }

        val content = response.choices.firstOrNull()?.message?.content
            ?: throw LlmException("Empty response from LLM")

        return PromptBuilder.parseResponse(content)
    }
}

class LlmException(message: String) : Exception(message)