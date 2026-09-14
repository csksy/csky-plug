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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

@CloudstreamPlugin
class ToonWorld4AllPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ToonWorld4All())
    }
}

/**
 * CloudStream provider for https://toonworld4all.me/
 *
 * PIPELINE (verified against the live site, no guesswork):
 *
 *  1. toonworld4all.me (WordPress "Herald" theme)
 *     - mainPage: latest + tag/category archives (works via /page/N/ pagination)
 *     - search: /?s={query}
 *     - posts carry episode accordions ("Episode 01" -> mks_button) whose links
 *       point to archive.toonworld4all.me/episode/{slug}; movies carry a single
 *       "Get Download Links" button -> archive.toonworld4all.me/movie/{slug}
 *
 *  2. archive.toonworld4all.me (SSR React app)
 *     - every page embeds window.__PROPS__ JSON with the full data model:
 *       metadata (TMDB title/overview/poster) + encodes[] (resolution, codec,
 *       bit_depth, size, readable label) + files[] (host + /redirect/{hash} link)
 *     - hosts seen in production: HubCloud, Filepress, GDFlix, MEGA
 *
 *  3. /redirect/{hash}
 *     - server-side decrypts the AES-CTR blob, shortens the real URL through
 *       exe.io / cuty.io / gplinks.co and serves a chooser page whose
 *       window.__PROPS__ contains {"destination": "<shortener url>", ...}
 *     - the visible link.domain+hidden is RANDOM DISPLAY JUNK (Fh() in the
 *       bundle randomizes it) - only "destination" is real
 *
 *  4. Shortener chain (exe.io -> exeygo -> ... or cuty.io/cuttty.com or
 *     gplinks.co) requires a real browser (Cloudflare Turnstile) -> resolved
 *     with CloudStream's WebViewResolver + an auto-click script that presses
 *     the Continue/Verify/Get-Link buttons while the chain walks itself.
 *
 *  5. File hosts (real URL finally known):
 *     - HubCloud  hubcloud.ist/video|file/{id}  -> FSL/10Gbps/Mega/pixeldrain servers
 *     - GDFlix    gdflix.dev/file/{id} (302 -> new4.gdflix.io) -> Direct/Cloud/GDIndex/FastCloud/Instant
 *     - Filepress new4.filepress.baby/file/{id} (Cloudflare) -> drive links
 *     - MEGA      mega.nz/file/{id}#{key} - AES-encrypted files, NOT playable
 *       by any Android player -> skipped intentionally
 *
 * Because every shortener hop needs its own WebView session, files are
 * resolved with a small parallelism cap and links are emitted as they arrive.
 */
class ToonWorld4All : MainAPI() {

    override var mainUrl = "https://toonworld4all.me"
    override var name = "ToonWorld4All"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
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
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    // ------------------------------------------------------------------ //
    //  mainPage / search
    // ------------------------------------------------------------------ //

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val section = request.data.trimEnd('/')
        val url = when {
            page <= 1 -> if (section.isEmpty()) "$mainUrl/" else "$mainUrl/$section/"
            else -> if (section.isEmpty()) "$mainUrl/page/$page/" else "$mainUrl/$section/page/$page/"
        }
        val doc = app.get(url, headers = headers).document
        val results = parsePostList(doc)
        return newHomePageResponse(request.name, results, results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val url = if (page == 1) "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
            else "$mainUrl/page/$page/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val doc = try {
                app.get(url, headers = headers).document
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                break
            }
            val found = parsePostList(doc)
            if (found.isEmpty()) break
            results.addAll(found.filter { it.url !in results.map { r -> r.url } })
        }
        return results
    }

    /** Parse a WordPress listing page (search results / archive / home). */
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

    // ------------------------------------------------------------------ //
    //  load
    // ------------------------------------------------------------------ //

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: url.trimEnd('/').substringAfterLast('/')

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        // synopsis from the post body ("Synopsis:" paragraph) or og:description
        val bodyText = doc.selectFirst("div.entry-content")?.text().orEmpty()
        val synopsis = bodyText.substringAfter("Synopsis:", "").substringBefore("Screenshots")
            .trim().ifBlank {
                doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            }

        val tags = doc.select("div.meta-tags a").map { it.text().lowercase() }
        val isAnime = tags.any { it.contains("anime") } ||
                title.contains("anime", true) ||
                url.contains("anime")

        // ---- archive links ---- //
        val movieLinks = mutableListOf<String>()
        for (abs in doc.select("a[href]")) {
            val href = abs.attr("href")
            if (href.contains("archive.toonworld4all.me/movie/")) {
                movieLinks.add(href)
            }
        }

        // ---- movie ---- //
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

        // ---- series ---- //
        val accordionItems = doc.select("div.mks_accordion_item")
            .filter { it.selectFirst("a[href*='archive.toonworld4all.me/episode/']") != null }
        if (accordionItems.isNotEmpty()) {
            // Season markers appear as <p><strong>SEASON N ...</strong></p> (no links)
            // before the accordion group they label.
            val seasonMarkers = mutableListOf<Pair<Int, org.jsoup.nodes.Element>>()
            doc.select("div.entry-content p").forEach { p ->
                val txt = p.text().trim()
                val m = Regex("""(?i)^(?:COMPLETE\s+)?SEASON\s*(\d{1,2})\b""").find(txt)
                if (m != null && p.select("a").isEmpty()) {
                    seasonMarkers.add(m.groupValues[1].toInt() to p)
                }
            }
            val content = doc.selectFirst("div.entry-content") ?: doc.body()
            val ordered = content.allElements

            fun seasonOf(el: org.jsoup.nodes.Element): Int {
                var best = 1
                val elIdx = ordered.indexOf(el)
                for ((seasonNum, pEl) in seasonMarkers) {
                    val pIdx = ordered.indexOf(pEl)
                    if (pIdx in 0..elIdx) best = seasonNum
                }
                return best
            }

            val episodes = mutableListOf<Episode>()
            accordionItems.forEach { item ->
                val heading = item.selectFirst(".mks_accordion_heading")?.text()?.trim() ?: return@forEach
                val link = item.selectFirst("a[href*='archive.toonworld4all.me/episode/']")
                    ?: item.selectFirst("a[href]") ?: return@forEach
                val href = link.attr("href")
                if (!href.contains("archive.toonworld4all.me/episode/")) return@forEach

                val season = seasonOf(item)
                val epNum = Regex("""(?i)(?:episode|ep\.?|e)\s*(\d{1,3})""").find(heading)
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d{1,3})x(\d{1,3})""").find(href)?.groupValues?.get(2)?.toIntOrNull()
                    ?: episodes.size + 1
                val epName = heading.replace(
                    Regex("""(?i)^episode\s*\d{1,3}\s*[:\-–]?\s*"""), ""
                ).ifBlank { null }

                episodes.add(
                    newEpisode(href) {
                        this.season = season
                        this.episode = epNum
                        this.name = epName
                    }
                )
            }
            // dedupe by season+episode keeping first
            val seen = mutableSetOf<Pair<Int, Int>>()
            val finalEps = episodes.filter { seen.add(it.season ?: 1 to (it.episode ?: 0)) }

            return newTvSeriesLoadResponse(
                title, url,
                if (isAnime) TvType.Anime else TvType.Cartoon,
                finalEps
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.showStatus = null
            }
        }

        // Old-format post (direct gplinks/batch links, no archive system).
        throw ErrorLoadingException("This is an old-format post without archive links. Open it in a browser.")
    }


    // ------------------------------------------------------------------ //
    //  loadLinks
    // ------------------------------------------------------------------ //

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

        // watch-online streams (rare, but supported when present)
        archive.streams.forEach { stream ->
            stream.play?.takeIf { it.startsWith("http") }?.let { playUrl ->
                callback(
                    newExtractorLink(
                        "ToonWorld4All Watch",
                        "Watch Online",
                        playUrl,
                        ExtractorLinkType.VIDEO
                    )
                )
            }
        }

        val pending = mutableListOf<Triple<String, JSONObject, String>>() // host, file, encodeLabel
        archive.encodes.forEach { encode ->
            val codec = encode.optJSONObject("readable")?.optString("codec")
                ?: encode.optString("resolution")
            val size = encode.optJSONObject("readable")?.optString("size").orEmpty()
            val quality = Regex("""(\d{3,4})""").find(encode.optString("resolution"))
                ?.groupValues?.get(1)?.toIntOrNull()
            val label = buildString {
                append(codec)
                if (size.isNotBlank()) append(" [$size]")
            }
            encode.optJSONArray("files")?.let { files ->
                for (i in 0 until files.length()) {
                    val file = files.optJSONObject(i) ?: continue
                    val host = file.optString("host")
                    val link = file.optString("link")
                    if (link.isBlank()) continue
                    // MEGA files are AES-encrypted and cannot be played by Android
                    // players - skip them instead of emitting broken links.
                    if (host.contains("mega", true) || link.contains("mega", true)) continue
                    pending.add(Triple(host, file, label))
                    file.put("__quality", quality ?: 0)
                    file.put("__label", label)
                }
            }
        }

        if (pending.isEmpty()) return false

        // Every file needs its own WebView shortener pass - keep parallelism low
        // so low-RAM devices do not explode, but let links stream in as they land.
        val semaphore = Semaphore(3)
        val emitted = java.util.concurrent.atomic.AtomicBoolean(false)

        coroutineScope {
            pending.map { (host, file, label) ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            resolveFile(host, file, label, callback)
                            emitted.set(true)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.d(TAG, "file resolution failed (${host}): ${e.message}")
                        }
                    }
                }
            }
        }
        return emitted.get()
    }

    private suspend fun resolveFile(
        host: String,
        file: JSONObject,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val redirectPath = file.optString("link")
        val quality = file.optInt("__quality", 0)

        // 1. shortener destination from the redirect chooser page
        val destination = Tw4aArchive.resolveRedirectDestination(redirectPath)
        if (destination.isNullOrBlank()) {
            Log.d(TAG, "no destination for $redirectPath")
            return
        }

        // 2. walk the shortener chain in a WebView until a file host appears
        val realUrl = Tw4aShortener.resolve(destination)
        if (realUrl.isNullOrBlank()) {
            Log.d(TAG, "shortener bypass failed: $destination")
            return
        }
        Log.d(TAG, "resolved $host -> $realUrl")

        // 3. run the matching host extractor
        val hostLower = host.lowercase()
        when {
            hostLower.contains("hubcloud") || realUrl.contains("hubcloud") ->
                Tw4aExtractors.extractHubCloud(realUrl, quality, label, callback)

            hostLower.contains("gdflix") || realUrl.contains("gdflix") ->
                Tw4aExtractors.extractGdflix(realUrl, quality, label, callback)

            hostLower.contains("filepress") || realUrl.contains("filepress") ||
                    realUrl.contains("filebee") ->
                Tw4aExtractors.extractFilePress(realUrl, quality, label, callback)

            realUrl.contains(".mp4", true) || realUrl.contains(".mkv", true) ||
                    realUrl.contains("pixeldrain") || realUrl.contains("drive.google") ||
                    realUrl.contains("googleusercontent") ->
                Tw4aExtractors.emitDirect(realUrl, quality, label, callback)

            else ->
                Tw4aExtractors.emitDirect(realUrl, quality, label, callback)
        }
    }

    companion object {
        private const val TAG = "TW4A"
    }
}
