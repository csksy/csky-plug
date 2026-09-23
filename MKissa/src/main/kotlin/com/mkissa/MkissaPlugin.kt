package com.mkissa

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

@CloudstreamPlugin
class MkissaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Mkissa())
    }
}

class Mkissa : MainAPI() {

    override var mainUrl = "https://mkissa.to"
    override var name = "MKissa"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val loadLinksTimeoutMs: Long? = 3 * 60_000L

    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.Movie
    )

    override val mainPage = mainPageOf(
        "sub" to "Anime Sub",
        "dub" to "Anime Dub",
        "popular" to "Trending",
        "movies" to "Anime Movies",
    )

    private fun showToSearch(node: JSONObject): SearchResponse? {
        val id = node.optString("_id")
        if (id.isBlank()) return null
        val title = node.optString("englishName").takeIf { it.isNotBlank() && it != "null" }
            ?: node.optString("name").takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        val poster = node.optString("thumbnail").takeIf { it.startsWith("http") }
        val eps = node.optJSONObject("availableEpisodes")
        val subCount = eps?.optInt("sub", 0) ?: 0
        val dubCount = eps?.optInt("dub", 0) ?: 0
        val isMovie = subCount <= 1 && dubCount <= 1 && (subCount + dubCount) > 0 && looksLikeMovie(title)
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime
        return if (isMovie) {
            newMovieSearchResponse(title, id, type) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, id, type) {
                this.posterUrl = poster
            }
        }
    }

    private fun looksLikeMovie(title: String): Boolean {
        val t = title.lowercase()
        return t.contains(" movie") || t.contains(" film") ||
                Regex("""\((?:19|20)\d{2}\)\s*$""").containsMatchIn(title)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()
        when (request.data) {
            "sub", "dub" -> {
                val vars = JSONObject()
                val search = JSONObject()
                search.put("listProfile", "browse")
                vars.put("search", search)
                vars.put("limit", 26)
                vars.put("page", page)
                vars.put("translationType", request.data)
                val root = MkissaApi.queryByHash(MkissaApi.BROWSE_HASH, vars)
                val edges = root?.optJSONObject("data")?.optJSONObject("shows")?.optJSONArray("edges")
                for (i in 0 until (edges?.length() ?: 0)) {
                    edges?.optJSONObject(i)?.let { showToSearch(it) }?.let { results.add(it) }
                }
            }
            "popular" -> {
                val vars = JSONObject()
                vars.put("type", "anime")
                vars.put("size", 20)
                vars.put("dateRange", 1)
                vars.put("page", page)
                vars.put("allowAdult", false)
                vars.put("allowUnknown", false)
                val root = MkissaApi.queryByHash(MkissaApi.TRENDING_HASH, vars)
                val recs = root?.optJSONObject("data")?.optJSONObject("queryPopular")
                    ?.optJSONArray("recommendations")
                for (i in 0 until (recs?.length() ?: 0)) {
                    recs?.optJSONObject(i)?.optJSONObject("anyCard")?.let { showToSearch(it) }
                        ?.let { results.add(it) }
                }
            }
            "movies" -> {
                val vars = JSONObject()
                val search = JSONObject()
                search.put("slug", "movie-anime")
                search.put("format", "anime")
                search.put("page", page)
                search.put("limit", 26)
                search.put("name", "")
                vars.put("search", search)
                val root = MkissaApi.queryByHash(MkissaApi.LIST_FOR_TAG_HASH, vars)
                val edges = root?.optJSONObject("data")?.optJSONObject("queryListForTag")?.optJSONArray("edges")
                for (i in 0 until (edges?.length() ?: 0)) {
                    edges?.optJSONObject(i)?.let { showToSearch(it) }?.let { results.add(it) }
                }
            }
        }
        return newHomePageResponse(request.name, results, results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        for (track in listOf("sub", "dub")) {
            val vars = JSONObject()
            val search = JSONObject()
            search.put("sortBy", "Top")
            search.put("query", query)
            search.put("listProfile", "browse")
            vars.put("search", search)
            vars.put("limit", 26)
            vars.put("page", 1)
            vars.put("translationType", track)
            val root = MkissaApi.queryByHash(MkissaApi.BROWSE_HASH, vars)
            val edges = root?.optJSONObject("data")?.optJSONObject("shows")?.optJSONArray("edges")
            for (i in 0 until (edges?.length() ?: 0)) {
                edges?.optJSONObject(i)?.let { showToSearch(it) }?.let { out.putIfAbsent(it.url, it) }
            }
        }
        return out.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val showId = url.trimEnd('/').substringAfterLast('/')
        val vars = JSONObject()
        vars.put("_id", showId)
        val search = JSONObject()
        search.put("allowAdult", false)
        search.put("allowUnknown", false)
        search.put("denyEcchi", false)
        search.put("lite", false)
        search.put("forMe", false)
        search.put("fromSearch", true)
        vars.put("search", search)
        val root = MkissaApi.queryByHash(MkissaApi.SHOW_DETAIL_HASH, vars)
            ?: throw ErrorLoadingException("Could not reach mkissa. Check your connection and retry.")
        val show = root.optJSONObject("data")?.optJSONObject("show")
            ?: throw ErrorLoadingException("Show not found.")

        val title = show.optString("englishName").takeIf { it.isNotBlank() && it != "null" }
            ?: show.optString("name")
        val poster = show.optString("thumbnail").takeIf { it.startsWith("http") }
        val banner = show.optString("banner").takeIf { it.startsWith("http") }
        val plot = show.optString("description").takeIf { it.isNotBlank() && it != "null" }
        val year = show.optJSONObject("airedStart")?.optInt("year")?.takeIf { it > 1900 }
        val genres = mutableListOf<String>()
        show.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i)?.let { genres.add(it) }
        }
        val score = show.optDouble("score", 0.0).takeIf { it > 0 }
        val status = when (show.optString("status")) {
            "Finished" -> ShowStatus.Completed
            "Releasing" -> ShowStatus.Ongoing
            else -> null
        }

        val detail = show.optJSONObject("availableEpisodesDetail")
        val subList = episodeNumbers(detail?.optJSONArray("sub"))
        val dubList = episodeNumbers(detail?.optJSONArray("dub"))

        val maxEp = maxOf(subList.maxOrNull() ?: 0, dubList.maxOrNull() ?: 0)
        val names = mutableMapOf<Int, String>()
        if (maxEp > 0) {
            val chunks = (1..maxEp).chunked(100)
            try {
                coroutineScope {
                    chunks.map { chunk ->
                        async {
                            MkissaApi.episodeNames(showId, chunk.first(), chunk.last())
                        }
                    }.awaitAll().forEach { names.putAll(it) }
                }
            } catch (e: Exception) {
                Log.d("MKISSA", "episode names failed: ${e.message}")
            }
        }

        fun buildEpisodes(list: List<Int>, track: String): List<Episode> {
            return list.sorted().map { num ->
                newEpisode("$showId|$track|$num") {
                    this.episode = num
                    this.name = names[num]
                }
            }
        }

        val subEpisodes = buildEpisodes(subList, "sub")
        val dubEpisodes = buildEpisodes(dubList, "dub")

        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
            throw ErrorLoadingException("No episodes available for this title.")
        }

        return newAnimeLoadResponse(title, showId, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.year = year
            this.plot = plot
            this.tags = genres
            this.score = score
            this.showStatus = status
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private fun episodeNumbers(arr: JSONArray?): List<Int> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Int>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            val n = v.toIntOrNull() ?: v.toDoubleOrNull()?.toInt()
            if (n != null && n > 0) out.add(n)
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
        if (parts.size < 3) return false
        val showId = parts[0]
        val track = parts[1]
        val episodeString = parts[2]

        val episode = MkissaApi.episodeQuery(showId, track, episodeString) ?: run {
            Log.e("MKISSA", "episode query failed for $data")
            return false
        }

        val episodeInfo = episode.optJSONObject("episodeInfo")
        val qualityHint = if (track == "dub") {
            episodeInfo?.optJSONObject("vidInforsdub")?.optInt("vidResolution", 0) ?: 0
        } else {
            episodeInfo?.optJSONObject("vidInforssub")?.optInt("vidResolution", 0) ?: 0
        }
        val trackLabel = if (track == "dub") "Dub" else "Sub"

        val sources = episode.optJSONArray("sourceUrls") ?: return false

        val ordered = mutableListOf<JSONObject>()
        for (i in 0 until sources.length()) {
            sources.optJSONObject(i)?.let { ordered.add(it) }
        }
        ordered.sortBy { it.optDouble("priority", 99.0) }

        var emitted = false
        for (source in ordered) {
            val sourceName = source.optString("sourceName")
            val sourceUrl = source.optString("sourceUrl")
            if (sourceUrl.isBlank()) continue
            try {
                if (sourceUrl.startsWith("--")) {
                    val decoded = MkissaCrypto.hexToUrl(sourceUrl.substring(2))
                    if (!decoded.contains("/apivtwo/clock")) continue
                    val links = MkissaApi.resolveClockLinks(decoded)
                    if (links != null) {
                        for (j in 0 until links.length()) {
                            val linkObj = links.optJSONObject(j) ?: continue
                            val link = linkObj.optString("link")
                            if (!link.startsWith("http")) continue
                            val isHls = linkObj.optBoolean("hls", false) ||
                                    link.contains(".m3u8", true) ||
                                    linkObj.optString("resolutionStr").equals("Hls", true)
                            val resolutionStr = linkObj.optString("resolutionStr")
                            val quality = parseQuality(resolutionStr, link, qualityHint)
                            callback(
                                newExtractorLink(
                                    "MKissa",
                                    "MKissa $trackLabel ${labelFor(resolutionStr, quality)}",
                                    link,
                                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                }
                            )
                            emitSubtitles(linkObj, subtitleCallback)
                            emitted = true
                        }
                    }
                } else if (sourceUrl.startsWith("http")) {
                    val name = sourceName.lowercase()
                    if (name.contains("fm") || sourceUrl.contains("bysekoze") ||
                        sourceUrl.contains("filemoon") || sourceUrl.contains("n1mwq")
                    ) {
                        val media = MkissaWeb.interceptMediaUrl(sourceUrl)
                        if (media != null) {
                            callback(
                                newExtractorLink(
                                    "MKissa Filemoon",
                                    "MKissa $trackLabel Filemoon",
                                    media,
                                    if (media.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = if (qualityHint > 0) qualityHint else Qualities.Unknown.value
                                }
                            )
                            emitted = true
                        }
                    } else {
                        loadExtractor(sourceUrl, mainUrl, subtitleCallback, callback)
                        emitted = true
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.d("MKISSA", "source $sourceName failed: ${e.message}")
            }
        }
        return emitted
    }

    private fun parseQuality(resolutionStr: String, link: String, hint: Int): Int {
        Regex("""(\d{3,4})p?""").find(resolutionStr)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""/(\d{3,4})p/""").find(link)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        if (hint > 0) return hint
        return Qualities.Unknown.value
    }

    private fun labelFor(resolutionStr: String, quality: Int): String {
        if (resolutionStr.isNotBlank() && resolutionStr != "null") return resolutionStr
        if (quality > 0) return "${quality}p"
        return "Default"
    }

    private fun emitSubtitles(linkObj: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
        val subs = linkObj.optJSONArray("subtitles") ?: return
        for (i in 0 until subs.length()) {
            val sub = subs.optJSONObject(i) ?: continue
            val url = sub.optString("url").takeIf { it.startsWith("http") } ?: continue
            val lang = sub.optString("lang").takeIf { it.isNotBlank() && it != "null" }
                ?: sub.optString("language").takeIf { it.isNotBlank() && it != "null" }
                ?: sub.optString("label").takeIf { it.isNotBlank() && it != "null" }
                ?: sub.optString("name").takeIf { it.isNotBlank() && it != "null" }
                ?: "English"
            subtitleCallback.invoke(SubtitleFile(lang, url))
        }
    }
}
