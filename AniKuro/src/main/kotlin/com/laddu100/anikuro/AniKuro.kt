package com.laddu100.anikuro

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * AniKuro (anikuro.ru) — AniList-keyed anime site with a Django JSON API.
 *
 * Verified live (2026-08):
 *  - Catalog:  /api/v1/discovery/{trending,top-airing,upcoming,recent} + paginated /api/v1/discovery/filter
 *  - Details:  /api/v1/anime/{anilistId}/full
 *  - Episodes: /api/v1/anime/{anilistId}/episodes  (real ani.zip/TVDB titles, per-episode sub/dub variants)
 *  - Sources:  12 providers; five use /api/v1/{p}/video/{id}/{ep}, seven use /api/v1/sources/{p}/{id}:{ep}
 *  - Playback: animepower = own CDN (needs Referer https://anikuro.ru/), most others are
 *              https://proxy.anikuro.ru/{base64(masterUrl|referer)}.m3u8 (self-contained, headerless)
 *              or direct CDN masters with Referer: https://megaplay.buzz/
 *  - Subtitles: API subtitle arrays + #EXT-X-MEDIA:TYPE=SUBTITLES renditions inside the masters (WebVTT .txt)
 */
class AniKuro : MainAPI() {
    override var mainUrl = "https://anikuro.ru"
    override var name = "AniKuro"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        const val TAG = "AniKuro"
        val FAILOVER_HOSTS = listOf("https://anikuro.ru", "https://anikuro.site")

        /** Site playback order (from the site's own WATCH_SOURCE_PROVIDERS). */
        val PROVIDERS = listOf(
            ProviderDef("animepower", "AniKuro", special = true),
            ProviderDef("animepahe", "Pahe"),
            ProviderDef("anikoto", "Anikoto"),
            ProviderDef("reanime", "ReAnime"),
            ProviderDef("animedao", "AnimeDao"),
            ProviderDef("animegg", "AnimeGG", special = true),
            ProviderDef("anidb", "AniDB", special = true),
            ProviderDef("animedunya", "AnimeDunya", special = true),
            ProviderDef("animeverse", "AnimeVerse", special = true),
            ProviderDef("allanime", "AllAni"),
            ProviderDef("animix", "AnimiX"),
            ProviderDef("senshi", "Senshi"),
        )
    }

    data class ProviderDef(val key: String, val label: String, val special: Boolean = false)

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://anikuro.ru/",
        "Origin" to "https://anikuro.ru",
    )

    private val apiBase: String
        get() = resolvedHost ?: mainUrl.trimEnd('/')

    /** The site rotates domains (anikuro.site is the announced fallback) — remember the live one. */
    @Volatile
    private var resolvedHost: String? = null

    /**
     * GETs an API path trying the current host first, then the failover list.
     * On success the working host is remembered for the session.
     */
    private suspend fun apiGet(path: String, timeout: Long): String? {
        val primary = resolvedHost ?: mainUrl.trimEnd('/')
        val hosts = listOf(primary) + FAILOVER_HOSTS.filter { !it.equals(primary, true) }
        for (host in hosts) {
            try {
                val text = app.get("$host$path", headers = apiHeaders, timeout = timeout).text
                resolvedHost = host
                if (primary != host) Log.i(TAG, "failover: now using $host")
                return text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "host $host failed for $path: ${e.message}")
            }
        }
        return null
    }

    // ------------------------------------------------------------------ DTOs

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Titles(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null,
        val userPreferred: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Images(val cover: String? = null, val banner: String? = null, val thumbnail: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Trailer(val id: String? = null, val site: String? = null, val thumbnail: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatalogItem(
        val id: Int? = null,
        val anilistId: Int? = null,
        val malId: Int? = null,
        val title: Titles? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val format: String? = null,
        val status: String? = null,
        val seasonYear: Int? = null,
        val averageScore: Int? = null,
        val episodes: Int? = null,
        val images: Images? = null,
        val bannerImage: String? = null,
        val hasDub: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatalogEnvelope(val ok: Boolean? = null, val data: CatalogData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatalogData(val items: List<CatalogItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecentEnvelope(val ok: Boolean? = null, val data: RecentData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecentData(val items: List<RecentItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecentItem(val episode: Int? = null, val media: CatalogItem? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FilterEnvelope(val ok: Boolean? = null, val data: FilterData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PageInfo(
        val total: Int? = null,
        val currentPage: Int? = null,
        val lastPage: Int? = null,
        val hasNextPage: Boolean? = null,
        val perPage: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FilterData(val items: List<CatalogItem>? = null, val pageInfo: PageInfo? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FullEnvelope(val ok: Boolean? = null, val data: FullData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FullData(
        val anilistId: Int? = null,
        val malId: Int? = null,
        val title: Titles? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val format: String? = null,
        val status: String? = null,
        val seasonYear: Int? = null,
        val averageScore: Int? = null,
        val episodeCount: Int? = null,
        val images: Images? = null,
        val bannerImage: String? = null,
        val hasDub: Boolean? = null,
        val trailer: Trailer? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesEnvelope(val ok: Boolean? = null, val data: EpisodesData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesData(
        val animeId: Int? = null,
        val episodes: List<EpisodeItem>? = null,
        val totalEpisodes: Int? = null,
        val hasDub: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        val id: String? = null,
        val number: Int? = null,
        val displayNumber: String? = null,
        val title: String? = null,
        val description: String? = null,
        val thumbnail: String? = null,
        val image: String? = null,
        val airDate: String? = null,
        val airedAt: String? = null,
        val filler: Boolean? = null,
        val variants: List<String>? = null,
    )

    // --- sources DTOs (three tolerated shapes, same as the site's own JS) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceEnvelope(val ok: Boolean? = null, val data: SourceData? = null, val error: ApiError? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiError(val code: String? = null, val message: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceData(
        val provider: String? = null,
        val normalized: List<VariantBlock>? = null,
        val raw: RawBlock? = null,
        val error: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VariantBlock(
        val variant: String? = null,
        val sources: List<SourceItem>? = null,
        val subtitles: List<SubtitleItem>? = null,
        val headers: Map<String, String>? = null,
        val error: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceItem(
        val url: String? = null,
        val quality: String? = null,
        val type: String? = null,
        val isM3U8: Boolean? = null,
        val headers: Map<String, String>? = null,
        val originalUrl: String? = null,
        val upstreamReferer: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubtitleItem(val label: String? = null, val lang: String? = null, val url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RawBlock(
        val sub: VariantBlock? = null,
        val dub: VariantBlock? = null,
        val providerResult: ProviderResult? = null,
        val error: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ProviderResult(
        val ok: Boolean? = null,
        val variants: List<VariantBlock>? = null,
        val error: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LinkData(val id: Int, val ep: Int, val variant: String)

    // ------------------------------------------------------------- main page

    override val mainPage = mainPageOf(
        "discovery:trending" to "Trending",
        "discovery:top-airing" to "Top Airing",
        "recent" to "Recent Episodes",
        "filter:POPULARITY_DESC:" to "Popular",
        "filter:SCORE_DESC:" to "Top Rated",
        "filter:TRENDING_DESC:" to "Trending Now",
        "filter:POPULARITY_DESC:TV" to "Latest TV",
        "filter:POPULARITY_DESC:MOVIE" to "Movies",
        "discovery:upcoming" to "Upcoming",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (kind, rest) = parseRowKey(request.data)
        return when (kind) {
            "recent" -> {
                val items = try {
                    apiGet("/api/v1/discovery/recent", 20_000L)?.let { parseJson<RecentEnvelope>(it) }?.data?.items
                } catch (e: Exception) {
                    Log.e(TAG, "recent failed: ${e.message}")
                    null
                }
                val home = items.orEmpty().mapNotNull { it.media?.toSearchResponse() }
                newHomePageResponse(request.name, home, hasNext = false)
            }
            "discovery" -> {
                val items = try {
                    apiGet("/api/v1/discovery/$rest", 20_000L)?.let { parseJson<CatalogEnvelope>(it) }?.data?.items
                } catch (e: Exception) {
                    Log.e(TAG, "discovery/$rest failed: ${e.message}")
                    null
                }
                val home = items.orEmpty().mapNotNull { it.toSearchResponse() }
                newHomePageResponse(request.name, home, hasNext = false)
            }
            else -> { // filter:<sort>:<formats>
                val env = try {
                    apiGet(buildFilterPath(page = page, sort = rest.first, formats = rest.second, perPage = 20), 20_000L)
                        ?.let { parseJson<FilterEnvelope>(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "filter failed: ${e.message}")
                    null
                }
                val data = env?.data
                val home = data?.items.orEmpty().mapNotNull { it.toSearchResponse() }
                val hasNext = data?.pageInfo?.hasNextPage ?: (home.size >= 20)
                newHomePageResponse(request.name, home, hasNext = hasNext)
            }
        }
    }

    private fun parseRowKey(data: String): Pair<String, Pair<String, String>> = when {
        data.startsWith("discovery:") -> "discovery" to (data.removePrefix("discovery:") to "")
        data.startsWith("filter:") -> {
            val parts = data.split(":")
            val sort = parts.getOrNull(1) ?: "POPULARITY_DESC"
            val formats = parts.getOrNull(2) ?: ""
            "filter" to (sort to formats)
        }
        else -> "recent" to ("" to "")
    }

    private fun buildFilterPath(page: Int, sort: String, formats: String, perPage: Int, query: String = ""): String {
        val sb = StringBuilder("/api/v1/discovery/filter?page=$page&perPage=$perPage")
        if (query.isNotBlank()) sb.append("&q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
        if (sort.isNotBlank()) sb.append("&sort=").append(sort)
        if (formats.isNotBlank()) sb.append("&formats=").append(formats)
        return sb.toString()
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return searchPaged(query, 1)
    }

    private suspend fun searchPaged(query: String, page: Int): List<SearchResponse> {
        return try {
            val env = apiGet(buildFilterPath(page = page, sort = "POPULARITY_DESC", formats = "", perPage = 20, query = query), 20_000L)
                ?.let { parseJson<FilterEnvelope>(it) }
            env?.data?.items.orEmpty().mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.e(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    // ----------------------------------------------------------------- load

    private fun CatalogItem.toSearchResponse(): SearchResponse? {
        val id = anilistId ?: id ?: return null
        val t = title
        val display = t?.english ?: t?.romaji ?: t?.userPreferred ?: t?.native ?: return null
        val tvType = formatToTvType(format)
        return newAnimeSearchResponse(display, "$mainUrl/watch/$id", tvType) {
            this.posterUrl = images?.cover
            this.otherName = t?.native
            addDubStatus(dubExist = hasDub == true, subExist = true)
        }
    }

    private fun formatToTvType(format: String?): TvType = when (format?.uppercase()) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA", "ONA", "SPECIAL", "MUSIC" -> TvType.OVA
        else -> TvType.Anime
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").substringBefore("?").toIntOrNull() ?: return null
        val full = try {
            apiGet("/api/v1/anime/$id/full", 25_000L)?.let { parseJson<FullEnvelope>(it) }?.data
        } catch (e: Exception) {
            Log.e(TAG, "full failed for $id: ${e.message}")
            null
        }

        val episodeList = try {
            apiGet("/api/v1/anime/$id/episodes", 30_000L)?.let { parseJson<EpisodesEnvelope>(it) }?.data
        } catch (e: Exception) {
            Log.e(TAG, "episodes failed for $id: ${e.message}")
            null
        }

        val eps = episodeList?.episodes.orEmpty()
        if (full == null && eps.isEmpty()) return null

        val t = full?.title
        val display = t?.english ?: t?.romaji ?: t?.userPreferred ?: t?.native ?: return null
        val tvType = formatToTvType(full?.format)

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        for (ep in eps) {
            val num = ep.number ?: continue
            val data = LinkData(id = id, ep = num, variant = "").toJson()
            val variants = ep.variants.orEmpty()
            val name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $num"
            val poster = ep.thumbnail ?: ep.image
            val airDate = parseAirDate(ep.airDate ?: ep.airedAt)

            val builder: (Episode).() -> Unit = {
                this.name = name
                this.episode = num
                this.posterUrl = poster
                this.description = ep.description
                if (airDate != null) this.addDate(airDate)
                if (ep.filler == true) this.description = (this.description.orEmpty() + "\n\n[Filler]").trim()
            }
            if (variants.isEmpty() || variants.contains("sub")) subEps.add(newEpisode(data, builder))
            if (variants.contains("dub")) dubEps.add(newEpisode(LinkData(id = id, ep = num, variant = "dub").toJson(), builder))
        }

        return newAnimeLoadResponse(display, url, tvType) {
            this.posterUrl = full?.images?.cover
            this.backgroundPosterUrl = full?.images?.banner ?: full?.bannerImage
            this.plot = full?.description
            this.tags = full?.genres
            this.year = full?.seasonYear
            this.showStatus = when (full?.status?.uppercase()) {
                "RELEASING" -> ShowStatus.Ongoing
                "FINISHED" -> ShowStatus.Completed
                else -> null
            }
            full?.averageScore?.let { this.score = Score.from10((it / 10.0).toString()) }
            addMalId(full?.malId?.takeIf { it > 0 })
            addAniListId(full?.anilistId?.takeIf { it > 0 } ?: id.takeIf { it > 0 })
            full?.trailer?.let { tr ->
                val site = tr.site?.lowercase().orEmpty()
                val tid = tr.id
                if (tid != null && site.contains("youtube")) addTrailer("https://youtube.com/watch?v=$tid")
            }
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    private fun parseAirDate(raw: String?): java.util.Date? {
        if (raw.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(raw.substringBefore("T").take(10))
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val link = try {
            parseJson<LinkData>(data)
        } catch (e: Exception) {
            Log.e(TAG, "bad link data: ${e.message}")
            return false
        }
        // Episode-level variant was stamped at load(); empty means "sub" default list.
        val variant = link.variant.ifBlank { "sub" }

        // All 12 providers fire in parallel; every source that answers gets emitted.
        val results = coroutineScope {
            PROVIDERS.amap { provider ->
                async {
                    try {
                        fetchProviderVariant(provider, link.id, link.ep, variant)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "${provider.label} failed: ${e.message}")
                        null
                    }
                }
            }.awaitAll()
        }

        val seenSubUrls = HashSet<String>()
        results.forEachIndexed { index, block ->
            val provider = PROVIDERS[index]
            if (block == null) return@forEachIndexed
            emitVariant(provider, block, seenSubUrls, subtitleCallback, callback)
        }
        return true
    }

    /** Hits one provider and returns the matching sub/dub block (tolerates all 3 response shapes). */
    private suspend fun fetchProviderVariant(
        provider: ProviderDef,
        animeId: Int,
        ep: Int,
        variant: String,
    ): VariantBlock? {
        val endpoint = if (provider.special) {
            "$apiBase/api/v1/${provider.key}/video/$animeId/$ep"
        } else {
            "$apiBase/api/v1/sources/${provider.key}/$animeId:$ep"
        }
        val text = try {
            apiGet(endpoint, 45_000L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "${provider.label} request failed: ${e.message}")
            return null
        }
        if (text.isNullOrBlank() || text.length > 6_000_000) return null

        val env = try {
            parseJson<SourceEnvelope>(text)
        } catch (e: Exception) {
            Log.d(TAG, "${provider.label} parse failed: ${e.message}")
            return null
        }
        val root = env.data ?: return null

        // Shape 1: normalized[] blocks
        root.normalized.orEmpty()
            .firstOrNull { it.variant.equals(variant, true) && !it.sources.isNullOrEmpty() }
            ?.let { return it }

        val raw = root.raw ?: return null

        // Shape 2: legacy raw.sub / raw.dub
        val legacy = if (variant.equals("dub", true)) raw.dub else raw.sub
        if (legacy != null && (!legacy.sources.isNullOrEmpty() || rawHasDefault(legacy))) return legacy

        // Shape 3: providerResult.variants
        return raw.providerResult?.variants.orEmpty()
            .firstOrNull { it.variant.equals(variant, true) && !it.sources.isNullOrEmpty() }
    }

    private fun rawHasDefault(block: VariantBlock): Boolean = block.sources.orEmpty().any { !it.url.isNullOrBlank() }

    // ------------------------------------------------------------- emission

    /** animepower's CDN (freevideoupload.xyz) 403s without the site Referer (verified live). */
    private fun siteRefererFor(url: String): String? =
        if (url.contains("freevideoupload.xyz")) "https://anikuro.ru/" else null

    private data class PlayLink(
        val url: String,
        val referer: String?,
        val headers: Map<String, String>,
        val quality: String?,
        val isProxy: Boolean,
    )

    private suspend fun emitVariant(
        provider: ProviderDef,
        block: VariantBlock,
        seenSubUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val sources = block.sources.orEmpty().filter { !it.url.isNullOrBlank() }
        if (sources.isEmpty()) return

        val links = ArrayList<PlayLink>()
        val seen = HashSet<String>()

        for (s in sources) {
            val url = s.url ?: continue
            val orig = s.originalUrl?.takeIf { it.startsWith("http") && it != url }
            val upstreamRef = s.upstreamReferer?.takeIf { it.startsWith("http") }
                ?: s.headers.orEmpty()["Referer"]?.takeIf { it.startsWith("http") }

            // Direct upstream master (needs the upstream Referer) — preferred.
            if (orig != null && seen.add(orig)) {
                links.add(
                    PlayLink(
                        url = orig,
                        referer = upstreamRef ?: siteRefererFor(orig),
                        headers = s.headers.orEmpty().filterKeys { it != "Referer" },
                        quality = s.quality,
                        isProxy = false,
                    )
                )
            }
            // The API URL itself (proxy.anikuro.ru links are headerless + CORS-open).
            if (seen.add(url)) {
                val isProxy = url.contains("proxy.anikuro.ru") || url.contains("/proxy.") || url.contains("?proxy=")
                links.add(
                    PlayLink(
                        url = url,
                        referer = if (isProxy) siteRefererFor(url) else (upstreamRef ?: siteRefererFor(url)),
                        headers = if (isProxy && siteRefererFor(url) == null) emptyMap() else s.headers.orEmpty().filterKeys { it != "Referer" },
                        quality = s.quality,
                        isProxy = isProxy,
                    )
                )
            }
        }
        if (links.isEmpty()) return

        // Keep the site's own label; append quality when the API gave a real one.
        fun linkName(l: PlayLink): String {
            val q = l.quality?.takeIf { it.isNotBlank() && !it.equals("default", true) }
            val base = if (l.isProxy && links.size > 1) "${provider.label} (proxy)" else provider.label
            return if (q != null) "$base · $q" else base
        }

        // Subtitles: API list first, then #EXT-X-MEDIA renditions from the first master.
        val subs = ArrayList<Triple<String, String, Map<String, String>>>() // label, url, headers
        for (st in block.subtitles.orEmpty()) {
            val u = st.url ?: continue
            if (!u.startsWith("http")) continue
            if (!seenSubUrls.add(u)) continue
            val siteRef = siteRefererFor(u)
            subs.add(Triple(st.label ?: "Subtitle", u, if (siteRef != null) mapOf("Referer" to siteRef) else emptyMap()))
        }
        links.firstOrNull()?.let { first ->
            parseMasterSubtitles(first).forEach { (label, url) ->
                if (seenSubUrls.add(url)) subs.add(Triple(label, url, first.headers))
            }
        }
        for ((label, url, headers) in subs) {
            subtitleCallback.invoke(newSubtitleFile(label, url) { this.headers = headers })
        }

        for (l in links) {
            val label = linkName(l)
            callback.invoke(
                newExtractorLink(label, label, l.url, type = ExtractorLinkType.M3U8) {
                    l.referer?.let { this.referer = it }
                    if (l.headers.isNotEmpty()) this.headers = l.headers
                }
            )
        }
    }

    /**
     * Parses #EXT-X-MEDIA:TYPE=SUBTITLES renditions out of a master playlist and
     * resolves relative URIs. animepower masters expose ~10 WebVTT languages here.
     */
    private suspend fun parseMasterSubtitles(link: PlayLink): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (!link.url.contains(".m3u8")) return out
        val headers = buildMap {
            link.referer?.let { put("Referer", it) }
            putAll(link.headers)
        }
        val body = try {
            app.get(link.url, headers = headers, timeout = 10_000L).text
        } catch (e: Exception) {
            return out
        }
        if (!body.contains("#EXT-X-MEDIA")) return out

        val baseDir = link.url.substringBeforeLast('/') + "/"
        for (line in body.lines()) {
            if (!line.startsWith("#EXT-X-MEDIA") || !line.contains("TYPE=SUBTITLES")) continue
            val name = Regex("NAME=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
            val uri = Regex("URI=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: continue
            if (uri.isBlank()) continue
            val full = if (uri.startsWith("http")) uri else baseDir + uri.removePrefix("./")
            if (!full.startsWith("http")) continue
            out.add(Pair(name?.takeIf { it.isNotBlank() } ?: "Subtitle", full))
        }
        return out
    }
}
