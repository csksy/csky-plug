package com.videasy

import android.content.Context
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class VideasyProvider : MainAPI() {
    override var mainUrl = "https://player.videasy.to"
    override var name = "Videasy"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    companion object {
        var context: Context? = null
    }

    private val tmdbApi = "https://db.speedracelight.com/3"
    private val sourceApi = "https://api.speedracelight.com"
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json",
        "Origin" to "https://player.videasy.to",
        "Referer" to "https://player.videasy.to/",
    )
    private val imageBase = "https://image.tmdb.org/t/p"

    private val servers = listOf("cdn", "m4uhd", "hdmovie", "superflix", "lamovie")

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
        val type = if (mediaType == "tv") TvType.TvSeries else TvType.Movie
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", type) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, "$mainUrl/movie/$id", type) { this.posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageParam = if (request.data.contains("?")) "&page=$page" else "?page=$page"
        val home = try {
            val response = parseJson<TMDBResponse>(app.get(request.data + pageParam, headers = apiHeaders, timeout = 15_000L).text)
            val mediaType = if (request.data.contains("/movie")) "movie" else "tv"
            response.results?.mapNotNull { mediaToSearchResponse(it, mediaType) } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val response = parseJson<TMDBResponse>(app.get("$tmdbApi/search/multi?query=${URLEncoder.encode(query, "UTF-8")}", headers = apiHeaders, timeout = 15_000L).text)
            response.results?.mapNotNull { if (it.mediaType == "person") null else mediaToSearchResponse(it, it.mediaType ?: "movie") } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val match = Regex("""/(movie|tv|anime)/(\d+)""").find(url) ?: return null
        val mediaType = match.groupValues[1]
        val tmdbId = match.groupValues[2].toIntOrNull() ?: return null
        return when (mediaType) {
            "movie" -> loadMovie(tmdbId, url)
            "tv" -> loadTV(tmdbId, url)
            "anime" -> loadAnime(tmdbId, url)
            else -> null
        }
    }

    private suspend fun loadMovie(tmdbId: Int, url: String): LoadResponse? {
        val details = try {
            parseJson<TMDBMovieDetails>(app.get("$tmdbApi/movie/$tmdbId?append_to_response=external_ids", headers = apiHeaders, timeout = 15_000L).text)
        } catch (e: Exception) { return null }
        val title = details.title ?: details.originalTitle ?: "Unknown"
        val data = mapOf("type" to "movie", "tmdbId" to tmdbId, "title" to title,
            "year" to details.releaseDate?.substring(0, 4)?.toIntOrNull(),
            "imdbId" to details.imdbId).toJson()
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
        val details = try {
            parseJson<TMDBTVDetails>(app.get("$tmdbApi/tv/$tmdbId?append_to_response=external_ids", headers = apiHeaders, timeout = 15_000L).text)
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
                val data = mapOf("type" to "tv", "tmdbId" to tmdbId, "title" to title,
                    "year" to details.firstAirDate?.substring(0, 4)?.toIntOrNull(),
                    "imdbId" to imdbId, "season" to seasonNum, "episode" to epNum,
                    "totalSeasons" to (details.seasons?.count { (it.seasonNumber ?: 0) > 0 } ?: 1)).toJson()
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
        val query = """query (${'$'}id: Int) { Media(id: ${'$'}id, type: ANIME) { id title { english romaji } coverImage { extraLarge large } bannerImage description(asHtml: false) episodes status seasonYear averageScore genres } }""".trimIndent()
        val requestData = mapOf("query" to query, "variables" to mapOf("id" to anilistId))
            .toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        val responseText = try {
            app.post("https://graphql.anilist.co", headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
                requestBody = requestData, timeout = 15_000L).text
        } catch (e: Exception) { return null }
        val media = parseJson<AniListResponse>(responseText).data?.Media ?: return null
        val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
        val totalEps = media.episodes ?: 1
        val episodes = mutableListOf<Episode>()
        for (i in 1..totalEps) {
            val data = mapOf("type" to "anime", "anilistId" to anilistId, "title" to title,
                "year" to media.seasonYear, "episode" to i).toJson()
            episodes.add(newEpisode(data) { this.name = "Episode $i"; this.episode = i })
        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            this.backgroundPosterUrl = media.bannerImage
            this.year = media.seasonYear
            this.plot = media.description?.replace(Regex("<[^>]*>"), "")
            this.tags = media.genres ?: emptyList()
            this.score = media.averageScore?.let { Score.from10((it / 10).toString()) }
            this.showStatus = when (media.status) { "RELEASING" -> ShowStatus.Ongoing; "FINISHED" -> ShowStatus.Completed; else -> null }
            addAniListId(anilistId)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
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
        val totalSeasons = (parsed["totalSeasons"] as? Number)?.toInt() ?: 1

        var found = false
        when (type) {
            "movie" -> found = resolveSources(tmdbId ?: return false, title, year, imdbId, null, null, null, subtitleCallback, callback)
            "tv" -> found = resolveSources(tmdbId ?: return false, title, year, imdbId, season ?: 1, episode, totalSeasons, subtitleCallback, callback)
            "anime" -> {
                resolveAnime(anilistId ?: return false, title, year, episode, callback)
                found = true
            }
        }
        return found
    }

    private suspend fun resolveAnime(anilistId: Int, title: String, year: Int?, episode: Int, callback: (ExtractorLink) -> Unit) {
        try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val url = "$sourceApi/hianime/sources-with-title?title=$encodedTitle&year=${year ?: ""}&episodeId=$episode"
            val response = parseJson<HianimeResponse>(app.get(url, headers = apiHeaders, timeout = 15_000L).text)
            val sources = response.mediaSources?.sources ?: emptyList()
            for (source in sources) {
                val srcUrl = source.url ?: continue
                val quality = source.quality ?: "Unknown"
                val qualityInt = when {
                    quality.contains("2160") || quality.contains("4K", true) -> 2160
                    quality.contains("1080") -> 1080
                    quality.contains("720") -> 720
                    quality.contains("480") -> 480
                    else -> Qualities.Unknown.value
                }
                val linkType = when {
                    srcUrl.contains(".m3u8", true) || source.type == "m3u8" -> ExtractorLinkType.M3U8
                    srcUrl.contains(".mpd", true) || source.type == "dash" -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }
                callback(newExtractorLink("Videasy", "Anime E$episode - $quality", srcUrl, linkType) {
                    this.quality = qualityInt
                    this.headers = mapOf("Referer" to "https://player.videasy.to/")
                })
            }
        } catch (e: Exception) {
            Log.d("Videasy", "Anime: ${e.message}")
        }
    }

    private suspend fun resolveSources(
        tmdbId: Int, title: String, year: Int?, imdbId: String?,
        season: Int?, episode: Int?, totalSeasons: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val encTitle = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val semaphore = Semaphore(5)
        val found = java.util.concurrent.atomic.AtomicBoolean(false)

        servers.map { server ->
            async {
                semaphore.withPermit {
                    try {
                        val sources = fetchServerSources(server, encTitle, tmdbId, year, imdbId, season, episode, totalSeasons)
                        if (sources != null) {
                            val label = server.replaceFirstChar { it.uppercase() }
                            for (sub in sources.subtitles ?: emptyList()) {
                                val url = sub.url ?: continue
                                val lang = sub.language ?: continue
                                subtitleCallback.invoke(newSubtitleFile(lang, url) {
                                    this.headers = mapOf("Referer" to "https://player.videasy.to/")
                                })
                            }
                            for (src in sources.sources ?: emptyList()) {
                                val url = src.url ?: continue
                                val quality = src.quality ?: "Unknown"
                                val linkType = when {
                                    url.contains(".m3u8", true) || src.type == "hls" -> ExtractorLinkType.M3U8
                                    url.contains(".mpd", true) || src.type == "dash" -> ExtractorLinkType.DASH
                                    else -> ExtractorLinkType.VIDEO
                                }
                                callback.invoke(
                                    newExtractorLink("Videasy", "Videasy [$label] $quality", url, linkType) {
                                        this.quality = qualityToInt(quality)
                                        this.headers = mapOf("Referer" to "https://player.videasy.to/")
                                    }
                                )
                                found.set(true)
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("Videasy", "$server: ${e.message}")
                    }
                }
            }
        }.awaitAll()
        found.get()
    }

    private fun okRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", apiHeaders["User-Agent"] ?: "Mozilla/5.0")
        .header("Accept", "application/json")
        .header("Origin", "https://player.videasy.to")
        .header("Referer", "https://player.videasy.to/")
        .build()

    private fun fetchServerSources(
        server: String, encTitle: String, tmdbId: Int, year: Int?, imdbId: String?,
        season: Int?, episode: Int?, totalSeasons: Int?
    ): DecVideasyResult? {
        // the api ties the seed to the connection it was issued on, so the seed
        // and the sources call must ride the same client
        for (attempt in 1..2) {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            try {
                val seedText = client.newCall(okRequest("$sourceApi/seed?mediaId=$tmdbId")).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    resp.body?.string() ?: return null
                }
                val seed = try {
                    parseJson<SeedResponse>(seedText).seed
                } catch (e: Exception) { null } ?: return null

                val url = buildString {
                    append("$sourceApi/$server/sources-with-title?title=$encTitle")
                    append(if (season == null) "&mediaType=Movie" else "&mediaType=TV")
                    if (year != null) append("&year=$year")
                    if (season != null) {
                        append("&seasonId=$season&episodeId=$episode")
                        if (totalSeasons != null) append("&totalSeasons=$totalSeasons")
                    }
                    append("&tmdbId=$tmdbId")
                    if (!imdbId.isNullOrBlank()) append("&imdbId=$imdbId")
                    append("&enc=2&seed=")
                    append(URLEncoder.encode(seed, "UTF-8").replace("+", "%20"))
                }

                var body: String? = null
                client.newCall(okRequest(url)).execute().use { resp ->
                    when {
                        resp.code == 401 -> body = null
                        !resp.isSuccessful -> return null
                        else -> body = resp.body?.string() ?: ""
                    }
                }
                val encrypted = body ?: continue
                if (encrypted.contains("\"error\"") || encrypted.length < 20) return null

                val decrypted = VideasyCrypto.decrypt(encrypted, seed, tmdbId) ?: continue
                return try {
                    parseJson<DecVideasyResponse>(decrypted).result
                } catch (e: Exception) {
                    Log.d("Videasy", "parse $server: ${e.message}")
                    null
                }
            } catch (e: Exception) {
                Log.d("Videasy", "$server: ${e.message}")
            }
        }
        return null
    }

    private fun qualityToInt(quality: String): Int = when {
        quality.contains("2160", true) || quality.contains("4k", true) -> 2160
        quality.contains("1080", true) -> 1080
        quality.contains("720", true) -> 720
        quality.contains("480", true) -> 480
        quality.contains("360", true) -> 360
        else -> Qualities.Unknown.value
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class HianimeResponse(@JsonProperty("mediaSources") val mediaSources: SourcesResponse? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class SourcesResponse(@JsonProperty("sources") val sources: List<VideasySource>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class VideasySource(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("type") val type: String? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class SeedResponse(
    @JsonProperty("seed") val seed: String? = null,
    @JsonProperty("ttlMs") val ttlMs: Long? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class DecVideasyResponse(@JsonProperty("result") val result: DecVideasyResult? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class DecVideasyResult(
    @JsonProperty("sources") val sources: List<VideasySource>? = null,
    @JsonProperty("subtitles") val subtitles: List<DecSubtitle>? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class DecSubtitle(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("language") val language: String? = null
)
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
data class TMDBSeason(@JsonProperty("season_number") val seasonNumber: Int? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBSeasonDetails(@JsonProperty("episodes") val episodes: List<TMDBEpisode>? = null)
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

private object VideasyCrypto {
    private fun w32(x: Int): Int {
        var e = x
        e = e xor (e ushr 16)
        e *= -2048144789
        e = e xor (e ushr 13)
        e *= -1028477387
        e = e xor (e ushr 16)
        return e
    }

    private fun rotl32(e: Int, t: Int): Int {
        val s = t and 31
        if (s == 0) return e
        return (e shl s) or (e ushr (32 - s))
    }

    private fun fnv1a(s: String): Int {
        var h = -2128831035
        for (c in s) h = (h xor c.code) * 16777619
        return w32(h)
    }

    fun decrypt(text: String, seed: String, mediaId: Int): String? {
        return try {
            var b = text.replace("-", "+").replace("_", "/")
            b += "=".repeat((4 - b.length % 4) % 4)
            val payload = java.util.Base64.getDecoder().decode(b)
            if (payload.size < 5) return null

            val S = IntArray(61)
            val present = BooleanArray(61)
            var a = w32(fnv1a(seed) xor w32(mediaId xor -1640531527))
            for (i in 0 until 8) {
                val t = ((a.toLong() and 0xFFFFFFFFL) % 61L).toInt()
                a = rotl32(a + -1640531527, 7 + (7 and i))
                S[t] = a xor w32(a)
                present[t] = true
                a = w32(a + t)
            }
            var acc = w32(-1515870811 xor a)

            val size = payload.size
            val keystream = ByteArray(size)
            var idx = 0
            var counter = 0
            while (idx < size) {
                val n = ((acc.toLong() and 0xFFFFFFFFL) % 61L).toInt()
                val d = if (present[n]) S[n] else 0
                val x = d xor (-1640531527 * (counter + 1))
                var l = if (present[n]) (acc xor x) or (acc and x) else acc xor x
                l = rotl32(l + acc, 31 and n) xor rotl32(acc, 31 and (n * 7))
                acc = w32(l + -1640531527)
                S[n] = acc
                present[n] = true
                counter++
                val t = acc
                keystream[idx] = (t and 0xFF).toByte(); idx++
                if (idx < size) { keystream[idx] = ((t ushr 8) and 0xFF).toByte(); idx++ }
                if (idx < size) { keystream[idx] = ((t ushr 16) and 0xFF).toByte(); idx++ }
                if (idx < size) { keystream[idx] = ((t ushr 24) and 0xFF).toByte(); idx++ }
            }
            for (i in 0 until size) payload[i] = (payload[i].toInt() xor keystream[i].toInt()).toByte()
            if (payload[0] != 109.toByte() || payload[1] != 118.toByte() ||
                payload[2] != 109.toByte() || payload[3] != 49.toByte()) return null
            String(payload, 4, size - 4, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
