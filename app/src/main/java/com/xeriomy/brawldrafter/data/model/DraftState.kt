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
    val teamBans: List<String> = emptyList(),
    val enemyBans: List<String> = emptyList(),
    val isYourTurn: Boolean = false,
    val availableBrawlers: List<String> = emptyList(),
    val draftPhase: DraftPhase = DraftPhase.UNKNOWN
) {
    enum class DraftPhase {
        BAN_PHASE, PICK_PHASE, COMPLETE, UNKNOWN
    }

    /** All currently picked brawlers (both teams) */
    val allPicks: List<String> get() = teamPicks + enemyPicks

    /** All bans (both teams) */
    val allBans: List<String> get() = teamBans + enemyBans

    /** Brawlers not yet picked */
    val unpicked: List<String>
        get() = ALL_BRAWLER_NAMES.filter { it !in allPicks && it !in allBans }

    /** Whether this looks like a real Brawl Stars draft screen.
     *  STRICT validation — all conditions must hold:
     *  1. A recognized game mode MUST be detected (Gem Grab, Brawl Ball, etc.)
     *     This alone eliminates all false positives from scanning the app's own UI,
     *     phone status bar, or any non-Brawl-Stars screen.
     *  2. At least 1 brawler must be identified (the draft has started)
     */
    val isValidDraft: Boolean
        get() {
            val uniqueBrawlers = (allPicks + allBans).toSet()
            val hasGameMode = mapGameMode != MapInfo.GameMode.UNKNOWN
            val hasBrawlers = uniqueBrawlers.size >= 1
            // BOTH conditions required — no exceptions
            return hasGameMode && hasBrawlers
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