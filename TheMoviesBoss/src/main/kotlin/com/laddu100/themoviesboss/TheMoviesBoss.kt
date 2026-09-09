package com.laddu100.themoviesboss

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.delay
import java.net.URLEncoder

private const val TAG = "TheMoviesBoss"

@JsonIgnoreProperties(ignoreUnknown = true)
private data class DooplayerResponse(val embed_url: String? = null, val type: String? = null)

class TheMoviesBoss : MainAPI() {
    override var mainUrl = "https://ww2.themoviesboss.blog"
    override var name = "TheMoviesBoss"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // the site serves stale database error pages under load, a single retry usually fixes it
    private suspend fun getPage(url: String): Document {
        val res = app.get(url, headers = baseHeaders)
        if (res.text.contains("Database Error", true)) {
            delay(1200)
            return app.get(url, headers = baseHeaders).document
        }
        return res.document
    }

    override val mainPage = mainPageOf(
        "home" to "Latest",
        "genre/web-series" to "Web Series",
        "genre/bollywood" to "Bollywood",
        "genre/hollywood" to "Hollywood",
        "genre/anime" to "Anime",
        "genre/regional" to "Regional",
        "genre/netflix" to "Netflix",
        "genre/amzn" to "Prime Video",
        "genre/action" to "Action",
        "genre/thriller" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.data) {
            "home" -> mainUrl
            else -> if (page > 1) "$mainUrl/${request.data}/page/$page/" else "$mainUrl/${request.data}/"
        }
        val doc = getPage(url)
        val items = doc.select("article.item").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        val hasNext = request.data != "home" &&
            doc.select("div.pagination a").any { it.attr("href").contains("/page/${page + 1}/") }
        return newHomePageResponse(request, items, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href*=\"/movies/\"]") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val img = selectFirst("img") ?: return null
        val titleRaw = img.attr("alt").ifBlank {
            link.selectFirst("h3.title")?.text() ?: return null
        }
        val title = cleanTitle(titleRaw)
        val poster = img.attr("src").ifBlank { img.attr("data-src") }
        return if (isSeries(titleRaw)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = searchQuality(titleRaw)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality = searchQuality(titleRaw)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val doc = getPage(url)
        return doc.select("div.result-item article, article.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getPage(url)
        val titleRaw = doc.selectFirst(".sheader h1")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: return null
        val title = cleanTitle(titleRaw)
        val poster = doc.selectFirst(".sheader .poster img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val year = doc.selectFirst(".extra .date")?.text()?.let {
            Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull()
        } ?: Regex("(\\d{4})").find(titleRaw)?.groupValues?.get(1)?.toIntOrNull()
        val runtime = doc.selectFirst(".extra .runtime")?.text()
            ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val tags = doc.select(".sgeneros a").map { it.text() }.filter { it.isNotBlank() }
        val plot = findPlot(doc)

        val players = doc.select("li.dooplay_player_option")
            .mapNotNull { option ->
                val nume = option.attr("data-nume").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (nume == "trailer") return@mapNotNull null
                val label = option.selectFirst("span.title")?.text()
                    ?.removePrefix("Watch Online")?.trim().orEmpty()
                nume to label
            }
        val postId = doc.selectFirst("li.dooplay_player_option")?.attr("data-post")
            ?: doc.selectFirst("article")?.attr("id")?.removePrefix("post-")
        if (postId.isNullOrBlank()) return null
        val dataType = doc.selectFirst("li.dooplay_player_option")?.attr("data-type")
            ?.takeIf { it.isNotBlank() } ?: "movie"
        val trailer = addTrailerLink(dataType, postId)

        return if (isSeries(titleRaw) || players.any { it.second.contains("Season", true) }) {
            val episodes = mutableListOf<Episode>()
            val seasons = players
                .mapNotNull { (nume, label) -> nume.toIntOrNull()?.let { Triple(it, label, nume) } }
                .sortedBy { it.first }
            for ((nume, label, raw) in seasons) {
                episodes.add(
                    newEpisode("$postId|$raw|$dataType") {
                        this.name = label.ifBlank { "Season $nume" }
                        this.season = nume
                        this.episode = nume
                    }
                )
            }
            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode("$postId|1|$dataType") {
                        this.name = "Season 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = runtime
                if (trailer != null) addTrailer(trailer)
            }
        } else {
            // a movie can expose several player servers, try them all
            val numes = players.map { it.first }.distinct().joinToString(",").ifBlank { "1" }
            newMovieLoadResponse(title, url, TvType.Movie, "$postId|$numes|$dataType") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = runtime
                if (trailer != null) addTrailer(trailer)
            }
        }
    }

    private suspend fun addTrailerLink(dataType: String, postId: String): String? {
        return try {
            val res = app.get("$mainUrl/wp-json/dooplayer/v2/$postId/$dataType/trailer", headers = baseHeaders).text
            val embed = parseJson<DooplayerResponse>(res).embed_url ?: return null
            if (embed.contains("youtube.com/embed/")) {
                embed.substringAfter("/embed/").substringBefore("?").let { "https://www.youtube.com/watch?v=$it" }
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "trailer: ${e.message}")
            null
        }
    }

    private fun findPlot(doc: Document): String? {
        val junk = Regex("download|watch online|screenshot|telegram|password|report|click here", RegexOption.IGNORE_CASE)
        return doc.select("p")
            .map { it.text().trim() }
            .firstOrNull { it.length > 80 && !junk.containsMatchIn(it) }
    }

    private fun isSeries(text: String): Boolean =
        text.contains("Season", true) ||
            text.contains("Episode", true) ||
            text.contains("Web Series", true) ||
            text.contains("TV Series", true) ||
            Regex("\\bS\\d{1,2}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun cleanTitle(raw: String): String {
        var t = raw.substringBefore(" - TheMoviesBoss")
        t = t.replace(Regex("\\s+(Download|Watch Online)\\b.*$", RegexOption.IGNORE_CASE), "")
        listOf(" Full Movie", " All Episodes", " Hindi Movie", " Movie", " Web Series", " Full HD")
            .forEach { t = t.substringBefore(it) }
        t = t.trim().trimEnd('-', ':', ',')
        // quality and language tags pile up at the end, strip a bounded run of them
        var prev: String
        do {
            prev = t
            t = t.replace(Regex("\\s+(Hindi|English|Tamil|Telugu|Malayalam|Kannada|Bengali|Marathi|Punjabi|Japanese|Korean|Spanish|Dual Audio|ORG|HQ|HDRip|WEB-?DL|BluRay|REMUX|AMZN|NF|ZEE5|SN|JHS|DS4K|HEVC|10Bit|4K|2160p|1080p|720p|480p|540p|576p|Remux|Line|LiNE)\\b.*$", RegexOption.IGNORE_CASE), "")
            t = t.trim().trimEnd('-', ':', ',')
        } while (t != prev)
        return t.ifBlank { raw }
    }

    private fun searchQuality(text: String): SearchQuality? = when {
        text.contains("2160p", true) || text.contains("4K", true) -> SearchQuality.FourK
        text.contains("1080p", true) -> SearchQuality.HD
        text.contains("720p", true) -> SearchQuality.SD
        text.contains("480p", true) -> SearchQuality.SD
        else -> null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val parts = data.split("|")
        val postId = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return false
        val numes = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "1"
        val dataType = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "movie"

        var found = false
        for (nume in numes.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            val embedUrl = try {
                val res = app.get("$mainUrl/wp-json/dooplayer/v2/$postId/$dataType/$nume", headers = baseHeaders).text
                parseJson<DooplayerResponse>(res).embed_url
            } catch (e: Exception) {
                Log.e(TAG, "dooplayer: ${e.message}")
                null
            } ?: continue

            if (!embedUrl.contains("tmbplayer.site/video/")) continue

            for (source in TmbPlayer.loadSources(embedUrl)) {
                val link = source.file?.takeIf { it.isNotBlank() } ?: continue
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "TMBPlayer ${source.label ?: ""}".trim(),
                        url = link,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://tmbplayer.site/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to baseHeaders["User-Agent"]!!)
                    }
                )
                found = true
            }
        }
        return found
    }
}
