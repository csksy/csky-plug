package com.streamingunity

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
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

        private val browserHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept-Language" to "en-US,en;q=0.9",
        )

        private val xhrHeaders = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
        )

        // VixCloud serves everything from the playlist endpoint, keep the
        // embed origin on every media request so CDN edges never 403.
        private val vixHeaders = mapOf(
            "Origin" to "https://vixcloud.co",
            "Referer" to "https://vixcloud.co/",
        )
    }

    override val mainPage = mainPageOf(
        "top10" to "Top 10 Today",
        "trending-movie" to "Trending Movies",
        "trending-tv" to "Trending TV Shows",
        "latest" to "Recently Added",
        "new-episodes" to "New Episodes",
    )

    private fun pageProps(html: String): JsonNode? {
        val raw = Jsoup.parse(html).selectFirst("[data-page]")?.attr("data-page") ?: return null
        return try {
            mapper.readTree(raw.replace("&quot;", "\"").replace("&amp;", "&"))
        } catch (e: Exception) {
            null
        }
    }

    private fun imageUrl(filename: String?): String? {
        if (filename.isNullOrBlank()) return null
        return "$CDN/$filename"
    }

    private fun JsonNode?.str(field: String): String? =
        this?.get(field)?.asText()?.takeIf { it.isNotBlank() }

    private fun JsonNode?.int(field: String): Int? = this?.get(field)?.asInt()

    private fun titleToSearch(node: JsonNode): SearchResponse? {
        val id = node.int("id") ?: return null
        val slug = node.str("slug") ?: return null
        val name = node.str("name") ?: return null
        val type = node.str("type") ?: "movie"
        val poster = node.get("images")?.find { it.str("type") == "poster" }?.let { imageUrl(it.str("filename")) }
        val tvType = if (type == "tv") TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(name, "$mainUrl/en/titles/$id-$slug", tvType) {
            this.posterUrl = poster
        }
    }

    private suspend fun browseTitles(section: String, page: Int): List<JsonNode> {
        val path = when (section) {
            "top10" -> {
                if (page > 1) return emptyList()
                "browse/top10?lang=en"
            }
            "trending-movie" -> "browse/trending?lang=en&type=movie&page=$page"
            "trending-tv" -> "browse/trending?lang=en&type=tv&page=$page"
            "latest" -> "browse/latest?lang=en&page=$page"
            "new-episodes" -> "browse/new-episodes?lang=en&page=$page"
            else -> return emptyList()
        }
        return try {
            val resp = app.get("$mainUrl/en/$path", headers = browserHeaders + xhrHeaders, timeout = 20_000L)
            mapper.readTree(resp.text).let { node ->
                (node.get("titles") ?: node.get("data"))?.filter { !it.isNull } ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "browse $section: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val titles = browseTitles(request.data, page).mapNotNull { titleToSearch(it) }
        val hasNext = titles.size >= 60
        return newHomePageResponse(request.name, titles, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return try {
            val html = app.get("$mainUrl/en/search?q=$encoded", headers = browserHeaders, timeout = 15_000L).text
            pageProps(html)?.get("props")?.get("titles")?.mapNotNull { titleToSearch(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders, timeout = 20_000L).text
        val props = pageProps(html)?.get("props") ?: return null
        val title = props.get("title") ?: return null
        val name = title.str("name") ?: return null
        val titleId = title.int("id") ?: return null

        val images = title.get("images")
        val poster = images?.find { it.str("type") == "poster" }?.let { imageUrl(it.str("filename")) }
        val bg = images?.find { it.str("type") == "background" }?.let { imageUrl(it.str("filename")) }
        val score = title.str("score")?.replace(",", ".")?.toDoubleOrNull()

        val movieBuilder: suspend MovieLoadResponse.() -> Unit = {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = title.str("plot")
            this.year = title.str("release_date")?.take(4)?.toIntOrNull()
            this.tags = title.get("genres")?.mapNotNull { it.str("name") } ?: emptyList()
            this.duration = title.int("runtime")
            this.score = score?.let { Score.from10(it) }
        }

        val loadedSeason = props.get("loadedSeason")
        val seasons = title.get("seasons")
        if (loadedSeason == null || seasons == null || seasons.size() == 0) {
            // Movies (and titles without any season data) resolve straight to
            // the title-level iframe: /en/iframe/{titleId}
            return newMovieLoadResponse(name, url, TvType.Movie, titleId.toString(), movieBuilder)
        }

        val loadedNumber = loadedSeason.int("number")
        val seasonLists = coroutineScope {
            seasons.mapNotNull { season ->
                val num = season.int("number") ?: return@mapNotNull null
                async {
                    val episodes = if (num == loadedNumber) {
                        loadedSeason.get("episodes")
                    } else {
                        try {
                            val sHtml = app.get("$url/season-$num", headers = browserHeaders, timeout = 15_000L).text
                            pageProps(sHtml)?.get("props")?.get("loadedSeason")?.get("episodes")
                        } catch (e: Exception) {
                            null
                        }
                    }
                    num to (episodes ?: mapper.createArrayNode())
                }
            }.awaitAll()
        }

        val episodes = mutableListOf<Episode>()
        for ((seasonNum, eps) in seasonLists.sortedBy { it.first }) {
            for (ep in eps) {
                val epNum = ep.int("number") ?: continue
                val epId = ep.int("id") ?: continue
                episodes.add(
                    newEpisode("$titleId|$epId") {
                        this.season = seasonNum
                        this.episode = epNum
                        this.name = ep.str("name")
                        this.description = ep.str("plot")
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(name, url, TvType.Movie, titleId.toString(), movieBuilder)
        }

        return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = title.str("plot")
            this.year = title.str("release_date")?.take(4)?.toIntOrNull()
            this.tags = title.get("genres")?.mapNotNull { it.str("name") } ?: emptyList()
            this.score = score?.let { Score.from10(it) }
        }
    }

    /** Strips html escapes the iframe markup carries inside attribute values. */
    private fun extractEmbedUrl(iframeHtml: String): String? {
        return Regex("""https://vixcloud\.co/embed/[^"'\s<>]+""")
            .find(iframeHtml)?.value?.replace("&amp;", "&")
    }

    private fun absoluteUrl(uri: String, masterUrl: String): String {
        if (uri.startsWith("http")) return uri
        return try {
            java.net.URL(java.net.URL(masterUrl), uri).toString()
        } catch (e: Exception) {
            uri
        }
    }

    /**
     * Emits one playable link per master playlist. The master is passed as-is
     * (never single renditions) because VixCloud keeps audio in a separate
     * rendition group - ExoPlayer re-combines them when given the master.
     * Subtitle tracks are additionally surfaced through the subtitle callback
     * so the CloudStream picker shows them alongside the embedded ones.
     */
    private suspend fun emitMaster(
        label: String,
        masterUrl: String,
        masterText: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES[^\n]*?NAME="([^"]*)"[^\n]*?URI="([^"]+)"""")
            .findAll(masterText).forEach { m ->
                val subName = m.groupValues[1].ifBlank { "Subtitle" }
                val subUrl = absoluteUrl(m.groupValues[2], masterUrl)
                try {
                    subtitleCallback(newSubtitleFile(subName, subUrl) {
                        this.headers = vixHeaders
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "subtitle emit: ${e.message}")
                }
            }

        val bestHeight = Regex("""#EXT-X-STREAM-INF:([^\n]*)\n\s*[^\n]+""")
            .findAll(masterText)
            .mapNotNull { m ->
                val attrs = m.groupValues[1]
                val codecs = Regex("""CODECS="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                if (codecs != null && codecs.split(',').all { it.startsWith("mp4a") }) {
                    null
                } else {
                    Regex("""RESOLUTION=(\d+)x(\d+)""").find(attrs)?.groupValues?.get(2)?.toIntOrNull()
                }
            }
            .filterNotNull()
            .maxOrNull()

        callback(
            newExtractorLink("StreamingUnity", label, masterUrl, ExtractorLinkType.M3U8) {
                this.headers = vixHeaders
                this.quality = bestHeight ?: Qualities.Unknown.value
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val titleId = parts.getOrNull(0) ?: return false
        val episodeId = parts.getOrNull(1)

        val iframeUrl = if (episodeId != null) {
            "$mainUrl/en/iframe/$titleId?episode_id=$episodeId&next_episode=0"
        } else {
            "$mainUrl/en/iframe/$titleId"
        }

        val iframeHtml = try {
            app.get(iframeUrl, headers = browserHeaders, referer = "$mainUrl/", timeout = 15_000L).text
        } catch (e: Exception) {
            Log.e(TAG, "iframe: ${e.message}")
            return false
        }

        val embedUrl = extractEmbedUrl(iframeHtml) ?: return false

        val embedHtml = try {
            app.get(
                embedUrl,
                headers = browserHeaders + mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                referer = "$mainUrl/",
                timeout = 20_000L
            ).text
        } catch (e: Exception) {
            Log.e(TAG, "embed: ${e.message}")
            null
        }

        val scriptData = embedHtml?.let { html ->
            Jsoup.parse(html).selectFirst("script:containsData(masterPlaylist)")?.data()
        }

        if (scriptData != null) {
            val token = Regex("""'token':\s*'([\w-]+)'""").find(scriptData)?.groupValues?.get(1)
            val expires = Regex("""'expires':\s*'(\d+)'""").find(scriptData)?.groupValues?.get(1)
            val masterBase = Regex("""url:\s*'([^']+)'""").find(scriptData)?.groupValues?.get(1)

            if (token != null && expires != null && masterBase != null) {
                val fhd = scriptData.contains("window.canPlayFHD = true")
                val streamUrls = Regex("""window\.streams\s*=\s*(\[.*?\]);""")
                    .find(scriptData)?.groupValues?.get(1)
                    ?.let { raw ->
                        try {
                            mapper.readTree(raw).mapNotNull { node ->
                                (node.str("name") ?: "Server") to (node.str("url") ?: return@mapNotNull null)
                            }
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    ?.ifEmpty { null }
                    ?: listOf("Server" to masterBase)

                var emitted = false
                for ((streamName, streamUrl) in streamUrls) {
                    val sep = if (streamUrl.contains('?')) '&' else '?'
                    val masterUrl = buildString {
                        append(streamUrl)
                        append(sep)
                        if (fhd) append("h=1&")
                        append("token=").append(token)
                        append("&expires=").append(expires)
                        append("&lang=en")
                    }
                    try {
                        val masterText = app.get(
                            masterUrl,
                            headers = browserHeaders + mapOf("Accept" to "*/*"),
                            referer = "https://vixcloud.co/",
                            timeout = 20_000L
                        ).text
                        if (!masterText.contains("#EXTM3U")) continue
                        emitMaster(streamName, masterUrl, masterText, subtitleCallback, callback)
                        emitted = true
                    } catch (e: Exception) {
                        Log.e(TAG, "master $streamName: ${e.message}")
                    }
                }
                if (emitted) return true
            }
        }

        // Cloudflare fallback: let the site player run inside a WebView and
        // intercept the master playlist request it fires.
        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""vixcloud\.co/playlist/"""),
                additionalUrls = listOf(Regex("""vixcloud\.co/playlist/""")),
                script = """document.querySelector('button,[role="button"],.jw-icon-display,.vjs-big-play-button')?.click();""",
                useOkhttp = false,
                timeout = 35_000L
            )
            val resolved = app.get(embedUrl, referer = "$mainUrl/", interceptor = resolver, timeout = 40_000L).url
            if (!resolved.contains("/playlist/")) {
                Log.e(TAG, "webview resolve failed: $resolved")
                return false
            }
            val masterText = app.get(
                resolved,
                headers = browserHeaders + mapOf("Accept" to "*/*"),
                referer = "https://vixcloud.co/",
                timeout = 20_000L
            ).text
            if (!masterText.contains("#EXTM3U")) return false
            emitMaster("Server", resolved, masterText, subtitleCallback, callback)
            true
        } catch (e: Exception) {
            Log.e(TAG, "webview: ${e.message}")
            false
        }
    }
}
