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

    fun isEnabled(feature: String): Boolean = getKey<Boolean>(PREFIX + feature) ?: false
    fun setEnabled(feature: String, enabled: Boolean) { setKey(PREFIX + feature, enabled) }

    // ============================================================
    // FEATURE 6: Watch Time Tracker
    // ============================================================

    data class WatchEntry(val anilistId: Int, val title: String, val posterUrl: String?, val watchTimeMs: Long, val episodesWatched: Int, val lastWatched: Long)

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

    data class SimpleAnime(val title: String, val url: String, val posterUrl: String?)

    fun getWatchHistoryList(): List<SimpleAnime> {
        return getWatchHistory().sortedByDescending { it.lastWatched }.map { entry ->
            SimpleAnime(entry.title, "https://graphql.anilist.co/info/${entry.anilistId}", entry.posterUrl)
        }
    }

    // ============================================================
    // FEATURE 7: Dual/Sub Badge System
    // ============================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceAvailability(
        val hasSub: Boolean = false,
        val hasDub: Boolean = false
    )

    private val availabilityCache = mutableMapOf<Int, SourceAvailability>()

    fun recordAvailability(anilistId: Int, hasSub: Boolean, hasDub: Boolean) {
        availabilityCache[anilistId] = SourceAvailability(hasSub, hasDub)
        try {
            val map = mutableMapOf<String, Boolean>()
            availabilityCache.forEach { (k, v) ->
                map["${k}_sub"] = v.hasSub
                map["${k}_dub"] = v.hasDub
            }
            setKey(PREFIX + "availability", map.toJson())
        } catch (_: Exception) {}
    }

    fun getAvailability(anilistId: Int): SourceAvailability {
        availabilityCache[anilistId]?.let { return it }
        return try {
            val raw = getKey<String>(PREFIX + "availability") ?: return SourceAvailability()
            val map = parseJson<Map<String, Boolean>>(raw)
            SourceAvailability(
                hasSub = map["${anilistId}_sub"] ?: false,
                hasDub = map["${anilistId}_dub"] ?: false
            )
        } catch (_: Exception) { SourceAvailability() }
    }

    // ============================================================
    // FEATURE 9: Custom Source Priority Profiles
    // ============================================================

    data class SourceProfile(
        val name: String,
        val providerOrder: List<String>,
        val description: String
    )

    val defaultProfiles = listOf(
        SourceProfile("Balanced", listOf("miruro","anichan","aninami","enma","animo","anikage","anineko","anisuge","aniwaves","anikai","anidb","twodhive","anikoto","anidap","senshi","anidao"), "All sources in default order"),
        SourceProfile("Fastest", listOf("miruro","anichan","aninami","animo","enma","anikage","anineko","anisuge","aniwaves","anikai","anidb","twodhive","anikoto","anidap","senshi","anidao"), "Prioritize fastest responding sources"),
        SourceProfile("Best Quality", listOf("miruro","enma","animo","anichan","aninami","anikage","anineko","anisuge","aniwaves","anikai","anidb","twodhive","anikoto","anidap","senshi","anidao"), "Prioritize 1080p sources"),
        SourceProfile("Hindi Dub", listOf("anidap","senshi","miruro","anichan","aninami","enma","animo","anikage","anineko","anisuge","aniwaves","anikai","anidb","twodhive","anikoto","anidao"), "Prioritize Hindi dub sources")
    )

    fun getActiveProfile(): String {
        return getKey<String>(PREFIX + "active_profile") ?: "Balanced"
    }

    fun setActiveProfile(name: String) {
        setKey(PREFIX + "active_profile", name)
    }

    fun getProfileOrder(): List<String> {
        val name = getActiveProfile()
        return defaultProfiles.find { it.name == name }?.providerOrder ?: defaultProfiles[0].providerOrder
    }

    // ============================================================
    // FEATURE 10: Anime Recommendation Engine
    // ============================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListRecommendation(
        val id: Int? = null,
        val title: String? = null,
        val posterUrl: String? = null,
        val score: Double? = null,
        val reason: String? = null
    )

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
            val variables = mapOf("id" to anilistId)
            val responseText = anilistQuery(query, variables)
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
                    score = media.averageScore?.toDouble(),
                    reason = "Recommended based on your anime"
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun getRecommendationsList(): List<SimpleAnime> {
        val history = getWatchHistory().sortedByDescending { it.lastWatched }.take(3)
        val allRecs = mutableListOf<AniListRecommendation>()
        val watchedIds = history.map { it.anilistId }.toSet()

        for (entry in history) {
            val recs = fetchRecommendations(entry.anilistId)
            allRecs.addAll(recs.filter { it.id !in watchedIds })
        }

        return allRecs.distinctBy { it.id }.take(20).mapNotNull { rec ->
            val id = rec.id ?: return@mapNotNull null
            val title = rec.title ?: return@mapNotNull null
            SimpleAnime(title, "https://graphql.anilist.co/info/$id", rec.posterUrl)
        }
    }

    // Helper to run AniList queries
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
