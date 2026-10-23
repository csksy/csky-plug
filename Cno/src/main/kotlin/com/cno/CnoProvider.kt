package com.cno

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "Cno"

class CnoProvider : MainAPI() {
    override var mainUrl = CnoApi.MAIN_URL
    override var name = "Cno"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // mirrors the shelves the site serves, the ids are stable api keys so a
    // renamed shelf keeps working, an unknown one just renders empty
    override val mainPage = listOf(
        MainPageData("Featured", "hero"),
        MainPageData("Top 10 Movies Today", "top10-movies"),
        MainPageData("Top 10 Shows Today", "top10-shows"),
        MainPageData("Most Liked on Cno.to", "most-liked"),
        MainPageData("New in Theaters", "now"),
        MainPageData("Popular Shows", "popular-shows"),
        MainPageData("Sci-Fi & Fantasy", "scifi"),
        MainPageData("Critically Acclaimed", "acclaimed"),
        MainPageData("Coming Soon", "soon"),
        MainPageData("Comedy Series", "comedy-tv"),
        MainPageData("Crime Series", "crime-tv"),
        MainPageData("Animation", "animation"),
        MainPageData("Documentaries", "docs"),
        MainPageData("Box Office Hits", "hits"),
        MainPageData("Timeless Classics", "classics")
    )

    // the home document is one request behind every row, keep it warm for
    // a few minutes instead of refetching per row
    private var homeCache: Pair<Long, JSONObject>? = null

    private suspend fun home(): JSONObject? {
        homeCache?.let { (at, doc) ->
            if (System.currentTimeMillis() - at < 300_000L) return doc
        }
        val doc = CnoApi.json("/api/home") ?: return null
        homeCache = System.currentTimeMillis() to doc
        return doc
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val doc = home()
            ?: return newHomePageResponse(request.name, emptyList<SearchResponse>(), hasNext = false)
        val items: JSONArray? = if (request.data == "hero") {
            doc.optJSONArray("hero")
        } else {
            doc.optJSONArray("shelves")?.let { shelves ->
                (0 until shelves.length())
                    .asSequence()
                    .mapNotNull { shelves.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == request.data }
                    ?.optJSONArray("items")
            }
        }
        val list = mutableListOf<SearchResponse>()
        if (items != null) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                toSearchResponse(item)?.let(list::add)
            }
        }
        return newHomePageResponse(request.name, list, hasNext = false)
    }

    private fun toSearchResponse(item: JSONObject): SearchResponse? {
        val kind = item.optString("type")
        if (kind != "movie" && kind != "tv") return null
        val title = item.optString("title").takeIf { it.isNotBlank() } ?: return null
        val id = item.optInt("id", 0)
        if (id == 0) return null
        val type = if (kind == "movie") TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(title, "$kind|$id", type) {
            this.posterUrl = CnoApi.image(item.optString("poster"))
            this.year = item.optInt("year", 0).takeIf { it > 0 }
            score = Score.from10(item.optDouble("rating", 0.0))
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return null
        val q = android.net.Uri.encode(query.trim())
        val doc = CnoApi.json("/api/search?q=$q") ?: return null
        val results = doc.optJSONArray("results") ?: return null
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            toSearchResponse(item)?.let(out::add)
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|")
        if (parts.size < 2) return null
        val id = parts[1].toIntOrNull() ?: return null
        return if (parts[0] == "movie") loadMovie(id, url) else loadShow(id, url)
    }

    private suspend fun loadMovie(id: Int, url: String): LoadResponse? {
        val doc = CnoApi.json("/api/movie/$id") ?: return null
        val title = doc.optString("title").takeIf { it.isNotBlank() } ?: return null

        val sources = doc.optJSONArray("sources")
        val comingSoon = sources == null || sources.length() == 0

        return newMovieLoadResponse(title, url, TvType.Movie, "movie|$id") {
            this.posterUrl = CnoApi.image(doc.optString("poster"))
            this.backgroundPosterUrl = CnoApi.image(doc.optString("backdrop"), "w780")
            this.logoUrl = CnoApi.image(doc.optString("logo"))
            this.year = doc.optInt("year", 0).takeIf { it > 0 }
            this.plot = doc.optString("overview").takeIf { it.isNotBlank() }
            this.score = Score.from10(doc.optDouble("rating", 0.0))
            this.duration = doc.optInt("runtime", 0).takeIf { it > 0 }
            this.contentRating = doc.optString("certification").takeIf { it.isNotBlank() }
            this.tags = jsonStrings(doc.optJSONArray("genres"))
            this.actors = jsonCast(doc.optJSONArray("cast"))
            this.recommendations = jsonRecommendations(doc.optJSONArray("recommendations"))
            this.trailers = jsonTrailers(doc.optJSONArray("videos"))
            this.comingSoon = comingSoon
        }
    }

    private suspend fun loadShow(id: Int, url: String): LoadResponse? {
        val doc = CnoApi.json("/api/tv/$id") ?: return null
        val title = doc.optString("title").takeIf { it.isNotBlank() } ?: return null

        val seasons = doc.optJSONArray("seasonList") ?: JSONArray()
        val numbers = mutableListOf<Int>()
        for (i in 0 until seasons.length()) {
            seasons.optJSONObject(i)?.optInt("number", -1)?.takeIf { it >= 0 }?.let(numbers::add)
        }
        if (numbers.isEmpty()) numbers.add(1)

        val episodes = mutableListOf<Episode>()
        coroutineScope {
            // long shows carry dozens of seasons, an unbounded burst of
            // season calls is what stalls the page on slow devices
            val gate = Semaphore(6)
            val jobs = numbers.map { season ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        fetchSeason(id, season)
                    }
                }
            }
            jobs.forEach { job ->
                runCatching { episodes.addAll(job.await()) }
                    .onFailure { Log.w(TAG, "season fetch failed: ${it.message}") }
            }
        }
        episodes.sortBy { (it.season ?: 0) * 10000 + (it.episode ?: 0) }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = CnoApi.image(doc.optString("poster"))
            this.backgroundPosterUrl = CnoApi.image(doc.optString("backdrop"), "w780")
            this.logoUrl = CnoApi.image(doc.optString("logo"))
            this.year = doc.optInt("year", 0).takeIf { it > 0 }
            this.plot = doc.optString("overview").takeIf { it.isNotBlank() }
            this.score = Score.from10(doc.optDouble("rating", 0.0))
            this.duration = doc.optInt("runtime", 0).takeIf { it > 0 }
            this.contentRating = doc.optString("certification").takeIf { it.isNotBlank() }
            this.tags = jsonStrings(doc.optJSONArray("genres"))
            this.actors = jsonCast(doc.optJSONArray("cast"))
            this.recommendations = jsonRecommendations(doc.optJSONArray("recommendations"))
            this.trailers = jsonTrailers(doc.optJSONArray("videos"))
            showStatus = when (doc.optString("status")) {
                "Returning Series" -> ShowStatus.Ongoing
                "Ended", "Canceled" -> ShowStatus.Completed
                else -> null
            }
        }
    }

    private suspend fun fetchSeason(id: Int, season: Int): List<Episode> {
        val doc = CnoApi.json("/api/tv/$id/season/$season") ?: return emptyList()
        val eps = doc.optJSONArray("episodes") ?: return emptyList()
        val out = ArrayList<Episode>(eps.length())
        for (i in 0 until eps.length()) {
            val e = eps.optJSONObject(i) ?: continue
            val number = e.optInt("episode", 0)
            if (number <= 0) continue
            val name = e.optString("title").takeIf { it.isNotBlank() } ?: "Episode $number"
            val airDate = e.optString("date").takeIf { it.length >= 8 && it[0].isDigit() }
            out.add(
                newEpisode("tv|$id|$season|$number") {
                    this.name = name
                    this.season = season
                    this.episode = number
                    this.posterUrl = CnoApi.image(e.optString("still"))
                    this.description = e.optString("overview").takeIf { it.isNotBlank() }
                    score = Score.from10(e.optDouble("rating", 0.0))
                    if (airDate != null) runCatching { addDate(airDate) }
                }
            )
        }
        return out
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 2) return false
        val kind = parts[0]
        val id = parts[1].toIntOrNull() ?: return false

        var sources = emptyList<JSONObject>()
        var subFile: String? = null
        if (kind == "movie") {
            sources = sourceList(CnoApi.json("/api/movie/$id"))
            subFile = "movie/$id"
        } else if (kind == "tv") {
            val season = parts.getOrNull(2)?.toIntOrNull() ?: return false
            val episode = parts.getOrNull(3)?.toIntOrNull() ?: return false
            sources = sourceList(CnoApi.json("/api/tv/$id/episode/$season/$episode"))
            subFile = "tv/$id/$season/$episode"
        } else {
            return false
        }
        subFile?.let { emitSiteSubs(it, subtitleCallback) }

        var emitted = 0
        for (i in 0 until sources.size) {
            val src = sources[i]
            if (src.optString("kind") != "hls") continue
            val label = src.optString("label").takeIf { it.isNotBlank() } ?: "Server"
            val audio = audioTag(src)
            val master = CnoApi.text(src.optString("url")) ?: continue
            if (!master.startsWith("#EXTM3U")) continue
            for (v in CnoApi.parseVariants(master, src.optString("url"))) {
                val linkName = if (v.quality > 0) "$label · ${v.quality}p$audio" else "$label$audio"
                callback(
                    newExtractorLink(name, linkName, v.url) {
                        type = ExtractorLinkType.M3U8
                        quality = v.quality
                        // the in house servers proxy their playlists through
                        // cno.to itself, those need the clearance cookie the
                        // same as any other site request, the multi cdn ones
                        // are on their own hosts and take none
                        headers = CnoApi.streamHeaders(v.url) ?: emptyMap()
                    }
                )
                emitted++
            }
        }

        if (emitted == 0) {
            // nothing on the site's own servers, the site itself points at
            // embed players for these, the vidfast one is the one a webview
            // can crack, cinesrc guards itself with a proof of work
            val embed = sources.firstOrNull { it.optString("url").contains("vidfast") }
                ?: sources.firstOrNull { it.optString("kind") == "embed" }
            if (embed != null) emitted += resolveEmbed(embed.optString("url"), callback)
        }
        return emitted > 0
    }

    // the site splits its anime servers into audio channels, japanese audio
    // with selectable subtitles, an english dub track, and one with the
    // subtitles burned into the video
    private fun audioTag(src: JSONObject): String {
        val note = src.optString("note")
        return when {
            src.optString("channel") == "dub" -> " (Dub)"
            note.contains("burned in", ignoreCase = true) -> " (Hardsub)"
            src.optString("channel") == "sub" -> " (Sub)"
            else -> ""
        }
    }

    private fun sourceList(doc: JSONObject?): List<JSONObject> {
        val arr = doc?.optJSONArray("sources") ?: return emptyList()
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let(out::add)
        }
        return out
    }

    private suspend fun emitSiteSubs(filePath: String, callback: (SubtitleFile) -> Unit) {
        val files = CnoApi.json("/api/subs/list/$filePath")?.optJSONArray("files") ?: return
        // the list is ranked, the first file of a language is its best match,
        // one track per language keeps the player menu readable
        val seen = HashSet<String>()
        var sent = 0
        for (i in 0 until files.length()) {
            if (sent >= 20) break
            val f = files.optJSONObject(i) ?: continue
            val lang = f.optString("lang").takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(lang)) continue
            val label = f.optString("label").takeIf { it.isNotBlank() } ?: lang
            val subUrl = "${CnoApi.MAIN_URL}/api/subs/file/$filePath/${f.optString("id")}.vtt"
            callback(
                newSubtitleFile(lang, label) {
                    url = subUrl
                    // the files are served by the site itself, the player
                    // fetches them directly so they carry the same headers
                    headers = CnoApi.streamHeaders(subUrl)
                }
            )
            sent++
        }
    }

    // the embed page builds its playlist in javascript, a plain request only
    // ever sees html, so the player runs in a webview until a media url shows
    private suspend fun resolveEmbed(pageUrl: String, callback: (ExtractorLink) -> Unit): Int {
        val resolver = WebViewResolver(
            interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:[?#]|$)"""),
            additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:[?#]|$)""")),
            script = NUDGE,
            useOkhttp = false,
            timeout = 45_000L
        )
        return try {
            val resolved = app.get(pageUrl, referer = "$mainUrl/", interceptor = resolver).url
            val isM3u8 = resolved.contains(".m3u8", ignoreCase = true)
            if (!isM3u8 && !resolved.contains(".mp4", ignoreCase = true)) return 0
            val origin = Regex("^https?://[^/]+").find(pageUrl)?.value ?: "$mainUrl/"
            callback(
                newExtractorLink(name, "Player", resolved) {
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to origin
                    )
                }
            )
            1
        } catch (e: Exception) {
            Log.w(TAG, "embed resolve failed: ${e.message}")
            0
        }
    }

    // a paused player never requests the manifest, this nudges it so the
    // webview sees the url pass by without the user hearing anything
    private val NUDGE = """
        (function () {
            try {
                var v = document.querySelector('video');
                if (v) {
                    v.muted = true;
                    var p = v.play();
                    if (p && p.catch) p.catch(function () {});
                }
            } catch (e) {}
        })();
    """.trimIndent()

    private fun jsonStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun jsonCast(arr: JSONArray?): List<ActorData> {
        if (arr == null) return emptyList()
        val out = ArrayList<ActorData>(minOf(arr.length(), 15))
        for (i in 0 until minOf(arr.length(), 15)) {
            val c = arr.optJSONObject(i) ?: continue
            val person = c.optString("name").takeIf { it.isNotBlank() } ?: continue
            out.add(ActorData(Actor(person, CnoApi.image(c.optString("photo")))))
        }
        return out
    }

    private fun jsonRecommendations(arr: JSONArray?): List<SearchResponse> {
        if (arr == null) return emptyList()
        val out = ArrayList<SearchResponse>(minOf(arr.length(), 12))
        for (i in 0 until minOf(arr.length(), 12)) {
            val it = arr.optJSONObject(i) ?: continue
            toSearchResponse(it)?.let(out::add)
        }
        return out
    }

    private fun jsonTrailers(arr: JSONArray?): MutableList<com.lagradost.cloudstream3.TrailerData> {
        if (arr == null) return mutableListOf()
        val out = ArrayList<com.lagradost.cloudstream3.TrailerData>(4)
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            if (v.optString("type") != "Trailer" || v.optString("site") != "YouTube") continue
            if (out.size >= 4) break
            out.add(
                com.lagradost.cloudstream3.TrailerData(
                    "https://www.youtube.com/watch?v=${v.optString("key")}",
                    null,
                    false
                )
            )
        }
        return out
    }
}
