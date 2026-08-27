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
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AniKuro (anikuro.ru) — AniList-keyed anime site with a Django JSON API.
 *
 * v3 fixes (all verified against live site):
 *  1. "No links found" — the source endpoints (/api/v1/sources/... and /api/v1/{p}/video/...)
 *     now return the provider payload BARE (normalized/raw at top level, no {ok,data} wrapper).
 *     The plugin now accepts BOTH the enveloped and the bare shape.
 *  2. NiceHttp `timeout` is SECONDS, not ms — 45_000L meant 12.5h hangs. Now uses seconds.
 *  3. A provider can return MULTIPLE normalized blocks for the same variant (several servers);
 *     all blocks are now collected and merged (previously only the first was emitted).
 *  4. Movies: CloudStream hides the Sub/Dub selector for TvType.AnimeMovie and always plays the
 *     first map entry (sub). MOVIE format is now emitted as TvType.Anime with the single movie
 *     episode, so the Sub/Dub dropdown appears and each variant plays its own stream.
 *  5. Poster DUB badge: the catalog API's hasDub is broken server-side (always false). Dub
 *     existence is now resolved via batched AniList GraphQL (English voice actors) — the same
 *     method the site's own frontend uses — with a session cache, plus per-episode variant truth
 *     learned in load().
 *
 * Still true from v1/v2:
 *  - Catalog: /api/v1/discovery/... + /api/v1/discovery/filter
 *  - Details: /api/v1/anime/{id}/full, Episodes: /api/v1/anime/{id}/episodes (real ani.zip titles)
 *  - 12 providers; five /api/v1/{p}/video/{id}/{ep}, seven /api/v1/sources/{p}/{id}:{ep}
 *  - proxy.anikuro.ru links are self-contained (headerless); animepower CDN needs Referer
 *    https://anikuro.ru/; direct upstream masters need their upstreamReferer.
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

        // NiceHttp timeouts are SECONDS (verified in NiceHttp Requests.kt) — do not pass ms!
        const val TIMEOUT_CATALOG = 15L
        const val TIMEOUT_DETAILS = 20L
        const val TIMEOUT_EPISODES = 30L
        const val TIMEOUT_SOURCES = 60L
        const val TIMEOUT_MASTER = 12L

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

        private const val ANILIST_API = "https://graphql.anilist.co"

        // NOTE: AniList only resolves voiceActors when the edge also selects node —
        // verified live: without node { id } the voiceActors arrays come back EMPTY.
        private val ANILIST_DUB_QUERY = """
            query (${"$"}ids: [Int]) {
              Page(perPage: 50) {
                media(id_in: ${"$"}ids, type: ANIME) {
                  id
                  characters(perPage: 20) {
                    edges {
                      voiceActors {
                        languageV2
                      }
                      node {
                        id
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
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
     * Non-JSON bodies (HTML error pages / interstitials) count as failure so the next
     * host is tried. On success the working host is remembered for the session.
     */
    private suspend fun apiGet(path: String, timeout: Long): String? {
        val primary = resolvedHost ?: mainUrl.trimEnd('/')
        val hosts = listOf(primary) + FAILOVER_HOSTS.filter { !it.equals(primary, true) }
        for (host in hosts) {
            try {
                val text = app.get("$host$path", headers = apiHeaders, timeout = timeout).text
                val trimmed = text.trimStart()
                if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                    Log.d(TAG, "host $host non-JSON for $path (${trimmed.take(60)})")
                    continue
                }
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

    // --- sources DTOs.
    // The backend is inconsistent: catalog routes wrap in {ok, data}, while the provider
    // routes return the payload BARE (normalized/raw at top level). SourceRoot tolerates both.

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceRoot(
        val ok: Boolean? = null,
        val data: SourceData? = null,
        // bare-shape fields (present when there is no `data` wrapper):
        val normalized: List<VariantBlock>? = null,
        val raw: RawBlock? = null,
        val provider: String? = null,
    ) {
        /** The effective payload regardless of envelope style. */
        fun effective(): SourceData? {
            data?.let { return it }
            return if (normalized != null || raw != null) {
                SourceData(provider = provider, normalized = normalized, raw = raw)
            } else null
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceData(
        val provider: String? = null,
        val normalized: List<VariantBlock>? = null,
        val raw: RawBlock? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VariantBlock(
        val variant: String? = null,
        val sources: List<SourceItem>? = null,
        val subtitles: List<SubtitleItem>? = null,
        val headers: Map<String, String>? = null,
        // legacy raw-block fields (shape 2):
        val default: String? = null,
        val quality: String? = null,
        val originalUrl: String? = null,
    ) {
        fun hasSources(): Boolean = !sources.isNullOrEmpty() || !default.isNullOrBlank()
    }

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
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ProviderResult(
        val ok: Boolean? = null,
        val variants: List<VariantBlock>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LinkData(val id: Int, val ep: Int, val variant: String)

    // --- AniList dub detection DTOs

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListResp(val data: AniListData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListData(val page: AniListPage? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListPage(val media: List<AniListMedia>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListMedia(val id: Int? = null, val characters: AniListCharacters? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListCharacters(val edges: List<AniListEdge>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListEdge(val voiceActors: List<AniListVoiceActor>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListVoiceActor(val languageV2: String? = null)

    // ------------------------------------------------- dub cache (AniList-backed)

    /**
     * The catalog API's hasDub flag is broken (always false server-side as of 2026-09), so cards
     * would all show "SUB". The site's own frontend derives dub existence from AniList character
     * voiceActors (any English VA). This cache batch-queries AniList for a page of ids and
     * remembers the result for the session; load() also feeds per-episode variant truth in.
     */
    object DubCache {
        private const val TTL_MS = 6 * 60 * 60 * 1000L // 6h
        private const val MAX_ENTRIES = 10_000
        private val map = ConcurrentHashMap<Int, Pair<Boolean, Long>>() // anilistId -> (hasDub, at)
        private val mutex = Mutex()

        fun peek(id: Int?): Boolean? {
            if (id == null) return null
            val entry = map[id] ?: return null
            return if (System.currentTimeMillis() - entry.second < TTL_MS) entry.first else null
        }

        fun put(id: Int, hasDub: Boolean) {
            if (map.size > MAX_ENTRIES) map.clear()
            map[id] = Pair(hasDub, System.currentTimeMillis())
        }

        /** Batch-fetches dub status for ids not cached yet. Never throws. */
        suspend fun annotate(rawIds: Collection<Int?>) {
            val ids = rawIds.filterNotNull().filter { it > 0 }.distinct()
            if (ids.isEmpty()) return
            val now = System.currentTimeMillis()
            val missing = ids.filter { id ->
                val entry = map[id]
                entry == null || (now - entry.second >= TTL_MS)
            }
            if (missing.isEmpty()) return
            try {
                mutex.withLock {
                    val now2 = System.currentTimeMillis()
                    val still = missing.filter { id ->
                        val entry = map[id]
                        entry == null || (now2 - entry.second >= TTL_MS)
                    }
                    if (still.isEmpty()) return
                    for (chunk in still.chunked(50)) {
                        val resp = try {
                            app.post(
                                ANILIST_API,
                                headers = mapOf(
                                    "Accept" to "application/json",
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                                ),
                                json = mapOf(
                                    "query" to ANILIST_DUB_QUERY,
                                    "variables" to mapOf("ids" to chunk),
                                ),
                                timeout = TIMEOUT_CATALOG,
                            ).text
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "anilist dub batch failed: ${e.message}")
                            null
                        } ?: continue
                        val parsed = try {
                            parseJson<AniListResp>(resp)
                        } catch (e: Exception) {
                            Log.d(TAG, "anilist dub parse failed: ${e.message}")
                            null
                        } ?: continue
                        for (media in parsed.data?.page?.media.orEmpty()) {
                            val id = media.id ?: continue
                            val hasDub = media.characters?.edges.orEmpty().any { edge ->
                                edge.voiceActors.orEmpty().any { it.languageV2.equals("English", true) }
                            }
                            put(id, hasDub)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "dub annotate error: ${e.message}")
            }
        }
    }

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
                    apiGet("/api/v1/discovery/recent", TIMEOUT_CATALOG)?.let { parseJson<RecentEnvelope>(it) }?.data?.items
                } catch (e: Exception) {
                    Log.e(TAG, "recent failed: ${e.message}")
                    null
                }
                val home = annotateAndMap(items.orEmpty().mapNotNull { it.media })
                newHomePageResponse(request.name, home, hasNext = false)
            }
            "discovery" -> {
                val items = try {
                    apiGet("/api/v1/discovery/$rest", TIMEOUT_CATALOG)?.let { parseJson<CatalogEnvelope>(it) }?.data?.items
                } catch (e: Exception) {
                    Log.e(TAG, "discovery/$rest failed: ${e.message}")
                    null
                }
                val home = annotateAndMap(items.orEmpty())
                newHomePageResponse(request.name, home, hasNext = false)
            }
            else -> { // filter:<sort>:<formats>
                val env = try {
                    apiGet(buildFilterPath(page = page, sort = rest.first, formats = rest.second, perPage = 20), TIMEOUT_CATALOG)
                        ?.let { parseJson<FilterEnvelope>(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "filter failed: ${e.message}")
                    null
                }
                val data = env?.data
                val home = annotateAndMap(data?.items.orEmpty())
                val hasNext = data?.pageInfo?.hasNextPage ?: (home.size >= 20)
                newHomePageResponse(request.name, home, hasNext = hasNext)
            }
        }
    }

    /** Resolves real dub existence for the page (batched) then maps to search responses. */
    private suspend fun annotateAndMap(items: List<CatalogItem>): List<SearchResponse> {
        DubCache.annotate(items.map { it.anilistId ?: it.id })
        return items.mapNotNull { it.toSearchResponse() }
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
            val env = apiGet(buildFilterPath(page = page, sort = "POPULARITY_DESC", formats = "", perPage = 20, query = query), TIMEOUT_CATALOG)
                ?.let { parseJson<FilterEnvelope>(it) }
            annotateAndMap(env?.data?.items.orEmpty())
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
        // catalog hasDub is broken server-side; OR with the AniList/variants-backed cache
        val dub = hasDub == true || DubCache.peek(id) == true
        return newAnimeSearchResponse(display, "$mainUrl/watch/$id", tvType) {
            this.posterUrl = images?.cover
            this.otherName = t?.native
            addDubStatus(dubExist = dub, subExist = true)
        }
    }

    /**
     * MOVIE format intentionally maps to TvType.Anime: CloudStream hides the Sub/Dub selector for
     * TvType.AnimeMovie (isMovieType) and always plays the first episodes-map entry, which would
     * force sub for every movie. As a plain anime with one "Complete Movie" episode per dub
     * status, the Sub/Dub dropdown appears and each status plays its own variant.
     */
    private fun formatToTvType(format: String?): TvType = when (format?.uppercase()) {
        "OVA", "ONA", "SPECIAL", "MUSIC" -> TvType.OVA
        else -> TvType.Anime
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").substringBefore("?").toIntOrNull() ?: return null
        val full = try {
            apiGet("/api/v1/anime/$id/full", TIMEOUT_DETAILS)?.let { parseJson<FullEnvelope>(it) }?.data
        } catch (e: Exception) {
            Log.e(TAG, "full failed for $id: ${e.message}")
            null
        }

        val episodeList = try {
            apiGet("/api/v1/anime/$id/episodes", TIMEOUT_EPISODES)?.let { parseJson<EpisodesEnvelope>(it) }?.data
        } catch (e: Exception) {
            Log.e(TAG, "episodes failed for $id: ${e.message}")
            null
        }

        var eps = episodeList?.episodes.orEmpty()
        if (eps.isEmpty() && full != null) {
            // Robustness fallback: single "Complete Movie"/finale entry when the episodes API
            // returns nothing but details exist (mirrors the site's own movie page).
            val variants = if (full.hasDub == true) listOf("sub", "dub") else listOf("sub")
            eps = listOf(
                EpisodeItem(
                    id = "$id:1",
                    number = 1,
                    displayNumber = "1",
                    title = if (full.format?.uppercase() == "MOVIE") "Complete Movie" else "Episode 1",
                    variants = variants,
                )
            )
        }
        if (full == null && eps.isEmpty()) return null

        val t = full?.title
        val display = t?.english ?: t?.romaji ?: t?.userPreferred ?: t?.native ?: return null
        val tvType = formatToTvType(full?.format)

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        for (ep in eps) {
            val num = ep.number ?: continue
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
            if (variants.isEmpty() || variants.contains("sub")) subEps.add(newEpisode(LinkData(id = id, ep = num, variant = "sub").toJson(), builder))
            if (variants.contains("dub")) dubEps.add(newEpisode(LinkData(id = id, ep = num, variant = "dub").toJson(), builder))
        }

        // Feed the truth learned from per-episode variants back into the card cache.
        if (subEps.isNotEmpty() || dubEps.isNotEmpty()) {
            DubCache.put(id, dubEps.isNotEmpty())
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
        val variant = link.variant.ifBlank { "sub" }

        // All 12 providers fire in parallel and emit as soon as they answer (streaming UX):
        // animepower replies in <1s, upstream scrapers can take 25s+.
        val seenUrls = HashSet<String>()
        val seenSubUrls = HashSet<String>()
        coroutineScope {
            PROVIDERS.amap { provider ->
                async {
                    val blocks = try {
                        fetchProviderBlocks(provider, link.id, link.ep, variant)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "${provider.label} failed: ${e.message}")
                        emptyList()
                    }
                    try {
                        emitProvider(provider, blocks, seenUrls, seenSubUrls, subtitleCallback, callback)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "${provider.label} emit failed: ${e.message}")
                    }
                }
            }
        }
        return true
    }

    /**
     * Hits one provider and returns EVERY block matching the variant (a provider may expose
     * several servers as separate normalized blocks). Tolerates all response shapes:
     * enveloped {ok,data} and bare payloads, normalized[] / raw.sub|raw.dub / providerResult.
     */
    private suspend fun fetchProviderBlocks(
        provider: ProviderDef,
        animeId: Int,
        ep: Int,
        variant: String,
    ): List<VariantBlock> {
        val endpoint = if (provider.special) {
            "$apiBase/api/v1/${provider.key}/video/$animeId/$ep"
        } else {
            "$apiBase/api/v1/sources/${provider.key}/$animeId:$ep"
        }
        val text = try {
            apiGet(endpoint, TIMEOUT_SOURCES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "${provider.label} request failed: ${e.message}")
            return emptyList()
        }
        if (text.isNullOrBlank() || text.length > 6_000_000) return emptyList()

        val root = try {
            parseJson<SourceRoot>(text)
        } catch (e: Exception) {
            Log.d(TAG, "${provider.label} parse failed: ${e.message}")
            return emptyList()
        }
        val root0 = root.effective() ?: return emptyList()

        val match: (VariantBlock) -> Boolean = { block ->
            block.variant.equals(variant, true) && block.hasSources()
        }

        // Shape 1: normalized[] — collect ALL matching blocks (multi-server support)
        val normalized = root0.normalized.orEmpty().filter(match)
        if (normalized.isNotEmpty()) return normalized

        val raw = root0.raw ?: return emptyList()

        // Shape 2: legacy raw.sub / raw.dub (may carry `default` instead of sources[])
        val legacy = if (variant.equals("dub", true)) raw.dub else raw.sub
        if (legacy != null && (legacy.variant == null || legacy.variant.equals(variant, true)) && legacy.hasSources()) {
            return listOf(legacy)
        }

        // Shape 3: providerResult.variants
        return raw.providerResult?.variants.orEmpty().filter(match)
    }

    // ------------------------------------------------------------- emission

    /** animepower's CDN (freevideoupload.xyz) 403s without the site Referer (verified live). */
    private fun siteRefererFor(url: String): String? =
        if (url.contains("freevideoupload.xyz")) "https://anikuro.ru/" else null

    private data class PlayLink(
        val url: String,
        val referer: String?,
        val headers: Map<String, String>,
        val quality: String?,
    )

    private suspend fun emitProvider(
        provider: ProviderDef,
        blocks: List<VariantBlock>,
        seenUrls: MutableSet<String>,
        seenSubUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (blocks.isEmpty()) return

        val links = ArrayList<PlayLink>()
        for (block in blocks) {
            val blockHeaders = block.headers.orEmpty()
            val blockSources = block.sources.orEmpty().filter { !it.url.isNullOrBlank() }
            val effective = if (blockSources.isEmpty() && !block.default.isNullOrBlank()) {
                listOf(SourceItem(url = block.default, quality = block.quality))
            } else blockSources

            for (s in effective) {
                val url = s.url ?: continue
                val orig = (s.originalUrl ?: block.originalUrl)
                    ?.takeIf { it.startsWith("http") && it != url }
                val upstreamRef = s.upstreamReferer?.takeIf { it.startsWith("http") }
                    ?: s.headers.orEmpty()["Referer"]?.takeIf { it.startsWith("http") }
                    ?: blockHeaders["Referer"]?.takeIf { it.startsWith("http") }

                // Direct upstream master (needs the upstream Referer) — preferred.
                if (orig != null && seenUrls.add(orig)) {
                    links.add(
                        PlayLink(
                            url = orig,
                            referer = upstreamRef ?: siteRefererFor(orig),
                            headers = s.headers.orEmpty().filterKeys { !it.equals("Referer", true) },
                            quality = s.quality ?: block.quality,
                        )
                    )
                }
                // The API URL itself (proxy.anikuro.ru links are headerless + CORS-open).
                if (seenUrls.add(url)) {
                    links.add(
                        PlayLink(
                            url = url,
                            referer = upstreamRef ?: siteRefererFor(url),
                            headers = s.headers.orEmpty().filterKeys { !it.equals("Referer", true) },
                            quality = s.quality ?: block.quality,
                        )
                    )
                }
            }
        }
        if (links.isEmpty()) return

        // Label: provider name + server number when a provider exposes several distinct masters,
        // + real quality when the API provides one (e.g. "AniKuro · 1080p").
        val needsServerSuffix = links.size > 1
        fun linkName(l: PlayLink): String {
            val q = l.quality?.takeIf { it.isNotBlank() && !it.equals("default", true) }
            val base = if (needsServerSuffix) "${provider.label} · ${links.indexOf(l) + 1}" else provider.label
            return if (q != null) "$base · $q" else base
        }

        // Subtitles: API lists from every block first, then #EXT-X-MEDIA renditions from the
        // first master. All deduped by URL across providers.
        for (block in blocks) {
            for (st in block.subtitles.orEmpty()) {
                val u = st.url ?: continue
                if (!u.startsWith("http")) continue
                if (!seenSubUrls.add(u)) continue
                val siteRef = siteRefererFor(u)
                val blockRef = block.headers?.get("Referer")?.takeIf { it.startsWith("http") }
                subtitleCallback.invoke(
                    newSubtitleFile(st.label ?: "Subtitle", u) {
                        this.headers = when {
                            siteRef != null -> mapOf("Referer" to siteRef)
                            blockRef != null -> mapOf("Referer" to blockRef)
                            else -> emptyMap()
                        }
                    }
                )
            }
        }
        links.firstOrNull()?.let { first ->
            parseMasterSubtitles(first).forEach { (label, url) ->
                if (seenSubUrls.add(url)) {
                    subtitleCallback.invoke(
                        newSubtitleFile(label, url) {
                            this.headers = first.headers
                        }
                    )
                }
            }
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
            app.get(link.url, headers = headers, timeout = TIMEOUT_MASTER).text
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
