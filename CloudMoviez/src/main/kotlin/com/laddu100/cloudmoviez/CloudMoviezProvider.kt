package com.laddu100.cloudmoviez

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

@JsonIgnoreProperties(ignoreUnknown = true)
data class CmzServer(
    @JsonProperty("s") val s: String = "",
    @JsonProperty("u") val u: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CmzRow(
    @JsonProperty("n") val n: String = "",
    @JsonProperty("q") val q: String? = null,
    @JsonProperty("at") val at: String? = null,
    @JsonProperty("t") val t: String = "f",
    @JsonProperty("u") val u: String? = null,
    @JsonProperty("srv") val srv: List<CmzServer> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CmzRowList(
    @JsonProperty("rows") val rows: List<CmzRow> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CmzFileTarget(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("post") val post: Long? = null,
    @JsonProperty("title") val title: String? = null
)

class CloudMoviezProvider : MainAPI() {
    override var mainUrl = MAIN_URL
    override var name = "CloudMoviez"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "CloudMoviez"

    override val mainPage = mainPageOf(
        "/movies/" to "Latest Movies",
        "/tvshows/" to "Latest TV Shows",
        "/genre/4k/" to "4K",
        "/genre/bollywood/" to "Bollywood",
        "/genre/hollywood/" to "Hollywood",
        "/genre/anime/" to "Anime"
    )

    private val rawClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun qualityValue(q: String?): Int {
        val s = q ?: return Qualities.Unknown.value
        return when {
            s.contains("2160") || s.contains("4k", true) -> 2160
            s.contains("1440") -> 1440
            s.contains("1080") -> 1080
            s.contains("720") -> 720
            s.contains("480") -> 480
            else -> Qualities.Unknown.value
        }
    }

    private fun parseSeasonEpisode(name: String): Pair<Int, Int>? {
        val m = Regex("""[Ss](\d{1,2})[\s._-]*[Ee](\d{1,3})""").find(name) ?: return null
        val s = m.groupValues[1].toIntOrNull() ?: return null
        val e = m.groupValues[2].toIntOrNull() ?: return null
        return Pair(s, e)
    }

    private fun decodeFileParam(b64: String): CmzFileTarget? {
        return try {
            val cleaned = b64.replace("-", "+").replace("_", "/").replace("=", "")
            val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
            val json = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            parseJson<CmzFileTarget>(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun absolute(href: String, base: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> base.trimEnd('/') + href
        else -> base.trimEnd('/') + "/" + href
    }

    private fun imgSrc(el: Element?): String? {
        el ?: return null
        val src = el.attr("data-src").ifBlank { el.attr("src") }
        if (src.isBlank()) return null
        return if (src.startsWith("//")) "https:$src" else src
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst(".details .title a, .data h3 a, h3.title a")
            ?: selectFirst(".thumbnail a, a[href*='/movies/'], a[href*='/tvshows/']")
            ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank() || (!href.contains("/movies/") && !href.contains("/tvshows/"))) return null
        var title = selectFirst(".details .title a, .data h3 a, h3.title a")?.text()?.trim() ?: ""
        if (title.isBlank()) title = selectFirst("img")?.attr("alt")?.trim() ?: a.text().trim()
        if (title.isBlank()) return null
        val isTv = href.contains("/tvshows/") || selectFirst(".tvshows, span.tvshows") != null
        val poster = imgSrc(selectFirst("img"))
        val year = Regex("""\b(19|20)\d{2}\b""").find(selectFirst(".meta, .data")?.text() ?: "")?.value?.toIntOrNull()
            ?: selectFirst(".year")?.text()?.trim()?.toIntOrNull()
        return newMovieSearchResponse(title, href, if (isTv) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val base = request.data.trimEnd('/')
            val url = if (page <= 1) "$MAIN_URL$base/" else "$MAIN_URL$base/page/$page/"
            val doc = cmzGet(url).document
            val items = doc.select("article.item, .items article, .result-item article").mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            val hasNext = items.isNotEmpty() && doc.selectFirst("a[href*='/page/${page + 1}/'], a.next.page-numbers, a:containsOwn(→)") != null
            newHomePageResponse(request.name, items, hasNext = hasNext)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val url = "$MAIN_URL/?s=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val doc = cmzGet(url).document
            doc.select(".result-item article, article.item").mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    private fun parseRows(doc: Document): List<CmzRow> {
        val rows = mutableListOf<CmzRow>()
        for (acc in doc.select("div.cmz-accordion.cmz-filter-item")) {
            val accTitle = acc.selectFirst(".cmz-accordion-title")?.text()?.trim() ?: ""
            val accQuality = acc.attr("data-quality").trim()
            for (row in acc.select(".cmz-row-card")) {
                val name = row.selectFirst(".cmz-row-title")?.text()?.trim() ?: continue
                val servers = mutableListOf<CmzServer>()
                for (btn in row.select("a.cmz-download-btn")) {
                    val href = btn.attr("href").trim()
                    if (!href.contains("?file=")) continue
                    val target = decodeFileParam(href.substringAfter("?file=")) ?: continue
                    val tUrl = target.url ?: continue
                    val tName = target.title ?: btn.className().substringAfter("cmz-btn-").uppercase()
                    servers.add(CmzServer(s = tName, u = tUrl))
                }
                if (servers.isEmpty()) continue
                val q = row.attr("data-quality").trim().ifBlank { accQuality }.ifBlank { name }
                rows.add(CmzRow(n = name, q = q, at = accTitle.ifBlank { null }, t = "f", u = null, srv = servers))
            }
        }
        return rows
    }

    private fun parseLinksPages(doc: Document): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (a in doc.select("a[href*='/links/']")) {
            val href = a.attr("href").trim()
            if (!href.contains("/links/")) continue
            val full = absolute(href, MAIN_URL)
            val label = a.text().replace('\u00a0', ' ').trim()
            if (label.isBlank()) continue
            out.add(Pair(full, label))
        }
        return out.distinctBy { it.first }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = cmzGet(url).document
            val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
            val poster = imgSrc(doc.selectFirst(".poster img, .sheader .poster img"))
            val backdrop = doc.selectFirst("#single .backdrop img, .bg img")?.let { imgSrc(it) } ?: poster
            val dateText = doc.selectFirst("span.date")?.text() ?: ""
            val year = Regex("""\b(19|20)\d{2}\b""").find(dateText)?.value?.toIntOrNull()
            val rating = doc.selectFirst(".dt_rating_vgs")?.text()?.trim()?.toDoubleOrNull()
            val duration = doc.selectFirst(".runtime")?.text()?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val genres = doc.select(".sgeneros a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct().take(12)
            val plot = doc.selectFirst("#info .wp-content p, .wp-content p, [itemprop=description]")?.text()?.trim()?.take(1000)
            val cast = doc.select(".persons .person .data .name a, #cast .person .data .name a").map { ActorData(Actor(it.text().trim())) }.filter { it.actor.name.isNotBlank() }.distinctBy { it.actor.name }.take(15)

            val isTv = url.contains("/tvshows/")
            val rows = parseRows(doc)
            val linksPages = parseLinksPages(doc)
            val seasonFromTitle = Regex("""\[Season\s*(\d+)\]""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            if (isTv) {
                val episodes = mutableListOf<Episode>()
                for (row in rows) {
                    val se = parseSeasonEpisode(row.n)
                    val season = se?.first ?: seasonFromTitle
                    val epNum = se?.second ?: (episodes.size + 1)
                    episodes.add(newEpisode(CmzRowList(listOf(row)).toJson()) {
                        this.season = season
                        this.episode = epNum
                        this.name = row.n
                    })
                }
                val nameCount = mutableMapOf<String, Int>()
                for ((linkUrl, label) in linksPages) {
                    val c = (nameCount[label] ?: 0) + 1
                    nameCount[label] = c
                    val displayName = if (c > 1) "$label (${linkUrl.trimEnd('/').substringAfterLast('/')})" else label
                    episodes.add(newEpisode(CmzRowList(listOf(CmzRow(n = label, q = label, at = null, t = "l", u = linkUrl, srv = emptyList()))).toJson()) {
                        this.season = seasonFromTitle
                        this.episode = episodes.size + 1
                        this.name = displayName
                    })
                }
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    this.score = rating?.let { Score.from10(it.toString()) }
                    this.duration = duration
                    this.actors = cast
                }
            } else {
                val extraRows = linksPages.map { (linkUrl, label) ->
                    CmzRow(n = label, q = label, at = null, t = "l", u = linkUrl, srv = emptyList())
                }
                return newMovieLoadResponse(title, url, TvType.Movie, CmzRowList(rows + extraRows).toJson()) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    this.score = rating?.let { Score.from10(it.toString()) }
                    this.duration = duration
                    this.actors = cast
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    private fun shortLabel(row: CmzRow): String {
        val base = row.at ?: row.q ?: ""
        return base.trim()
    }

    private suspend fun resolveRow(row: CmzRow, callback: (ExtractorLink) -> Unit) {
        if (row.t == "l") {
            val linkUrl = row.u ?: return
            try {
                val doc = cmzGet(linkUrl).document
                val target = doc.selectFirst("a#link")?.attr("href")?.trim() ?: return
                if (target.isBlank()) return
                resolveTarget(target, row.n, row, callback)
            } catch (e: Exception) {
                Log.e(TAG, "linksPage: ${e.message}")
            }
            return
        }
        for (server in row.srv) {
            resolveTarget(server.u, server.s, row, callback)
        }
    }

    private suspend fun resolveTarget(url: String, serverName: String, row: CmzRow, callback: (ExtractorLink) -> Unit) {
        val normalized = cmzNormalizeUrl(url)
        val host = cmzHostOf(normalized)
        try {
            when {
                host.endsWith("gdflix.io") -> {
                    val fileId = Regex("""/file/([A-Za-z0-9]+)""").find(normalized)?.groupValues?.get(1)
                    val packId = Regex("""/pack/([A-Za-z0-9]+)""").find(normalized)?.groupValues?.get(1)
                    when {
                        fileId != null -> resolveGdflixFile(fileId, serverName, row, callback)
                        packId != null -> resolveGdflixPack(packId, serverName, row, callback)
                    }
                }
                host.endsWith("dotflix.store") -> {
                    val code = Regex("""/share/([A-Za-z0-9]+)""").find(normalized)?.groupValues?.get(1)
                    if (code != null) resolveDotflix(code, serverName, row, callback)
                }
                host.endsWith("pixeldrain.dev") -> {
                    val pdId = Regex("""/u/([A-Za-z0-9]+)""").find(normalized)?.groupValues?.get(1)
                    if (pdId != null) emitLink("PixelDrain", "https://pixeldrain.dev/api/file/$pdId?download", row, callback)
                }
                else -> Log.w(TAG, "unhandled host: $host")
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveTarget: ${e.message}")
        }
    }

    private fun linkLabel(kind: String, row: CmzRow): String {
        val base = shortLabel(row)
        return if (base.isBlank()) kind else "$kind · $base"
    }

    private suspend fun emitLink(kind: String, url: String, row: CmzRow, callback: (ExtractorLink) -> Unit) {
        if (url.isBlank()) return
        callback.invoke(newExtractorLink("CloudMoviez", linkLabel(kind, row), url, ExtractorLinkType.VIDEO) {
            this.quality = qualityValue(row.q ?: row.n)
        })
    }

    private suspend fun resolveBusycdn(instantUrl: String): String? {
        return try {
            val resp = app.get(instantUrl, allowRedirects = false, timeout = 20_000L)
            val loc = resp.headers["location"] ?: resp.headers["Location"]
            if (!loc.isNullOrBlank() && loc.contains("url=")) {
                java.net.URLDecoder.decode(loc.substringAfter("url="), "UTF-8")
            } else if (!loc.isNullOrBlank()) {
                loc
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "busycdn: ${e.message}")
            null
        }
    }

    private suspend fun gdflixMultipartPost(url: String, parts: Map<String, String>): String? {
        val sessionHeaders = cmzSessionHeaders("new4.gdflix.io")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply { parts.forEach { (k, v) -> addFormDataPart(k, v) } }
            .build()
        val req = Request.Builder().url(url)
            .header("User-Agent", sessionHeaders["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Accept", "*/*")
            .header("Origin", "https://new4.gdflix.io")
            .header("Referer", url)
            .header("x-token", "new4.gdflix.io")
            .apply { sessionHeaders["Cookie"]?.let { header("Cookie", it) } }
            .post(body)
            .build()
        return try {
            withContext(Dispatchers.IO) {
                rawClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "multipart: ${e.message}")
            null
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GdflixCloudStart(
        @JsonProperty("error") val error: Boolean? = null,
        @JsonProperty("url") val url: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GdflixCloudPoll(
        @JsonProperty("done") val done: Boolean? = null,
        @JsonProperty("percent") val percent: Int? = null,
        @JsonProperty("redirect") val redirect: String? = null
    )

    private suspend fun resolveGdflixCloud(cloudPageUrl: String, serverName: String, row: CmzRow, callback: (ExtractorLink) -> Unit) {
        try {
            val page = cmzGet(cloudPageUrl)
            val html = page.text
            val key = Regex("""formData\.append\("key",\s*"([a-f0-9]{40})""").find(html)?.groupValues?.get(1) ?: return
            val startBody = gdflixMultipartPost(
                cloudPageUrl,
                mapOf("action" to "cloud", "key" to key, "action_token" to "")
            ) ?: return
            val start = try { parseJson<GdflixCloudStart>(startBody) } catch (e: Exception) { return }
            if (start.error != false) return
            val tokenPath = start.url ?: return
            val tokenUrl = absolute(tokenPath, "https://new4.gdflix.io")
            cmzGet(tokenUrl)
            var redirect: String? = null
            for (i in 1..20) {
                val pollResp = try {
                    app.get(
                        "$tokenUrl&xhr=1",
                        headers = cmzSessionHeaders("new4.gdflix.io") + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "*/*",
                            "Referer" to tokenUrl
                        ),
                        timeout = 20_000L
                    )
                } catch (e: Exception) { null }
                val pollBody = pollResp?.text
                if (pollBody != null) {
                    val poll = try { parseJson<GdflixCloudPoll>(pollBody) } catch (e: Exception) { null }
                    if (poll?.done == true) {
                        redirect = poll.redirect
                        break
                    }
                }
                delay(1500)
            }
            val redirectPath = redirect ?: return
            val finalUrl = absolute(redirectPath, "https://new4.gdflix.io")
            val finalPage = cmzGet(finalUrl).document
            val dl = finalPage.selectFirst("a[href*='workers.dev/'], a[href*='cloud-dl']")?.attr("href")?.trim() ?: return
            emitLink("GDFlix Cloud", absolute(dl, "https://new4.gdflix.io"), row, callback)
        } catch (e: Exception) {
            Log.e(TAG, "cloud: ${e.message}")
        }
    }

    private suspend fun resolveGdflixFile(fileId: String, serverName: String, row: CmzRow, callback: (ExtractorLink) -> Unit) {
        val page = try {
            cmzGet("https://new4.gdflix.io/file/$fileId")
        } catch (e: Exception) {
            Log.e(TAG, "gdflixFile: ${e.message}")
            return
        }
        val doc = Jsoup.parse(page.text)
        val instant = doc.selectFirst("a[href*='instant.busycdn.xyz']")?.attr("href")?.trim()
        if (!instant.isNullOrBlank()) {
            val direct = resolveBusycdn(instant)
            if (!direct.isNullOrBlank()) {
                emitLink("GDFlix Instant", direct, row, callback)
            }
        }
        val cloudHref = doc.selectFirst("a[href*='/cloud/']")?.attr("href")?.trim()
        if (!cloudHref.isNullOrBlank()) {
            resolveGdflixCloud(absolute(cloudHref, "https://new4.gdflix.io"), serverName, row, callback)
        }
        val pixel = doc.selectFirst("a[href*='pixeldrain.dev/u/']")?.attr("href")?.trim()
        if (!pixel.isNullOrBlank()) {
            val pdId = Regex("""/u/([A-Za-z0-9]+)""").find(pixel)?.groupValues?.get(1)
            if (pdId != null) {
                emitLink("PixelDrain", "https://pixeldrain.dev/api/file/$pdId?download", row, callback)
            }
        }
    }

    private suspend fun resolveGdflixPack(packId: String, serverName: String, row: CmzRow, callback: (ExtractorLink) -> Unit) {
        val page = try {
            cmzGet("https://new4.gdflix.io/pack/$packId")
        } catch (e: Exception) {
            Log.e(TAG, "gdflixPack: ${e.message}")
            return
        }
        val doc = Jsoup.parse(page.text)
        val files = doc.select("a[href*='/file/']").mapNotNull { a ->
            val href = a.attr("href").trim()
            val id = Regex("""/file/([A-Za-z0-9]+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val label = a.text().replace('\u00a0', ' ').trim()
            if (label.isBlank()) return@mapNotNull null
            Pair(id, label)
        }.distinctBy { it.first }
        coroutineScope {
            files.map { (id, label) ->
                async {
                    try {
                        val fdoc = Jsoup.parse(cmzGet("https://new4.gdflix.io/file/$id").text)
                        val instant = fdoc.selectFirst("a[href*='instant.busycdn.xyz']")?.attr("href")?.trim()
                        if (!instant.isNullOrBlank()) {
                            val direct = resolveBusycdn(instant)
                            if (!direct.isNullOrBlank()) {
                                val fileRow = CmzRow(n = label, q = label, at = label, t = "f", u = null, srv = emptyList())
                                emitLink("GDFlix Instant", direct, fileRow, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "packFile: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DotflixExtract(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("downloadUrl") val downloadUrl: String? = null
    )

    private suspend fun resolveDotflix(code: String, serverName: String, row: CmzRow, callback: (ExtractorLink) -> Unit) {
        try {
            val resp = app.post(
                "https://dotflix.store/api/extract-download",
                json = mapOf("sharingCode" to code),
                headers = mapOf(
                    "Accept" to "*/*",
                    "Content-Type" to "application/json",
                    "Origin" to "https://dotflix.store",
                    "Referer" to "https://dotflix.store/share/$code"
                ),
                timeout = 30_000L
            )
            val extract = try { parseJson<DotflixExtract>(resp.text) } catch (e: Exception) { null }
            if (extract?.success == true && !extract.downloadUrl.isNullOrBlank()) {
                emitLink("DotFlix $serverName".trim(), extract.downloadUrl, row, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "dotflix: ${e.message}")
        }
        try {
            val share = cmzGet("https://dotflix.store/share/$code")
            val pixel = Regex("""pixeldrainLink\\?"\s*:\s*\\?"(https://pixeldrain\.dev/u/[A-Za-z0-9]+)""").find(share.text)?.groupValues?.get(1)
            if (!pixel.isNullOrBlank()) {
                val pdId = Regex("""/u/([A-Za-z0-9]+)""").find(pixel)?.groupValues?.get(1)
                if (pdId != null) {
                    emitLink("PixelDrain $serverName".trim(), "https://pixeldrain.dev/api/file/$pdId?download", row, callback)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "dotflixShare: ${e.message}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rows = try {
            parseJson<CmzRowList>(data).rows
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks parse: ${e.message}")
            return false
        }
        if (rows.isEmpty()) return false
        return try {
            coroutineScope {
                rows.map { row ->
                    async { resolveRow(row, callback) }
                }.awaitAll()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: ${e.message}")
            true
        }
    }

    companion object {
        const val MAIN_URL = "https://new.cloudmoviez.shop"
    }
}
