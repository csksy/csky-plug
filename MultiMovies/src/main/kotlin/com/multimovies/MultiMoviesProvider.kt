package com.multimovies

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MultiMoviesProvider : MainAPI() {
    override var mainUrl = "https://multimovies.motorcycles"
    override var name = "MultiMovies"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private val ajaxHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Movies",
        "$mainUrl/tvshows" to "TV Shows",
        "$mainUrl/genre/anime" to "Anime",
        "$mainUrl/genre/bollywood" to "Bollywood",
        "$mainUrl/genre/hollywood" to "Hollywood",
        "$mainUrl/genre/dual-audio" to "Dual Audio",
        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/comedy" to "Comedy",
        "$mainUrl/genre/thriller" to "Thriller",
        "$mainUrl/genre/science-fiction" to "Sci-Fi",
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("div.thumbnail a") ?: selectFirst("div.image a") ?: selectFirst("a")
            ?: return null
        val url = a.attr("href").ifBlank { return null }
        val img = a.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: ""
        val title = a.selectFirst("img")?.attr("alt")?.trim()
            ?: selectFirst("div.title a")?.text()?.trim()
            ?: a.attr("title")?.trim()
            ?: return null
        val isSeries = url.contains("/tvshows/") || url.contains("/episodes/")
        val year = selectFirst("span.year")?.text()?.toIntOrNull()
        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = img
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = img
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data}/page/$page/"
        val doc = try {
            app.get(pageUrl, headers = baseHeaders, timeout = 30_000L).document
        } catch (e: Exception) {
            Log.d("MM", "getMainPage: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = doc.select("div.result-item").mapNotNull { it.toSearchResult() }
        val hasNext = doc.select("div.pagination a.next, a.nextpostslink").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = try {
            app.get("$mainUrl/?s=$encoded", headers = baseHeaders, timeout = 30_000L).document
        } catch (e: Exception) {
            Log.d("MM", "search: ${e.message}")
            return emptyList()
        }
        return doc.select("div.result-item").mapNotNull { it.toSearchResult() }
    }

    private data class ServerOption(
        val nume: String,
        val title: String,
        val postId: String,
        val type: String,
    )

    private suspend fun fetchServerOptions(doc: org.jsoup.nodes.Document, defaultType: String): List<ServerOption> {
        val options = mutableListOf<ServerOption>()
        val lis = doc.select("li.dooplay_player_option")
        for (li in lis) {
            val nume = li.attr("data-nume")
            val postId = li.attr("data-post")
            val type = li.attr("data-type").ifBlank { defaultType }
            val title = li.selectFirst("span.title")?.text()?.trim() ?: continue
            if (nume == "trailer") continue
            options.add(ServerOption(nume, title, postId, type))
        }
        return options
    }

    private suspend fun fetchEmbedUrl(postId: String, nume: String, type: String): String? {
        return try {
            val resp = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to postId,
                    "nume" to nume,
                    "type" to type,
                ),
                headers = ajaxHeaders,
                referer = "$mainUrl/",
                timeout = 15_000L,
            ).text
            val json = parseJson<MutableMap<String, Any?>>(resp)
            json["embed_url"] as? String
        } catch (e: Exception) {
            Log.d("MM", "fetchEmbedUrl: ${e.message}")
            null
        }
    }

    private fun parseSeasons(doc: org.jsoup.nodes.Document): List<Pair<Int, List<Pair<String, String>>>> {
        val seasons = mutableListOf<Pair<Int, List<Pair<String, String>>>>()
        val seasonBlocks = doc.select("div.se-c")
        for (block in seasonBlocks) {
            val seasonNum = block.selectFirst("span.se-t")?.text()?.toIntOrNull() ?: 1
            val episodes = mutableListOf<Pair<String, String>>()
            val epLinks = block.select("div.episodiotitle a")
            for (ep in epLinks) {
                val href = ep.attr("href")
                val title = ep.text().trim()
                if (href.isNotBlank()) episodes.add(href to title)
            }
            seasons.add(seasonNum to episodes)
        }
        return seasons
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            app.get(url, headers = baseHeaders, timeout = 30_000L).document
        } catch (e: Exception) {
            Log.d("MM", "load: ${e.message}")
            return null
        }

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img.wp-post-image")?.attr("src")
        val plot = doc.selectFirst("div synopsis, div.description, p.description")?.text()?.trim()
            ?: doc.selectFirst("div#info p, div.entry-content p")?.text()?.trim()
        val year = doc.selectFirst("span.date")?.text()?.substringBefore("-")?.trim()?.toIntOrNull()
        val genres = doc.select("div.genres a, #info a[href*=genre]").map { it.text().trim() }.filter { it.isNotBlank() }

        val isSeries = url.contains("/tvshows/") || doc.select("div.se-c").isNotEmpty()

        if (isSeries) {
            val seasons = parseSeasons(doc)
            val episodes = mutableListOf<Episode>()

            for ((seasonNum, epList) in seasons) {
                for ((epUrl, epTitle) in epList) {
                    val epNum = Regex("""\d+x(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""-(\d+)x(\d+)""").find(epUrl)?.groupValues?.get(2)?.toIntOrNull()
                        ?: 1

                    val epDoc = try {
                        app.get(epUrl, headers = baseHeaders, timeout = 30_000L).document
                    } catch (e: Exception) { continue }

                    val serverOptions = fetchServerOptions(epDoc, "tv")
                    val serverData = serverOptions.map { opt ->
                        mapOf(
                            "n" to opt.nume,
                            "t" to opt.title,
                            "p" to opt.postId,
                            "y" to opt.type,
                        )
                    }.toJson()

                    episodes.add(newEpisode(serverData) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                    })
                }
            }

            if (episodes.isEmpty()) return null
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        }

        val serverOptions = fetchServerOptions(doc, "movie")
        val serverData = serverOptions.map { opt ->
            mapOf(
                "n" to opt.nume,
                "t" to opt.title,
                "p" to opt.postId,
                "y" to opt.type,
            )
        }.toJson()

        return newMovieLoadResponse(title, url, TvType.Movie, serverData) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val serverOptions = try {
            parseJson<List<Map<String, Any?>>>(data)
        } catch (e: Exception) {
            Log.d("MM", "loadLinks parse: ${e.message}")
            return false
        }

        var found = false

        coroutineScope {
            serverOptions.map { opt ->
                async {
                    val nume = opt["n"] as? String ?: return@async
                    val title = opt["t"] as? String ?: "Unknown"
                    val postId = opt["p"] as? String ?: return@async
                    val type = opt["y"] as? String ?: "movie"

                    val embedUrl = fetchEmbedUrl(postId, nume, type) ?: return@async

                    val collected = mutableListOf<ExtractorLink>()
                    try {
                        loadExtractor(embedUrl, "$mainUrl/", subtitleCallback) { el -> collected.add(el) }
                    } catch (e: Exception) {
                        Log.d("MM", "loadExtractor $embedUrl: ${e.message}")
                    }

                    if (collected.isEmpty() && embedUrl.contains(Regex("vidlink|vixsrc|nxsha|vidzee|vidlux|zxcstream|cinemaos|nhdapi|peachify|screenscape|iqsmart"))) {
                        try {
                            val resolver = WebViewResolver(
                                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                                script = """document.querySelector('video,button,.play-button,[role=button]')?.click();""",
                                useOkhttp = false,
                                timeout = 30_000L,
                            )
                            val resolvedUrl = app.get(embedUrl, referer = "$mainUrl/", interceptor = resolver).url

                            if (resolvedUrl.contains(".m3u8", true) || resolvedUrl.contains(".mp4", true)) {
                                val isM3u8 = resolvedUrl.contains(".m3u8", true)
                                collected.add(
                                    newExtractorLink(
                                        source = "MultiMovies",
                                        name = "$title",
                                        url = resolvedUrl,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.referer = embedUrl
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.d("MM", "WebViewResolver $embedUrl: ${e.message}")
                        }
                    }

                    for (el in collected) {
                        callback(
                            newExtractorLink(
                                source = el.source,
                                name = "$title - ${el.name}".trim(),
                                url = el.url,
                                type = if (el.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = el.referer
                                this.headers = el.headers
                                this.quality = el.quality
                            }
                        )
                        found = true
                    }
                }
            }.awaitAll()
        }

        return found
    }
}
