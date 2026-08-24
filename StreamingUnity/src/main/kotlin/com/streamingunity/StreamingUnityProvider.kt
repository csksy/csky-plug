package com.streamingunity

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup

class StreamingUnityProvider : MainAPI() {
    override var mainUrl = "https://streamingunity.vip"
    override var name = "StreamingUnity"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"

    companion object {
        var context: Context? = null
        private const val TAG = "StreamingUnity"
        private val mapper = ObjectMapper()
        private const val CDN = "https://cdn.streamingunity.vip/images"
    }

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    override val mainPage = mainPageOf(
        "/" to "Home",
    )

    private fun pageProps(html: String): JsonNode? {
        val doc = Jsoup.parse(html)
        val raw = doc.selectFirst("[data-page]")?.attr("data-page") ?: return null
        return try { mapper.readTree(raw) } catch (e: Exception) { null }
    }

    private fun imageUrl(filename: String?): String? {
        if (filename.isNullOrBlank()) return null
        return "$CDN/$filename"
    }

    private fun JsonNode?.str(field: String): String? = this?.get(field)?.asText()?.takeIf { it.isNotBlank() }
    private fun JsonNode?.int(field: String): Int? = this?.get(field)?.asInt()

    private fun titleToSearch(node: JsonNode): SearchResponse? {
        val id = node.get("id")?.asInt() ?: return null
        val slug = node.str("slug") ?: return null
        val name = node.str("name") ?: return null
        val type = node.str("type") ?: "movie"
        val poster = node.get("images")?.find { it.str("type") == "poster" }?.let { imageUrl(it.str("filename")) }
        val tvType = if (type == "tv") TvType.TvSeries else TvType.Movie
        val url = "$mainUrl/en/titles/$id-$slug"
        return newMovieSearchResponse(name, url, tvType) { this.posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/en/", headers = baseHeaders, timeout = 20_000L).text
        val props = pageProps(html)?.get("props") ?: return newHomePageResponse("Home", emptyList())
        val sliders = props.get("sliders") ?: return newHomePageResponse("Home", emptyList())
        val lists = mutableListOf<HomePageList>()
        for (slider in sliders) {
            val label = slider.str("label") ?: slider.str("name") ?: continue
            val titles = slider.get("titles")?.mapNotNull { titleToSearch(it) } ?: emptyList()
            if (titles.isNotEmpty()) lists.add(HomePageList(label, titles, false))
        }
        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val html = try {
            app.get("$mainUrl/en/search?q=$encoded", headers = baseHeaders, timeout = 15_000L).text
        } catch (e: Exception) { return emptyList() }
        val props = pageProps(html)?.get("props") ?: return emptyList()
        return props.get("titles")?.mapNotNull { titleToSearch(it) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = baseHeaders, timeout = 20_000L).text
        val props = pageProps(html)?.get("props") ?: return null
        val title = props.get("title") ?: return null
        val name = title.str("name") ?: return null
        val type = title.str("type") ?: "movie"
        val poster = title.get("images")?.find { it.str("type") == "poster" }?.let { imageUrl(it.str("filename")) }
        val bg = title.get("images")?.find { it.str("type") == "background" }?.let { imageUrl(it.str("filename")) }
        val plot = title.str("plot")
        val year = title.str("release_date")?.substring(0, 4)?.toIntOrNull()
        val score = title.str("score")?.toDoubleOrNull()
        val genres = title.get("genres")?.mapNotNull { it.str("name") } ?: emptyList()

        val titleId = title.int("id") ?: return null
        val slug = title.str("slug") ?: return null
        val seasons = title.get("seasons") ?: mapper.createArrayNode()

        if (type == "movie" || seasons.size() == 0) {
            val ep = props.get("loadedSeason")?.get("episodes")?.firstOrNull()
            val epId = ep?.int("id") ?: return null
            val data = "$titleId|$epId"
            return newMovieLoadResponse(name, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.plot = plot
                this.year = year
                this.tags = genres
                this.score = score?.let { Score.from10(it.toString()) }
            }
        }

        val seasonLists = coroutineScope {
            seasons.mapNotNull { season ->
                val num = season.int("number") ?: return@mapNotNull null
                async {
                    val sHtml = app.get("$url/season-$num", headers = baseHeaders, timeout = 15_000L).text
                    val sProps = pageProps(sHtml)?.get("props")?.get("loadedSeason")
                    num to (sProps?.get("episodes") ?: mapper.createArrayNode())
                }
            }.awaitAll()
        }

        val episodes = mutableListOf<Episode>()
        for ((seasonNum, eps) in seasonLists.sortedBy { it.first }) {
            for (ep in eps) {
                val epNum = ep.int("number") ?: continue
                val epId = ep.int("id") ?: continue
                val epName = ep.str("name")
                val epPlot = ep.str("plot")
                val data = "$titleId|$epId"
                episodes.add(newEpisode(data) {
                    this.season = seasonNum
                    this.episode = epNum
                    this.name = epName
                    this.description = epPlot
                })
            }
        }

        return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = plot
            this.year = year
            this.tags = genres
            this.score = score?.let { Score.from10(it.toString()) }
        }
    }

    private fun loadUrl(): String = mainUrl

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 2) return false
        val titleId = parts[0]
        val episodeId = parts[1]

        val iframeUrl = "$mainUrl/en/iframe/$titleId?episode_id=$episodeId&next_episode=0"
        val iframeHtml = try {
            app.get(iframeUrl, headers = baseHeaders, timeout = 15_000L).text
        } catch (e: Exception) {
            Log.e(TAG, "iframe: ${e.message}")
            return false
        }

        val embedMatch = Regex("https://vixcloud\\.co/embed/[^\"'&<>]+(?:&amp;[^\"'<>]+)*").find(iframeHtml)
        val embedUrl = embedMatch?.value?.replace("&amp;", "&") ?: return false

        val resolver = WebViewResolver(
            interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
            additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
            script = """document.querySelector('button,[role="button"],.vjs-big-play-button,.jw-icon-display,.vds-play-button')?.click();""",
            useOkhttp = false,
            timeout = 30_000L
        )

        return try {
            val resolved = app.get(embedUrl, referer = mainUrl, interceptor = resolver, timeout = 35_000L).url
            if (resolved.contains(".m3u8", true) || resolved.contains(".mp4", true)) {
                val linkType = if (resolved.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(
                    newExtractorLink(
                        "StreamingUnity", "StreamingUnity", resolved, linkType
                    ) {
                        this.headers = mapOf("Referer" to "https://vixcloud.co/")
                    }
                )
                true
            } else {
                Log.d(TAG, "no media url resolved")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolve: ${e.message}")
            false
        }
    }
}
