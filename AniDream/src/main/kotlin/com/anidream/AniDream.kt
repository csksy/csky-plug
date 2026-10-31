package com.anidream

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AniDream : MainAPI() {

    override var mainUrl = AniDreamApi.url()
    override var name = "AniDream"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "popularity" to "All Time Popular",
        "score" to "Top Rated",
        "newest" to "Recently Added",
        "movie" to "Anime Movies"
    )

    private data class EpisodeRef(
        val id: Int,
        val ep: Int,
        val dub: Boolean
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        DonationManager.checkAndShow()
        AniDreamApi.refreshDomain()
        mainUrl = AniDreamApi.url()

        val (items, hasNext) = when (request.data) {
            "movie" -> AniDreamApi.catalog("popularity", page, format = "MOVIE")
            else -> AniDreamApi.catalog(request.data, page)
        }
        return newHomePageResponse(
            request.name,
            items.mapNotNull { it.toSearchResponse() },
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        AniDreamApi.refreshDomain()
        mainUrl = AniDreamApi.url()
        return AniDreamApi.search(query).mapNotNull { it.toSearchResponse() }
    }

    private fun AniDreamApi.MediaItem.toSearchResponse(): SearchResponse? {
        val title = displayTitle() ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id") {
            this.posterUrl = poster
            this.year = year
            this.otherName = romaji
            addDubStatus(dubExist = dubCount > 0, subExist = subCount > 0)
            score?.let { score = Score.from100(it.toDouble()) }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        AniDreamApi.refreshDomain()
        mainUrl = AniDreamApi.url()

        val id = url.substringAfterLast("/").toIntOrNull() ?: return null
        val anime = AniDreamApi.detail(id) ?: return null
        val title = anime.displayTitle() ?: return null
        val eps = AniDreamApi.episodes(id)

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        for (ep in eps) {
            if (ep.number <= 0) continue
            if (ep.hasSub) subEps.add(ep.toEpisode(id, dub = false))
            if (ep.hasDub) dubEps.add(ep.toEpisode(id, dub = true))
        }
        // not aired yet or only raws listed, still let the user try sub
        if (subEps.isEmpty() && dubEps.isEmpty()) {
            for (ep in eps) {
                if (ep.number > 0) subEps.add(ep.toEpisode(id, dub = false))
            }
        }

        val showStatus = when (anime.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }
        // a movie with both tracks stays an anime so the sub and dub lists
        // keep their switcher
        val type = when {
            anime.format == "MOVIE" && dubEps.isNotEmpty() -> TvType.Anime
            anime.format == "MOVIE" -> TvType.AnimeMovie
            anime.format == "OVA" || anime.format == "ONA" || anime.format == "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(title, "$mainUrl/anime/$id", type) {
            this.engName = anime.english
            this.japName = anime.romaji ?: anime.native
            this.posterUrl = anime.poster
            this.backgroundPosterUrl = anime.banner
            this.year = anime.year
            this.showStatus = showStatus
            this.plot = anime.description?.let(::stripHtml)
            this.tags = anime.genres
            this.duration = anime.duration
            this.score = anime.score?.let { Score.from100(it.toDouble()) }
            addAniListId(anime.id)
            if (anime.malId > 0) addMalId(anime.malId)
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    private fun AniDreamApi.EpisodeItem.toEpisode(id: Int, dub: Boolean): Episode {
        val ref = EpisodeRef(id = id, ep = number, dub = dub)
        val label = displayTitle() ?: "Episode $number"
        return newEpisode(ref.toJson()) {
            this.name = if (isFiller) "$label (Filler)" else label
            this.episode = number
            this.posterUrl = thumbnail?.takeIf { it.startsWith("http") }
            parseAirDate(airedAt)?.let { addDate(it) }
        }
    }

    private fun parseAirDate(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(raw.substringBefore("T").take(10))
        } catch (_: Exception) {
            null
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("\\x3Cbr>", "\n", ignoreCase = true)
            .trim()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = try {
            parseJson<EpisodeRef>(data)
        } catch (_: Exception) {
            null
        } ?: return false
        if (ref.id <= 0 || ref.ep <= 0) return false

        val stream = AniDreamApi.stream(ref.id, ref.ep, ref.dub) ?: return false
        val registered = AniDreamProxy.register(
            masterUrl = stream.streamUrl,
            dub = ref.dub,
            subtitles = stream.subtitles,
            refresher = { AniDreamApi.stream(ref.id, ref.ep, ref.dub)?.streamUrl }
        ) ?: return false

        val trackName = if (ref.dub) "Dub" else "Sub"
        val label = "$name $trackName"
        callback.invoke(
            newExtractorLink(label, label, registered.url, type = ExtractorLinkType.M3U8) {
                quality = qualityFor(registered.height)
                headers = AniDreamApi.cdnHeaders
            }
        )

        // the default track first so players that auto pick the first one
        // land on the intended subtitles
        val ordered = stream.subtitles.withIndex()
            .sortedByDescending { it.value.isDefault }
        for ((index, sub) in ordered) {
            val display = sub.label.ifBlank { "English" }
            subtitleCallback.invoke(
                newSubtitleFile(display, "${registered.url.substringBeforeLast("/")}/vtt/$index.vtt") {
                    headers = AniDreamApi.cdnHeaders
                }
            )
        }
        return true
    }

    private fun qualityFor(height: Int): Int = when {
        height >= 2160 -> Qualities.P2160.value
        height >= 1080 -> Qualities.P1080.value
        height >= 720 -> Qualities.P720.value
        height >= 480 -> Qualities.P480.value
        else -> Qualities.P360.value
    }
}
