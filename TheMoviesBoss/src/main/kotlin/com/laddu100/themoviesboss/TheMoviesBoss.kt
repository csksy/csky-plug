package com.laddu100.themoviesboss

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "TheMoviesBoss"

@JsonIgnoreProperties(ignoreUnknown = true)
private data class DooplayerResponse(val embed_url: String? = null, val type: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class EpisodePayload(
    val page: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val downloads: List<String> = emptyList()
)

// episode 0 marks a movie or a merged whole season stream
private const val MERGED = 0

private val seasonEpisodeRegex = Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE)
private val seasonOnlyRegex = Regex("""\b(?:S(\d{1,2})|Season\s*(\d{1,2}))\b""", RegexOption.IGNORE_CASE)
private val seasonRegex = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
private val episodeNumberRegex = Regex("""Episode\s*0*(\d+)""", RegexOption.IGNORE_CASE)

// hosts whose pages resolve through the dedicated extractors registered by this plugin,
// tmbcloud pages only enumerate files since their file generation sits behind a captcha
private val MIRROR_HOSTS = listOf(
    "hubcloud", "vcloud", "gofile.io", "gdflix", "gdlink", "driveleech",
    "driveseed", "filepress", "filebee", "pixeldrain", "hubdrive"
)

private val directFileRegex = Regex("""\.(mp4|mkv|avi|webm)(\?|$)""", RegexOption.IGNORE_CASE)

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

    private val resolutionRegex = Regex("""(\d{3,4})[pP]""")

    private data class PlayerOption(val nume: String, val dataType: String, val postId: String, val label: String) {
        val seasonNumber: Int? = seasonRegex.find(label)?.groupValues?.get(1)?.toIntOrNull()
        val episodeNumber: Int? = episodeNumberRegex.find(label)?.groupValues?.get(1)?.toIntOrNull()
    }

    private data class CloudFile(val season: Int, val episode: Int, val fileName: String, val link: String?)

    private data class CloudPage(val files: List<CloudFile>, val mirrors: List<String>)

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

    // every http link on the page hosted by a known mirror or a plain file url,
    // tmbcloud entries excluded because they are handled with their own page reads
    private fun pageMirrorLinks(doc: Document): List<String> {
        val found = mutableListOf<String>()
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (!href.startsWith("http")) return@forEach
            if (MIRROR_HOSTS.any { href.contains(it, true) }) found += href
            else if (directFileRegex.containsMatchIn(href)) found += href
        }
        return found.distinct()
    }

    // tmbcloud pages come as folder listings, per-server spoiler groups, or single files;
    // per-file entries carry their own sub page which is what the player later resolves
    private suspend fun fetchCloudPage(doc: Document): CloudPage {
        val cloudLinks = doc.select("a[href*=\"tmbcloud.\"]")
            .map { it.absUrl("href") }
            .filter { it.startsWith("http") }
            .distinct()
        if (cloudLinks.isEmpty()) return CloudPage(emptyList(), emptyList())
        return coroutineScope {
            val pages = cloudLinks.map { link ->
                async(Dispatchers.IO) { readCloudPage(link) }
            }.awaitAll()
            CloudPage(
                files = pages.flatMap { it.files }.distinctBy { it.fileName },
                mirrors = pages.flatMap { it.mirrors }.distinct()
            )
        }
    }

    private suspend fun readCloudPage(url: String): CloudPage {
        return try {
            val doc = getCachedPage(url)
            val files = mutableListOf<CloudFile>()

            // anchors on tmbcloud pages label their target with the file name, so an
            // anchor text carrying SxxExx enumerates that episode even when the target
            // is a captcha gated tmbcloud page that cannot resolve further
            doc.select("a[href]").forEach { anchor ->
                val href = anchor.absUrl("href")
                if (!href.startsWith("http") || href == url) return@forEach
                val text = anchor.text().trim()
                val match = seasonEpisodeRegex.find(text)
                    ?: seasonEpisodeRegex.find(href.substringAfterLast('/').substringBefore('?'))
                    ?: return@forEach
                val season = match.groupValues[1].toIntOrNull() ?: return@forEach
                val episode = match.groupValues[2].toIntOrNull() ?: return@forEach
                val link = if (href.contains("tmbcloud.", true)) null else href
                files += CloudFile(season, episode, text.ifBlank { href.substringAfterLast('/') }, link)
            }

            doc.select("button[data-file]").forEach { btn ->
                val name = btn.attr("data-file").trim()
                val match = seasonEpisodeRegex.find(name) ?: return@forEach
                val season = match.groupValues[1].toIntOrNull() ?: return@forEach
                val episode = match.groupValues[2].toIntOrNull() ?: return@forEach
                files += CloudFile(season, episode, name, null)
            }

            if (files.isEmpty()) {
                doc.selectFirst(".download-box h1, .download-box h2")?.text()?.trim()?.let { name ->
                    val match = seasonEpisodeRegex.find(name)
                    if (match != null) {
                        val season = match.groupValues[1].toIntOrNull()
                        val episode = match.groupValues[2].toIntOrNull()
                        if (season != null && episode != null) files += CloudFile(season, episode, name, null)
                    }
                }
            }

            // single file pages expose their mirrors page wide, attach them to the one file
            val mirrors = pageMirrorLinks(doc).filter { !it.contains("tmbcloud.", true) }
            CloudPage(files.distinctBy { Triple(it.season, it.episode, it.link) }, if (files.size <= 1) mirrors else emptyList())
        } catch (e: Exception) {
            Log.d(TAG, "tmbcloud page: ${e.message}")
            CloudPage(emptyList(), emptyList())
        }
    }

    // detail page mirror links are tagged with SxxExx when they belong to one episode and
    // with a bare season marker when they belong to a season pack, the rest are whole title
    private data class PageDownloads(
        val perEpisode: Map<Pair<Int, Int>, List<String>>,
        val perSeason: Map<Int, List<String>>,
        val untagged: List<String>
    )

    private fun detailPageDownloads(doc: Document): PageDownloads {
        val perEpisode = mutableMapOf<Pair<Int, Int>, MutableList<String>>()
        val perSeason = mutableMapOf<Int, MutableList<String>>()
        val untagged = mutableListOf<String>()
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (!href.startsWith("http")) return@forEach
            val isMirror = MIRROR_HOSTS.any { href.contains(it, true) }
            val isDirect = directFileRegex.containsMatchIn(href)
            if (!isMirror && !isDirect) return@forEach
            val context = buildString {
                append(anchor.text())
                append(' ')
                append(anchor.parent()?.text().orEmpty())
                append(' ')
                append(href.substringAfterLast("/").substringBefore("?"))
            }
            val episodeMatch = seasonEpisodeRegex.find(context)
            val seasonMatch = seasonOnlyRegex.find(context)
            when {
                episodeMatch != null -> {
                    val key = (episodeMatch.groupValues[1].toIntOrNull() ?: 0) to
                        (episodeMatch.groupValues[2].toIntOrNull() ?: 0)
                    perEpisode.getOrPut(key) { mutableListOf() }.add(href)
                }
                seasonMatch != null -> {
                    val season = (seasonMatch.groupValues[1] ?: seasonMatch.groupValues[2]).toIntOrNull() ?: 0
                    perSeason.getOrPut(season) { mutableListOf() }.add(href)
                }
                else -> untagged += href
            }
        }
        return PageDownloads(perEpisode, perSeason, untagged.distinct())
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain(DOMAIN_KEY) ?: mainUrl
        val doc = getCachedPage(url)
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
        val cloud = fetchCloudPage(doc)
        val pageDownloads = detailPageDownloads(doc)

        val isSeries = isSeries(titleRaw) || cloud.files.isNotEmpty()

        return if (isSeries) {
            val defaultSeason = seasonRegex.find(titleRaw)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episodesBySeason = sortedMapOf<Int, MutableSet<Int>>()
            cloud.files.forEach {
                episodesBySeason.getOrPut(it.season) { sortedSetOf() }.add(it.episode)
            }
            pageDownloads.perEpisode.keys.forEach { (season, ep) ->
                if (ep > 0) episodesBySeason.getOrPut(season) { sortedSetOf() }.add(ep)
            }
            options.forEach { option ->
                option.episodeNumber?.let { ep ->
                    episodesBySeason.getOrPut(option.seasonNumber ?: defaultSeason) { sortedSetOf() }.add(ep)
                }
            }
            if (episodesBySeason.isEmpty() && options.any { it.episodeNumber == null }) {
                episodesBySeason.getOrPut(defaultSeason) { sortedSetOf() }
            }

            val downloadLinkByEpisode = cloud.files
                .filter { it.link != null }
                .associateBy({ it.season to it.episode }, { it.link!! })

            val seasonsWithSeasonPacks = pageDownloads.perSeason.keys.toMutableSet()

            val episodes = mutableListOf<Episode>()
            for ((season, episodeNumbers) in episodesBySeason) {
                for (ep in episodeNumbers) {
                    val downloads = buildList {
                        downloadLinkByEpisode[season to ep]?.let { add(it) }
                        addAll(pageDownloads.perEpisode[season to ep].orEmpty())
                    }.distinct()
                    episodes.add(
                        newEpisode(EpisodePayload(url, season, ep, downloads).toJson()) {
                            this.name = "Episode $ep"
                            this.season = season
                            this.episode = ep
                        }
                    )
                }
                // the site streams whole seasons as one merged file per player option,
                // and season tagged download buttons ride along on the same entry
                val hasSeasonStream = options.any {
                    it.episodeNumber == null &&
                        (it.seasonNumber == season || (it.seasonNumber == null && season == defaultSeason))
                }
                val seasonPacks = pageDownloads.perSeason[season].orEmpty()
                if (hasSeasonStream || seasonPacks.isNotEmpty()) {
                    seasonsWithSeasonPacks.remove(season)
                    episodes.add(
                        newEpisode(
                            EpisodePayload(url, season, MERGED, seasonPacks).toJson()
                        ) {
                            this.name = "Season $season (Full)"
                            this.season = season
                            this.episode = MERGED
                        }
                    )
                }
            }

            // season packs whose season has no episode list still deserve a playable entry
            for ((season, packs) in pageDownloads.perSeason) {
                if (season in seasonsWithSeasonPacks) {
                    episodes.add(
                        newEpisode(EpisodePayload(url, season, MERGED, packs).toJson()) {
                            this.name = "Season $season (Full)"
                            this.season = season
                            this.episode = MERGED
                        }
                    )
                }
            }

            // whole title downloads land on the default season entry so they are reachable
            if (pageDownloads.untagged.isNotEmpty() && cloud.mirrors.isNotEmpty()) {
                val whole = (pageDownloads.untagged + cloud.mirrors).distinct()
                val exists = episodes.any { it.season == defaultSeason && it.episode == MERGED }
                if (!exists) {
                    episodes.add(
                        newEpisode(EpisodePayload(url, defaultSeason, MERGED, whole).toJson()) {
                            this.name = "Season $defaultSeason (Full)"
                            this.season = defaultSeason
                            this.episode = MERGED
                        }
                    )
                }
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
            val movieDownloads = (
                pageDownloads.untagged + cloud.mirrors +
                    cloud.files.mapNotNull { it.link } +
                    pageDownloads.perSeason.values.flatten()
                ).distinct()
            newMovieLoadResponse(
                title, url, TvType.Movie,
                EpisodePayload(url, 0, MERGED, movieDownloads).toJson()
            ) {
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

    private suspend fun dooplayerEmbed(option: PlayerOption): String? {
        return try {
            val res = app.get(
                "$mainUrl/wp-json/dooplayer/v2/${option.postId}/${option.dataType}/${option.nume}",
                headers = baseHeaders
            ).text
            parseJson<DooplayerResponse>(res).embed_url
                ?.takeIf { it.contains("tmbplayer.") && it.contains("video") }
        } catch (e: Exception) {
            Log.e(TAG, "dooplayer: ${e.message}")
            null
        }
    }

    private fun seasonOptions(options: List<PlayerOption>, season: Int): List<PlayerOption> {
        val labeled = options.filter { it.episodeNumber == null && it.seasonNumber == season }
        if (labeled.isNotEmpty()) return labeled
        return options.filter { it.episodeNumber == null && it.seasonNumber == null }
    }

    private fun qualityFromText(text: String?): Int? {
        text ?: return null
        resolutionRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        if (text.contains("4k", true)) return 2160
        return null
    }

    private fun TmbPlayer.TmbSource.streamQuality(fallbackTitle: String?): Int =
        qualityFromText(label) ?: qualityFromText(fallbackTitle) ?: Qualities.Unknown.value

    // tmbplayer hands out an hls playlist plus a download page with progressive files.
    // both are probed here so dead links never reach the player
    private suspend fun emitOptionSources(
        option: PlayerOption,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = dooplayerEmbed(option) ?: return false
        val watch = TmbPlayer.resolve(embedUrl) ?: return false
        var found = false

        for (source in watch.sources) {
            val file = source.file ?: continue
            val isHls = file.contains("/hls/") || file.substringBefore("?").endsWith(".m3u8")
            if (!isHls) continue
            if (!m3u8Responds(file)) continue
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "TMBPlayer ${source.label?.ifBlank { null } ?: "Stream"}$suffix",
                    url = file,
                    referer = TmbPlayer.PLAYER_URL,
                    quality = source.streamQuality(watch.title),
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf(
                        "User-Agent" to baseHeaders["User-Agent"]!!,
                        "Referer" to TmbPlayer.PLAYER_URL
                    )
                )
            )
            found = true
        }

        val downloadUrl = watch.download_url ?: return found
        val downloads = TmbPlayer.resolveDownloads(downloadUrl, embedUrl) ?: return found
        for (source in downloads.sources.distinctBy { it.file }) {
            val file = source.file ?: continue
            if (file.contains("/hls/") || file.substringBefore("?").endsWith(".m3u8")) continue
            if (!urlResponds(file, embedUrl)) continue
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "TMB Download ${source.label?.ifBlank { null } ?: "File"}$suffix",
                    url = file,
                    referer = embedUrl,
                    quality = source.streamQuality(downloads.title ?: watch.title),
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf("User-Agent" to baseHeaders["User-Agent"]!!)
                )
            )
            found = true
        }
        return found
    }

    // a manifest answer proves the playlist alive, http level rejections prove it dead,
    // network level failures stay ambiguous and still reach the player
    private suspend fun m3u8Responds(url: String): Boolean {
        return try {
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to baseHeaders["User-Agent"]!!,
                    "Referer" to TmbPlayer.PLAYER_URL
                ),
                timeout = 10000L
            )
            if (res.code in 200..299) {
                res.text.contains("#EXTM3U") || res.text.contains("#EXT-X")
            } else {
                Log.d(TAG, "m3u8 probe code ${res.code}")
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "m3u8 probe: ${e.message}")
            true
        }
    }

    // progressive files redirect once before serving, a head request filters the dead ones
    private suspend fun urlResponds(url: String, referer: String): Boolean {
        return try {
            val res = app.head(
                url,
                headers = mapOf(
                    "User-Agent" to baseHeaders["User-Agent"]!!,
                    "Referer" to referer
                ),
                timeout = 10000L
            )
            res.code < 400 || res.code == 405
        } catch (e: Exception) {
            Log.d(TAG, "probe $url: ${e.message}")
            true
        }
    }

    private suspend fun resolveDownload(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (!MIRROR_HOSTS.any { link.contains(it, true) } && directFileRegex.containsMatchIn(link)) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Direct ${link.substringAfterLast('/').substringBefore('?')}",
                        url = link,
                        referer = mainUrl,
                        quality = getIndexQuality(link),
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return
            }
            loadExtractor(link, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e(TAG, "download $link: ${e.message}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val payload = try {
            parseJson<EpisodePayload>(data)
        } catch (e: Exception) {
            Log.e(TAG, "payload: ${e.message}")
            null
        } ?: return false

        val pageUrl = payload.page.takeIf { it.isNotBlank() } ?: return false
        val season = payload.season
        val episode = payload.episode

        val emitted = AtomicInteger(0)
        val counted: (ExtractorLink) -> Unit = { link ->
            emitted.incrementAndGet()
            callback.invoke(link)
        }

        // download links resolve first so the sources list fills without waiting on the player api
        coroutineScope {
            payload.downloads.map { link ->
                async(Dispatchers.IO) { resolveDownload(link, pageUrl, subtitleCallback, counted) }
            }.awaitAll()
        }

        val page = try {
            getCachedPage(pageUrl)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks page: ${e.message}")
            return emitted.get() > 0
        }
        val options = parsePlayerOptions(page)
        if (options.isEmpty()) return emitted.get() > 0

        val merged = options.filter { it.episodeNumber == null }
        val targets: List<Pair<PlayerOption, String>> = when {
            // movie pages expose every server without season or episode filtering
            episode == MERGED && season == 0 && merged.isNotEmpty() -> merged.map { it to "" }
            episode == MERGED -> seasonOptions(merged, season).ifEmpty { merged }.map { it to "" }
            else -> {
                // episode specific streams win when the page offers them, otherwise the
                // season's merged file is the only playable source the site provides
                val exact = options.filter {
                    it.episodeNumber == episode && (it.seasonNumber == null || it.seasonNumber == season)
                }
                if (exact.isNotEmpty()) exact.map { it to "" }
                else seasonOptions(merged, season).ifEmpty { merged }.map { it to " (Season Pack)" }
            }
        }

        for ((option, suffix) in targets) {
            emitOptionSources(option, suffix, counted)
        }
        return emitted.get() > 0
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
