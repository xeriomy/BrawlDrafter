package com.xeriomy.brawldrafter.ocr

import android.graphics.Rect
import com.xeriomy.brawldrafter.data.model.ALL_BRAWLER_NAMES
import com.xeriomy.brawldrafter.data.model.DraftState
import com.xeriomy.brawldrafter.data.model.MapInfo

/**
 * Parses raw OCR text from Brawl Stars draft screen into a structured DraftState.
 * 
 * The draft screen has a predictable layout:
 * - Map name at the top center
 * - Game mode below the map name
 * - Team brawlers on the left/bottom
 * - Enemy brawlers on the right/top
 * - Ban phase shows "BAN" labels
 * - Pick phase shows selection order
 * 
 * Strategy: Use both text content AND spatial position to determine:
 * - Which brawlers are team vs enemy (based on screen position)
 * - Which phase we're in (ban vs pick)
 * - Map name detection
 */
object DraftScreenParser {

    // Keywords that indicate game modes
    private val GAME_MODE_KEYWORDS = mapOf(
        "gem" to MapInfo.GameMode.GEM_GRAB,
        "showdown" to MapInfo.GameMode.SHOWDOWN,
        "brawl ball" to MapInfo.GameMode.BRAWL_BALL,
        "brawlball" to MapInfo.GameMode.BRAWL_BALL,
        "heist" to MapInfo.GameMode.HEIST,
        "bounty" to MapInfo.GameMode.BOUNTY,
        "hot zone" to MapInfo.GameMode.HOT_ZONE,
        "hotzone" to MapInfo.GameMode.HOT_ZONE,
        "knockout" to MapInfo.GameMode.KNOCKOUT,
        "blind pick" to MapInfo.GameMode.BLIND_PICK,
        "capture the flag" to MapInfo.GameMode.CAPTURE_THE_FLAG,
        "takedown" to MapInfo.GameMode.TAKEDOWN,
        "stash" to MapInfo.GameMode.STASH,
        "checkerboard" to MapInfo.GameMode.CHECKERBOARD,
        "duo showdown" to MapInfo.GameMode.DUO_SHOWDOWN,
        "rampage" to MapInfo.GameMode.SUPERCITY_RAMPAGE
    )

    /**
     * Parse raw OCR text into DraftState.
     * 
     * @param ocrText Raw text from ML Kit
     * @param screenWidth Screen width in pixels (for spatial analysis)
     * @param screenHeight Screen height in pixels
     * @return Parsed DraftState
     */
    fun parse(ocrText: String, screenWidth: Int, screenHeight: Int): DraftState {
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }
        
        val detectedBrawlers = mutableListOf<Pair<String, Double>>() // name, y-position ratio
        var detectedMapName = ""
        var detectedGameMode = MapInfo.GameMode.UNKNOWN
        var isBanPhase = false
        var isPickPhase = false

        for (line in lines) {
            // Check for ban phase indicator
            if (line.contains("BAN", ignoreCase = true) && !line.contains("BAND", ignoreCase = true)) {
                isBanPhase = true
            }

            // Check for pick phase indicator
            if (line.contains("PICK", ignoreCase = true)) {
                isPickPhase = true
            }

            // Check for game mode
            for ((keyword, mode) in GAME_MODE_KEYWORDS) {
                if (line.contains(keyword, ignoreCase = true)) {
                    detectedGameMode = mode
                }
            }

            // Try to find a brawler name in this line
            val brawlerMatch = findBrawlerInLine(line)
            if (brawlerMatch != null) {
                detectedBrawlers.add(brawlerMatch)
            }
        }

        // Try to extract map name from lines that don't contain brawler names
        detectedMapName = extractMapName(lines.filter { findBrawlerInLine(it) == null })

        // Determine phase
        val phase = when {
            isBanPhase -> DraftState.DraftPhase.BAN_PHASE
            isPickPhase || detectedBrawlers.isNotEmpty() -> DraftState.DraftPhase.PICK_PHASE
            else -> DraftState.DraftPhase.UNKNOWN
        }

        return DraftState(
            mapName = detectedMapName,
            mapGameMode = detectedGameMode,
            teamPicks = detectedBrawlers.map { it.first }, // Simplified - spatial version below
            enemyPicks = emptyList(),  // Will be populated by spatial parser
            draftPhase = phase,
            isYourTurn = true  // Assumption: user triggers scan when it's their turn
        )
    }

    /**
     * Parse with spatial information for accurate team/enemy separation.
     * 
     * Brawl Stars draft layout (landscape):
     * - Top portion: Enemy team picks (or bans)
     * - Bottom portion: Your team picks
     * - Center: Map name, VS indicator
     * 
     * @param textWithPositions List of (text, bounding box) pairs from OCR
     * @param screenWidth Screen width in pixels
     * @param screenHeight Screen height in pixels
     * @return Parsed DraftState with accurate team/enemy assignment
     */
    fun parseWithPositions(
        textWithPositions: List<Pair<String, Rect>>,
        screenWidth: Int,
        screenHeight: Int
    ): DraftState {
        val teamPicks = mutableListOf<String>()
        val enemyPicks = mutableListOf<String>()
        val allText = StringBuilder()
        var detectedGameMode = MapInfo.GameMode.UNKNOWN
        var mapNameParts = mutableListOf<String>()
        var isBanPhase = false

        // Process in vertical order (top = enemies, bottom = team)
        val sortedItems = textWithPositions.sortedBy { it.second.centerY() }

        for ((text, rect) in sortedItems) {
            val textUpper = text.uppercase()
            val yRatio = rect.centerY().toDouble() / screenHeight

            // Check for ban/pick keywords
            if (textUpper.contains("BAN") && !textUpper.contains("BAND")) {
                isBanPhase = true
                continue
            }

            // Check for game mode
            for ((keyword, mode) in GAME_MODE_KEYWORDS) {
                if (text.contains(keyword, ignoreCase = true)) {
                    detectedGameMode = mode
                }
            }

            // Try to match brawler name
            val brawlerName = fuzzyMatchBrawler(text)

            if (brawlerName != null) {
                // In Brawl Stars draft (landscape):
                // - Enemy picks are in the top ~40% of the screen
                // - Team picks are in the bottom ~40% of the screen
                if (yRatio < 0.4) {
                    enemyPicks.add(brawlerName)
                } else if (yRatio > 0.6) {
                    teamPicks.add(brawlerName)
                }
                // Ignore middle zone (likely map name area)
            } else {
                // Non-brawler text might be part of map name
                if (text.length in 3..25 && !textUpper.contains("PICK") 
                    && !textUpper.contains("BAN") && !textUpper.contains("BRAWL STARS")
                    && text.all { it.isLetter() || it.isWhitespace() || it in "'-" }) {
                    mapNameParts.add(text)
                }
            }

            allText.appendLine(text)
        }

        val mapName = mapNameParts.joinToString(" ").trim()
            .removeSuffix("map", ignoreCase = true).trim()

        val phase = when {
            isBanPhase -> DraftState.DraftPhase.BAN_PHASE
            teamPicks.isNotEmpty() || enemyPicks.isNotEmpty() -> DraftState.DraftPhase.PICK_PHASE
            else -> DraftState.DraftPhase.UNKNOWN
        }

        return DraftState(
            mapName = mapName,
            mapGameMode = detectedGameMode,
            teamPicks = teamPicks,
            enemyPicks = enemyPicks,
            isYourTurn = true,
            draftPhase = phase
        )
    }

    /**
     * Find a brawler name within a line of text using fuzzy matching.
     */
    private fun findBrawlerInLine(line: String): Pair<String, Double>? {
        val brawlerName = fuzzyMatchBrawler(line)
        return if (brawlerName != null) brawlerName to 0.5 else null
    }

    /**
     * Fuzzy match a text string against all known brawler names.
     * Handles OCR errors (e.g., "SheIIy" for "Shelly", "5pike" for "Spike").
     */
    fun fuzzyMatchBrawler(text: String): String? {
        val cleanText = text.trim()

        // Direct match first
        ALL_BRAWLER_NAMES.firstOrNull { it.equals(cleanText, ignoreCase = true) }
            ?.let { return it }

        // Containment check (OCR might include extra characters)
        ALL_BRAWLER_NAMES.firstOrNull { brawler ->
            cleanText.contains(brawler, ignoreCase = true) || 
            brawler.contains(cleanText, ignoreCase = true)
        }?.let { return it }

        // Levenshtein distance based fuzzy matching for OCR errors
        val normalizedText = cleanText.lowercase()
            .replace("l", "I").replace("1", "l").replace("0", "O")
            .replace("5", "S").replace("3", "E").replace("8", "B")
            .replace("|", "l").replace("{", "c").replace("}", "}")

        ALL_BRAWLER_NAMES
            .filter { levenshteinDistance(normalizedText, it.lowercase()) <= 3 }
            .minByOrNull { levenshteinDistance(normalizedText, it.lowercase()) }
            ?.let { return it }

        return null
    }

    /**
     * Extract map name from lines that don't contain brawler names.
     */
    private fun extractMapName(candidateLines: List<String>): String {
        // Map names are typically 2-5 words, in the center of the screen
        return candidateLines
            .filter { line ->
                line.length in 4..30 &&
                line.split(" ").size in 1..5 &&
                !line.uppercase().contains("PICK") &&
                !line.uppercase().contains("BAN") &&
                !line.uppercase().contains("SCORE") &&
                !line.uppercase().contains("TROPHY") &&
                !line.uppercase().contains("POWER")
            }
            .joinToString(" ")
            .trim()
    }

    /**
     * Calculate Levenshtein distance between two strings.
     * Used for fuzzy matching OCR output against brawler names.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // deletion
                    dp[i][j - 1] + 1,       // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[len1][len2]
    }
}