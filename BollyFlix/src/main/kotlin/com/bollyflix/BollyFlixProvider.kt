package com.bollyflix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
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

    // Extract audio tag content like {Hindi-English} from heading text.
    // Uses string ops: Android's ICU regex rejects a literal unescaped }.
    private fun extractAudio(text: String): String {
        val start = text.indexOf('{')
        if (start < 0) return ""
        val end = text.indexOf('}', start)
        if (end < 0) return ""
        return text.substring(start + 1, end).trim()
    }

    // Extract season number from heading text like "Stranger Things (Season 1) ..."
    private fun extractSeason(text: String): Int? {
        val m = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1)?.toIntOrNull()
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

    // One parsed download block: a quality heading with its sibling <p> of links.
    private data class DownloadBlock(
        val qualityName: String,
        val qualityInt: Int,
        val audio: String,
        val season: Int?,
        val googleDrive: String?,
        val linksmod: String?,
        val fxlinks: String?,
    )

    // Parse h4 (series) and h5 (movie) headings that contain quality + size bracket.
    // Series headings carry a season number, e.g. "Stranger Things (Season 1) ... 480p [..]".
    private fun parseDownloadBlocks(doc: org.jsoup.nodes.Document): List<DownloadBlock> {
        val blocks = mutableListOf<DownloadBlock>()
        val headings = doc.select("h4, h5")
        for (heading in headings) {
            val text = heading.text()
            if (!Regex("""\b(?:480p|720p|1080p|2160p|4K)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text)
            ) continue
            if (!text.contains("[")) continue
            val (qName, qInt) = extractQuality(text)
            val audio = extractAudio(text)
            val season = extractSeason(text)
            var googleDrive: String? = null
            var linksmod: String? = null
            var fxlinks: String? = null
            var sibling = heading.nextElementSibling()
            // Walk consecutive <p> siblings collecting download links.
            // Movie pages use <a class="dl">, series pages use
            // <a class="maxbutton maxbutton-download-links">; selecting by href
            // pattern works for both instead of relying on a class name.
            while (sibling != null && sibling.tagName() == "p") {
                sibling.select("a[href]").forEach { a ->
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
                blocks.add(DownloadBlock(qName, qInt, audio, season, googleDrive, linksmod, fxlinks))
            }
        }
        return blocks
    }

    // Fetch a fxlinks aggregator page and map episode number -> fastdlserver URL.
    // A "Season Zip" entry (no episode number) is returned under key 0.
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
                if (epNum != null) {
                    map[epNum] = href
                } else if (label.contains("Season Zip", ignoreCase = true) ||
                    label.contains("Zip", ignoreCase = true)
                ) {
                    map[0] = href
                }
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
            val storyHeading = doc.select("h2").firstOrNull {
                it.text().contains("Storyline", ignoreCase = true)
            }
            storyHeading?.nextElementSibling()?.text()
        } ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val blocks = try {
            parseDownloadBlocks(doc)
        } catch (e: Exception) {
            Log.d("BollyFlix", "parseDownloadBlocks error: ${e.message}")
            emptyList()
        }
        val isSeries = url.contains("web-series") ||
            url.contains("season") ||
            blocks.any { it.fxlinks != null }

        if (isSeries) {
            return buildSeriesLoadResponse(h1, url, poster, description, blocks)
        }

        // Movie: encode download blocks into data for loadLinks.
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
        }
    }

    private suspend fun buildSeriesLoadResponse(
        h1: String,
        url: String,
        poster: String?,
        description: String?,
        blocks: List<DownloadBlock>,
    ): LoadResponse? {
        // Group blocks by season. Headings without a season number default to 1.
        val bySeason = blocks.groupBy { it.season ?: 1 }.toSortedMap()
        if (bySeason.isEmpty()) return null

        // Fetch every fxlinks page in parallel (one per quality per season).
        // Key: (season, qualityName, audio) so we can merge episode maps across qualities.
        data class FxKey(val season: Int, val qName: String, val qInt: Int, val audio: String)
        val fxBlocks = blocks.filter { it.fxlinks != null }
        val fxResults: Map<FxKey, Map<Int, String>> = coroutineScope {
            fxBlocks.map { block ->
                val key = FxKey(block.season ?: 1, block.qualityName, block.qualityInt, block.audio)
                async { key to fetchFxlinksEpisodes(block.fxlinks!!) }
            }.awaitAll().toMap()
        }

        val episodes = mutableListOf<Episode>()
        for ((season, seasonBlocks) in bySeason) {
            // Determine episode count for this season across all qualities.
            val maxEp = fxResults.entries
                .filter { it.key.season == season }
                .flatMap { it.value.keys }
                .filter { it > 0 }
                .maxOrNull() ?: 0

            if (maxEp > 0) {
                for (epNum in 1..maxEp) {
                    val links = fxResults.entries
                        .filter { it.key.season == season && it.value.containsKey(epNum) }
                        .map { (key, epMap) ->
                            mapOf(
                                "q" to key.qName,
                                "qi" to key.qInt,
                                "a" to key.audio,
                                "u" to epMap[epNum]!!,
                            )
                        }
                    if (links.isEmpty()) continue
                    val data = mapOf(
                        "t" to h1, "s" to season, "e" to epNum, "links" to links
                    ).toJson()
                    episodes.add(
                        newEpisode(data) {
                            this.name = "$h1 S${season}E$epNum"
                            this.season = season
                            this.episode = epNum
                        }
                    )
                }
            } else {
                // No episode labels: treat each quality block as a season-pack episode.
                val links = seasonBlocks.mapNotNull { block ->
                    val u = block.googleDrive ?: block.fxlinks ?: return@mapNotNull null
                    mapOf(
                        "q" to block.qualityName,
                        "qi" to block.qualityInt,
                        "a" to block.audio,
                        "u" to u,
                    )
                }
                if (links.isEmpty()) continue
                val data = mapOf(
                    "t" to h1, "s" to season, "e" to 1, "links" to links
                ).toJson()
                episodes.add(
                    newEpisode(data) {
                        this.name = "$h1 Season $season"
                        this.season = season
                        this.episode = 1
                    }
                )
            }
        }

        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(h1, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
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
                    // Movie links use "g" (google drive) + "m" (linksmod).
                    // Series links use "u" (fastdlserver URL per episode).
                    val gUrl = node["g"] as? String ?: ""
                    val uUrl = node["u"] as? String ?: ""
                    val fastUrl = if (gUrl.isNotBlank()) gUrl else uUrl
                    if (fastUrl.isNotBlank() && fastUrl.contains("fastdlserver")) {
                        // fastdlserver -> gdflix.* -> multiple direct sources.
                        // loadExtractor dispatches to the registered FastDlServer extractor
                        // which follows the redirect to a GDFlix page and surfaces every
                        // download button (Cloud R2, Instant, Drivebot, Fast Cloud, GoFile).
                        val collected = mutableListOf<ExtractorLink>()
                        try {
                            loadExtractor(fastUrl, "", subtitleCallback) { el -> collected.add(el) }
                        } catch (e: Exception) {
                            Log.d("BollyFlix", "fastdlserver link error: ${e.message}")
                        }
                        for (el in collected) relabelLink(el, audioLabel, qInt, callback)
                    }
                    // linksmod aggregator: fetch page, extract file host links, use built-in extractors.
                    val linksmodUrl = node["m"] as? String ?: ""
                    if (linksmodUrl.isNotBlank()) {
                        try {
                            val aggDoc = app.get(linksmodUrl, headers = headers, timeout = 30_000L).document
                            for (a in aggDoc.select("a[href]")) {
                                val href = a.attr("href")
                                if (!isFileHost(href)) continue
                                val collected = mutableListOf<ExtractorLink>()
                                loadExtractor(href, linksmodUrl, subtitleCallback) { el -> collected.add(el) }
                                for (el in collected) relabelLink(el, " $qName$audioLabel", qInt, callback)
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
            "gdflix", "fastdlserver", "send.cm", "streamtape",
            "doodstream", "voe", "vidoza", "upstream", "streamlare", "filemoon",
        )
        return hosts.any { url.contains(it, ignoreCase = true) }
    }
}
