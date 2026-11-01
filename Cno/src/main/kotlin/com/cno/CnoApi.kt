package com.cno

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.laddu100.FirebaseDomainHelper
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CnoApi {
    private const val TAG = "Cno"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

    private const val DEFAULT_BETA = "https://beta.cno.to"

    // every host in the site's family, the edge and both cdns, lets this one
    // referer through, so the player can wear it for playlists and segments
    // alike instead of a per host guess
    private const val STREAM_REFERER = "https://cno.to/"

    // nicehttp counts its timeout in seconds, a mistaken millis value
    // turns into a request that can hang for hours
    private const val CALL_TIMEOUT = 15L

    private const val DNS_TTL_MS = 10 * 60 * 1000L

    @Volatile
    private var betaUrl = DEFAULT_BETA

    @Volatile
    private var domainsRefreshedAt = 0L

    suspend fun refreshDomains() {
        // the firebase map is the same one the repo's other plugins read, so
        // a domain swap for this network only has to be published once
        if (System.currentTimeMillis() - domainsRefreshedAt < 5 * 60 * 1000L) return
        domainsRefreshedAt = System.currentTimeMillis()
        try {
            FirebaseDomainHelper.getDomain("cno_beta")?.let { betaUrl = it.removeSuffix("/") }
        } catch (_: Exception) {
            // keep the last known base, the default has stayed live so far
        }
    }

    fun beta(): String = betaUrl

    fun streamReferer(): String = STREAM_REFERER

    fun artUrl(path: String?, width: String = "w500"): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return "$betaUrl/art/t/p/$width$path"
    }

    // the edge serves the artwork of every title it lists, so posters never
    // depend on image.tmdb.org being reachable from the device
    fun stillUrl(path: String?): String? = artUrl(path, "w300")

    // anime art arrives as site relative paths, they only resolve against
    // the beta base
    fun animeArt(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return "$betaUrl$path"
    }

    private fun originOf(url: String): String = try {
        val cut = url.indexOf('/', url.indexOf("//") + 2)
        if (cut <= 0) url else url.substring(0, cut)
    } catch (_: Exception) {
        url
    }

    private fun hostAndPath(url: String): String {
        val origin = originOf(url)
        return url.removePrefix(origin).take(80).ifBlank { "/" }
    }

    // the same shape of request the site's own pages send, it keeps the
    // plain path alive whenever the edge is feeling permissive
    private fun browserHeaders(url: String, referer: String, accept: String): Map<String, String> {
        return mutableMapOf(
            "User-Agent" to (CnoWebView.userAgent() ?: USER_AGENT),
            "Accept" to accept,
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to if (originOf(url) == originOf(referer)) "same-origin" else "cross-site"
        )
    }

    // a clearance is only honored next to the user agent it was minted for
    private fun clearanceHeaders(url: String): Map<String, String> {
        val jar = CnoWebView.cookies(url) ?: return emptyMap()
        val ua = CnoWebView.userAgent() ?: return emptyMap()
        return mapOf(
            "Cookie" to jar,
            "User-Agent" to ua
        )
    }

    // the edge answers a refused request with a challenge page, a block page
    // or a bare 403, and the shapes have changed between deployments, so
    // anything that is not the expected payload counts as a refusal and
    // wakes the heavier lanes up
    private fun refused(code: Int, body: String, mitigated: String?, contentType: String?): Boolean {
        if (mitigated == "challenge") return true
        if (code == 200 && contentType?.contains("text/html") == true) return true
        if (code == 403 || code == 429 || code >= 500) return true
        return false
    }

    private fun refusedNice(res: com.lagradost.nicehttp.NiceResponse, body: String): Boolean =
        refused(
            res.code,
            body,
            res.headers["cf-mitigated"],
            res.headers["content-type"]
        )

    class DirectOutcome internal constructor(
        val body: String?,
        val refused: Boolean,
        val definitiveMiss: Boolean
    )

    // the first lane: the app's own client with the browser shaped headers,
    // the harvested clearance riding along when one exists. a dead network
    // or a refused answer is told apart from a real miss so the caller can
    // send the heavier lanes in only when they can help
    private suspend fun directOutcome(url: String, referer: String, accept: String): DirectOutcome {
        val headers = browserHeaders(url, referer, accept)
        val clearance = clearanceHeaders(url)
        if (clearance.isNotEmpty()) {
            try {
                val res = app.get(url, headers = headers + clearance, timeout = CALL_TIMEOUT)
                val body = res.text
                if (res.code == 200 && !refusedNice(res, body)) return DirectOutcome(body, false, false)
            } catch (e: Exception) {
                Log.w(TAG, "direct lane with clearance failed ${hostAndPath(url)}: ${e.message}")
            }
        }
        return try {
            val res = app.get(url, headers = headers, timeout = CALL_TIMEOUT)
            val body = res.text
            when {
                res.code == 200 && !refusedNice(res, body) -> DirectOutcome(body, false, false)
                res.code == 404 || res.code == 410 -> DirectOutcome(null, false, true)
                else -> DirectOutcome(null, true, false)
            }
        } catch (e: Exception) {
            // a carrier resolver that forgets the site's name or a middlebox
            // that cuts the handshake lands here, the heavier lanes still
            // have a chance of getting through
            Log.w(TAG, "direct lane failed ${hostAndPath(url)}: ${e.message}")
            DirectOutcome(null, true, false)
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private class DohAnswer(
        @com.fasterxml.jackson.annotation.JsonProperty("Answer")
        val answer: List<DohRecord> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private class DohRecord(val type: Int? = null, val data: String? = null)

    private val dnsCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    // a carrier resolver can be made to forget the site's names while the
    // rest of the internet still answers, so the guarded hosts are resolved
    // over https through google's public resolver before the system is
    // asked. only literal addresses are kept, the resolver also hands back
    // cname strings no socket can dial
    private class GuardedDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == "cno.to" || hostname.endsWith(".cno.to") || hostname == "ani.pm") {
                dohAnswer(hostname)?.let { if (it.isNotEmpty()) return it }
            }
            return Dns.SYSTEM.lookup(hostname)
        }

        private fun dohAnswer(hostname: String): List<InetAddress>? {
            val cached = dnsCache[hostname]
            if (cached != null && System.currentTimeMillis() - cached.first < DNS_TTL_MS) {
                return cached.second
            }
            try {
                val found = mutableListOf<InetAddress>()
                for (qtype in listOf("A", "AAAA")) {
                    val conn = URL("https://dns.google/resolve?name=$hostname&type=$qtype")
                        .openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("User-Agent", USER_AGENT)
                        conn.setRequestProperty("Accept", "application/json")
                        if (conn.responseCode != 200) return null
                        val parsed = parseJson<DohAnswer>(
                            conn.inputStream.bufferedReader().use { it.readText() }
                        )
                        for (record in parsed.answer) {
                            val data = record.data ?: continue
                            val literal = when (record.type) {
                                1 -> Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(data)
                                28 -> data.contains(':') && !data.contains(' ')
                                else -> false
                            }
                            if (literal) {
                                runCatching { InetAddress.getByName(data) }.getOrNull()?.let { found.add(it) }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                dnsCache[hostname] = System.currentTimeMillis() to found
                return found
            } catch (_: Exception) {
                // the resolver itself is out of reach, the system answer is
                // whatever it is
                return null
            }
        }
    }

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(GuardedDns())
            .connectTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT + 5, TimeUnit.SECONDS)
            .build()
    }

    // the second lane: the same browser shaped call placed through a client
    // that dials the guarded hosts at addresses it resolved itself
    private fun dohOutcome(url: String, referer: String, accept: String): DirectOutcome {
        return try {
            val builder = Request.Builder().url(url)
            (browserHeaders(url, referer, accept) + clearanceHeaders(url)).forEach { (k, v) ->
                builder.header(k, v)
            }
            dohClient.newCall(builder.build()).execute().use { res ->
                val body = res.body?.string() ?: ""
                when {
                    res.code == 200 && !refused(
                        res.code,
                        body,
                        res.header("cf-mitigated"),
                        res.header("content-type")
                    ) -> DirectOutcome(body, false, false)

                    res.code == 404 || res.code == 410 -> DirectOutcome(null, false, true)
                    else -> DirectOutcome(null, true, false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "doh lane failed ${hostAndPath(url)}: ${e.message}")
            DirectOutcome(null, true, false)
        }
    }

    enum class Lane { DIRECT, DOH }

    @Volatile
    private var preferredLane: Lane? = null

    class Readable internal constructor(
        val body: String,
        // true when a plain client could read the url, false when only the
        // browser stack got through, the player then has to be served from
        // the local proxy instead of the guarded urls
        val plainOk: Boolean
    )

    /**
     * Everything the plugin reads off the guarded hosts goes through here.
     * The plain lanes go first because they are instant whenever the network
     * lets them through, the doh lane answers the networks whose resolver
     * refuses the site's names, and when both are turned away the request
     * is re-run from inside the site's own page where the webview's browser
     * stack carries it and its clearance lands in the jar for the calls
     * after it. a definitive not found comes back as null without waking
     * the webview up.
     */
    suspend fun readable(url: String, referer: String, accept: String = "application/json"): Readable? {
        val lanes = if (preferredLane == Lane.DOH) {
            listOf(Lane.DOH, Lane.DIRECT)
        } else {
            listOf(Lane.DIRECT, Lane.DOH)
        }
        for (lane in lanes) {
            val outcome = when (lane) {
                Lane.DIRECT -> directOutcome(url, referer, accept)
                Lane.DOH -> dohOutcome(url, referer, accept)
            }
            if (outcome.body != null) {
                preferredLane = lane
                return Readable(outcome.body, true)
            }
            if (outcome.definitiveMiss) return null
        }

        val viaWebView = CnoWebView.fetch(url)
        if (viaWebView != null) return Readable(viaWebView, false)

        // the page session may still have harvested a clearance on its way
        // down, the direct lane gets one more go with it before giving up
        if (clearanceHeaders(url).isNotEmpty()) {
            val retry = directOutcome(url, referer, accept)
            if (retry.body != null) {
                preferredLane = Lane.DIRECT
                return Readable(retry.body, false)
            }
        }
        Log.w(TAG, "every lane refused ${hostAndPath(url)}")
        return null
    }

    suspend fun text(url: String, referer: String, accept: String = "application/json"): String? =
        readable(url, referer, accept)?.body

    // headers for anything the player itself has to pull off the guarded
    // hosts, the playlists, the subtitle files and the cdn segments all
    // accept this one set, the clearance rides along when one exists
    fun streamHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to (CnoWebView.userAgent() ?: USER_AGENT),
            "Referer" to STREAM_REFERER,
            "Accept" to "*/*"
        )
        CnoWebView.cookies(betaUrl)?.let { headers["Cookie"] = it }
        return headers
    }

    suspend fun searchScreen(query: String, page: Int): ScreenSearchResponse? {
        val body = text("$betaUrl/screen-api/search?q=${encode(query)}&type=all&page=$page", "$betaUrl/")
            ?: return null
        return try {
            parseJson<ScreenSearchResponse>(body)
        } catch (e: Exception) {
            Log.d(TAG, "search parse failed: ${e.message}")
            null
        }
    }

    @Volatile
    private var shelfCache: List<ScreenShelf> = emptyList()

    @Volatile
    private var shelfCacheAt = 0L

    suspend fun siteShelves(): List<ScreenShelf> {
        // every mainpage section reads the same shelf document, so it is kept
        // for a few minutes instead of being pulled once per section
        if (System.currentTimeMillis() - shelfCacheAt < 5 * 60 * 1000L && shelfCache.isNotEmpty()) {
            return shelfCache
        }
        // v pins the shelf layout the site itself renders, without it the edge
        // can hand back an older shape
        val body = text("$betaUrl/screen-api/site/dexter?v=8", "$betaUrl/") ?: return shelfCache
        return try {
            val shelves = parseJson<ScreenSite>(body).shelves
            if (shelves.isNotEmpty()) {
                shelfCache = shelves
                shelfCacheAt = System.currentTimeMillis()
            }
            shelves
        } catch (e: Exception) {
            Log.d(TAG, "shelves parse failed: ${e.message}")
            shelfCache
        }
    }

    suspend fun movieDetail(id: Int): ScreenDetail? {
        val body = text("$betaUrl/screen-api/movie/$id", "$betaUrl/") ?: return null
        return try {
            parseJson<ScreenDetail>(body)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun tvDetail(id: Int): ScreenDetail? {
        val body = text("$betaUrl/screen-api/tv/$id", "$betaUrl/") ?: return null
        return try {
            parseJson<ScreenDetail>(body)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun seasonEpisodes(id: Int, season: Int): SeasonResponse? {
        val body = text("$betaUrl/screen-api/tv/$id/season/$season", "$betaUrl/") ?: return null
        return try {
            parseJson<SeasonResponse>(body)
        } catch (_: Exception) {
            null
        }
    }

    data class MasterResult(
        val text: String,
        val hasAudioRenditions: Boolean,
        val variants: List<Variant>
    )

    data class Variant(
        val width: Int,
        val height: Int,
        val url: String
    )

    // one retry: the edge has been seen answering a perfectly valid request
    // with a truncated playlist once before serving the real one
    suspend fun masterPlaylist(path: String): Readable? {
        repeat(2) {
            val readable = readable("$betaUrl$path", "$betaUrl/", accept = "*/*")
            if (readable != null && readable.body.startsWith("#EXTM3U")) return readable
        }
        return null
    }

    // variants in these masters are relative /screen/pl/ paths, the player
    // would resolve them against whatever host it loaded the master from, so
    // they are made absolute here before being handed out as links
    fun parseMaster(body: String): MasterResult? {
        val hasAudio = body.contains("TYPE=AUDIO")
        val variants = mutableListOf<Variant>()
        val lines = body.lines()
        var pendingWidth: Int? = null
        var pendingHeight: Int? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                val resolution = Regex("RESOLUTION=(\\d+)x(\\d+)").find(trimmed)
                pendingWidth = resolution?.groupValues?.get(1)?.toIntOrNull()
                pendingHeight = resolution?.groupValues?.get(2)?.toIntOrNull()
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val width = pendingWidth
                val height = pendingHeight
                if (width != null && width > 0) {
                    val absolute = if (trimmed.startsWith("http")) {
                        trimmed
                    } else {
                        "$betaUrl$trimmed"
                    }
                    variants.add(Variant(width, height ?: 0, absolute))
                }
                pendingWidth = null
                pendingHeight = null
            }
        }
        if (variants.isEmpty() && !hasAudio) return null
        return MasterResult(body, hasAudio, variants)
    }

    suspend fun subsList(path: String): List<SubFile> {
        val body = text("$betaUrl/screen-api/subs/list/$path", "$betaUrl/") ?: return emptyList()
        return try {
            parseJson<SubsList>(body).files
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun subTrackUrl(path: String, lang: String): String {
        return "$betaUrl/screen/subs/$path/$lang.vtt"
    }

    // the anime catalog lives on the beta edge itself and answers plain
    // clients, the partner api on the sibling domain sits behind a harder
    // challenge for no extra data
    suspend fun browseAnime(
        query: String?,
        page: Int,
        season: String? = null,
        year: Int? = null
    ): BrowseResponse? {
        val params = buildList {
            if (!query.isNullOrBlank()) add("q=${encode(query)}")
            add("page=$page")
            add("sfw=1")
            add("sort=trending")
            season?.let { add("season=$it") }
            year?.let { add("year=$it") }
        }
        val body = text("$betaUrl/api/anime/browse?${params.joinToString("&")}", "$betaUrl/") ?: return null
        return try {
            parseJson<BrowseResponse>(body)
        } catch (e: Exception) {
            Log.d(TAG, "browse parse failed: ${e.message}")
            null
        }
    }

    suspend fun animeDetail(anilistId: Int): AnimeDetail? {
        val body = text("$betaUrl/api/anime/ani/$anilistId", "$betaUrl/") ?: return null
        return try {
            parseJson<AnimeDetail>(body)
        } catch (e: Exception) {
            Log.d(TAG, "anime detail parse failed: ${e.message}")
            null
        }
    }

    // the stream folders on the edge are keyed by anilist id plus the slug
    // the site's watch route carries, the slug only lives inside the route
    fun slugOf(routeId: String?): String? {
        if (routeId.isNullOrBlank()) return null
        val parts = routeId.split('-')
        if (parts.size < 3) return null
        return parts.subList(0, parts.size - 2).joinToString("-")
    }

    fun animeMasterUrl(anilistId: Int, slug: String, episode: Int): String {
        return "$betaUrl/hls/$anilistId-$slug/E${"%04d".format(episode)}/master.m3u8"
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
