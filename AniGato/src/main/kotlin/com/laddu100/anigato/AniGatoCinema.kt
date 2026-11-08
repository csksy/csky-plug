package com.laddu100.anigato

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

// cinema titles come from tmdb, sub and dub are separate audio calls on the
// same endpoint, so unlike the anime provider the separation shows up as
// labelled links instead of a switcher
class AniGatoCinemaProvider : MainAPI() {

    override var mainUrl = AniGatoApi.MAIN_URL
    override var name = "AniGato Cinema"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movie:popular" to "Popular Movies",
        "movie:top" to "Top Rated Movies",
        "series:popular" to "Popular Series",
        "series:trending" to "Trending Series"
    )

    companion object {
        private const val TAG = "AniGatoCinema"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val (type, kind) = request.data.split(":")
        val feed = AniGatoApi.cinemaFeed(type, kind, page) ?: return null
        val items = feed.media.orEmpty().mapNotNull { it.toSearchResponse() }
        val hasNext = feed.hasNextPage == true || (items.isNotEmpty() && page < 400)
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return coroutineScope {
            val movies = async { AniGatoApi.cinemaSearch(query, "movie") }
            val series = async { AniGatoApi.cinemaSearch(query, "series") }
            (movies.await() + series.await()).mapNotNull { it.toSearchResponse() }
        }
    }

    private fun CinemaFeedItem.toSearchResponse(): SearchResponse? {
        val tmdbId = tmdbId ?: return null
        val title = this.title?.display() ?: return null
        val isSeries = (type ?: kind) == "series"
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, "$mainUrl/cinema/$tvType/$tmdbId", tvType) {
            this.posterUrl = coverImage?.best() ?: poster
            this.year = year
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.trimEnd('/').split("/")
        val tmdbId = parts.lastOrNull()?.toIntOrNull() ?: return null
        val type = if (parts.contains("TvSeries")) "series" else "movie"

        val meta = AniGatoApi.cinemaMeta(type, tmdbId) ?: return null
        val title = meta.title?.display() ?: return null
        val imdbId = meta.imdbId?.takeIf { it.isNotBlank() }

        val plot = meta.description?.let { html ->
            html.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "").trim()
        }
        val year = meta.seasonYear
            ?: meta.releaseInfo?.take(4)?.toIntOrNull()

        if (type == "movie") {
            val data = AniGatoCinemaData(
                tmdbId = tmdbId,
                imdbId = imdbId,
                type = "movie",
                title = title
            ).toJson()
            val response = newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = meta.coverImage?.best()
                this.backgroundPosterUrl = meta.bannerImage
                this.plot = plot
                this.tags = meta.genres.orEmpty()
                this.year = year
                this.duration = meta.duration
                meta.averageScore?.let { score = com.lagradost.cloudstream3.Score.from100(it) }
            }
            meta.trailer?.let { trailer ->
                if (trailer.site == "youtube" && !trailer.id.isNullOrBlank()) {
                    try {
                        response.addTrailer("https://www.youtube.com/watch?v=${trailer.id}")
                    } catch (e: Exception) {
                        Log.d(TAG, "trailer: ${e.message}")
                    }
                }
            }
            return response
        }

        // the season list may include a 0 for specials, the site filters it out
        val seasons = meta.seasonList.orEmpty().filter { it > 0 }
            .ifEmpty { (1..(meta.seasons ?: 1)).toList() }
        if (seasons.isEmpty()) return null

        val episodes = seasons.flatMap { season ->
            AniGatoApi.cinemaEpisodes(tmdbId, season).mapIndexed { _, entry ->
                val num = entry.number ?: return@mapIndexed null
                val data = AniGatoCinemaData(
                    tmdbId = tmdbId,
                    imdbId = imdbId,
                    type = "series",
                    season = season,
                    episode = num,
                    title = title
                ).toJson()
                newEpisode(data) {
                    this.season = season
                    this.episode = num
                    this.name = entry.title?.takeIf { it.isNotBlank() && !it.startsWith("Episode") }
                    this.posterUrl = entry.thumbnail?.takeIf { it.startsWith("http") }
                    this.description = entry.description?.takeIf { it.isNotBlank() }
                }
            }.filterNotNull()
        }
        if (episodes.isEmpty()) return null

        val showStatus = when (meta.status?.lowercase()) {
            "ended", "canceled" -> ShowStatus.Completed
            "returning series", "in production" -> ShowStatus.Ongoing
            else -> null
        }

        val seriesResponse = newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = meta.coverImage?.best()
            this.backgroundPosterUrl = meta.bannerImage
            this.plot = plot
            this.tags = meta.genres.orEmpty()
            this.year = year
            this.showStatus = showStatus
            meta.averageScore?.let { score = com.lagradost.cloudstream3.Score.from100(it) }
        }
        meta.trailer?.let { trailer ->
            if (trailer.site == "youtube" && !trailer.id.isNullOrBlank()) {
                try {
                    seriesResponse.addTrailer("https://www.youtube.com/watch?v=${trailer.id}")
                } catch (e: Exception) {
                    Log.d(TAG, "trailer: ${e.message}")
                }
            }
        }
        return seriesResponse
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = try {
            parseJson<AniGatoCinemaData>(data)
        } catch (e: Exception) {
            Log.d(TAG, "cinema data parse: ${e.message}")
            return false
        }
        val tmdbId = ref.tmdbId ?: return false
        val type = if (ref.type == "series") "series" else "movie"
        val title = ref.title.orEmpty()

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubs = ConcurrentHashMap.newKeySet<String>()

        val results = coroutineScope {
            val subJob = async(Dispatchers.IO) {
                emitAudio(
                    ref, tmdbId, type, title, "sub",
                    seenLinks, seenSubs, subtitleCallback, callback
                )
            }
            val dubJob = async(Dispatchers.IO) {
                emitAudio(
                    ref, tmdbId, type, title, "dub",
                    seenLinks, seenSubs, subtitleCallback, callback
                )
            }
            // cinema subtitles are a single shared pool for the whole title,
            // one track per language keeps the picker readable
            val subsJob = async(Dispatchers.IO) {
                emitCinemaSubtitles(ref, tmdbId, type, seenSubs, subtitleCallback)
            }
            subsJob.await()
            listOf(subJob.await(), dubJob.await())
        }

        return results.any { it }
    }

    private suspend fun emitCinemaSubtitles(
        ref: AniGatoCinemaData,
        tmdbId: Int,
        type: String,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val subs = AniGatoApi.cinemaSubtitles(tmdbId, type, ref.imdbId, ref.season, ref.episode)
        val seenLangs = ConcurrentHashMap.newKeySet<String>()
        for (track in subs) {
            val subUrl = track.url?.takeIf { it.isNotBlank() } ?: continue
            val absolute = AniGatoApi.absolute(subUrl)
            if (!seenSubs.add(absolute)) continue
            val lang = track.lang?.takeIf { it.isNotBlank() } ?: "en"
            if (!seenLangs.add(lang)) continue
            val label = track.label?.takeIf { it.isNotBlank() } ?: lang
            try {
                subtitleCallback.invoke(
                    newSubtitleFile(label, absolute) {
                        this.headers = mapOf("Referer" to "${AniGatoApi.MAIN_URL}/")
                    }
                )
            } catch (e: Exception) {
                Log.d(TAG, "cinema subtitle emit: ${e.message}")
            }
        }
    }

    private suspend fun emitAudio(
        ref: AniGatoCinemaData,
        tmdbId: Int,
        type: String,
        title: String,
        audio: String,
        seenLinks: MutableSet<String>,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = AniGatoApi.cinemaStream(
            tmdbId, type, ref.imdbId, ref.season, ref.episode, audio
        ) ?: return false
        val streams = response.streams.orEmpty()
        if (streams.isEmpty() || response.dmca == true || response.unavailable == true) return false

        val audioLabel = if (audio == "dub") "Dub" else "Sub"

        val results = coroutineScope {
            streams.map { stream ->
                async(Dispatchers.IO) {
                    val rawUrl = stream.url?.takeIf { it.isNotBlank() } ?: return@async false
                    if (AniGatoApi.isSelfHostedEncrypted(rawUrl) && rawUrl.startsWith("http")) return@async false
                    val url = AniGatoApi.absolute(rawUrl)

                    val validated = AniGatoApi.validateStream(url) ?: return@async false
                    // the same server often answers for both audio calls, the
                    // audio tag keeps one entry per mode in the picker
                    if (!seenLinks.add("$audio:${validated.url}")) return@async false

                    val serverName = stream.serverName ?: stream.name ?: "Server"
                    val qualityLabel = stream.quality?.takeIf { it.isNotBlank() && it != "auto" }
                    val height = validated.maxHeight ?: qualityLabel?.let { qualityHeight(it) }
                    val label = buildString {
                        append(serverName)
                        if (height != null) append(" ${height}p")
                        append(" ($audioLabel)")
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = label,
                            url = validated.url,
                            type = if (validated.isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                        ) {
                            height?.let { quality = it }
                            this.headers = mapOf(
                                "User-Agent" to AniGatoApi.browserHeaders["User-Agent"]!!,
                                "Referer" to "${AniGatoApi.MAIN_URL}/"
                            )
                        }
                    )

                    // the vidlink server carries its own subtitle set, the rest
                    // rely on the shared pool
                    stream.subtitles.orEmpty().forEach { track ->
                        val subUrl = track.url?.takeIf { it.isNotBlank() } ?: return@forEach
                        val absolute = AniGatoApi.absolute(subUrl)
                        if (!seenSubs.add(absolute)) return@forEach
                        val subLabel = track.label?.takeIf { it.isNotBlank() }
                            ?: track.lang?.takeIf { it.isNotBlank() }
                            ?: "English"
                        try {
                            subtitleCallback.invoke(
                                newSubtitleFile(subLabel, absolute) {
                                    this.headers = mapOf("Referer" to "${AniGatoApi.MAIN_URL}/")
                                }
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "vidlink subtitle emit: ${e.message}")
                        }
                    }
                    true
                }
            }
        }.map { it.await() }

        return results.any { it }
    }

    private fun qualityHeight(label: String): Int? = when {
        label.contains("4K", true) || label.contains("2160", true) -> 2160
        label.contains("1080", true) -> 1080
        label.contains("720", true) -> 720
        label.contains("480", true) -> 480
        label.contains("360", true) -> 360
        else -> null
    }
}
