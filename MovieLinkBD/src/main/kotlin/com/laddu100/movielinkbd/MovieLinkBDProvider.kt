package com.laddu100.movielinkbd

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MovieLinkBDProvider : MainAPI() {

    override var mainUrl = "https://movielinkbd.net"
    override var name = "MovieLinkBD"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "latest" to "Latest Updates",
        "category/bangla-dubbed" to "Bangla Dubbed",
        "category/dual-audio" to "Dual Audio",
        "category/anime" to "Anime",
        "category/k-drama" to "K/J/C Drama",
        "category/animation" to "Animation",
        "category/indonesian" to "Indonesian",
        "category/wwe" to "WWE",
        "category/horror" to "Horror"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LinkEntry(val label: String, val url: String)

    private data class DownloadLink(
        val label: String,
        val url: String
    )

    private fun pageUrl(data: String, page: Int): String {
        return if (page <= 1) {
            if (data == "latest") "$mainUrl/" else "$mainUrl/$data/"
        } else {
            if (data == "latest") "$mainUrl/page/$page/" else "$mainUrl/$data/page/$page/"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = try {
            app.get(pageUrl(request.data, page), headers = headers, timeout = 20_000L).document
        } catch (e: Exception) {
            Log.d("MovieLinkBD", "main page failed: ${e.message}")
            return null
        }
        val items = doc.select("a.yv-movie-card").mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val doc = try {
            app.get(url, headers = headers, timeout = 20_000L).document
        } catch (e: Exception) {
            Log.d("MovieLinkBD", "search failed: ${e.message}")
            return emptyList()
        }
        return doc.select("a.yv-movie-card").mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst(".yv-movie-title")?.text()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = selectFirst(".yv-movie-img")?.attr("abs:src")?.takeIf { it.startsWith("http") }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            app.get(url, headers = headers, timeout = 20_000L).document
        } catch (e: Exception) {
            Log.d("MovieLinkBD", "load failed: ${e.message}")
            return null
        }

        val title = doc.selectFirst("h1.yv-hero-title")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim()
            ?: return null
        val poster = doc.selectFirst(".yv-poster img")?.attr("abs:src")?.takeIf { it.startsWith("http") }
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: doc.selectFirst(".yv-quick-tag")?.text()?.let { t ->
                Regex("""\d{4}""").find(t)?.value?.toIntOrNull()
            }
        val genres = infoValue(doc, "Genres")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val runtime = doc.select("span.yv-quick-tag").map { it.text() }
            .firstOrNull { Regex("""\d+\s*(?:Hour|Minute)""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?.let(::parseRuntime)
        val score = Regex("""IMDb Rating:\s*([\d.]+)/10""").find(
            doc.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        )?.groupValues?.get(1)?.toFloatOrNull()?.let { Score.from10(it) }

        val episodes = parseEpisodes(doc)
        if (episodes.isNotEmpty()) {
            val ongoing = doc.select("span.yv-badge-status").map { it.text() }
                .any { Regex("weekly|ongoing", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.tags = genres
                this.duration = runtime
                this.score = score
                this.showStatus = if (ongoing) ShowStatus.Ongoing else ShowStatus.Completed
            }
        }

        val links = parseMovieLinks(doc)
        if (links.isEmpty()) return null
        return newMovieLoadResponse(title, url, TvType.Movie, links.map { LinkEntry(it.label, it.url) }.toJson()) {
            this.posterUrl = poster
            this.year = year
            this.tags = genres
            this.duration = runtime
            this.score = score
        }
    }

    private fun infoValue(doc: Document, label: String): String? {
        for (item in doc.select("div.yv-info-item")) {
            val lbl = item.selectFirst(".yv-info-lbl")?.text()?.removeSuffix(":")?.trim()
            if (lbl.equals(label, ignoreCase = true)) {
                return item.selectFirst(".yv-info-val")?.text()?.trim()
            }
        }
        return null
    }

    private fun parseRuntime(text: String): Int? {
        var minutes = 0
        Regex("""(\d+)\s*Hour""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            minutes += it * 60
        }
        Regex("""(\d+)\s*Minute""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            minutes += it
        }
        return minutes.takeIf { it > 0 }
    }

    private fun parseMovieLinks(doc: Document): List<DownloadLink> {
        val links = mutableListOf<DownloadLink>()
        for (box in doc.select("div.dl-box")) {
            val quality = box.selectFirst(".dl-quality-text")?.text()?.trim() ?: continue
            val size = box.selectFirst(".dl-size-badge")?.text()?.trim() ?: ""
            val href = box.selectFirst("a.dl-action-btn")?.attr("href")?.takeIf { it.startsWith("http") } ?: continue
            val label = if (size.isBlank()) quality else "$quality · $size"
            links.add(DownloadLink(label, href))
        }
        return links
    }

    // "Episode 01" rows map to a single episode, "Ep 01-05" zip packs expand
    // so every covered number shows up on its own in the app
    private fun parseEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        for (seasonBlock in doc.select("div.yv-season-block")) {
            val season = seasonBlock.selectFirst(".yv-season-header > span")?.text()
                ?.let { Regex("""(\d+)""").find(it)?.value?.toIntOrNull() } ?: 1
            for (row in seasonBlock.select("div.yv-ep-row")) {
                val epTitle = row.selectFirst(".yv-ep-title")?.text()?.trim() ?: continue
                val range = Regex("""(?i)ep(?:isode)?\s*(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?""").find(epTitle)
                    ?: continue
                val start = range.groupValues[1].toIntOrNull() ?: continue
                val end = range.groupValues[2].toIntOrNull() ?: start
                if (end < start) continue

                val links = row.select(".yv-pill-group a.yv-pill").mapNotNull { pill ->
                    val href = pill.attr("href").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                    val label = pill.text().replace(Regex("\\s+"), " ").trim()
                    DownloadLink(label, href)
                }
                if (links.isEmpty()) continue

                val data = links.map { LinkEntry(it.label, it.url) }.toJson()
                val packNote = if (end > start) " ($epTitle pack)" else ""
                for (ep in start..end) {
                    episodes.add(
                        newEpisode(data) {
                            this.episode = ep
                            this.season = season
                            this.name = "Episode $ep$packNote"
                        }
                    )
                }
            }
        }
        return episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
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
            Log.d("MovieLinkBD", "link data parse failed: ${e.message}")
            return false
        }
        if (entries.isEmpty()) return false

        val results = coroutineScope {
            entries.map { entry ->
                async {
                    val file = KiteCloud.resolve(entry.url) ?: return@async false
                    val sizeLabel = if (file.size.isBlank()) "" else " · ${file.size}"
                    val quality = if (file.quality != Qualities.Unknown.value) file.quality else KiteCloud.qualityFrom(entry.label)
                    // the download endpoint the drive host hands out ignores range
                    // requests, so playback and resume go through the local proxy
                    // which serves ranges off its own copy of the stream
                    val playUrl = MovieLinkProxy.register(file.url, headers) ?: file.url
                    callback.invoke(
                        newExtractorLink(name, "${entry.label}$sizeLabel", playUrl, type = ExtractorLinkType.VIDEO) {
                            this.quality = quality
                        }
                    )
                    true
                }
            }.awaitAll()
        }
        return results.any { it }
    }
}
