package com.laddu100.raghavanime

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup

class RaghavNineAnime : MainAPI() {
    override var mainUrl = "https://9anime.org.lv"
    override var name = "9anime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "" to "Latest Releases"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("nineanime") ?: mainUrl
        val url = if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
        val doc = app.get(url).document
        val anime = doc.select("article.bs").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst(".entry-title")?.text()
                ?: element.selectFirst("h2")?.text()
                ?: a.text()
            newAnimeSearchResponse(title, a.attr("href"), TvType.Anime) {
                this.posterUrl = element.selectFirst("img")?.attr("src")
            }
        }
        return newHomePageResponse(request.name, anime, hasNext = anime.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("nineanime") ?: mainUrl
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return doc.select("article.bs").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst(".entry-title")?.text()
                ?: element.selectFirst("h2")?.text()
                ?: a.text()
            newAnimeSearchResponse(title, a.attr("href"), TvType.Anime) {
                this.posterUrl = element.selectFirst("img")?.attr("src")
            }
        }
    }

    // the site lists sub and dub as separate entries; dub entries carry a
    // marker in the title or slug so the audio can be picked without loading
    private fun entryIsDub(name: String, url: String): Boolean {
        return name.contains("(Dub)", true) || url.contains("-dub", true)
    }

    private fun cleanTitle(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // finds the watch page url for the requested episode; dub requests prefer
    // the dub entry of the same show and fall back to the sub one
    suspend fun findEpisodeLink(
        searchTitles: List<String>,
        episode: Int,
        isDub: Boolean
    ): String? {
        val queries = searchTitles.filter { it.isNotBlank() }.toMutableList()
        if (isDub) {
            searchTitles.filter { it.isNotBlank() }.forEach { queries.add("$it dub") }
        }

        data class Candidate(val score: Int, val name: String, val url: String, val dub: Boolean)

        val candidates = mutableListOf<Candidate>()
        for (query in queries.distinct()) {
            val results = try {
                search(query)
            } catch (e: Throwable) {
                Log.e("RaghavAnimeKitsu", "[9anime] search '$query' failed: ${e.message}")
                continue
            }
            val target = cleanTitle(query)
            for (r in results) {
                val c = cleanTitle(r.name)
                val titleScore = when {
                    c == target -> 3
                    c.removeSuffix(" dub") == target.removeSuffix(" dub") -> 2
                    c.contains(target.removeSuffix(" dub")) || target.contains(c) -> 1
                    else -> 0
                }
                if (titleScore == 0) continue
                candidates.add(Candidate(titleScore, r.name, r.url, entryIsDub(r.name, r.url)))
            }
        }
        if (candidates.isEmpty()) {
            Log.d("RaghavAnimeKitsu", "[9anime] no title match for '${searchTitles.firstOrNull()}' ep $episode ${if (isDub) "dub" else "sub"}")
            return null
        }

        // sub and dub live on separate entries, only the one carrying the
        // requested audio can actually serve the episode
        val usable = candidates.filter { it.dub == isDub }.ifEmpty { candidates }

        for (cand in usable.sortedByDescending { it.score }.distinctBy { it.url }) {
            try {
                val loadResult = load(cand.url) as? com.lagradost.cloudstream3.AnimeLoadResponse ?: continue
                val epKey = if (cand.dub) DubStatus.Dubbed else DubStatus.Subbed
                val ep = loadResult.episodes?.get(epKey)?.find { it.episode == episode } ?: continue
                Log.d("RaghavAnimeKitsu", "[9anime] matched '${cand.name}' (dub=${cand.dub}) for ep $episode")
                return ep.data
            } catch (e: Throwable) {
                Log.e("RaghavAnimeKitsu", "[9anime] load failed for '${cand.name}': ${e.message}")
            }
        }
        Log.d("RaghavAnimeKitsu", "[9anime] ep $episode not found on ${usable.size} candidates")
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("nineanime") ?: mainUrl
        var detailUrl = url
        if (!url.contains("/anime/")) {
            val doc = app.get(url).document
            val animeLink = doc.select("a[href*=/anime/]").map { it.attr("href") }.firstOrNull { href ->
                val path = href.substringAfter("/anime/").trim('/')
                path.isNotEmpty() && !path.contains('/') && !path.contains('?') && !path.contains("page")
            }
            if (animeLink != null) {
                detailUrl = animeLink
            }
        }

        val doc = app.get(detailUrl).document
        val title = doc.selectFirst("h1.entry-title")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: "Unknown"
        val posterUrl = doc.selectFirst(".thumb img")?.attr("src")
            ?: doc.selectFirst(".poster img")?.attr("src")
            ?: doc.selectFirst("img")?.attr("src")
        val plot = doc.selectFirst(".entry-content")?.text()
            ?: doc.selectFirst(".desc")?.text()
            ?: doc.selectFirst(".story")?.text()
        val tags = doc.select(".genxed a").map { it.text() }

        val statusText = doc.selectFirst(".info-content")?.text() ?: ""
        val showStatus = when {
            statusText.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
            statusText.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
            else -> null
        }
        val typeText = statusText
        val tvType = when {
            typeText.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            typeText.contains("OVA", ignoreCase = true) || typeText.contains("ONA", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }

        val episodesList = mutableListOf<Episode>()
        val eplister = doc.select(".eplister li")
        if (eplister.isNotEmpty()) {
            eplister.forEach { li ->
                val a = li.selectFirst("a") ?: return@forEach
                val epNum = li.selectFirst(".epl-num")?.text()?.toIntOrNull()
                    ?: Regex("""\d+""").find(li.selectFirst(".epl-num")?.text() ?: "")?.value?.toIntOrNull()
                    ?: 1
                episodesList.add(newEpisode(a.attr("href")) {
                    this.name = li.selectFirst(".epl-title")?.text() ?: "Episode $epNum"
                    this.episode = epNum
                    this.description = li.selectFirst(".epl-date")?.text()
                })
            }
        } else {
            episodesList.add(newEpisode(detailUrl) {
                this.name = title
                this.episode = 1
            })
        }

        // episodes are listed newest first, flip to chronological order
        episodesList.reverse()

        val isDub = entryIsDub(title, detailUrl)
        return newAnimeLoadResponse(title, detailUrl, tvType) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.showStatus = showStatus
            if (isDub) {
                addEpisodes(DubStatus.Dubbed, episodesList)
            } else {
                addEpisodes(DubStatus.Subbed, episodesList)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val res = try {
            app.get(data)
        } catch (e: Exception) {
            Log.e("RaghavAnimeKitsu", "[9anime] watch page failed for ${data.take(90)}: ${e.message}")
            return@coroutineScope false
        }
        val doc = res.document

        // base64 mirror options in the <select class="mirror"> tag, each holding
        // an iframe snippet; gogo player mirrors wrap the real embeds one level
        // deeper and expose one iframe per server variant
        val embedUrls = doc.select("select.mirror option").mapNotNull { opt ->
            val b64Value = opt.attr("value")
            if (b64Value.isBlank() || b64Value == "...") return@mapNotNull null
            val decodedIframe = try {
                String(Base64.decode(b64Value, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null
            Jsoup.parse(decodedIframe).selectFirst("iframe")?.attr("src")
        }.flatMap { iframeUrl ->
            if (iframeUrl.contains("gogoanime.me.uk/newplayer.php")) {
                val playerHtml = try {
                    app.get(iframeUrl, headers = mapOf("Referer" to data)).text
                } catch (e: Exception) {
                    ""
                }
                val innerSrcs = Jsoup.parse(playerHtml).select("iframe")
                    .map { it.attr("src") }.filter { it.isNotBlank() }
                if (innerSrcs.isNotEmpty()) innerSrcs else listOf(iframeUrl)
            } else {
                listOf(iframeUrl)
            }
        }.distinct()

        Log.d("RaghavAnimeKitsu", "[9anime] ${embedUrls.size} mirrors for ${data.take(80)}")

        val results = embedUrls.map { embedUrl ->
            async {
                try {
                    resolveMirror(embedUrl, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e("RaghavAnimeKitsu", "[9anime] mirror ${embedUrl.take(90)} failed: ${e.message}")
                    false
                }
            }
        }
        results.awaitAll().any { it }
    }

    private suspend fun resolveMirror(
        embedUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            embedUrl.contains("plyr.php#") -> {
                val b64 = embedUrl.substringAfter("#").substringBefore("#")
                val decodedUrl = try {
                    String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: Exception) {
                    null
                } ?: return false
                if (decodedUrl.isBlank()) return false
                callback.invoke(
                    newExtractorLink(
                        "9anime KiwiK",
                        "9anime KiwiK",
                        decodedUrl,
                        if (decodedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://gogoanime.me.uk/"
                    }
                )
                true
            }
            embedUrl.contains("megaplay.buzz") || embedUrl.contains("vidtube.site") || embedUrl.contains("vidwish.live") -> {
                val label = "9anime MegaPlay"
                val stream = MegaPlayHelper.resolveStream(embedUrl, refererUrl, "9anime")
                if (stream == null) {
                    Log.d("RaghavAnimeKitsu", "[9anime] megaplay gave no stream for ${embedUrl.take(80)}")
                    false
                } else {
                    MegaPlayHelper.emitLinks(
                        "9anime", label, stream.m3u8, "https://megaplay.buzz/",
                        stream.subtitles, subtitleCallback, callback
                    )
                }
            }
            embedUrl.contains("vidmoly.biz") -> {
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to refererUrl
                )
                val html = try {
                    app.get(embedUrl, headers = headers).text
                } catch (e: Exception) {
                    return false
                }
                val m3u8 = Regex("""file\s*:\s*['"](https?://[^'"]+?\.m3u8[^'"]*)['"]""").find(html)
                    ?.groupValues?.get(1)
                    ?: Regex("""https?://[^'"\s]+?\.m3u8[^'"\s]*""").find(html)?.value
                if (m3u8 == null) {
                    Log.d("RaghavAnimeKitsu", "[9anime] vidmoly gave no m3u8")
                    return false
                }
                val playHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://vidmoly.biz/",
                    "Origin" to "https://vidmoly.biz"
                )
                val generated = try {
                    M3u8Helper.generateM3u8("9anime Vidmoly", m3u8, "https://vidmoly.biz", headers = playHeaders)
                } catch (e: Exception) {
                    emptyList()
                }
                if (generated.isNotEmpty()) {
                    generated.forEach(callback)
                    true
                } else {
                    callback.invoke(
                        newExtractorLink("9anime Vidmoly", "9anime Vidmoly", m3u8, type = ExtractorLinkType.M3U8) {
                            this.referer = "https://vidmoly.biz/"
                            this.headers = playHeaders
                        }
                    )
                    true
                }
            }
            embedUrl.contains("bysesayeveum.com") -> {
                try {
                    val resolver = WebViewResolver(
                        interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                        additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                        // the byse player mounts inside a cross-origin iframe, clicking the
                        // outer container forwards the action to the inner play button
                        script = """document.querySelector('button,[role="button"],.vjs-big-play-button,.jw-icon-display,.vds-play-button,[onclick]')?.click();""",
                        // the moon player handshake routinely takes over a minute to clear
                        useOkhttp = false,
                        timeout = 90_000L
                    )
                    val resolved = app.get(embedUrl, referer = refererUrl, interceptor = resolver).url
                    val headers = mapOf("Referer" to embedUrl)
                    when {
                        resolved.contains(".m3u8", ignoreCase = true) -> {
                            M3u8Helper.generateM3u8("9anime Moon", resolved, embedUrl, headers = headers).forEach(callback)
                            true
                        }
                        resolved.contains(".mp4", ignoreCase = true) -> {
                            callback.invoke(
                                newExtractorLink("9anime Moon", "9anime Moon", resolved, type = ExtractorLinkType.VIDEO) {
                                    this.headers = headers
                                }
                            )
                            true
                        }
                        else -> false
                    }
                } catch (e: Exception) {
                    Log.e("RaghavAnimeKitsu", "[9anime] moon webview failed: ${e.message}")
                    false
                }
            }
            else -> {
                try {
                    loadExtractor(embedUrl, refererUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    false
                }
            }
        }
    }
}
