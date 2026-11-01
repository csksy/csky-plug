package com.cno

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.raghav.donation.DonationManager

class CnoAnimeProvider : MainAPI() {
    override var mainUrl = CnoApi.beta()
    override var name = "Multi Audio Anime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private companion object {
        const val RECENT_PAGE_LIMIT = 25

        fun tvTypeOf(format: String?): TvType = when (format?.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona", "special", "music" -> TvType.OVA
            else -> TvType.Anime
        }

        fun showStatusOf(status: String?): ShowStatus? = when (status?.lowercase()) {
            null -> null
            "currently airing", "releasing", "ongoing" -> ShowStatus.Ongoing
            "finished", "finished airing", "completed" -> ShowStatus.Completed
            else -> null
        }
    }

    override val mainPage = mainPageOf(
        "top:week" to "Trending This Week",
        "top:month" to "Trending This Month",
        "top:all" to "All-Time Popular",
        "recent" to "Latest Episodes"
    )

    private fun titleResponse(title: PartnerTitle): SearchResponse? {
        val id = title.anilistId ?: return null
        val name = title.title?.takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(name, id.toString(), tvTypeOf(title.format)) {
            posterUrl = title.poster
            year = title.year
            addDubStatus(
                dubExist = (title.episodes?.dub ?: 0) > 0,
                subExist = (title.episodes?.sub ?: 0) > 0
            )
        }
    }

    private fun recentResponse(item: PartnerRecentItem): SearchResponse? {
        val id = item.anilistId ?: return null
        val name = item.title?.takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(name, id.toString(), TvType.Anime) {
            posterUrl = item.poster
            addDubStatus(dubExist = item.dub == true, subExist = item.sub == true)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        CnoApi.refreshDomains()
        mainUrl = CnoApi.beta()
        return when {
            request.data == "recent" -> {
                if (page > RECENT_PAGE_LIMIT) {
                    return newHomePageResponse(request.name, emptyList(), hasNext = false)
                }
                val items = CnoApi.partnerRecent(page).mapNotNull { recentResponse(it) }
                newHomePageResponse(request.name, items, hasNext = page < RECENT_PAGE_LIMIT)
            }
            request.data.startsWith("top:") -> {
                val range = request.data.removePrefix("top:")
                val items = CnoApi.partnerTop(range, 30).mapNotNull { titleResponse(it) }
                newHomePageResponse(request.name, items, hasNext = false)
            }
            else -> newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        CnoApi.refreshDomains()
        mainUrl = CnoApi.beta()
        return CnoApi.partnerSearch(query).mapNotNull { titleResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        CnoApi.refreshDomains()
        mainUrl = CnoApi.beta()
        val anilistId = url.toIntOrNull() ?: return null
        val series = CnoApi.partnerSeries(anilistId) ?: return null
        val title = series.title?.takeIf { it.isNotBlank() } ?: return null

        // the stream folders on the edge are keyed by anilist id plus the slug
        // the site uses in its watch route, which only this endpoint knows
        val beta = CnoApi.betaServer(anilistId)
        val watchPath = beta?.path?.takeIf { beta.available == true } ?: return null
        val split = watchPath.lastIndexOf('-')
        if (split <= 0) return null
        val slug = watchPath.substring(0, split).removePrefix("/watch/")
        val pathId = watchPath.substring(split + 1)
        val origin = (beta.origin ?: CnoApi.beta()).removeSuffix("/")
        val playableEpisodes = beta.episodes.toSet()

        fun episodeData(number: Int): String = "$anilistId|$number|$slug|$pathId|$origin"

        fun buildEpisodes(dub: Boolean): List<Episode> {
            return series.episodeList.mapNotNull { ep ->
                val number = ep.number ?: return@mapNotNull null
                if (number < 1) return@mapNotNull null
                if (number !in playableEpisodes) return@mapNotNull null
                val available = ep.available
                val wanted = if (dub) available?.dub == true else available?.sub == true
                if (!wanted) return@mapNotNull null
                newEpisode(episodeData(number)) {
                    this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $number"
                    this.episode = number
                    this.description = ep.aired?.takeIf { it.isNotBlank() }?.let { "Aired $it" }
                    ep.runtimeSeconds?.takeIf { it > 0 }?.let { this.runTime = it / 60 }
                }
            }
        }

        val subEpisodes = buildEpisodes(dub = false)
        val dubEpisodes = buildEpisodes(dub = true)
        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) return null

        return newAnimeLoadResponse(title, url, tvTypeOf(series.format)) {
            posterUrl = series.poster
            backgroundPosterUrl = series.banner
            year = series.year
            plot = series.synopsis
            tags = series.genres
            duration = series.durationMinutes?.takeIf { it > 0 }
            contentRating = series.rating?.takeIf { it.isNotBlank() }
            series.score?.takeIf { it > 0 }?.let { score = Score.from10(it.toString()) }
            showStatusOf(series.status)?.let { this.showStatus = it }
            series.anilistId?.let { addAniListId(it) }
            series.malId?.let { addMalId(it) }
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 5) return false
        val anilistId = parts[0].toIntOrNull() ?: return false
        val episode = parts[1].toIntOrNull() ?: return false
        val slug = parts[2].takeIf { it.isNotBlank() } ?: return false
        val pathId = parts[3].takeIf { it.isNotBlank() } ?: return false
        val origin = parts[4].removeSuffix("/")

        val masterUrl = CnoApi.animeMasterUrl(origin, pathId.toIntOrNull() ?: anilistId, slug, episode)

        // the edge has been seen serving a half synced master on the first hit,
        // so it is asked for once more before the link is handed to the player
        if (!masterIsHealthy(masterUrl)) return false

        // every audio track and subtitle of the episode rides this one master,
        // the track switcher of the player picks between them
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name - Multi Audio",
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "$origin/"
            }
        )
        return true
    }

    private suspend fun masterIsHealthy(url: String): Boolean {
        repeat(2) {
            try {
                val res = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to CnoApi.USER_AGENT,
                        "Accept" to "*/*",
                        "Referer" to url.substringBefore("/hls/") + "/"
                    ),
                    timeout = 20_000L
                )
                if (res.code == 200 && res.text.startsWith("#EXTM3U")) return true
            } catch (_: Exception) {
                // retried below, two failures mean the episode is not there
            }
        }
        return false
    }
}
