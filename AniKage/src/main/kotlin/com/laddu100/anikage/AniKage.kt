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
 *  Source handling (all verified live):
 *   - koto  : embedUrl on megaplay.buzz / vidtube.site (same MegaPlay software).
 *             embed page -> data-id -> {host}/stream/getSourcesNew?id={data-id} (X-Requested-With:
 *             XMLHttpRequest) -> {sources:{file: <m3u8>}, tracks:[{file,label}]} -> play with
 *             Referer https://{embedHost}/ (kryntal/akirax/shiora CDNs 403 without it).
 *   - neko  : embedUrl on vivibebe.site/{code}?sub={vtt} and bibiemb.xyz/{code}h?sub=...
 *             embed page inline: const src = "https://.../master.m3u8" (vivibebe constructible as
 *             /public/stream/{code}/master.m3u8; bibiemb serves via random *.vibevibe.workers.dev).
 *   - dib   : playeng.animeapps.top playsub.php — upstream currently DEAD (serves a parked page);
 *             generic m3u8 scan attempted so it recovers automatically if the host comes back.
 *   - wave  : play.echovideo.ru embed-1/{code} — generic m3u8 scan attempted (filecodes may expire).
 *   - kiwi / uwu("beep") : token-only sources (no embedUrl). The site plays them through
 *             https://prox.anikage.cc/m3u8/{token} (its resolver: Le(url, isM3U8?'m3u8':'stream')).
 *             Emitted as-is, best-effort, same as the site does.
 *   - any source without an embedUrl also goes through the prox resolver path.
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

        const val PROXY_BASE = "https://prox.anikage.cc"

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
    data class SourcesEnvelope(
        val slug: String? = null,
        val number: Int? = null,
        val providerId: String? = null,
        val subType: String? = null,
        val sources: List<SourceItem>? = null,
        val subtitles: List<SourceSubtitle>? = null,
        val embeds: List<SourceSubtitle>? = null,
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
        val env = try {
            apiGet(
                "/api/media/anime/$slug/episodes/$ep/sources?provider=$server&lang=$variant&server=$server",
                TIMEOUT_SOURCES,
            )?.let { parseJson<SourcesEnvelope>(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "$server sources request failed: ${e.message}")
            null
        } ?: return

        val sources = env.sources.orEmpty()
        if (sources.isEmpty()) return

        val label = serverLabel(server)
        val emitted = ArrayList<PlayLink>()

        for (s in sources) {
            val embed = s.embedUrl
            if (!embed.isNullOrBlank() && embed.startsWith("http")) {
                val extracted = extractEmbed(label, embed, s.quality)
                for (e in extracted.links) {
                    if (seenUrls.add(e.url)) emitted.add(e)
                }
                for (sub in extracted.subs) {
                    if (sub.url.startsWith("http") && seenSubUrls.add(sub.url)) {
                        subtitleCallback.invoke(
                            newSubtitleFile(sub.label, sub.url) {
                                sub.referer?.let { this.headers = mapOf("Referer" to it) }
                            }
                        )
                    }
                }
                // Neko-style embeds carry the subtitle in the ?sub= query param.
                val subParam = embed.substringAfter("?sub=", "").substringBefore("&").takeIf { it.startsWith("http") }
                if (subParam != null && seenSubUrls.add(subParam)) {
                    subtitleCallback.invoke(newSubtitleFile("English", subParam))
                }
            } else {
                // Token-only source (kiwi / uwu / anything without an embed) — the site
                // plays these through its own proxy resolver; emit best-effort.
                val tok = s.url ?: continue
                val prox = "$PROXY_BASE/${if (s.isM3U8 != false) "m3u8" else "stream"}/$tok"
                if (seenUrls.add(prox)) emitted.add(PlayLink(prox, "https://anikage.cc/", s.quality))
            }
        }

        // Subtitles carried directly by the /sources payload are encrypted tokens
        // (prox-resolved by the site). Emit them best-effort; plaintext subs come from
        // the MegaPlay-family tracks below.
        for (st in env.subtitles.orEmpty()) {
            val label0 = st.label ?: st.lang ?: st.language ?: "English"
            val proxied = st.file ?: st.url ?: continue
            val url = if (proxied.startsWith("http")) proxied else "$PROXY_BASE/stream/$proxied"
            if (url.startsWith("http") && seenSubUrls.add(url)) {
                subtitleCallback.invoke(newSubtitleFile(label0, url) {
                    this.headers = mapOf("Referer" to "https://anikage.cc/")
                })
            }
        }

        for (l in emitted) {
            val name = qualityLabel(label, l.url, l.quality)
            callback.invoke(
                newExtractorLink(name, name, l.url, type = ExtractorLinkType.M3U8) {
                    l.referer?.let { this.referer = it }
                }
            )
        }
    }

    private data class PlayLink(val url: String, val referer: String?, val quality: String?)
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

    private fun qualityLabel(base: String, url: String, quality: String?): String {
        val q = when {
            quality.isNullOrBlank() -> null
            quality.equals("auto", true) -> null
            quality.contains("hd-1", true) -> "1080p"
            quality.contains("hd-2", true) -> "HD"
            quality.contains("hd", true) -> quality.uppercase()
            else -> quality
        }
        return if (q != null) "$base · $q" else base
    }

    /**
     * Extracts playable masters + plaintext subtitles from an embed page. Handles:
     *  1. MegaPlay-family pages (megaplay.buzz, vidtube.site — same software): data-id ->
     *     /stream/getSourcesNew?id= (AJAX) -> sources.file + tracks (plaintext subs!).
     *  2. Inline-const pages (vivibebe.site, bibiemb.xyz): const src = "https://.../master.m3u8".
     *  3. Generic: first .m3u8 URL found in the page HTML (recovers hosts that come back alive).
     */
    private suspend fun extractEmbed(baseLabel: String, embedUrl: String, quality: String?): ExtractResult {
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
                    links.add(PlayLink(file, "$megaBase/", quality))
                    for (t in mega.tracks.orEmpty()) {
                        val tf = t.file ?: continue
                        if (tf.startsWith("http")) {
                            subs.add(PlaySub(tf, t.label ?: "English", "$megaBase/"))
                        }
                    }
                }
            }
            if (links.isNotEmpty()) return ExtractResult(links, subs)
        }

        // ---- 2/3. Fetch the embed page once and try inline-const then generic scans.
        val body = fetchEmbedBody(embedUrl) ?: return ExtractResult(links, subs)

        val constSrc = Regex("const\\s+src\\s*=\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\"").find(body)?.groupValues?.get(1)
        if (constSrc != null) {
            links.add(PlayLink(constSrc, "https://$host/", quality))
            return ExtractResult(links, subs)
        }

        val generic = Regex("(https?://[^\"'\\s<>]+?\\.m3u8[^\"'\\s<>]*)").findAll(body)
            .map { it.groupValues[1] }
            .firstOrNull { !it.contains("proxylist") }
        if (generic != null) {
            links.add(PlayLink(generic, "https://$host/", quality))
        }
        return ExtractResult(links, subs)
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