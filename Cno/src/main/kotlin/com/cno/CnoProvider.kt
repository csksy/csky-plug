package com.cno

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager

class CnoProvider : MainAPI() {
    override var mainUrl = CnoApi.beta()
    override var name = "Cno"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private companion object {
        // shelf ids of the curated screen the site ships, every genre shelf
        // carries the same shape so only the interesting ones are surfaced
        val SHELVES = listOf(
            "dexter-new" to "Recently Added",
            "dexter-series" to "Series",
            "dexter-top10" to "Top 10 Films",
            "dexter-liked" to "Most Liked",
            "dexter-top-rated" to "Top Rated",
            "dexter-genre-28" to "Action",
            "dexter-genre-35" to "Comedy",
            "dexter-genre-16" to "Animation",
            "dexter-genre-878" to "Science Fiction",
            "dexter-genre-27" to "Horror",
            "dexter-genre-53" to "Thriller",
            "dexter-genre-18" to "Drama",
            "dexter-genre-80" to "Crime",
            "dexter-genre-10749" to "Romance",
            "dexter-classics" to "Classics"
        )

        fun qualityOf(height: Int): Int = when {
            height >= 2160 -> Qualities.P2160.value
            height >= 1440 -> Qualities.P1440.value
            height >= 1080 -> Qualities.P1080.value
            height >= 720 -> Qualities.P720.value
            height >= 480 -> Qualities.P480.value
            height >= 360 -> Qualities.P360.value
            else -> Qualities.P240.value
        }

        fun itemUrl(item: ScreenItem): String? {
            val id = item.id ?: return null
            return when (item.type) {
                "movie" -> "movie/$id"
                "tv" -> "tv/$id"
                else -> null
            }
        }
    }

    override val mainPage = mainPageOf(
        *SHELVES.map { (id, label) -> id to label }.toTypedArray()
    )

    private fun toItemResponse(item: ScreenItem): SearchResponse? {
        val url = itemUrl(item) ?: return null
        val title = item.title?.takeIf { it.isNotBlank() } ?: return null
        // titles the edge does not host itself only play through third party
        // embeds, showing them would just dead end in the player
        if (item.playable == false) return null
        val poster = CnoApi.artUrl(item.poster)
        return when (item.type) {
            "movie" -> newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = item.year
                item.rating?.takeIf { it > 0 }?.let { score = Score.from10(it.toString()) }
            }
            else -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = item.year
                item.rating?.takeIf { it > 0 }?.let { score = Score.from10(it.toString()) }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        CnoApi.refreshDomains()
        mainUrl = CnoApi.beta()
        val shelves = CnoApi.siteShelves()
        val shelf = shelves.firstOrNull { it.id == request.data }
        val items = shelf?.items.orEmpty().mapNotNull { toItemResponse(it) }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        CnoApi.refreshDomains()
        mainUrl = CnoApi.beta()
        return CnoApi.searchScreen(query, 1)?.results.orEmpty()
            .mapNotNull { toItemResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        CnoApi.refreshDomains()
        mainUrl = CnoApi.beta()
        val parts = url.split("/")
        val id = parts.getOrNull(1)?.toIntOrNull() ?: return null

        return when (parts.getOrNull(0)) {
            "movie" -> loadMovie(id, url)
            "tv" -> loadShow(id, url)
            else -> null
        }
    }

    private suspend fun loadMovie(id: Int, url: String): LoadResponse? {
        val detail = CnoApi.movieDetail(id) ?: return null
        val title = detail.title?.takeIf { it.isNotBlank() } ?: return null

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = CnoApi.artUrl(detail.poster)
            backgroundPosterUrl = CnoApi.artUrl(detail.backdrop, "w1280")
            year = detail.year
            plot = detail.overview?.takeIf { it.isNotBlank() }
            tags = detail.genres
            duration = detail.runtime?.takeIf { it > 0 }
            contentRating = detail.certification?.takeIf { it.isNotBlank() }
            detail.rating?.takeIf { it > 0 }?.let { score = Score.from10(it.toString()) }
            actors = detail.cast.mapNotNull { person ->
                person.name?.takeIf { it.isNotBlank() }?.let { name ->
                    ActorData(Actor(name, CnoApi.artUrl(person.photo, "w185")), null, person.role)
                }
            }
            recommendations = detail.recommendations.mapNotNull { item ->
                toItemResponse(item)
            }
        }
    }

    private suspend fun loadShow(id: Int, url: String): LoadResponse? {
        val detail = CnoApi.tvDetail(id) ?: return null
        val title = detail.title?.takeIf { it.isNotBlank() } ?: return null

        // season 0 holds specials which the edge only serves through third
        // party embeds, so they are left out instead of dead ending in the player
        val seasons = detail.seasonList
            .filter { (it.number ?: -1) > 0 && it.playable != false }
            .sortedBy { it.number ?: 0 }
        if (seasons.isEmpty()) return null

        val episodes = mutableListOf<Episode>()
        for (season in seasons) {
            val number = season.number ?: continue
            val response = CnoApi.seasonEpisodes(id, number) ?: continue
            for (ep in response.episodes) {
                val seasonNumber = ep.season ?: number
                val episodeNumber = ep.episode ?: continue
                if (ep.playable == false) continue
                episodes.add(
                    newEpisode("tv/$id|$seasonNumber|$episodeNumber") {
                        this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $episodeNumber"
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.description = ep.overview?.takeIf { it.isNotBlank() }
                        this.posterUrl = CnoApi.stillUrl(ep.still)
                    }
                )
            }
        }
        if (episodes.isEmpty()) return null

        val showStatus = when (detail.status?.lowercase()) {
            null -> null
            "returning series", "in production", "planned" -> ShowStatus.Ongoing
            "ended", "canceled", "released" -> ShowStatus.Completed
            else -> null
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = CnoApi.artUrl(detail.poster)
            backgroundPosterUrl = CnoApi.artUrl(detail.backdrop, "w1280")
            year = detail.year
            plot = detail.overview?.takeIf { it.isNotBlank() }
            tags = detail.genres
            duration = detail.runtime?.takeIf { it > 0 }
            contentRating = detail.certification?.takeIf { it.isNotBlank() }
            detail.rating?.takeIf { it > 0 }?.let { score = Score.from10(it.toString()) }
            this.showStatus = showStatus
            actors = detail.cast.mapNotNull { person ->
                person.name?.takeIf { it.isNotBlank() }?.let { name ->
                    ActorData(Actor(name, CnoApi.artUrl(person.photo, "w185")), null, person.role)
                }
            }
            recommendations = detail.recommendations.mapNotNull { item ->
                toItemResponse(item)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // movie links carry movie/{id}, episodes carry tv/{id}|{season}|{episode}
        val segments = data.split("|")
        val head = segments[0].split("/")
        val kind = head.getOrNull(0) ?: return false
        val id = head.getOrNull(1) ?: return false

        val masterPath: String
        val subsPath: String
        when (kind) {
            "movie" -> {
                masterPath = "/screen/master/movie/$id"
                subsPath = "movie/$id"
            }
            "tv" -> {
                val season = segments.getOrNull(1) ?: return false
                val episode = segments.getOrNull(2) ?: return false
                masterPath = "/screen/master/tv/$id/$season/$episode"
                subsPath = "tv/$id/$season/$episode"
            }
            else -> return false
        }

        val master = CnoApi.masterPlaylist(masterPath) ?: return false

        // shows with a separate audio rendition only play in sync from the full
        // master, splitting those per quality would hand the player silent video
        if (master.hasAudioRenditions || master.variants.isEmpty()) {
            val masterUrl = "${CnoApi.beta()}$masterPath"
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    // the playlists live on the guarded host, exoplayer needs
                    // the same clearance and user agent the plugin itself uses
                    headers = CnoApi.streamHeaders(masterUrl, "${CnoApi.beta()}/")
                }
            )
        } else {
            for (variant in master.variants) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = variant.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = qualityOf(variant.height)
                        headers = CnoApi.streamHeaders(variant.url, "${CnoApi.beta()}/")
                    }
                )
            }
        }

        emitSubtitles(subsPath, subtitleCallback)
        return true
    }

    private suspend fun emitSubtitles(
        path: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        // forced tracks are duplicates of the same language with only signs and
        // songs, the full track is the one worth listing
        val seenLangs = mutableSetOf<String>()
        for (file in CnoApi.subsList(path)) {
            val lang = file.lang?.takeIf { it.isNotBlank() } ?: continue
            if (file.forced == true) continue
            if (!seenLangs.add(lang)) continue
            val label = buildString {
                append(file.label ?: lang)
                if (file.hi == true) append(" [hi]")
            }
            try {
                val trackUrl = CnoApi.subTrackUrl(path, lang)
                subtitleCallback.invoke(
                    newSubtitleFile(label, trackUrl) {
                        // the files are served by the guarded host itself, the
                        // player fetches them directly so they carry the
                        // clearance and user agent along
                        headers = CnoApi.streamHeaders(trackUrl, "${CnoApi.beta()}/")
                    }
                )
            } catch (_: Exception) {
                // one broken track should not cost the whole list
            }
        }
    }
}
