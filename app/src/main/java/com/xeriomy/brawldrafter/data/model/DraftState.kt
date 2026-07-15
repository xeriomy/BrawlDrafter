package com.xeriomy.brawldrafter.data.model

import com.google.gson.annotations.SerializedName

/**
 * Map metadata from Brawl Stars API / Brawlify.
 * Includes current meta stats (win rates, ban rates per brawler on this map).
 */
data class MapInfo(
    val id: String,
    val name: String,
    val gameMode: GameMode,
    val imageUrl: String = "",
    val environment: String = "",
    @SerializedName("brawler_stats")
    val brawlerStats: List<BrawlerMapStats> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
) {
    enum class GameMode {
        GEM_GRAB, SHOWDOWN, BRAWL_BALL, HEIST, BOUNTY,
        HOT_ZONE, KNOCKOUT, SUPERCITY_RAMPAGE,
        BLIND_PICK, CAPTURE_THE_FLAG, CHECKERBOARD,
        TAKEDOWN, STASH, DUO_SHOWDOWN, TRIPLE_THREAT,
        UNKNOWN
    }
}

data class BrawlerMapStats(
    val brawlerName: String,
    val winRate: Double,
    val pickRate: Double,
    val banRate: Double,
    val usageRate: Double = 0.0
)

/**
 * Parsed draft screen state from OCR.
 */
data class DraftState(
    val mapName: String = "",
    val mapGameMode: MapInfo.GameMode = MapInfo.GameMode.UNKNOWN,
    val teamPicks: List<String> = emptyList(),
    val enemyPicks: List<String> = emptyList(),
    val isYourTurn: Boolean = false,
    val availableBrawlers: List<String> = emptyList(),
    val draftPhase: DraftPhase = DraftPhase.UNKNOWN
) {
    enum class DraftPhase {
        BAN_PHASE, PICK_PHASE, COMPLETE, UNKNOWN
    }

    /** All currently picked brawlers (both teams) */
    val allPicks: List<String> get() = teamPicks + enemyPicks

    /** Brawlers not yet picked */
    val unpicked: List<String>
        get() = ALL_BRAWLER_NAMES.filter { it !in allPicks }

    /** Whether this looks like a real Brawl Stars draft screen.
     *  Strict validation to avoid false positives from scanning the app's own UI.
     *  Requires either:
     *  - A recognized Brawl Stars game mode (Gem Grab, Brawl Ball, etc.)
     *  - 3+ unique real brawler names detected
     *  - A game mode AND at least 1 brawler
     */
    val isValidDraft: Boolean
        get() {
            val uniquePicks = allPicks.toSet()
            val hasGameMode = mapGameMode != MapInfo.GameMode.UNKNOWN
            val hasManyBrawlers = uniquePicks.size >= 3
            val hasBrawlerAndMode = uniquePicks.size >= 1 && hasGameMode
            return hasManyBrawlers || hasBrawlerAndMode
        }
}

/**
 * AI recommendation result for a single brawler pick.
 */
data class Recommendation(
    val brawlerName: String,
    val score: Double,          // 0.0 - 100.0 composite score
    val winRateOnMap: Double = 0.0,
    val counterTo: List<String> = emptyList(),
    val synergyWith: List<String> = emptyList(),
    val weakTo: List<String> = emptyList(),
    val reasoning: String = "",  // AI-generated explanation
    val tier: Int = 0
) {
    val grade: String
        get() = when {
            score >= 90 -> "S"
            score >= 80 -> "A"
            score >= 65 -> "B"
            score >= 50 -> "C"
            else -> "D"
        }
}

data class DraftAnalysis(
    val recommendations: List<Recommendation>,
    val mapAnalysis: String = "",
    val teamCompScore: Double = 0.0,
    val enemyCompScore: Double = 0.0,
    val overallAdvice: String = ""
)