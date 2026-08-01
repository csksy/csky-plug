package com.laddu100.anishows

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import java.net.URLEncoder

class AniShows : MainAPI() {
    override var mainUrl = "https://anishows.org"
    override var name = "AniShows"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.TvSeries, TvType.Movie)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val tmdbApiBase = "$mainUrl/api/tmdb"

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
        val vote_average: Double? = null
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbExternalIds(
        val imdb_id: String? = null,
        val tvdb_id: Int? = null
    )

    data class EpisodeData(
        val tmdbId: Int,
        val mediaType: String,
        val season: Int,
        val episode: Int,
        val title: String,
        val imdbId: String? = null,
        val year: Int? = null
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
        return if (type == "tv") loadTv(tmdbId, url) else loadMovie(tmdbId, url)
    }

    private suspend fun loadTv(tmdbId: Int, url: String): LoadResponse? {
        val text = fetchTmdb("$tmdbApiBase/tv/$tmdbId") ?: return null
        val tv = parseJson<TmdbTvResponse>(text)
        val title = tv.name ?: tv.original_name ?: return null
        val year = tv.first_air_date?.substringBefore("-")?.toIntOrNull()

        val imdbId = try {
            val extText = fetchTmdb("$tmdbApiBase/tv/$tmdbId/external_ids")
            extText?.let { parseJson<TmdbExternalIds>(it).imdb_id }
        } catch (e: Exception) { null }

        val seasons = tv.seasons?.filter { it.season_number != null && it.season_number!! > 0 } ?: emptyList()
        val episodes = mutableListOf<Episode>()
        for (season in seasons) {
            val seasonNum = season.season_number ?: continue
            val seasonText = fetchTmdb("$tmdbApiBase/tv/$tmdbId/season/$seasonNum") ?: continue
            val seasonData = parseJson<TmdbSeasonResponse>(seasonText)
            seasonData.episodes?.forEach { ep ->
                val epNum = ep.episode_number ?: return@forEach
                episodes.add(newEpisode(EpisodeData(tmdbId, "tv", seasonNum, epNum, title, imdbId, year).toJson()) {
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
            this.year = year
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
        val year = movie.release_date?.substringBefore("-")?.toIntOrNull()

        val imdbId = try {
            val extText = fetchTmdb("$tmdbApiBase/movie/$tmdbId/external_ids")
            extText?.let { parseJson<TmdbExternalIds>(it).imdb_id }
        } catch (e: Exception) { null }

        return newMovieLoadResponse(title, url, TvType.Movie, EpisodeData(tmdbId, "movie", 0, 0, title, imdbId, year).toJson()) {
            this.posterUrl = getImageUrl(movie.poster_path)
            this.backgroundPosterUrl = getImageUrl(movie.backdrop_path)
            this.plot = movie.overview
            this.year = year
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
        val season = if (isMovie) null else epData.season
        val episode = if (isMovie) null else epData.episode
        val imdbId = epData.imdbId
        val title = epData.title
        val year = epData.year

        Log.d("AniShows", "loadLinks: tmdbId=$tmdbId imdbId=$imdbId title=$title isMovie=$isMovie S${season}E${episode}")

        val loadData = AllLoadLinksData(
            title = title,
            imdbId = imdbId,
            tmdbId = tmdbId,
            year = year,
            season = season,
            episode = episode,
            isAnime = false,
            isBollywood = false
        )

        try {
            AniShowsExtractors.invokeAllSources(loadData, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.d("AniShows", "invokeAllSources error: ${e.message}")
        }

        return true
    }
}
