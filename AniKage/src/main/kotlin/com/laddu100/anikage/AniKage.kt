package com.laddu100.anikage

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
 * AniKage (anikage.cc) — SvelteKit anime site, AniList-keyed, JSON API under /api/media/anime.
 *
 * Everything below was verified against the LIVE site before implementation:
 *
 *  Catalog/search : GET /api/media/anime/browse?sort=&page=&q=&format=&genres=&status=
 *                   -> {data:[{slug,anilistId,title{romaji,english},coverImage,format,...}], hasNext}
 *  Info           : GET /api/media/anime/{slug}
 *                   -> {anime:{anilistId,malId,title,description,bannerImage,trailerId,format,...}}
 *  Episodes       : GET /api/media/anime/{slug}/episodes
 *                   -> [{number,title,titleRomaji,description,image,airDate,isFiller}] (real TVDB names)
 *  Servers        : GET /api/media/anime/{slug}/episodes/{n}/servers
 *                   -> {servers:[{id,subTypes:["sub","dub"]}]}  (per-episode sub/dub truth)
 *  Sources        : GET /api/media/anime/{slug}/episodes/{n}/sources?provider={id}&lang={sub|dub}&server={id}
 *                   -> {sources:[{url(encrypted token), quality, isM3U8, embedUrl, type}], subtitles, embeds}
 *
 *  Source handling (re-verified live 2026-08-28, v2):
 *   The /sources payload now carries THREE places an embed can appear — sources[].embedUrl,
 *   top-level embeds[] and embedOptions[] — and they differ per provider. v2 merges all of
 *   them (deduped) and routes every embed URL by host:
 *
 *   - koto  : megaplay.buzz / vidtube.site embeds (same MegaPlay software).
 *             embed page -> data-id -> {host}/stream/getSourcesNew?id={data-id} (X-Requested-With:
 *             XMLHttpRequest, Referer = the embed URL itself) -> {sources:{file:<m3u8>},
 *             tracks:[{file,label}] (plaintext VTT subs)} -> play with Referer https://{embedHost}/
 *             (kryntal/akirax/shiora CDNs 403 without it). vidtube files 404 individually when
 *             expired; megaplay mirrors cover the episode.
 *   - neko  : vivibebe.site/{code}?sub={vtt} + bibiemb.xyz/{code}h?sub=... (inline
 *             const src = "https://.../master.m3u8", bibiemb via *.vibevibe.workers.dev)
 *             PLUS StreamHG (otakuhg.site/e/{code}) and Earnvids (otakuvid.online/embed/{code}):
 *             their pages carry an eval-packed JWPlayer setup whose unpacked JS defines
 *             var links={"hls2":"https://...master.m3u8?t=...","hls3":"https://...master.txt"}
 *             (verified 200 HLS with Referer https://{embedHost}/). Captions ride the embed
 *             URL query (?sub= / caption_1=&sub_1=). playmogo.com (DoodStream) is Turnstile-
 *             gated server-side and cannot be extracted by an HTTP client — skipped.
 *   - dib   : playeng.animeapps.top upstream is DEAD (serves a parked Google Cloud page).
 *   - wave  : embeds (echovideo 404 / gn1r5n "video not found" / myvidplay -> playmogo) are dead;
 *             sources are prox-tokens (see below). Generic m3u8 scan kept so it self-recovers.
 *   - kiwi / uwu / megg : token-only sources, no embeds anywhere. The site resolves them via
 *             https://prox.anikage.cc/m3u8|stream/{token} — that prox now answers 400 "bad url"
 *             for EVERY fresh token (verified repeatedly, browser-identical headers included),
 *             so these servers are broken on anikage.cc itself. v2 does NOT emit dead prox
 *             links (they were the "some error" links reported on v1); the generic embed scan
 *             still runs so the servers recover automatically if the site fixes its prox.
 *
 *  Movies: format=MOVIE -> emitted as TvType.Anime with the single movie episode added under BOTH
 *  dub statuses, so CloudStream's Sub/Dub selector appears and each status plays its own variant
 *  (verified: Koe no Katachi sub+dub both extract and play). CloudStream hides the selector for
 *  TvType.AnimeMovie, so plain Anime is used — same approach as AniKuro v3/v4.
 *
 *  Poster DUB badge: catalog items carry no dub flag; resolved via batched ALIASED AniList Media
 *  queries (English voice actors) — AniList's Page.media(id_in:) returns EMPTY for anonymous
 *  queries as of 2026-08, singular aliased Media(id:) works (verified 9/9 + 30/30 against site
 *  truth on AniKuro v4). Per-episode truth from /servers feeds the cache in load().
 *
 *  NiceHttp notes: `timeout` is SECONDS; Response status is `.code`.
 */
class AniKage : MainAPI() {
    override var mainUrl = "https://anikage.cc"
    override var name = "AniKage"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        const val TAG = "AniKage"

        /** NiceHttp timeouts are SECONDS — do not pass ms! */
        const val TIMEOUT_CATALOG = 15L
        const val TIMEOUT_INFO = 20L
        const val TIMEOUT_EPISODES = 30L
        const val TIMEOUT_SOURCES = 45L
        const val TIMEOUT_EMBED = 20L
        const val TIMEOUT_MASTER = 12L

        /** The site's server ids (from /episodes/{n}/servers, live-verified). */
        val SERVERS = listOf("koto", "kiwi", "uwu", "neko", "megg", "dib", "wave")

        private const val ANILIST_API = "https://graphql.anilist.co"

        // AniList (verified live 2026): Page { media(id_in: [...]) } returns EMPTY for
        // anonymous queries — singular Media(id:) works, so dub status is batched through
        // ALIASED Media fields. AniList only resolves voiceActors when the edge also
        // selects node { id }.
        private const val ANILIST_BATCH = 20
        private const val ANILIST_MEDIA_FIELDS =
            "id characters(perPage: 12) { edges { voiceActors { languageV2 } node { id } } }"

        private fun buildDubQuery(ids: List<Int>): String = buildString {
            append("query { ")
            ids.forEachIndexed { i, id ->
                append("m").append(i).append(": Media(id: ").append(id)
                    .append(", type: ANIME) { ").append(ANILIST_MEDIA_FIELDS).append(" } ")
            }
            append("}")
        }
    }

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://anikage.cc/",
        "Origin" to "https://anikage.cc",
    )

    private val htmlHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    /**
     * GET with path-only discipline: callers pass PATHS (never full URLs) so the host can
     * never be double-prefixed (the bug class that killed AniKuro v3's links).
     * Non-JSON bodies count as failure.
     */
    private suspend fun apiGet(path: String, timeout: Long): String? {
        val url = if (path.startsWith("http")) {
            Log.w(TAG, "apiGet got absolute URL (normalized): $path")
            path
        } else "$mainUrl$path"
        return try {
            val text = app.get(url, headers = apiHeaders, timeout = timeout).text
            val trimmed = text.trimStart()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                Log.d(TAG, "non-JSON for $path (${trimmed.take(60)})")
                null
            } else text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "apiGet failed for $path: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ DTOs

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Titles(val romaji: String? = null, val english: String? = null, val native: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CoverImage(val large: String? = null, val medium: String? = null, val extraLarge: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BrowseItem(
        val slug: String? = null,
        val anilistId: Int? = null,
        val title: Titles? = null,
        val coverImage: CoverImage? = null,
        val format: String? = null,
        val status: String? = null,
        val season: String? = null,
        val year: Int? = null,
        val totalEpisodes: Int? = null,
        val averageScore: Int? = null,
        val genres: List<String>? = null,
        val isAdult: Boolean? = null,
        val type: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BrowseEnvelope(
        val data: List<BrowseItem>? = null,
        val count: Int? = null,
        val total: Int? = null,
        val page: Int? = null,
        val hasNext: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class InfoAnime(
        val slug: String? = null,
        val anilistId: Int? = null,
        val malId: Int? = null,
        val title: Titles? = null,
        val description: String? = null,
        val bannerImage: String? = null,
        val trailerId: String? = null,
        val format: String? = null,
        val status: String? = null,
        val season: String? = null,
        val year: Int? = null,
        val totalEpisodes: Int? = null,
        val duration: Int? = null,
        val averageScore: Int? = null,
        val meanScore: Int? = null,
        val genres: List<String>? = null,
        val coverImage: CoverImage? = null,
        val isAdult: Boolean? = null,
        val type: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class InfoEnvelope(val anime: InfoAnime? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        val id: String? = null,
        val slug: String? = null,
        val number: Int? = null,
        val title: String? = null,
        val titleRomaji: String? = null,
        val titleNative: String? = null,
        val description: String? = null,
        val image: String? = null,
        val airDate: String? = null,
        val runtime: Int? = null,
        val isFiller: Boolean? = null,
        val isRecap: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServerItem(val id: String? = null, val providerId: String? = null, val subTypes: List<String>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServersEnvelope(val servers: List<ServerItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceItem(
        val url: String? = null,
        val quality: String? = null,
        val isM3U8: Boolean? = null,
        val embedUrl: String? = null,
        val type: String? = null,
        val server: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceSubtitle(
        val file: String? = null,
        val url: String? = null,
        val label: String? = null,
        val lang: String? = null,
        val language: String? = null,
        val kind: String? = null,
        val `default`: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EmbedItem(val url: String? = null, val type: String? = null, val server: String? = null, val status: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EmbedOption(val key: String? = null, val label: String? = null, val url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourcesEnvelope(
        val slug: String? = null,
        val number: Int? = null,
        val providerId: String? = null,
        val subType: String? = null,
        val sources: List<SourceItem>? = null,
        val subtitles: List<SourceSubtitle>? = null,
        val embeds: List<EmbedItem>? = null,
        val embedOptions: List<EmbedOption>? = null,
        val headers: Map<String, String>? = null,
    )

    /** MegaPlay-family getSourcesNew response (koto's megaplay.buzz / vidtube.site embeds). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaSources(val file: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaTrack(val file: String? = null, val label: String? = null, val kind: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaSourcesResp(
        val sources: MegaSources? = null,
        val tracks: List<MegaTrack>? = null,
    )

    // --- AniList dub detection DTOs (aliased singular Media query)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListAliasResp(val data: Map<String, AniListMedia?>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListMedia(val id: Int? = null, val characters: AniListCharacters? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListCharacters(val edges: List<AniListEdge>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListEdge(val voiceActors: List<AniListVoiceActor>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListVoiceActor(val languageV2: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LinkData(val slug: String, val ep: Int, val variant: String)

    // ------------------------------------------------- dub cache (AniList-backed)

    /**
     * Catalog items carry no dub flag, so cards would all show "SUB". Dub existence is
     * derived from AniList character voiceActors (any English VA) — the same method the
     * site's own ecosystem uses — batched through ALIASED singular Media fields, cached
     * for the session; load() also feeds per-episode /servers variant truth in.
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

        private fun hasEnglishVa(media: AniListMedia?): Boolean? {
            if (media?.id == null) return null
            return media.characters?.edges.orEmpty().any { edge ->
                edge.voiceActors.orEmpty().any { it.languageV2.equals("English", true) }
            }
        }

        /** One POST with one retry on 429/5xx. Returns the parsed alias map or null. Never throws. */
        private suspend fun postAliasChunk(chunk: List<Int>): Map<String, AniListMedia?>? {
            val headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            )
            repeat(2) { attempt ->
                try {
                    val r = app.post(
                        ANILIST_API,
                        headers = headers,
                        json = mapOf("query" to buildDubQuery(chunk)),
                        timeout = TIMEOUT_CATALOG,
                    )
                    if (r.code == 429 || r.code >= 500) {
                        kotlinx.coroutines.delay(1200L * (attempt + 1))
                        return@repeat
                    }
                    return try {
                        parseJson<AniListAliasResp>(r.text).data
                    } catch (e: Exception) {
                        Log.d(TAG, "anilist dub parse failed: ${e.message}")
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "anilist dub batch failed: ${e.message}")
                    return null
                }
            }
            return null
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
                    for (chunk in still.chunked(ANILIST_BATCH)) {
                        val aliasMap = postAliasChunk(chunk) ?: continue
                        for ((_, media) in aliasMap) {
                            // Aliases for unknown/filtered ids come back null -> leave uncached.
                            val verdict = hasEnglishVa(media) ?: continue
                            put(media!!.id!!, verdict)
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
        "browse?sort=TRENDING_DESC" to "Trending",
        "browse?sort=POPULARITY_DESC" to "Popular",
        "browse?sort=SCORE_DESC" to "Top Rated",
        "browse?status=RELEASING&sort=TRENDING_DESC" to "Currently Airing",
        "browse?format=MOVIE&sort=POPULARITY_DESC" to "Movies",
        "browse?genres=Action&sort=POPULARITY_DESC" to "Action",
        "browse?genres=Fantasy&sort=POPULARITY_DESC" to "Fantasy",
        "browse?genres=Romance&sort=POPULARITY_DESC" to "Romance",
        "browse?genres=Comedy&sort=POPULARITY_DESC" to "Comedy",
        "browse?genres=Sports&sort=POPULARITY_DESC" to "Sports",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = try {
            val sep = if (request.data.contains("?")) "&" else "?"
            apiGet("/api/media/anime/${request.data}${sep}page=$page", TIMEOUT_CATALOG)
                ?.let { parseJson<BrowseEnvelope>(it) }?.data
        } catch (e: Exception) {
            Log.e(TAG, "main page failed: ${e.message}")
            null
        }
        val home = annotateAndMap(items.orEmpty())
        val hasNext = home.isNotEmpty() // browse always reports more until an empty page
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    /** Resolves real dub existence for the page (batched) then maps to search responses. */
    private suspend fun annotateAndMap(items: List<BrowseItem>): List<SearchResponse> {
        DubCache.annotate(items.map { it.anilistId })
        return items.mapNotNull { it.toSearchResponse() }
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val env = apiGet(
                "/api/media/anime/browse?q=" + java.net.URLEncoder.encode(query, "UTF-8"),
                TIMEOUT_CATALOG,
            )?.let { parseJson<BrowseEnvelope>(it) }
            annotateAndMap(env?.data.orEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    // ----------------------------------------------------------------- load

    private fun BrowseItem.toSearchResponse(): SearchResponse? {
        val slug = slug ?: return null
        if (isAdult == true) return null
        val t = title
        val display = t?.english ?: t?.romaji ?: t?.native ?: return null
        val id = anilistId
        val dub = id != null && DubCache.peek(id) == true
        return newAnimeSearchResponse(display, "$mainUrl/anime/info/$slug", formatToTvType(format)) {
            this.posterUrl = coverImage?.extraLarge ?: coverImage?.large ?: coverImage?.medium
            this.otherName = t?.native
            addDubStatus(dubExist = dub, subExist = true)
        }
    }

    /**
     * MOVIE format maps to TvType.Anime (NOT AnimeMovie): CloudStream hides the Sub/Dub
     * selector for AnimeMovie and always plays the first episodes-map entry. As a plain
     * anime with one "Complete Movie" episode per dub status, the selector appears and
     * each status plays its own variant (verified live: Koe no Katachi sub+dub).
     */
    private fun formatToTvType(format: String?): TvType = when (format?.uppercase()) {
        "OVA", "ONA", "SPECIAL", "MUSIC" -> TvType.OVA
        else -> TvType.Anime
    }

    private suspend fun fetchServers(slug: String, ep: Int): List<ServerItem> = try {
        apiGet("/api/media/anime/$slug/episodes/$ep/servers", TIMEOUT_EPISODES)
            ?.let { parseJson<ServersEnvelope>(it) }?.servers
    } catch (e: Exception) {
        Log.d(TAG, "servers failed for $slug:$ep: ${e.message}")
        null
    }.orEmpty()

    /** True when any server for this episode exposes the given subType ("sub"/"dub"). */
    private fun List<ServerItem>.hasVariant(variant: String): Boolean =
        any { it.subTypes.orEmpty().any { v -> v.equals(variant, true) } }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringBefore("?").substringAfterLast("/")
        if (slug.isBlank()) return null

        val info = try {
            apiGet("/api/media/anime/$slug", TIMEOUT_INFO)?.let { parseJson<InfoEnvelope>(it) }?.anime
        } catch (e: Exception) {
            Log.e(TAG, "info failed for $slug: ${e.message}")
            null
        }

        val eps = try {
            apiGet("/api/media/anime/$slug/episodes", TIMEOUT_EPISODES)
                ?.let { parseJson<List<EpisodeItem>>(it) }
        } catch (e: Exception) {
            Log.e(TAG, "episodes failed for $slug: ${e.message}")
            null
        }.orEmpty().sortedBy { it.number ?: Int.MAX_VALUE }

        if (info == null && eps.isEmpty()) return null

        val t = info?.title
        val display = t?.english ?: t?.romaji ?: t?.native ?: slug
        val tvType = formatToTvType(info?.format)
        val isMovie = info?.format?.uppercase() == "MOVIE" || (eps.size <= 1 && info != null &&
            info.format?.uppercase() in listOf(null, "MOVIE"))

        // Per-episode dub truth from the /servers endpoint (checked first + last episode —
        // dub availability across all episodes is derived from these, matching the site's
        // own lazy per-episode server loading).
        var hasDub = false
        var hasSub = eps.isNotEmpty()
        val checkEps = linkedSetOf<Int>()
        eps.firstOrNull()?.number?.let { checkEps.add(it) }
        eps.lastOrNull()?.number?.let { checkEps.add(it) }
        for (ep in checkEps) {
            val servers = fetchServers(slug, ep)
            if (servers.hasVariant("dub")) hasDub = true
            if (servers.hasVariant("sub")) hasSub = true
        }
        // Feed the learned truth into the card badge cache (keyed by AniList id).
        info?.anilistId?.takeIf { it > 0 }?.let { id ->
            if (hasDub) DubCache.put(id, true)
        }

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        for (ep in eps) {
            val num = ep.number ?: continue
            val isSingle = eps.size == 1
            val name = when {
                isSingle && isMovie -> "Complete Movie"
                ep.title.isNullOrBlank() -> "Episode $num"
                else -> ep.title
            }
            val airDate = parseAirDate(ep.airDate)
            val builder: (Episode).() -> Unit = {
                this.name = name
                this.episode = num
                this.posterUrl = ep.image
                this.description = ep.description
                if (airDate != null) this.addDate(airDate)
                if (ep.isFiller == true) this.description = (this.description.orEmpty() + "\n\n[Filler]").trim()
            }
            subEps.add(newEpisode(LinkData(slug = slug, ep = num, variant = "sub").toJson(), builder))
            if (hasDub) dubEps.add(newEpisode(LinkData(slug = slug, ep = num, variant = "dub").toJson(), builder))
        }

        return newAnimeLoadResponse(display, "$mainUrl/anime/info/$slug", tvType) {
            this.posterUrl = info?.coverImage?.extraLarge ?: info?.coverImage?.large
            this.backgroundPosterUrl = info?.bannerImage
            this.plot = info?.description
            this.tags = info?.genres
            this.year = info?.year
            this.showStatus = when (info?.status?.uppercase()) {
                "RELEASING" -> ShowStatus.Ongoing
                "FINISHED" -> ShowStatus.Completed
                else -> null
            }
            val score = info?.averageScore ?: info?.meanScore
            score?.let { this.score = Score.from10((it / 10.0).toString()) }
            addMalId(info?.malId?.takeIf { it > 0 })
            addAniListId(info?.anilistId?.takeIf { it > 0 })
            info?.trailerId?.takeIf { it.isNotBlank() }?.let { addTrailer("https://youtube.com/watch?v=$it") }
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

        // Per-episode truth: which servers actually expose this variant?
        val servers = fetchServers(link.slug, link.ep)
            .filter { it.subTypes.orEmpty().any { v -> v.equals(variant, true) } }
            .mapNotNull { it.id }
        val serverList = if (servers.isEmpty()) SERVERS else servers

        val seenUrls = HashSet<String>()
        val seenSubUrls = HashSet<String>()
        coroutineScope {
            serverList.amap { server ->
                async {
                    try {
                        extractServer(server, link.slug, link.ep, variant, seenUrls, seenSubUrls, subtitleCallback, callback)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "$server failed: ${e.message}")
                    }
                }
            }
        }
        return true
    }

    private data class PlayLink(val url: String, val referer: String?, val quality: String?, val tag: String? = null)
    private data class PlaySub(val url: String, val label: String, val referer: String?)
    private data class ExtractResult(val links: List<PlayLink>, val subs: List<PlaySub>)

    private fun serverLabel(server: String): String = when (server) {
        "koto" -> "Koto"
        "kiwi" -> "Kiwi"
        "uwu" -> "Uwu"
        "neko" -> "Neko"
        "megg" -> "Megg"
        "dib" -> "Dib"
        "wave" -> "Wave"
        else -> server.replaceFirstChar { it.uppercase() }
    }

    private fun qualityLabel(base: String, url: String, quality: String?, tag: String?): String {
        val q = when {
            quality.isNullOrBlank() -> null
            quality.equals("auto", true) -> null
            quality.contains("hd-1", true) -> "1080p"
            quality.contains("hd-2", true) -> "HD"
            quality.contains("hd", true) -> quality.uppercase()
            else -> quality
        }
        var label = if (q != null) "$base \u00b7 $q" else base
        if (tag != null) label = "$label \u00b7 $tag"
        return label
    }

    /** GETs the /sources payload with retries — the API rate-limits hard (429s) under load. */
    private suspend fun fetchSourcesEnvelope(
        slug: String,
        ep: Int,
        server: String,
        variant: String,
    ): SourcesEnvelope? {
        val path = "/api/media/anime/$slug/episodes/$ep/sources?provider=$server&lang=$variant&server=$server"
        for (attempt in 0 until 3) {
            val text = apiGet(path, TIMEOUT_SOURCES)
            if (text != null) {
                val parsed = try {
                    parseJson<SourcesEnvelope>(text)
                } catch (e: Exception) {
                    Log.d(TAG, "$server sources parse failed: ${e.message}")
                    null
                }
                if (parsed != null) return parsed
            }
            if (attempt < 2) kotlinx.coroutines.delay(1800L * (attempt + 1))
        }
        return null
    }

    /** Subtitles hidden in an embed URL's query string (?sub=, ?caption_1=&sub_1=, ?c1_file=&c1_label=). */
    private fun embedQuerySubs(embedUrl: String): List<PlaySub> {
        val out = ArrayList<PlaySub>()
        val query = embedUrl.substringAfter("?", "")
        if (query.isBlank()) return out
        val decodedPairs = query.split("&").mapNotNull { pair ->
            val k = pair.substringBefore("=")
            val v = try {
                java.net.URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8")
            } catch (e: Exception) {
                pair.substringAfter("=", "")
            }
            k to v
        }
        fun param(name: String): String? = decodedPairs.firstOrNull { it.first == name }?.second
        for ((k, v) in decodedPairs) {
            if (!v.startsWith("http")) continue
            val label = when (k) {
                "sub" -> "English"
                "caption_1" -> param("sub_1")?.takeIf { it.isNotBlank() } ?: "English"
                "c1_file" -> param("c1_label")?.takeIf { it.isNotBlank() } ?: "English"
                else -> null
            } ?: continue
            out.add(PlaySub(v, label, null))
        }
        return out
    }

    private suspend fun extractServer(
        server: String,
        slug: String,
        ep: Int,
        variant: String,
        seenUrls: MutableSet<String>,
        seenSubUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val env = fetchSourcesEnvelope(slug, ep, server, variant) ?: return
        val label = serverLabel(server)
        val emitted = ArrayList<PlayLink>()

        // The payload can carry embeds in THREE places; merge them all (order-stable, deduped):
        //   1. sources[].embedUrl  — the canonical per-source embed
        //   2. embedOptions[]      — the site's embed picker (E-Koto/E-Wish/E-Neko/E-Ken)
        //   3. embeds[]            — full mirror list (StreamHG/Earnvids/Doodstream/...)
        val candidates = LinkedHashSet<String>()
        for (s in env.sources.orEmpty()) s.embedUrl?.takeIf { it.startsWith("http") }?.let { candidates.add(it) }
        for (o in env.embedOptions.orEmpty()) o.url?.takeIf { it.startsWith("http") }?.let { candidates.add(it) }
        for (e in env.embeds.orEmpty()) e.url?.takeIf { it.startsWith("http") }?.let { candidates.add(it) }

        // v2 NOTE: encrypted-token sources WITHOUT any embed are deliberately NOT emitted.
        // The site resolves them via prox.anikage.cc, which now answers 400 "bad url" for
        // every fresh token (verified live) — emitting those links produced the dead
        // "some error" entries users reported on v1. The generic embed scan below still
        // runs, so these servers recover automatically if the site ever fixes its prox.

        for (embed in candidates) {
            val res = try {
                extractEmbed(label, embed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "$label embed extract failed: ${e.message}")
                ExtractResult(emptyList(), emptyList())
            }
            for (l in res.links) {
                if (seenUrls.add(l.url)) emitted.add(l)
            }
            val allSubs = ArrayList<PlaySub>(res.subs)
            allSubs.addAll(embedQuerySubs(embed))
            for (sub in allSubs) {
                if (!sub.url.startsWith("http")) continue
                if (seenSubUrls.add(sub.url)) {
                    subtitleCallback.invoke(
                        newSubtitleFile(sub.label, sub.url) {
                            sub.referer?.let { this.headers = mapOf("Referer" to it) }
                        }
                    )
                }
            }
        }

        // Payload subtitles are encrypted prox-tokens (same broken prox) — only emit
        // plaintext ones, if the API ever returns them directly.
        for (st in env.subtitles.orEmpty()) {
            val label0 = st.label ?: st.lang ?: st.language ?: "English"
            val url = st.file ?: st.url ?: continue
            if (url.startsWith("http") && seenSubUrls.add(url)) {
                subtitleCallback.invoke(newSubtitleFile(label0, url))
            }
        }

        for (l in emitted) {
            val name = qualityLabel(label, l.url, l.quality, l.tag)
            callback.invoke(
                newExtractorLink(name, name, l.url, type = ExtractorLinkType.M3U8) {
                    l.referer?.let { this.referer = it }
                }
            )
        }
    }

    /**
     * Extracts playable masters + plaintext subtitles from an embed URL. Handles:
     *  1. MegaPlay-family (megaplay.buzz, vidtube.site): data-id -> /stream/getSourcesNew?id=
     *     (AJAX header, Referer = the embed URL) -> sources.file + tracks (plaintext subs).
     *  2. Inline-const pages (vivibebe.site, bibiemb.xyz): const src = "https://.../master.m3u8".
     *  3. StreamHG/Earnvids (otakuhg.site, otakuvid.online): eval-packed JWPlayer page whose
     *     unpacked JS defines var links={"hls2":"...m3u8","hls3":"..."} — unpacked live-verified.
     *  4. Generic: first .m3u8 found in the page (recovers hosts that come back alive).
     */
    private suspend fun extractEmbed(baseLabel: String, embedUrl: String): ExtractResult {
        val host = embedUrl.substringAfter("://").substringBefore("/")
        val links = ArrayList<PlayLink>()
        val subs = ArrayList<PlaySub>()

        // ---- 1. MegaPlay family (data-id + getSourcesNew).
        val megaBase = when {
            host.endsWith("megaplay.buzz") -> "https://megaplay.buzz"
            host.endsWith("vidtube.site") -> "https://vidtube.site"
            else -> null
        }
        if (megaBase != null) {
            val embedBody = fetchEmbedBody(embedUrl)
            val dataId = embedBody?.let { Regex("data-id=\"(\\d+)\"").find(it)?.groupValues?.get(1) }
            if (dataId != null) {
                val resp = try {
                    app.get(
                        "$megaBase/stream/getSourcesNew?id=$dataId",
                        headers = mapOf(
                            "User-Agent" to apiHeaders["User-Agent"]!!,
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to embedUrl,
                        ),
                        timeout = TIMEOUT_EMBED,
                    ).text
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "$baseLabel getSourcesNew failed: ${e.message}")
                    null
                }
                val mega = resp?.let {
                    try { parseJson<MegaSourcesResp>(it) } catch (e: Exception) { null }
                }
                val file = mega?.sources?.file
                if (!file.isNullOrBlank() && file.startsWith("http")) {
                    links.add(PlayLink(file, "$megaBase/", null))
                    for (t in mega.tracks.orEmpty()) {
                        val tf = t.file ?: continue
                        if (tf.startsWith("http")) {
                            subs.add(PlaySub(tf, t.label ?: "English", "$megaBase/"))
                        }
                    }
                }
            }
            if (links.isNotEmpty()) return ExtractResult(links, subs)
            // else: this mirror's file is expired (vidtube answers plain 404) — the merged
            // candidate list means other mirrors for the same server still get tried.
        }

        // ---- 2/3. Fetch the embed page once and try inline-const, packed-links, then generic.
        val body = fetchEmbedBody(embedUrl) ?: return ExtractResult(links, subs)

        // 2a. VibeNeko family: const src = "https://.../master.m3u8"
        val constSrc = Regex("const\\s+src\\s*=\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\"").find(body)?.groupValues?.get(1)
        if (constSrc != null) {
            links.add(PlayLink(constSrc, "https://$host/", null))
            return ExtractResult(links, subs)
        }

        // 2b. StreamHG/Earnvids: eval-packed setup defining var links={"hls2":...,"hls3":...}.
        val packed = JsPacker.unpack(body)
        if (packed != null) {
            val linksBlock = Regex("var\\s+links\\s*=\\s*\\{[^}]*\\}").find(packed)?.value
            if (linksBlock != null) {
                for (key in listOf("hls2", "hls3", "hls4")) {
                    val u = Regex("\"$key\"\\s*:\\s*\"(https?://[^\"]+)\"").find(linksBlock)?.groupValues?.get(1) ?: continue
                    links.add(PlayLink(u, "https://$host/", null, tag = key.uppercase()))
                }
                if (links.isNotEmpty()) return ExtractResult(links, subs)
            }
        }

        // 3. Generic: first .m3u8 in the page (recovers hosts that come back alive).
        val generic = Regex("(https?://[^\"'\\s<>]+?\\.m3u8[^\"'\\s<>]*)").findAll(body)
            .map { it.groupValues[1] }
            .firstOrNull { !it.contains("proxylist") }
        if (generic != null) {
            links.add(PlayLink(generic, "https://$host/", null))
        }
        return ExtractResult(links, subs)
    }

    /**
     * Minimal unpacker for the SHORT-form JS packer used by otakuhg.site / otakuvid.online:
     *   eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('PAYLOAD',A,C,'K1|K2|...'.split('|')))
     * Base-N token substitution from the highest index down (verified against live pages).
     */
    private object JsPacker {
        private val PAYLOAD_RE = Regex(
            "\\}\\('(.+?)',(\\d+),(\\d+),'(.*?)'\\.split\\('\\|'\\)\\)\\)",
            RegexOption.DOT_MATCHES_ALL,
        )
        private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        fun unpack(body: String): String? {
            val m = PAYLOAD_RE.find(body) ?: return null
            val a = m.groupValues[2].toIntOrNull() ?: return null
            val c = m.groupValues[3].toIntOrNull() ?: return null
            val keys = m.groupValues[4].split('|')
            if (a < 2 || c < 1) return null
            var p = m.groupValues[1]
            for (i in c - 1 downTo 0) {
                val word = keys.getOrNull(i)
                if (word.isNullOrEmpty()) continue
                val token = toBase(i, a)
                p = Regex("\\b${Regex.escape(token)}\\b").replace(p) { word }
            }
            return p
        }

        private fun toBase(n0: Int, base: Int): String {
            var n = n0
            var out = ""
            while (n > 0) {
                out = DIGITS[n % base] + out
                n /= base
            }
            return out.ifEmpty { "0" }
        }
    }

    private suspend fun fetchEmbedBody(embedUrl: String): String? = try {
        app.get(
            embedUrl,
            headers = htmlHeaders + mapOf("Referer" to "https://anikage.cc/"),
            timeout = TIMEOUT_EMBED,
        ).text
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "embed fetch failed: ${e.message}")
        null
    }
}
