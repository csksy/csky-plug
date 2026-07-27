package com.laddu100

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.net.URLEncoder

class ZenkaiProvider : MainAPI() {
    override var mainUrl = "https://zenkai.to"
    override var name = "Zenkai"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val instantLinkLoading = true

    private val TAG = "Zenkai"

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    private val ajaxHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "en-US,en;q=0.9",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        Pair("$mainUrl/home", "Home"),
        Pair("$mainUrl/filter?sort=recently_updated", "Recently Updated"),
        Pair("$mainUrl/filter?sort=recently_added", "Recently Added"),
        Pair("$mainUrl/filter?sort=top_airing", "Top Airing"),
        Pair("$mainUrl/filter?sort=top_rated", "Top Rated"),
        Pair("$mainUrl/filter?sort=most_viewed", "Most Viewed"),
        Pair("$mainUrl/filter?type=movie", "Movies"),
        Pair("$mainUrl/filter?type=tv", "TV Series"),
        Pair("$mainUrl/filter?type=ova", "OVA"),
        Pair("$mainUrl/filter?type=ona", "ONA"),
        Pair("$mainUrl/filter?type=special", "Specials")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            request.data + (if (request.data.contains("?")) "&" else "?") + "page=$page"
        } else {
            request.data
        }

        return try {
            val html = app.get(url, headers = baseHeaders, timeout = 30_000L).text
            val soup = Jsoup.parse(html)
            val items = soup.select(".flw-item").mapNotNull { parseSearchItem(it) }
            newHomePageResponse(request.name, items, hasNext = items.size >= 30)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun parseSearchItem(item: org.jsoup.nodes.Element): SearchResponse? {
        val href = item.selectFirst("a.film-poster-ahref")?.attr("href") ?: return null
        val title = item.selectFirst(".film-name a")?.text()?.trim() ?: return null
        val poster = item.selectFirst(".film-poster-img")?.attr("src")
        val sub = item.selectFirst(".tick-sub")?.text()?.trim()?.toIntOrNull() ?: 0
        val dub = item.selectFirst(".tick-dub")?.text()?.trim()?.toIntOrNull() ?: 0
        val typeText = item.selectFirst(".fdi-item")?.text()?.trim() ?: "TV"
        val tvType = when (typeText.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona", "special" -> TvType.OVA
            else -> TvType.Anime
        }
        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            addDubStatus(dubExist = dub > 0, subExist = sub > 0)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val html = app.get("$mainUrl/filter?keyword=$encoded", headers = baseHeaders, timeout = 30_000L).text
            Jsoup.parse(html).select(".flw-item").mapNotNull { parseSearchItem(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val html = app.get(url, headers = baseHeaders, timeout = 30_000L).text
            val soup = Jsoup.parse(html)

            val animeId = soup.selectFirst("[data-id]")?.attr("data-id") ?: return null
            val title = soup.selectFirst("h1.film-name, h2.film-name")?.text()?.trim()
                ?: soup.selectFirst(".d-title")?.attr("data-jp") ?: return null
            val jpTitle = soup.selectFirst(".d-title")?.attr("data-jp")
            val poster = soup.selectFirst(".film-poster-img")?.attr("src")
            val plot = soup.selectFirst(".description, .shorting")?.text()?.trim()
            val genres = soup.select("a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }
            val year = soup.selectFirst(".fdi-item:containsOwn(20)")?.text()?.take(4)?.toIntOrNull()
            val typeText = soup.selectFirst(".fdi-item")?.text()?.trim() ?: "TV"
            val tvType = when (typeText.lowercase()) {
                "movie" -> TvType.AnimeMovie
                "ova", "ona", "special" -> TvType.OVA
                else -> TvType.Anime
            }

            val vrf = vrfEncode(animeId)
            val epsResponseText = app.get(
                "$mainUrl/ajax/episode/list/$animeId?vrf=$vrf",
                headers = ajaxHeaders + ("Referer" to url),
                timeout = 30_000L
            ).text

            val epsJson = try { parseJson<AjaxResponse>(epsResponseText) } catch (e: Exception) { return null }
            val epsHtml = epsJson.result ?: return null
            val epsSoup = Jsoup.parse(epsHtml)

            val subEpisodes = mutableListOf<Episode>()
            val dubEpisodes = mutableListOf<Episode>()

            epsSoup.select("a.ep-item").forEach { epLink ->
                val epNum = epLink.attr("data-num").toIntOrNull()
                    ?: return@forEach
                val epTitle = epLink.attr("title")?.takeIf { it.isNotBlank() }
                    ?: "Episode $epNum"
                val dataIds = epLink.attr("data-ids").takeIf { it.isNotBlank() } ?: return@forEach
                val hasSub = epLink.attr("data-sub") == "1"
                val hasDub = epLink.attr("data-dub") == "1"
                val epImage = epLink.selectFirst("img")?.attr("src")

                if (hasSub) {
                    subEpisodes.add(newEpisode(EpisodeData(animeId, dataIds, epNum, epTitle, "sub", url).toJson()) {
                        this.episode = epNum
                        this.name = epTitle
                        this.posterUrl = epImage
                    })
                }
                if (hasDub) {
                    dubEpisodes.add(newEpisode(EpisodeData(animeId, dataIds, epNum, epTitle, "dub", url).toJson()) {
                        this.episode = epNum
                        this.name = epTitle
                        this.posterUrl = epImage
                    })
                }
            }

            val finalType = if (tvType == TvType.AnimeMovie && dubEpisodes.isNotEmpty()) TvType.Anime else tvType

            return newAnimeLoadResponse(title, url, finalType) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
                if (jpTitle != null) this.japName = jpTitle
                if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
                if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
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
        val epData = try { parseJson<EpisodeData>(data) } catch (e: Exception) { return false }

        val serverListResponse = try {
            app.get(
                "$mainUrl/ajax/server/list?servers=${epData.dataIds}",
                headers = ajaxHeaders + ("Referer" to epData.animeUrl),
                timeout = 30_000L
            ).text
        } catch (e: Exception) { return false }

        val serverListJson = try { parseJson<AjaxResponse>(serverListResponse) } catch (e: Exception) { return false }
        val serverListHtml = serverListJson.result ?: return false
        val serverSoup = Jsoup.parse(serverListHtml)

        val targetType = if (epData.epType == "dub") "dub" else "s-sub"

        val servers = mutableListOf<Pair<String, String>>()
        serverSoup.select("a.server-item, .ps__-list a").forEach { serverEl ->
            val type = serverEl.attr("data-type") ?: return@forEach
            val linkId = serverEl.attr("data-link-id").takeIf { it.isNotBlank() } ?: return@forEach
            val serverName = serverEl.text().trim().ifBlank { "Server" }
            if (type == targetType || (targetType == "s-sub" && type == "h-sub")) {
                servers.add(Pair(serverName, linkId))
            }
        }

        if (servers.isEmpty()) {
            serverSoup.select("a.server-item, .ps__-list a").forEach { serverEl ->
                val linkId = serverEl.attr("data-link-id").takeIf { it.isNotBlank() } ?: return@forEach
                val serverName = serverEl.text().trim().ifBlank { "Server" }
                servers.add(Pair(serverName, linkId))
            }
        }

        var found = false
        val subtitleLoaded = java.util.concurrent.atomic.AtomicBoolean(false)

        coroutineScope {
            servers.map { (serverName, linkId) ->
                async {
                    try {
                        val serverResponse = app.get(
                            "$mainUrl/ajax/server?get=$linkId",
                            headers = ajaxHeaders + ("Referer" to epData.animeUrl),
                            timeout = 15_000L
                        ).text

                        val serverJson = try { parseJson<ServerResponse>(serverResponse) } catch (e: Exception) { return@async }
                        val streamUrl = serverJson.result?.url ?: return@async

                        if (subtitleLoaded.compareAndSet(false, true)) {
                            val subtitleUrl = extractSubtitleUrl(streamUrl)
                            if (subtitleUrl != null) {
                                try {
                                    subtitleCallback.invoke(newSubtitleFile("English", subtitleUrl) {
                                        this.headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")
                                    })
                                } catch (_: Exception) {}
                            }
                        }

                        val labelSuffix = if (epData.epType == "dub") "Dub" else "Sub"
                        val label = "$name $serverName ($labelSuffix)"

                        when {
                            streamUrl.contains("vivibebe.site") || streamUrl.contains("bibiemb.xyz") -> {
                                try {
                                    loadExtractor(streamUrl, "$mainUrl/", subtitleCallback, callback)
                                    found = true
                                } catch (_: Exception) {}
                            }

                            streamUrl.contains(".m3u8") -> {
                                try {
                                    M3u8Helper.generateM3u8(label, streamUrl, "$mainUrl/", headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")).forEach(callback)
                                    found = true
                                } catch (_: Exception) {}
                            }

                            else -> {
                                try {
                                    loadExtractor(streamUrl, "$mainUrl/", subtitleCallback, callback)
                                    found = true
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                }
            }.awaitAll()
        }

        return found
    }

    private fun extractSubtitleUrl(url: String): String? {
        val patterns = listOf("sub=", "caption_1=", "c1_file=", "sub_1=")
        for (param in patterns) {
            if (url.contains(param)) {
                val subUrl = url.substringAfter(param).substringBefore("&")
                if (subUrl.startsWith("http")) return subUrl
            }
        }
        return null
    }

    private fun vrfEncode(input: String): String {
        val encrypted = rc4("ysJhV6U27FVIjjuk", input)
        val encoded = base64UrlEncode(encrypted)
        val shifted = shiftChars(encoded)
        val doubleEncoded = base64UrlEncode(shifted)
        val rot13 = rot13(doubleEncoded)
        return URLEncoder.encode(rot13, "UTF-8")
    }

    private fun rc4(key: String, data: String): String {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + key[i % key.length].code) % 256
            val temp = s[i]; s[i] = s[j]; s[j] = temp
        }
        var i2 = 0; var j2 = 0
        val result = StringBuilder()
        for (char in data) {
            i2 = (i2 + 1) % 256
            j2 = (j2 + s[i2]) % 256
            val temp = s[i2]; s[i2] = s[j2]; s[j2] = temp
            val k = s[(s[i2] + s[j2]) % 256]
            result.append((char.code xor k).toChar())
        }
        return result.toString()
    }

    private fun base64UrlEncode(input: String): String {
        return Base64.encodeToString(input.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP)
            .replace("/", "_").replace("+", "-")
    }

    private fun shiftChars(input: String): String {
        val result = StringBuilder()
        for (i in input.indices) {
            var a = input[i].code
            when (i % 8) {
                1 -> a += 3; 7 -> a += 5; 2 -> a -= 4; 4 -> a -= 2
                6 -> a += 4; 0 -> a -= 3; 3 -> a += 2; 5 -> a += 5
            }
            result.append(a.toChar())
        }
        return result.toString()
    }

    private fun rot13(input: String): String {
        val result = StringBuilder()
        for (c in input) {
            if (c.isLetter()) {
                val base = if (c.isUpperCase()) 'A' else 'a'
                result.append((base.code + (c.code - base.code + 13) % 26).toChar())
            } else result.append(c)
        }
        return result.toString()
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AjaxResponse(@JsonProperty("status") val status: Int? = null, @JsonProperty("result") val result: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServerResponse(@JsonProperty("status") val status: Int? = null, @JsonProperty("result") val result: ServerResult? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServerResult(@JsonProperty("url") val url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeData(val animeId: String, val dataIds: String, val epNum: Int, val epTitle: String, val epType: String, val animeUrl: String)
}
