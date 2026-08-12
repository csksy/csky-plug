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
        "$mainUrl/genre/science-fiction" to "Sci-Fi",
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("div.poster a") ?: selectFirst("div.thumbnail a")
            ?: selectFirst("div.image a") ?: selectFirst("div.title a")
            ?: selectFirst("a[href]") ?: return null
        val url = a.attr("href").ifBlank { return null }
        val img = selectFirst("div.poster img")?.attr("src")
            ?: selectFirst("img")?.attr("src")
            ?: ""
        val title = selectFirst("div.poster img")?.attr("alt")?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: selectFirst("div.title a")?.text()?.trim()
            ?: selectFirst("h3 a")?.text()?.trim()
            ?: selectFirst("h2 a")?.text()?.trim()
            ?: return null
        val isSeries = url.contains("/tvshows/") || url.contains("/episodes/")
        val year = selectFirst("span.year")?.text()?.toIntOrNull()
            ?: selectFirst("span")?.text()?.trim()?.toIntOrNull()
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
        val pageUrl = if (page <= 1) "${request.data}/" else "${request.data}/page/$page/"
        val doc = try {
            app.get(pageUrl, headers = baseHeaders, timeout = 30_000L).document
        } catch (e: Exception) {
            Log.d("MM", "getMainPage: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = doc.select("article.item").mapNotNull { it.toSearchResult() }
        val hasNext = doc.select("div.pagination a.next, a.nextpostslink, a.next.page-numbers").isNotEmpty()
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
        return doc.select("div.result-item, article.item").mapNotNull { it.toSearchResult() }
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
            val lower = title.lowercase()
            if (lower.contains("gdmirror") || lower.contains("cineverse")) {
                options.add(ServerOption(nume, title, postId, type))
            }
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
            (json["embed_url"] as? String)?.replace("&amp;", "&")
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
        val plot = doc.selectFirst("div.synopsis, div.description, p.description")?.text()?.trim()
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

    private suspend fun resolveCineverse(embedUrl: String, sourceName: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(embedUrl, headers = baseHeaders, timeout = 15_000L).text

            val directSrc = Regex("""directSrc\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.replace("&amp;", "&")

            if (directSrc != null && directSrc.contains(".m3u8")) {
                callback(
                    newExtractorLink(
                        source = "MultiMovies",
                        name = "$sourceName",
                        url = directSrc,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            val proxyUrls = Regex("""url=(https?[^&"'\s]+\.m3u8[^&"'\s]*)""").findAll(html)
                .map { it.groupValues[1] }
                .map { java.net.URLDecoder.decode(it, "UTF-8").replace("\\/", "/").replace("&amp;", "&") }
                .filter { it.contains(".m3u8") }
                .distinct()
                .toList()

            if (proxyUrls.isNotEmpty()) {
                for (m3u8Url in proxyUrls) {
                    callback(
                        newExtractorLink(
                            source = "MultiMovies",
                            name = "$sourceName",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                return true
            }

            val switchCalls = Regex("""switchServer\(\s*'([^']+)'\s*,\s*'([^']+)'\s*,\s*'([^']+)'\s*,\s*'([^']+)'\s*,\s*'([^']*)'""").findAll(html).toList()

            if (switchCalls.isNotEmpty()) {
                val baseUrl = Regex("""https?://[^/]+""").find(embedUrl)?.value ?: ""
                var anyFound = false

                for (call in switchCalls) {
                    val (_, platform, name, code, title) = call.destructured
                    val titleEncoded = URLEncoder.encode(title.ifBlank { "Video" }, "UTF-8")
                    val proxyUrl = "$baseUrl/proxy.php?p=$platform&c=$code&title=$titleEncoded&site_ref=&noredirect=1"

                    try {
                        val proxyHtml = app.get(proxyUrl, headers = baseHeaders, timeout = 15_000L).text

                        val proxyDirectSrc = Regex("""directSrc\s*=\s*"([^"]+)"""").find(proxyHtml)?.groupValues?.get(1)
                            ?.replace("\\/", "/")
                            ?.replace("&amp;", "&")

                        if (proxyDirectSrc != null && proxyDirectSrc.contains(".m3u8")) {
                            callback(
                                newExtractorLink(
                                    source = "MultiMovies",
                                    name = "$sourceName - $name",
                                    url = proxyDirectSrc,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            anyFound = true
                            continue
                        }

                        val m3u8FromProxy = Regex("""url=(https?[^&"'\s]+\.m3u8[^&"'\s]*)""").findAll(proxyHtml)
                            .map { it.groupValues[1] }
                            .map { java.net.URLDecoder.decode(it, "UTF-8").replace("\\/", "/").replace("&amp;", "&") }
                            .filter { it.contains(".m3u8") }
                            .distinct()
                            .toList()

                        for (m3u8Url in m3u8FromProxy) {
                            callback(
                                newExtractorLink(
                                    source = "MultiMovies",
                                    name = "$sourceName - $name",
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            anyFound = true
                        }
                    } catch (e: Exception) {
                        Log.d("MM", "proxy fetch $platform: ${e.message}")
                    }
                }

                if (anyFound) return true
            }

            val srcVar = Regex("""var\s+src\s*=\s*"([^"]*serve_m3u8[^"]*)"""").find(html)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.replace("&amp;", "&")

            if (srcVar != null) {
                val m3u8FromSrc = Regex("""url=(https?[^&"'\s]+\.m3u8[^&"'\s]*)""").findAll(srcVar)
                    .map { it.groupValues[1] }
                    .map { java.net.URLDecoder.decode(it, "UTF-8").replace("\\/", "/") }
                    .filter { it.contains(".m3u8") }
                    .distinct()
                    .toList()

                for (m3u8Url in m3u8FromSrc) {
                    callback(
                        newExtractorLink(
                            source = "MultiMovies",
                            name = "$sourceName",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }

            false
        } catch (e: Exception) {
            Log.d("MM", "resolveCineverse: ${e.message}")
            false
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

                    if (embedUrl.contains("gdmirrorbot.nl")) {
                        val collected = mutableListOf<ExtractorLink>()
                        try {
                            loadExtractor(embedUrl, "$mainUrl/", subtitleCallback) { el -> collected.add(el) }
                        } catch (e: Exception) {
                            Log.d("MM", "GDMirror loadExtractor: ${e.message}")
                        }
                        for (el in collected) {
                            callback(
                                newExtractorLink(
                                    source = el.source,
                                    name = "$title - ${el.name}".trim(),
                                    url = el.url,
                                    type = if (el.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                ) {
                                    this.quality = el.quality
                                }
                            )
                            found = true
                        }

                        if (collected.isEmpty()) {
                            if (resolveCineverse(embedUrl, title, callback)) found = true
                        }
                    } else if (embedUrl.contains("modiplay") || embedUrl.contains("cineverse")) {
                        if (resolveCineverse(embedUrl, title, callback)) found = true
                    }
                }
            }.awaitAll()
        }

        return found
    }
}
