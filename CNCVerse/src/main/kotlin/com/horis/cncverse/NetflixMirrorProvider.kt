package com.horis.cncverse

import android.content.Context
import com.horis.cncverse.entities.EpisodesData
import com.horis.cncverse.entities.PostData
import com.horis.cncverse.entities.SearchData
import com.horis.cncverse.entities.Season
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
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

// Netflix mirror served by the NetMirror backend. The same backend powers the
// sibling PrimeVideo/HotStar/Disney providers; the per-provider discriminator is
// the `ott` cookie ("nf" here). Catalog discovery uses the cheaper verify.php
// bypass (UtilsKt.bypass); stream resolution routes through the rotating NewTv
// API (UtilsKt.resolveApiUrl + getNewTvUserToken).
class NetflixMirrorProvider : MainAPI() {

    override var mainUrl = "https://net52.cc"
    override var name = "Netflix"
    override var lang = "ta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )
    override val hasMainPage = true
    override val hasDownloadSupport = true

    // t_hash_t value from verify.php — populated lazily by UtilsKt.bypass()
    private var cookie_value: String = ""

    val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // The shared cookie bundle sent to every NetMirror endpoint. `hd=on` asks
    // the backend for the HD variant; `ott=nf` selects the Netflix catalog.
    private fun netmirrorCookies(): Map<String, String> = mapOf(
        "t_hash_t" to cookie_value,
        "hd" to "on",
        "ott" to "nf"
    )

    private fun posterUrl(id: String): String = "https://imgcdn.kim/poster/v/$id.jpg"
    private fun episodePosterUrl(id: String): String = "https://imgcdn.kim/nf/v/200/$id.jpg"
    private fun episodesListPosterUrl(id: String): String = "https://imgcdn.kim/epimg/150/$id.jpg"
    private fun posterHeaders(): Map<String, String> = mapOf("Referer" to "$mainUrl/home")

    private suspend fun ensureBypassCookie() {
        if (cookie_value.isEmpty()) {
            cookie_value = bypass(mainUrl)
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        ensureBypassCookie()
        val url = "$mainUrl/mobile/home?app=1"
        val doc = app.get(
            url = url,
            headers = headers,
            referer = url,
            cookies = netmirrorCookies()
        ).document
        val items = doc.select(".tray-container, #top10").map { toHomePageList(it) }
        return newHomePageResponse(items, false)
    }

    private fun toHomePageList(element: Element): HomePageList {
        val name = element.select("h2, span").text()
        val items = element.select("article, .top10-post").mapNotNull { toSearchResult(it) }
        return HomePageList(name, items, false)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val id = element.selectFirst("a")?.attr("data-post")?.takeIf { it.isNotEmpty() }
            ?: element.attr("data-post")
        if (id.isEmpty()) return null
        return newAnimeSearchResponse("", Id(id).toJson()) {
            posterUrl = posterUrl(id)
            posterHeaders = posterHeaders()
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        ensureBypassCookie()
        val url = "$mainUrl/mobile/search.php?s=$query&t=${APIHolder.unixTime}"
        val resp = app.get(
            url = url,
            referer = "$mainUrl/home",
            cookies = netmirrorCookies()
        )
        val data = resp.parsed<SearchData>()
        return data.searchResult.map { result ->
            newAnimeSearchResponse(result.t, Id(result.id).toJson()) {
                posterUrl = posterUrl(result.id)
                posterHeaders = posterHeaders()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureBypassCookie()
        val id = parseJson<Id>(url).id
        val postUrl = "$mainUrl/mobile/post.php?id=$id&t=${APIHolder.unixTime}"
        val resp = app.get(
            url = postUrl,
            headers = headers,
            referer = "$mainUrl/home",
            cookies = netmirrorCookies()
        )
        val data = resp.parsed<PostData>()

        val cast: List<ActorData> = data.cast
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { ActorData(Actor(it)) }
            ?: emptyList()

        val genre: List<String>? = data.genre
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }

        val rating: String? = data.match?.replace("IMDb ", "")
        val runtimeMinutes: Int = convertRuntimeToMinutes(data.runtime.orEmpty())

        val suggest: List<AnimeSearchResponse>? = data.suggest?.map { s ->
            newAnimeSearchResponse("", Id(s.id).toJson()) {
                posterUrl = posterUrl(s.id)
                posterHeaders = posterHeaders()
            }
        }

        val episodes = ArrayList<Episode>()
        // firstOrNull handles both "empty list" and "list with JSON null first slot";
        // the backend signals a movie (single playable item) by returning an empty/null
        // episodes array rather than a separate endpoint.
        val isMovie = data.episodes.firstOrNull() == null
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        if (isMovie) {
            episodes += newEpisode(LoadData(data.title, id)) {
                name = data.title
            }
        } else {
            for (e in data.episodes) {
                episodes += newEpisode(LoadData(data.title, e.id)) {
                    name = e.t
                    episode = e.ep.removePrefix("E").toIntOrNull()
                    season = e.s.removePrefix("S").toIntOrNull()
                    posterUrl = episodePosterUrl(e.id)
                    runTime = e.time.removeSuffix("m").toIntOrNull()
                }
            }
            // Backend hints at more pages for the current season via nextPageShow==1.
            if (data.nextPageShow == 1) {
                val more = data.nextPageSeason?.let { seasonId ->
                    getEpisodes(data.title, url, seasonId, 2)
                } ?: emptyList()
                episodes.addAll(more)
            }
            // Fetch every other season in parallel. The current season is the last
            // entry of data.season, so drop it before fanning out.
            data.season?.dropLast(1)?.amap { season: Season ->
                episodes.addAll(getEpisodes(data.title, url, season.id, 1))
            }
        }

        return newTvSeriesLoadResponse(
            name = data.title,
            url = url,
            type = type,
            episodes = episodes
        ) {
            posterUrl = posterUrl(id)
            backgroundPosterUrl = posterUrl(id)
            posterHeaders = posterHeaders()
            plot = data.desc
            year = data.year.toIntOrNull()
            tags = genre
            actors = cast
            score = Score.from10(rating)
            duration = runtimeMinutes
            contentRating = data.ua
            recommendations = suggest
        }
    }

    // Paginates episodes.php until the backend signals no next page. Note the
    // polarity here is the inverse of load's nextPageShow check — this endpoint
    // uses 0 to mean "more pages available".
    private suspend fun getEpisodes(
        title: String,
        eid: String,
        sid: String,
        page: Int
    ): List<Episode> {
        val episodes = ArrayList<Episode>()
        val cookies = netmirrorCookies()
        var pg = page
        while (true) {
            val url = "$mainUrl/mobile/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg"
            val resp = app.get(
                url = url,
                headers = headers,
                referer = "$mainUrl/home",
                cookies = cookies
            )
            val data = resp.parsed<EpisodesData>()
            for (e in data.episodes ?: emptyList()) {
                episodes += newEpisode(LoadData(title, e.id)) {
                    name = e.t
                    episode = e.ep.removePrefix("E").toIntOrNull()
                    season = e.s.removePrefix("S").toIntOrNull()
                    posterUrl = episodesListPosterUrl(e.id)
                    runTime = e.time.removeSuffix("m").toIntOrNull()
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

            // First attempt with cached token; if the server rejects it we force
            // an OTP refresh and retry exactly once.
            var userToken = getNewTvUserToken(apiBase, "nf", forceRefresh = false)
            var response = fetchPlayerResponse(apiBase, id, userToken)
            if (response.status == "otp") {
                userToken = getNewTvUserToken(apiBase, "nf", forceRefresh = true)
                response = fetchPlayerResponse(apiBase, id, userToken)
            }

            val videoLink = response.video_link
            if (videoLink.isNullOrBlank()) return false

            val referer = response.referer ?: apiBase
            val link = newExtractorLink(
                source = name,
                name = name,
                url = videoLink,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
            }
            callback.invoke(link)
            true
        } catch (e: Exception) {
            Log.e("NetflixMirrorProvider", "loadLinks failed: ${e.message}")
            false
        }
    }

    private suspend fun fetchPlayerResponse(
        apiBase: String,
        id: String,
        userToken: String
    ): NewTvPlayerResponse {
        val headers = buildNewTvHeaders("nf", mapOf("Usertoken" to userToken))
        return app.get("$apiBase/newtv/player.php?id=$id", headers = headers)
            .parsed<NewTvPlayerResponse>()
    }

    // NetMirror requires the `hd=on` cookie on the m3u8 segment requests too,
    // otherwise the backend downgrades the manifest to SD. The Interceptor is
    // invoked by the player for every stream URL.
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            if (request.url.toString().contains(".m3u8")) {
                val newRequest = request.newBuilder().header("Cookie", "hd=on").build()
                chain.proceed(newRequest)
            } else {
                chain.proceed(request)
            }
        }
    }

    // JSON-encoded payload passed as the CloudStream "URL" — load/loadLinks
    // round-trip the id (and for episodes, the title) through these. AppUtils.toJson
    // (kotlinx) writes them; parseJson (Jackson, from UtilsKt) reads them back.
    data class Id(val id: String)

    data class LoadData(val title: String, val id: String)

    companion object {
        @Volatile
        private var context: Context? = null

        fun getContext(): Context? = context

        fun setContext(value: Context?) {
            context = value
        }
    }
}
