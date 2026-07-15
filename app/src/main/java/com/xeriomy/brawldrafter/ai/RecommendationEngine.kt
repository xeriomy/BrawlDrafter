package com.xeriomy.brawldrafter.ai

import com.xeriomy.brawldrafter.data.api.LlmClient
import com.xeriomy.brawldrafter.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The main recommendation engine that orchestrates the full analysis pipeline:
 * 
 * 1. Takes parsed draft state from OCR
 * 2. Fetches live meta data from Brawlify API
 * 3. Loads cached brawler relationship data
 * 4. Sends structured prompt to LLM
 * 5. Returns ranked recommendations
 * 
 * This runs the hybrid approach: data-driven scoring + AI reasoning.
 */
class RecommendationEngine(
    private val llmClient: LlmClient? = null,
    private val metaRepository: com.xeriomy.brawldrafter.data.repository.MetaRepository
) {
    /** Whether this engine has vision capability (LLM configured with vision support). */
    val hasVision: Boolean get() = llmClient != null

    /** Create a vision identifier using the same LLM credentials. */
    fun createVisionIdentifier(): VisionBrawlerIdentifier? {
        val client = llmClient ?: return null
        return VisionBrawlerIdentifier(client.provider, client.apiKey, client.baseUrl)
    }

    /**
     * Main entry point - analyze the current draft and return recommendations.
     * 
     * @param draftState Parsed draft screen from OCR
     * @return DraftAnalysis with ranked brawler recommendations
     */
    suspend fun analyze(draftState: DraftState): DraftAnalysis = withContext(Dispatchers.IO) {
        // Step 1: Fetch live meta data for the detected map
        val mapInfo = if (draftState.mapName.isNotBlank()) {
            metaRepository.getMapStats(draftState.mapName)
        } else null

        // Step 2: Load all brawler data (cached + fresh)
        val brawlerData = metaRepository.getAllBrawlers()

        // Step 3: If map wasn't detected by OCR, try to find it from available maps
        val resolvedMap = mapInfo ?: metaRepository.findBestMapMatch(draftState.mapName)

        // Step 4: Build prompt with all available data
        val prompt = PromptBuilder.buildPrompt(draftState, resolvedMap, brawlerData)

        // Step 5: Get AI recommendations (or data-only if no LLM configured)
        val aiAnalysis = if (llmClient != null) {
            try {
                llmClient.getRecommendations(prompt)
            } catch (e: Exception) {
                // Fallback to data-only scoring if LLM fails
                buildDataOnlyRecommendations(draftState, resolvedMap, brawlerData)
            }
        } else {
            buildDataOnlyRecommendations(draftState, resolvedMap, brawlerData)
        }

        // Step 6: Merge AI scores with data scores for final ranking
        mergeWithMetaScores(aiAnalysis, resolvedMap, brawlerData)
    }

    /**
     * API-only analysis: pure data-driven scoring using live meta data.
     * No LLM required. Uses map win rates, counter/synergy relationships, and tier ratings.
     */
    suspend fun analyzeApiOnly(draftState: DraftState): DraftAnalysis = withContext(Dispatchers.IO) {
        val mapInfo = if (draftState.mapName.isNotBlank()) {
            metaRepository.getMapStats(draftState.mapName)
        } else null
        val brawlerData = metaRepository.getAllBrawlers()
        val resolvedMap = mapInfo ?: metaRepository.findBestMapMatch(draftState.mapName)
        buildDataOnlyRecommendations(draftState, resolvedMap, brawlerData)
    }

    /**
     * Fallback scoring when LLM is unavailable.
     * Uses pure data-driven scoring based on map stats + counter/synergy relationships.
     */
    private fun buildDataOnlyRecommendations(
        draftState: DraftState,
        mapInfo: MapInfo?,
        brawlerData: List<Brawler>
    ): DraftAnalysis {
        val available = draftState.unpicked.ifEmpty {
            ALL_BRAWLER_NAMES.filter { it !in draftState.allPicks }
        }

        val mapStats = mapInfo?.brawlerStats?.associateBy { it.brawlerName } ?: emptyMap()
        val brawlerMap = brawlerData.associateBy { it.name }

        val recommendations = available.mapNotNull { brawlerName ->
            val brawler = brawlerMap[brawlerName] ?: return@mapNotNull null
            val mapStat = mapStats[brawlerName]

            var score = 50.0

            // Map win rate bonus (up to +25)
            mapStat?.let { score += (it.winRate - 50) * 0.5 }

            // Counter value (up to +15)
            val enemyCountered = brawler.counters.count { it in draftState.enemyPicks }
            score += enemyCountered * 5.0

            // Synergy value (up to +10)
            val teammateSynergy = brawler.synergies.count { it in draftState.teamPicks }
            score += teammateSynergy * 3.5

            // Weakness penalty (up to -15)
            val weakToEnemy = brawler.weakTo.count { it in draftState.enemyPicks }
            score -= weakToEnemy * 5.0

            // General tier bonus (up to +10)
            score += (brawler.tier - 3) * 2.5

            score = score.coerceIn(0.0, 100.0)

            val wrOnMap = mapStat?.winRate ?: brawler.winRate
            Recommendation(
                brawlerName = brawlerName,
                score = score,
                winRateOnMap = wrOnMap,
                counterTo = brawler.counters.filter { it in draftState.enemyPicks },
                synergyWith = brawler.synergies.filter { it in draftState.teamPicks },
                weakTo = brawler.weakTo.filter { it in draftState.enemyPicks },
                reasoning = "Data-only: WR on map=${"%.1f".format(wrOnMap)}%, counters ${brawler.counters.count { it in draftState.enemyPicks }} enemies, synergizes with ${brawler.synergies.count { it in draftState.teamPicks }} teammates.",
                tier = brawler.tier
            )
        }
        .sortedByDescending { it.score }
        .take(5)

        return DraftAnalysis(
            recommendations = recommendations,
            mapAnalysis = mapInfo?.let { "${it.name} (${it.gameMode.name})" } ?: "Map not detected",
            overallAdvice = if (recommendations.isNotEmpty()) {
                "Top pick: ${recommendations.first().brawlerName} (Score: ${"%.0f".format(recommendations.first().score)})"
            } else "No recommendations available"
        )
    }

    /**
     * Merge AI analysis scores with live meta data scores.
     * AI provides reasoning, meta data provides real-time accuracy.
     */
    private fun mergeWithMetaScores(
        aiAnalysis: DraftAnalysis,
        mapInfo: MapInfo?,
        brawlerData: List<Brawler>
    ): DraftAnalysis {
        val mapStats = mapInfo?.brawlerStats?.associateBy { it.brawlerName } ?: emptyMap()
        val brawlerMap = brawlerData.associateBy { it.name }

        val mergedRecs = aiAnalysis.recommendations.map { rec ->
            val mapStat = mapStats[rec.brawlerName]
            val brawler = brawlerMap[rec.brawlerName]

            // Adjust AI score with live meta data (weighted blend)
            val metaScore = when {
                mapStat != null -> mapStat.winRate
                brawler != null -> brawler.winRate
                else -> 50.0
            }

            // 70% AI score + 30% meta score
            val finalScore = (rec.score * 0.7) + (metaScore * 0.3)

            rec.copy(
                score = finalScore.coerceIn(0.0, 100.0),
                winRateOnMap = mapStat?.winRate ?: rec.winRateOnMap
            )
        }
        .sortedByDescending { it.score }

        return aiAnalysis.copy(recommendations = mergedRecs)
    }
}