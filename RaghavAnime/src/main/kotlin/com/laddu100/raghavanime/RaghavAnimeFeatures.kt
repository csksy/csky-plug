package com.laddu100.raghavanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

object RaghavAnimeFeatures {

    private const val PREFIX = "raghavanime_feat_"

    fun isEnabled(feature: String): Boolean = getKey<Boolean>(PREFIX + feature) ?: when (feature) {
        "watch_time" -> true
        "recommendations" -> true
        else -> false
    }
    fun setEnabled(feature: String, enabled: Boolean) { setKey(PREFIX + feature, enabled) }

    // ============================================================
    // FEATURE: Watch Time Tracker
    // ============================================================

    data class WatchEntry(
        val anilistId: Int,
        val title: String,
        val posterUrl: String?,
        val watchTimeMs: Long,
        val episodesWatched: Int,
        val lastWatched: Long
    )

    fun recordWatchTime(anilistId: Int, title: String, posterUrl: String?, durationMs: Long) {
        try {
            val list = getWatchHistory().toMutableList()
            val existing = list.find { it.anilistId == anilistId }
            if (existing != null) {
                list.remove(existing)
                list.add(existing.copy(
                    watchTimeMs = existing.watchTimeMs + durationMs,
                    episodesWatched = existing.episodesWatched + 1,
                    lastWatched = System.currentTimeMillis()
                ))
            } else {
                list.add(WatchEntry(anilistId, title, posterUrl, durationMs, 1, System.currentTimeMillis()))
            }
            setKey(PREFIX + "watch_history", list.take(100).toJson())
        } catch (_: Exception) {}
    }

    fun getWatchHistory(): List<WatchEntry> {
        return try {
            val raw = getKey<String>(PREFIX + "watch_history") ?: return emptyList()
            parseJson(raw)
        } catch (_: Exception) { emptyList() }
    }

    fun getWatchTimeForAnime(anilistId: Int): Long {
        return getWatchHistory().find { it.anilistId == anilistId }?.watchTimeMs ?: 0L
    }

    fun formatWatchTime(ms: Long): String {
        val hours = ms / (1000 * 60 * 60)
        val minutes = (ms / (1000 * 60)) % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "Just started"
        }
    }

    fun getTotalWatchTime(): Long {
        return getWatchHistory().sumOf { it.watchTimeMs }
    }

    fun getTotalEpisodesWatched(): Int {
        return getWatchHistory().sumOf { it.episodesWatched }
    }

    fun getAnimeWatchedCount(): Int {
        return getWatchHistory().size
    }

    fun getMostWatchedAnime(): WatchEntry? {
        return getWatchHistory().maxByOrNull { it.watchTimeMs }
    }

    data class SimpleAnime(val title: String, val url: String, val posterUrl: String?)

    // ============================================================
    // FEATURE: Custom Source Profiles
    // ============================================================

    data class CustomProfile(
        val name: String,
        val sources: List<String>,
        val enabled: Boolean = false
    )

    fun getCustomProfiles(): List<CustomProfile> {
        return try {
            val raw = getKey<String>(PREFIX + "custom_profiles") ?: return emptyList()
            parseJson(raw)
        } catch (_: Exception) { emptyList() }
    }

    fun saveCustomProfiles(profiles: List<CustomProfile>) {
        setKey(PREFIX + "custom_profiles", profiles.toJson())
    }

    fun getActiveCustomProfile(): CustomProfile? {
        return getCustomProfiles().find { it.enabled }
    }

    fun setActiveProfile(profileName: String?) {
        val profiles = getCustomProfiles().map { p ->
            p.copy(enabled = p.name == profileName)
        }
        saveCustomProfiles(profiles)
    }

    fun isCustomProfileActive(): Boolean {
        return getCustomProfiles().any { it.enabled }
    }

    fun shouldRunSource(sourceKey: String): Boolean {
        val profile = getActiveCustomProfile()
        if (profile != null) {
            return sourceKey in profile.sources
        }
        return com.laddu100.raghavanime.settings.SettingsFragment.isEnabled(sourceKey)
    }

    // ============================================================
    // FEATURE: Anime Recommendation Engine
    // ============================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListRecommendation(
        val id: Int? = null,
        val title: String? = null,
        val posterUrl: String? = null,
        val score: Double? = null
    )

    private var cachedRecommendations: List<SimpleAnime>? = null

    suspend fun fetchRecommendations(anilistId: Int): List<AniListRecommendation> {
        return try {
            val query = """
                query (${'$'}id: Int) {
                    Media(id: ${'$'}id, type: ANIME) {
                        recommendations(sort: RATING_DESC, perPage: 12) {
                            nodes {
                                mediaRecommendation {
                                    id
                                    title { english romaji }
                                    coverImage { extraLarge large }
                                    averageScore
                                }
                            }
                        }
                    }
                }
            """.trimIndent()
            val responseText = anilistQuery(query, mapOf("id" to anilistId))
            val response = parseJson<AniListResponse>(responseText)
            val recs = response.data?.Media?.recommendations?.nodes ?: emptyList()
            recs.mapNotNull { node ->
                val media = node.mediaRecommendation ?: return@mapNotNull null
                val id = media.id ?: return@mapNotNull null
                val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
                AniListRecommendation(
                    id = id,
                    title = title,
                    posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large,
                    score = media.averageScore?.toDouble()
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun getRecommendationsList(): List<SimpleAnime> {
        cachedRecommendations?.let { return it }

        val history = getWatchHistory().sortedByDescending { it.lastWatched }.take(3)
        if (history.isEmpty()) return emptyList()

        val allRecs = mutableListOf<AniListRecommendation>()
        val watchedIds = history.map { it.anilistId }.toSet()

        for (entry in history) {
            val recs = fetchRecommendations(entry.anilistId)
            allRecs.addAll(recs.filter { it.id !in watchedIds })
        }

        val result = allRecs.distinctBy { it.id }.take(20).mapNotNull { rec ->
            val id = rec.id ?: return@mapNotNull null
            val title = rec.title ?: return@mapNotNull null
            SimpleAnime(title, "https://graphql.anilist.co/info/$id", rec.posterUrl)
        }

        cachedRecommendations = result
        return result
    }

    fun resetRecommendations() {
        cachedRecommendations = null
    }

    // ============================================================
    // FEATURE: Discover New Anime
    // ============================================================

    suspend fun discoverAnime(query: String? = null, genre: String? = null, sortBy: String = "POPULARITY_DESC"): List<SimpleAnime> {
        return try {
            val gqlQuery = """
                query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}search: String, ${'$'}genre: String, ${'$'}sort: [MediaSort]) {
                    Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                        media(type: ANIME, search: ${'$'}search, genre_in: ${'$'}genre, sort: ${'$'}sort) {
                            id
                            title { english romaji }
                            coverImage { extraLarge large }
                            averageScore
                            genres
                            seasonYear
                            format
                            episodes
                        }
                    }
                }
            """.trimIndent()

            val variables = mutableMapOf<String, Any?>(
                "page" to 1,
                "perPage" to 20,
                "sort" to listOf(sortBy)
            )
            if (query != null && query.isNotBlank()) variables["search"] = query
            if (genre != null && genre != "Any") {
                variables["genre"] = genre
            }

            val responseText = anilistQuery(gqlQuery, variables)
            val response = parseJson<AniListResponse>(responseText)
            val mediaList = response.data?.Page?.media ?: emptyList()

            mediaList.mapNotNull { media ->
                val id = media.id ?: return@mapNotNull null
                val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
                SimpleAnime(title, "https://graphql.anilist.co/info/$id", media.coverImage?.extraLarge ?: media.coverImage?.large)
            }
        } catch (_: Exception) { emptyList() }
    }

    val availableGenres = listOf(
        "Any", "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror",
        "Mystery", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller"
    )

    val availableSorts = listOf(
        "POPULARITY_DESC" to "Most Popular",
        "SCORE_DESC" to "Highest Rated",
        "START_DATE_DESC" to "Newest",
        "TRENDING_DESC" to "Trending",
        "FAVOURITES_DESC" to "Most Favourited"
    )

    private suspend fun anilistQuery(query: String, variables: Map<String, Any?>): String {
        val requestData = mapOf(
            "query" to query,
            "variables" to variables
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        return app.post(
            "https://graphql.anilist.co",
            headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
            requestBody = requestData
        ).text
    }
}
