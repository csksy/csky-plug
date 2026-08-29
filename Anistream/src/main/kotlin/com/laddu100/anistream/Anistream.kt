package com.laddu100.anistream

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
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
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException

/**
 * Anistream.one — anime with sub & dub, real episode titles, 10 servers.
 *
 * Provider families:
 *  - API servers (per-episode, from /rest/api/servers): beep mimi yuki neko
 *    kiwi sora (+ hidden wave fallback)
 *  - Client-injected (mirrors the site frontend's own rules):
 *      minky (megaplay embed, when yuki present)
 *      zen   (flixcloud embed, sub-only, always)
 *      hawk  (vidhawk embed, sub + dub, always)
 *
 * Names carry the softsub/hardsub tag from the site's own provider tips:
 * neko/kiwi = hardsub, everything else = softsub.
 */
class Anistream : MainAPI() {

    override var mainUrl = AnistreamApi.MAIN_URL
    override var name = "Anistream"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "recent" to "Recently Added",
        "trending" to "Trending Now",
        "updated" to "Recently Updated",
        "season" to "Popular This Season",
        "movies" to "Popular Movies",
        "upcoming" to "Upcoming Soon",
        "popular" to "All-Time Popular",
        "favourites" to "All-Time Favourites",
    )

    companion object {
        private const val TAG = "Anistream"

        /** Static provider metadata: display name + sub burn-in type (from site tips). */
        private val PROVIDER_META = mapOf(
            "beep" to ("Beep" to "Soft"),
            "mimi" to ("Mimi" to "Soft"),
            "yuki" to ("Yuki" to "Soft"),
            "neko" to ("Neko" to "Hard"),
            "kiwi" to ("Kiwi" to "Hard"),
            "sora" to ("Sora" to "Soft"),
            "wave" to ("Wave" to "Soft"),
            "minky" to ("Minky" to "Soft"),
            "zen" to ("Zen" to "Soft"),
            "hawk" to ("Hawk" to "Soft"),
        )

        /** Extra fallback API providers tried even when not listed for the episode. */
        private val FALLBACK_PROVIDERS = listOf("wave")

        private fun providerLabel(id: String): String {
            val (disp, kind) = PROVIDER_META[id] ?: (id.replaceFirstChar { it.uppercase() } to "Soft")
            return "$disp ($kind)"
        }
    }

    // ------------------------------------------------------------- mainPage

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items: List<SearchResponse> = when (request.name) {
            "recent" -> {
                val recent = AnistreamApi.recent(page) ?: return null
                recent.results.mapNotNull { it ->
                    val slug = it.id ?: return@mapNotNull null
                    newAnimeSearchResponse(it.titleEnglish ?: it.titleRomaji ?: slug, "$mainUrl/anime/$slug") {
                        this.posterUrl = it.coverImage?.best
                        this.year = null
                        this.score = it.meanScore?.let { s -> Score.from10((s / 10.0).toString()) }
                        addDubStatus(dubExist = it.isDub == true, subExist = it.isSub == true)
                        this.otherName = "EP ${it.episode ?: "?"}"
                    }
                }
            }
            "trending" -> catalog(
                """{ includeAdult: false, statusIn: ["RELEASING"] }""",
                """{ field: TRENDING, direction: DESC }""", page
            )
            "updated" -> catalog(
                """{ includeAdult: false, statusIn: ["RELEASING"] }""",
                """{ field: UPDATED_AT, direction: DESC }""", page
            )
            "season" -> catalog(
                """{ includeAdult: false, statusIn: ["RELEASING"], seasonIn: ["${currentSeason()}"], seasonYearMin: ${currentYear()}, seasonYearMax: ${currentYear()} }""",
                """{ field: POPULARITY, direction: DESC }""", page
            )
            "movies" -> catalog(
                """{ includeAdult: false, statusIn: ["FINISHED"], formatIn: ["MOVIE"] }""",
                """{ field: POPULARITY, direction: DESC }""", page
            )
            "upcoming" -> catalog(
                """{ includeAdult: false, statusIn: ["NOT_YET_RELEASED"] }""",
                """{ field: POPULARITY, direction: DESC }""", page
            )
            "popular" -> catalog(
                """{ includeAdult: false, statusIn: ["FINISHED"] }""",
                """{ field: POPULARITY, direction: DESC }""", page
            )
            "favourites" -> catalog(
                """{ includeAdult: false }""",
                """{ field: FAVOURITES, direction: DESC }""", page
            )
            else -> return null
        }
        return newHomePageResponse(request.name, items, hasNext = items.size >= 30)
    }

    private suspend fun catalog(filter: String, sort: String, page: Int): List<SearchResponse> {
        val offset = (page - 1) * 30
        return AnistreamApi.catalogAnime(filter, sort, offset, 30).mapNotNull { node ->
            val slug = node.id ?: return@mapNotNull null
            newAnimeSearchResponse(node.displayTitle, "$mainUrl/anime/$slug") {
                this.posterUrl = node.coverImage?.best
                this.year = node.seasonYear
                this.score = node.averageScore?.let { Score.from10((it / 10.0).toString()) }
                addDubStatus(dubExist = (node.dubCount ?: 0) > 0, subExist = (node.subCount ?: 0) > 0)
                this.otherName = node.format
            }
        }
    }

    private fun currentSeason(): String {
        return when (java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)) {
            in 0..2 -> "WINTER"
            in 3..5 -> "SPRING"
            in 6..8 -> "SUMMER"
            else -> "FALL"
        }
    }

    private fun currentYear(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    // --------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        return AnistreamApi.searchAnime(query).mapNotNull { node ->
            val slug = node.id ?: return@mapNotNull null
            newAnimeSearchResponse(node.displayTitle, "$mainUrl/anime/$slug") {
                this.posterUrl = node.coverImage?.best
                this.year = node.seasonYear
                this.score = node.averageScore?.let { Score.from10((it / 10.0).toString()) }
                addDubStatus(dubExist = (node.dubCount ?: 0) > 0, subExist = (node.subCount ?: 0) > 0)
                this.otherName = node.format
            }
        }
    }

    // ----------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/").substringBefore("?")
        val detail = AnistreamApi.animeDetail(slug) ?: return null
        var eps = AnistreamApi.episodes(slug)

        // Movie / empty fallback: synthesize a single complete episode from sub/dub counts.
        if (eps.isEmpty()) {
            val hasSub = (detail.subCount ?: 0) > 0 || detail.dubCount == null
            val hasDub = (detail.dubCount ?: 0) > 0
            eps = listOf(
                EpisodeItem(
                    number = 1,
                    titles = mapOf("en" to "Complete Movie"),
                    hasSub = hasSub,
                    hasDub = hasDub
                )
            )
        }

        // MOVIE maps to TvType.Anime on purpose: CloudStream hides the Sub/Dub
        // selector for isMovieType, which would force sub for every movie
        // (same trick AniKuro uses).
        val tvType = when (detail.format?.uppercase()) {
            "OVA", "ONA", "SPECIAL", "MUSIC" -> TvType.OVA
            else -> TvType.Anime
        }

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        for (ep in eps) {
            val num = ep.number ?: continue
            val title = ep.epTitle ?: "Episode $num"
            val airDate = parseAirDate(ep.airDateUtc)
            val filler = if (ep.isFiller == true) "\n\n[Filler]" else ""

            val builder: (Episode).() -> Unit = {
                this.name = title
                this.episode = num
                this.posterUrl = ep.img
                this.description = ep.description?.let { it + filler } ?: filler.trim().ifBlank { null }
                airDate?.let { this.addDate(it) }
            }
            if (ep.hasSub != false) {
                subEps.add(
                    newEpisode(
                        LinkData(
                            slug = slug,
                            anilistId = detail.anilistId,
                            malId = detail.malId,
                            ep = num,
                            variant = "sub",
                            title = detail.displayTitle
                        ).toJson(),
                        builder
                    )
                )
            }
            if (ep.hasDub == true) {
                dubEps.add(
                    newEpisode(
                        LinkData(
                            slug = slug,
                            anilistId = detail.anilistId,
                            malId = detail.malId,
                            ep = num,
                            variant = "dub",
                            title = detail.displayTitle
                        ).toJson(),
                        builder
                    )
                )
            }
        }
        if (subEps.isEmpty() && dubEps.isEmpty()) return null

        return newAnimeLoadResponse(detail.displayTitle, url, tvType) {
            this.posterUrl = detail.coverImage?.best
            this.backgroundPosterUrl = detail.bannerImage
            this.plot = detail.description
            this.tags = detail.genres
            this.year = detail.seasonYear
            this.showStatus = when (detail.status?.uppercase()) {
                "RELEASING" -> ShowStatus.Ongoing
                "FINISHED" -> ShowStatus.Completed
                else -> null
            }
            this.score = detail.averageScore?.let { Score.from10((it / 10.0).toString()) }
            this.duration = detail.duration
            addMalId(detail.malId?.takeIf { it > 0 })
            addAniListId(detail.anilistId?.takeIf { it > 0 })
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    private fun parseAirDate(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(raw.substringBefore("T").take(10))
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = try {
            parseJson<LinkData>(data)
        } catch (e: Exception) {
            return false
        }
        val audio = if (link.variant == "dub") "dub" else "sub"

        // 1) live server list for this episode/variant
        val servers = AnistreamApi.servers(link.slug, link.ep)
        val apiProviders = (if (audio == "dub") servers?.dubProviders else servers?.subProviders)
            .orEmpty()
            .mapNotNull { it.id?.lowercase() }
            .filter { it !in setOf("minky", "zen", "hawk") }   // injected ones handled below
            .toMutableSet()

        // 2) mirror the site frontend's injection rules (from its player bundle):
        //    minky when yuki present for that variant; zen always for sub; hawk always.
        val yukiPresent = apiProviders.contains("yuki")
        val anilistId = link.anilistId
        if (anilistId != null && anilistId > 0) {
            if (yukiPresent) apiProviders.add("minky")
            if (audio == "sub") apiProviders.add("zen")
            apiProviders.add("hawk")
        }
        // 3) hidden fallbacks that still answer the sources API
        FALLBACK_PROVIDERS.forEach { apiProviders.add(it) }

        val seenUrls = HashSet<String>()
        val seenSubs = HashSet<String>()

        coroutineScope {
            apiProviders.toList().amap { providerId ->
                async {
                    try {
                        resolveProvider(providerId, link, audio, seenUrls, seenSubs, subtitleCallback, callback)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // isolated failure — never kill the whole list
                        false
                    }
                }
            }
        }
        return true
    }

    /** Resolve ONE provider and emit its links + subtitles. */
    private suspend fun resolveProvider(
        providerId: String,
        link: LinkData,
        audio: String,
        seenUrls: HashSet<String>,
        seenSubs: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val label = providerLabel(providerId)
        when (providerId) {
            "minky" -> {
                val anilistId = link.anilistId ?: return
                val res = MegaplayResolver.resolve(anilistId, link.ep, audio) ?: return
                emitSingle(
                    label, res.m3u8,
                    mapOf("Referer" to "${MegaplayResolver.MAIN_URL}/"),
                    seenUrls, callback
                )
                res.tracks.forEach { track ->
                    val u = track.bestUrl?.takeIf { it.startsWith("http") } ?: return@forEach
                    if (track.kind == "captions" || track.kind == "subtitles") {
                        if (seenSubs.add(u)) {
                            subtitleCallback(
                                newSubtitleFile(track.label ?: "Subtitle", u) {
                                    this.headers = mapOf("Referer" to "${MegaplayResolver.MAIN_URL}/")
                                }
                            )
                        }
                    }
                }
            }
            "hawk" -> {
                val anilistId = link.anilistId ?: return
                val res = VidhawkResolver.resolve(anilistId, link.ep, audio) ?: return
                val track = res.trackFor(audio) ?: return
                emitSingle(label, track.src, mapOf("Origin" to VidhawkResolver.MAIN_URL), seenUrls, callback)
                // captions for the chosen audio variant
                res.captions[audio].orEmpty().forEach { cap ->
                    val u = cap.src ?: return@forEach
                    if (!u.startsWith("http") || !seenSubs.add(u)) return@forEach
                    subtitleCallback(
                        newSubtitleFile(cap.label ?: "English", u) {
                            this.headers = mapOf("Origin" to VidhawkResolver.MAIN_URL)
                        }
                    )
                }
            }
            "zen" -> {
                val anilistId = link.anilistId ?: return
                val watchSlug = AnistreamApi.watchSlug(link.title, anilistId, link.ep)
                val lookup = AnistreamApi.flixcloudLookup(watchSlug, anilistId, link.ep) ?: return
                val playerUrl = lookup.playerUrl?.takeIf { it.isNotBlank() } ?: return
                val res = FlixcloudResolver.resolve(playerUrl) ?: return
                emitSingle(label, res.m3u8, mapOf("Referer" to "https://flixcloud.cc/"), seenUrls, callback)
                res.subtitles.forEach { sub ->
                    val u = sub.bestUrl ?: return@forEach
                    if (!u.startsWith("http") || !seenSubs.add(u)) return@forEach
                    // prefer srt tracks (CloudStream player renders srt/vtt natively)
                    val name = sub.language ?: "Subtitle"
                    subtitleCallback(newSubtitleFile(name, u) {})
                }
            }
            else -> {
                // API-backed provider: beep mimi yuki neko kiwi sora wave
                val env = AnistreamApi.sources(link.slug, link.ep, audio, providerId) ?: return
                val headers = env.headers.orEmpty()
                env.sources.forEach { src ->
                    val u = src.url?.takeIf { it.startsWith("http") } ?: return@forEach
                    // fix the site's own "https:///host" triple-slash bug on sora subtitle hosts
                    val fixed = if (u.startsWith("https:///")) "https://" + u.removePrefix("https:///") else u
                    if (!seenUrls.add(fixed)) return@forEach
                    val quality = when {
                        src.quality != null && src.quality != "auto" -> src.quality
                        else -> "Auto"
                    }
                    callback.invoke(
                        newExtractorLink("$label · $audio", "$label · $audio · $quality", fixed, type = ExtractorLinkType.M3U8) {
                            if (headers.isNotEmpty()) this.headers = headers
                        }
                    )
                }
                env.tracks.orEmpty().forEach { track ->
                    val u = track.bestUrl?.takeIf { it.startsWith("http") } ?: return@forEach
                    // same triple-slash fix (sora srt)
                    val fixed = if (u.startsWith("https:///")) "https://" + u.removePrefix("https:///") else u
                    if (!seenSubs.add(fixed)) return@forEach
                    val name = track.label
                        ?: track.lang?.takeIf { it.isNotBlank() }
                        ?: "Subtitle"
                    subtitleCallback(
                        newSubtitleFile(name, fixed) {
                            if (headers.isNotEmpty()) this.headers = headers
                        }
                    )
                }
            }
        }
    }

    /** Emit one m3u8 (auto quality label), with optional per-quality variants. */
    private suspend fun emitSingle(
        label: String,
        url: String?,
        headers: Map<String, String>,
        seenUrls: HashSet<String>,
        callback: (ExtractorLink) -> Unit,
        skipSeen: Boolean = false
    ) {
        if (url.isNullOrBlank() || !url.startsWith("http")) return
        if (!skipSeen && !seenUrls.add(url)) return
        try {
            generateM3u8(
                label,
                url,
                headers.getOrDefault("Referer", mainUrl),
                headers = headers
            ).forEach(callback)
        } catch (e: Exception) {
            callback.invoke(
                newExtractorLink(label, "$label · auto", url, type = ExtractorLinkType.M3U8) {
                    if (headers.isNotEmpty()) this.headers = headers
                }
            )
        }
    }
}
