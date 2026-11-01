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
import java.util.Calendar

class CnoAnimeProvider : MainAPI() {
    override var mainUrl = CnoApi.beta()
    override var name = "Multi Audio Anime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private companion object {
        const val PAGE_LIMIT = 30

        fun tvTypeOf(format: String?): TvType = when (format?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL", "MUSIC" -> TvType.OVA
            else -> TvType.Anime
        }

        fun showStatusOf(status: String?): ShowStatus? = when (status?.uppercase()) {
            null -> null
            "RELEASING", "CURRENTLY AIRING" -> ShowStatus.Ongoing
            "FINISHED", "FINISHED AIRING", "NOT_YET_RELEASED" -> ShowStatus.Completed
            else -> null
        }

        // the site sends minutes as a number for some titles and as "80 min"
        // for others, the response wants plain minutes
        fun minutesOf(duration: Any?): Int? = when (duration) {
            is Int -> duration.takeIf { it > 0 }
            is String -> Regex("(\\d+)").find(duration)?.groupValues?.get(1)?.toIntOrNull()
            else -> null
        }

        fun currentSeason(): String = when (Calendar.getInstance().get(Calendar.MONTH)) {
            11, 0, 1 -> "winter"
            2, 3, 4 -> "spring"
            5, 6, 7 -> "summer"
            else -> "fall"
        }

        fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)
    }

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "season" to "Airing This Season",
        "year" to "New This Year"
    )

    private fun browseResponse(item: BrowseItem): SearchResponse? {
        val id = item.anilistId?.toIntOrNull() ?: return null
        val name = item.title?.takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(name, id.toString(), tvTypeOf(item.type)) {
            posterUrl = CnoApi.animeArt(item.poster)
            year = item.year
            addDubStatus(
                dubExist = (item.dubCount ?: 0) > 0 || item.hasDub == true,
                subExist = (item.subCount ?: 0) > 0 || item.hasDub == false
            )
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        CnoApi.refreshDomains()
        mainUrl = CnoApi.beta()
        if (page > PAGE_LIMIT) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val response = when (request.data) {
            "season" -> CnoApi.browseAnime(null, page, season = currentSeason(), year = currentYear())
            "year" -> CnoApi.browseAnime(null, page, year = currentYear())
            else -> CnoApi.browseAnime(null, page)
        } ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = response.items.mapNotNull { browseResponse(it) }
        return newHomePageResponse(request.name, items, hasNext = response.hasNextPage == true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        CnoApi.refreshDomains()
        mainUrl = CnoApi.beta()
        val first = CnoApi.browseAnime(query, 1) ?: return emptyList()
        val results = first.items.toMutableList()
        if (first.hasNextPage == true) {
            CnoApi.browseAnime(query, 2)?.let { results.addAll(it.items) }
        }
        return results.mapNotNull { browseResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        CnoApi.refreshDomains()
        mainUrl = CnoApi.beta()
        val anilistId = url.toIntOrNull() ?: return null
        val detail = CnoApi.animeDetail(anilistId) ?: return null
        val title = detail.title?.takeIf { it.isNotBlank() } ?: return null

        // the stream folders on the edge are keyed by the anilist id plus the
        // slug carried in the watch route, and the slug is not guessable
        val slug = CnoApi.slugOf(detail.routeId) ?: return null
        val idNumber = detail.anilistId?.toIntOrNull() ?: anilistId

        fun episodeData(number: Int): String = "$idNumber|$number|$slug"

        fun buildEpisodes(dub: Boolean): List<Episode> {
            return detail.episodes.mapNotNull { ep ->
                val number = ep.number ?: return@mapNotNull null
                if (number < 1) return@mapNotNull null
                // episodes riding a third party player cannot be played here
                if (ep.embedId != null) return@mapNotNull null
                val wanted = if (dub) ep.dub == true else ep.sub == true
                if (!wanted) return@mapNotNull null
                newEpisode(episodeData(number)) {
                    this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $number"
                    this.episode = number
                    this.description = ep.description?.takeIf { it.isNotBlank() }
                        ?: ep.aired?.takeIf { it.isNotBlank() }?.let { "Aired $it" }
                    this.posterUrl = CnoApi.animeArt(ep.thumbnail)
                    ep.runtimeSeconds?.takeIf { it > 0 }?.let { this.runTime = it / 60 }
                }
            }
        }

        val subEpisodes = buildEpisodes(dub = false)
        val dubEpisodes = buildEpisodes(dub = true)
        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) return null

        return newAnimeLoadResponse(title, url, tvTypeOf(detail.type)) {
            posterUrl = CnoApi.animeArt(detail.poster)
            backgroundPosterUrl = CnoApi.animeArt(detail.banner)
            year = detail.year
            plot = detail.synopsis
            tags = detail.genres
            duration = minutesOf(detail.duration)
            contentRating = detail.rating?.takeIf { it.isNotBlank() }
            detail.score?.takeIf { it > 0 }?.let { score = Score.from10(it.toString()) }
            showStatusOf(detail.status)?.let { this.showStatus = it }
            addAniListId(idNumber)
            detail.malId?.toIntOrNull()?.let { addMalId(it) }
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
        if (parts.size < 3) return false
        val anilistId = parts[0].toIntOrNull() ?: return false
        val episode = parts[1].toIntOrNull() ?: return false
        val slug = parts[2].takeIf { it.isNotBlank() } ?: return false

        val masterUrl = CnoApi.animeMasterUrl(anilistId, slug, episode)

        // one retry: the edge has been seen answering a first hit with a
        // truncated playlist before serving the real one
        var readable: CnoApi.Readable? = null
        for (attempt in 0 until 2) {
            val candidate = CnoApi.readable(masterUrl, "${CnoApi.beta()}/", accept = "*/*")
            if (candidate != null && candidate.body.startsWith("#EXTM3U")) {
                readable = candidate
                break
            }
        }
        val screen = readable ?: return false

        // the master carries an audio rendition per language and a subtitle
        // rendition per track, one link plays every one of them and the
        // audio is picked inside the player exactly like the site's picker
        val linkUrl = if (screen.plainOk) {
            masterUrl
        } else {
            // only the browser stack got through, the player cannot walk the
            // guarded playlist tree itself so it is served from the local
            // proxy while the segments stay on the open cdn
            CnoProxy.linkFor(masterUrl) ?: return false
        }
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name - Multi Audio",
                url = linkUrl,
                type = ExtractorLinkType.M3U8
            ) {
                // the tree refuses plain user agents outright, the headers
                // ride the master, every rendition and the segments
                headers = CnoApi.streamHeaders()
            }
        )
        return true
    }
}
