package com.bollyflix

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
import org.jsoup.nodes.Element

class BollyFlixProvider : MainAPI() {
    override var mainUrl = "https://bollyflix.free"
    override var name = "BollyFlix"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/web-series/" to "Web Series",
        "$mainUrl/movies/bollywood/" to "Bollywood",
        "$mainUrl/movies/hollywood/" to "Hollywood",
        "$mainUrl/movies/dual-audio-movies/" to "Dual Audio",
        "$mainUrl/movies/multi-audio/" to "Multi Audio",
        "$mainUrl/movies/south-hindi-dubbed/" to "South Hindi Dubbed",
        "$mainUrl/movies/hindi-dubbed-movies-480p-720p/" to "Hindi Dubbed",
        "$mainUrl/movies/punjabi/" to "Punjabi",
        "$mainUrl/anime/" to "Anime",
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
    )

    // Clean listing/h1 titles like "Download Name (2026) Dual Audio {Hindi-English} Movie 480p ..."
    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("Download ", ignoreCase = true)) t = t.substring(9).trim()
        // Keep up to and including (Year) or (Season N)
        val parenMatch = Regex("""^.+?\((?:Season\s*\d+|\d{4})\)""").find(t)
        if (parenMatch != null) return parenMatch.value.trim()
        // Fallback: cut at first quality keyword
        val cutIdx = Regex("""\s+(?:480p|720p|1080p|2160p|4K)\b""", RegexOption.IGNORE_CASE)
            .find(t)?.range?.first
        if (cutIdx != null) t = t.substring(0, cutIdx).trim()
        t = t.replace(
            Regex(
                """\s+(?:Dual\s+Audio|Multi\s+Audio|Hindi\s+Dubbed|Movie|Web\s+Series|WEB\s+Series|K-Drama|Korean\s+Series|Anime)$""",
                RegexOption.IGNORE_CASE
            ), ""
        ).trim()
        return t
    }

    private fun extractQuality(text: String): Pair<String, Int> {
        return when {
            Regex("2160p|4K", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "2160p" to 2160
            Regex("1080p\\s*HQ", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "1080p HQ" to 1080
            Regex("1080p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "1080p" to 1080
            Regex("720p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "720p" to 720
            Regex("480p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "480p" to 480
            Regex("360p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "360p" to 360
            else -> "Unknown" to Qualities.Unknown.value
        }
    }

    // Extract audio tag content like {Hindi-English} from heading text
    private fun extractAudio(text: String): String {
        val m = Regex("\\{([^}]+)}").find(text)
        return m?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("h2.title a") ?: selectFirst("h2 a") ?: selectFirst("a.post-image")
            ?: return null
        val url = a.attr("href").ifBlank { return null }
        val title = cleanTitle(a.attr("title").ifBlank { a.text() })
        if (title.isBlank()) return null
        val img = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val isSeries = url.contains("web-series") || url.contains("season")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = img
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = img
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = try {
            app.get(pageUrl, headers = headers, timeout = 30_000L).document
        } catch (e: Exception) {
            Log.d("BollyFlix", "getMainPage error: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = doc.select("article.latestPost").mapNotNull { it.toSearchResult() }
        val hasNext = doc.select("a.next.page-numbers, li.next a").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = try {
            app.get("$mainUrl/?s=$encoded", headers = headers, timeout = 30_000L).document
        } catch (e: Exception) {
            Log.d("BollyFlix", "search error: ${e.message}")
            return emptyList()
        }
        return doc.select("article.latestPost").mapNotNull { it.toSearchResult() }
    }

    // Parsed download block from an h4/h5 heading and its sibling <p> with <a class="dl"> links
    private data class DownloadBlock(
        val qualityName: String,
        val qualityInt: Int,
        val audio: String,
        val googleDrive: String?,
        val linksmod: String?,
        val fxlinks: String?,
    )

    // Parse h4 (series) and h5 (movie) headings that contain quality + size bracket
    private fun parseDownloadBlocks(doc: org.jsoup.nodes.Document): List<DownloadBlock> {
        val blocks = mutableListOf<DownloadBlock>()
        val headings = doc.select("h4, h5")
        for (heading in headings) {
            val text = heading.text()
            // Must contain a quality keyword and a size bracket to be a download heading
            if (!Regex("""\b(?:480p|720p|1080p|2160p|4K)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text)
            ) continue
            if (!text.contains("[")) continue
            val (qName, qInt) = extractQuality(text)
            val audio = extractAudio(text)
            var googleDrive: String? = null
            var linksmod: String? = null
            var fxlinks: String? = null
            var sibling = heading.nextElementSibling()
            // Walk consecutive <p> siblings collecting dl links
            while (sibling != null && sibling.tagName() == "p") {
                sibling.select("a.dl").forEach { a ->
                    val href = a.attr("href")
                    when {
                        href.contains("fastdlserver") -> googleDrive = href
                        href.contains("linksmod") -> linksmod = href
                        href.contains("fxlinks") -> fxlinks = href
                    }
                }
                sibling = sibling.nextElementSibling()
            }
            if (googleDrive != null || linksmod != null || fxlinks != null) {
                blocks.add(DownloadBlock(qName, qInt, audio, googleDrive, linksmod, fxlinks))
            }
        }
        return blocks
    }

    // Resolve a fastdlserver / gdflix URL to the direct R2 Cloudflare MKV link.
    // The fastdlserver URL 302-redirects to gdflix.dev -> new3.gdflix.io which serves
    // an HTML page containing the R2 direct link (works with byte-range seeking).
    private suspend fun resolveGDFlix(fastdlUrl: String): String? {
        return try {
            val resp = app.get(fastdlUrl, headers = headers, timeout = 30_000L)
            val html = resp.text
            // The R2 URL appears directly in page source; token is optional and not required
            Regex("""https://pub-[a-f0-9]+\.r2\.dev/[a-f0-9]+(?:\?token=\d+)?""")
                .find(html)?.value
        } catch (e: Exception) {
            Log.d("BollyFlix", "GDFlix resolve failed: ${e.message}")
            null
        }
    }

    // Fetch a fxlinks aggregator page and map episode number -> fastdlserver URL
    private suspend fun fetchFxlinksEpisodes(fxlinksUrl: String): Map<Int, String> {
        return try {
            val doc = app.get(fxlinksUrl, headers = headers, timeout = 30_000L).document
            val map = mutableMapOf<Int, String>()
            doc.select("a[href]").forEach { a ->
                val href = a.attr("href")
                if (!href.contains("fastdlserver")) return@forEach
                val label = a.text().trim()
                val epNum = Regex("""(?:Episode|Ep\.?|E)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(label)?.groupValues?.get(1)?.toIntOrNull()
                if (epNum != null) map[epNum] = href
            }
            map
        } catch (e: Exception) {
            Log.d("BollyFlix", "fxlinks fetch failed: ${e.message}")
            emptyMap()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            app.get(url, headers = headers, timeout = 30_000L).document
        } catch (e: Exception) {
            Log.d("BollyFlix", "load error: ${e.message}")
            return null
        }
        val h1 = doc.selectFirst("h1")?.text()?.let { cleanTitle(it) } ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val description = run {
            // Storyline section: h2 containing "Storyline" followed by a <p>
            val storyHeading = doc.select("h2").firstOrNull {
                it.text().contains("Storyline", ignoreCase = true)
            }
            storyHeading?.nextElementSibling()?.text()
        } ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val tags = doc.select("a[rel=tag]").map { it.text().trim() }.filter { it.isNotBlank() }
        val blocks = parseDownloadBlocks(doc)
        val isSeries = url.contains("web-series") ||
            url.contains("season") ||
            blocks.any { it.fxlinks != null }

        if (isSeries) {
            // Gather fxlinks URLs per quality, fetch episode lists in parallel
            val fxlinksBlocks = blocks.filter { it.fxlinks != null }
            val epMaps: Map<Int, Map<Int, String>> = coroutineScope {
                fxlinksBlocks.mapIndexed { idx, block ->
                    async { idx to fetchFxlinksEpisodes(block.fxlinks!!) }
                }.awaitAll().toMap()
            }
            // Determine total episode count across all qualities
            val maxEp = epMaps.values.flatMap { it.keys }.maxOrNull() ?: 0
            val episodes = if (maxEp > 0) {
                (1..maxEp).map { epNum ->
                    val links = fxlinksBlocks.mapIndexedNotNull { idx, block ->
                        val fastUrl = epMaps[idx]?.get(epNum) ?: return@mapIndexedNotNull null
                        mapOf(
                            "q" to block.qualityName,
                            "qi" to block.qualityInt,
                            "a" to block.audio,
                            "u" to fastUrl,
                        )
                    }
                    val data = mapOf(
                        "t" to h1,
                        "e" to epNum,
                        "links" to links,
                    ).toJson()
                    newEpisode(data) {
                        this.name = "$h1 - Episode $epNum"
                        this.episode = epNum
                    }
                }
            } else {
                // No episode labels found; treat as single complete-pack episode
                val links = blocks.mapNotNull { block ->
                    val u = block.googleDrive ?: block.fxlinks ?: return@mapNotNull null
                    mapOf(
                        "q" to block.qualityName,
                        "qi" to block.qualityInt,
                        "a" to block.audio,
                        "u" to u,
                    )
                }
                val data = mapOf(
                    "t" to h1,
                    "e" to 1,
                    "links" to links,
                ).toJson()
                listOf(
                    newEpisode(data) {
                        this.name = h1
                        this.episode = 1
                    }
                )
            }
            return newTvSeriesLoadResponse(h1, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }

        // Movie: encode download blocks into data for loadLinks
        val links = blocks.map { block ->
            mapOf(
                "q" to block.qualityName,
                "qi" to block.qualityInt,
                "a" to block.audio,
                "g" to (block.googleDrive ?: ""),
                "m" to (block.linksmod ?: ""),
            )
        }
        val data = mapOf("t" to h1, "u" to url, "links" to links).toJson()
        return newMovieLoadResponse(h1, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsed = try {
            parseJson<MutableMap<String, Any?>>(data)
        } catch (e: Exception) {
            Log.d("BollyFlix", "loadLinks parse error: ${e.message}")
            return false
        }
        @Suppress("UNCHECKED_CAST")
        val links = (parsed["links"] as? List<*>) ?: return false

        coroutineScope {
            links.map { linkRaw ->
                async {
                    val node = linkRaw as? Map<*, *> ?: return@async
                    val qName = node["q"] as? String ?: "Unknown"
                    val qInt = (node["qi"] as? Number)?.toInt() ?: Qualities.Unknown.value
                    val audio = node["a"] as? String ?: ""
                    val audioLabel = if (audio.isNotBlank()) " {$audio}" else ""
                    // Google Drive (fastdlserver -> GDFlix -> R2 direct MKV)
                    // Movie links use "g", series links use "u"
                    val gUrl = node["g"] as? String ?: ""
                    val uUrl = node["u"] as? String ?: ""
                    val fastUrl = if (gUrl.isNotBlank()) gUrl else uUrl
                    if (fastUrl.isNotBlank() && fastUrl.contains("fastdlserver")) {
                        val r2 = resolveGDFlix(fastUrl)
                        if (r2 != null) {
                            callback(
                                newExtractorLink(
                                    source = "BollyFlix",
                                    name = "Google Drive $qName$audioLabel",
                                    url = r2,
                                    type = ExtractorLinkType.VIDEO,
                                ) {
                                    this.quality = qInt
                                }
                            )
                        }
                    }
                    // linksmod aggregator: fetch page, extract file host links, use built-in extractors
                    val linksmodUrl = node["m"] as? String ?: ""
                    if (linksmodUrl.isNotBlank()) {
                        try {
                            val aggDoc = app.get(linksmodUrl, headers = headers, timeout = 30_000L).document
                            for (a in aggDoc.select("a[href]")) {
                                val href = a.attr("href")
                                if (!isFileHost(href)) continue
                                // Collect extracted links then re-label with quality + audio,
                                // because newExtractorLink is suspend and can't run inside
                                // the non-suspend callback that loadExtractor expects.
                                val collected = mutableListOf<ExtractorLink>()
                                loadExtractor(href, linksmodUrl, subtitleCallback) { el ->
                                    collected.add(el)
                                }
                                for (el in collected) {
                                    callback(
                                        newExtractorLink(
                                            source = el.source,
                                            name = "${el.name} $qName$audioLabel".trim(),
                                            url = el.url,
                                            type = if (el.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                        ) {
                                            this.referer = el.referer
                                            this.headers = el.headers
                                            this.quality = if (el.quality <= 0) qInt else el.quality
                                        }
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("BollyFlix", "linksmod error: ${e.message}")
                        }
                    }
                }
            }.awaitAll()
        }
        return true
    }

    private fun isFileHost(url: String): Boolean {
        val hosts = listOf(
            "gofile", "megaup", "mixdrop", "1fichier", "multiup", "mediafire",
            "usersdrive", "clicknupload", "ddownload", "filelions", "uploadhaven",
            "turbobit", "rapidgator", "keep2share", "nitroflare", "katfile",
            "hubcloud", "filesgram", "drivebot", "fastcdn", "r2.dev",
            "gdflix", "fastdlserver", "drop", "uploadmb", "send.cm", "streamtape",
            "doodstream", "voe", "vidoza", "upstream", "streamlare", "filemoon",
        )
        return hosts.any { url.contains(it, ignoreCase = true) }
    }
}
