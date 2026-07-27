package com.laddu100.bingr

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// =========================================================================
// BingrProvider — Movies & TV
// =========================================================================
// Home page uses TMDB-backed discover endpoints from the bingr API.
// Search uses /search?q=...&type=multi.
// load() fetches /details/{type}/{id} for full metadata (hero banner, cast, etc.).
// loadLinks() tries all 8 Server 1 sources IN PARALLEL via POST /stream,
// plus the /languages endpoint for multi-audio MP4 sources, plus
// /subtitles/vdrk for VTT subtitles.

class BingrProvider : MainAPI() {
    override var mainUrl = "https://bingr.one"
    override var name = "Bingr"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "Bingr"

    // Data URL format: bingr://{type}/{tmdbId}/{title}
    // type is "movie" or "tv". Title is included for stream search queries.

    override val mainPage = mainPageOf(
        "trending_all" to "Trending",
        "trending_movie" to "Trending Movies",
        "trending_tv" to "Trending TV Shows",
        "top_movies" to "Top Rated Movies",
        "action" to "Action Movies",
        "comedy" to "Comedy Movies",
        "scifi" to "Sci-Fi & Fantasy",
        "horror" to "Horror Movies",
        "romance" to "Romance Movies",
        "top_tv" to "Top Rated TV",
        "crime" to "Crime Thrillers",
        "new_movies" to "New Releases"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "getMainPage: ${request.name} page=$page")
        return try {
            val path = when (request.data) {
                "trending_all" -> "/trending/all?page=$page"
                "trending_movie" -> "/trending/movie?page=$page"
                "trending_tv" -> "/trending/tv?page=$page"
                "top_movies" -> "/discover/movie?sort_by=vote_average.desc&min_votes=1000&page=$page"
                "action" -> "/discover/movie?genre=28&sort_by=popularity.desc&page=$page"
                "comedy" -> "/discover/movie?genre=35&sort_by=popularity.desc&page=$page"
                "scifi" -> "/discover/movie?genre=878&sort_by=popularity.desc&page=$page"
                "horror" -> "/discover/movie?genre=27&sort_by=popularity.desc&page=$page"
                "romance" -> "/discover/movie?genre=10749&sort_by=popularity.desc&page=$page"
                "top_tv" -> "/discover/tv?sort_by=vote_average.desc&min_votes=500&page=$page"
                "crime" -> "/discover/movie?genre=80&sort_by=popularity.desc&page=$page"
                "new_movies" -> "/discover/movie?sort_by=primary_release_date.desc&min_votes=50&page=$page"
                else -> return newHomePageResponse(request.name, emptyList())
            }
            val resp = BingrApi.discover(path)
            val items = resp.results.mapNotNull { item ->
                val type = item.type
                if (type != "movie" && type != "tv") return@mapNotNull null
                val title = item.title
                if (title.isBlank()) return@mapNotNull null
                val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
                newMovieSearchResponse(title, "bingr://$type/${item.id}/$title", tvType) {
                    this.posterUrl = item.poster
                    this.year = item.year?.toIntOrNull()
                }
            }
            Log.d(TAG, "getMainPage: ${request.name} got ${items.size} items")
            newHomePageResponse(request.name, items, hasNext = resp.page < resp.total_pages)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search: '$query'")
        if (query.isBlank()) return emptyList()
        return try {
            val resp = BingrApi.search(query, "multi")
            val results = resp.results.mapNotNull { item ->
                val type = item.type
                if (type != "movie" && type != "tv") return@mapNotNull null
                val title = item.title
                if (title.isBlank()) return@mapNotNull null
                val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
                newMovieSearchResponse(title, "bingr://$type/${item.id}/$title", tvType) {
                    this.posterUrl = item.poster
                    this.year = item.year?.toIntOrNull()
                }
            }
            Log.d(TAG, "search: got ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    private fun parseDataUrl(url: String): Triple<String, Long, String>? {
        val payload = url.substringAfter("bingr://")
        val parts = payload.split("/")
        if (parts.size < 3) return null
        val type = parts[0]
        val id = parts[1].toLongOrNull() ?: return null
        val title = parts.subList(2, parts.size).joinToString("/")
        if (type !in listOf("movie", "tv") || title.isBlank()) return null
        return Triple(type, id, title)
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load: url=$url")
        val (type, tmdbId, title) = parseDataUrl(url) ?: run {
            Log.e(TAG, "load: failed to parse url=$url")
            return null
        }
        Log.d(TAG, "load: type=$type tmdbId=$tmdbId title='$title'")

        return try {
            val detail = BingrApi.getDetails(type, tmdbId)
            val isSeries = type == "tv"
            val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

            // Build recommendations from similars
            val recommendations = detail?.similars?.take(15)?.mapNotNull { rec ->
                val recType = rec.type
                if (recType != "movie" && recType != "tv") return@mapNotNull null
                val recTitle = rec.title
                if (recTitle.isBlank()) return@mapNotNull null
                newMovieSearchResponse(recTitle, "bingr://$recType/${rec.id}/$recTitle",
                    if (recType == "movie") TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = rec.poster
                    this.year = rec.year?.toIntOrNull()
                }
            } ?: emptyList()

            if (isSeries) {
                // Generate episodes from seasons. Season 0 is Specials.
                val seasons = detail?.seasons ?: emptyList()
                val episodes = mutableListOf<Episode>()
                for (season in seasons) {
                    val seasonNum = season.season
                    val epCount = season.episodes
                    if (epCount <= 0) continue
                    // Fetch episode titles from /episodes endpoint
                    val epList = BingrApi.getEpisodes(tmdbId, seasonNum)
                    if (epList.isNotEmpty()) {
                        for (ep in epList) {
                            episodes.add(newEpisode("bingr://tv/$tmdbId/$seasonNum/${ep.episode}/$title") {
                                this.season = seasonNum
                                this.episode = ep.episode
                                this.name = ep.title ?: "S${seasonNum}E${ep.episode}"
                                this.posterUrl = ep.still
                                this.description = ep.overview
                            })
                        }
                    } else {
                        // Fallback: generate episode numbers without titles
                        for (i in 1..epCount) {
                            episodes.add(newEpisode("bingr://tv/$tmdbId/$seasonNum/$i/$title") {
                                this.season = seasonNum
                                this.episode = i
                                this.name = "S${seasonNum}E$i"
                            })
                        }
                    }
                }
                Log.d(TAG, "load: ${episodes.size} episodes across ${seasons.size} seasons")
                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = detail?.poster
                    this.backgroundPosterUrl = detail?.backdrop_original ?: detail?.backdrop
                    this.plot = detail?.overview
                    this.year = detail?.year?.toIntOrNull()
                    this.tags = detail?.genres ?: emptyList()
                    this.actors = detail?.cast?.take(15)?.map { ActorData(Actor(it.name, it.photo)) } ?: emptyList()
                    this.score = detail?.rating?.let { Score.from10(it) }
                    this.duration = detail?.runtime?.toInt()
                    this.recommendations = recommendations
                }
            } else {
                newMovieLoadResponse(title, url, tvType, "bingr://movie/$tmdbId/$title") {
                    this.posterUrl = detail?.poster
                    this.backgroundPosterUrl = detail?.backdrop_original ?: detail?.backdrop
                    this.plot = detail?.overview
                    this.year = detail?.year?.toIntOrNull()
                    this.tags = detail?.genres ?: emptyList()
                    this.actors = detail?.cast?.take(15)?.map { ActorData(Actor(it.name, it.photo)) } ?: emptyList()
                    this.score = detail?.rating?.let { Score.from10(it) }
                    this.duration = detail?.runtime?.toInt()
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: data=$data")
        return try {
            // Parse the data URL to extract type, id, and optional season/episode
            val parsed = parseLoadData(data)
            if (parsed == null) {
                Log.e(TAG, "loadLinks: failed to parse data=$data")
                return false
            }
            val (type, id, season, episode, title) = parsed
            Log.d(TAG, "loadLinks: type=$type id=$id season=$season ep=$episode title='$title'")

            // Fetch detail for year (improves stream matching)
            val detail = BingrApi.getDetails(type, id)
            val year = detail?.year
            val found = java.util.concurrent.atomic.AtomicBoolean(false)

            // 1. Try all 8 Server 1 sources IN PARALLEL.
            // Each server returns different sources for different titles — parallel is the
            // only way to get all available sources quickly (under 20s).
            coroutineScope {
                BingrApi.SERVER_IDS.map { srv ->
                    async {
                        try {
                            val streamResp = BingrApi.getStream(srv, type, id, title, year, season, episode)
                            if (streamResp != null && streamResp.sources.isNotEmpty()) {
                                val serverName = BingrApi.SERVER_NAMES[srv] ?: srv
                                Log.d(TAG, "loadLinks: $serverName ($srv) returned ${streamResp.sources.size} sources")
                                for (source in streamResp.sources) {
                                    val label = if (source.label.isNotBlank()) source.label else "${serverName} — ${source.quality}"
                                    val linkName = "Bingr | $serverName — ${source.language} — $label"
                                    emitSource(linkName, source, callback)
                                    found.set(true)
                                }
                                // Emit any subtitles from the stream response
                                for (sub in streamResp.subtitles) {
                                    try {
                                        subtitleCallback.invoke(SubtitleFile(sub.lang, sub.url))
                                    } catch (e: Exception) {
                                        Log.e(TAG, "loadLinks: subtitle emit error: ${e.message}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadLinks: $srv error: ${e.message}")
                        }
                    }
                }.awaitAll()
            }

            // 2. Fetch multi-audio MP4 sources from /languages endpoint.
            // These are separate MP4 URLs for different audio languages (e.g., Tagalog).
            try {
                val langSources = BingrApi.getLanguages(type, id, title, year, season, episode)
                if (langSources.isNotEmpty()) {
                    Log.d(TAG, "loadLinks: /languages returned ${langSources.size} sources")
                    for (lsrc in langSources) {
                        val quality = parseQuality(lsrc.quality)
                        val linkName = "Bingr | Lang — ${lsrc.language} — ${lsrc.label}"
                        val link = newExtractorLink("Bingr", linkName, lsrc.url, ExtractorLinkType.VIDEO) {
                            this.quality = quality
                        }
                        callback.invoke(link)
                        found.set(true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks: /languages error: ${e.message}")
            }

            // 3. Fetch VTT subtitles from /subtitles/vdrk endpoint.
            try {
                val subs = BingrApi.getSubtitles(type, id, season, episode)
                if (subs.isNotEmpty()) {
                    Log.d(TAG, "loadLinks: got ${subs.size} subtitles")
                    for (sub in subs) {
                        try {
                            subtitleCallback.invoke(SubtitleFile(sub.lang, sub.url))
                        } catch (e: Exception) {
                            Log.e(TAG, "loadLinks: subtitle emit error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks: subtitles error: ${e.message}")
            }

            Log.d(TAG, "loadLinks: done, found=${found.get()}")
            found.get()
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: ${e.message}")
            false
        }
    }

    // Emit a single source as an ExtractorLink with the correct type and headers.
    // Uses the ExtractorLink constructor directly (not the suspend newExtractorLink helper)
    // because we're inside an async coroutine block and the constructor is non-suspend.
    private suspend fun emitSource(
        linkName: String,
        source: BingrSource,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = when {
            source.isMP4 -> ExtractorLinkType.VIDEO
            source.type.contains("mp4") -> ExtractorLinkType.VIDEO
            source.type.contains("mpegurl") -> ExtractorLinkType.M3U8
            source.type.contains("dash") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.M3U8
        }
        val quality = parseQuality(source.quality)
        val link = newExtractorLink("Bingr", linkName, source.url, type) {
            this.quality = quality
            // Some sources require a Referer header (e.g., Sirius needs Referer: https://hdghartv.cc/)
            if (source.headers.isNotEmpty()) {
                this.headers = source.headers
                source.headers["Referer"]?.let { this.referer = it }
            }
        }
        callback.invoke(link)
    }

    // Parse quality string ("1080p", "720p", "480p", "Auto", "HD") into Qualities int.
    private fun parseQuality(q: String?): Int {
        if (q == null) return Qualities.Unknown.value
        return when {
            q.contains("2160") || q.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            q.contains("1080") -> Qualities.P1080.value
            q.contains("720") -> Qualities.P720.value
            q.contains("480") -> Qualities.P480.value
            q.contains("360") -> Qualities.P360.value
            q.contains("auto", ignoreCase = true) -> Qualities.Unknown.value
            q.contains("hd", ignoreCase = true) -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }
    }

    // Parse the data URL for loadLinks.
    // Movie: "bingr://movie/{tmdbId}/{title}"
    // TV:    "bingr://tv/{tmdbId}/{season}/{episode}/{title}"
    private fun parseLoadData(data: String): ParsedLoadData? {
        return try {
            val payload = data.substringAfter("bingr://")
            val parts = payload.split("/")
            when {
                parts.size >= 3 && parts[0] == "movie" -> {
                    val id = parts[1].toLongOrNull() ?: return null
                    val title = parts.subList(2, parts.size).joinToString("/")
                    ParsedLoadData("movie", id, null, null, title)
                }
                parts.size >= 5 && parts[0] == "tv" -> {
                    val id = parts[1].toLongOrNull() ?: return null
                    val season = parts[2].toIntOrNull() ?: return null
                    val episode = parts[3].toIntOrNull() ?: return null
                    val title = parts.subList(4, parts.size).joinToString("/")
                    ParsedLoadData("tv", id, season, episode, title)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class ParsedLoadData(
        val type: String,
        val id: Long,
        val season: Int?,
        val episode: Int?,
        val title: String
    )
}
