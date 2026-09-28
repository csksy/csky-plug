package com.laddu100.eonmovies

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

private const val TAG = "EonMovies"

@JsonIgnoreProperties(ignoreUnknown = true)
data class LinkEntry(val label: String, val url: String)

internal data class Row(val name: String, val href: String) {
    val episodeRange: IntRange? get() = episodePattern.find(name)?.let {
        val start = it.groupValues[1].toIntOrNull() ?: return@let null
        val end = it.groupValues[2].toIntOrNull() ?: start
        if (end >= start) start..end else null
    }
    val seasonNumber: Int? get() = seasonPattern.find(name)?.groupValues?.get(1)?.toIntOrNull()
    val isEpisode get() = episodeRange != null
    val isSeason get() = seasonNumber != null
}

internal val episodePattern =
    Regex("""(?i)^(?:bonus\s+)?ep(?:isode)?\s*(\d{1,4})(?:\s*[-\u2013]\s*(\d{1,4}))?$""")
internal val seasonPattern =
    Regex("""(?i)^season\s*(\d{1,3})(?:\s*[-\u2013]\s*(\d{1,3}))?$""")
internal val titleSeasonPattern = Regex("""(?i)season\s*(\d{1,3})""")

internal fun parseRows(html: String): List<Row> {
    val doc = Jsoup.parse(html)
    val rows = mutableListOf<Row>()
    for (el in doc.select("div.dl-row")) {
        val name = el.attr("data-dlname").takeIf { it.isNotBlank() }
            ?: el.selectFirst(".dl-row-name")?.text()?.takeIf { it.isNotBlank() } ?: continue
        val href = el.selectFirst("a.dl-btn")?.attr("href")?.takeIf { it.contains("/dl/") }
            ?: continue
        rows.add(Row(name.trim(), href))
    }
    return rows
}

internal fun qualityOf(label: String): Int {
    Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)
        ?.toIntOrNull()?.let { return it }
    if (label.contains("4k", ignoreCase = true) || label.contains("2160")) return 2160
    return Qualities.Unknown.value
}

internal fun seasonOfTitle(title: String): Int? =
    titleSeasonPattern.find(title)?.groupValues?.get(1)?.toIntOrNull()

class EonMoviesProvider : MainAPI() {

    override var mainUrl = "https://new4.eonmovies.click"
    override var name = "EonMovies"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // a mirror chain can walk a links page, a hub and a generation page
    // before the first probe, dead hosts need room to time out
    override val loadLinksTimeoutMs: Long? = 5 * 60_000L

    override val mainPage = mainPageOf(
        "latest" to "Latest Updates",
        "anime" to "Anime",
        "28" to "Action",
        "35" to "Comedy",
        "18" to "Drama",
        "27" to "Horror",
        "878" to "Sci-Fi",
        "53" to "Thriller"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    private data class MirrorTarget(val source: String, val url: String, val qualityLabel: String?)

    private val genreIdsPattern = Regex(""""(\d+(?:,\d+)*)"\.split\(','\)""")
    private val genreNames = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
        80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
        14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
        9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction",
        10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western",
        10759 to "Action & Adventure", 10762 to "Kids", 10763 to "News",
        10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap",
        10767 to "Talk", 10768 to "War & Politics"
    )

    private fun listingUrl(data: String, page: Int): String {
        return when (data) {
            "latest" -> "$mainUrl/?action=&page=$page&name=&category=&genre="
            "anime" -> "$mainUrl/?category=anime&page=$page"
            else -> "$mainUrl/?genre=$data&page=$page"
        }
    }

    private suspend fun getPage(url: String): Document? {
        return try {
            app.get(url, headers = headers, timeout = 25_000L).document
        } catch (e: Exception) {
            Log.d(TAG, "page load failed: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = getPage(listingUrl(request.data, page)) ?: return null
        val items = doc.select("div.movie-card > a").mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
        val hasNext = doc.outerHtml().contains("page=${page + 1}&") && items.isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val url = "$mainUrl/?action=search&page=1&name=${URLEncoder.encode(query, "UTF-8")}"
        val doc = getPage(url) ?: return emptyList()
        return doc.select("div.movie-card > a").mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst(".card-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val year = selectFirst(".card-meta")?.text()?.let { Regex("""(\d{4})""").find(it)?.value }
            ?.toIntOrNull()
        val poster = selectFirst("img")?.attr("data-src")?.toAbsolute()
        val isSeries = selectFirst(".movie-badge-series") != null
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun String?.toAbsolute(): String? {
        if (this == null) return null
        return when {
            startsWith("http") -> this
            startsWith("/") -> "$mainUrl$this"
            else -> null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getPage(url) ?: return null
        val html = doc.outerHtml()

        val title = Regex("""<title>\s*(.*?)\s*-\s*EonMovies\s*</title>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("title")?.text()?.substringBefore("- EonMovies")?.trim()
            ?: return null
        val year = doc.selectFirst("#movieYearInfo")?.text()?.trim()?.toIntOrNull()
        val poster = doc.selectFirst("img.movie-poster")?.attr("src")?.toAbsolute()
        val plot = doc.selectFirst("p.overview-text")?.text()?.trim()
        val isSeries = doc.select(".meta-pill").any { it.text().trim().equals("Series", true) } ||
            doc.selectFirst(".movie-badge-series") != null
        val genres = genreIdsPattern.find(html)?.groupValues?.get(1)?.split(",")
            ?.mapNotNull { genreNames[it.trim().toIntOrNull()] }.orEmpty()

        val rows = parseRows(html)
        if (rows.isEmpty()) return null

        val titleSeason = seasonOfTitle(title)
        val episodes = mutableListOf<Episode>()
        val episodeRowCount = rows.count { it.isEpisode }

        // numbered episode rows win even when an unnumbered extra like a bonus
        // clip rides along, the straggler keeps its name and gets a tail number
        if (episodeRowCount > rows.size / 2 && rows.none { it.isSeason }) {
            var next = (rows.maxOf { it.episodeRange?.first ?: 0 }) + 1
            for (row in rows) {
                val number = row.episodeRange?.first ?: next++
                episodes.add(
                    newEpisode(listOf(LinkEntry(row.name, row.href)).toJson()) {
                        this.name = row.name
                        this.season = titleSeason ?: row.seasonNumber ?: 1
                        this.episode = number
                    }
                )
            }
        } else if (rows.all { it.isSeason }) {
            val seasonRows = coroutineScope {
                rows.map { row ->
                    async {
                        val season = row.seasonNumber ?: 1
                        buildSeasonEpisodes(season, loadHubRows(row.href))
                    }
                }.awaitAll()
            }
            episodes.addAll(seasonRows.flatten())
        }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
            }
        }

        // only quality rows left; a series in this shape is sold as a whole
        // season pack so it stays a series with a single pack entry
        return if (isSeries) {
            newTvSeriesLoadResponse(
                title, url, TvType.TvSeries,
                listOf(
                    newEpisode(rows.toLinkData()) {
                        this.name = if (titleSeason != null) "Season $titleSeason Pack" else "Full Season Pack"
                        this.season = titleSeason ?: 1
                        this.episode = 1
                    }
                )
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, rows.toLinkData()) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
            }
        }
    }

    private fun List<Row>.toLinkData(): String = map { LinkEntry(it.name, it.href) }.toJson()

    // a season row lands on a hub that either lists whole season packs by
    // quality or the episodes themselves, both shapes end up as episodes
    private suspend fun buildSeasonEpisodes(season: Int, hubRows: List<Row>): List<Episode> {
        if (hubRows.isEmpty()) return emptyList()
        if (hubRows.count { it.isEpisode } > hubRows.size / 2) {
            var next = (hubRows.maxOf { it.episodeRange?.first ?: 0 }) + 1
            return hubRows.map { row ->
                val number = row.episodeRange?.first ?: next++
                newEpisode(listOf(LinkEntry(row.name, row.href)).toJson()) {
                    this.name = row.name
                    this.season = season
                    this.episode = number
                }
            }
        }
        return listOf(
            newEpisode(hubRows.toLinkData()) {
                this.name = "Season $season Pack"
                this.season = season
                this.episode = 1
            }
        )
    }

    private suspend fun loadHubRows(href: String): List<Row> {
        val location = redirectOf(if (href.startsWith("http")) href else "$mainUrl$href")
            ?: return emptyList()
        if (!location.contains("/page/")) return emptyList()
        val hubUrl = if (location.startsWith("http")) location else "$mainUrl$location"
        val html = try {
            app.get(hubUrl, headers = headers, timeout = 25_000L).text
        } catch (e: Exception) {
            Log.d(TAG, "hub load failed: ${e.message}")
            return emptyList()
        }
        return parseRows(html)
    }

    private suspend fun redirectOf(url: String): String? {
        return try {
            val res = app.get(url, headers = headers, allowRedirects = false, timeout = 20_000L)
            if (res.code in 300..399) res.headers["location"] else null
        } catch (e: Exception) {
            Log.d(TAG, "redirect check failed: ${e.message}")
            null
        }
    }

    // a /dl link either hops to a mirror page with one button per cloud,
    // to a quality hub, or straight out to the host itself
    private suspend fun expandEntry(href: String, depth: Int, qualityLabel: String?): List<MirrorTarget> {
        if (depth > 2) return emptyList()
        val location = redirectOf(if (href.startsWith("http")) href else "$mainUrl$href")
            ?: return emptyList()

        if (location.contains("/links/")) {
            val mirrorUrl = if (location.startsWith("http")) location else "$mainUrl$location"
            val doc = getPage(mirrorUrl) ?: return emptyList()
            return coroutineScope {
                doc.select("a.dl-btn-host").map { btn ->
                    async {
                        val label = btn.selectFirst("span")?.text()?.trim()
                            ?: return@async emptyList()
                        val btnHref = btn.attr("href").takeIf { it.contains("/dl/") }
                            ?: return@async emptyList()
                        val sub = redirectOf(
                            if (btnHref.startsWith("http")) btnHref else "$mainUrl$btnHref"
                        ) ?: return@async emptyList()
                        if (sub.startsWith("http")) {
                            listOf(MirrorTarget(label, sub, qualityLabel))
                        } else {
                            expandEntry(btnHref, depth + 1, qualityLabel)
                        }
                    }
                }.awaitAll().flatten()
            }
        }

        if (location.contains("/page/")) {
            val hubUrl = if (location.startsWith("http")) location else "$mainUrl$location"
            val hubRows = loadRows(hubUrl)
            return coroutineScope {
                hubRows.map { row ->
                    async { expandEntry(row.href, depth + 1, row.name) }
                }.awaitAll().flatten()
            }
        }

        if (location.startsWith("http")) {
            return listOf(MirrorTarget(EonSources.sourceName(location), location, qualityLabel))
        }
        return emptyList()
    }

    private suspend fun loadRows(url: String): List<Row> {
        val html = try {
            app.get(url, headers = headers, timeout = 25_000L).text
        } catch (e: Exception) {
            Log.d(TAG, "row load failed: ${e.message}")
            return emptyList()
        }
        return parseRows(html)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val entries = try {
            parseJson<List<LinkEntry>>(data)
        } catch (e: Exception) {
            Log.d(TAG, "link data parse failed: ${e.message}")
            return false
        }
        if (entries.isEmpty()) return false

        val emitted = java.util.Collections.synchronizedSet(HashSet<String>())
        val results = coroutineScope {
            entries.map { entry ->
                async {
                    var found = false
                    val targets = expandEntry(entry.url, 0, null)
                    val streams = coroutineScope {
                        targets.map { target ->
                            async { EonSources.resolve(target.url).map { it to target } }
                        }.awaitAll().flatten()
                    }
                    for ((stream, target) in streams) {
                        if (!emitted.add(stream.url)) continue
                        val label = listOfNotNull(
                            entry.label,
                            target.qualityLabel,
                            target.source,
                            stream.subLabel
                        ).joinToString(" · ")
                        callback.invoke(
                            newExtractorLink(
                                name,
                                label,
                                stream.url,
                                type = stream.type
                            ) {
                                this.headers = stream.headers
                                this.quality = qualityOf(target.qualityLabel ?: entry.label)
                            }
                        )
                        found = true
                    }
                    found
                }
            }.awaitAll()
        }
        return results.any { it }
    }
}
