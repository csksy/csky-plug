package com.csksy.netmirror

import com.csksy.netmirror.entities.EpisodesData
import com.csksy.netmirror.entities.PostData
import com.csksy.netmirror.entities.SearchData
import com.csksy.netmirror.entities.Season
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import org.jsoup.nodes.Element

private const val TAG = "NetMirror"
private const val MAIN_URL = "https://net52.cc"

private val catalogHeaders = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
    "Cache-Control" to "max-age=0",
    "Connection" to "keep-alive",
    "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"124\", \"Android WebView\";v=\"124\"",
    "sec-ch-ua-mobile" to "?0",
    "sec-ch-ua-platform" to "\"Android\"",
    "Sec-Fetch-Dest" to "document",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "same-origin",
    "Sec-Fetch-User" to "?1",
    "Upgrade-Insecure-Requests" to "1",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Safari/537.36",
    "X-Requested-With" to "XMLHttpRequest"
)

abstract class NetMirrorBaseProvider : MainAPI() {
    override var mainUrl = MAIN_URL
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    protected abstract val ott: String
    protected abstract val urlPrefix: String
    protected abstract val posterBase: String
    protected abstract val backdropBase: String
    protected abstract val epImgBase: String

    private var cookieValue: String = ""

    private fun cookies(): Map<String, String> = mapOf(
        "t_hash_t" to cookieValue,
        "hd" to "on",
        "ott" to ott
    )

    private fun posterHeaders(): Map<String, String> = mapOf("Referer" to "$mainUrl/home")

    private suspend fun ensureCookie() {
        if (cookieValue.isEmpty()) {
            cookieValue = bypass(mainUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        ensureCookie()
        val url = "$mainUrl/mobile/home?app=1"
        val doc = cfGet(url, headers = catalogHeaders, referer = url, cookies = cookies()).document
        val items = doc.select(".tray-container, #top10").map { el ->
            val name = el.select("h2, span").text()
            val list = el.select("article, .top10-post").mapNotNull { toSearchResult(it) }
            HomePageList(name, list, false)
        }
        return newHomePageResponse(items, false)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val id = element.selectFirst("a")?.attr("data-post")?.takeIf { it.isNotEmpty() }
            ?: element.attr("data-post")
        if (id.isEmpty()) return null
        return newAnimeSearchResponse("", Id(id).toJson()) {
            posterUrl = "$posterBase$id.jpg"
            posterHeaders = posterHeaders()
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        ensureCookie()
        val url = "$mainUrl/mobile/$urlPrefix/search.php?s=$query&t=${APIHolder.unixTime}"
        val resp = cfGet(url, referer = "$mainUrl/home", cookies = cookies())
        val data = resp.parsed<SearchData>()
        return data.searchResult.map { result ->
            newAnimeSearchResponse(result.t, Id(result.id).toJson()) {
                posterUrl = "$posterBase${result.id}.jpg"
                posterHeaders = posterHeaders()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureCookie()
        val id = parseJson<Id>(url).id
        val postUrl = "$mainUrl/mobile/$urlPrefix/post.php?id=$id&t=${APIHolder.unixTime}"
        val resp = cfGet(postUrl, headers = catalogHeaders, referer = "$mainUrl/home", cookies = cookies())
        val data = resp.parsed<PostData>()

        val cast: List<ActorData> = data.cast
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { ActorData(Actor(it)) } ?: emptyList()

        val genre: List<String>? = data.genre
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

        val rating: String? = data.match?.replace("IMDb ", "")
        val runtimeMinutes: Int = convertRuntimeToMinutes(data.runtime.orEmpty())

        val suggest = data.suggest?.map { s ->
            newAnimeSearchResponse("", Id(s.id).toJson()) {
                posterUrl = "$posterBase${s.id}.jpg"
                posterHeaders = posterHeaders()
            }
        }

        val episodes = ArrayList<Episode>()
        val isMovie = data.episodes.isEmpty()
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        if (isMovie) {
            episodes += newEpisode(LoadData(data.title, id)) { name = data.title }
        } else {
            for (e in data.episodes) {
                val epId = e.id ?: continue
                episodes += newEpisode(LoadData(data.title, epId)) {
                    name = e.t
                    episode = e.ep?.removePrefix("E")?.toIntOrNull()
                    season = e.s?.removePrefix("S")?.toIntOrNull()
                    posterUrl = "$epImgBase$epId.jpg"
                    runTime = e.time?.removeSuffix("m")?.toIntOrNull()
                }
            }
            if (data.nextPageShow == 1) {
                data.nextPageSeason?.let { seasonId ->
                    episodes.addAll(getEpisodes(data.title, id, seasonId, 2))
                }
            }
            data.season?.dropLast(1)?.amap { season: Season ->
                episodes.addAll(getEpisodes(data.title, id, season.id, 1))
            }
        }

        return newTvSeriesLoadResponse(data.title, url, type, episodes) {
            posterUrl = "$posterBase$id.jpg"
            backgroundPosterUrl = "$backdropBase$id.jpg"
            posterHeaders = posterHeaders()
            plot = data.desc
            year = data.year?.toIntOrNull()
            tags = genre
            actors = cast
            score = Score.from10(rating)
            duration = runtimeMinutes
            contentRating = data.ua
            recommendations = suggest
        }
    }

    private suspend fun getEpisodes(title: String, eid: String, sid: String, startPage: Int): List<Episode> {
        val episodes = ArrayList<Episode>()
        val cookies = cookies()
        var pg = startPage
        while (true) {
            val url = "$mainUrl/mobile/$urlPrefix/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg"
            val resp = cfGet(url, headers = catalogHeaders, referer = "$mainUrl/home", cookies = cookies)
            val data = resp.parsed<EpisodesData>()
            for (e in data.episodes ?: emptyList()) {
                val epId = e.id ?: continue
                episodes += newEpisode(LoadData(title, epId)) {
                    name = e.t
                    episode = e.ep?.removePrefix("E")?.toIntOrNull()
                    season = e.s?.removePrefix("S")?.toIntOrNull()
                    posterUrl = "$epImgBase$epId.jpg"
                    runTime = e.time?.removeSuffix("m")?.toIntOrNull()
                }
            }
            if (data.nextPageShow == 0) pg++ else break
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val apiBase = resolveApiUrl()
            val id = parseJson<LoadData>(data).id

            var userToken = getNewTvUserToken(apiBase, ott, forceRefresh = false)
            var response = fetchPlayerResponse(apiBase, id, userToken)
            if (response.status == "otp") {
                userToken = getNewTvUserToken(apiBase, ott, forceRefresh = true)
                response = fetchPlayerResponse(apiBase, id, userToken)
            }

            val videoLink = response.video_link
            if (videoLink.isNullOrBlank()) return false

            val referer = response.referer ?: apiBase
            callback.invoke(newExtractorLink(
                source = name,
                name = name,
                url = videoLink,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
            })
            true
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks failed: ${e.message}")
            false
        }
    }

    private suspend fun fetchPlayerResponse(apiBase: String, id: String, userToken: String): NewTvPlayerResponse {
        val headers = buildNewTvHeaders(ott, mapOf("Usertoken" to userToken))
        return cfGet("$apiBase/newtv/player.php?id=$id", headers = headers).parsed<NewTvPlayerResponse>()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = hdInterceptor

    data class Id(val id: String)
    data class LoadData(val title: String, val id: String)
}

class NetflixProvider : NetMirrorBaseProvider() {
    override var name = "Netflix"
    override var lang = "ta"
    override val ott = "nf"
    override val urlPrefix = ""
    override val posterBase = "https://imgcdn.kim/poster/v/"
    override val backdropBase = "https://imgcdn.kim/poster/v/"
    override val epImgBase = "https://imgcdn.kim/nf/v/200/"
}

class PrimeVideoProvider : NetMirrorBaseProvider() {
    override var name = "Prime Video"
    override var lang = "ta"
    override val ott = "pv"
    override val urlPrefix = "pv/"
    override val posterBase = "https://imgcdn.kim/pv/v/"
    override val backdropBase = "https://imgcdn.kim/pv/h/"
    override val epImgBase = "https://imgcdn.kim/pvepimg/"
}

class HotstarProvider : NetMirrorBaseProvider() {
    override var name = "Hotstar"
    override var lang = "ta"
    override val ott = "hs"
    override val urlPrefix = "hs/"
    override val posterBase = "https://imgcdn.kim/hs/v/"
    override val backdropBase = "https://imgcdn.kim/hs/h/"
    override val epImgBase = "https://imgcdn.kim/hsepimg/150/"
}
