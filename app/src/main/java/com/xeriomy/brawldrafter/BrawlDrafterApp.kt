package com.xeriomy.brawldrafter

import android.app.Application
import com.xeriomy.brawldrafter.ai.RecommendationEngine
import com.xeriomy.brawldrafter.data.api.BrawlifyApi
import com.xeriomy.brawldrafter.data.api.LlmClient
import com.xeriomy.brawldrafter.data.api.LlmProvider
import com.xeriomy.brawldrafter.data.repository.MetaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class BrawlDrafterApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var metaRepository: MetaRepository
        private set

    /** Current recommendation engine — updated when user changes settings. */
    var currentEngine: RecommendationEngine? = null
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize networking
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()

        // Brawlify API
        val brawlifyRetrofit = Retrofit.Builder()
            .baseUrl("https://api.brawlify.com/v1/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val brawlifyApi = brawlifyRetrofit.create(BrawlifyApi::class.java)

        // Meta repository
        metaRepository = MetaRepository(brawlifyApi)

        // Pre-fetch meta data in background
        appScope.launch {
            try {
                metaRepository.getAllBrawlers()
                metaRepository.getAllMaps()
            } catch (_: Exception) {}

            // Create initial engine from saved preferences
            val prefs = getSharedPreferences("brawldrafter", MODE_PRIVATE)
            val apiKey = prefs.getString("api_key", "") ?: ""
            val providerStr = prefs.getString("provider", "OpenAI") ?: "OpenAI"
            val mode = prefs.getString("mode", "api_only") ?: "api_only"
            val provider = when (providerStr) {
                "Gemini" -> LlmProvider.GEMINI
                "Claude" -> LlmProvider.CLAUDE
                else -> LlmProvider.OPENAI
            }
            updateEngine(apiKey, provider, mode)
        }
    }

    /**
     * Create / update the recommendation engine based on current settings.
     *
     * @param apiKey  LLM API key (blank if using API-only mode)
     * @param provider  Which LLM provider to use
     * @param mode  "api_only" or "ai_plus_api"
     */
    fun updateEngine(apiKey: String? = null, provider: LlmProvider = LlmProvider.OPENAI, mode: String = "api_only") {
        currentEngine = if (mode == "api_only" || apiKey.isNullOrBlank()) {
            // API-only: no LLM needed, just meta data analysis
            RecommendationEngine(metaRepository = metaRepository)
        } else {
            // AI + API: full hybrid analysis
            val llmClient = LlmClient(provider, apiKey)
            RecommendationEngine(llmClient, metaRepository)
        }
    }
}