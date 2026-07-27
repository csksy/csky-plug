package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MkvBaseProvider : MainAPI() {
    override var mainUrl = "https://mkvbase.site"
    override var name = "MkvBase"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "MkvBase"
    private val ua = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // TMDB for catalog/search/detail metadata. The same key CineTmdb and many other plugins
    // use — it's a public TMDB v3 key. We use TMDB for the home page and search because
    // mkvbase.site itself has no catalog/search endpoint (it's a link search engine, not a
    // content catalog). When the user clicks a result, load() takes the TMDB title and
    // searches mkvbase.site for download links.
    private val tmdbApiKey = "1865f43a0549ca50d341dd9ab8b29f49"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"
    private val tmdbImgOrig = "https://image.tmdb.org/t/p/original"

    override val mainPage = mainPageOf(
        "tmdb_trending" to "Trending",
        "tmdb_popular_movies" to "Popular Movies",
        "tmdb_popular_tv" to "Popular TV Shows",
        "tmdb_top_movies" to "Top Rated Movies",
        "tmdb_top_tv" to "Top Rated TV Shows",
        "tmdb_now_playing" to "Now Playing",
        "tmdb_airing_today" to "Airing Today",
        "tmdb_action" to "Action Movies",
        "tmdb_comedy" to "Comedy Movies",
        "tmdb_horror" to "Horror Movies"
    )

    private suspend fun tmdbGet(path: String): String {
        val sep = if (path.contains("?")) "&" else "?"
        return app.get("$tmdbApi$path${sep}api_key=$tmdbApiKey&language=en-US", timeout = 30_000L).text
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "getMainPage: ${request.name} page=$page")
        return try {
            val (path, mediaType) = when (request.data) {
                "tmdb_trending" -> Pair("/trending/all/day?page=$page", null)
                "tmdb_popular_movies" -> Pair("/movie/popular?page=$page", "movie")
                "tmdb_popular_tv" -> Pair("/tv/popular?page=$page", "tv")
                "tmdb_top_movies" -> Pair("/movie/top_rated?page=$page", "movie")
                "tmdb_top_tv" -> Pair("/tv/top_rated?page=$page", "tv")
                "tmdb_now_playing" -> Pair("/movie/now_playing?page=$page", "movie")
                "tmdb_airing_today" -> Pair("/tv/airing_today?page=$page", "tv")
                "tmdb_action" -> Pair("/discover/movie?page=$page&with_genres=28&sort_by=popularity.desc", "movie")
                "tmdb_comedy" -> Pair("/discover/movie?page=$page&with_genres=35&sort_by=popularity.desc", "movie")
                "tmdb_horror" -> Pair("/discover/movie?page=$page&with_genres=27&sort_by=popularity.desc", "movie")
                else -> return newHomePageResponse(request.name, emptyList())
            }
            val json = tmdbGet(path)
            val resp = parseJson<TmdbResponse>(json)
            val items = resp.results?.mapNotNull { item ->
                val type = mediaType ?: item.media_type
                if (type != "movie" && type != "tv") return@mapNotNull null
                val id = item.id ?: return@mapNotNull null
                val title = if (type == "movie") item.title ?: item.name else item.name ?: item.title
                if (title.isNullOrBlank()) return@mapNotNull null
                val poster = item.poster_path?.let { "$tmdbImg$it" }
                val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
                val year = (item.release_date ?: item.first_air_date)?.take(4)?.toIntOrNull()
                // Data payload encodes TMDB id + type + title so load() can fetch full
                // metadata (backdrop, plot, cast) and then search mkvbase for links.
                newMovieSearchResponse(title, "$mainUrl/$type|$id|$title", tvType) {
                    this.posterUrl = poster
                    this.year = year
                }
            } ?: emptyList()
            Log.d(TAG, "getMainPage: ${request.name} got ${items.size} items")
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search: '$query'")
        if (query.isBlank()) return emptyList()
        return try {
            val json = tmdbGet("/search/multi?query=${URLEncoder.encode(query, "UTF-8")}&page=1")
            val resp = parseJson<TmdbResponse>(json)
            val results = resp.results?.mapNotNull { item ->
                val type = item.media_type
                if (type != "movie" && type != "tv") return@mapNotNull null
                val id = item.id ?: return@mapNotNull null
                val title = if (type == "movie") item.title ?: item.name else item.name ?: item.title
                if (title.isNullOrBlank()) return@mapNotNull null
                val poster = item.poster_path?.let { "$tmdbImg$it" }
                val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
                val year = (item.release_date ?: item.first_air_date)?.take(4)?.toIntOrNull()
                newMovieSearchResponse(title, "$mainUrl/$type|$id|$title", tvType) {
                    this.posterUrl = poster
                    this.year = year
                }
            } ?: emptyList()
            Log.d(TAG, "search: got ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    // Parses the data payload created in getMainPage/search.
    // Format: "https://mkvbase.site/{type}|{tmdbId}|{title}"
    private fun parseDataUrl(url: String): Triple<String, String, String>? {
        val payload = url.substringAfter("$mainUrl/")
        val parts = payload.split("|")
        if (parts.size < 3) return null
        val type = parts[0] // "movie" or "tv"
        val id = parts[1]
        val title = parts.subList(2, parts.size).joinToString("|")
        if (type !in listOf("movie", "tv") || id.isBlank() || title.isBlank()) return null
        return Triple(type, id, title)
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load: url=$url")
        val (mediaType, tmdbId, title) = parseDataUrl(url) ?: run {
            Log.e(TAG, "load: failed to parse url=$url")
            return null
        }
        Log.d(TAG, "load: type=$mediaType tmdbId=$tmdbId title='$title'")

        return try {
            // Fetch full TMDB detail for the hero banner, plot, cast, etc.
            val detailPath = if (mediaType == "movie") {
                "/movie/$tmdbId?append_to_response=credits,recommendations"
            } else {
                "/tv/$tmdbId?append_to_response=credits,recommendations"
            }
            val detail = try {
                parseJson<TmdbDetail>(tmdbGet(detailPath))
            } catch (e: Exception) {
                Log.e(TAG, "load: TMDB detail fetch failed: ${e.message}")
                null
            }

            val posterPath = detail?.poster_path
            val backdropPath = detail?.backdrop_path
            val plot = detail?.overview ?: ""
            val year = (detail?.release_date ?: detail?.first_air_date)?.take(4)?.toIntOrNull()
            val rating = detail?.vote_average
            val runtime = (detail?.runtime?.toInt() ?: detail?.episode_run_time?.firstOrNull()?.toInt())
            val genres = detail?.genres?.mapNotNull { it.name } ?: emptyList()
            val cast = detail?.credits?.cast?.take(15)?.mapNotNull { it.name } ?: emptyList()
            val recommendations = detail?.recommendations?.results?.take(10)?.mapNotNull { rec ->
                val recType = if (rec.media_type == "movie" || rec.media_type == "tv") rec.media_type
                    else if (rec.title != null) "movie" else if (rec.name != null) "tv" else return@mapNotNull null
                val recId = rec.id ?: return@mapNotNull null
                val recTitle = if (recType == "movie") rec.title ?: rec.name else rec.name ?: rec.title
                if (recTitle.isNullOrBlank()) return@mapNotNull null
                newMovieSearchResponse(recTitle, "$mainUrl/$recType|$recId|$recTitle",
                    if (recType == "movie") TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = rec.poster_path?.let { "$tmdbImg$it" }
                    this.year = (rec.release_date ?: rec.first_air_date)?.take(4)?.toIntOrNull()
                }
            } ?: emptyList()

            // Now fetch download links from mkvbase.site.
            val links = fetchLinks(title)
            Log.d(TAG, "load: got ${links.size} links from mkvbase")
            if (links.isEmpty()) {
                Log.e(TAG, "load: no links found for '$title'")
                // Still return a response with metadata so the user sees the detail page
                // (with a "no links" state) rather than a blank error.
            }

            val isSeries = mediaType == "tv"
            val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

            if (isSeries) {
                // Parse episode numbers from link titles. mkvbase links often have
                // "S01E05" or "Episode 5" in the title. Links without an episode number
                // are treated as episode 1, 2, 3... in encounter order.
                val episodes = mutableListOf<Episode>()
                val seenEps = mutableSetOf<String>()
                for (link in links) {
                    val epMatch = Regex("(?i)S(\\d+)E(\\d+)|EPiSODE\\s*(\\d+)|Episode\\s*(\\d+)|EP(\\d+)").find(link.title)
                    val epNum = epMatch?.let {
                        it.groupValues[2].ifBlank { it.groupValues[3] }
                            .ifBlank { it.groupValues[4] }
                            .ifBlank { it.groupValues[5] }
                            .toIntOrNull()
                    } ?: (episodes.size + 1)
                    val seasonNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epKey = "S${seasonNum}E${epNum}"
                    if (seenEps.add(epKey)) {
                        episodes.add(newEpisode(link.toJson()) {
                            this.episode = epNum
                            this.season = seasonNum
                            this.name = link.title.take(80)
                        })
                    }
                }
                Log.d(TAG, "load: ${episodes.size} episodes")
                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = posterPath?.let { "$tmdbImg$it" }
                    this.backgroundPosterUrl = backdropPath?.let { "$tmdbImgOrig$it" }
                    this.plot = plot
                    this.year = year
                    this.tags = genres
                    this.actors = cast.map { ActorData(Actor(it)) }
                    this.score = rating?.let { Score.from10(it) }
                    this.duration = runtime
                    this.recommendations = recommendations
                }
            } else {
                newMovieLoadResponse(title, url, tvType, links.toJson()) {
                    this.posterUrl = posterPath?.let { "$tmdbImg$it" }
                    this.backgroundPosterUrl = backdropPath?.let { "$tmdbImgOrig$it" }
                    this.plot = plot
                    this.year = year
                    this.tags = genres
                    this.actors = cast.map { ActorData(Actor(it)) }
                    this.score = rating?.let { Score.from10(it) }
                    this.duration = runtime
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: data length=${data.length}")
        val links = try {
            if (data.startsWith("[")) parseJson<List<MkvLink>>(data)
            else listOf(parseJson<MkvLink>(data))
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: parse error: ${e.message}")
            return false
        }
        Log.d(TAG, "loadLinks: ${links.size} links to process")

        // Process ALL links IN PARALLEL. Sequential processing of 49 links at 3s each
        // would take 147s and CloudStream cancels the coroutine after ~30s, causing
        // "Job was cancelled" errors and missing links. Parallel processing with a
        // bounded concurrency (Semaphore) keeps total time under 30s even with 50 links.
        //
        // For GDFlix/HubCloud URLs, we use loadExtractor() which delegates to registered
        // ExtractorApi instances from other plugins (e.g., HDhub4u registers GDFlix and
        // HubCloud extractors). If no extractor is registered, we fall back to our own
        // resolver, then finally pass the URL as-is.
        val found = java.util.concurrent.atomic.AtomicBoolean(false)
        val semaphore = kotlinx.coroutines.sync.Semaphore(10) // max 10 concurrent resolutions
        coroutineScope {
            links.mapIndexed { index, link ->
                async {
                    semaphore.withPermit {
                        try {
                            val quality = parseQualityFromTitle(link.title)
                            val label = cleanLinkTitle(link.title).take(60)
                            Log.d(TAG, "loadLinks: [$index] $label quality=$quality url=${link.url}")

                            when {
                                link.url.contains("gdflix") || link.url.contains("gdlink") -> {
                                    // Try loadExtractor first (uses registered ExtractorApi from
                                    // other plugins like HDhub4u/Movies4u). If that fails, try
                                    // our own resolver. If both fail, pass URL as-is.
                                    var resolved = false
                                    try {
                                        resolved = loadExtractor(link.url, "$mainUrl/", subtitleCallback, callback)
                                    } catch (e: Exception) {
                                        Log.d(TAG, "loadLinks: [$index] loadExtractor failed: ${e.message}")
                                    }
                                    if (!resolved) {
                                        val directUrl = resolveGdFlix(link.url)
                                        if (directUrl != null) {
                                            Log.d(TAG, "loadLinks: [$index] GDFlix resolved: ${directUrl.take(80)}")
                                            callback.invoke(newExtractorLink("MkvBase", "GDFlix - $label", directUrl, ExtractorLinkType.VIDEO) { this.quality = quality })
                                            found.set(true)
                                        } else {
                                            Log.d(TAG, "loadLinks: [$index] GDFlix unresolved, passing as-is")
                                            callback.invoke(newExtractorLink("MkvBase", label, link.url, ExtractorLinkType.VIDEO) { this.quality = quality })
                                            found.set(true)
                                        }
                                    } else {
                                        found.set(true)
                                    }
                                }
                                link.url.contains("hubcloud") -> {
                                    // Same approach: try loadExtractor first, then our resolver, then as-is.
                                    var resolved = false
                                    try {
                                        resolved = loadExtractor(link.url, "$mainUrl/", subtitleCallback, callback)
                                    } catch (e: Exception) {
                                        Log.d(TAG, "loadLinks: [$index] loadExtractor failed: ${e.message}")
                                    }
                                    if (!resolved) {
                                        val directUrl = resolveHubCloud(link.url)
                                        if (directUrl != null) {
                                            Log.d(TAG, "loadLinks: [$index] HubCloud resolved: ${directUrl.take(80)}")
                                            callback.invoke(newExtractorLink("MkvBase", "HubCloud - $label", directUrl, ExtractorLinkType.VIDEO) { this.quality = quality })
                                            found.set(true)
                                        } else {
                                            Log.d(TAG, "loadLinks: [$index] HubCloud unresolved, passing as-is")
                                            callback.invoke(newExtractorLink("MkvBase", label, link.url, ExtractorLinkType.VIDEO) { this.quality = quality })
                                            found.set(true)
                                        }
                                    } else {
                                        found.set(true)
                                    }
                                }
                                else -> {
                                    callback.invoke(newExtractorLink("MkvBase", label, link.url, ExtractorLinkType.VIDEO) { this.quality = quality })
                                    found.set(true)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadLinks: [$index] error: ${e.message}")
                        }
                    }
                }
            }.awaitAll()
        }
        Log.d(TAG, "loadLinks: done, found=${found.get()}")
        return found.get()
    }

    // =========================================================================
    // mkvbase.site API client
    // =========================================================================
    //
    // The mkvbase.site /api/links endpoint is protected by a custom anti-bot scheme that
    // combines Cloudflare with a proof-of-work + HMAC signature. The full flow (reverse-
    // engineered from the site's JS at /_next/static/chunks/0up108_bmqdzq.js) is:
    //
    // 1. Load the main page (https://mkvbase.site/) through mkvBaseGet to obtain CF
    //    cookies (cf_clearance) AND mkvbase session cookies (mkv_client_key, mkv_seq,
    //    mkv_challenge). The mkv_challenge cookie has format "seed:difficulty:expiry:hash"
    //    (URL-encoded, so ":" becomes "%3A").
    //
    // 2. XOR-encode the search query with (timestamp % 256). Each byte is XOR'd and
    //    formatted as 2-digit hex. This produces the "q" parameter.
    //
    // 3. Compute a proof-of-work nonce. The algorithm is a DOUBLE SHA-256:
    //      hash1 = sha256("seed:nonce")
    //      hash2 = sha256(hash1 + ":" + encodedQuery)
    //    Find the smallest nonce such that hash2 starts with `difficulty` zero chars.
    //    Difficulty is read from mkv_challenge[1] (default 3 if parse fails).
    //
    // 4. Compute an HMAC-SHA-256 signature over "encodedQuery:timestamp:seq:nonce:ent"
    //    using mkv_client_key as the HMAC key. `ent` is mouse-movement entropy (0 for
    //    the initial empty-query call, a positive integer for subsequent searches).
    //
    // 5. GET /api/links?q={encodedQuery}&t={timestamp}&seq={seq}&pow={nonce}&ent={ent}&sig={signature}
    //    with X-Requested-With: XMLHttpRequest and Referer: https://mkvbase.site/.
    //    The response set-cookie headers update mkv_session (counter increments), mkv_seq
    //    (increments), and mkv_challenge (new seed+difficulty). These MUST be used for
    //    the next request — the old cookies are invalidated.
    //
    // The `ent` (entropy) parameter is the key insight the previous implementation missed.
    // The site's JS accumulates |dx|+|dy| of mouse movements into a counter (capped at
    // 50000) and includes it in the signature. A real browser user always has mouse
    // movement, so ent > 0. The plugin sends ent=0 for the initial empty-query call
    // (which the server allows) but MUST send a non-zero ent for actual searches. We
    // use a fixed pseudo-entropy value that the server accepts.

    // Mutex serializes fetchLinks API calls to prevent the "Request sequence out of sync"
    // error. When CloudStream pre-loads multiple detail pages from the home screen, 3+
    // fetchLinks calls fire concurrently. They all read seq=0 from the cookie cache, all
    // compute PoW with seq=0, all call the API. The server only accepts the FIRST call
    // (incrementing seq to 1) and rejects the rest with {"error":"Request sequence out of sync"}.
    // The Mutex ensures calls run one-at-a-time: after each call, the response set-cookie
    // updates seq, and the next call (waiting on the Mutex) reads the fresh seq value.
    private val fetchLinksMutex = Mutex()

    private suspend fun fetchLinks(query: String): List<MkvLink> = fetchLinksMutex.withLock {
        Log.d(TAG, "fetchLinks: query='$query' (mutex acquired)")

        // Step 1: Load the main page through mkvBaseGet to obtain CF + session cookies.
        // This is cached (15h TTL) so subsequent calls skip it.
        val pageResponse = mkvBaseGet(mainUrl, headers = mapOf("Accept" to "text/html"))
        Log.d(TAG, "fetchLinks: page response code=${pageResponse.code}")

        val cookies = MkvBaseCFStore.getCookies()
        if (cookies == null) {
            Log.e(TAG, "fetchLinks: no CF cookies available after page load")
            return emptyList()
        }

        val clientKey = extractCookieValue(cookies, "mkv_client_key")
        if (clientKey == null) {
            Log.e(TAG, "fetchLinks: no mkv_client_key in cookies")
            return emptyList()
        }
        var seq = extractCookieValue(cookies, "mkv_seq") ?: "0"
        val challenge = extractCookieValue(cookies, "mkv_challenge")
        Log.d(TAG, "fetchLinks: clientKey=${clientKey.take(10)}... seq=$seq")

        // Step 2: XOR-encode the query. The timestamp is used as the XOR key (mod 256).
        // The JS uses Date.now() for non-headless browsers. We use the same.
        val timestamp = System.currentTimeMillis()
        val xorKey = (timestamp % 256).toInt()
        val encodedQuery = xorEncode(query, xorKey)

        // Step 3: Compute PoW nonce via double-SHA-256.
        // mkv_challenge format: "seed:difficulty:expiry:hash" (URL-decoded).
        var powNonce = 0
        if (challenge != null) {
            val parts = challenge.split(":")
            val seed = parts.getOrNull(0) ?: ""
            val difficulty = parts.getOrNull(1)?.toIntOrNull() ?: 3
            Log.d(TAG, "fetchLinks: computing PoW seed=${seed.take(10)}... difficulty=$difficulty")
            powNonce = computePoW(seed, encodedQuery, difficulty)
            Log.d(TAG, "fetchLinks: PoW nonce=$powNonce")
        }

        // Step 4: Compute HMAC-SHA-256 signature.
        // ent = entropy. For empty query (trending), use 0. For actual searches, use a
        // non-zero pseudo-entropy value (the server accepts any positive integer <= 50000
        // as long as it's consistent with the signature). We use a small fixed value that
        // mimics a few mouse movements.
        val ent = if (query.isBlank()) 0 else 3559
        val sigInput = "$encodedQuery:$timestamp:$seq:$powNonce:$ent"
        val signature = hmacSha256(clientKey, sigInput)

        // Step 5: Call /api/links. Use mkvBaseGet (NOT app.get) so CF cookies are attached.
        // This is safe because isCloudflareBlocked won't false-positive on mkvbase's own
        // 403 "Human interaction verification failed" JSON error (we explicitly exclude it).
        val apiUrl = "$mainUrl/api/links?q=${URLEncoder.encode(encodedQuery, "UTF-8")}" +
            "&t=$timestamp&seq=$seq&pow=$powNonce&ent=$ent&sig=$signature"
        Log.d(TAG, "fetchLinks: calling API ent=$ent")

        val apiResponse = try {
            mkvBaseGet(apiUrl, headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "fetchLinks: API call failed: ${e.message}")
            return emptyList()
        }

        Log.d(TAG, "fetchLinks: API code=${apiResponse.code} body=${apiResponse.text.take(300)}")

        if (apiResponse.code == 403) {
            val errorBody = apiResponse.text
            if (errorBody.contains("Request sequence out of sync")) {
                // Seq was stale (e.g., cookies from a previous session). Clear cookies,
                // reload the page to get fresh mkv_seq/mkv_challenge, and retry ONCE.
                Log.e(TAG, "fetchLinks: Request sequence out of sync — reloading page and retrying")
                MkvBaseCFStore.clear()
                val retryPageResponse = mkvBaseGet(mainUrl, headers = mapOf("Accept" to "text/html"))
                Log.d(TAG, "fetchLinks: retry page response code=${retryPageResponse.code}")
                val retryCookies = MkvBaseCFStore.getCookies()
                if (retryCookies != null) {
                    val retryClientKey = extractCookieValue(retryCookies, "mkv_client_key")
                    val retrySeq = extractCookieValue(retryCookies, "mkv_seq") ?: "0"
                    val retryChallenge = extractCookieValue(retryCookies, "mkv_challenge")
                    if (retryClientKey != null && retryChallenge != null) {
                        Log.d(TAG, "fetchLinks: retry with fresh seq=$retrySeq")
                        val retryTimestamp = System.currentTimeMillis()
                        val retryXorKey = (retryTimestamp % 256).toInt()
                        val retryEncodedQuery = xorEncode(query, retryXorKey)
                        val retryParts = retryChallenge.split(":")
                        val retrySeed = retryParts.getOrNull(0) ?: ""
                        val retryDifficulty = retryParts.getOrNull(1)?.toIntOrNull() ?: 3
                        val retryPowNonce = computePoW(retrySeed, retryEncodedQuery, retryDifficulty)
                        val retrySigInput = "$retryEncodedQuery:$retryTimestamp:$retrySeq:$retryPowNonce:$ent"
                        val retrySignature = hmacSha256(retryClientKey, retrySigInput)
                        val retryApiUrl = "$mainUrl/api/links?q=${URLEncoder.encode(retryEncodedQuery, "UTF-8")}" +
                            "&t=$retryTimestamp&seq=$retrySeq&pow=$retryPowNonce&ent=$ent&sig=$retrySignature"
                        val retryApiResponse = try {
                            mkvBaseGet(retryApiUrl, headers = mapOf(
                                "Accept" to "*/*",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to "$mainUrl/"
                            ))
                        } catch (e: Exception) {
                            Log.e(TAG, "fetchLinks: retry API call failed: ${e.message}")
                            return emptyList()
                        }
                        Log.d(TAG, "fetchLinks: retry API code=${retryApiResponse.code} body=${retryApiResponse.text.take(300)}")
                        if (retryApiResponse.code == 200) {
                            // Update cookies from retry response
                            try {
                                val retrySetCookies = retryApiResponse.headers.values("set-cookie")
                                if (retrySetCookies.isNotEmpty()) {
                                    val merged = mergeCookies(retryCookies, retrySetCookies)
                                    val ua2 = MkvBaseCFStore.getUserAgent() ?: ua
                                    val host = MkvBaseCFStore.getHost() ?: mainUrl
                                    MkvBaseCFStore.save(merged, ua2, host)
                                }
                            } catch (e: Exception) {}
                            return try {
                                parseJson<LinksResponse>(retryApiResponse.text).results ?: emptyList()
                            } catch (e: Exception) {
                                Log.e(TAG, "fetchLinks: retry parse error: ${e.message}")
                                emptyList()
                            }
                        }
                    }
                }
                return emptyList()
            }
            Log.e(TAG, "fetchLinks: API returned 403 - signature rejected")
            MkvBaseCFStore.clear()
            return emptyList()
        }

        // Step 6: Update cookies from the response set-cookie headers. The server issues
        // new mkv_session, mkv_seq, mkv_challenge cookies after each call. If we don't
        // update our stored cookies, the next call will use stale seq and fail.
        try {
            // NiceResponse exposes all Set-Cookie headers via headers.values("set-cookie").
            // Each entry is one Set-Cookie header value (e.g. "mkv_seq=1; Path=/; ...").
            val setCookies = apiResponse.headers.values("set-cookie")
            if (setCookies.isNotEmpty()) {
                val merged = mergeCookies(cookies, setCookies)
                val ua2 = MkvBaseCFStore.getUserAgent() ?: ua
                val host = MkvBaseCFStore.getHost() ?: mainUrl
                MkvBaseCFStore.save(merged, ua2, host)
                Log.d(TAG, "fetchLinks: updated cookies after API call (new seq=${extractCookieValue(merged, "mkv_seq")})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchLinks: cookie update failed: ${e.message}")
        }

        return try {
            val data = parseJson<LinksResponse>(apiResponse.text)
            val results = data.results ?: emptyList()
            Log.d(TAG, "fetchLinks: got ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "fetchLinks: parse error: ${e.message}")
            emptyList()
        }
    }

    // Double-SHA-256 PoW. Matches the site's JS function p(seed, difficulty, encodedQuery):
    //   for nonce in 0..1_000_000:
    //     hash1 = sha256("seed:nonce")
    //     hash2 = sha256(hash1 + ":" + encodedQuery)
    //     if hash2.startsWith("0".repeat(difficulty)): return nonce
    // The previous implementation was wrong (single sha256 of "seed:query:nonce") which
    // is why the server always rejected our PoW with "Human interaction verification failed".
    private fun computePoW(seed: String, encodedQuery: String, difficulty: Int): Int {
        val target = "0".repeat(difficulty)
        var nonce = 0
        while (nonce < 1_000_000) {
            val hash1 = sha256("$seed:$nonce")
            val hash2 = sha256("$hash1:$encodedQuery")
            if (hash2.startsWith(target)) return nonce
            nonce++
        }
        return 0
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun xorEncode(input: String, key: Int): String {
        return input.toCharArray().joinToString("") { String.format("%02x", it.code xor key) }
    }

    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // Extracts a cookie value from a Cookie header string. Cookie values may be
    // URL-encoded (e.g. mkv_challenge uses %3A for ":"). We URL-decode for consistency
    // with how the site's JS reads them (document.cookie gives the raw encoded value,
    // then decodeURIComponent is applied).
    private fun extractCookieValue(cookieStr: String, name: String): String? {
        for (part in cookieStr.split(";")) {
            val trimmed = part.trim()
            if (trimmed.startsWith("$name=")) {
                val raw = trimmed.substringAfter("$name=")
                return try { java.net.URLDecoder.decode(raw, "UTF-8") }
                catch (e: Exception) { raw }
            }
        }
        return null
    }

    // Merges existing cookies with new Set-Cookie values from a response. Existing cookies
    // not in the Set-Cookie list are preserved; cookies in the Set-Cookie list are
    // replaced (or removed if Max-Age=0).
    private fun mergeCookies(existingCookieStr: String, setCookieValues: List<String>): String {
        val existing = mutableMapOf<String, String>()
        for (part in existingCookieStr.split(";")) {
            val trimmed = part.trim()
            if (trimmed.isBlank()) continue
            val eq = trimmed.indexOf("=")
            if (eq > 0) {
                val k = trimmed.substring(0, eq).trim()
                val v = trimmed.substring(eq + 1).trim()
                existing[k] = v
            }
        }
        for (sc in setCookieValues) {
            val trimmed = sc.trim().substringBefore(";").trim()
            if (trimmed.isBlank()) continue
            val eq = trimmed.indexOf("=")
            if (eq > 0) {
                val k = trimmed.substring(0, eq).trim()
                val v = trimmed.substring(eq + 1).trim()
                if (v.equals("", ignoreCase = true) || v.contains("Max-Age=0", ignoreCase = true)) {
                    existing.remove(k)
                } else {
                    existing[k] = v
                }
            }
        }
        return existing.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    // Resolve a GDFlix or gdlink.dev file URL to a direct download URL.
    //
    // The flow (discovered by fetching the actual pages):
    // 1. gdflix.dev/file/XXX or gdlink.dev/file/XXX → redirects to new3.gdflix.io/file/XXX
    // 2. The file page has multiple download buttons. The key one is the
    //    "instant.busycdn.xyz" link which redirects through fastcdn-dl.pages.dev
    //    to a Google Drive direct download URL (video-downloads.googleusercontent.com).
    // 3. We extract the googleusercontent URL from the fastcdn-dl.pages.dev query param.
    //
    // If the instant link is not found (page structure changed), we fall back to
    // trying CloudStream's built-in loadExtractor which may handle it via registered
    // ExtractorApi instances.
    private suspend fun resolveGdFlix(url: String): String? {
        return try {
            // Step 1: Fetch the file page. gdflix.dev/gdlink.dev redirect to new3.gdflix.io.
            // We use allowRedirects=true so OkHttp follows the redirect chain automatically.
            val pageResponse = app.get(url, headers = mapOf(
                "User-Agent" to ua,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ), timeout = 15_000L)
            val html = pageResponse.text
            val soup = Jsoup.parse(html)

            // Step 2: Find the instant.busycdn.xyz download link on the page.
            // This is the most reliable direct download path — it redirects through
            // fastcdn-dl.pages.dev to a Google Drive direct URL.
            val instantLink = soup.selectFirst("a[href*=instant.busycdn.xyz]")?.attr("href")
            if (instantLink != null) {
                Log.d(TAG, "resolveGdFlix: found instant link: ${instantLink.take(80)}")
                // Step 3: Follow the redirect from instant.busycdn.xyz.
                // It 302-redirects to fastcdn-dl.pages.dev/?url=GOOGLE_URL
                // We use allowRedirects=false to capture the redirect URL, then extract
                // the googleusercontent URL from the query parameter.
                val redirectResponse = app.get(instantLink, headers = mapOf(
                    "User-Agent" to ua
                ), allowRedirects = false, timeout = 10_000L)
                val redirectUrl = redirectResponse.headers["location"]
                if (redirectUrl != null && redirectUrl.contains("url=")) {
                    // Extract the googleusercontent URL from the query parameter.
                    val googleUrl = extractQueryParam(redirectUrl, "url")
                    if (googleUrl != null && googleUrl.startsWith("http")) {
                        Log.d(TAG, "resolveGdFlix: extracted google URL: ${googleUrl.take(80)}")
                        return googleUrl
                    }
                }
                // If we couldn't extract the URL from the redirect, try following the
                // full redirect chain and use the final URL.
                Log.d(TAG, "resolveGdFlix: redirect extraction failed, trying full follow")
                val fullResponse = app.get(instantLink, headers = mapOf(
                    "User-Agent" to ua
                ), timeout = 10_000L)
                val finalUrl = fullResponse.url?.toString()
                if (finalUrl != null && (finalUrl.contains("googleusercontent") || finalUrl.contains("google"))) {
                    return finalUrl
                }
            }

            // Fallback: try /cflare/ link (Cloudflare CDN download). The page at
            // /cflare/TIMESTAMP/FILE_ID returns HTML with a JS-based POST to get the
            // download URL. We can't easily replicate the POST, so just pass the
            // original file URL through and let CloudStream's built-in extractor try.
            Log.d(TAG, "resolveGdFlix: no instant link found, returning null")
            null
        } catch (e: Exception) {
            Log.e(TAG, "resolveGdFlix: ${e.message}")
            null
        }
    }

    // Extract a query parameter value from a URL string.
    private fun extractQueryParam(urlStr: String, param: String): String? {
        return try {
            val uri = java.net.URI(urlStr)
            val query = uri.query ?: return null
            val params = query.split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            }
            params[param]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveHubCloud(url: String): String? {
        return try {
            val response = app.get(url, headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/"), timeout = 30_000L)
            val soup = Jsoup.parse(response.text)
            response.headers["HX-Redirect"]?.let { hxRedirect ->
                val redirectUrl = if (hxRedirect.startsWith("http")) hxRedirect else "https://hubcloud.cx$hxRedirect"
                val redirectResponse = app.get(redirectUrl, headers = mapOf("User-Agent" to ua), allowRedirects = false, timeout = 30_000L)
                redirectResponse.headers["location"]?.let { loc ->
                    return if (loc.startsWith("http")) loc else "https://hubcloud.cx$loc"
                }
            }
            soup.selectFirst("a[href*='download'], a[href*='/dl/'], a#download")?.attr("href")?.let { downloadBtn ->
                val fullUrl = if (downloadBtn.startsWith("http")) downloadBtn else "https://hubcloud.cx$downloadBtn"
                val dlResponse = app.get(fullUrl, headers = mapOf("User-Agent" to ua, "Referer" to url), allowRedirects = false, timeout = 30_000L)
                dlResponse.headers["location"]?.let { loc ->
                    return if (loc.startsWith("http")) loc else "https://hubcloud.cx$loc"
                }
                Jsoup.parse(dlResponse.text).selectFirst("a[href~=(?i)\\.(mkv|mp4|avi)]")?.attr("href")?.let { dl ->
                    return if (dl.startsWith("http")) dl else "https://hubcloud.cx$dl"
                }
            }
            soup.selectFirst("a[href~=(?i)\\.(mkv|mp4|avi)]")?.attr("href")?.let { dl ->
                if (dl.startsWith("http")) dl else "https://hubcloud.cx$dl"
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveHubCloud: ${e.message}")
            null
        }
    }

    private fun parseQualityFromTitle(title: String): Int = when {
        // 2160p / 4K / UHD — but NOT "DS4K" (Downscaled 4K, which is actually 1080p or lower).
        // The negative lookbehind (?<![a-z]) ensures "4k" is not preceded by a letter,
        // so "DS4K" doesn't match but standalone "4K" or " 4K " does.
        title.contains(Regex("(?i)2160p|(?<![a-z])4k(?![a-z])|\\buhd\\b")) -> Qualities.P2160.value
        title.contains(Regex("(?i)1080p|fullhd")) -> Qualities.P1080.value
        title.contains(Regex("(?i)720p")) -> Qualities.P720.value
        title.contains(Regex("(?i)480p")) -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private fun cleanLinkTitle(title: String): String {
        return title.replace(Regex("(?i)\\|\\s*GDFlix\\s*"), "")
            .replace(Regex("(?i)GDFlix\\s*\\|\\s*"), "")
            .replace(Regex("\\.mkv$"), "").replace(Regex("\\.mp4$"), "")
            .replace(Regex("\\.avi$"), "").trim()
    }

    // =========================================================================
    // Data classes
    // =========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbResponse(@JsonProperty("results") val results: List<TmdbItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbItem(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val poster_path: String? = null,
        @JsonProperty("backdrop_path") val backdrop_path: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("media_type") val media_type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbDetail(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val poster_path: String? = null,
        @JsonProperty("backdrop_path") val backdrop_path: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("runtime") val runtime: Long? = null,
        @JsonProperty("episode_run_time") val episode_run_time: List<Long>? = null,
        @JsonProperty("vote_average") val vote_average: Double? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null,
        @JsonProperty("recommendations") val recommendations: TmdbResponse? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbGenre(@JsonProperty("name") val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCastMember>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbCastMember(@JsonProperty("name") val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LinksResponse(@JsonProperty("results") val results: List<MkvLink>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MkvLink(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("title") val title: String = "",
        @JsonProperty("url") val url: String = ""
    )
}
