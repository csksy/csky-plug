package com.csksy.toonix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private const val TAG = "Toonix"

class ToonixProvider : MainAPI() {
    override var mainUrl = "https://toonix.bond"
    override var name = "Toonix"
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.Movie)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "shows" to "TV Shows",
        "movies" to "Movies",
    )

    private val flightRegex = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
    // the player component receives the stream url directly, hosts vary between
    // hls workers and archive.org so match the component instead of the host
    private val playerSrcRegex =
        Regex("""L\d+",null,\{"src":"(https?://[^"]+)""")
    private val catalogRegex = Regex(
        """\{"slug":"([^"]+)","href":"([^"]+)","poster":"([^"]+)","title":"([^"]+)","year":([^,]+),"type":"(SHOW|MOVIE)","category":"(ANIME|CARTOON)""""
    )
    private val firstEpisodeRefRegex = Regex("""episodeRefs":\["(\d+)/(\d+)"""")
    private val yearRegex = Regex("""\(\s*(\d{4})\s*\)""")
    private val resolutionRegex = Regex("""RESOLUTION=\d+x(\d+)""")

    private data class TxEpisode(
        val number: Int? = null,
        val title: String? = null,
        val image: String? = null,
        val duration: String? = null
    )

    private data class TxSeason(
        val number: Int? = null,
        val title: String? = null,
        val episodes: List<TxEpisode>? = null
    )

    private suspend fun getPage(url: String): String = app.get(url, timeout = 30_000L).text

    // flight data sits inside js strings, the escapes must be decoded so the
    // embedded json stays parseable
    private fun flightPayload(html: String): String {
        val sb = StringBuilder()
        for (m in flightRegex.findAll(html)) unescapeJs(m.groupValues[1], sb)
        return sb.toString()
    }

    private fun unescapeJs(s: String, out: StringBuilder) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                out.append(c)
                i++
                continue
            }
            when (s[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                'u' -> {
                    val code = s.substring(i + 2, minOf(i + 6, s.length)).toIntOrNull(16)
                    if (code != null) { out.append(Character.toChars(code)); i += 6 } else { out.append(c); i++ }
                }
                else -> { out.append(s[i + 1]); i += 2 }
            }
        }
    }

    // brace matching is needed because season titles can contain brackets
    private fun jsonArray(payload: String, key: String): String? {
        val marker = "\"$key\":["
        val start = payload.indexOf(marker)
        if (start < 0) return null
        var i = start + marker.length - 1
        var depth = 0
        var inString = false
        var escaped = false
        while (i < payload.length) {
            val c = payload[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                inString = !inString
            } else if (!inString) {
                if (c == '[') depth++
                else if (c == ']') {
                    depth--
                    if (depth == 0) return payload.substring(start + marker.length - 1, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun cardToSearch(
        title: String,
        href: String,
        poster: String,
        year: Int?,
        isMovie: Boolean,
        isAnime: Boolean
    ): SearchResponse {
        val url = mainUrl + href
        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, url, if (isAnime) TvType.Anime else TvType.Cartoon) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        return try {
            val path = when (request.data) {
                "movies" -> "/movies"
                "shows" -> "/tv-shows"
                else -> "/"
            }
            val doc = Jsoup.parse(getPage(mainUrl + path))
            val items = ArrayList<SearchResponse>()
            val seen = HashSet<String>()
            for (a in doc.select("main a[href^=/show/], main a[href^=/title/]")) {
                val href = a.attr("href")
                if (href.count { it == '/' } != 2 || !seen.add(href)) continue
                val img = a.selectFirst("img") ?: continue
                val title = img.attr("alt").trim()
                if (title.isEmpty()) continue
                val spans = a.select("span").map { it.text().trim() }.filter { it.isNotEmpty() }
                val category = spans.getOrNull(1)
                val year = spans.firstOrNull { it.length == 4 && it.toIntOrNull() in 1900..2099 }?.toIntOrNull()
                items.add(
                    cardToSearch(
                        title = title,
                        href = href,
                        poster = img.attr("src"),
                        year = year,
                        isMovie = href.startsWith("/title/"),
                        isAnime = category == "ANIME"
                    )
                )
            }
            newHomePageResponse(request.name, items, hasNext = false)
        } catch (e: Exception) {
            Log.e(TAG, "main page failed: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return try {
            val payload = flightPayload(getPage("$mainUrl/search"))
            catalogRegex.findAll(payload)
                .filter { it.groupValues[4].lowercase().contains(q) }
                .map { m ->
                    cardToSearch(
                        title = m.groupValues[4],
                        href = m.groupValues[2],
                        poster = m.groupValues[3],
                        year = m.groupValues[5].toIntOrNull(),
                        isMovie = m.groupValues[6] == "MOVIE",
                        isAnime = m.groupValues[7] == "ANIME"
                    )
                }
                .toList()
        } catch (e: Exception) {
            Log.e(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = getPage(url)
        if (url.contains("/title/")) return loadMovie(url, Jsoup.parse(html))

        val doc = Jsoup.parse(html)
        val h1Text = doc.selectFirst("h1")?.text()?.trim() ?: "Show"
        val yearMatch = yearRegex.findAll(h1Text).lastOrNull()
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
        val title = yearMatch?.let { h1Text.substring(0, it.range.first).trim() }?.ifBlank { h1Text } ?: h1Text
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val poster = doc.selectFirst("img[src*=tmdb]")?.attr("src")
        val genres = doc.selectFirst("h1")?.nextElementSibling()?.select("span")
            ?.filter { it.children().isEmpty() && it.text().isNotBlank() && !it.text().startsWith("(") }
            ?.map { it.text().trim() }
            .orEmpty()
        val tvType = if (genres.firstOrNull()?.equals("Anime", ignoreCase = true) == true) TvType.Anime else TvType.Cartoon
        val slug = url.trimEnd('/').substringAfterLast('/')

        // one watch page carries the full season sidebar, episode numbers are not
        // contiguous so the first ref from the show page is used to reach it
        val episodes = ArrayList<Episode>()
        val showPayload = flightPayload(html)
        val firstRef = firstEpisodeRefRegex.find(showPayload)
        if (firstRef != null) {
            val watchPayload = flightPayload(getPage("$mainUrl/show/$slug/${firstRef.groupValues[1]}/${firstRef.groupValues[2]}"))
            val seasonsJson = jsonArray(watchPayload, "seasons")
            if (seasonsJson != null) {
                val seasons = try {
                    parseJson<List<TxSeason>>(seasonsJson)
                } catch (e: Exception) {
                    Log.e(TAG, "season parse failed: ${e.message}")
                    null
                }
                for (season in seasons.orEmpty()) {
                    val seasonNum = season.number ?: continue
                    for (ep in season.episodes.orEmpty()) {
                        val epNum = ep.number ?: continue
                        episodes.add(
                            newEpisode("$mainUrl/show/$slug/$seasonNum/$epNum") {
                                this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = ep.image
                            }
                        )
                    }
                }
            }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("No episodes found for $title")

        return newTvSeriesLoadResponse(
            title,
            url,
            tvType,
            episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
        }
    }

    private suspend fun loadMovie(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Movie"
        val watchHref = doc.selectFirst("a[href^=/watch/]")?.attr("href")
            ?: throw ErrorLoadingException("No watch link for $title")
        return newMovieLoadResponse(title, url, TvType.Movie, mainUrl + watchHref) {
            this.posterUrl = doc.selectFirst("img[src*=tmdb]")?.attr("src")
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    private suspend fun manifestHeight(url: String): Int? {
        return try {
            val body = app.get(url, timeout = 15_000L).text
            if (!body.startsWith("#EXTM3U")) null else resolutionRegex.find(body)?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = if (data.startsWith("http")) data else mainUrl + data
        return try {
            val payload = flightPayload(getPage(url))
            val links = playerSrcRegex.findAll(payload).map { it.groupValues[1] }.distinct().toList()
            for ((i, link) in links.withIndex()) {
                val isHls = "hlsfast" in link || link.endsWith(".m3u8")
                val height = if (isHls) manifestHeight(link) else null
                val label = StringBuilder(name)
                if (height != null) label.append(" ").append(height).append("p")
                if (links.size > 1) label.append(" ").append(i + 1)
                callback(
                    newExtractorLink(
                        source = name,
                        name = label.toString(),
                        url = link,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = when {
                            height == null -> Qualities.Unknown.value
                            height >= 1080 -> Qualities.P1080.value
                            height >= 720 -> Qualities.P720.value
                            height >= 480 -> Qualities.P480.value
                            else -> Qualities.P360.value
                        }
                    }
                )
            }
            links.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks failed: ${e.message}")
            false
        }
    }
}
