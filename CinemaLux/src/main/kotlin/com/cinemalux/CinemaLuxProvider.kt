package com.cinemalux

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

class CinemaLuxProvider : MainAPI() {
    override var mainUrl = "https://cinemalux.click"
    override var name = "CinemaLux"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/series/" to "TV Series",
        "$mainUrl/genre/action/" to "Action",
        "$mainUrl/genre/adventure/" to "Adventure",
        "$mainUrl/genre/comedy/" to "Comedy",
        "$mainUrl/genre/crime/" to "Crime",
        "$mainUrl/genre/drama/" to "Drama",
        "$mainUrl/genre/horror/" to "Horror",
        "$mainUrl/genre/sci-fi/" to "Sci-Fi",
        "$mainUrl/genre/thriller/" to "Thriller",
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
    )

    private fun extractQuality(text: String): Pair<String, Int> {
        return when {
            Regex("2160p|2160P|4K|4k", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "2160p" to 2160
            Regex("1080p|1080P", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "1080p" to 1080
            Regex("720p|720P", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "720p" to 720
            Regex("480p|480P", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "480p" to 480
            else -> "Unknown" to Qualities.Unknown.value
        }
    }

    // Extract season number from labels like "Season 01 - 720P WEB-DL"
    private fun extractSeason(text: String): Int? {
        val m = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    // Extract episode number from labels like "EPISODE - 01 (755.77 MB)"
    private fun extractEpisode(text: String): Int? {
        val m = Regex("""EPISODE\s*-?\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    // Extract audio tag from movie page text like "Languages: Hindi-English-Tamil-Telugu"
    private fun extractAudio(text: String): String {
        val m = Regex("""Languages:\s*(.+)""", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Dooplay search result: div.result-item > article > div.image div.thumbnail a
        val a = selectFirst("div.thumbnail a") ?: selectFirst("div.title a") ?: return null
        val url = a.attr("href").ifBlank { return null }
        val title = a.selectFirst("img")?.attr("alt")?.trim()
            ?: a.text().trim().ifBlank { return null }
        val img = a.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val isSeries = url.contains("/series/")
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
            Log.d("CinemaLux", "getMainPage error: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = doc.select("div.result-item").mapNotNull { it.toSearchResult() }
        val hasNext = doc.select("div.pagination a.next, a.next.page-numbers").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = try {
            app.get("$mainUrl/?s=$encoded", headers = headers, timeout = 30_000L).document
        } catch (e: Exception) {
            Log.d("CinemaLux", "search error: ${e.message}")
            return emptyList()
        }
        return doc.select("div.result-item").mapNotNull { it.toSearchResult() }
    }

    // A download button from the detail page: a tpi.li shortlink with a label.
    private data class DownloadButton(
        val tpiUrl: String,
        val label: String,
        val qualityName: String,
        val qualityInt: Int,
        val season: Int?,
    )

    // Parse div.custom-links for ep-simple-button anchors pointing to tpi.li.
    private fun parseDownloadButtons(doc: org.jsoup.nodes.Document): List<DownloadButton> {
        val buttons = mutableListOf<DownloadButton>()
        val container = doc.selectFirst("div.custom-links") ?: return buttons
        for (anchor in container.select("a.ep-simple-button")) {
            val href = anchor.attr("href").trim()
            if (!href.contains("tpi.li")) continue
            val label = anchor.select("span").text().ifBlank { anchor.text() }.trim()
            if (label.isBlank()) continue
            val (qName, qInt) = extractQuality(label)
            val season = extractSeason(label)
            buttons.add(DownloadButton(href, label, qName, qInt, season))
        }
        return buttons
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            app.get(url, headers = headers, timeout = 30_000L).document
        } catch (e: Exception) {
            Log.d("CinemaLux", "load error: ${e.message}")
            return null
        }
        val h1 = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val description = doc.selectFirst("div.s_box, div.synopsis, p:contains(Synopsis)")
            ?.text()?.trim()
            ?: doc.selectFirst("div.contenido p")?.text()?.trim()
        val buttons = parseDownloadButtons(doc)
        val isSeries = url.contains("/series/") || buttons.any { it.season != null }

        if (isSeries) {
            return buildSeriesLoadResponse(h1, url, poster, description, buttons)
        }

        // Movie: encode tpi.li links with quality labels into data.
        val audio = doc.selectFirst("div.custom-links p")?.text()?.let { extractAudio(it) } ?: ""
        val links = buttons.map { btn ->
            mapOf(
                "q" to btn.qualityName,
                "qi" to btn.qualityInt,
                "a" to audio,
                "u" to btn.tpiUrl,
            )
        }
        val data = mapOf("t" to h1, "links" to links).toJson()
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
        buttons: List<DownloadButton>,
    ): LoadResponse? {
        // Group buttons by season (default to 1 if no season in label).
        val bySeason = buttons.groupBy { it.season ?: 1 }.toSortedMap()
        if (bySeason.isEmpty()) return null

        // Resolve each tpi.li link to a linkstore.zip URL in parallel, then
        // fetch the linkstore page to get per-episode drive.linkstore.zip links.
        // Key: (season, qualityName) so we can merge across qualities per season.
        data class ResolvedKey(val season: Int, val qName: String, val qInt: Int)
        val resolved: Map<ResolvedKey, List<LinkstoreEntry>> = coroutineScope {
            buttons.map { btn ->
                val key = ResolvedKey(btn.season ?: 1, btn.qualityName, btn.qualityInt)
                async {
                    val linkstoreUrl = resolveTpiLi(btn.tpiUrl) ?: return@async key to emptyList<LinkstoreEntry>()
                    val entries = fetchLinkstoreLinks(linkstoreUrl)
                    key to entries
                }
            }.awaitAll().toMap()
        }

        val episodes = mutableListOf<Episode>()
        for ((season, _) in bySeason) {
            // Collect all episode entries for this season across qualities.
            val seasonResolved = resolved.entries.filter { it.key.season == season }
            // Determine max episode number across all qualities.
            val maxEp = seasonResolved
                .flatMap { it.value }
                .mapNotNull { extractEpisode(it.label) }
                .maxOrNull() ?: 0

            if (maxEp > 0) {
                for (epNum in 1..maxEp) {
                    val links = mutableListOf<Map<String, Any?>>()
                    for ((key, entries) in seasonResolved) {
                        val epEntry = entries.firstOrNull { extractEpisode(it.label) == epNum }
                        if (epEntry != null) {
                            links.add(mapOf(
                                "q" to key.qName,
                                "qi" to key.qInt,
                                "u" to epEntry.url,
                            ))
                        }
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
                // No episode labels: treat as a season-pack single episode.
                val links = seasonResolved.flatMap { (key, entries) ->
                    entries.map { entry ->
                        mapOf(
                            "q" to key.qName,
                            "qi" to key.qInt,
                            "u" to entry.url,
                        )
                    }
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
            Log.d("CinemaLux", "loadLinks parse error: ${e.message}")
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

                    if (linkUrl.contains("tpi.li")) {
                        // Movie path: tpi.li → linkstore.zip → direct file host links.
                        val linkstoreUrl = resolveTpiLi(linkUrl) ?: return@async
                        val entries = fetchLinkstoreLinks(linkstoreUrl)
                        for (entry in entries) {
                            val collected = mutableListOf<ExtractorLink>()
                            try {
                                loadExtractor(entry.url, linkstoreUrl, subtitleCallback) { el -> collected.add(el) }
                            } catch (e: Exception) {
                                Log.d("CinemaLux", "movie host ${entry.url}: ${e.message}")
                            }
                            for (el in collected) relabelLink(el, " $qName$audioLabel", qInt, callback)
                        }
                    } else {
                        // Series path: the URL is already a drive.linkstore.zip link.
                        // The registered LuxeDrive extractor follows the redirect to
                        // luxedrive.dad and extracts gdflix/R2/pixeldrain buttons.
                        val collected = mutableListOf<ExtractorLink>()
                        try {
                            loadExtractor(linkUrl, "", subtitleCallback) { el -> collected.add(el) }
                        } catch (e: Exception) {
                            Log.d("CinemaLux", "series link $linkUrl: ${e.message}")
                        }
                        for (el in collected) relabelLink(el, " $qName$audioLabel", qInt, callback)
                    }
                }
            }.awaitAll()
        }
        return true
    }
}
