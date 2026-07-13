package com.xeriomy.brawldrafter

import android.app.Application
import com.xeriomy.brawldrafter.data.api.BrawlifyApi
import com.xeriomy.brawldrafter.data.api.LlmClient
import com.xeriomy.brawldrafter.data.api.LlmProvider
import com.xeriomy.brawldrafter.data.repository.MetaRepository
import com.xeriomy.brawldrafter.overlay.FloatingButtonService
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

        val brawlifyApi = brawlifyRetrofit.create(com.xeriomy.brawldrafter.data.api.BrawlifyApi::class.java)

        // Meta repository
        metaRepository = MetaRepository(brawlifyApi)

        // Pre-fetch meta data in background
        appScope.launch {
            try {
                metaRepository.getAllBrawlers()
                metaRepository.getAllMaps()
            } catch (_: Exception) {}
        }
    }

    /**
     * Create a RecommendationEngine with the user's configured API key.
     */
    fun createRecommendationEngine(apiKey: String, provider: LlmProvider): com.xeriomy.brawldrafter.ai.RecommendationEngine {
        val llmClient = LlmClient(provider, apiKey)
        return com.xeriomy.brawldrafter.ai.RecommendationEngine(llmClient, metaRepository)
    }
}