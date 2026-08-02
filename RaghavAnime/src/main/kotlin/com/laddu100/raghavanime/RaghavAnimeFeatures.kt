package com.laddu100.raghavanime

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * RaghavAnimeFeatures — Watch Time Tracker + Anime Recommendations
 *
 * Both features are ON by default and persistent across app restarts.
 *
 * Recommendations logic:
 * - Fetches recommendations from AniList for ALL watched anime (not just top 3)
 * - Aggregates and deduplicates by AniList ID
 * - Sorts by recommendation score (averageScore from AniList)
 * - Caches to persistent storage so recommendations survive app restart
 * - Updates every time user watches a new anime
 * - Reset button clears the cache so next homepage load regenerates fresh
 */
object RaghavAnimeFeatures {

    private const val PREFIX = "raghavanime_feat_"

    fun isEnabled(feature: String): Boolean = getKey<Boolean>(PREFIX + feature) ?: when (feature) {
        "watch_time" -> true
        "recommendations" -> true
        else -> false
    }
    fun setEnabled(feature: String, enabled: Boolean) { setKey(PREFIX + feature, enabled) }

    // ===== Watch Time Tracker =====
    @JsonIgnoreProperties(ignoreUnknown = true)
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
            // Invalidate recommendations cache so next homepage load regenerates with new watch history
            invalidateRecommendationsCache()
        } catch (_: Exception) {}
    }

    fun getWatchHistory(): List<WatchEntry> = try {
        val raw = getKey<String>(PREFIX + "watch_history") ?: return emptyList()
        parseJson(raw)
    } catch (_: Exception) { emptyList() }

    fun getWatchTimeForAnime(anilistId: Int): Long = getWatchHistory().find { it.anilistId == anilistId }?.watchTimeMs ?: 0L

    fun formatWatchTime(ms: Long): String {
        val h = ms / 3600000
        val m = (ms / 60000) % 60
        return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "Just started"
    }

    fun getTotalWatchTime(): Long = getWatchHistory().sumOf { it.watchTimeMs }
    fun getTotalEpisodesWatched(): Int = getWatchHistory().sumOf { it.episodesWatched }
    fun getAnimeWatchedCount(): Int = getWatchHistory().size

    fun resetWatchHistory() {
        try { setKey(PREFIX + "watch_history", emptyList<WatchEntry>().toJson()) } catch (_: Exception) {}
        invalidateRecommendationsCache()
    }

    // ===== Anime Recommendations =====
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SimpleAnime(val title: String, val url: String, val posterUrl: String?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecommendationEntry(
        val id: Int,
        val title: String,
        val posterUrl: String?,
        val score: Int?
    )

    /**
     * Fetches recommendations for a single AniList anime.
     * Uses AniList GraphQL recommendations(sort: RATING_DESC) — these are user-curated
     * recommendations on AniList, sorted by rating (most agreed-upon first).
     */
    private suspend fun fetchRecommendationsForAnime(anilistId: Int): List<RecommendationEntry> {
        return try {
            val query = """
                query (${'$'}id: Int) {
                    Media(id: ${'$'}id, type: ANIME) {
                        recommendations(sort: RATING_DESC, perPage: 20) {
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
            val nodes = response.data?.Media?.recommendations?.nodes ?: emptyList()
            nodes.mapNotNull { node ->
                val media = node.mediaRecommendation ?: return@mapNotNull null
                val id = media.id ?: return@mapNotNull null
                val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
                val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
                RecommendationEntry(id, title, poster, media.averageScore)
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Generates the recommendations list from the ENTIRE watch history.
     *
     * How it works:
     * 1. Reads all watched anime from watch history (up to 100 entries)
     * 2. For each watched anime, fetches its top 20 AniList recommendations
     * 3. Aggregates all recommendations into one list
     * 4. Removes already-watched anime from the recommendations
     * 5. Deduplicates by AniList ID (keeping the one with highest score)
     * 6. Sorts by score (highest first)
     * 7. Takes top 20
     * 8. Caches result to persistent storage
     *
     * This runs in background and is called from getRecommendationsList().
     */
    suspend fun regenerateRecommendations(): List<RecommendationEntry> {
        val history = getWatchHistory()
        if (history.isEmpty()) {
            try { setKey(PREFIX + "rec_cache", emptyList<RecommendationEntry>().toJson()) } catch (_: Exception) {}
            return emptyList()
        }

        val watchedIds = history.map { it.anilistId }.toSet()
        val allRecs = mutableMapOf<Int, RecommendationEntry>()

        // Fetch recommendations for ALL watched anime (cap at 50 to avoid too many API calls)
        for (entry in history.take(50)) {
            val recs = fetchRecommendationsForAnime(entry.anilistId)
            for (rec in recs) {
                if (rec.id in watchedIds) continue
                val existing = allRecs[rec.id]
                if (existing == null || (rec.score ?: 0) > (existing.score ?: 0)) {
                    allRecs[rec.id] = rec
                }
            }
        }

        val result = allRecs.values
            .sortedByDescending { it.score ?: 0 }
            .take(20)

        try { setKey(PREFIX + "rec_cache", result.toJson()) } catch (_: Exception) {}
        return result
    }

    /**
     * Returns the recommendations list.
     * - If cache exists and is not empty, returns cached list (fast, works offline)
     * - If cache is empty, regenerates from watch history
     */
    suspend fun getRecommendationsList(): List<SimpleAnime> {
        val cached = getCachedRecommendations()
        if (cached.isNotEmpty()) {
            return cached.map { SimpleAnime(it.title, "https://graphql.anilist.co/info/${it.id}", it.posterUrl) }
        }
        val recs = regenerateRecommendations()
        return recs.map { SimpleAnime(it.title, "https://graphql.anilist.co/info/${it.id}", it.posterUrl) }
    }

    fun getCachedRecommendations(): List<RecommendationEntry> = try {
        val raw = getKey<String>(PREFIX + "rec_cache") ?: return emptyList()
        parseJson(raw)
    } catch (_: Exception) { emptyList() }

    fun invalidateRecommendationsCache() {
        try { setKey(PREFIX + "rec_cache", emptyList<RecommendationEntry>().toJson()) } catch (_: Exception) {}
    }

    /**
     * Reset recommendations — clears the cache completely.
     * Next time the homepage RECOMMEND section loads, it will regenerate from watch history.
     */
    fun resetRecommendations() {
        invalidateRecommendationsCache()
    }

    private suspend fun anilistQuery(query: String, variables: Map<String, Any?>): String {
        val requestData = mapOf("query" to query, "variables" to variables).toJson()
            .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        return app.post(
            "https://graphql.anilist.co",
            headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
            requestBody = requestData
        ).text
    }
}
