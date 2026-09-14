package com.just4anime.plugin

import android.content.Context
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

@CloudstreamPlugin
class Just4AnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Just4Anime())
    }
}

/**
 * CloudStream provider for https://just4anime.online/
 *
 *  - catalog/search  -> just4anime.online/api/advanced-search (AniList-backed)
 *  - episode titles  -> just4anime.online/api/episodes/{anilistId} (real TMDB titles)
 *  - server list     -> api.just4anime.online/api/v1/meta/availability/{id}/servers
 *  - streams         -> api.just4anime.online/api/v1/meta/sources/{id}?provider=&num=&type=
 *
 * ALL 16 providers the API exposes are probed for every episode:
 * jin, sen, aoi, dio, meg, mai, sai, rin, kai, zeke, ryuk, echo,
 * aina, kiwi, ash, koto. Providers found in availability use their reported
 * types + providerAnimeId; all others are blind-probed (the backend resolves
 * the provider's own anime id itself).
 *
 * NOTE ON EPISODE DATA: CloudStream runs episode data through fixUrl(),
 * turning "21|2|dub" into "https://just4anime.online/21|2|dub". Every id is
 * therefore sanitized with substringAfterLast('/') before use - a bare
 * AniList id in the sources path answers in <1s, a mangled url makes the
 * backend hang ~50s per request (verified against the live API).
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

    /** Provider metadata: pretty label + types to blind-probe when availability
     *  does not list the provider (types from the API's own provider catalog). */
    private data class ProviderInfo(val pretty: String, val blindTypes: List<String>)

    companion object {
        private const val TAG = "just4anime"

        /** All 16 providers the site API exposes, in the site's priority order. */
        private val PROVIDERS = linkedMapOf(
            "jin" to ProviderInfo("Jin (MegaPlay)", listOf("sub", "dub")),
            "sen" to ProviderInfo("Sen (Senshi)", listOf("sub", "dub")),
            "aoi" to ProviderInfo("Aoi (AllAnime)", listOf("sub", "dub")),
            "dio" to ProviderInfo("Dio (AniDB)", listOf("sub", "dub")),
            "meg" to ProviderInfo("Meg (MegaVid)", listOf("hsub", "dub")),
            "mai" to ProviderInfo("Mai (AniNeko Otaku)", listOf("sub", "dub")),
            "sai" to ProviderInfo("Sai (AniNeko StreamHG)", listOf("sub", "dub")),
            "rin" to ProviderInfo("Rin (ZokoAnime)", listOf("sub", "dub")),
            "kai" to ProviderInfo("Kai (AniNeko HD-1)", listOf("sub", "dub")),
            "zeke" to ProviderInfo("Zeke (AniNeko HD-2)", listOf("sub", "dub")),
            "ryuk" to ProviderInfo("Ryuk (AnimeGG)", listOf("sub", "dub")),
            "echo" to ProviderInfo("Echo (AnimixPlay)", listOf("sub", "dub")),
            "aina" to ProviderInfo("Aina (AnimePahe)", listOf("sub")),
            "kiwi" to ProviderInfo("Kiwi (AnimePahe Kwik)", listOf("sub")),
            "ash" to ProviderInfo("Ash (AllAnime Player)", listOf("sub", "dub")),
            "koto" to ProviderInfo("Koto (Anikoto)", listOf("sub", "dub"))
        )

        /**
         * CloudStream's fixUrl() prepends mainUrl to episode data that does not
         * start with http, and the mangled form is what reaches loadLinks
         * (verified via user logcat). Extract the trailing path segment - the
         * bare AniList id - from either form.
         */
        fun sanitizeId(raw: String): String =
            raw.trim().substringBefore('?').trimEnd('/').substringAfterLast('/').trim()
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
        val anilistId = sanitizeId(url)
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

        val title = meta.title ?: meta.titleRomaji ?: "Anime"

        val poster = meta.images?.firstOrNull { it.coverType.equals("Poster", true) }?.url
            ?: meta.images?.firstOrNull { !it.url.isNullOrBlank() }?.url
        val banner = meta.images?.firstOrNull {
            it.coverType.equals("Banner", true) || it.coverType.equals("Fanart", true)
        }?.url

        val subEpisodes = buildEpisodes("sub")
        val dubEpisodes = buildEpisodes("dub")

        // Both tabs are always offered: availability discovery is time-boxed
        // server-side and regularly misses providers, so gating the dub list on
        // it would hide real dub sources (same behaviour as the site's own
        // server dropdown - an episode simply has no links when unavailable).
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

    private fun normalizeType(raw: String): String {
        val t = raw.trim().lowercase()
        return when {
            t == "h-sub" || t == "hardsub" || t == "hsub" -> "hsub"
            else -> t
        }
    }

    /**
     * Full probe list for one episode. Providers listed by availability use
     * their reported types (authoritative per anime); every other provider is
     * blind-probed using the catalog types. Filtered to the requested tab:
     * dub tab -> dub only, sub tab -> sub + hsub.
     */
    private fun buildProbes(category: String, servers: Map<String, J4AServer>): List<Probe> {
        val wantedTypes: (String) -> Boolean = if (category == "dub") {
            { t -> t == "dub" }
        } else {
            { t -> t == "sub" || t == "hsub" }
        }

        val probes = mutableListOf<Probe>()
        for ((code, info) in PROVIDERS) {
            val srv = servers[code]
            val types = if (srv != null) srv.types.map(::normalizeType) else info.blindTypes
            val paid = srv?.animeId?.takeIf { it.isNotBlank() }
            // "sub" first so the Sub label wins URL dedup against hsub twins
            for (t in types.filter(wantedTypes).sortedBy { it != "sub" }) {
                probes.add(Probe(code, info.pretty, t, paid))
            }
        }
        // providers discovered by availability but unknown to the static map
        for (srv in servers.values) {
            if (srv.code.isBlank() || PROVIDERS.containsKey(srv.code)) continue
            val pretty = srv.displayName?.takeIf { it.isNotBlank() } ?: srv.code
            for (t in srv.types.map(::normalizeType).filter(wantedTypes)) {
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
        // parts[0] may arrive as "https://just4anime.online/21" after fixUrl()
        val anilistId = sanitizeId(parts[0])
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
        } catch (e: CancellationException) {
            throw e
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
                } catch (e: CancellationException) {
                    throw e
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
            } catch (e: CancellationException) {
                throw e
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
