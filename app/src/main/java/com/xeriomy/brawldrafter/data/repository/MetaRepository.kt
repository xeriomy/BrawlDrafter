package com.xeriomy.brawldrafter.data.repository

import com.xeriomy.brawldrafter.data.api.BrawlifyApi
import com.xeriomy.brawldrafter.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository that manages all meta data (brawlers, maps, stats).
 * 
 * Strategy:
 * - Fetches fresh data from Brawlify API
 * - Falls back to hardcoded fallback data if API is unavailable
 * - Caches data in memory for the session
 * - Future: Room DB for persistent caching
 */
class MetaRepository(
    private val brawlifyApi: BrawlifyApi
) {
    // In-memory cache
    private var cachedBrawlers: List<Brawler> = emptyList()
    private var cachedMaps: List<MapInfo> = emptyList()
    private var lastBrawlerFetch: Long = 0
    private var lastMapFetch: Long = 0

    companion object {
        private const val CACHE_DURATION_MS = 30 * 60 * 1000L // 30 minutes
    }

    /**
     * Get all brawlers with their meta stats.
     * Fetches from API if cache is stale, otherwise returns cached data.
     */
    suspend fun getAllBrawlers(): List<Brawler> = withContext(Dispatchers.IO) {
        if (cachedBrawlers.isNotEmpty() && System.currentTimeMillis() - lastBrawlerFetch < CACHE_DURATION_MS) {
            return@withContext cachedBrawlers
        }

        try {
            val response = brawlifyApi.getBrawlers()
            val brawlers = response.data?.map { it.toBrawler() } ?: fallbackBrawlers()
            cachedBrawlers = brawlers
            lastBrawlerFetch = System.currentTimeMillis()
            brawlers
        } catch (e: Exception) {
            if (cachedBrawlers.isNotEmpty()) cachedBrawlers else fallbackBrawlers()
        }
    }

    /**
     * Get all current maps with stats.
     */
    suspend fun getAllMaps(): List<MapInfo> = withContext(Dispatchers.IO) {
        if (cachedMaps.isNotEmpty() && System.currentTimeMillis() - lastMapFetch < CACHE_DURATION_MS) {
            return@withContext cachedMaps
        }

        try {
            val response = brawlifyApi.getMaps()
            val maps = response.data?.map { it.toMapInfo() } ?: emptyList()
            cachedMaps = maps
            lastMapFetch = System.currentTimeMillis()
            maps
        } catch (e: Exception) {
            if (cachedMaps.isNotEmpty()) cachedMaps else emptyList()
        }
    }

    /**
     * Get stats for a specific map by name (fuzzy match).
     */
    suspend fun getMapStats(mapName: String): MapInfo? {
        val maps = getAllMaps()
        return maps.firstOrNull { it.name.equals(mapName, ignoreCase = true) }
            ?: maps.firstOrNull { it.name.contains(mapName, ignoreCase = true) }
            ?: maps.firstOrNull { mapName.contains(it.name, ignoreCase = true) }
    }

    /**
     * Find best matching map when OCR text is partial/garbled.
     */
    suspend fun findBestMapMatch(ocrText: String): MapInfo? {
        if (ocrText.isBlank()) return null
        val maps = getAllMaps()
        if (maps.isEmpty()) return null

        // Try exact matches first
        maps.firstOrNull { it.name.equals(ocrText, ignoreCase = true) }?.let { return it }

        // Fuzzy match: find map with highest word overlap
        val ocrWords = ocrText.lowercase().split(Regex("\\s+"))
        return maps.maxByOrNull { map ->
            val mapWords = map.name.lowercase().split(Regex("\\s+"))
            mapWords.count { word -> ocrWords.any { ocr -> ocr.contains(word) || word.contains(ocr) } }
        }
    }

    // --- Mappers ---

    private fun com.xeriomy.brawldrafter.data.api.BrawlifyBrawler.toBrawler(): Brawler {
        val classEnum = try {
            Brawler.BrawlerClass.valueOf(classDef.uppercase().replace(" ", "_").replace("-", "_"))
        } catch (e: Exception) {
            Brawler.BrawlerClass.DAMAGE_DEALER
        }

        val rarityEnum = try {
            Brawler.Rarity.valueOf(rarity.uppercase().replace(" ", "_"))
        } catch (e: Exception) {
            Brawler.Rarity.COMMON
        }

        return Brawler(
            id = id,
            name = name,
            brawlerClass = classEnum,
            rarity = rarityEnum,
            imageUrl = imageUrl,
            winRate = stats?.winRate ?: 0.0,
            pickRate = stats?.pickRate ?: 0.0,
            banRate = stats?.banRate ?: 0.0,
            tier = stats?.tier ?: 0
        )
    }

    private fun com.xeriomy.brawldrafter.data.api.BrawlifyMap.toMapInfo(): MapInfo {
        val gameMode = try {
            MapInfo.GameMode.valueOf(gameMode.uppercase().replace(" ", "_"))
        } catch (e: Exception) {
            MapInfo.GameMode.UNKNOWN
        }

        val brawlerStats = stats?.map { stat ->
            BrawlerMapStats(
                brawlerName = stat.brawler?.name ?: "",
                winRate = stat.winRate,
                pickRate = stat.pickRate,
                banRate = stat.banRate
            )
        } ?: emptyList()

        return MapInfo(
            id = id,
            name = name,
            gameMode = gameMode,
            imageUrl = imageUrl,
            brawlerStats = brawlerStats
        )
    }

    // --- Fallback data (hardcoded popular brawlers with relationship data) ---

    private fun fallbackBrawlers(): List<Brawler> = listOf(
        Brawler(1, "Shelly", Brawler.BrawlerClass.DAMAGE_DEALER, Brawler.Rarity.STARTER,
            counters = listOf("Edgar", "Mortis", "Crow"), weakTo = listOf("Piper", "Brock", "Nani"),
            synergies = listOf("Poco", "Bull"), tier = 3, winRate = 49.5),
        Brawler(2, "Nita", Brawler.BrawlerClass.CONTROLLER, Brawler.Rarity.STARTER,
            counters = listOf("Jessie", "Pam"), weakTo = listOf("Brock", "Piper"),
            synergies = listOf("Tick", "Jessie"), tier = 3, winRate = 48.0),
        Brawler(3, "Colt", Brawler.BrawlerClass.DAMAGE_DEALER, Brawler.Rarity.COMMON,
            counters = listOf("Edgar", "Mortis"), weakTo = listOf("Cordelius", "Shade"),
            synergies = listOf("Gene", "Poco"), tier = 3, winRate = 49.0),
        Brawler(4, "Bull", Brawler.BrawlerClass.TANK, Brawler.Rarity.COMMON,
            counters = listOf("Jessie", "Barley"), weakTo = listOf("Piper", "Brock", "Crow"),
            synergies = listOf("Shelly", "Poco"), tier = 2, winRate = 47.5),
        Brawler(5, "Jessie", Brawler.BrawlerClass.CONTROLLER, Brawler.Rarity.COMMON,
            counters = listOf("Spike", "Tick"), weakTo = listOf("Colt", "Brock"),
            synergies = listOf("Nita", "Pam"), tier = 3, winRate = 49.5),
        Brawler(6, "Brock", Brawler.BrawlerClass.DAMAGE_DEALER, Brawler.Rarity.RARE,
            counters = listOf("Edgar", "Bull"), weakTo = listOf("Gene", "Frank"),
            synergies = listOf("Poco", "Pam"), tier = 3, winRate = 50.0),
        Brawler(7, "Dynamike", Brawler.BrawlerClass.DAMAGE_DEALER, Brawler.Rarity.RARE,
            counters = listOf("Bull", "Frank"), weakTo = listOf("Leon", "Crow"),
            synergies = listOf("Barley", "Pam"), tier = 3, winRate = 50.5),
        Brawler(8, "Bo", Brawler.BrawlerClass.CONTROLLER, Brawler.Rarity.RARE,
            counters = listOf("Mortis", "Edgar"), weakTo = listOf("Brock", "Piper"),
            synergies = listOf("Poco", "Tick"), tier = 3, winRate = 49.0),
        Brawler(9, "Spike", Brawler.BrawlerClass.DAMAGE_DEALER, Brawler.Rarity.LEGENDARY,
            counters = listOf("Edgar", "Mortis"), weakTo = listOf("Gene", "Piper"),
            synergies = listOf("Barley", "Crow"), tier = 4, winRate = 50.0),
        Brawler(10, "Leon", Brawler.BrawlerClass.ASSASSIN, Brawler.Rarity.LEGENDARY,
            counters = listOf("Brock", "Piper"), weakTo = listOf("Lou", "Barley"),
            synergies = listOf("Crow", "Tara"), tier = 4, winRate = 50.5),
        Brawler(11, "Crow", Brawler.BrawlerClass.ASSASSIN, Brawler.Rarity.LEGENDARY,
            counters = listOf("Sandy", "Poco"), weakTo = listOf("Gene", "Frank"),
            synergies = listOf("Leon", "Spike"), tier = 4, winRate = 50.0),
        Brawler(12, "Gene", Brawler.BrawlerClass.SUPPORT, Brawler.Rarity.MYTHIC,
            counters = listOf("Brock", "Piper", "Colt"), weakTo = listOf("Mortis", "Edgar"),
            synergies = listOf("Poco", "Pam"), tier = 4, winRate = 51.0),
        Brawler(13, "Poco", Brawler.BrawlerClass.SUPPORT, Brawler.Rarity.RARE,
            counters = listOf("Crow", "Tick"), weakTo = listOf("Piper", "Brock"),
            synergies = listOf("Almost any brawler"), tier = 4, winRate = 51.5),
        Brawler(14, "Edgar", Brawler.BrawlerClass.ASSASSIN, Brawler.Rarity.EPIC,
            counters = listOf("Brock", "Piper", "Colt"), weakTo = listOf("Frank", "Gene"),
            synergies = listOf("Sandy", "Lou"), tier = 4, winRate = 50.0),
        Brawler(15, "Sandy", Brawler.BrawlerClass.CONTROLLER, Brawler.Rarity.MYTHIC,
            counters = listOf("Barley", "Dynamike"), weakTo = listOf("Gene", "Crow"),
            synergies = listOf("Edgar", "Poco"), tier = 4, winRate = 51.0),
        Brawler(16, "Mico", Brawler.BrawlerClass.ASSASSIN, Brawler.Rarity.MYTHIC,
            counters = listOf("Brock", "Piper"), weakTo = listOf("Frank", "Gene"),
            synergies = listOf("Edgar", "Leon"), tier = 5, winRate = 52.0),
        Brawler(17, "Kenji", Brawler.BrawlerClass.ASSASSIN, Brawler.Rarity.MYTHIC,
            counters = listOf("Brock", "Colt"), weakTo = listOf("Piper", "Frank"),
            synergies = listOf("Poco", "Sandy"), tier = 5, winRate = 53.0),
        Brawler(18, "Lily", Brawler.BrawlerClass.SUPPORT, Brawler.Rarity.CHROMATIC,
            counters = listOf("Crow", "Leon"), weakTo = listOf("Edgar", "Mortis"),
            synergies = listOf("Poco", "Gene"), tier = 4, winRate = 51.0),
        Brawler(19, "Pearl", Brawler.BrawlerClass.TANK, Brawler.Rarity.MYTHIC,
            counters = listOf("Brock", "Colt"), weakTo = listOf("Gene", "Piper"),
            synergies = listOf("Poco", "Sandy"), tier = 4, winRate = 50.5),
        Brawler(20, "Cordelius", Brawler.BrawlerClass.ASSASSIN, Brawler.Rarity.CHROMATIC,
            counters = listOf("Colt", "Brock", "Piper"), weakTo = listOf("Gene", "Frank"),
            synergies = listOf("Sandy", "Poco"), tier = 4, winRate = 51.0)
    )
}