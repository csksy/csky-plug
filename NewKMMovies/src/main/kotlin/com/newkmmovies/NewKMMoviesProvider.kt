package com.newkmmovies

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

class NewKMMoviesProvider : MainAPI() {
    override var mainUrl = "https://kmmovies.online"
    override var name = "NewKMMovies"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/bollywood/" to "Bollywood",
        "$mainUrl/category/hollywood/" to "Hollywood",
        "$mainUrl/category/4k/" to "4K",
        "$mainUrl/category/dual-audio/" to "Dual Audio",
        "$mainUrl/category/south/" to "South",
        "$mainUrl/category/web-series/" to "Web Series",
        "$mainUrl/category/anime/" to "Anime",
        "$mainUrl/category/kdrama/" to "KDrama",
        "$mainUrl/category/english/" to "English",
    )

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private fun extractQuality(text: String): Pair<String, Int> {
        return when {
            text.contains("2160p", true) || text.contains("4K", true) || text.contains("4k", true) -> "2160p" to 2160
            text.contains("1080p", true) -> "1080p" to 1080
            text.contains("720p", true) -> "720p" to 720
            text.contains("480p", true) -> "480p" to 480
            else -> "Unknown" to Qualities.Unknown.value
        }
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("Download ", true)) t = t.substring(9).trim()
        val parenMatch = Regex("""^.+?\((?:Season\s*\d+|\d{4})\)""").find(t)
        if (parenMatch != null) return parenMatch.value.trim()
        val cutIdx = Regex("""\s+(?:480p|720p|1080p|2160p|4K)\b""", RegexOption.IGNORE_CASE).find(t)?.range?.first
        if (cutIdx != null) t = t.substring(0, cutIdx).trim()
        t = t.replace(
            Regex("""\s+(?:Dual\s+Audio|Multi\s+Audio|Hindi\s+Dubbed|Movie|Web\s+Series|WEB\s+Series|Download).*$""", RegexOption.IGNORE_CASE), ""
        ).trim()
        return t.trim().trimEnd('(', '-', ':', '.')
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = a.attr("href").ifBlank { return null }
        val titleEl = selectFirst("h3.movie-title") ?: selectFirst("h2.movie-title") ?: selectFirst(".movie-title")
        val titleRaw = titleEl?.text()?.trim()
            ?: a.attr("title")?.trim()
            ?: a.selectFirst("img")?.attr("title")?.trim()
            ?: a.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val title = cleanTitle(titleRaw)
        if (title.isBlank()) return null
        val img = selectFirst("img.poster")?.attr("src")
            ?: selectFirst("img")?.attr("src")
            ?: ""
        val isSeries = titleRaw.contains("S0", true) || titleRaw.contains("Season", true) ||
            titleRaw.contains("Series", true) || selectFirst(".meta-row")?.text()?.contains("Series", true) == true
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
            Log.d("NKM", "getMainPage: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = doc.select("article.movie-card").mapNotNull { it.toSearchResult() }
        val hasNext = doc.select("a.next.page-numbers, a.nextpostslink").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = try {
            kmGet("$mainUrl/?s=$encoded", headers = baseHeaders).document
        } catch (e: Exception) {
            Log.d("NKM", "search: ${e.message}")
            return emptyList()
        }
        return doc.select("article.movie-card").mapNotNull { it.toSearchResult() }
    }

    private data class DownloadLink(
        val qualityName: String,
        val qualityInt: Int,
        val size: String,
        val url: String,
        val category: String,
    )

    private data class SeasonData(
        val seasonNum: Int,
        val epCount: Int,
        val episodeLinks: List<DownloadLink>,
        val combinedLinks: List<DownloadLink>,
        val zipLinks: List<DownloadLink>,
    )

    private fun parseDownloadLinks(doc: org.jsoup.nodes.Document): List<DownloadLink> {
        val links = mutableListOf<DownloadLink>()
        val btns = doc.select("a.dl-btn")
        for (btn in btns) {
            val href = btn.attr("href").trim()
            if (href.isBlank() || !href.startsWith("http")) continue
            val res = btn.selectFirst("span.dl-res")?.text()?.trim() ?: "Unknown"
            val size = btn.selectFirst("span.dl-size")?.text()?.trim() ?: ""
            val cls = btn.className()
            val category = if (cls.contains("webdl")) "webdl" else "encoded"
            val (qName, qInt) = extractQuality(res)
            links.add(DownloadLink(qName, qInt, size, href, category))
        }
        return links
    }

    private fun parseSeasonBlocks(doc: org.jsoup.nodes.Document): List<SeasonData> {
        val seasons = mutableListOf<SeasonData>()
        val seasonBlocks = doc.select("div.season-block")
        for (block in seasonBlocks) {
            val titleText = block.selectFirst("span.season-block-title")?.text()?.trim() ?: continue
            val seasonNum = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(titleText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epCount = Regex("""\((\d+)\s*eps\)""", RegexOption.IGNORE_CASE).find(titleText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val episodeLinks = mutableListOf<DownloadLink>()
            val combinedLinks = mutableListOf<DownloadLink>()
            val zipLinks = mutableListOf<DownloadLink>()

            val typeContents = block.select("div.type-content")
            for (tc in typeContents) {
                val dataType = tc.attr("data-type") ?: ""
                val btns = tc.select("a.dl-btn")
                for (btn in btns) {
                    val href = btn.attr("href").trim()
                    if (href.isBlank() || !href.startsWith("http")) continue
                    val res = btn.selectFirst("span.dl-res")?.text()?.trim() ?: "Unknown"
                    val size = btn.selectFirst("span.dl-size")?.text()?.trim() ?: ""
                    val cls = btn.className()
                    val category = if (cls.contains("webdl")) "webdl" else "encoded"
                    val (qName, qInt) = extractQuality(res)
                    val dl = DownloadLink(qName, qInt, size, href, category)
                    when {
                        dataType.startsWith("episodes") -> episodeLinks.add(dl)
                        dataType.startsWith("combined") -> combinedLinks.add(dl)
                        dataType.startsWith("zip") -> zipLinks.add(dl)
                    }
                }
            }

            seasons.add(SeasonData(seasonNum, epCount, episodeLinks, combinedLinks, zipLinks))
        }
        return seasons
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            kmGet(url, headers = baseHeaders).document
        } catch (e: Exception) {
            Log.d("NKM", "load: ${e.message}")
            return null
        }

        val h1 = doc.selectFirst("h1")?.text()?.let { cleanTitle(it) }
            ?: doc.selectFirst("title")?.text()?.let { cleanTitle(it) }
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img.poster")?.attr("src")
            ?: ""

        val plot = doc.selectFirst("p.about-summary")?.text()?.trim()
            ?: doc.selectFirst("div.about-summary")?.text()?.trim()
            ?: ""

        val year = doc.selectFirst("div.about-meta-box:contains(Release) .about-meta-value")?.text()?.trim()
            ?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val genres = doc.select("div.about-meta-box:contains(Genres) .about-meta-value a").map { it.text().trim() }

        val audio = doc.selectFirst("div.about-meta-box:contains(Audio) .about-meta-value")?.text()?.trim()
            ?: doc.selectFirst(".badge-audio")?.attr("title")?.trim()
            ?: ""

        val ratingStr = doc.selectFirst("div.about-highlight-pill:contains(IMDb) .about-highlight-value")?.text()?.trim()
        val rating = ratingStr?.toFloatOrNull()

        val seasonBlocks = parseSeasonBlocks(doc)
        val isSeries = seasonBlocks.isNotEmpty() || h1.contains("S0", true) || h1.contains("Season", true)

        if (isSeries && seasonBlocks.isNotEmpty()) {
            return buildSeriesResponse(h1, url, poster, plot, year, genres, audio, rating, seasonBlocks)
        }

        val downloadLinks = parseDownloadLinks(doc)
        val links = downloadLinks.map { dl ->
            mapOf(
                "q" to dl.qualityName,
                "qi" to dl.qualityInt,
                "s" to dl.size,
                "u" to dl.url,
                "a" to audio,
            )
        }
        val data = mapOf("t" to h1, "u" to url, "links" to links).toJson()
        return newMovieLoadResponse(h1, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            this.score = rating?.let { Score.from10(it) }
        }
    }

    private suspend fun buildSeriesResponse(
        h1: String,
        url: String,
        poster: String?,
        plot: String?,
        year: Int?,
        genres: List<String>,
        audio: String,
        rating: Float?,
        seasons: List<SeasonData>,
    ): LoadResponse? {
        val episodes = mutableListOf<Episode>()

        for (season in seasons) {
            if (season.episodeLinks.isEmpty() && season.combinedLinks.isEmpty() && season.zipLinks.isEmpty()) continue

            val hasEpisodeLinks = season.episodeLinks.any { it.url.contains("episodes.magiclinks.lol") }
            if (hasEpisodeLinks) {
                val maxEp = if (season.epCount > 0) season.epCount else 1
                val allQualityLinks = (season.episodeLinks + season.combinedLinks + season.zipLinks)
                for (epNum in 1..maxEp) {
                    val links = allQualityLinks.map { dl ->
                        mapOf(
                            "q" to dl.qualityName,
                            "qi" to dl.qualityInt,
                            "s" to dl.size,
                            "u" to dl.url,
                            "a" to audio,
                            "e" to epNum,
                        )
                    }
                    val data = mapOf("t" to h1, "s" to season.seasonNum, "e" to epNum, "links" to links).toJson()
                    episodes.add(
                        newEpisode(data) {
                            this.name = "$h1 S${season.seasonNum}E$epNum"
                            this.season = season.seasonNum
                            this.episode = epNum
                        }
                    )
                }
            } else {
                val allLinks = (season.episodeLinks + season.combinedLinks + season.zipLinks)
                val links = allLinks.map { dl ->
                    mapOf(
                        "q" to dl.qualityName,
                        "qi" to dl.qualityInt,
                        "s" to dl.size,
                        "u" to dl.url,
                        "a" to audio,
                    )
                }
                val data = mapOf("t" to h1, "s" to season.seasonNum, "e" to 1, "links" to links).toJson()
                episodes.add(
                    newEpisode(data) {
                        this.name = "$h1 Season ${season.seasonNum}"
                        this.season = season.seasonNum
                        this.episode = 1
                    }
                )
            }
        }

        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(h1, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            this.score = rating?.let { Score.from10(it) }
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
            Log.d("NKM", "loadLinks parse: ${e.message}")
            return false
        }
        @Suppress("UNCHECKED_CAST")
        val links = (parsed["links"] as? List<*>) ?: return false
        val epNum = (parsed["e"] as? Number)?.toInt()

        coroutineScope {
            links.map { linkRaw ->
                async {
                    val node = linkRaw as? Map<*, *> ?: return@async
                    val qName = node["q"] as? String ?: "Unknown"
                    val qInt = (node["qi"] as? Number)?.toInt() ?: Qualities.Unknown.value
                    val audio = node["a"] as? String ?: ""
                    val audioLabel = if (audio.isNotBlank()) " {$audio}" else ""
                    val linkUrl = node["u"] as? String ?: return@async
                    val size = node["s"] as? String ?: ""
                    val sizeLabel = if (size.isNotBlank()) " [$size]" else ""
                    val epLabel = if (epNum != null && epNum > 1) " E$epNum" else ""

                    val collected = mutableListOf<ExtractorLink>()
                    try {
                        loadExtractor(linkUrl, "$mainUrl/", subtitleCallback) { el -> collected.add(el) }
                    } catch (e: Exception) {
                        Log.d("NKM", "extractor $linkUrl: ${e.message}")
                    }
                    for (el in collected) {
                        callback(
                            newExtractorLink(
                                source = el.source,
                                name = "${el.name}$epLabel $qName$audioLabel$sizeLabel".trim(),
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
