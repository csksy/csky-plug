package com.toonworld4all

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap

@CloudstreamPlugin
class ToonWorld4AllPlugin : Plugin() {
    override fun load(context: Context) {
        initTw4aCFBypass()
        registerMainAPI(ToonWorld4All())
        openSettings = { ctx ->
            (ctx as? androidx.appcompat.app.AppCompatActivity)?.let { activity ->
                ToonWorld4AllSettingsFragment(this).show(activity.supportFragmentManager, "ToonWorld4AllSettings")
            }
            kotlin.Unit
        }
    }
}

class ToonWorld4All : MainAPI() {

    override var mainUrl = "https://toonworld4all.me"
    override var name = "ToonWorld4All"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val loadLinksTimeoutMs: Long? = 5 * 60_000L

    override val supportedTypes = setOf(
        TvType.Cartoon, TvType.Anime, TvType.Movie, TvType.TvSeries, TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "" to "Latest Updates",
        "category/anime" to "Anime (Hindi Dub)",
        "tag/eng-sub-anime" to "Anime (Eng Sub)",
        "tag/animated-series" to "Cartoon Shows",
        "tag/animated-movies" to "Animated Movies",
        "tag/hollywood-movies" to "Hollywood Movies",
        "category/netflix" to "Netflix",
        "category/amazon-prime-video" to "Prime Video",
        "category/disney" to "Disney+",
        "tag/hindi-cartoons" to "Hindi Dub Cartoons",
        "tag/eng-cartoons" to "Eng Dub Cartoons",
        "category/cartoon-network-india" to "Cartoon Network",
        "category/disney-channel-india" to "Disney Channel",
        "category/sonic-nickelodeon" to "Sonic Nickelodeon",
        "category/nick-india" to "Nick India",
        "category/pogo" to "Pogo",
        "category/hungama-tv" to "Hungama TV",
        "category/sony-yay" to "Sony Yay",
        "category/discovery-kids" to "Discovery Kids",
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val section = request.data.trimEnd('/')
        val url = when {
            page <= 1 -> if (section.isEmpty()) "$mainUrl/" else "$mainUrl/$section/"
            else -> if (section.isEmpty()) "$mainUrl/page/$page/" else "$mainUrl/$section/page/$page/"
        }
        val html = fetchHtml(url)
            ?: throw ErrorLoadingException("Could not reach toonworld4all.me. Check your connection and retry.")
        val results = parsePostList(Jsoup.parse(html))
        if (results.isEmpty() && tw4aLooksLikeChallenge(html)) {
            throw ErrorLoadingException("Cloudflare is blocking toonworld4all.me on this network. Retry, or try with a VPN.")
        }
        return newHomePageResponse(request.name, results, results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val url = if (page == 1) "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
            else "$mainUrl/page/$page/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val html = try {
                fetchHtml(url) ?: break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                break
            }
            val found = parsePostList(Jsoup.parse(html))
            if (found.isEmpty()) break
            results.addAll(found.filter { it.url !in results.map { r -> r.url } })
        }
        return results
    }

    private fun parsePostList(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (article in doc.select("article")) {
            val a = article.selectFirst("h2.entry-title a") ?: continue
            val url = a.attr("href").trim()
            if (!url.startsWith(mainUrl)) continue
            val title = a.text().trim()
            if (title.isEmpty() || url == "$mainUrl/") continue

            val img = article.selectFirst(".herald-post-thumbnail img")
                ?: article.selectFirst("img[src]")
            val poster = img?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.takeIf { it.startsWith("http") }

            val type = guessType(url, title, article.attr("class"))
            out.add(
                when (type) {
                    TvType.Movie, TvType.AnimeMovie ->
                        newMovieSearchResponse(title, url, type) { this.posterUrl = poster }
                    else ->
                        newTvSeriesSearchResponse(title, url, type) { this.posterUrl = poster }
                }
            )
        }
        return out
    }

    private fun guessType(url: String, title: String, classes: String): TvType {
        val hay = (url + " " + title + " " + classes).lowercase()
        val isAnime = hay.contains("anime")
        return when {
            hay.contains("movie") || hay.contains("film") ->
                if (isAnime) TvType.AnimeMovie else TvType.Movie
            isAnime -> TvType.Anime
            else -> TvType.Cartoon
        }
    }

    private suspend fun fetchHtml(url: String, attempts: Int = 3): String? {
        var lastHtml: String? = null
        for (attempt in 0 until attempts) {
            try {
                val response = tw4aGet(url, headers, timeout = 25_000L)
                val html = response.text
                if (html.isNotBlank()) {
                    if (!tw4aLooksLikeChallenge(html)) return html
                    lastHtml = html
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.d(TAG, "fetchHtml attempt ${attempt + 1} failed for $url: ${e.message}")
            }
            if (attempt < attempts - 1) kotlinx.coroutines.delay(700L * (attempt + 1))
        }
        return lastHtml
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = fetchHtml(url)
            ?: throw ErrorLoadingException("Could not reach toonworld4all.me. Check your connection and retry.")

        if (tw4aLooksLikeChallenge(html)) {
            throw ErrorLoadingException("Cloudflare is blocking toonworld4all.me on this network. Retry, or try with a VPN.")
        }

        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: url.trimEnd('/').substringAfterLast('/')

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val bodyText = doc.selectFirst("div.entry-content")?.text().orEmpty()
        val synopsis = bodyText.substringAfter("Synopsis:", "").substringBefore("Screenshots")
            .trim().ifBlank {
                doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            }

        val tags = doc.select("div.meta-tags a").map { it.text().lowercase() }
        val isAnime = tags.any { it.contains("anime") } ||
                title.contains("anime", true) ||
                url.contains("anime")

        val movieLinks = doc.select("a[href]")
            .map { it.attr("href") }
            .filter { it.contains("archive.toonworld4all.me/movie/") }
            .distinct()

        val episodeLinks = doc.select("a[href]")
            .map { it.attr("href") }
            .filter { it.contains("archive.toonworld4all.me/episode/") }
            .distinct()

        if (movieLinks.isNotEmpty()) {
            val data = movieLinks.first()
            return newMovieLoadResponse(
                title, url,
                if (isAnime) TvType.AnimeMovie else TvType.Movie,
                data
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
            }
        }

        val episodes = parseEpisodes(doc, episodeLinks)
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(
                title, url,
                if (isAnime) TvType.Anime else TvType.Cartoon,
                episodes
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.showStatus = null
            }
        }

        if (doc.selectFirst("div.entry-content") != null) {
            throw ErrorLoadingException("This post has no archive links (old format). Open it in a browser.")
        }
        throw ErrorLoadingException("Could not load this post - the page did not parse. Retry.")
    }

    private fun parseEpisodes(
        doc: org.jsoup.nodes.Document,
        episodeLinks: List<String>
    ): List<Episode> {
        val content = doc.selectFirst("div.entry-content") ?: doc.body()

        data class Marker(val season: Int, val index: Int)
        val markers = mutableListOf<Marker>()
        val ordered = content.allElements
        content.select("p, h1, h2, h3, h4, strong").forEach { el ->
            if (el.select("a").isNotEmpty()) return@forEach
            val txt = el.text().trim()
            val m = Regex("""(?i)^(?:COMPLETE\s+)?SEASON\s*(\d{1,2})\b""").find(txt)
            if (m != null) markers.add(Marker(m.groupValues[1].toInt(), ordered.indexOf(el)))
        }

        fun seasonOf(el: org.jsoup.nodes.Element, fallbackUrl: String): Int {
            val elIdx = ordered.indexOf(el)
            var best: Int? = null
            for (mk in markers) if (mk.index in 0..elIdx) best = mk.season
            if (best != null) return best
            Regex("""-(\d{1,2})x\d{1,3}""").find(fallbackUrl)?.let {
                return it.groupValues[1].toIntOrNull() ?: 1
            }
            Regex("""-s(\d{1,2})e\d{1,3}""", RegexOption.IGNORE_CASE).find(fallbackUrl)?.let {
                return it.groupValues[1].toIntOrNull() ?: 1
            }
            return 1
        }

        val episodes = mutableListOf<Episode>()

        val accordionItems = doc.select("div.mks_accordion_item")
            .filter { it.selectFirst("a[href*='archive.toonworld4all.me/episode/']") != null }
        for (item in accordionItems) {
            val heading = item.selectFirst(".mks_accordion_heading")?.text()?.trim() ?: continue
            val link = item.selectFirst("a[href*='archive.toonworld4all.me/episode/']") ?: continue
            val href = link.attr("href")
            val season = seasonOf(item, href)
            val epNum = Regex("""(?i)(?:episode|ep\.?|e)\s*(\d{1,3})""").find(heading)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d{1,2})x(\d{1,3})""").find(href)?.groupValues?.get(2)?.toIntOrNull()
                ?: episodes.size + 1
            val cachedName = episodeNameCache[href]
            val epName = heading.replace(
                Regex("""(?i)^episode\s*\d{1,3}\s*[:\-–]?\s*"""), ""
            ).ifBlank { cachedName }
            episodes.add(
                newEpisode(href) {
                    this.season = season
                    this.episode = epNum
                    this.name = epName
                }
            )
        }

        if (episodes.isEmpty()) {
            for (href in episodeLinks) {
                val epNum = Regex("""(\d{1,2})x(\d{1,3})""").find(href)?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("""(?i)[\-e](\d{1,3})(?:$|/)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: episodes.size + 1
                val season = seasonOf(content, href)
                episodes.add(
                    newEpisode(href) {
                        this.season = season
                        this.episode = epNum
                    }
                )
            }
        }

        val seen = mutableSetOf<Pair<Int, Int>>()
        return episodes.filter { seen.add((it.season ?: 1) to (it.episode ?: 0)) }
    }

    private class PendingFile(
        val host: String,
        val redirectPath: String,
        val quality: Int,
        val label: String,
        val cacheKey: String,
        val priority: Int,
    )

    private class CachedLink(
        val name: String,
        val url: String,
        val quality: Int,
        val referer: String,
        val headers: Map<String, String>?,
    )

    companion object {
        private const val TAG = "TW4A"

        private val resolvedCache = ConcurrentHashMap<String, String>()
        private val linkCache = ConcurrentHashMap<String, List<CachedLink>>()
        private val linkCacheTimes = ConcurrentHashMap<String, Long>()
        private val episodeNameCache = ConcurrentHashMap<String, String>()

        private const val LINK_CACHE_TTL = 30 * 60_000L
        private const val FAIL_TTL = 10 * 60_000L
        private val failTimes = ConcurrentHashMap<String, Long>()

        private fun cacheFail(key: String) {
            failTimes[key] = System.currentTimeMillis()
        }

        private fun isFreshFail(key: String): Boolean {
            val t = failTimes[key] ?: return false
            return System.currentTimeMillis() - t < FAIL_TTL
        }

        private fun cachedLinksFor(url: String): List<CachedLink>? {
            val cached = linkCache[url] ?: return null
            val t = linkCacheTimes[url] ?: 0L
            if (System.currentTimeMillis() - t > LINK_CACHE_TTL) {
                linkCache.remove(url)
                linkCacheTimes.remove(url)
                return null
            }
            return cached
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank() || !data.contains("archive.toonworld4all.me")) return false

        val archive = Tw4aArchive.fetchArchive(data) ?: run {
            Log.e(TAG, "failed to parse archive props for $data")
            return false
        }

        val metadata = archive.metadata
        val isEpisode = metadata?.optString("type") == "EPISODE"
        val realName = metadata?.optString("name")?.takeIf { it.isNotBlank() }
        if (realName != null && isEpisode) {
            episodeNameCache[data] = realName
        }
        val nameSuffix = if (isEpisode && realName != null) " - $realName" else ""

        var emittedAny = false

        archive.streams.forEach { stream ->
            val playUrl = stream.optString("play").takeIf { it.startsWith("http") } ?: return@forEach
            if (Regex("""\.(m3u8|mp4|mkv|webm)(\?|#|$)""").containsMatchIn(playUrl)) {
                callback(
                    newExtractorLink(
                        "ToonWorld4All Watch",
                        "Watch Online",
                        playUrl,
                        ExtractorLinkType.VIDEO
                    )
                )
                emittedAny = true
            }
        }

        val pending = mutableListOf<PendingFile>()
        Tw4aArchive.parseEncodes(archive).forEach { encode ->
            val label = buildString {
                append(encode.codec)
                if (encode.size.isNotBlank()) append(" [${encode.size}]")
            }
            val isHevc = encode.codec.contains("hevc", true) || encode.codec.contains("x265", true) ||
                    encode.codec.contains("265", true)
            val encodePriority = when {
                encode.quality == 720 && !isHevc -> 0
                encode.quality == 1080 && !isHevc -> 1
                encode.quality == 480 && !isHevc -> 2
                encode.quality == 720 -> 3
                encode.quality == 1080 -> 4
                encode.quality == 2160 -> 5
                else -> 6
            }
            encode.files.forEach { file ->
                if (!tw4aHostEnabled(file.host)) return@forEach
                val hostLower = file.host.lowercase()
                val hostPriority = when {
                    hostLower.contains("hubcloud") -> 0
                    hostLower.contains("gdflix") -> 1
                    hostLower.contains("filepress") -> 2
                    hostLower.contains("mega") -> 3
                    else -> 4
                }
                val cacheKey = "$data|${file.host}|$label"
                pending.add(
                    PendingFile(
                        host = file.host,
                        redirectPath = file.redirectPath,
                        quality = encode.quality,
                        label = label + nameSuffix,
                        cacheKey = cacheKey,
                        priority = encodePriority * 10 + hostPriority
                    )
                )
            }
        }
        if (pending.isEmpty()) return emittedAny
        pending.sortBy { it.priority }

        val toResolve = mutableListOf<PendingFile>()
        for (file in pending) {
            val resolvedUrl = resolvedCache[file.cacheKey]
            if (!resolvedUrl.isNullOrBlank()) {
                emittedAny = emitHostLinks(resolvedUrl, callback) || emittedAny
                continue
            }
            if (isFreshFail(file.cacheKey)) continue
            toResolve.add(file)
        }
        if (toResolve.isEmpty()) return emittedAny

        fun isDirectFileUrl(dest: String): Boolean =
            dest.startsWith("http") && TW4A_FILE_HOST.containsMatchIn(dest) && !tw4aIsShortenerUrl(dest)

        val paths = toResolve.map { it.redirectPath }.distinct()
        val redirectInfos = Tw4aArchive.resolveRedirects(paths)

        val shortenerFiles = mutableListOf<Pair<PendingFile, String>>()
        for (file in toResolve) {
            if (resolvedCache[file.cacheKey] != null) continue
            val info = redirectInfos[file.redirectPath]
            if (info == null) {
                if (!isFreshFail(file.cacheKey)) cacheFail(file.cacheKey)
                continue
            }
            val dest = info.destination
            when {
                isDirectFileUrl(dest) -> {
                    resolvedCache[file.cacheKey] = dest
                }
                dest.startsWith("http") -> {
                    shortenerFiles.add(file to dest)
                }
                else -> {
                    cacheFail(file.cacheKey)
                }
            }
        }

        if (shortenerFiles.isNotEmpty() && !tw4aShortenerRefused()) {
            tw4aSetSystem24Hour()
            val probeEntry = shortenerFiles.minByOrNull { it.first.priority } ?: return emittedAny
            val probeFile = probeEntry.first
            val probeDest = probeEntry.second
            val seenDomains = java.util.Collections.synchronizedSet(HashSet<String>())
            tw4aShortenerDomainKey(probeDest)?.let { seenDomains.add(it) }
            val nextProvider: suspend () -> String? = {
                val info = Tw4aArchive.resolveRedirect(probeFile.redirectPath)
                val dest = info?.destination.orEmpty()
                when {
                    dest.isBlank() -> null
                    isDirectFileUrl(dest) -> null
                    else -> if (tw4aShortenerDomainKey(dest)?.let { seenDomains.add(it) } == true) dest else null
                }
            }
            val session = showTw4aShortenerSessionAndWait(listOf(probeDest), nextProvider)
            if (session.landings.isEmpty()) {
                markTw4aShortenerRefused()
            } else {
                val landing = session.landings.values.first()
                for (file in toResolve) {
                    if (file.redirectPath == probeFile.redirectPath) {
                        resolvedCache[file.cacheKey] = landing
                    }
                }
                val remaining = toResolve
                    .filter { it.redirectPath != probeFile.redirectPath && resolvedCache[it.cacheKey] == null }
                    .map { it.redirectPath }
                    .distinct()
                if (remaining.isNotEmpty()) {
                    val reinfos = Tw4aArchive.resolveRedirects(remaining)
                    for (file in toResolve) {
                        if (resolvedCache[file.cacheKey] != null) continue
                        val info = reinfos[file.redirectPath] ?: continue
                        if (isDirectFileUrl(info.destination)) {
                            resolvedCache[file.cacheKey] = info.destination
                        }
                    }
                }
            }
        }

        val resolvable = mutableListOf<Pair<PendingFile, String>>()
        for (file in toResolve) {
            val realUrl = resolvedCache[file.cacheKey]
            if (!realUrl.isNullOrBlank()) {
                resolvable.add(file to realUrl)
            }
        }

        if (resolvable.isEmpty()) return emittedAny

        val deadline = System.currentTimeMillis() + 240_000L
        val emittedFlag = java.util.concurrent.atomic.AtomicBoolean(emittedAny)
        coroutineScope {
            resolvable.sortedBy { it.first.priority }.map { (file, realUrl) ->
                async {
                    if (System.currentTimeMillis() > deadline) return@async
                    try {
                        val ok = resolveFile(file, realUrl, callback)
                        if (ok) emittedFlag.set(true)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.d(TAG, "file resolution failed (${file.host}): ${e.message}")
                    }
                }
            }.forEach { runCatching { it.await() } }
        }
        return emittedFlag.get()
    }

    private suspend fun emitHostLinks(
        hostUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cached = cachedLinksFor(hostUrl)
        if (cached != null) {
            for (l in cached) {
                callback(
                    newExtractorLink("ToonWorld4All", l.name, l.url, ExtractorLinkType.VIDEO) {
                        this.quality = if (l.quality > 0) l.quality else com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                        this.referer = l.referer
                        if (l.headers != null) this.headers = l.headers
                    }
                )
            }
            return cached.isNotEmpty()
        }
        return false
    }

    private suspend fun resolveFile(
        file: PendingFile,
        realUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cachedLinks = cachedLinksFor(realUrl)
        if (cachedLinks != null) {
            for (l in cachedLinks) {
                callback(
                    newExtractorLink("ToonWorld4All", l.name, l.url, ExtractorLinkType.VIDEO) {
                        this.quality = if (l.quality > 0) l.quality else com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                        this.referer = l.referer
                        if (l.headers != null) this.headers = l.headers
                    }
                )
            }
            return cachedLinks.isNotEmpty()
        }

        val collected = mutableListOf<CachedLink>()
        val capturing: (ExtractorLink) -> Unit = { link ->
            collected.add(
                CachedLink(
                    name = link.name,
                    url = link.url,
                    quality = link.quality,
                    referer = link.referer ?: "",
                    headers = link.headers,
                )
            )
            callback(link)
        }
        val hostLower = file.host.lowercase()
        val directFile = realUrl.contains("flapdoodle") || realUrl.contains(".r2.dev") ||
                realUrl.contains("googleusercontent") ||
                Regex("""\.(mp4|mkv|m3u8)(\?|$)""").containsMatchIn(realUrl)
        when {
            directFile ->
                Tw4aExtractors.emitDirect(realUrl, file.quality, file.label, capturing)

            hostLower.contains("hubcloud") || realUrl.contains("hubcloud") ->
                Tw4aExtractors.extractHubCloud(realUrl, file.quality, file.label, capturing)

            hostLower.contains("gdflix") || realUrl.contains("gdflix") ->
                Tw4aExtractors.extractGdflix(realUrl, file.quality, file.label, capturing)

            hostLower.contains("filepress") || realUrl.contains("filepress") ||
                    realUrl.contains("filebee") || realUrl.contains("flapdoodle") ->
                Tw4aExtractors.extractFilepress(realUrl, file.quality, file.label, capturing)

            hostLower.contains("mega") || realUrl.contains("mega.nz") || realUrl.contains("mega.co.nz") ->
                Tw4aExtractors.emitMega(realUrl, file.quality, file.label, capturing)

            else ->
                Tw4aExtractors.emitDirect(realUrl, file.quality, file.label, capturing)
        }
        if (collected.isNotEmpty()) {
            linkCache[realUrl] = collected.toList()
            linkCacheTimes[realUrl] = System.currentTimeMillis()
            return true
        }
        cacheFail(file.cacheKey)
        return false
    }
}
