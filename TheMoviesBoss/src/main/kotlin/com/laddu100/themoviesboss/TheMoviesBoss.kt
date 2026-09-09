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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "TheMoviesBoss"

@JsonIgnoreProperties(ignoreUnknown = true)
private data class DooplayerResponse(val embed_url: String? = null, val type: String? = null)

// episode data keeps the source page and the episode number, loadLinks re-derives
// everything else so the data string stays short and stable
private const val SEASON_EPISODE = 0

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

    private val episodeNumberRegex = Regex("""Episode\s*0*(\d+)""", RegexOption.IGNORE_CASE)
    private val seasonRegex = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)

    private data class PlayerOption(val nume: String, val dataType: String, val postId: String, val label: String)
    private data class CloudFile(val season: Int, val episode: Int, val url: String, val fileName: String)

    // pages are read in load() for the episode list and again in loadLinks on playback,
    // the cache keeps that second read from re-hitting the site while binge watching
    private data class CachedPage(val doc: Document, val storedAt: Long)
    private val pageCache = ConcurrentHashMap<String, CachedPage>()

    // the site serves stale database error pages under load, a single retry usually fixes it
    private suspend fun getPage(url: String): Document {
        val res = try {
            app.get(url, headers = baseHeaders)
        } catch (e: Exception) {
            Log.e(TAG, "getPage $url: ${e.message}")
            throw e
        }
        if (res.text.contains("Database Error", true)) {
            delay(1200)
            return app.get(url, headers = baseHeaders).document
        }
        return res.document
    }

    private suspend fun getCachedPage(url: String): Document {
        val cached = pageCache[url]
        if (cached != null && System.currentTimeMillis() - cached.storedAt < CACHE_TTL_MS) return cached.doc
        val doc = getPage(url)
        if (pageCache.size > 40) pageCache.clear()
        pageCache[url] = CachedPage(doc, System.currentTimeMillis())
        return doc
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
        mainUrl = FirebaseDomainHelper.getDomain(DOMAIN_KEY) ?: mainUrl
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
        mainUrl = FirebaseDomainHelper.getDomain(DOMAIN_KEY) ?: mainUrl
        if (query.isBlank()) return emptyList()
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val doc = getPage(url)
        return doc.select("div.result-item article, article.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun parsePlayerOptions(doc: Document): List<PlayerOption> {
        return doc.select("li.dooplay_player_option").mapNotNull { option ->
            val nume = option.attr("data-nume").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val postId = option.attr("data-post").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val dataType = option.attr("data-type").takeIf { it.isNotBlank() } ?: "movie"
            val label = option.selectFirst("span.title")?.text()
                ?.removePrefix("Watch Online")?.trim().orEmpty()
            PlayerOption(nume, dataType, postId, label)
        }
    }

    private fun episodeNumberOfOption(option: PlayerOption): Int? =
        episodeNumberRegex.find(option.label)?.groupValues?.get(1)?.toIntOrNull()

    // the tmbcloud bucket pages hide per episode links behind different host blocks,
    // only anchors naming an SxxEyy file matter for the episode list
    private suspend fun fetchCloudFiles(cloudUrls: List<String>): List<CloudFile> {
        return coroutineScope {
            cloudUrls.map { cloudUrl ->
                async(Dispatchers.IO) {
                    try {
                        val doc = getCachedPage(cloudUrl)
                        doc.select("a[href]").mapNotNull { anchor ->
                            val href = anchor.attr("href")
                            if (!href.startsWith("http")) return@mapNotNull null
                            if (!href.contains("hubcloud.", true) && !href.contains("filepress.", true)) {
                                return@mapNotNull null
                            }
                            val text = anchor.text()
                            val (season, episode) = HubCloud.episodeFromFileName(text) ?: return@mapNotNull null
                            CloudFile(season, episode, href, text)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "cloud bucket: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    private fun cloudUrlsOf(doc: Document): List<String> =
        doc.select("a[href*=\"tmbcloud.\"]").map { it.attr("href") }
            .filter { it.startsWith("http") }
            .distinct()

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain(DOMAIN_KEY) ?: mainUrl
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
        val options = parsePlayerOptions(doc)
        val trailer = options.firstOrNull()?.let { addTrailerLink(it.dataType, it.postId) }

        val isSeries = isSeries(titleRaw) || options.any { episodeNumberOfOption(it) != null }

        return if (isSeries) {
            val defaultSeason = seasonRegex.find(titleRaw)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val cloudFiles = fetchCloudFiles(cloudUrlsOf(doc))

            // episode numbers come from both the streaming options and the download buckets
            val streamEps = options.mapNotNull { episodeNumberOfOption(it) }.filter { it > 0 }.toSet()
            val downloadEps = cloudFiles.map { it.episode }.toSet()
            val episodeNumbers = (streamEps + downloadEps).filter { it > 0 }.sorted()

            val episodes = mutableListOf<Episode>()
            for (ep in episodeNumbers) {
                episodes.add(
                    newEpisode("$url|$ep") {
                        this.name = "Episode $ep"
                        this.season = defaultSeason
                        this.episode = ep
                    }
                )
            }
            // pages with a single watch option stream the whole season as one file,
            // keep it reachable without polluting the real episode numbering
            if (options.any { episodeNumberOfOption(it) == null } || episodeNumbers.isEmpty()) {
                episodes.add(
                    newEpisode("$url|$SEASON_EPISODE") {
                        this.name = "Full Season (All Episodes)"
                        this.season = defaultSeason
                        this.episode = SEASON_EPISODE
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
            newMovieLoadResponse(title, url, TvType.Movie, "$url|$SEASON_EPISODE") {
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

    private suspend fun emitStreamSources(
        numes: List<PlayerOption>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        for (option in numes) {
            val embedUrl = try {
                val res = app.get(
                    "$mainUrl/wp-json/dooplayer/v2/${option.postId}/${option.dataType}/${option.nume}",
                    headers = baseHeaders
                ).text
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

    private suspend fun emitDownloadSources(
        pageUrl: String,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = try {
            getCachedPage(pageUrl)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks page: ${e.message}")
            return false
        }
        val cloudUrls = cloudUrlsOf(page)
        if (cloudUrls.isEmpty()) return false
        val files = fetchCloudFiles(cloudUrls)

        // episode null means the merged whole season file
        val wanted = files.filter { file ->
            if (episode == null) HubCloud.episodeFromFileName(file.fileName) == null else file.episode == episode
        }.filter { it.url.contains("hubcloud.", true) }
            .distinctBy { it.url }
        if (wanted.isEmpty()) return false

        return coroutineScope {
            val results = wanted.map { file ->
                async(Dispatchers.IO) {
                    try {
                        HubCloud.resolve(file.url, file.fileName, callback)
                    } catch (e: Exception) {
                        Log.d(TAG, "download resolve: ${e.message}")
                        false
                    }
                }
            }.awaitAll()
            results.any { it }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val separator = data.lastIndexOf('|')
        if (separator <= 0) return false
        val pageUrl = data.substring(0, separator)
        val episode = data.substring(separator + 1).toIntOrNull() ?: return false

        val page = try {
            getCachedPage(pageUrl)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks page: ${e.message}")
            return false
        }
        val options = parsePlayerOptions(page)

        // episode 0 is the merged whole season stream, movies keep every server
        val streamOptions = if (episode == SEASON_EPISODE) {
            if (options.none { episodeNumberOfOption(it) != null }) {
                options
            } else {
                options.filter { episodeNumberOfOption(it) == null }
            }
        } else {
            options.filter { episodeNumberOfOption(it) == episode }
        }

        var found = false
        if (streamOptions.isNotEmpty()) {
            found = emitStreamSources(streamOptions, callback) || found
        }
        found = emitDownloadSources(pageUrl, episode.takeIf { it != SEASON_EPISODE }, callback) || found
        return found
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

    companion object {
        const val DOMAIN_KEY = "themoviesboss"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
