package com.xeriomy.brawldrafter.data.model

import com.google.gson.annotations.SerializedName

/**
 * Represents a Brawl Stars brawler with all relevant metadata.
 * Data is sourced from Brawlify/BSAPI and cached locally.
 */
data class Brawler(
    val id: Int,
    val name: String,
    @SerializedName("class") val brawlerClass: BrawlerClass,
    val rarity: Rarity,
    val hp: Int = 0,
    val damage: Int = 0,
    val range: Int = 0,
    val speed: Int = 0,
    val imageUrl: String = "",
    val counters: List<String> = emptyList(),      // Brawler names this one beats
    val weakTo: List<String> = emptyList(),          // Brawler names that beat this one
    val synergies: List<String> = emptyList(),       // Brawler names that work well with this one
    val bestMaps: List<String> = emptyList(),
    val tier: Int = 0,                               // 1-5 tier rating
    val winRate: Double = 0.0,
    val pickRate: Double = 0.0,
    val banRate: Double = 0.0,
) {
    enum class BrawlerClass {
        DAMAGE_DEALER, TANK, SUPPORT, CONTROLLER, ASSASSIN, MARKSMAN
    }

    enum class Rarity {
        COMMON, RARE, SUPER_RARE, EPIC, MYTHIC, LEGENDARY, CHROMATIC, STARTER
    }
}

/**
 * All known brawler names for OCR fuzzy matching.
 * Updated regularly from API data.
 */
val ALL_BRAWLER_NAMES = listOf(
    "Shelly", "Nita", "Colt", "Bull", "Jessie", "Brock",
    "Dynamike", "Bo", "El Primo", "Barley", "Poco", "Rosa",
    "Rico", "Darryl", "Penny", "Carl", "Pam", "Frank",
    "Mortis", "Tara", "Gene", "Max", "Sprout", "Byron",
    "Squeak", "Tick", "Amber", "Lola", "Colette", "Edgar",
    "Griff", "Grom", "Bonnie", "Fang", "Eve", "Janet",
    "Lou", "Draco", "Mico", "Kenji", "Juju", "Angelo",
    "Pearl", "Berry", "Lily", "Clancy", "Moe", "Melodie",
    "Shade", "Chester", "Gray", "Kit", "Lancelot", " Cordelius",
    "Ruffs", "Buster", "Charli", "Gus", "Dou", "Drew",
    "Joy", "Belle", "Surge", "Chromatic", "Sandy",
    "Leon", "Crow", "Spike", "Bea", "Emz", "Stu",
    "Buzz", "Nani", "Meg", "Chester", "Gale", "Ash"
)