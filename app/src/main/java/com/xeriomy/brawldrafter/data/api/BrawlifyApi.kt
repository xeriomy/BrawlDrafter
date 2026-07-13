package com.xeriomy.brawldrafter.data.api

import com.xeriomy.brawldrafter.data.model.Brawler
import com.xeriomy.brawldrafter.data.model.MapInfo
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Brawlify API - community source for live meta data, brawler stats, map stats.
 * Base URL: https://api.brawlify.com/v1/
 */
interface BrawlifyApi {

    /** Get current brawler list with meta stats */
    @GET("brawlers")
    suspend fun getBrawlers(): BrawlifyResponse<List<BrawlifyBrawler>>

    /** Get current map rotation with per-brawler stats */
    @GET("maps")
    suspend fun getMaps(): BrawlifyResponse<List<BrawlifyMap>>

    /** Get stats for a specific map */
    @GET("maps/{mapId}")
    suspend fun getMapDetail(@Path("mapId") mapId: String): BrawlifyResponse<BrawlifyMapDetail>
}

// --- Response wrappers ---
data class BrawlifyResponse<T>(
    val status: Int = 0,
    val data: T? = null
)

data class BrawlifyBrawler(
    val id: Int,
    val name: String,
    val classDef: String = "",
    val rarity: String = "",
    val imageUrl: String = "",
    val stats: BrawlifyBrawlerStats? = null
)

data class BrawlifyBrawlerStats(
    val winRate: Double = 0.0,
    val pickRate: Double = 0.0,
    val banRate: Double = 0.0,
    val tier: Int = 0
)

data class BrawlifyMap(
    val id: String,
    val name: String,
    val gameMode: String = "",
    val imageUrl: String = "",
    val stats: List<BrawlifyMapBrawlerStats>? = null
)

data class BrawlifyMapDetail(
    val id: String,
    val name: String,
    val gameMode: String = "",
    val brawlerStats: List<BrawlifyMapBrawlerStats> = emptyList()
)

data class BrawlifyMapBrawlerStats(
    val brawler: BrawlifyBrawler? = null,
    val winRate: Double = 0.0,
    val pickRate: Double = 0.0,
    val banRate: Double = 0.0
)