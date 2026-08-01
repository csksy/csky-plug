package com.laddu100.anishows

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AniShows : MainAPI() {
    override var mainUrl = "https://anishows.org"
    override var name = "AniShows"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.TvSeries, TvType.Movie)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val tmdbApiBase = "$mainUrl/api/tmdb"

    private val vidrockKey = bytesFromHex("7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f")

    private fun bytesFromHex(hex: String): ByteArray {
        val len = hex.length / 2
        val data = ByteArray(len)
        for (i in 0 until len) {
            data[i] = ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return data
    }

    private fun decryptVidrockUrl(encrypted: String): String? {
        return try {
            val b64 = encrypted.replace('-', '+').replace('_', '/')
            val padded = when (b64.length % 4) {
                2 -> b64 + "=="
                3 -> b64 + "="
                else -> b64
            }
            val raw = Base64.decode(padded, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val cipherText = raw.copyOfRange(12, raw.size - 16)
            val tag = raw.copyOfRange(raw.size - 16, raw.size)
            val combined = cipherText + tag
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(vidrockKey, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            String(cipher.doFinal(combined), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d("AniShows", "vidrock decrypt error: ${e.message}")
            null
        }
    }

    override val mainPage = mainPageOf(
        "$tmdbApiBase/trending/all/week" to "Trending",
        "$tmdbApiBase/tv/popular" to "Popular TV",
        "$tmdbApiBase/movie/popular" to "Popular Movies",
        "$tmdbApiBase/tv/top_rated" to "Top Rated TV",
        "$tmdbApiBase/movie/top_rated" to "Top Rated Movies"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSearchResponse(
        val page: Int? = null,
        val results: List<TmdbItem>? = null,
        val total_pages: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbItem(
        val id: Int? = null,
        val name: String? = null,
        val title: String? = null,
        val original_name: String? = null,
        val original_title: String? = null,
        val overview: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val media_type: String? = null,
        val first_air_date: String? = null,
        val release_date: String? = null,
        val vote_average: Double? = null,
        val genre_ids: List<Int>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbTvResponse(
        val id: Int? = null,
        val name: String? = null,
        val original_name: String? = null,
        val overview: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val genres: List<TmdbGenre>? = null,
        val number_of_seasons: Int? = null,
        val first_air_date: String? = null,
        val status: String? = null,
        val vote_average: Double? = null,
        val seasons: List<TmdbSeason>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbGenre(val id: Int? = null, val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSeason(
        val id: Int? = null,
        val season_number: Int? = null,
        val episode_count: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val poster_path: String? = null,
        val air_date: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSeasonResponse(
        val id: String? = null,
        val season_number: Int? = null,
        val episodes: List<TmdbEpisode>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbEpisode(
        val id: Int? = null,
        val episode_number: Int? = null,
        val season_number: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val still_path: String? = null,
        val air_date: String? = null,
        val runtime: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbMovieResponse(
        val id: Int? = null,
        val title: String? = null,
        val original_title: String? = null,
        val overview: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val genres: List<TmdbGenre>? = null,
        val release_date: String? = null,
        val runtime: Int? = null,
        val vote_average: Double? = null,
        val status: String? = null
    )

    data class EpisodeData(
        val tmdbId: Int,
        val mediaType: String,
        val season: Int,
        val episode: Int,
        val title: String = ""
    )

    private fun getImageUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "$mainUrl/api/tmdb-image/w500${path}"
    }

    private suspend fun fetchTmdb(url: String): String? {
        return try {
            val resp = app.get(url, headers = mapOf("User-Agent" to ua), timeout = 15_000L)
            if (resp.code != 200) {
                Log.d("AniShows", "TMDB fetch HTTP ${resp.code}: $url")
                return null
            }
            resp.text
        } catch (e: Exception) {
            Log.d("AniShows", "TMDB fetch error: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = "${request.data}?page=$page"
            val text = fetchTmdb(url) ?: return newHomePageResponse(request.name, emptyList())
            val resp = parseJson<TmdbSearchResponse>(text)
            val inferredType = when {
                request.data.contains("/tv/") -> "tv"
                request.data.contains("/movie/") -> "movie"
                else -> null
            }
            val home = resp.results?.mapNotNull { it.toSearchResponse(inferredType) } ?: emptyList()
            newHomePageResponse(request.name, home, hasNext = resp.page?.let { it < (resp.total_pages ?: 1) } ?: false)
        } catch (e: Exception) {
            Log.d("AniShows", "getMainPage error: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun TmdbItem.toSearchResponse(inferredType: String? = null): SearchResponse? {
        val id = id ?: return null
        val title = name ?: title ?: original_name ?: original_title ?: return null
        val type = media_type ?: inferredType ?: if (first_air_date != null) "tv" else "movie"
        val tvType = when (type) {
            "tv" -> TvType.TvSeries
            "movie" -> TvType.Movie
            else -> TvType.TvSeries
        }
        val url = if (type == "movie") "$mainUrl/watch/movie/$id" else "$mainUrl/watch/tv/$id/1/1"
        return newAnimeSearchResponse(title, url, tvType) {
            this.posterUrl = getImageUrl(poster_path)
            this.year = (first_air_date ?: release_date)?.substringBefore("-")?.toIntOrNull()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val text = fetchTmdb("$tmdbApiBase/search/multi?query=$encoded") ?: return emptyList()
            val resp = parseJson<TmdbSearchResponse>(text)
            resp.results?.mapNotNull { it.toSearchResponse(null) } ?: emptyList()
        } catch (e: Exception) {
            Log.d("AniShows", "search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val type = if (url.contains("/movie/")) "movie" else "tv"
        val tmdbId = Regex("""/(?:tv|movie)/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        return if (type == "tv") {
            loadTv(tmdbId, url)
        } else {
            loadMovie(tmdbId, url)
        }
    }

    private suspend fun loadTv(tmdbId: Int, url: String): LoadResponse? {
        val text = fetchTmdb("$tmdbApiBase/tv/$tmdbId") ?: return null
        val tv = parseJson<TmdbTvResponse>(text)
        val title = tv.name ?: tv.original_name ?: return null

        val seasons = tv.seasons?.filter { it.season_number != null && it.season_number!! > 0 } ?: emptyList()
        val episodes = mutableListOf<Episode>()

        for (season in seasons) {
            val seasonNum = season.season_number ?: continue
            val seasonText = fetchTmdb("$tmdbApiBase/tv/$tmdbId/season/$seasonNum") ?: continue
            val seasonData = parseJson<TmdbSeasonResponse>(seasonText)
            seasonData.episodes?.forEach { ep ->
                val epNum = ep.episode_number ?: return@forEach
                episodes.add(newEpisode(EpisodeData(tmdbId, "tv", seasonNum, epNum, title).toJson()) {
                    this.episode = epNum
                    this.season = seasonNum
                    this.name = ep.name ?: "Episode $epNum"
                    this.description = ep.overview
                    this.posterUrl = getImageUrl(ep.still_path)
                })
            }
        }

        val tvType = if (tv.genres?.any { it.name?.lowercase()?.contains("anime") == true } == true || tv.original_name?.let { it.any { c -> c.code > 0x3000 } } == true) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = getImageUrl(tv.poster_path)
            this.backgroundPosterUrl = getImageUrl(tv.backdrop_path)
            this.plot = tv.overview
            this.year = tv.first_air_date?.substringBefore("-")?.toIntOrNull()
            this.tags = tv.genres?.mapNotNull { it.name }
            if (tv.vote_average != null) this.score = Score.from10((tv.vote_average / 10).toString())
            this.showStatus = when (tv.status?.lowercase()) {
                "returning series" -> ShowStatus.Ongoing
                "ended", "canceled" -> ShowStatus.Completed
                else -> null
            }
        }
    }

    private suspend fun loadMovie(tmdbId: Int, url: String): LoadResponse? {
        val text = fetchTmdb("$tmdbApiBase/movie/$tmdbId") ?: return null
        val movie = parseJson<TmdbMovieResponse>(text)
        val title = movie.title ?: movie.original_title ?: return null

        return newMovieLoadResponse(title, url, TvType.Movie, EpisodeData(tmdbId, "movie", 0, 0, title).toJson()) {
            this.posterUrl = getImageUrl(movie.poster_path)
            this.backgroundPosterUrl = getImageUrl(movie.backdrop_path)
            this.plot = movie.overview
            this.year = movie.release_date?.substringBefore("-")?.toIntOrNull()
            this.tags = movie.genres?.mapNotNull { it.name }
            if (movie.vote_average != null) this.score = Score.from10((movie.vote_average / 10).toString())
            this.duration = movie.runtime
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            parseJson<EpisodeData>(data)
        } catch (e: Exception) {
            Log.d("AniShows", "loadLinks parse error: ${e.message}")
            return false
        }

        val tmdbId = epData.tmdbId
        val isMovie = epData.mediaType == "movie"
        val season = epData.season
        val episode = epData.episode

        Log.d("AniShows", "loadLinks: tmdbId=$tmdbId isMovie=$isMovie S${season}E${episode}")

        var found = false

        // Server 1: vidrock.ru (direct API + AES-GCM decryption, no WebView needed)
        try {
            if (loadVidrock(tmdbId, isMovie, season, episode, callback)) found = true
        } catch (e: Exception) {
            Log.d("AniShows", "[vidrock] error: ${e.message}")
        }

        // Server 2: videasy.to (WebViewResolver)
        try {
            if (loadWebViewServer("videasy", tmdbId, isMovie, season, episode, callback)) found = true
        } catch (e: Exception) {
            Log.d("AniShows", "[videasy] error: ${e.message}")
        }

        // Server 3: vidfast.vc (WebViewResolver)
        try {
            if (loadWebViewServer("vidfast", tmdbId, isMovie, season, episode, callback)) found = true
        } catch (e: Exception) {
            Log.d("AniShows", "[vidfast] error: ${e.message}")
        }

        // Server 4: vidlink.pro (WebViewResolver)
        try {
            if (loadWebViewServer("vidlink", tmdbId, isMovie, season, episode, callback)) found = true
        } catch (e: Exception) {
            Log.d("AniShows", "[vidlink] error: ${e.message}")
        }

        // Server 5: vidcore.net (WebViewResolver)
        try {
            if (loadWebViewServer("vidcore", tmdbId, isMovie, season, episode, callback)) found = true
        } catch (e: Exception) {
            Log.d("AniShows", "[vidcore] error: ${e.message}")
        }

        // Server 6: vidzee.wtf (WebViewResolver)
        try {
            if (loadWebViewServer("vidzee", tmdbId, isMovie, season, episode, callback)) found = true
        } catch (e: Exception) {
            Log.d("AniShows", "[vidzee] error: ${e.message}")
        }

        // Server 7: modiplay.xyz (Hindi, WebViewResolver)
        try {
            if (loadModiplay(tmdbId, isMovie, callback)) found = true
        } catch (e: Exception) {
            Log.d("AniShows", "[modiplay] error: ${e.message}")
        }

        // Server 8: screenscape.me (Hindi, WebViewResolver)
        try {
            if (loadScreenscape(tmdbId, callback)) found = true
        } catch (e: Exception) {
            Log.d("AniShows", "[screenscape] error: ${e.message}")
        }

        // Additional sources: VegaMovies, Moviesmod, TopMovies, MoviesDrive
        val title = epData.title
        if (title.isNotBlank()) {
            Log.d("AniShows", "searching external providers for: $title")

            try {
                val vega = VegaMoviesProvider()
                val vegaResults = vega.search(title)
                val vegaMatch = vegaResults?.firstOrNull()
                if (vegaMatch != null) {
                    val vegaLoad = vega.load(vegaMatch.url) as? AnimeLoadResponse
                    if (vegaLoad != null) {
                        if (isMovie) {
                            vega.loadLinks(vegaLoad.url, false, subtitleCallback, callback)
                            Log.d("AniShows", "[vegamovies] loaded (movie)")
                            found = true
                        } else {
                            val vegaEp = vegaLoad.episodes?.get(DubStatus.Subbed)?.find { it.episode == episode }
                            if (vegaEp != null) {
                                vega.loadLinks(vegaEp.data, false, subtitleCallback, callback)
                                Log.d("AniShows", "[vegamovies] loaded")
                                found = true
                            }
                        }
                    }
                } else {
                    Log.d("AniShows", "[vegamovies] no match")
                }
            } catch (e: Exception) {
                Log.d("AniShows", "[vegamovies] error: ${e.message}")
            }

            try {
                val mod = MoviesmodProvider()
                val modResults = mod.search(title)
                val modMatch = modResults?.firstOrNull()
                if (modMatch != null) {
                    val modLoad = mod.load(modMatch.url) as? AnimeLoadResponse
                    if (modLoad != null) {
                        if (isMovie) {
                            mod.loadLinks(modLoad.url, false, subtitleCallback, callback)
                            Log.d("AniShows", "[moviesmod] loaded (movie)")
                            found = true
                        } else {
                            val modEp = modLoad.episodes?.get(DubStatus.Subbed)?.find { it.episode == episode }
                            if (modEp != null) {
                                mod.loadLinks(modEp.data, false, subtitleCallback, callback)
                                Log.d("AniShows", "[moviesmod] loaded")
                                found = true
                            }
                        }
                    }
                } else {
                    Log.d("AniShows", "[moviesmod] no match")
                }
            } catch (e: Exception) {
                Log.d("AniShows", "[moviesmod] error: ${e.message}")
            }

            try {
                val top = TopmoviesProvider()
                val topResults = top.search(title)
                val topMatch = topResults?.firstOrNull()
                if (topMatch != null) {
                    val topLoad = top.load(topMatch.url) as? AnimeLoadResponse
                    if (topLoad != null) {
                        if (isMovie) {
                            top.loadLinks(topLoad.url, false, subtitleCallback, callback)
                            Log.d("AniShows", "[topmovies] loaded (movie)")
                            found = true
                        } else {
                            val topEp = topLoad.episodes?.get(DubStatus.Subbed)?.find { it.episode == episode }
                            if (topEp != null) {
                                top.loadLinks(topEp.data, false, subtitleCallback, callback)
                                Log.d("AniShows", "[topmovies] loaded")
                                found = true
                            }
                        }
                    }
                } else {
                    Log.d("AniShows", "[topmovies] no match")
                }
            } catch (e: Exception) {
                Log.d("AniShows", "[topmovies] error: ${e.message}")
            }

            try {
                val drive = MoviesDriveProvider()
                val driveResults = drive.search(title)
                val driveMatch = driveResults?.firstOrNull()
                if (driveMatch != null) {
                    val driveLoad = drive.load(driveMatch.url) as? AnimeLoadResponse
                    if (driveLoad != null) {
                        if (isMovie) {
                            drive.loadLinks(driveLoad.url, false, subtitleCallback, callback)
                            Log.d("AniShows", "[moviesdrive] loaded (movie)")
                            found = true
                        } else {
                            val driveEp = driveLoad.episodes?.get(DubStatus.Subbed)?.find { it.episode == episode }
                            if (driveEp != null) {
                                drive.loadLinks(driveEp.data, false, subtitleCallback, callback)
                                Log.d("AniShows", "[moviesdrive] loaded")
                                found = true
                            }
                        }
                    }
                } else {
                    Log.d("AniShows", "[moviesdrive] no match")
                }
            } catch (e: Exception) {
                Log.d("AniShows", "[moviesdrive] error: ${e.message}")
            }
        }


        return found
    }

    private suspend fun fetchTmdbTitle(tmdbId: Int, isMovie: Boolean): String {
        return try {
            val endpoint = if (isMovie) "$tmdbApiBase/movie/$tmdbId" else "$tmdbApiBase/tv/$tmdbId"
            val text = fetchTmdb(endpoint) ?: return ""
            if (isMovie) {
                parseJson<TmdbMovieResponse>(text).title ?: ""
            } else {
                parseJson<TmdbTvResponse>(text).name ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun loadVidrock(
        tmdbId: Int,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiUrl = if (isMovie) {
            "https://vidrock.ru/api/movie/$tmdbId"
        } else {
            "https://vidrock.ru/api/tv/$tmdbId/$season/$episode"
        }
        Log.d("AniShows", "[vidrock] fetching: $apiUrl")

        val resp = app.get(apiUrl, headers = mapOf("User-Agent" to ua), timeout = 15_000L)
        if (resp.code != 200) {
            Log.d("AniShows", "[vidrock] HTTP ${resp.code}")
            return false
        }

        val text = resp.text
        if (text.contains("no available") || text.isBlank()) {
            Log.d("AniShows", "[vidrock] no sources")
            return false
        }

        val data = try {
            parseJson<Map<String, Map<String, String?>>>(text)
        } catch (e: Exception) {
            Log.d("AniShows", "[vidrock] parse error: ${e.message}")
            return false
        }

        var found = false
        for ((serverName, info) in data) {
            val encrypted = info["url"] ?: continue
            val type = info["type"] ?: continue
            val lang = info["language"] ?: "Original"

            val streamUrl = decryptVidrockUrl(encrypted)
            if (streamUrl == null || streamUrl.isBlank()) {
                Log.d("AniShows", "[vidrock] $serverName: decrypt failed")
                continue
            }

            Log.d("AniShows", "[vidrock] $serverName ($type): ${streamUrl.take(80)}")

            if (type == "hls") {
                callback.invoke(
                    newExtractorLink(
                        "AniShows Vidrock $serverName ($lang)",
                        "AniShows Vidrock $serverName ($lang)",
                        streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://vidrock.ru/"
                        this.headers = mapOf("User-Agent" to ua, "Referer" to "https://vidrock.ru/")
                    }
                )
                found = true
            } else if (type == "mp4") {
                try {
                    val mp4Resp = app.get(streamUrl, headers = mapOf("User-Agent" to ua, "Referer" to "https://vidrock.ru/"), timeout = 15_000L)
                    if (mp4Resp.code == 200 && mp4Resp.text.trimStart().startsWith("[")) {
                        parseJson<List<Map<String, Any?>>>(mp4Resp.text).forEach { q ->
                            val mp4Url = q["url"] as? String ?: return@forEach
                            val res = q["resolution"]
                            val label = "AniShows Vidrock $serverName ($lang)" + (res?.let { " ${it}p" } ?: "")
                            callback.invoke(newExtractorLink(label, label, mp4Url, type = ExtractorLinkType.VIDEO) {
                                this.referer = "https://vidrock.ru/"
                                this.headers = mapOf("User-Agent" to ua, "Referer" to "https://vidrock.ru/")
                            })
                        }
                        found = true
                    } else {
                        callback.invoke(newExtractorLink(
                            "AniShows Vidrock $serverName ($lang)", "AniShows Vidrock $serverName ($lang)",
                            streamUrl, type = ExtractorLinkType.VIDEO
                        ) { this.referer = "https://vidrock.ru/"; this.headers = mapOf("User-Agent" to ua, "Referer" to "https://vidrock.ru/") })
                        found = true
                    }
                } catch (e: Exception) {
                    Log.d("AniShows", "[vidrock] $serverName mp4 error: ${e.message}")
                }
            }
        }
        return found
    }

    private suspend fun loadWebViewServer(
        server: String,
        tmdbId: Int,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = when (server) {
            "videasy" -> if (isMovie) "https://player.videasy.to/movie/$tmdbId"
                         else "https://player.videasy.to/tv/$tmdbId/$season/$episode"
            "vidfast" -> if (isMovie) "https://vidfast.vc/movie/$tmdbId?autoPlay=true"
                         else "https://vidfast.vc/tv/$tmdbId/$season/$episode?autoPlay=true"
            "vidlink" -> if (isMovie) "https://vidlink.pro/movie/$tmdbId"
                         else "https://vidlink.pro/tv/$tmdbId/$season/$episode"
            "vidcore" -> if (isMovie) "https://vidcore.net/movie/$tmdbId?autoPlay=true"
                         else "https://vidcore.net/tv/$tmdbId/$season/$episode?autoPlay=true"
            "vidzee" -> if (isMovie) "https://player.vidzee.wtf/embed/movie/$tmdbId"
                        else "https://player.vidzee.wtf/embed/tv/$tmdbId/$season/$episode"
            else -> return false
        }

        Log.d("AniShows", "[$server] resolving: $embedUrl")

        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8|\.mp4"""),
                additionalUrls = listOf(Regex("""\.m3u8|\.mp4""")),
                script = """try{var b=document.querySelector('button,[class*=play],.vjs-big-play-button,[class*=Play],video');if(b){b.click()}}catch(e){}""",
                useOkhttp = false,
                timeout = 25_000L
            )
            val resolved = app.get(embedUrl, referer = "$mainUrl/", interceptor = resolver).url
            Log.d("AniShows", "[$server] resolved: ${resolved.take(100)}")

            if (resolved.contains(".m3u8", ignoreCase = true)) {
                val host = Regex("""https?://([^/]+)""").find(resolved)?.groupValues?.get(1) ?: ""
                callback.invoke(newExtractorLink(
                    "AniShows ${server.replaceFirstChar { it.uppercase() }}",
                    "AniShows ${server.replaceFirstChar { it.uppercase() }}",
                    resolved, type = ExtractorLinkType.M3U8
                ) { this.referer = "https://$host/"; this.headers = mapOf("User-Agent" to ua, "Referer" to "https://$host/") })
                true
            } else if (resolved.contains(".mp4", ignoreCase = true)) {
                val host = Regex("""https?://([^/]+)""").find(resolved)?.groupValues?.get(1) ?: ""
                callback.invoke(newExtractorLink(
                    "AniShows ${server.replaceFirstChar { it.uppercase() }}",
                    "AniShows ${server.replaceFirstChar { it.uppercase() }}",
                    resolved, type = ExtractorLinkType.VIDEO
                ) { this.referer = "https://$host/"; this.headers = mapOf("User-Agent" to ua, "Referer" to "https://$host/") })
                true
            } else {
                try {
                    loadExtractor(resolved, "$mainUrl/", subtitleCallback = {}, callback = callback)
                    true
                } catch (e: Exception) {
                    Log.d("AniShows", "[$server] loadExtractor failed: ${e.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.d("AniShows", "[$server] resolve failed: ${e.message}")
            false
        }
    }

    private suspend fun loadModiplay(
        tmdbId: Int,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = if (isMovie) {
            "https://rozgarlelo.modiplay.xyz/embed/tmdb/movie?id=$tmdbId"
        } else {
            "https://rozgarlelo.modiplay.xyz/embed/tmdb/tv?id=$tmdbId"
        }
        Log.d("AniShows", "[modiplay] resolving: $embedUrl")

        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8|\.mp4"""),
                additionalUrls = listOf(Regex("""\.m3u8|\.mp4""")),
                script = """try{var b=document.querySelector('button,[class*=play],.vjs-big-play-button,video');if(b){b.click()}}catch(e){}""",
                useOkhttp = false,
                timeout = 25_000L
            )
            val resolved = app.get(embedUrl, referer = "$mainUrl/", interceptor = resolver).url
            Log.d("AniShows", "[modiplay] resolved: ${resolved.take(100)}")

            if (resolved.contains(".m3u8", ignoreCase = true)) {
                callback.invoke(newExtractorLink(
                    "AniShows ModiPlay (Hindi)", "AniShows ModiPlay (Hindi)",
                    resolved, type = ExtractorLinkType.M3U8
                ) { this.referer = "https://rozgarlelo.modiplay.xyz/"; this.headers = mapOf("User-Agent" to ua, "Referer" to "https://rozgarlelo.modiplay.xyz/") })
                true
            } else if (resolved.contains(".mp4", ignoreCase = true)) {
                callback.invoke(newExtractorLink(
                    "AniShows ModiPlay (Hindi)", "AniShows ModiPlay (Hindi)",
                    resolved, type = ExtractorLinkType.VIDEO
                ) { this.referer = "https://rozgarlelo.modiplay.xyz/"; this.headers = mapOf("User-Agent" to ua, "Referer" to "https://rozgarlelo.modiplay.xyz/") })
                true
            } else {
                try {
                    loadExtractor(resolved, "https://rozgarlelo.modiplay.xyz/", subtitleCallback = {}, callback = callback)
                    true
                } catch (e: Exception) {
                    Log.d("AniShows", "[modiplay] loadExtractor failed: ${e.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.d("AniShows", "[modiplay] resolve failed: ${e.message}")
            false
        }
    }

    private suspend fun loadScreenscape(
        tmdbId: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = "https://embed.screenscape.me/embed?tmdb=$tmdbId"
        Log.d("AniShows", "[screenscape] resolving: $embedUrl")

        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8|\.mp4"""),
                additionalUrls = listOf(Regex("""\.m3u8|\.mp4""")),
                script = """try{var b=document.querySelector('button,[class*=play],.vjs-big-play-button,video');if(b){b.click()}}catch(e){}""",
                useOkhttp = false,
                timeout = 20_000L
            )
            val resolved = app.get(embedUrl, referer = "$mainUrl/", interceptor = resolver).url
            Log.d("AniShows", "[screenscape] resolved: ${resolved.take(100)}")

            if (resolved.contains(".m3u8", ignoreCase = true)) {
                M3u8Helper.generateM3u8("AniShows ScreenScape (Hindi)", resolved, "https://embed.screenscape.me/").forEach(callback)
                true
            } else if (resolved.contains(".mp4", ignoreCase = true)) {
                callback.invoke(
                    newExtractorLink(
                        "AniShows ScreenScape (Hindi)",
                        "AniShows ScreenScape (Hindi)",
                        resolved,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://embed.screenscape.me/"
                        this.headers = mapOf("User-Agent" to ua)
                    }
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.d("AniShows", "[screenscape] resolve failed: ${e.message}")
            false
        }
    }
}
