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
import org.jsoup.nodes.Element
import java.net.URLEncoder

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
        "$mainUrl/category/dual-audio/" to "Dual Audio",
        "$mainUrl/category/web-series/" to "Web Series",
    )

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private val internalDomains = listOf(
        "kmmovies", "wp-", "w.org", "cloudflare", "googleapi",
        "googletagmanager", "font-awesome", "gmpg", "jquery", "bootstrap",
    )

    private fun extractQuality(text: String): Pair<String, Int> {
        return when {
            Regex("2160p|4K|4k", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "2160p" to 2160
            Regex("1080p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "1080p" to 1080
            Regex("720p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "720p" to 720
            Regex("480p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "480p" to 480
            else -> "Unknown" to Qualities.Unknown.value
        }
    }

    private fun extractAudio(text: String): String {
        val m = Regex("\\{([^}]+)}").find(text)
        return m?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun extractSeason(text: String): Int? {
        val m = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisode(text: String): Int? {
        val m = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("Download ", ignoreCase = true)) t = t.substring(9).trim()
        val parenMatch = Regex("""^.+?\((?:Season\s*\d+|\d{4})\)""").find(t)
        if (parenMatch != null) return parenMatch.value.trim()
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

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("div.thumbnail a, div.image a, a.post-image, h2.title a, h3.title a")
            ?: selectFirst("a") ?: return null
        val url = a.attr("href").ifBlank { return null }
        val title = a.selectFirst("img")?.attr("alt")?.let { cleanTitle(it) }
            ?: a.attr("title").let { cleanTitle(it) }
            ?: a.text().let { cleanTitle(it) }
        if (title.isBlank()) return null
        val img = a.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        } ?: ""
        val isSeries = url.contains("series") || url.contains("season") ||
            title.contains("Season", true) || title.contains("Series", true)
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
            kmGet(pageUrl, headers = baseHeaders).document
        } catch (e: Exception) {
            Log.d("KMMovies", "getMainPage: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = doc.select("article, div.result-item, div.item").mapNotNull { it.toSearchResult() }
        val hasNext = doc.select("a.next.page-numbers, div.navigation a.nextpostslink").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = try {
            kmGet("$mainUrl/?s=$encoded", headers = baseHeaders).document
        } catch (e: Exception) {
            Log.d("KMMovies", "search: ${e.message}")
            return emptyList()
        }
        return doc.select("article, div.result-item, div.item").mapNotNull { it.toSearchResult() }
    }

    private data class DownloadBlock(
        val qualityName: String,
        val qualityInt: Int,
        val audio: String,
        val season: Int?,
        val links: List<String>,
    )

    private fun parseDownloadBlocks(doc: org.jsoup.nodes.Document): List<DownloadBlock> {
        val blocks = mutableListOf<DownloadBlock>()
        val entry = doc.selectFirst("div.entry-content, div.thecontent, div.post-content")
            ?: doc.body()
        val headings = entry.select("h2, h3, h4, h5, p strong, strong")
        for (heading in headings) {
            val text = heading.text().trim()
            if (!Regex("""\b(?:480p|720p|1080p|2160p|4K)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text)
            ) continue
            val (qName, qInt) = extractQuality(text)
            val audio = extractAudio(text)
            val season = extractSeason(text)
            val links = mutableListOf<String>()
            var sibling = heading.nextElementSibling()
            var attempts = 0
            while (sibling != null && attempts < 5) {
                for (a in sibling.select("a[href]")) {
                    val href = a.attr("href").trim()
                    if (href.isNotBlank() && href.startsWith("http") && !isInternalLink(href)) {
                        links.add(href)
                    }
                }
                if (links.isNotEmpty()) break
                sibling = sibling.nextElementSibling()
                attempts++
            }
            if (links.isNotEmpty()) {
                blocks.add(DownloadBlock(qName, qInt, audio, season, links))
            }
        }
        return blocks
    }

    private fun isInternalLink(href: String): Boolean = internalDomains.any { href.contains(it) }

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            kmGet(url, headers = baseHeaders).document
        } catch (e: Exception) {
            Log.d("KMMovies", "load: ${e.message}")
            return null
        }
        val h1 = doc.selectFirst("h1")?.text()?.let { cleanTitle(it) }
            ?: doc.selectFirst("title")?.text()?.let { cleanTitle(it) }
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img.wp-post-image")?.attr("src")
        val description = doc.selectFirst("div.entry-content p, div.thecontent p, div.synopsis")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val blocks = try {
            parseDownloadBlocks(doc)
        } catch (e: Exception) {
            Log.d("KMMovies", "parseDownloadBlocks: ${e.message}")
            emptyList()
        }
        val isSeries = url.contains("series") || url.contains("season") ||
            blocks.any { it.season != null } ||
            h1.contains("Season", true)

        if (isSeries) {
            return buildSeriesLoadResponse(h1, url, poster, description, blocks)
        }

        val links = blocks.flatMap { block ->
            block.links.map { link ->
                mapOf(
                    "q" to block.qualityName,
                    "qi" to block.qualityInt,
                    "a" to block.audio,
                    "u" to link,
                )
            }
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
        val bySeason = blocks.groupBy { it.season ?: 1 }.toSortedMap()
        if (bySeason.isEmpty()) return null

        val episodes = mutableListOf<Episode>()
        for ((season, seasonBlocks) in bySeason) {
            val allLinks = seasonBlocks.flatMap { block ->
                block.links.map { link ->
                    mapOf(
                        "q" to block.qualityName,
                        "qi" to block.qualityInt,
                        "a" to block.audio,
                        "u" to link,
                    )
                }
            }
            if (allLinks.isEmpty()) continue

            val hasEpisodeLabels = seasonBlocks.any { block ->
                block.links.any { link ->
                    Regex("""Episode\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(link)
                }
            }

            if (hasEpisodeLabels) {
                val episodeLinks = mutableMapOf<Int, MutableList<Map<String, Any>>>()
                for (block in seasonBlocks) {
                    for (link in block.links) {
                        val epNum = extractEpisode(link) ?: 1
                        episodeLinks.getOrPut(epNum) { mutableListOf() }.add(
                            mapOf(
                                "q" to block.qualityName,
                                "qi" to block.qualityInt,
                                "a" to block.audio,
                                "u" to link,
                            )
                        )
                    }
                }
                for ((epNum, links) in episodeLinks) {
                    val data = mapOf("t" to h1, "s" to season, "e" to epNum, "links" to links).toJson()
                    episodes.add(
                        newEpisode(data) {
                            this.name = "$h1 S${season}E$epNum"
                            this.season = season
                            this.episode = epNum
                        }
                    )
                }
            } else {
                val data = mapOf("t" to h1, "s" to season, "e" to 1, "links" to allLinks).toJson()
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
            Log.d("KMMovies", "loadLinks parse: ${e.message}")
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
                    val linkUrl = node["u"] as? String ?: return@async

                    val collected = mutableListOf<ExtractorLink>()
                    try {
                        loadExtractor(linkUrl, "$mainUrl/", subtitleCallback) { el -> collected.add(el) }
                    } catch (e: Exception) {
                        Log.d("KMMovies", "extractor $linkUrl: ${e.message}")
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
            }.awaitAll()
        }
        return true
    }
}
