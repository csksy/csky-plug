package com.laddu100.raghavanime
import com.lagradost.api.Log

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.newSubtitleFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred

class RaghavTwoDHive : MainAPI() {
    override var mainUrl = "https://2dhive.com"
    override var name = "2Dhive"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "completed" to "Completed Classics",
        "top" to "Top Rated Anime"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val mapper = ObjectMapper()

    private suspend fun quickGet(url: String, referer: String? = null): String {
        val headers = mutableMapOf("User-Agent" to userAgent)
        headers["Referer"] = referer ?: "$mainUrl/"
        return app.get(url = url, headers = headers).text
    }

    private fun parseGrid(soup: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        soup.select("a[href*=\"/anime?anime=\"]").forEach { a ->
            val href = a.attr("href")
            val title = a.selectFirst("h3 span.truncate")?.text()?.trim()
                ?: a.selectFirst("h3")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: ""
            if (title.length < 2) return@forEach

            var posterUrl: String? = null
            val img = a.selectFirst("img")
            if (img != null) {
                val src = img.attr("src").takeIf { it.isNotBlank() }
                if (src != null && (src.contains("anilist") || src.contains("myanimelist") || src.contains("tmdb"))) {
                    posterUrl = src
                }
            }
            if (posterUrl == null) {
                var parent = a.parent()
                repeat(5) {
                    if (parent != null && posterUrl == null) {
                        val pImg = parent!!.selectFirst("img")
                        if (pImg != null) {
                            val src = pImg.attr("src").takeIf { it.isNotBlank() }
                            if (src != null && (src.contains("anilist") || src.contains("myanimelist") || src.contains("tmdb"))) {
                                posterUrl = src
                            }
                        }
                    }
                    parent = parent?.parent()
                }
            }

            results.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            })
        }
        return results.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("twodhive") ?: mainUrl
        Log.d("RaghavAnime", "[2DHive] getMainPage '${request.name}' page=$page")
        val url = if (page > 1) {
            "$mainUrl/?list=${request.data}&page=$page"
        } else {
            "$mainUrl/?list=${request.data}"
        }
        val html = quickGet(url)
        val soup = Jsoup.parse(html)
        val items = parseGrid(soup)
        Log.d("RaghavAnime", "[2DHive] getMainPage '${request.name}' page=$page parsed ${items.size} items (html len=${html.length})")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("RaghavAnime", "[2DHive] search '$query'")
        mainUrl = FirebaseDomainHelper.getDomain("twodhive") ?: mainUrl
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = quickGet("$mainUrl/?q=$encodedQuery")
        val soup = Jsoup.parse(html)
        val results = parseGrid(soup)
        Log.d("RaghavAnime", "[2DHive] search '$query' returned ${results.size} results (html len=${html.length})")
        return results
    }

    private fun decodeAstro(node: JsonNode): JsonNode {
        if (node.isArray && node.size() == 2 && node.get(0).isNumber) {
            return decodeAstro(node.get(1))
        }
        if (node.isArray) {
            val arrayNode = mapper.createArrayNode()
            node.forEach { arrayNode.add(decodeAstro(it)) }
            return arrayNode
        }
        if (node.isObject) {
            val objectNode = mapper.createObjectNode()
            node.fields().forEach { (key, value) ->
                objectNode.set<JsonNode>(key, decodeAstro(value))
            }
            return objectNode
        }
        return node
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("RaghavAnime", "[2DHive] load '$url'")
        mainUrl = FirebaseDomainHelper.getDomain("twodhive") ?: mainUrl
        val malId = url.substringAfter("anime=").substringBefore("&").substringBefore("/").toIntOrNull()
        val html = quickGet(url)
        val soup = Jsoup.parse(html)

        val title = soup.selectFirst("h1")?.text()?.trim()
            ?: soup.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"
        Log.d("RaghavAnime", "[2DHive] load: title='$title' malId=$malId htmlLen=${html.length}")

        var poster: String? = null
        soup.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.contains("anilist") && src.contains("cover")) {
                poster = src
                return@forEach
            }
        }
        if (poster == null) {
            poster = soup.selectFirst("meta[property=og:image]")?.attr("content")
        }

        var plot = ""
        val summaryLabel = soup.select("p").firstOrNull { it.text().trim() == "Synopsis" }
        if (summaryLabel != null) {
            val summaryP = summaryLabel.nextElementSibling()
            if (summaryP != null) {
                plot = summaryP.text().trim()
            }
        }
        if (plot.isBlank() && malId != null) {
            try {
                val apiResp = quickGet("$mainUrl/api/anime/summary?malId=$malId")
                val apiJson = mapper.readTree(apiResp)
                plot = apiJson.get("anime")?.get("synopsis")?.asText() ?: ""
            } catch (e: Exception) { Log.e("RaghavAnime", "2DHive: ${e.message}") }
        }

        val genres = mutableListOf<String>()
        var year: Int? = null
        if (malId != null) {
            try {
                val apiResp = quickGet("$mainUrl/api/anime/summary?malId=$malId")
                val apiJson = mapper.readTree(apiResp)
                val genresNode = apiJson.get("anime")?.get("genres")
                if (genresNode != null && genresNode.isArray) {
                    genresNode.forEach { g -> genres.add(g.asText()) }
                }
                year = apiJson.get("anime")?.get("year")?.asInt()
            } catch (e: Exception) { Log.e("RaghavAnime", "2DHive: ${e.message}") }
        }
        if (year == null) {
            soup.select("div, span, p, small").forEach { el ->
                val text = el.text()
                val match = Regex("""\b(19\d\d|20\d\d)\b""").find(text)
                if (match != null && (text.contains("Premiered", true) || text.contains("Aired", true) || text.contains("Year", true))) {
                    year = match.groupValues[1].toIntOrNull()
                }
            }
        }

        var totalEpisodes = 1
        val titleMap = mutableMapOf<Int, Pair<String?, String?>>()

        val episodeBrowserIsland = soup.select("astro-island").firstOrNull {
            it.attr("component-url").contains("EpisodeBrowser", ignoreCase = true)
        }
        Log.d("RaghavAnime", "[2DHive] load: EpisodeBrowser island ${if (episodeBrowserIsland != null) "found" else "not found"}")
        if (episodeBrowserIsland != null) {
            val propsStr = episodeBrowserIsland.attr("props").takeIf { it.isNotEmpty() }
            if (!propsStr.isNullOrEmpty()) {
                try {
                    val props = mapper.readTree(propsStr)
                    val decoded = decodeAstro(props)
                    totalEpisodes = decoded.get("totalEpisodes")?.asInt() ?: 1
                    val episodeMetaNode = decoded.get("episodeMeta")
                    if (episodeMetaNode != null && episodeMetaNode.isArray) {
                        episodeMetaNode.forEach { ep ->
                            val num = ep.get("number")?.asInt()
                            val epTitle = ep.get("title")?.asText()
                            val thumb = ep.get("thumbnail")?.asText()
                            if (num != null) {
                                titleMap[num] = Pair(epTitle, thumb)
                            }
                        }
                    }
                } catch (e: Exception) { Log.e("RaghavAnime", "2DHive: ${e.message}") }
            }
        }

        val maxFromLinks = soup.select("a[href*=\"/episode?\"]").mapNotNull { a ->
            Regex("""ep_num=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        val epCount = maxOf(totalEpisodes, maxFromLinks).coerceAtLeast(1)
        Log.d("RaghavAnime", "[2DHive] load: totalEpisodes=$totalEpisodes maxFromLinks=$maxFromLinks epCount=$epCount epMeta=${titleMap.size}")

        val episodes = (1..epCount).map { num ->
            val epUrl = "$mainUrl/episode?anime=${malId ?: ""}&ep_num=$num"
            val meta = titleMap[num]
            newEpisode(epUrl) {
                this.episode = num
                this.name = meta?.first?.takeIf { it.isNotBlank() } ?: "Episode $num"
                this.posterUrl = meta?.second
            }
        }

        val subEpisodes = episodes.map { ep ->
            newEpisode("${ep.data}|sub") {
                this.episode = ep.episode
                this.name = ep.name
                this.posterUrl = ep.posterUrl
            }
        }
        val dubEpisodes = episodes.map { ep ->
            newEpisode("${ep.data}|dub") {
                this.episode = ep.episode
                this.name = ep.name
                this.posterUrl = ep.posterUrl
            }
        }

        Log.d("RaghavAnime", "[2DHive] load '$url': built ${subEpisodes.size} sub / ${dubEpisodes.size} dub episodes")
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private suspend fun getProxyUrl(malId: Int, epNum: Int): String {
        try {
            val apiRespText = app.get(
                url = "$mainUrl/api/hianime?mal_id=$malId&ep_num=$epNum",
                headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
            ).text
            val apiJson = mapper.readTree(apiRespText)
            val m3u8 = apiJson.get("m3u8")?.asText()
            if (!m3u8.isNullOrEmpty() && m3u8.contains("/m3u8-proxy")) {
                Log.d("RaghavAnime", "[2DHive] getProxyUrl: using site proxy for malId=$malId ep=$epNum")
                return m3u8.substringBefore("/m3u8-proxy") + "/m3u8-proxy"
            }
            Log.d("RaghavAnime", "[2DHive] getProxyUrl: no site proxy in api response, using default")
        } catch (e: Exception) { Log.e("RaghavAnime", "2DHive: ${e.message}") }
        return "https://anicloud-hls-proxy.n3779118.workers.dev/m3u8-proxy"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        Log.d("RaghavAnime", "[2DHive] loadLinks data='${data.take(100)}'")
        val parts = data.split("|")
        if (parts.size < 2) {
            Log.d("RaghavAnime", "[2DHive] loadLinks: malformed data, parts=${parts.size} < 2")
            return@coroutineScope false
        }
        val epUrl = parts[0]
        val type = parts[1]

        val html = quickGet(epUrl)
        val soup = Jsoup.parse(html)

        val island = soup.select("astro-island").firstOrNull {
            it.attr("component-url").contains("MultiServerPlayer", ignoreCase = true)
        }
        if (island == null) {
            Log.d("RaghavAnime", "[2DHive] loadLinks: no MultiServerPlayer island for '${epUrl.take(80)}'")
            return@coroutineScope false
        }

        val propsStr = island.attr("props").takeIf { it.isNotEmpty() }
        if (propsStr == null) {
            Log.d("RaghavAnime", "[2DHive] loadLinks: MultiServerPlayer island has empty props")
            return@coroutineScope false
        }

        val props = mapper.readTree(propsStr)
        val decoded = decodeAstro(props)

        val malId = decoded.get("animeIdOrName")?.let { node ->
            if (node.isNumber) node.asInt() else node.asText().toIntOrNull()
        } ?: epUrl.substringAfter("anime=").substringBefore("&").toIntOrNull()

        val epNum = decoded.get("epNum")?.asInt() ?: 1
        val serversList = decoded.get("servers")
        Log.d("RaghavAnime", "[2DHive] loadLinks: malId=$malId epNum=$epNum type=$type servers=${serversList?.size() ?: 0}")

        val loadedResults = mutableListOf<Deferred<Boolean>>()

        if (serversList != null && serversList.isArray) {
            serversList.forEach { serverItem ->
                val serverName = serverItem.get("server_name")?.asText()?.trim() ?: ""
                val slug = serverItem.get("slug")?.asText()?.trim() ?: ""
                val isDub = serverItem.get("dub")?.asBoolean() ?: false
                val animeName = serverItem.get("anime_name")?.asText()?.trim() ?: ""

                val mappedServerName = if (serverName.equals("hydrax", ignoreCase = true)) "hadfree" else serverName
                val isServerDub = isDub || mappedServerName.contains("dub", ignoreCase = true) || animeName.contains("dub", ignoreCase = true)

                Log.d("RaghavAnime", "[2DHive] loadLinks: server '$serverName' (mapped='$mappedServerName' slug='${slug.take(40)}' dub=$isServerDub) ${if ((type == "dub" && isServerDub) || (type == "sub" && !isServerDub)) "matches type=$type" else "skipped for type=$type"}")
                if ((type == "dub" && isServerDub) || (type == "sub" && !isServerDub)) {
                    loadedResults.add(async {
                        try {
                            when {
                                mappedServerName.equals("hadfree", ignoreCase = true) -> {
                                    Log.d("RaghavAnime", "[2DHive] hadfree: fetching streamUrl for slug=$slug")
                                    val apiResp = app.get(
                                        url = "$mainUrl/api/hadfree?slug=$slug",
                                        headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
                                    ).text
                                    val streamUrl = mapper.readTree(apiResp).get("streamUrl")?.asText()
                                    if (!streamUrl.isNullOrEmpty()) {
                                        Log.d("RaghavAnime", "[2DHive] hadfree: streamUrl found '${streamUrl.take(80)}'")
                                        callback(
                                            newExtractorLink("Hadfree", "Hadfree", streamUrl, type = ExtractorLinkType.VIDEO) {
                                                this.headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
                                                this.referer = "$mainUrl/"
                                            }
                                        )
                                        true
                                    } else {
                                        Log.d("RaghavAnime", "[2DHive] hadfree: no streamUrl for slug=$slug")
                                        false
                                    }
                                }
                                mappedServerName.equals("mp4upload", ignoreCase = true) -> {
                                    Log.d("RaghavAnime", "[2DHive] mp4upload: loadExtractor for slug=$slug")
                                    loadExtractor("https://www.mp4upload.com/embed-$slug.html", epUrl, subtitleCallback, callback)
                                }
                                mappedServerName.equals("meta_media_id", ignoreCase = true) -> {
                                    Log.d("RaghavAnime", "[2DHive] meta_media_id: facebook loadExtractor for video_id=$slug")
                                    loadExtractor("https://www.facebook.com/video/embed?video_id=$slug", epUrl, subtitleCallback, callback)
                                }
                                mappedServerName.equals("abyssplayer", ignoreCase = true) -> {
                                    Log.d("RaghavAnime", "[2DHive] abyssplayer: loadExtractor for slug=$slug")
                                    loadExtractor("https://abyssplayer.com/$slug", epUrl, subtitleCallback, callback)
                                }
                                slug.startsWith("http://") || slug.startsWith("https://") -> {
                                    val encodedSlug = slug.replace(" ", "%20")
                                    if (encodedSlug.contains(".mp4") || encodedSlug.contains(".m3u8")) {
                                        Log.d("RaghavAnime", "[2DHive] direct link: '${encodedSlug.take(80)}'")
                                        callback(
                                            newExtractorLink(
                                                mappedServerName.ifEmpty { "Direct" },
                                                mappedServerName.ifEmpty { "Direct Link" },
                                                encodedSlug,
                                                type = if (encodedSlug.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
                                                this.referer = "$mainUrl/"
                                            }
                                        )
                                        true
                                    } else {
                                        Log.d("RaghavAnime", "[2DHive] direct slug: loadExtractor for '${encodedSlug.take(80)}'")
                                        loadExtractor(encodedSlug, epUrl, subtitleCallback, callback)
                                    }
                                }
                                else -> {
                                    Log.d("RaghavAnime", "[2DHive] no handler for server '$mappedServerName' (slug='${slug.take(40)}')")
                                    false
                                }
                            }
                        } catch (_: Exception) { Log.e("RaghavAnime", "[2DHive] server '$mappedServerName' resolve failed"); false }
                    })
                }
            }
        }

        if (malId != null) {
            loadedResults.add(async {
                try {
                    Log.d("RaghavAnime", "[2DHive] MegaPlay: fetching mal/$malId/$epNum/$type")
                    val megaplayUrl = "https://megaplay.buzz/stream/mal/$malId/$epNum/$type"
                    val playerPageHtml = app.get(megaplayUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to epUrl)).text
                    val playerPageSoup = Jsoup.parse(playerPageHtml)
                    val playerId = playerPageSoup.selectFirst("#megaplay-player")?.attr("data-id")
                        ?: Regex("""data-id=["'](\d+)""").find(playerPageHtml)?.groupValues?.get(1)
                        ?: playerPageSoup.selectFirst("#megaplay-player")?.attr("data-realid")
                        ?: Regex("""data-realid=["'](\d+)""").find(playerPageHtml)?.groupValues?.get(1)
                        ?: Regex("""/stream/s-\d+/(\d+)""").find(megaplayUrl)?.groupValues?.get(1)

                    if (playerId != null) {
                        val sourcesText = app.get(
                            "https://megaplay.buzz/stream/getSources?id=$playerId&type=$type",
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to megaplayUrl,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin" to "https://megaplay.buzz"
                            )
                        ).text

                        val sourcesJson = mapper.readTree(sourcesText)
                        val sources = sourcesJson.get("sources")
                        val m3u8Url = if (sources != null && sources.isArray) {
                            sources.get(0)?.get("file")?.asText()
                        } else {
                            sources?.get("file")?.asText()
                        }

                        val tracks = sourcesJson.get("tracks")
                        if (tracks != null && tracks.isArray) {
                            Log.d("RaghavAnime", "[2DHive] MegaPlay: ${tracks.size()} subtitle tracks")
                            tracks.forEach { track ->
                                val file = track.get("file")?.asText() ?: return@forEach
                                val label = track.get("label")?.asText() ?: "Unknown"
                                subtitleCallback(newSubtitleFile(label, file) {
                                    this.headers = mapOf("Referer" to "https://megaplay.buzz/")
                                })
                            }
                        }

                        if (!m3u8Url.isNullOrEmpty()) {
                            Log.d("RaghavAnime", "[2DHive] MegaPlay: m3u8 found '${m3u8Url.take(80)}'")
                            val displayName = if (type == "sub") "MegaPlay Sub" else "MegaPlay Dub"
                            callback(
                                newExtractorLink(displayName, "$displayName (Direct)", m3u8Url, type = ExtractorLinkType.M3U8) {
                                    this.headers = mapOf("User-Agent" to userAgent, "Referer" to "https://megaplay.buzz/", "Origin" to "https://megaplay.buzz")
                                    this.referer = "https://megaplay.buzz/"
                                }
                            )

                            val proxyPrefix = getProxyUrl(malId, epNum)
                            val encodedTarget = URLEncoder.encode(m3u8Url, "UTF-8")
                            val encodedHeaders = URLEncoder.encode("{\"referer\":\"https://megaplay.buzz/\"}", "UTF-8")
                            val wrappedUrl = "$proxyPrefix?url=$encodedTarget&headers=$encodedHeaders"
                            callback(
                                newExtractorLink(displayName, "$displayName (Proxy)", wrappedUrl, type = ExtractorLinkType.M3U8) {
                                    this.headers = mapOf("User-Agent" to userAgent, "Referer" to "https://megaplay.buzz/")
                                    this.referer = "https://megaplay.buzz/"
                                }
                            )
                            true
                        } else {
                            Log.d("RaghavAnime", "[2DHive] MegaPlay: no m3u8 in sources")
                            false
                        }
                    } else {
                        Log.d("RaghavAnime", "[2DHive] MegaPlay: no playerId found in player page")
                        false
                    }
                } catch (_: Exception) { Log.e("RaghavAnime", "[2DHive] MegaPlay resolve failed"); false }
            })
        }

        val allResults = loadedResults.awaitAll()
        val anySuccess = allResults.any { it }
        Log.d("RaghavAnime", "[2DHive] loadLinks finished: ${allResults.size} tasks, success=$anySuccess")
        anySuccess
    }
}
