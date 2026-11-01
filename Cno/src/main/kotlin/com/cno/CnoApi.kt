package com.cno

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.nicehttp.NiceResponse
import com.laddu100.FirebaseDomainHelper

object CnoApi {
    private const val TAG = "Cno"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

    private const val DEFAULT_BETA = "https://beta.cno.to"
    private const val DEFAULT_ANI = "https://ani.pm"

    // nicehttp counts its timeout in seconds, a mistaken millis value
    // turns into a request that can hang for hours
    private const val CALL_TIMEOUT = 15L

    @Volatile
    private var betaUrl = DEFAULT_BETA

    @Volatile
    private var aniUrl = DEFAULT_ANI

    @Volatile
    private var domainsRefreshedAt = 0L

    suspend fun refreshDomains() {
        // the firebase map is the same one the repo's other plugins read, so a
        // domain swap for this network only has to be published once
        if (System.currentTimeMillis() - domainsRefreshedAt < 5 * 60 * 1000L) return
        domainsRefreshedAt = System.currentTimeMillis()
        try {
            FirebaseDomainHelper.getDomain("cno_beta")?.let { betaUrl = it.removeSuffix("/") }
            FirebaseDomainHelper.getDomain("anipm")?.let { aniUrl = it.removeSuffix("/") }
        } catch (_: Exception) {
            // keep the last known bases, both defaults have stayed live so far
        }
    }

    fun beta(): String = betaUrl

    fun ani(): String = aniUrl

    fun artUrl(path: String?, width: String = "w500"): String? {
        if (path.isNullOrBlank()) return null
        return "$betaUrl/art/t/p/$width$path"
    }

    // the edge serves the artwork of every title it lists, so posters never
    // depend on image.tmdb.org being reachable from the device
    fun stillUrl(path: String?): String? = artUrl(path, "w300")

    private fun originOf(url: String): String = try {
        val cut = url.indexOf('/', url.indexOf("//") + 2)
        if (cut <= 0) url else url.substring(0, cut)
    } catch (_: Exception) {
        url
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

    // the edge answers a refused request with one of these, whatever the
    // transport says about itself above that is a real response
    private fun refused(res: NiceResponse, body: String): Boolean {
        if (res.headers["cf-mitigated"] == "challenge") return true
        if (res.code == 200) {
            val type = res.headers["content-type"] ?: ""
            if (type.contains("text/html")) return true
        }
        if (res.code == 403 || res.code == 429 || res.code == 503) {
            return body.contains("challenge-platform") ||
                body.contains("just a moment", true) ||
                body.contains("you have been blocked") ||
                body.contains("attention required", true)
        }
        return false
    }

    private class DirectOutcome(val body: String?, val refusedByEdge: Boolean)

    // both direct attempts in one place: the harvested clearance first, then
    // the plain browser shaped call. A refusal by the edge is told apart from
    // a definitive miss so only the first one wakes the webview up.
    private suspend fun directOutcome(url: String, referer: String, accept: String): DirectOutcome {
        val clearance = clearanceHeaders(url)
        if (clearance.isNotEmpty()) {
            try {
                val res = app.get(
                    url,
                    headers = browserHeaders(url, referer, accept) + clearance,
                    timeout = CALL_TIMEOUT
                )
                val body = res.text
                if (res.code == 200 && !refused(res, body)) return DirectOutcome(body, false)
            } catch (_: Exception) {
            }
        }
        return try {
            val res = app.get(url, headers = browserHeaders(url, referer, accept), timeout = CALL_TIMEOUT)
            val body = res.text
            when {
                res.code == 200 && !refused(res, body) -> DirectOutcome(body, false)
                refused(res, body) || res.code == 403 || res.code == 429 || res.code == 503 ->
                    DirectOutcome(null, true)
                else -> DirectOutcome(null, false)
            }
        } catch (_: Exception) {
            DirectOutcome(null, false)
        }
    }

    /**
     * Everything the plugin reads off either host goes through here. The
     * direct call goes first because it is instant whenever the edge lets
     * it through, and when the edge refuses the request is re-run from
     * inside the site's own page where the webview's browser stack carries
     * it. A definitive not found comes back as null without waking the
     * webview up.
     */
    suspend fun text(url: String, referer: String, accept: String = "application/json"): String? {
        val direct = directOutcome(url, referer, accept)
        if (direct.body != null) return direct.body
        if (!direct.refusedByEdge) return null

        Log.d(TAG, "edge refused ${originOf(url).removePrefix("https://")}${url.substringAfter(originOf(url), "")}, moving to the webview")
        return CnoWebView.fetch(url)
    }

    // tells whether the network lets plain clients onto a path, the anime
    // links only need the proxy when it does not
    suspend fun plainText(url: String, referer: String, accept: String = "application/json"): String? {
        return directOutcome(url, referer, accept).body
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
        val height: Int,
        val url: String
    )

    // one retry: the edge has been seen answering a perfectly valid request
    // with a truncated playlist once before serving the real one
    suspend fun masterPlaylist(path: String): MasterResult? {
        repeat(2) {
            val body = text("$betaUrl$path", "$betaUrl/", accept = "*/*")
            if (body != null && body.startsWith("#EXTM3U")) {
                return parseMaster(body)
            }
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
        var pendingHeight: Int? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                val resolution = Regex("RESOLUTION=(\\d+)x(\\d+)").find(trimmed)
                pendingHeight = resolution?.groupValues?.get(2)?.toIntOrNull()
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val height = pendingHeight
                if (height != null && height > 0) {
                    val absolute = if (trimmed.startsWith("http")) {
                        trimmed
                    } else {
                        "$betaUrl$trimmed"
                    }
                    variants.add(Variant(height, absolute))
                }
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

    // headers for anything the player itself has to pull off the guarded
    // hosts, the playlists and subtitle files are fetched by exoplayer so
    // they carry the clearance and the user agent it belongs to
    fun streamHeaders(url: String, referer: String): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to (CnoWebView.userAgent() ?: USER_AGENT),
            "Referer" to referer,
            "Accept" to "*/*"
        )
        CnoWebView.cookies(url)?.let { headers["Cookie"] = it }
        return headers
    }

    suspend fun partnerSearch(query: String): List<PartnerTitle> {
        val body = text("${ani()}/api/partner/v1/titles?q=${encode(query)}&limit=50&adult=0", "${ani()}/")
            ?: return emptyList()
        return try {
            parseJson<PartnerTitlesResponse>(body).data
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun partnerTop(range: String, limit: Int): List<PartnerTitle> {
        val body = text("${ani()}/api/partner/v1/top?range=$range&limit=$limit&adult=0", "${ani()}/")
            ?: return emptyList()
        return try {
            parseJson<PartnerTitlesResponse>(body).data
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun partnerRecent(page: Int): List<PartnerRecentItem> {
        val body = text("${ani()}/api/partner/v1/recent?page=$page&limit=40&adult=0", "${ani()}/")
            ?: return emptyList()
        return try {
            parseJson<PartnerRecentResponse>(body).data
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun partnerSeries(anilistId: Int): PartnerSeries? {
        val body = text("${ani()}/api/partner/v1/series/$anilistId", "${ani()}/") ?: return null
        return try {
            parseJson<PartnerSeriesResponse>(body).data
        } catch (_: Exception) {
            null
        }
    }

    // the beta edge keys its hls folders by anilist id plus the slug the site
    // itself uses, and the slug is not guessable, so it is read from here
    suspend fun betaServer(anilistId: Int): BetaServerResponse? {
        val body = text("${ani()}/api/anime/beta-server/$anilistId", "${ani()}/") ?: return null
        return try {
            parseJson<BetaServerResponse>(body)
        } catch (_: Exception) {
            null
        }
    }

    fun animeMasterUrl(origin: String, anilistId: Int, slug: String, episode: Int): String {
        return "$origin/hls/$anilistId-$slug/E${"%04d".format(episode)}/master.m3u8"
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
