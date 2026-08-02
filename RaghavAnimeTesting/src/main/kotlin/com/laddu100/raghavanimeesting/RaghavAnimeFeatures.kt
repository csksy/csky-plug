package com.laddu100.raghavanimeesting

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

    // ===== Watch Time Tracker =====
    data class WatchEntry(val anilistId: Int, val title: String, val posterUrl: String?, val watchTimeMs: Long, val episodesWatched: Int, val lastWatched: Long)

    fun recordWatchTime(anilistId: Int, title: String, posterUrl: String?, durationMs: Long) {
        try {
            val list = getWatchHistory().toMutableList()
            val existing = list.find { it.anilistId == anilistId }
            if (existing != null) { list.remove(existing); list.add(existing.copy(watchTimeMs = existing.watchTimeMs + durationMs, episodesWatched = existing.episodesWatched + 1, lastWatched = System.currentTimeMillis())) }
            else { list.add(WatchEntry(anilistId, title, posterUrl, durationMs, 1, System.currentTimeMillis())) }
            setKey(PREFIX + "watch_history", list.take(100).toJson())
        } catch (_: Exception) {}
    }
    fun getWatchHistory(): List<WatchEntry> = try { val raw = getKey<String>(PREFIX + "watch_history") ?: return emptyList(); parseJson(raw) } catch (_: Exception) { emptyList() }
    fun getWatchTimeForAnime(anilistId: Int): Long = getWatchHistory().find { it.anilistId == anilistId }?.watchTimeMs ?: 0L
    fun formatWatchTime(ms: Long): String { val h = ms / 3600000; val m = (ms / 60000) % 60; return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "Just started" }
    fun getTotalWatchTime(): Long = getWatchHistory().sumOf { it.watchTimeMs }
    fun getTotalEpisodesWatched(): Int = getWatchHistory().sumOf { it.episodesWatched }
    fun getAnimeWatchedCount(): Int = getWatchHistory().size
    fun resetWatchHistory() { try { setKey(PREFIX + "watch_history", emptyList<WatchEntry>().toJson()) } catch (_: Exception) {} }

    // ===== Custom Source Profiles =====
    data class CustomProfile(val name: String, val sources: List<String>, val enabled: Boolean = false)
    fun getCustomProfiles(): List<CustomProfile> = try { val raw = getKey<String>(PREFIX + "custom_profiles") ?: return emptyList(); parseJson(raw) } catch (_: Exception) { emptyList() }
    fun saveCustomProfiles(profiles: List<CustomProfile>) { setKey(PREFIX + "custom_profiles", profiles.toJson()) }
    fun getActiveCustomProfile(): CustomProfile? = getCustomProfiles().find { it.enabled }
    fun setActiveProfile(profileName: String?) { saveCustomProfiles(getCustomProfiles().map { p -> p.copy(enabled = p.name == profileName) }) }
    fun isCustomProfileActive(): Boolean = getCustomProfiles().any { it.enabled }
    fun shouldRunSource(sourceKey: String): Boolean { val p = getActiveCustomProfile(); if (p != null) return sourceKey in p.sources; return com.laddu100.raghavanimeesting.settings.SettingsFragment.isEnabled(sourceKey) }

    // ===== Recommendations =====
    @JsonIgnoreProperties(ignoreUnknown = true) data class AniListRecommendation(val id: Int? = null, val title: String? = null, val posterUrl: String? = null, val score: Double? = null)
    private var cachedRecommendations: List<SimpleAnime>? = null

    suspend fun fetchRecommendations(anilistId: Int): List<AniListRecommendation> {
        return try {
            val query = "query (${'$'}id: Int) { Media(id: ${'$'}id, type: ANIME) { recommendations(sort: RATING_DESC, perPage: 12) { nodes { mediaRecommendation { id title { english romaji } coverImage { extraLarge large } averageScore } } } } }"
            val responseText = anilistQuery(query, mapOf("id" to anilistId))
            val response = parseJson<AniListResponse>(responseText)
            val recs = response.data?.Media?.recommendations?.nodes ?: emptyList()
            recs.mapNotNull { node -> val media = node.mediaRecommendation ?: return@mapNotNull null; val id = media.id ?: return@mapNotNull null; val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null; AniListRecommendation(id, title, media.coverImage?.extraLarge ?: media.coverImage?.large, media.averageScore?.toDouble()) }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun getRecommendationsList(): List<SimpleAnime> {
        cachedRecommendations?.let { return it }
        val history = getWatchHistory().sortedByDescending { it.lastWatched }.take(3)
        if (history.isEmpty()) return emptyList()
        val allRecs = mutableListOf<AniListRecommendation>()
        val watchedIds = history.map { it.anilistId }.toSet()
        for (entry in history) { allRecs.addAll(fetchRecommendations(entry.anilistId).filter { it.id !in watchedIds }) }
        val result = allRecs.distinctBy { it.id }.take(20).mapNotNull { rec -> val id = rec.id ?: return@mapNotNull null; val title = rec.title ?: return@mapNotNull null; SimpleAnime(title, "https://graphql.anilist.co/info/$id", rec.posterUrl) }
        cachedRecommendations = result; return result
    }
    fun resetRecommendations() { cachedRecommendations = null; try { setKey(PREFIX + "rec_cache", null as Any?) } catch (_: Exception) {} }

    // ===== Discover Anime =====
    data class SimpleAnime(val title: String, val url: String, val posterUrl: String?)
    data class DiscoverResult(val id: Int, val title: String, val posterUrl: String?, val score: Double?, val year: Int?, val genres: List<String>?, val format: String?, val episodes: Int?, val synopsis: String?)
    data class DiscoverPage(val results: List<DiscoverResult>, val currentPage: Int, val hasNextPage: Boolean, val lastPage: Int)
    data class AnimeDetail(val id: Int, val title: String, val romajiTitle: String?, val posterUrl: String?, val score: Double?, val year: Int?, val genres: List<String>?, val format: String?, val status: String?, val episodes: Int?, val synopsis: String?)

    val availableGenres = listOf("Any", "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mystery", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller")
    val availableSorts = listOf("POPULARITY_DESC" to "Most Popular", "SCORE_DESC" to "Highest Rated", "START_DATE_DESC" to "Newest", "TRENDING_DESC" to "Trending", "FAVOURITES_DESC" to "Most Favourited")

    suspend fun searchSuggestions(query: String): List<DiscoverResult> {
        return try {
            val query2 = "query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) { Page(page: ${'$'}page, perPage: ${'$'}perPage) { media(type: ANIME, search: ${'$'}search, sort: SEARCH_MATCH) { id title { english romaji } coverImage { extraLarge large } averageScore seasonYear format episodes } } }"
            val responseText = anilistQuery(query2, mapOf("search" to query, "page" to 1, "perPage" to 10))
            val mediaList = parseJson<AniListResponse>(responseText).data?.Page?.media ?: emptyList()
            mediaList.mapNotNull { m -> val id = m.id ?: return@mapNotNull null; val t = m.title?.english ?: m.title?.romaji ?: return@mapNotNull null; DiscoverResult(id, t, m.coverImage?.extraLarge ?: m.coverImage?.large, m.averageScore?.toDouble(), m.seasonYear, null, m.format, m.episodes, null) }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun discoverAnime(query: String? = null, genres: List<String> = emptyList(), sortBy: String = "POPULARITY_DESC", page: Int = 1): DiscoverPage {
        return try {
            val gqlQuery = "query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}search: String, ${'$'}genre: [String], ${'$'}sort: [MediaSort]) { Page(page: ${'$'}page, perPage: ${'$'}perPage) { pageInfo { currentPage hasNextPage lastPage } media(type: ANIME, search: ${'$'}search, genre_in: ${'$'}genre, sort: ${'$'}sort, isAdult: false) { id title { english romaji } coverImage { extraLarge large } averageScore genres seasonYear format episodes description(asHtml: false) } } }"
            val variables = mutableMapOf<String, Any?>("page" to page, "perPage" to 10, "sort" to listOf(sortBy))
            if (genres.isNotEmpty()) variables["genre"] = genres
            if (query != null && query.isNotBlank()) variables["search"] = query
            val responseText = anilistQuery(gqlQuery, variables)
            val response = parseJson<AniListResponse>(responseText)
            val pageInfo = response.data?.Page?.pageInfo
            val mediaList = response.data?.Page?.media ?: emptyList()
            val results = mediaList.mapNotNull { m -> val id = m.id ?: return@mapNotNull null; val t = m.title?.english ?: m.title?.romaji ?: return@mapNotNull null; DiscoverResult(id, t, m.coverImage?.extraLarge ?: m.coverImage?.large, m.averageScore?.toDouble(), m.seasonYear, m.genres, m.format, m.episodes, m.description?.replace(Regex("<[^>]*>"), "")) }
            DiscoverPage(results, pageInfo?.currentPage ?: page, pageInfo?.hasNextPage ?: false, pageInfo?.lastPage ?: page)
        } catch (e: Exception) { DiscoverPage(emptyList(), page, false, page) }
    }

    suspend fun fetchAnimeDetail(anilistId: Int): AnimeDetail? {
        return try {
            val query = "query (${'$'}id: Int) { Media(id: ${'$'}id, type: ANIME) { id title { english romaji } coverImage { extraLarge large } averageScore seasonYear genres format status episodes description(asHtml: false) } }"
            val media = parseJson<AniListResponse>(anilistQuery(query, mapOf("id" to anilistId))).data?.Media ?: return null
            AnimeDetail(media.id ?: return null, media.title?.english ?: media.title?.romaji ?: return null, media.title?.romaji, media.coverImage?.extraLarge ?: media.coverImage?.large, media.averageScore?.toDouble(), media.seasonYear, media.genres, media.format, media.status, media.episodes, media.description?.replace(Regex("<[^>]*>"), ""))
        } catch (_: Exception) { null }
    }

    // ===== Advanced Search =====
    val availableSeasons = listOf("Any", "WINTER", "SPRING", "SUMMER", "FALL")
    val availableFormats = listOf("Any", "TV", "MOVIE", "OVA", "ONA", "SPECIAL", "MUSIC")
    val availableStatus = listOf("Any", "FINISHED", "RELEASING", "NOT_YET_RELEASED", "CANCELLED")

    suspend fun fetchGenres(): List<String> {
        return try {
            val query = "query { GenreCollection }"
            val responseText = anilistQuery(query, emptyMap())
            val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(responseText)
            val genres = json.get("data")?.get("GenreCollection") ?: return emptyList()
            genres.map { it.asText() }.filter { it != "Hentai" }
        } catch (_: Exception) { emptyList() }
    }

    data class TagInfo(val name: String, val category: String)
    suspend fun fetchTags(): List<TagInfo> {
        return try {
            val query = "query { MediaTagCollection { name category } }"
            val responseText = anilistQuery(query, emptyMap())
            val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(responseText)
            val tags = json.get("data")?.get("MediaTagCollection") ?: return emptyList()
            tags.map { TagInfo(it.get("name").asText(), it.get("category")?.asText() ?: "") }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun advancedSearch(
        search: String? = null, genres: List<String> = emptyList(), tags: List<String> = emptyList(),
        year: Int? = null, season: String? = null, formats: List<String> = emptyList(),
        status: String? = null, sortBy: String = "POPULARITY_DESC", page: Int = 1
    ): DiscoverPage {
        return try {
            val gqlQuery = """query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}search: String, ${'$'}genre: [String], ${'$'}tag: [String], ${'$'}sort: [MediaSort], ${'$'}year: Int, ${'$'}season: MediaSeason, ${'$'}format: [MediaFormat], ${'$'}status: MediaStatus) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    pageInfo { currentPage hasNextPage lastPage }
                    media(type: ANIME, search: ${'$'}search, genre_in: ${'$'}genre, tag_in: ${'$'}tag, sort: ${'$'}sort, seasonYear: ${'$'}year, season: ${'$'}season, format_in: ${'$'}format, status: ${'$'}status, isAdult: false) {
                        id title { english romaji } coverImage { extraLarge large } averageScore genres seasonYear format episodes description(asHtml: false)
                    }
                }
            }""".trimIndent()
            val variables = mutableMapOf<String, Any?>("page" to page, "perPage" to 10, "sort" to listOf(sortBy))
            if (search?.isNotBlank() == true) variables["search"] = search
            if (genres.isNotEmpty()) variables["genre"] = genres
            if (tags.isNotEmpty()) variables["tag"] = tags
            if (year != null) variables["year"] = year
            if (season != null && season != "Any") variables["season"] = season
            if (formats.isNotEmpty()) variables["format"] = formats
            if (status != null && status != "Any") variables["status"] = status
            val responseText = anilistQuery(gqlQuery, variables)
            val response = parseJson<AniListResponse>(responseText)
            val pageInfo = response.data?.Page?.pageInfo
            val mediaList = response.data?.Page?.media ?: emptyList()
            val results = mediaList.mapNotNull { m -> val id = m.id ?: return@mapNotNull null; val t = m.title?.english ?: m.title?.romaji ?: return@mapNotNull null; DiscoverResult(id, t, m.coverImage?.extraLarge ?: m.coverImage?.large, m.averageScore?.toDouble(), m.seasonYear, m.genres, m.format, m.episodes, m.description?.replace(Regex("<[^>]*>"), "")) }
            DiscoverPage(results, pageInfo?.currentPage ?: page, pageInfo?.hasNextPage ?: false, pageInfo?.lastPage ?: page)
        } catch (e: Exception) { DiscoverPage(emptyList(), page, false, page) }
    }

    private suspend fun anilistQuery(query: String, variables: Map<String, Any?>): String {
        val requestData = mapOf("query" to query, "variables" to variables).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        return app.post("https://graphql.anilist.co", headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"), requestBody = requestData).text
    }
}
