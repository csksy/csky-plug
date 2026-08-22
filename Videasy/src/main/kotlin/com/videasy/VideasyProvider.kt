package com.videasy

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class VideasyProvider : MainAPI() {
    override var mainUrl = "https://player.videasy.to"
    override var name = "Videasy"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbApi = "https://db.speedracelight.com/3"
    private val sourceApi = "https://api.speedracelight.com"
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json",
        "Origin" to "https://player.videasy.to",
        "Referer" to "https://player.videasy.to/",
    )

    private val imageBase = "https://image.tmdb.org/t/p"

    override val mainPage = mainPageOf(
        "$tmdbApi/trending/movie/week" to "Trending Movies",
        "$tmdbApi/trending/tv/week" to "Trending TV Shows",
        "$tmdbApi/discover/movie?sort_by=popularity.desc" to "Popular Movies",
        "$tmdbApi/discover/tv?sort_by=popularity.desc" to "Popular TV Shows",
        "$tmdbApi/movie/top_rated" to "Top Rated Movies",
        "$tmdbApi/tv/top_rated" to "Top Rated TV Shows",
    )

    private fun imageUrl(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank()) return null
        return "$imageBase/$size$path"
    }

    private fun mediaToSearchResponse(item: TMDBItem, mediaType: String): SearchResponse? {
        val id = item.id ?: return null
        val title = item.title ?: item.name ?: return null
        val poster = imageUrl(item.posterPath)
        val type = when (mediaType) {
            "tv" -> TvType.TvSeries
            else -> TvType.Movie
        }
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", type) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/movie/$id", type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageParam = if (request.data.contains("?")) "&page=$page" else "?page=$page"
        val url = request.data + pageParam
        val home = try {
            val responseText = app.get(url, headers = apiHeaders, timeout = 15_000L).text
            val response = parseJson<TMDBResponse>(responseText)
            val mediaType = if (request.data.contains("/movie")) "movie" else "tv"
            response.results?.mapNotNull { mediaToSearchResponse(it, mediaType) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return try {
            val url = "$tmdbApi/search/multi?query=$encoded"
            val responseText = app.get(url, headers = apiHeaders, timeout = 15_000L).text
            val response = parseJson<TMDBResponse>(responseText)
            response.results?.mapNotNull { item ->
                val mediaType = item.mediaType ?: return@mapNotNull null
                if (mediaType == "person") return@mapNotNull null
                mediaToSearchResponse(item, mediaType)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val typeMatch = Regex("""/(movie|tv|anime)/(\d+)""").find(url)
        val mediaType = typeMatch?.groupValues?.get(1) ?: return null
        val tmdbId = typeMatch.groupValues?.get(2)?.toIntOrNull() ?: return null

        return when (mediaType) {
            "movie" -> loadMovie(tmdbId, url)
            "tv" -> loadTV(tmdbId, url)
            "anime" -> loadAnime(tmdbId, url)
            else -> null
        }
    }

    private suspend fun loadMovie(tmdbId: Int, url: String): LoadResponse? {
        val detailsUrl = "$tmdbApi/movie/$tmdbId?append_to_response=external_ids"
        val details = try {
            parseJson<TMDBMovieDetails>(app.get(detailsUrl, headers = apiHeaders, timeout = 15_000L).text)
        } catch (e: Exception) { return null }

        val title = details.title ?: details.originalTitle ?: "Unknown"
        val data = mapOf(
            "type" to "movie",
            "tmdbId" to tmdbId,
            "title" to title,
            "year" to details.releaseDate?.substring(0, 4)?.toIntOrNull(),
            "imdbId" to details.imdbId,
        ).toJson()

        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = imageUrl(details.posterPath)
            this.backgroundPosterUrl = imageUrl(details.backdropPath, "original")
            this.year = details.releaseDate?.substring(0, 4)?.toIntOrNull()
            this.plot = details.overview
            this.tags = details.genres?.mapNotNull { it.name } ?: emptyList()
            this.duration = details.runtime
            this.score = details.voteAverage?.let { Score.from10((it / 2).toString()) }
            if (details.imdbId != null) addImdbId(details.imdbId)
        }
    }

    private suspend fun loadTV(tmdbId: Int, url: String): LoadResponse? {
        val detailsUrl = "$tmdbApi/tv/$tmdbId?append_to_response=external_ids"
        val details = try {
            parseJson<TMDBTVDetails>(app.get(detailsUrl, headers = apiHeaders, timeout = 15_000L).text)
        } catch (e: Exception) { return null }

        val title = details.name ?: details.originalName ?: "Unknown"
        val imdbId = details.externalIds?.imdbId
        val episodes = mutableListOf<Episode>()

        for (season in details.seasons ?: emptyList()) {
            val seasonNum = season.seasonNumber ?: continue
            if (seasonNum == 0) continue
            val seasonDetails = try {
                parseJson<TMDBSeasonDetails>(app.get("$tmdbApi/tv/$tmdbId/season/$seasonNum", headers = apiHeaders, timeout = 15_000L).text)
            } catch (e: Exception) { null } ?: continue

            for (ep in seasonDetails.episodes ?: emptyList()) {
                val epNum = ep.episodeNumber ?: continue
                val data = mapOf(
                    "type" to "tv",
                    "tmdbId" to tmdbId,
                    "title" to title,
                    "year" to details.firstAirDate?.substring(0, 4)?.toIntOrNull(),
                    "imdbId" to imdbId,
                    "season" to seasonNum,
                    "episode" to epNum,
                ).toJson()
                episodes.add(newEpisode(data) {
                    this.name = ep.name ?: "S${seasonNum}E$epNum"
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = imageUrl(ep.stillPath)
                    this.description = ep.overview
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = imageUrl(details.posterPath)
            this.backgroundPosterUrl = imageUrl(details.backdropPath, "original")
            this.year = details.firstAirDate?.substring(0, 4)?.toIntOrNull()
            this.plot = details.overview
            this.tags = details.genres?.mapNotNull { it.name } ?: emptyList()
            this.score = details.voteAverage?.let { Score.from10((it / 2).toString()) }
            if (imdbId != null) addImdbId(imdbId)
        }
    }

    private suspend fun loadAnime(anilistId: Int, url: String): LoadResponse? {
        val query = """
            query (${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    id
                    title { english romaji }
                    coverImage { extraLarge large }
                    bannerImage
                    description(asHtml: false)
                    episodes
                    status
                    seasonYear
                    averageScore
                    genres
                }
            }
        """.trimIndent()

        val requestData = mapOf("query" to query, "variables" to mapOf("id" to anilistId))
            .toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        val responseText = try {
            app.post("https://graphql.anilist.co",
                headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
                requestBody = requestData, timeout = 15_000L).text
        } catch (e: Exception) { return null }

        val media = parseJson<AniListResponse>(responseText).data?.Media ?: return null
        val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
        val totalEps = media.episodes ?: 1

        val episodes = mutableListOf<Episode>()
        for (i in 1..totalEps) {
            val data = mapOf("type" to "anime", "anilistId" to anilistId, "title" to title,
                "year" to media.seasonYear, "episode" to i).toJson()
            episodes.add(newEpisode(data) {
                this.name = "Episode $i"
                this.episode = i
            })
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            this.backgroundPosterUrl = media.bannerImage
            this.year = media.seasonYear
            this.plot = media.description?.replace(Regex("<[^>]*>"), "")
            this.tags = media.genres ?: emptyList()
            this.score = media.averageScore?.let { Score.from10((it / 10).toString()) }
            this.showStatus = when (media.status) {
                "RELEASING" -> ShowStatus.Ongoing
                "FINISHED" -> ShowStatus.Completed
                else -> null
            }
            addAniListId(anilistId)
        }
    }

    private val providers = listOf(
        "cdn" to "Videasy",
        "vsrc" to "Neon",
        "m4uhd" to "Breach",
        "downloader2" to "Cypher",
        "hdmovie" to "Vyse",
        "superflix" to "Raze",
        "lamovie" to "Omen",
        "meine" to "Killjoy",
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = try { parseJson<Map<String, Any?>>(data) } catch (e: Exception) { return false }

        val type = parsed["type"] as? String ?: return false
        val tmdbId = (parsed["tmdbId"] as? Number)?.toInt()
        val anilistId = (parsed["anilistId"] as? Number)?.toInt()
        val title = parsed["title"] as? String ?: ""
        val year = (parsed["year"] as? Number)?.toInt()
        val imdbId = parsed["imdbId"] as? String
        val season = (parsed["season"] as? Number)?.toInt()
        val episode = (parsed["episode"] as? Number)?.toInt() ?: 1

        when (type) {
            "movie" -> resolveMovieSources(tmdbId ?: return false, title, year, imdbId, callback)
            "tv" -> resolveTVSources(tmdbId ?: return false, title, year, imdbId, season ?: 1, episode, callback)
            "anime" -> resolveAnimeSources(anilistId ?: return false, title, year, episode, callback)
        }

        return true
    }

    private suspend fun resolveMovieSources(tmdbId: Int, title: String, year: Int?, imdbId: String?, callback: (ExtractorLink) -> Unit) {
        val params = mutableMapOf(
            "title" to title,
            "mediaType" to "Movie",
            "tmdbId" to tmdbId.toString(),
        )
        if (year != null) params["year"] = year.toString()
        if (imdbId != null) params["imdbId"] = imdbId

        for ((provider, label) in providers) {
            try {
                val sources = fetchSourcesWithSeed(provider, params, tmdbId)
                if (sources != null) {
                    Log.d("Videasy", "Provider $provider: ${sources.size} sources")
                    emitSources(sources, label, callback)
                }
            } catch (e: Exception) {
                Log.d("Videasy", "Provider $provider: ${e.message}")
            }
        }
    }

    private suspend fun resolveTVSources(tmdbId: Int, title: String, year: Int?, imdbId: String?, season: Int, episode: Int, callback: (ExtractorLink) -> Unit) {
        val params = mutableMapOf(
            "title" to title,
            "mediaType" to "TV",
            "tmdbId" to tmdbId.toString(),
            "seasonId" to season.toString(),
            "episodeId" to episode.toString(),
        )
        if (year != null) params["year"] = year.toString()
        if (imdbId != null) params["imdbId"] = imdbId

        for ((provider, label) in providers) {
            try {
                val sources = fetchSourcesWithSeed(provider, params, tmdbId)
                if (sources != null) {
                    emitSources(sources, "$label S${season}E$episode", callback)
                }
            } catch (e: Exception) {
                Log.d("Videasy", "Provider $provider: ${e.message}")
            }
        }
    }

    private suspend fun resolveAnimeSources(anilistId: Int, title: String, year: Int?, episode: Int, callback: (ExtractorLink) -> Unit) {
        try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val url = "$sourceApi/hianime/sources-with-title?title=$encodedTitle&year=${year ?: ""}&episodeId=$episode"
            val responseText = app.get(url, headers = apiHeaders, timeout = 15_000L).text
            val response = parseJson<HianimeResponse>(responseText)
            val sources = response.mediaSources?.sources ?: emptyList()
            emitSources(sources, "Anime E$episode", callback)
        } catch (e: Exception) {
            Log.d("Videasy", "Anime: ${e.message}")
        }
    }

    private suspend fun fetchSourcesWithSeed(provider: String, params: Map<String, String>, mediaId: Int): List<VideasySource>? {
        return try {
            val seedUrl = "$sourceApi/seed?mediaId=$mediaId"
            val seedResp = app.get(seedUrl, headers = apiHeaders, timeout = 10_000L)
            val seedText = seedResp.text
            val seedData = parseJson<SeedResponse>(seedText)
            val seed = seedData.seed ?: return null

            val cookieHeader = extractCookies(seedResp)

            val encodedParams = params.map { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }.joinToString("&")
            val sourcesUrl = "$sourceApi/$provider/sources-with-title?$encodedParams&enc=2&seed=${URLEncoder.encode(seed, "UTF-8")}"

            val sourcesHeaders = apiHeaders.toMutableMap()
            if (cookieHeader.isNotBlank()) {
                sourcesHeaders["Cookie"] = cookieHeader
            }

            val encryptedResp = app.get(sourcesUrl, headers = sourcesHeaders, timeout = 15_000L)
            val encrypted = encryptedResp.text

            if (encrypted.contains("\"error\"") || encrypted.length < 20) {
                Log.d("Videasy", "Provider $provider error: ${encrypted.take(100)}")
                return null
            }

            val decrypted = VideasyCrypto.decrypt(encrypted, encrypted, seed, mediaId)
            val sourcesResponse = parseJson<SourcesResponse>(decrypted)
            sourcesResponse.sources
        } catch (e: Exception) {
            Log.d("Videasy", "fetchSources $provider: ${e.message}")
            null
        }
    }

    private fun extractCookies(response: com.lagradost.nicehttp.NiceResponse): String {
        val cookies = response.cookies
        if (cookies.isNotEmpty()) {
            return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        val setCookie = response.headers["set-cookie"] ?: response.headers["Set-Cookie"] ?: ""
        if (setCookie.isNotBlank()) {
            return setCookie.split(";").firstOrNull()?.trim() ?: ""
        }
        return ""
    }

    private suspend fun emitSources(sources: List<VideasySource>, label: String, callback: (ExtractorLink) -> Unit) {
        for (source in sources) {
            val url = source.url ?: continue
            val quality = source.quality ?: "Unknown"
            val qualityInt = when {
                quality.contains("2160") || quality.contains("4K", true) -> 2160
                quality.contains("1080") -> 1080
                quality.contains("720") -> 720
                quality.contains("480") -> 480
                else -> Qualities.Unknown.value
            }
            val type = when {
                url.contains(".m3u8", true) || source.type == "m3u8" -> ExtractorLinkType.M3U8
                url.contains(".mpd", true) || source.type == "dash" || source.type == "mpd" -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            callback(
                newExtractorLink(
                    source = "Videasy",
                    name = "$label - $quality",
                    url = url,
                    type = type
                ) {
                    this.quality = qualityInt
                    this.headers = mapOf("Referer" to "https://player.videasy.to/")
                }
            )
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeedResponse(@JsonProperty("seed") val seed: String? = null, @JsonProperty("ttlMs") val ttlMs: Long? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourcesResponse(@JsonProperty("sources") val sources: List<VideasySource>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VideasySource(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("type") val type: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HianimeResponse(@JsonProperty("mediaSources") val mediaSources: SourcesResponse? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBResponse(@JsonProperty("results") val results: List<TMDBItem>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBMovieDetails(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("genres") val genres: List<TMDBGenre>? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBTVDetails(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("genres") val genres: List<TMDBGenre>? = null,
    @JsonProperty("seasons") val seasons: List<TMDBSeason>? = null,
    @JsonProperty("external_ids") val externalIds: TMDBExternalIds? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBExternalIds(@JsonProperty("imdb_id") val imdbId: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBGenre(@JsonProperty("name") val name: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBSeason(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBSeasonDetails(
    @JsonProperty("episodes") val episodes: List<TMDBEpisode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListResponse(@JsonProperty("data") val data: AniListData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListData(@JsonProperty("Media") val Media: AniListMedia? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListMedia(
    @JsonProperty("title") val title: AniListTitle? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListTitle(@JsonProperty("english") val english: String? = null, @JsonProperty("romaji") val romaji: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListCoverImage(@JsonProperty("extraLarge") val extraLarge: String? = null, @JsonProperty("large") val large: String? = null)
