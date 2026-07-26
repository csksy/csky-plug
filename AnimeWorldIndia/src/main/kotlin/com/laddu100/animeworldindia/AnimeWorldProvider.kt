package com.laddu100.animeworldindia

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import java.net.URLEncoder

class AnimeWorldProvider : MainAPI() {
    override var mainUrl = "https://watchanimeworld.top"
    override var name = "Anime World India"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Cartoon)

    private val TAG = "AnimeWorld"

    @Volatile
    private var isUrlLoaded = false

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FirebaseConfig(
        @JsonProperty("animeworldindia_url") val animeworldindia_url: String? = null,
        @JsonProperty("animeworld_url") val animeworld_url: String? = null,
        @JsonProperty("awi_url") val awi_url: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json", timeout = 10_000L).text
            val config = parseJson<FirebaseConfig>(response)
            val url = config.animeworldindia_url ?: config.animeworld_url ?: config.awi_url
            if (!url.isNullOrBlank()) {
                mainUrl = url.removeSuffix("/")
            }
            isUrlLoaded = true
        } catch (e: Exception) {
            isUrlLoaded = true
        }
    }

    override val mainPage = mainPageOf(
        "home" to "Latest Series",
        "franchise" to "Franchises"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        loadFirebaseUrl()
        return try {
            val response = animeWorldGet(mainUrl)
            val doc = Jsoup.parse(response.text)

            when (request.data) {
                "home" -> {
                    val links = doc.select("a[href*=/series/]")
                    val seen = mutableSetOf<String>()
                    val items = links.mapNotNull { el ->
                        val href = el.attr("href")
                        if (!seen.add(href)) return@mapNotNull null
                        val title = el.selectFirst("h2, h3, .title")?.text()?.trim()
                            ?: el.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val poster = el.selectFirst("img")?.let { img ->
                            img.attr("data-src").ifBlank { img.attr("src") }
                        }
                        newAnimeSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = poster?.let { if (it.startsWith("//")) "https:$it" else it }
                        }
                    }
                    newHomePageResponse("Latest Series", items, hasNext = false)
                }
                "franchise" -> {
                    val links = doc.select("a[href*=/category/franchise/]")
                    val items = links.mapNotNull { el ->
                        val href = el.attr("href")
                        val title = el.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val poster = el.selectFirst("img")?.let { img ->
                            img.attr("data-src").ifBlank { img.attr("src") }
                        }
                        newAnimeSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = poster?.let { if (it.startsWith("//")) "https:$it" else it }
                        }
                    }
                    newHomePageResponse("Franchises", items, hasNext = false)
                }
                else -> newHomePageResponse(request.name, emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        loadFirebaseUrl()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val response = animeWorldGet("$mainUrl/?s=$encoded")
            val doc = Jsoup.parse(response.text)
            doc.select("a[href*=/series/]").mapNotNull { el ->
                val href = el.attr("href")
                val title = el.selectFirst("h2, h3, .title")?.text()?.trim()
                    ?: el.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }
                }
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster?.let { if (it.startsWith("//")) "https:$it" else it }
                }
            }.distinctBy { it.url }
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        loadFirebaseUrl()
        return try {
            val response = animeWorldGet(url)
            val doc = Jsoup.parse(response.text)

            val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
            val poster = doc.selectFirst(".poster img, .post-thumbnail img")?.let { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                if (src.startsWith("//")) "https:$src" else src
            }
            val backdrop = doc.selectFirst(".backdrop img, .bg img")?.let { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                if (src.startsWith("//")) "https:$src" else src
            }
            val plot = doc.selectFirst(".description, .synopsis, .plot, p")?.text()?.trim()
            val genres = doc.select("a[href*=genre]").map { it.text().trim() }.filter { it.isNotBlank() }

            val postId = doc.select("[data-post]").firstOrNull()?.attr("data-post") ?: ""
            val seasons = doc.select("[data-season]").mapNotNull { el ->
                val seasonNum = el.attr("data-season").toIntOrNull() ?: return@mapNotNull null
                seasonNum
            }.distinct().sorted()

            val episodes = mutableListOf<Episode>()
            if (seasons.isNotEmpty()) {
                for (season in seasons) {
                    val epLinks = if (season == seasons.first()) {
                        doc.select("a[href*=/episode/]").map { it.attr("href") }.distinct()
                    } else {
                        fetchSeasonEpisodes(postId, season)
                    }
                    for (epUrl in epLinks) {
                        val epTitle = epUrl.substringAfter("/episode/").replace("-", " ")
                            .replaceBeforeLast("-s0", "").ifBlank { epUrl.substringAfter("/episode/") }
                        val epNum = Regex("""(\d+)x(\d+)""").find(epUrl)?.let { m ->
                            m.groupValues[2].toIntOrNull()
                        } ?: (episodes.size + 1)
                        val seasonNum = Regex("""(\d+)x(\d+)""").find(epUrl)?.let { m ->
                            m.groupValues[1].toIntOrNull()
                        } ?: season
                        episodes.add(newEpisode(epUrl) {
                            this.season = seasonNum
                            this.episode = epNum
                            this.name = epTitle
                        })
                    }
                }
            } else {
                doc.select("a[href*=/episode/]").map { it.attr("href") }.distinct().forEachIndexed { idx, epUrl ->
                    val epNum = Regex("""(\d+)x(\d+)""").find(epUrl)?.let { m ->
                        m.groupValues[2].toIntOrNull()
                    } ?: (idx + 1)
                    val seasonNum = Regex("""(\d+)x(\d+)""").find(epUrl)?.let { m ->
                        m.groupValues[1].toIntOrNull()
                    } ?: 1
                    episodes.add(newEpisode(epUrl) {
                        this.season = seasonNum
                        this.episode = epNum
                        this.name = epUrl.substringAfter("/episode/").replace("-", " ")
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop ?: poster
                this.plot = plot
                this.tags = genres
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    private suspend fun fetchSeasonEpisodes(postId: String, season: Int): List<String> {
        if (postId.isBlank()) return emptyList()
        return try {
            val body = "action=action_select_season&post=$postId&season=$season"
            val response = animeWorldPost("$mainUrl/wp-admin/admin-ajax.php", body, headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest"
            ))
            val doc = Jsoup.parse(response.text)
            doc.select("a[href*=/episode/]").map { it.attr("href") }.distinct()
        } catch (e: Exception) {
            Log.e(TAG, "fetchSeasonEpisodes: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadFirebaseUrl()
        return try {
            val response = animeWorldGet(data)
            val doc = Jsoup.parse(response.text)

            val iframes = doc.select("iframe").mapNotNull { el ->
                val src = el.attr("data-src").ifBlank { el.attr("src") }
                if (src.isNotBlank()) src else null
            }.distinct()

            var found = false
            for (iframe in iframes) {
                when {
                    iframe.contains("zephyrflick") -> {
                        val videoId = Regex("/video/([a-f0-9]+)").find(iframe)?.groupValues?.get(1) ?: continue
                        val resolved = resolveZephyrFlick(videoId, subtitleCallback, callback)
                        if (resolved) found = true
                    }
                    iframe.contains("player1.php") -> {
                        val base64Data = Regex("data=([A-Za-z0-9+/=]+)").find(iframe)?.groupValues?.get(1) ?: continue
                        val resolved = resolvePlayer1(base64Data, callback)
                        if (resolved) found = true
                    }
                }
            }
            found
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: ${e.message}")
            false
        }
    }

    private suspend fun resolveZephyrFlick(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = animeWorldGet("https://play.zephyrflick.top/video/$videoId", headers = mapOf(
                "Referer" to mainUrl
            ))
            val html = response.text
            val allHashes = Regex("[a-f0-9]{32}").findAll(html).map { it.value }.distinct().toList()
            val cdnHash = allHashes.firstOrNull { it != videoId } ?: return false

            val m3u8Url = "https://s7.as-cdn13.top/cdn/down/$cdnHash/master.m3u8"

            val subtitleUrl = Regex("https?://[^\"'\\s]*Subtitle/[^\"'\\s]+\\.srt").find(html)?.value
            if (subtitleUrl != null) {
                subtitleCallback.invoke(SubtitleFile("en", subtitleUrl))
            }

            val links = M3u8Helper.generateM3u8(
                source = "Anime World India",
                streamUrl = m3u8Url,
                referer = "https://play.zephyrflick.top/",
                quality = Qualities.Unknown.value,
                headers = mapOf("Referer" to "https://play.zephyrflick.top/"),
                name = "Anime World India - ZephyrFlick"
            )
            links.forEach { callback.invoke(it) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "resolveZephyrFlick: ${e.message}")
            false
        }
    }

    private suspend fun resolvePlayer1(base64Data: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val decoded = java.util.Base64.getDecoder().decode(base64Data).toString(Charsets.UTF_8)
            val languages = parseJson<List<Player1Language>>(decoded)
            for (lang in languages) {
                val link = newExtractorLink(
                    "Anime World India",
                    "Anime World India - ${lang.language}",
                    lang.link,
                    ExtractorLinkType.VIDEO
                ) {}
                callback.invoke(link)
            }
            languages.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "resolvePlayer1: ${e.message}")
            false
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Player1Language(
        @JsonProperty("language") val language: String = "",
        @JsonProperty("link") val link: String = ""
    )
}
