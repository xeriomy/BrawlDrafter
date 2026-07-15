package com.xeriomy.brawldrafter.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.xeriomy.brawldrafter.data.model.*

/**
 * Builds structured prompts for the LLM and parses responses.
 * 
 * Strategy: Send the LLM all available data in a structured format
 * and request a JSON response with ranked brawler recommendations.
 */
object PromptBuilder {

    const val SYSTEM_PROMPT = """You are an expert Brawl Stars draft analyst and coach. Your job is to analyze a draft situation and recommend the best brawler pick.

You will receive:
- Map name and game mode
- Teammate brawler picks
- Enemy brawler picks
- Available brawlers to pick from
- Meta data (win rates, pick rates, ban rates on this map)
- Brawler relationship data (counters, synergies, weaknesses)

You must respond with ONLY a valid JSON object (no markdown, no explanation outside JSON) with this exact structure:
{
  "recommendations": [
    {
      "brawlerName": "string",
      "score": 0-100,
      "winRateOnMap": 0.0-100.0,
      "counterTo": ["enemy brawler names this counters"],
      "synergyWith": ["teammate names this synergizes with"],
      "weakTo": ["enemy names that can counter this"],
      "reasoning": "2-3 sentence explanation of why this is a good pick",
      "tier": 1-5
    }
  ],
  "mapAnalysis": "Brief analysis of the map and what comp types excel here",
  "teamCompScore": 0-100,
  "enemyCompScore": 0-100,
  "overallAdvice": "1-2 sentence strategic advice for this draft"
}

Rules:
- Return top 3-5 recommendations sorted by score (highest first)
- Score should be a composite of: map win rate, counter value, synergy value, and overall meta strength
- Be specific in reasoning - reference exact brawler names and abilities
- Consider the full team composition, not just individual matchups
- Account for ban phase if applicable
- Always prioritize brawlers that are available to pick"""

    /**
     * Builds a user prompt with all draft context + meta data.
     */
    fun buildPrompt(
        draftState: DraftState,
        mapInfo: MapInfo?,
        brawlerData: List<Brawler>
    ): String {
        val sb = StringBuilder()

        // Draft situation
        sb.appendLine("## CURRENT DRAFT SITUATION")
        sb.appendLine("**Map:** ${draftState.mapName}")
        sb.appendLine("**Game Mode:** ${draftState.mapGameMode.name.replace("_", " ")}")
        sb.appendLine("**Phase:** ${draftState.draftPhase.name.replace("_", " ")}")
        sb.appendLine("**Is My Turn:** ${draftState.isYourTurn}")
        sb.appendLine()

        // Team picks
        sb.appendLine("## TEAM PICKS (${draftState.teamPicks.size}/3)")
        draftState.teamPicks.forEach { sb.appendLine("- $it") }
        if (draftState.teamPicks.isEmpty()) sb.appendLine("(none yet)")
        sb.appendLine()

        // Enemy picks
        sb.appendLine("## ENEMY PICKS (${draftState.enemyPicks.size}/3)")
        draftState.enemyPicks.forEach { sb.appendLine("- $it") }
        if (draftState.enemyPicks.isEmpty()) sb.appendLine("(none yet)")
        sb.appendLine()

        // Available brawlers
        val available = draftState.unpicked.ifEmpty {
            ALL_BRAWLER_NAMES.filter { it !in draftState.allPicks }
        }
        sb.appendLine("## AVAILABLE BRAWLERS TO PICK")
        available.forEach { sb.appendLine("- $it") }
        sb.appendLine()

        // Map-specific meta data
        if (mapInfo != null) {
            sb.appendLine("## MAP META DATA (Live)")
            sb.appendLine("Brawler performance on ${mapInfo.name}:")
            mapInfo.brawlerStats
                .sortedByDescending { it.winRate }
                .take(15)
                .forEach { stat ->
                    sb.appendLine(
                        "- ${stat.brawlerName}: " +
                        "WR=${"%.1f".format(stat.winRate)}% " +
                        "PR=${"%.1f".format(stat.pickRate)}% " +
                        "BR=${"%.1f".format(stat.banRate)}%"
                    )
                }
            sb.appendLine()
        }

        // Brawler relationship data
        sb.appendLine("## BRAWLER RELATIONSHIPS")
        val relevantBrawlers = draftState.allPicks.toSet()
        brawlerData.filter { it.name in relevantBrawlers || it.name in available.take(20) }
            .forEach { brawler ->
                if (brawler.counters.isNotEmpty() || brawler.weakTo.isNotEmpty() || brawler.synergies.isNotEmpty()) {
                    sb.appendLine("**${brawler.name}:**")
                    if (brawler.counters.isNotEmpty())
                        sb.appendLine("  Counters: ${brawler.counters.joinToString(", ")}")
                    if (brawler.weakTo.isNotEmpty())
                        sb.appendLine("  Weak to: ${brawler.weakTo.joinToString(", ")}")
                    if (brawler.synergies.isNotEmpty())
                        sb.appendLine("  Synergizes with: ${brawler.synergies.joinToString(", ")}")
                }
            }

        sb.appendLine()
        sb.appendLine("Provide your top pick recommendations as JSON.")

        return sb.toString()
    }

    /**
     * Parses the LLM JSON response into a DraftAnalysis object.
     * Handles common formatting issues robustly.
     */
    fun parseResponse(jsonString: String): DraftAnalysis {
        val gson = Gson()
        
        // Clean potential markdown code block wrapping
        val cleanJson = jsonString
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val type = object : TypeToken<DraftAnalysis>() {}.type
            gson.fromJson(cleanJson, type) ?: DraftAnalysis(emptyList())
        } catch (e: Exception) {
            // Try to extract JSON from the response if wrapped in text
            val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL)
                .find(cleanJson)?.value
            if (jsonMatch != null) {
                try {
                    val type = object : TypeToken<DraftAnalysis>() {}.type
                    gson.fromJson(jsonMatch, type) ?: DraftAnalysis(emptyList())
                } catch (e2: Exception) {
                    DraftAnalysis(emptyList(), overallAdvice = "Failed to parse AI response")
                }
            } else {
                DraftAnalysis(emptyList(), overallAdvice = "Failed to parse AI response")
            }
        }
    }

    const val VISION_PROMPT = """You are analyzing a Brawl Stars draft screen screenshot.

The draft screen layout:
- Top section: ENEMY team brawlers (shown as circular portrait icons, left to right)
- Bottom section: YOUR TEAM brawlers (shown as circular portrait icons, left to right)
- Center area: Map name and game mode text
- Banned brawlers may have an X overlay or appear grayed out

Identify ALL brawlers visible in the draft. Determine if each is on the top row (enemy) or bottom row (team). Also read the map name and game mode if visible.

Respond with ONLY a valid JSON object (no markdown, no explanation):
{
  "teamPicks": ["BrawlerName1"],
  "enemyPicks": ["BrawlerName1"],
  "mapName": "map name here",
  "gameMode": "game mode here"
}

Known Brawl Stars brawlers: Shelly, Nita, Colt, Bull, Jessie, Brock, Dynamike, Bo, El Primo, Barley, Poco, Rosa, Rico, Darryl, Penny, Carl, Pam, Frank, Mortis, Tara, Gene, Max, Sprout, Byron, Squeak, Tick, Amber, Lola, Colette, Edgar, Griff, Grom, Bonnie, Fang, Eve, Janet, Lou, Draco, Mico, Kenji, Juju, Angelo, Pearl, Berry, Lily, Clancy, Moe, Melodie, Shade, Chester, Gray, Kit, Lancelot, Cordelius, Ruffs, Buster, Charli, Gus, Dou, Drew, Joy, Belle, Surge, Sandy, Leon, Crow, Spike, Bea, Emz, Stu, Buzz, Nani, Meg, Gale, Ash"""

    /**
     * Parses the vision API JSON response into a VisionDraftResult.
     */
    fun parseVisionResponse(jsonString: String): VisionDraftResult {
        val gson = Gson()
        val clean = jsonString.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        return try {
            val json = gson.fromJson(clean, JsonObject::class.java)
            VisionDraftResult(
                teamPicks = json.getAsJsonArray("teamPicks")?.map { it.asString } ?: emptyList(),
                enemyPicks = json.getAsJsonArray("enemyPicks")?.map { it.asString } ?: emptyList(),
                mapName = json.get("mapName")?.asString ?: "",
                gameMode = json.get("gameMode")?.asString ?: ""
            )
        } catch (e: Exception) {
            val match = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(clean)?.value
            if (match != null) {
                try {
                    val json = gson.fromJson(match, JsonObject::class.java)
                    VisionDraftResult(
                        teamPicks = json.getAsJsonArray("teamPicks")?.map { it.asString } ?: emptyList(),
                        enemyPicks = json.getAsJsonArray("enemyPicks")?.map { it.asString } ?: emptyList(),
                        mapName = json.get("mapName")?.asString ?: "",
                        gameMode = json.get("gameMode")?.asString ?: ""
                    )
                } catch (_: Exception) {
                    VisionDraftResult()
                }
            } else {
                VisionDraftResult()
            }
        }
    }
}