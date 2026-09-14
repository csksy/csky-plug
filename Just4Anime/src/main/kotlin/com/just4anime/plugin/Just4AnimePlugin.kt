package com.just4anime.plugin

import com.lagradost.api.Log
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
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

@CloudstreamPlugin
class Just4AnimePlugin : Plugin()

/**
 * CloudStream provider for https://just4anime.online/
 *
 * Architecture mirrors the site itself:
 *  - catalog/search  -> just4anime.online/api (AniList-backed, advanced-search)
 *  - episode titles  -> just4anime.online/api/episodes/{anilistId} (real TMDB titles)
 *  - server list     -> api.just4anime.online/api/v1/meta/availability/{id}/servers
 *  - streams         -> api.just4anime.online/api/v1/meta/sources/{id}?provider=&num=&type=&providerAnimeId=
 *
 * EVERY provider the site exposes is probed for EVERY episode. Providers the
 * availability endpoint misses are blind-probed without providerAnimeId (the
 * backend resolves it itself), so no source is ever skipped - dead servers
 * simply contribute nothing until they recover.
 *
 * Sub / Dub / Hardsub separation:
 *  - episode lists are split into Subbed (sub + hardsub links) and Dubbed tabs
 *  - every link is labeled "just4anime <Provider> <Sub|Dub|Hardsub>"
 */
class Just4Anime : MainAPI() {

    override var mainUrl = "https://just4anime.online"
    override var name = "just4anime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "TRENDING_DESC" to "Trending Now",
        "POPULARITY_DESC" to "Most Popular",
        "SCORE_DESC" to "Top Rated",
        "START_DATE_DESC" to "New Releases"
    )

    companion object {
        private const val TAG = "just4anime"

        /**
         * Every provider the site UI exposes, in the site's own priority order.
         * Keys are provider codes, values are the pretty names used in link
         * labels. Availability-discovered providers not in this map still work
         * (fallback label = displayName/code).
         */
        private val PROVIDERS = linkedMapOf(
            "jin" to "Jin (MegaPlay)",
            "sen" to "Sen (Senshi)",
            "aoi" to "Aoi (AllAnime)",
            "dio" to "Dio (AniDB)",
            "meg" to "Meg (MegaVid)",
            "mai" to "Mai (AniNeko Otaku)",
            "sai" to "Sai (AniNeko StreamHG)",
            "rin" to "Rin (ZokoAnime)",
            "kai" to "Kai (AniNeko HD-1)",
            "zeke" to "Zeke (AniNeko HD-2)",
            "ryuk" to "Ryuk (AnimeGG)",
            "echo" to "Echo (AnimixPlay)"
        )
    }

    // ------------------------------------------------------------------ pages

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val data = Just4AnimeApi.search(query = "", page = page, sort = request.data) ?: return null
        val results = data.results.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, results, hasNext = data.hasNextPage == true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        var page = 1
        while (page <= 3) {
            val data = Just4AnimeApi.search(query = query, page = page, sort = "POPULARITY_DESC")
                ?: break
            if (data.results.isEmpty()) break
            out += data.results.mapNotNull { it.toSearchResponse() }
            if (data.hasNextPage != true) break
            page++
        }
        return out
    }

    private fun J4ASearchResult.toSearchResponse(): SearchResponse? {
        val anilistId = id ?: return null
        val title = title?.userPreferred
            ?: title?.english
            ?: title?.romaji
            ?: title?.native
            ?: "Unknown"
        return newAnimeSearchResponse(title, anilistId, TvType.Anime) {
            this.posterUrl = image ?: cover
            this.year = releaseDate?.take(4)?.toIntOrNull()
            rating?.let { raw ->
                raw.toFloatOrNull()?.let { value ->
                    val ten = if (value > 10f) value / 10f else value
                    this.score = Score.from10(ten.toString())
                }
            }
            addDubStatus(dubExist = true, subExist = true)
        }
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = url.trim()
            .removePrefix(mainUrl)
            .trim('/')
            .substringBefore('?')
            .trim()
        if (anilistId.isBlank()) return null

        val meta = Just4AnimeApi.episodes(anilistId) ?: return null

        val tvType = when (meta.format?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        // Real TMDB episode titles; drop unaired entries when flagged.
        val rawEps = meta.episodes
            .filter { (it.number ?: 0) > 0 && it.hasAired != false }
            .sortedBy { it.number }

        val synthesizedCount = meta.currentEpisode
            ?: meta.totalEpisodes
            ?: meta.anilistEpisodeCount
            ?: 0

        fun buildEpisodes(category: String): List<Episode> {
            return if (rawEps.isNotEmpty()) {
                rawEps.map { ep ->
                    val num = ep.number!!
                    newEpisode("$anilistId|$num|$category") {
                        this.name = buildString {
                            append(ep.title?.takeIf { it.isNotBlank() } ?: "Episode $num")
                            if (ep.isFiller == true) append(" (Filler)")
                        }
                        this.episode = num
                        this.description = ep.description?.takeIf { it.isNotBlank() }
                        this.posterUrl = ep.image?.takeIf { it.isNotBlank() }
                    }
                }
            } else {
                (1..synthesizedCount).map { num ->
                    newEpisode("$anilistId|$num|$category") {
                        this.name = "Episode $num"
                        this.episode = num
                    }
                }
            }
        }

        val title = meta.title
            ?: meta.titleRomaji
            ?: "Anime"

        val poster = meta.images?.firstOrNull { it.coverType.equals("Poster", true) }?.url
            ?: meta.images?.firstOrNull { !it.url.isNullOrBlank() }?.url
        val banner = meta.images?.firstOrNull {
            it.coverType.equals("Banner", true) || it.coverType.equals("Fanart", true)
        }?.url

        val subEpisodes = buildEpisodes("sub")
        val dubEpisodes = buildEpisodes("dub")

        // Dub tab is always offered - availability discovery is time-boxed and
        // regularly misses providers (sen/dio/echo/ryuk/aoi), so gating the dub
        // list on it would hide real dub sources. Episodes without dub simply
        // resolve no links, exactly like the website's own server dropdown.
        return newAnimeLoadResponse(title, anilistId, tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.year = meta.year
            this.plot = meta.description?.replace(Regex("<[^>]*>"), "")?.trim()
            this.tags = (meta.genres.orEmpty()) + (meta.studios.orEmpty())
            this.showStatus = when (meta.status?.uppercase()) {
                "RELEASING", "NOT_YET_RELEASED", "AIRING" -> ShowStatus.Ongoing
                "FINISHED", "COMPLETED", "CANCELLED", "DISCONTINUED" -> ShowStatus.Completed
                else -> null
            }
            anilistId.toIntOrNull()?.let { addAniListId(it) }
            meta.malId?.let { addMalId(it) }
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    // ------------------------------------------------------------- loadLinks

    private data class Probe(
        val code: String,
        val pretty: String,
        val type: String,
        val providerAnimeId: String?
    )

    /**
     * Build the full probe list for one episode. Known servers contribute
     * their availability-reported types; every other site provider is probed
     * blind (sub + hsub for the sub tab, dub for the dub tab).
     */
    private fun buildProbes(category: String, servers: Map<String, J4AServer>): List<Probe> {
        val probes = mutableListOf<Probe>()
        for ((code, pretty) in PROVIDERS) {
            val srv = servers[code]
            if (srv != null) {
                val wanted = srv.types
                    .map { raw ->
                        val t = raw.trim().lowercase()
                        if (t == "h-sub" || t == "hardsub") "hsub" else t
                    }
                    .filter { t ->
                        if (category == "dub") t == "dub"
                        else t == "sub" || t == "hsub"
                    }
                    // keep "sub" first so the Sub label wins URL dedup against hsub twins
                    .sortedBy { it != "sub" }
                for (t in wanted) {
                    probes.add(
                        Probe(
                            code = code,
                            pretty = pretty,
                            type = t,
                            providerAnimeId = srv.animeId?.takeIf { it.isNotBlank() }
                        )
                    )
                }
            } else {
                if (category == "dub") {
                    probes.add(Probe(code, pretty, "dub", null))
                } else {
                    probes.add(Probe(code, pretty, "sub", null))
                    probes.add(Probe(code, pretty, "hsub", null))
                }
            }
        }
        // providers discovered by availability but unknown to the static map
        for (srv in servers.values) {
            if (srv.code.isBlank() || PROVIDERS.containsKey(srv.code)) continue
            val pretty = srv.displayName?.takeIf { it.isNotBlank() } ?: srv.code
            val wanted = srv.types
                .map { raw -> val t = raw.trim().lowercase(); if (t == "h-sub") "hsub" else t }
                .filter { t -> if (category == "dub") t == "dub" else (t == "sub" || t == "hsub") }
            for (t in wanted) {
                probes.add(Probe(srv.code, pretty, t, srv.animeId?.takeIf { it.isNotBlank() }))
            }
        }
        return probes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size != 3) return false
        val anilistId = parts[0].trim()
        val num = parts[1].trim().toIntOrNull() ?: return false
        val category = parts[2].trim().lowercase()
        if (anilistId.isBlank() || (category != "sub" && category != "dub")) return false

        val servers = Just4AnimeApi.availability(anilistId).associateBy { it.code }
        val probes = buildProbes(category, servers)
        if (probes.isEmpty()) return false

        val seenUrls = ConcurrentHashMap.newKeySet<String>()
        val seenSubs = ConcurrentHashMap.newKeySet<String>()

        val results = coroutineScope {
            probes.map { probe ->
                async {
                    runProbe(probe, anilistId, num, category, seenUrls, seenSubs, subtitleCallback, callback)
                }
            }.awaitAll()
        }
        val found = results.any { it }
        Log.d(TAG, "loadLinks $anilistId ep$num [$category]: ${probes.size} probes -> $found")
        return found
    }

    private suspend fun runProbe(
        probe: Probe,
        anilistId: String,
        num: Int,
        category: String,
        seenUrls: MutableSet<String>,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val data = try {
            Just4AnimeApi.sources(anilistId, probe.code, num, probe.type, probe.providerAnimeId)
        } catch (e: Exception) {
            Log.d(TAG, "${probe.code}/${probe.type} sources error: ${e.message}")
            null
        } ?: return false

        if (data.sources.isEmpty()) return false

        // Trust the response over the request when labeling - some providers
        // (e.g. AnimeGG) ignore the type param and always return sub streams.
        val respType = (data.type ?: probe.type).lowercase()
        val label = when {
            respType.contains("hsub") || respType.contains("hard") -> "Hardsub"
            respType == "dub" || data.isDub == true -> "Dub"
            else -> "Sub"
        }
        // Never leak the wrong audio into a tab.
        if (category == "dub" && label != "Dub") return false
        if (category != "dub" && label == "Dub") return false

        var found = false

        // Soft subtitle tracks (proxied by the site, fetchable as-is).
        if (label != "Hardsub") {
            val allSubs = data.subtitles + data.sources.flatMap { it.subtitles ?: emptyList() }
            for (sub in allSubs) {
                val subUrl = sub.url ?: continue
                if (subUrl.isBlank() || !seenSubs.add(subUrl)) continue
                val lang = sub.language ?: sub.lang ?: "Subtitles"
                try {
                    subtitleCallback(SubtitleFile(lang, subUrl))
                } catch (e: Exception) {
                    Log.d(TAG, "subtitle emit failed: ${e.message}")
                }
            }
        }

        for (stream in data.sources) {
            val streamUrl = stream.url ?: continue
            if (streamUrl.isBlank() || !seenUrls.add(streamUrl)) continue

            val linkType = if (stream.isM3U8 == true) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val qualityInt = parseQuality(stream.quality)
            val qualityLabel = stream.quality
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("auto", true) }

            val linkName = buildString {
                append(Just4AnimeApi.PROVIDER_NAME)
                append(' ')
                append(probe.pretty)
                append(' ')
                append(label)
                if (qualityLabel != null) append(' ').append(qualityLabel)
            }

            val headers = (stream.headers ?: emptyMap())
                .plus("User-Agent" to Just4AnimeApi.USER_AGENT)

            try {
                callback(
                    newExtractorLink(
                        source = Just4AnimeApi.PROVIDER_NAME,
                        name = linkName,
                        url = streamUrl,
                        type = linkType
                    ) {
                        this.quality = qualityInt
                        this.headers = headers
                    }
                )
                found = true
            } catch (e: Exception) {
                Log.d(TAG, "link emit failed: ${e.message}")
            }
        }
        return found
    }

    private fun parseQuality(quality: String?): Int {
        if (quality.isNullOrBlank()) return -1
        Regex("(2160|1080|720|480|360|240)[pP]?").find(quality)?.groupValues?.get(1)
            ?.toIntOrNull()?.let { return it }
        if (quality.contains("4K", true)) return 2160
        return -1
    }
}
