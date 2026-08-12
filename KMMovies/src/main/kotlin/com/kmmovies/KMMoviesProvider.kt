package com.kmmovies

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * KMMovies — kmmovies.online
 *
 * Site layout (verified live):
 *   Homepage / search / category  -> article.movie-card (h3.movie-title, img.poster,
 *                                   .meta-row first span = "Series" for TV, .badge-audio)
 *   Detail (#download-links)      -> a.dl-btn  (span.dl-res = quality, span.dl-size = size)
 *      Movies:  href = https://w3.magiclinks.lol/{id}-2/   (link-protected WP post)
 *      TV:      .season-block per season; Episode-Wise tab links:
 *               https://episodes.magiclinks.lol/series/{slug}-{quality}/
 *               (Combined / Zip tabs = whole-season packs -> intentionally skipped)
 *   Episodes page                 -> .ep-row  (span.ep-name "Episode N" + a.dl-btn ->
 *               https://w1.skydrop.sbs/download.php?id={token})
 *   Movie w3 page                 -> real links live in the WP REST API:
 *               GET /wp-json/wp/v2/posts?slug={id}-2  -> content.rendered contains
 *               "Google Photos Link: https://w1.skydrop.sbs/download.php?id=..." and
 *               "Google Drive Link: https://drive.google.com/file/d/{id}/view"
 *   skydrop                       -> GET https://w1.skydrop.sbs/api.php?id={token}
 *               -> {"success":true,"link":"https://video-downloads.googleusercontent.com/..."}
 *               direct MKV stream (multi-audio: ExoPlayer audio-track selector works natively)
 *
 * All primary links resolve to direct Google UserContent URLs the player can stream
 * without custom headers; Google Drive links are offered as secondary sources through
 * the built-in loadExtractor.
 */
class KMMoviesProvider : MainAPI() {
    override var mainUrl = "https://kmmovies.online"
    override var name = "KMMovies"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/movies/" to "Movies",
        "$mainUrl/category/tv-series/" to "TV Series",
        "$mainUrl/category/bollywood/" to "Bollywood",
        "$mainUrl/category/hollywood/" to "Hollywood",
        "$mainUrl/category/4k/" to "4K",
        "$mainUrl/category/south/" to "South",
        "$mainUrl/category/dual-audio/" to "Dual Audio",
        "$mainUrl/category/kdrama/" to "KDrama",
        "$mainUrl/category/60fps/" to "60FPS",
        "$mainUrl/category/anime/" to "Anime",
        "$mainUrl/category/bluray-remux/" to "BluRay Remux",
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private fun extractQuality(text: String): Pair<String, Int> = when {
        Regex("2160p|4K|4k", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "2160p" to Qualities.P2160.value
        Regex("1080p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "1080p" to Qualities.P1080.value
        Regex("720p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "720p" to Qualities.P720.value
        Regex("480p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "480p" to Qualities.P480.value
        else -> "Unknown" to Qualities.Unknown.value
    }

    // ------------------------------------------------------------------ cards

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank() || !href.contains(mainUrl)) return null
        val title = selectFirst("h3.movie-title")?.text()?.trim()
            ?: a.attr("aria-label")?.trim()
            ?: a.attr("title")?.trim()
            ?: return null
        val poster = selectFirst("img.poster, img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        } ?: ""
        val metaText = selectFirst(".meta-row")?.text() ?: ""
        val isSeries = metaText.contains("Series", true) ||
            selectFirst(".badge-episodes") != null ||
            Regex("""\bS\d{1,2}\b""", RegexOption.IGNORE_CASE).containsMatchIn(title) ||
            title.contains("Season", true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = try {
            kmCFGet(url, headers = headers, referer = "$mainUrl/").document
        } catch (e: Exception) {
            Log.d("KMMovies", "getMainPage: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = doc.select("article.movie-card").mapNotNull { it.toSearchResult() }
        val hasNext = doc.select("a.next.page-numbers").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            kmCFGet("$mainUrl/?s=$encoded", headers = headers, referer = "$mainUrl/").document
                .select("article.movie-card").mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            Log.d("KMMovies", "search: ${e.message}")
            emptyList()
        }
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            kmCFGet(url, headers = headers, referer = "$mainUrl/").document
        } catch (e: Exception) {
            Log.d("KMMovies", "load: ${e.message}")
            return null
        }

        val title = doc.selectFirst("h1.hero-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = doc.selectFirst("img.hero-poster")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""
        val plot = doc.selectFirst("div.description, div.movie-overview, div.synopsis")?.text()?.trim()
        val genres = doc.select("a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val year = Regex("""\b(20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val audio = doc.selectFirst(".badge-audio")?.attr("title")?.trim().orEmpty()

        val linksSection = doc.selectFirst("#download-links")
        val isTv = linksSection?.selectFirst(".download-category.tv-series, .season-block") != null
            || title.contains("Season", true)
            || Regex("""\bS\d{1,2}\b""", RegexOption.IGNORE_CASE).containsMatchIn(title)

        return if (isTv) {
            buildSeriesLoadResponse(title, url, poster, plot, genres, year, audio, linksSection)
        } else {
            buildMovieLoadResponse(title, url, poster, plot, genres, year, audio, linksSection)
        }
    }

    private suspend fun buildMovieLoadResponse(
        title: String,
        url: String,
        poster: String?,
        plot: String?,
        genres: List<String>,
        year: Int?,
        audio: String,
        linksSection: Element?,
    ): LoadResponse? {
        // Protected links (w3.magiclinks.lol/{id}-2/) — resolved via WP REST API in loadLinks.
        val links = linksSection?.select("a.dl-btn[href*='magiclinks.lol']")?.mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val qName = a.selectFirst(".dl-res")?.text()?.trim() ?: "Unknown"
            val size = a.selectFirst(".dl-size")?.text()?.trim() ?: ""
            KMMovieLink(href, qName, size, audio)
        }?.distinctBy { it.url } ?: emptyList()

        // Some movie pages expose direct skydrop links instead of protected ones.
        val directSkydrop = linksSection?.select("a.dl-btn[href*='skydrop.sbs']")
            ?.mapNotNull { it.attr("href").trim().takeIf { h -> h.isNotBlank() } }
            ?.distinct()
            ?: emptyList()

        if (links.isEmpty() && directSkydrop.isEmpty()) return null

        val data = mapOf(
            "t" to title,
            "links" to links,
            "skydrop" to directSkydrop,
        ).toJson()
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
        }
    }

    private suspend fun buildSeriesLoadResponse(
        title: String,
        url: String,
        poster: String?,
        plot: String?,
        genres: List<String>,
        year: Int?,
        audio: String,
        linksSection: Element?,
    ): LoadResponse? {
        val seasons = linksSection?.select("div.season-block") ?: emptyList()
        if (seasons.isEmpty()) return null

        val episodes = mutableListOf<Episode>()

        seasons.forEachIndexed { index, seasonBlock ->
            val header = seasonBlock.selectFirst(".season-block-title")?.text()?.trim() ?: ""
            val seasonNum = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)

            // Episode-Wise tab only (data-type="episodes-N"). Combined / Zip are
            // whole-season packs and are intentionally skipped.
            val episodeWise = seasonBlock.select("div.type-content[data-type^='episodes']")
            val qualityPages = episodeWise.select("a.dl-btn[href*='episodes.magiclinks.lol']")
                .mapNotNull { a ->
                    val href = a.attr("href").trim()
                    if (href.isBlank()) return@mapNotNull null
                    val qName = a.selectFirst(".dl-res")?.text()?.trim() ?: "Unknown"
                    val size = a.selectFirst(".dl-size")?.text()?.trim() ?: ""
                    KMQualityPage(href, qName, size)
                }.distinctBy { it.url }

            if (qualityPages.isEmpty()) {
                // Fallback: season with no episode-wise links -> single pack entry.
                val packLinks = seasonBlock.select("a.dl-btn[href*='magiclinks.lol']")
                    .mapNotNull { it.attr("href").trim().takeIf { h -> h.isNotBlank() } }
                    .distinct()
                if (packLinks.isEmpty()) return@forEachIndexed
                val data = mapOf(
                    "t" to title,
                    "s" to seasonNum,
                    "e" to 1,
                    "fallback" to packLinks,
                ).toJson()
                episodes.add(
                    newEpisode(data) {
                        this.name = "$title Season $seasonNum"
                        this.season = seasonNum
                        this.episode = 1
                        this.posterUrl = poster
                    }
                )
                return@forEachIndexed
            }

            // Episode count from the season header "(N eps)", else resolve from page.
            val epCount = Regex("""\((\d+)\s*eps?\)""", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)?.toIntOrNull()
                ?: qualityPages.firstOrNull()?.let { fetchEpCount(it.url) }
                ?: 8

            for (epNum in 1..epCount) {
                val data = mapOf(
                    "t" to title,
                    "s" to seasonNum,
                    "e" to epNum,
                    "pages" to qualityPages,
                ).toJson()
                episodes.add(
                    newEpisode(data) {
                        this.name = "Episode $epNum"
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = poster
                    }
                )
            }
        }

        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
        }
    }

    // ------------------------------------------------------------------ links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsed = try {
            parseJson<MutableMap<String, Any?>>(data)
        } catch (e: Exception) {
            Log.d("KMMovies", "loadLinks parse: ${e.message}")
            return false
        }

        val title = parsed["t"] as? String ?: ""

        // Whole-season pack links (fallback for seasons without episode-wise pages)
        val fallback = parsed["fallback"] as? List<*>
        val hasFallback = fallback != null && fallback.isNotEmpty()

        // TV episode: per-episode quality pages (each lists every episode of that quality)
        val pages = parsed["pages"] as? List<*>
        if (pages != null && pages.isNotEmpty()) {
            val episodeNum = (parsed["e"] as? Number)?.toInt() ?: 1
            val seasonNum = (parsed["s"] as? Number)?.toInt() ?: 1

            val found = coroutineScope {
                pages.map { pageRaw ->
                    async {
                        val node = pageRaw as? Map<*, *> ?: return@async false
                        val pageUrl = node["url"] as? String ?: return@async false
                        val qName = node["quality"] as? String ?: "Unknown"
                        val size = node["size"] as? String ?: ""

                        val epLinks = fetchEpisodesPage(pageUrl) ?: return@async false
                        val skydropUrl = epLinks[episodeNum] ?: return@async false
                        val direct = resolveSkydrop(skydropUrl) ?: return@async false

                        emitDirect(title, qName, size, direct, seasonNum, episodeNum, callback)
                        true
                    }
                }.awaitAll().any { it }
            }
            if (found) return true
            if (hasFallback) {
                return resolvePackLinks(fallback, title, subtitleCallback, callback)
            }
            return false
        }

        if (hasFallback) {
            return resolvePackLinks(fallback, title, subtitleCallback, callback)
        }

        // Movie: protected w3 links + optional direct skydrop links
        val links = parsed["links"] as? List<*> ?: emptyList<Any>()
        val skydrop = parsed["skydrop"] as? List<*> ?: emptyList<Any>()
        if (links.isEmpty() && skydrop.isEmpty()) return false

        val found = coroutineScope {
            links.map { linkRaw ->
                async {
                    val node = linkRaw as? Map<*, *> ?: return@async false
                    val magicUrl = node["url"] as? String ?: return@async false
                    val qName = node["quality"] as? String ?: "Unknown"
                    val size = node["size"] as? String ?: ""
                    val audio = node["audio"] as? String ?: ""

                    val resolved = resolveW3Movie(magicUrl)
                    if (resolved.isEmpty()) return@async false

                    emitMovieSources(resolved, qName, size, audio, subtitleCallback, callback)
                    true
                }
            }.awaitAll().any { it }
        }
        if (found) return true

        // Direct skydrop links on the detail page
        return coroutineScope {
            skydrop.map { raw ->
                async {
                    val url = (raw as? String) ?: return@async false
                    val direct = resolveSkydrop(url) ?: return@async false
                    callback(
                        newExtractorLink(
                            source = "KMMovies",
                            name = "KMMovies",
                            url = direct,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    true
                }
            }.awaitAll().any { it }
        }
    }

    private suspend fun resolvePackLinks(
        fallback: List<*>,
        title: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return coroutineScope {
            fallback.map { raw ->
                async {
                    val magicUrl = raw as? String ?: return@async false
                    val resolved = resolveW3Movie(magicUrl)
                    if (resolved.isEmpty()) return@async false
                    emitMovieSources(resolved, "Unknown", "", "", subtitleCallback, callback)
                    true
                }
            }.awaitAll().any { it }
        }
    }

    private suspend fun emitMovieSources(
        resolved: List<String>,
        qName: String,
        size: String,
        audio: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val (_, qInt) = extractQuality(qName)
        for (direct in resolved) {
            if (direct.contains("googleusercontent.com")) {
                // Direct Google UserContent URL — no headers needed
                val audioLabel = if (audio.isNotBlank()) " • $audio" else ""
                val sizeLabel = if (size.isNotBlank()) " ($size)" else ""
                val name = ("KMMovies $qName$sizeLabel$audioLabel").trim()
                callback(
                    newExtractorLink(
                        source = "KMMovies",
                        name = name,
                        url = direct,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = qInt
                    }
                )
            } else if (direct.startsWith("https://drive.google.com/")) {
                // Google Drive file view page — let the built-in extractor handle it
                try {
                    loadExtractor(direct, "$mainUrl/", subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.d("KMMovies", "gdrive extractor: ${e.message}")
                }
            }
        }
    }

    private suspend fun emitDirect(
        title: String,
        qName: String,
        size: String,
        direct: String,
        season: Int,
        episode: Int,
        callback: (ExtractorLink) -> Unit,
    ) {
        val (_, qInt) = extractQuality(qName)
        val name = "KMMovies S${season}E$episode $qName" +
            if (size.isNotBlank()) " ($size)" else ""
        callback(
            newExtractorLink(
                source = "KMMovies",
                name = name,
                url = direct,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.quality = qInt
            }
        )
    }

    // ------------------------------------------------------------------ resolvers

    /**
     * Fetch one episodes.magiclinks.lol quality page and return episodeNum -> skydrop URL.
     */
    private suspend fun fetchEpisodesPage(pageUrl: String): Map<Int, String>? {
        EpisodesCache.get(pageUrl)?.let { return it }
        val map = try {
            val doc = kmCFGet(pageUrl, headers = headers, referer = "$mainUrl/").document
            val out = mutableMapOf<Int, String>()
            doc.select("div.ep-row").forEachIndexed { idx, row ->
                val link = row.selectFirst("a.dl-btn[href*='skydrop']")?.attr("href")?.trim()
                    ?: return@forEachIndexed
                val name = row.selectFirst(".ep-name")?.text() ?: ""
                val epNum = Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (idx + 1)
                out[epNum] = link
            }
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            Log.d("KMMovies", "fetchEpisodesPage $pageUrl: ${e.message}")
            null
        }
        if (map != null) EpisodesCache.put(pageUrl, map)
        return map
    }

    private suspend fun fetchEpCount(pageUrl: String): Int =
        fetchEpisodesPage(pageUrl)?.size ?: 8

    /**
     * skydrop download.php?id={token} -> api.php -> direct googleusercontent video URL.
     * WordPress renders "--" in the encrypted token as an en-dash; the raw token is
     * tried first, then the en-dash -> "--" fix.
     */
    private suspend fun resolveSkydrop(downloadPhpUrl: String): String? {
        SkydropCache.get(downloadPhpUrl)?.let { return it }

        val token = Regex("""[?&]id=([^&\s]+)""").find(downloadPhpUrl)?.groupValues?.get(1)
            ?: return null

        val candidates = buildList {
            add(token)
            val fixed = token.replace("\u2013", "--")
            if (fixed != token) add(fixed)
        }

        var resolved: String? = null
        // api.php is a shared resolver: it answers {"busy":true} while it is
        // processing other files, and can transiently return garbage — retry a
        // few times before giving up on each candidate token.
        for (cand in candidates) {
            for (attempt in 1..5) {
                try {
                    val body = kmCFGet(
                        "https://w1.skydrop.sbs/api.php?id=$cand",
                        headers = headers,
                        referer = "https://w1.skydrop.sbs/",
                    ).text
                    val resp = parseJson<SkydropResponse>(body)
                    if (resp.success && !resp.link.isNullOrBlank()) {
                        resolved = resp.link
                        break
                    }
                    if ((resp.busy || body.isBlank()) && attempt < 5) {
                        delay(1500)
                        continue
                    }
                } catch (e: Exception) {
                    Log.d("KMMovies", "skydrop api: ${e.message}")
                    if (attempt < 5) {
                        delay(1500)
                        continue
                    }
                }
                break
            }
            if (resolved != null) break
        }
        if (resolved != null) SkydropCache.put(downloadPhpUrl, resolved)
        return resolved
    }

    /**
     * w3.magiclinks.lol/{id}-2/ is a link-protected WP post. The real links live in the
     * WP REST API; parse content.rendered for the skydrop / Google Drive links.
     */
    private suspend fun resolveW3Movie(magicUrl: String): List<String> {
        W3Cache.get(magicUrl)?.let { return it }

        val slug = Regex("""w3\.magiclinks\.lol/([^/]+)/?""").find(magicUrl)?.groupValues?.get(1)
            ?: return emptyList()

        val links = mutableListOf<String>()
        try {
            val body = kmCFGet(
                "https://w3.magiclinks.lol/wp-json/wp/v2/posts?slug=$slug",
                headers = headers,
                referer = "https://w3.magiclinks.lol/",
            ).text
            val posts = parseJson<List<Map<String, Any?>>>(body)
            val rendered = (posts.firstOrNull()?.get("content") as? Map<*, *>)
                ?.get("rendered") as? String ?: ""
            val text = Jsoup.parse(rendered).text()

            // Google Photos Link (skydrop) is the reliable direct source
            Regex("""Google Photos Link:\s*(https?://\S+)""")
                .find(text)?.groupValues?.get(1)?.let { skydrop ->
                    resolveSkydrop(skydrop)?.let { links.add(it) }
                }

            // Google Drive Link as a secondary source
            Regex("""Google Drive Link:\s*(https?://\S+)""")
                .find(text)?.groupValues?.get(1)?.let { links.add(it) }
        } catch (e: Exception) {
            Log.d("KMMovies", "resolveW3Movie $magicUrl: ${e.message}")
        }

        if (links.isNotEmpty()) W3Cache.put(magicUrl, links)
        return links.distinct()
    }

    // ------------------------------------------------------------------ data

    data class KMMovieLink(
        val url: String,
        val quality: String = "Unknown",
        val size: String = "",
        val audio: String = "",
    )

    data class KMQualityPage(
        val url: String,
        val quality: String,
        val size: String = "",
    )

    data class SkydropResponse(
        val success: Boolean = false,
        val busy: Boolean = false,
        val link: String? = null,
    )
}

// ---------------------------------------------------------------------- caches

private object EpisodesCache {
    private val map = ConcurrentHashMap<String, Pair<Long, Map<Int, String>>>()
    private const val TTL = 30 * 60_000L

    fun get(key: String): Map<Int, String>? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.first > TTL) {
            map.remove(key)
            return null
        }
        return entry.second
    }

    fun put(key: String, value: Map<Int, String>) {
        map[key] = System.currentTimeMillis() to value
    }
}

private object SkydropCache {
    private val map = ConcurrentHashMap<String, Pair<Long, String>>()
    private const val TTL = 60 * 60_000L

    fun get(key: String): String? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.first > TTL) {
            map.remove(key)
            return null
        }
        return entry.second
    }

    fun put(key: String, value: String) {
        map[key] = System.currentTimeMillis() to value
    }
}

private object W3Cache {
    private val map = ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private const val TTL = 60 * 60_000L

    fun get(key: String): List<String>? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.first > TTL) {
            map.remove(key)
            return null
        }
        return entry.second
    }

    fun put(key: String, value: List<String>) {
        map[key] = System.currentTimeMillis() to value
    }
}
