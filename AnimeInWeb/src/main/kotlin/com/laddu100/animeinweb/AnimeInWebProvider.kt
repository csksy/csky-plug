package com.laddu100.animeinweb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class AnimeInWebProvider : MainAPI() {
    override var mainUrl = "https://animeinweb.com"
    override var name = "AnimeInWeb"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.TvSeries)

    private val proxySecret = "animein-secure-proxy-key-123"
    private val imgHost = "https://xyz-api.animein.net"
    private val apiHeaders = mapOf(
        "x-proxy-secret" to proxySecret,
        "Accept" to "application/json",
        "User-Agent" to USER_AGENT
    )
    private val posterHeaders get() = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")

    // the image host rejects requests without the site's own referer
    private fun fixPosterUrl(url: String?): String? {
        val value = url?.takeIf { it.isNotBlank() } ?: return null
        return if (value.startsWith("http")) value else "$imgHost$value"
    }

    private fun apiUrl(path: String): String = "$mainUrl/api/proxy/3/2$path"

    private suspend inline fun <reified T : Any> fetchJson(url: String): T {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val text = app.get(url, headers = apiHeaders).text
                return parseJson<T>(text)
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "[fetch] ${url.substringAfter("/api/proxy")} attempt ${attempt + 1} failed: ${e.message}")
                delay(800L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("fetch failed")
    }

    private fun tvTypeOf(type: String?): TvType = when (type?.uppercase()) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA", "ONA", "SPECIAL" -> TvType.OVA
        "LIVE ACTION" -> TvType.TvSeries
        else -> TvType.Anime
    }

    private fun showStatusOf(status: String?): ShowStatus? = when (status?.uppercase()) {
        "ONGOING" -> ShowStatus.Ongoing
        "FINISHED" -> ShowStatus.Completed
        else -> null
    }

    private fun qualityValue(label: String?): Int = when {
        label?.contains("1080") == true -> Qualities.P1080.value
        label?.contains("720") == true -> Qualities.P720.value
        label?.contains("480") == true -> Qualities.P480.value
        label?.contains("360") == true -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    // the schedule API only answers full Indonesian day names
    private fun todayIndonesianDay(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"))
        val names = arrayOf("MINGGU", "SENIN", "SELASA", "RABU", "KAMIS", "JUMAT", "SABTU")
        return names[cal.get(Calendar.DAY_OF_WEEK) - 1]
    }

    private fun parseAirDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val sdf = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
            sdf.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
            sdf.parse(value.trim())?.time
        }.getOrNull()
    }

    private fun MovieItem.toSearchResponse(): SearchResponse {
        val url = "$mainUrl/anime/$id"
        val poster = fixPosterUrl(image_poster)
        val type = tvTypeOf(this.type)
        val titleValue = title?.takeIf { it.isNotBlank() } ?: "Unknown"
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(titleValue, url, TvType.TvSeries) {
                addPoster(poster, posterHeaders)
            }
            else -> newAnimeSearchResponse(titleValue, url, type) {
                addPoster(poster, posterHeaders)
            }
        }
    }

    override val mainPage = mainPageOf(
        "latest" to "Terbaru",
        "views" to "Populer",
        "schedule" to "Jadwal Hari Ini",
        "home:hot" to "Hot",
        "home:waiting" to "Akan Tayang",
        "home:random" to "Acak"
    )

    override suspend fun getMainPage(page: Int, request: com.lagradost.cloudstream3.MainPageRequest): com.lagradost.cloudstream3.HomePageResponse {
        return when {
            request.data == "latest" || request.data == "views" -> explorePage(page, request, request.data)
            request.data == "schedule" -> {
                if (page > 0) return newHomePageResponse(request, emptyList(), false)
                val day = todayIndonesianDay()
                val items = fetchJson<ScheduleEnvelope>("${apiUrl("/schedule/data")}?day=$day")
                    .data.movie.map { it.toSearchResponse() }
                Log.d(TAG, "[main] schedule $day: ${items.size} titles")
                newHomePageResponse(request, items, false)
            }
            request.data.startsWith("home:") -> {
                if (page > 0) return newHomePageResponse(request, emptyList(), false)
                val key = request.data.removePrefix("home:")
                val data = fetchJson<HomeEnvelope>("${apiUrl("/home/data")}?day=${todayIndonesianDay()}&limit=16").data
                val list = when (key) {
                    "hot" -> data.hot
                    "waiting" -> data.waiting
                    "random" -> data.random
                    else -> emptyList()
                }
                Log.d(TAG, "[main] home:$key -> ${list.size} titles")
                newHomePageResponse(request, list.map { it.toSearchResponse() }, false)
            }
            else -> explorePage(page, request, "latest")
        }
    }

    private suspend fun explorePage(
        page: Int,
        request: com.lagradost.cloudstream3.MainPageRequest,
        sort: String
    ): com.lagradost.cloudstream3.HomePageResponse {
        val res = fetchJson<ExploreEnvelope>("${apiUrl("/explore/movie")}?page=$page&sort=$sort&keyword=")
        val items = res.data.movie.map { it.toSearchResponse() }
        return newHomePageResponse(request, items, items.size >= EXPLORE_PAGE_SIZE)
    }

    override suspend fun search(query: String, page: Int): com.lagradost.cloudstream3.SearchResponseList? {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val encoded = URLEncoder.encode(query, "UTF-8")
        // cloudstream search pages start at 1, the API at 0
        val res = fetchJson<ExploreEnvelope>("${apiUrl("/explore/movie")}?page=${page - 1}&sort=&keyword=$encoded")
        val items = res.data.movie.map { it.toSearchResponse() }
        Log.d(TAG, "[search] '$query' page $page: ${items.size} results")
        return newSearchResponseList(items, items.size >= EXPLORE_PAGE_SIZE)
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: return null
        val detail = fetchJson<DetailEnvelope>("${apiUrl("/movie/detail")}/$id").data
        val movie = detail.movie
        val title = movie.title?.takeIf { it.isNotBlank() } ?: return null
        val tvType = tvTypeOf(movie.type)

        val episodes = fetchEpisodePages(id)
            .reversed()
            .distinctBy { it.id }
            .mapIndexed { idx, ep ->
                newEpisode(ep.id) {
                    this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode ${ep.index ?: (idx + 1)}"
                    this.episode = ep.index?.toIntOrNull() ?: (idx + 1)
                    this.date = parseAirDate(ep.key_time)
                }
            }

        Log.d(TAG, "[load] $title type=${movie.type} eps=${episodes.size}")

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.plot = movie.synopsis
                this.tags = movie.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                this.year = movie.year?.toIntOrNull()
                this.showStatus = showStatusOf(movie.status)
                addPoster(fixPosterUrl(movie.image_poster), posterHeaders)
            }
        } else {
            newAnimeLoadResponse(title, url, tvType) {
                this.plot = movie.synopsis
                this.tags = movie.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                this.year = movie.year?.toIntOrNull()
                this.showStatus = showStatusOf(movie.status)
                this.synonyms = movie.synonyms?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }
                addPoster(fixPosterUrl(movie.image_poster), posterHeaders)
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // the API serves 30 episodes per page, newest first, with no page-size
    // override, so full lists need pages walked in parallel batches
    private suspend fun fetchEpisodePages(id: String): List<EpisodeItem> {
        val first = episodePage(id, 0)
        if (first.size < EPISODES_PER_PAGE) return first
        val all = mutableListOf<EpisodeItem>()
        all.addAll(first)
        coroutineScope {
            var start = 1
            while (start <= MAX_EPISODE_PAGES) {
                val range = start until start + PAGE_BATCH
                val pages = range.map { p ->
                    async {
                        runCatching { episodePage(id, p) }.getOrDefault(emptyList())
                    }
                }.awaitAll()
                pages.forEach { all.addAll(it) }
                if (pages.any { it.size < EPISODES_PER_PAGE }) break
                start += PAGE_BATCH
            }
        }
        return all
    }

    private suspend fun episodePage(id: String, page: Int): List<EpisodeItem> {
        val res = fetchJson<EpisodeListEnvelope>("${apiUrl("/movie/episode")}/$id?page=$page")
        return res.data.episode
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val stream = fetchJson<StreamEnvelope>("${apiUrl("/episode/streamnew")}/$data").data
        var found = false

        for (server in stream.server) {
            val link = server.link?.takeIf { it.isNotBlank() } ?: continue
            when (server.type) {
                "direct" -> {
                    val label = server.name?.takeIf { it.isNotBlank() } ?: "RAPSODI"
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = label,
                            url = link,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = qualityValue(server.quality)
                            this.referer = "$mainUrl/"
                        }
                    )
                    found = true
                }
                else -> {
                    // the site's own player only streams "direct" servers; the
                    // remaining "semi" hosts are legacy entries, most long dead
                    val host = runCatching { URI(link).host ?: "" }.getOrDefault("")
                    if (host == "www.blogger.com" || host == "gdplayer.to") {
                        try {
                            if (loadExtractor(link, "$mainUrl/", subtitleCallback, callback)) found = true
                        } catch (e: Exception) {
                            Log.e(TAG, "[loadLinks] extractor failed for $host: ${e.message}")
                        }
                    } else {
                        Log.d(TAG, "[loadLinks] skip dead host $host")
                    }
                }
            }
        }

        Log.d(TAG, "[loadLinks] ep=$data found=$found")
        return found
    }

    companion object {
        private const val TAG = "AnimeInWeb"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val EXPLORE_PAGE_SIZE = 60
        private const val EPISODES_PER_PAGE = 30
        private const val PAGE_BATCH = 6
        private const val MAX_EPISODE_PAGES = 60

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class MovieItem(
            val id: String = "",
            val title: String? = null,
            val synopsis: String? = null,
            val synonyms: String? = null,
            val image_poster: String? = null,
            val image_cover: String? = null,
            val type: String? = null,
            val year: String? = null,
            val day: String? = null,
            val status: String? = null,
            val studio: String? = null,
            val aired_start: String? = null,
            val genre: String? = null
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class EpisodeItem(
            val id: String = "",
            val index: String? = null,
            val title: String? = null,
            val id_movie: String? = null,
            val key_time: String? = null,
            val image: String? = null
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class ServerItem(
            val id: String = "",
            val link: String? = null,
            val quality: String? = null,
            val key_file_size: String? = null,
            val name: String? = null,
            val type: String? = null,
            val domain: String? = null,
            val username: String? = null,
            val server_id: String? = null
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class HomeData(
            val hot: List<MovieItem> = emptyList(),
            val `new`: List<MovieItem> = emptyList(),
            val popular: List<MovieItem> = emptyList(),
            val waiting: List<MovieItem> = emptyList(),
            val random: List<MovieItem> = emptyList(),
            val today: List<MovieItem> = emptyList()
        )

        data class HomeEnvelope(val data: HomeData = HomeData())

        data class ExploreData(val movie: List<MovieItem> = emptyList())
        data class ExploreEnvelope(val data: ExploreData = ExploreData())

        data class ScheduleData(val movie: List<MovieItem> = emptyList())
        data class ScheduleEnvelope(val data: ScheduleData = ScheduleData())

        data class DetailData(val movie: MovieItem = MovieItem(), val episode: EpisodeItem? = null)
        data class DetailEnvelope(val data: DetailData = DetailData())

        data class EpisodeListData(val episode: List<EpisodeItem> = emptyList())
        data class EpisodeListEnvelope(val data: EpisodeListData = EpisodeListData())

        data class StreamData(
            val episode: EpisodeItem? = null,
            val episode_next: EpisodeItem? = null,
            val server: List<ServerItem> = emptyList()
        )
        data class StreamEnvelope(val data: StreamData = StreamData())
    }
}
