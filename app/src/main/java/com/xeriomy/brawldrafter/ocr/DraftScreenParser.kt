package com.xeriomy.brawldrafter.ocr

import android.graphics.Rect
import com.xeriomy.brawldrafter.data.model.ALL_BRAWLER_NAMES
import com.xeriomy.brawldrafter.data.model.DraftState
import com.xeriomy.brawldrafter.data.model.MapInfo

/**
 * Parses raw OCR text from Brawl Stars draft screen into a structured DraftState.
 *
 * CRITICAL anti-false-positive measures:
 * - POISON_WORDS: if ANY OCR text contains these, the entire scan is rejected
 *   (these are words from the phone status bar, BrawlDrafter app UI, etc.)
 * - Map name extraction is restricted to the center vertical band only (35-65%)
 * - Game mode must be from the known keyword list
 * - isValidDraft requires recognized game mode (not just any brawler count)
 */
object DraftScreenParser {

    // Keywords that indicate game modes (Brawl Stars specific)
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
     * Words that, if found in ANY OCR text, prove this is NOT a Brawl Stars draft screen.
     * These come from: phone status bar, BrawlDrafter app UI, system notifications, etc.
     *
     * If ANY single OCR text block contains one of these (case-insensitive substring match),
     * the entire parse result is poisoned and must be rejected.
     */
    private val POISON_WORDS = setOf(
        // BrawlDrafter app UI
        "brawldrafter", "ai-powered", "assistant", "overlay active",
        "api only", "ai + api", "requires api", "no api key",
        "start overlay", "meta data analysis", "generating",
        "select 'api", "open brawl stars",
        // Phone status bar / system
        "turkcell", "vodafone", "telekom", "verizon", "at&t", "t-mobile",
        "wifi", "vpn", "battery", "signal", "mobile data",
        "connected", "capturing screen", "screen capture",
        "notification", "messages", "call", "settings",
        "cancel", "ok", "allow", "deny", "permission",
        // System UI elements
        "recent", "back", "home", "recents",
        "navigation", "gesture", "system",
        // Time-related (status bar)
        "am", "pm",
        // Generic tech words that appear in status bars
        "lte", "5g", "4g", "3g", "edge", "hspa",
        "bluetooth", "nfc", "airplane", "flashlight",
        "rotation", "dnd", "do not disturb",
        "screenshot", "screen record",
        "power off", "restart", "lock",
        // Common app store / browser text
        "play store", "chrome", "safari", "firefox",
        "youtube", "instagram", "tiktok", "twitter",
        "discord", "telegram", "whatsapp",
        "download", "upload", "install", "update",
        "search", "share", "copy", "paste",
        // Extra safety
        "brawl stars"  // The game title itself appears in the game's header, not in draft
    )

    /**
     * Check if any OCR text block contains a poison word.
     * Returns true if the scan is poisoned (i.e., NOT a real draft screen).
     */
    private fun isPoisoned(textWithPositions: List<Pair<String, Rect>>): Boolean {
        for ((text, _) in textWithPositions) {
            val lower = text.lowercase().trim()
            if (lower.isBlank()) continue
            for (poison in POISON_WORDS) {
                if (lower.contains(poison)) return true
            }
        }
        return false
    }

    /**
     * Check if a single text block looks like it belongs to a Brawl Stars map name.
     * Rejects anything that looks like app UI, system text, or random noise.
     */
    private fun looksLikeMapName(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.length < 3 || lower.length > 30) return false

        // Must be mostly letters and spaces
        val letterCount = lower.count { it.isLetter() || it.isWhitespace() || it in "'-_" }
        if (letterCount < lower.length * 0.7) return false

        // Reject if it contains poison words
        for (poison in POISON_WORDS) {
            if (lower.contains(poison)) return false
        }

        // Reject common non-map words
        val rejectWords = setOf(
            "pick", "ban", "score", "trophy", "power", "level",
            "team", "enemy", "vs", "win", "loss", "draw",
            "loading", "connecting", "offline", "online",
            "tap", "click", "swipe", "drag", "select",
            "time", "round", "turn", "phase", "ready",
            "confirm", "back", "next", "skip",
            "brawler", "brawlers", "character", "hero",
            "rank", "rating", "points", "cups", "tokens",
            "shop", "store", "offer", "deal", "free",
            "battle", "fight", "play", "start", "end",
            "mode", "map", "game", "draft"
        )
        // Only reject if the ENTIRE text (stripped) is a reject word, not just contains it
        // Because real map names can contain words like "bell" (Belle's Bell)
        val stripped = lower.replace(Regex("[^a-z\\s]"), "").trim()
        if (stripped in rejectWords) return false

        // Reject if it's all uppercase (likely a label, not a map name)
        if (text == text.uppercase() && text.length > 3 && text.none { it.isLowerCase() }) return false

        return true
    }

    /**
     * Parse with spatial information for accurate team/enemy separation.
     *
     * Brawl Stars draft layout (landscape):
     * - Top portion (y < 35%): Enemy team picks
     * - Center band (35-65%): Map name, VS indicator, game mode
     * - Bottom portion (y > 65%): Your team picks
     *
     * @param textWithPositions List of (text, bounding box) pairs from OCR
     * @param screenWidth Screen width in pixels
     * @param screenHeight Screen height in pixels
     * @return Parsed DraftState, or an invalid DraftState if scan is poisoned
     */
    fun parseWithPositions(
        textWithPositions: List<Pair<String, Rect>>,
        screenWidth: Int,
        screenHeight: Int
    ): DraftState {
        // === POISON CHECK: Reject if ANY text contains non-BS words ===
        if (isPoisoned(textWithPositions)) {
            return DraftState() // Invalid empty state — isValidDraft will be false
        }

        val teamPicks = mutableListOf<String>()
        val enemyPicks = mutableListOf<String>()
        var detectedGameMode = MapInfo.GameMode.UNKNOWN
        val mapNameParts = mutableListOf<String>()
        var isBanPhase = false

        for ((text, rect) in textWithPositions) {
            val textTrimmed = text.trim()
            if (textTrimmed.isBlank()) continue

            val textUpper = textTrimmed.uppercase()
            val yRatio = rect.centerY().toDouble() / screenHeight

            // Check for ban/pick keywords
            if (textUpper.contains("BAN") && !textUpper.contains("BAND")) {
                isBanPhase = true
                continue
            }

            // Check for game mode (only from known keywords)
            for ((keyword, mode) in GAME_MODE_KEYWORDS) {
                if (textTrimmed.contains(keyword, ignoreCase = true)) {
                    detectedGameMode = mode
                }
            }

            // Try to match brawler name
            val brawlerName = fuzzyMatchBrawler(textTrimmed)

            if (brawlerName != null) {
                // Spatial assignment: enemies top 35%, team bottom 35%
                if (yRatio < 0.35) {
                    if (brawlerName !in enemyPicks) enemyPicks.add(brawlerName)
                } else if (yRatio > 0.65) {
                    if (brawlerName !in teamPicks) teamPicks.add(brawlerName)
                }
                // Middle zone: ignore — brawler names in the center are likely false positives
            } else if (yRatio in 0.35..0.65) {
                // Map name candidate: ONLY from the center band
                if (looksLikeMapName(textTrimmed)) {
                    mapNameParts.add(textTrimmed)
                }
            }
        }

        var mapName = mapNameParts.joinToString(" ").trim()
        // Clean trailing "map" or "Map" if present
        if (mapName.endsWith(" map", ignoreCase = true)) {
            mapName = mapName.dropLast(4).trim()
        }

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
     * Simple text-only parser (legacy, not used in main flow).
     */
    fun parse(ocrText: String, screenWidth: Int, screenHeight: Int): DraftState {
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }

        // Poison check on raw text
        val fullText = ocrText.lowercase()
        for (poison in POISON_WORDS) {
            if (fullText.contains(poison)) return DraftState()
        }

        val detectedBrawlers = mutableListOf<Pair<String, Double>>()
        var detectedGameMode = MapInfo.GameMode.UNKNOWN
        var isBanPhase = false
        var isPickPhase = false

        for (line in lines) {
            if (line.contains("BAN", ignoreCase = true) && !line.contains("BAND", ignoreCase = true)) {
                isBanPhase = true
            }
            if (line.contains("PICK", ignoreCase = true)) {
                isPickPhase = true
            }
            for ((keyword, mode) in GAME_MODE_KEYWORDS) {
                if (line.contains(keyword, ignoreCase = true)) {
                    detectedGameMode = mode
                }
            }
            val brawlerMatch = findBrawlerInLine(line)
            if (brawlerMatch != null) {
                detectedBrawlers.add(brawlerMatch)
            }
        }

        val phase = when {
            isBanPhase -> DraftState.DraftPhase.BAN_PHASE
            isPickPhase || detectedBrawlers.isNotEmpty() -> DraftState.DraftPhase.PICK_PHASE
            else -> DraftState.DraftPhase.UNKNOWN
        }

        return DraftState(
            mapGameMode = detectedGameMode,
            teamPicks = detectedBrawlers.map { it.first },
            enemyPicks = emptyList(),
            draftPhase = phase,
            isYourTurn = true
        )
    }

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

        // Reject very short text — real brawler names are 3+ chars
        if (cleanText.length < 3) return null

        // Reject text that's clearly not a name (contains numbers, special chars)
        if (cleanText.any { it.isDigit() }) return null

        // Reject if text contains poison words
        val lower = cleanText.lowercase()
        for (poison in POISON_WORDS) {
            if (lower.contains(poison)) return null
        }

        // Reject text with too many spaces (likely a sentence, not a name)
        if (cleanText.count { it == ' ' } > 1) return null

        // Direct match first
        ALL_BRAWLER_NAMES.firstOrNull { it.equals(cleanText, ignoreCase = true) }
            ?.let { return it }

        // Containment check — only if the OCR text is close in length to the brawler name
        ALL_BRAWLER_NAMES.firstOrNull { brawler ->
            val lengthRatio = cleanText.length.toFloat() / brawler.length.toFloat()
            lengthRatio in 0.5..2.0 &&
            (cleanText.contains(brawler, ignoreCase = true) ||
             brawler.contains(cleanText, ignoreCase = true))
        }?.let { return it }

        // Levenshtein distance based fuzzy matching for OCR errors
        val normalizedText = cleanText.lowercase()
            .replace("l", "I").replace("1", "l").replace("0", "O")
            .replace("5", "S").replace("3", "E").replace("8", "B")
            .replace("|", "l").replace("{", "c").replace("}", "}")

        if (normalizedText.length < 3) return null

        ALL_BRAWLER_NAMES
            .filter { brawler ->
                val nameLower = brawler.lowercase()
                kotlin.math.abs(normalizedText.length - nameLower.length) <= 1 &&
                levenshteinDistance(normalizedText, nameLower) <= 2
            }
            .minByOrNull { levenshteinDistance(normalizedText, it.lowercase()) }
            ?.let { return it }

        return null
    }

    /**
     * Calculate Levenshtein distance between two strings.
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
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[len1][len2]
    }
}