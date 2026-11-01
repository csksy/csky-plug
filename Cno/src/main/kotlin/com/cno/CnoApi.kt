package com.cno

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.laddu100.FirebaseDomainHelper

object CnoApi {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

    private const val DEFAULT_BETA = "https://beta.cno.to"
    private const val DEFAULT_ANI = "https://ani.pm"

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

    private fun headers(referer: String = "$betaUrl/", accept: String = "application/json") = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to accept,
        "Referer" to referer
    )

    private suspend fun getJson(url: String, referer: String = "$betaUrl/"): String? {
        return try {
            val res = app.get(url, headers = headers(referer), timeout = 20_000L)
            if (res.code == 200) res.text else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun searchScreen(query: String, page: Int): ScreenSearchResponse? {
        val text = getJson(
            "$betaUrl/screen-api/search?q=${encode(query)}&type=all&page=$page"
        ) ?: return null
        return try {
            parseJson<ScreenSearchResponse>(text)
        } catch (_: Exception) {
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
        val text = getJson("$betaUrl/screen-api/site/dexter?v=8") ?: return shelfCache
        return try {
            val shelves = parseJson<ScreenSite>(text).shelves
            if (shelves.isNotEmpty()) {
                shelfCache = shelves
                shelfCacheAt = System.currentTimeMillis()
            }
            shelves
        } catch (_: Exception) {
            shelfCache
        }
    }

    suspend fun movieDetail(id: Int): ScreenDetail? {
        val text = getJson("$betaUrl/screen-api/movie/$id") ?: return null
        return try {
            parseJson<ScreenDetail>(text)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun tvDetail(id: Int): ScreenDetail? {
        val text = getJson("$betaUrl/screen-api/tv/$id") ?: return null
        return try {
            parseJson<ScreenDetail>(text)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun seasonEpisodes(id: Int, season: Int): SeasonResponse? {
        val text = getJson("$betaUrl/screen-api/tv/$id/season/$season") ?: return null
        return try {
            parseJson<SeasonResponse>(text)
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
            val text = fetchMaster("$betaUrl$path")
            if (text != null) {
                val parsed = parseMaster(text) ?: return null
                return parsed
            }
        }
        return null
    }

    private suspend fun fetchMaster(url: String): String? {
        return try {
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Referer" to "$betaUrl/"
                ),
                timeout = 20_000L
            )
            if (res.code == 200 && res.text.startsWith("#EXTM3U")) res.text else null
        } catch (_: Exception) {
            null
        }
    }

    // variants in these masters are relative /screen/pl/ paths, the player
    // would resolve them against whatever host it loaded the master from, so
    // they are made absolute here before being handed out as links
    fun parseMaster(text: String): MasterResult? {
        val hasAudio = text.contains("TYPE=AUDIO")
        val variants = mutableListOf<Variant>()
        val lines = text.lines()
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
        return MasterResult(text, hasAudio, variants)
    }

    suspend fun subsList(path: String): List<SubFile> {
        val text = getJson("$betaUrl/screen-api/subs/list/$path") ?: return emptyList()
        return try {
            parseJson<SubsList>(text).files
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun subTrackUrl(path: String, lang: String): String {
        return "$betaUrl/screen/subs/$path/$lang.vtt"
    }

    suspend fun partnerSearch(query: String): List<PartnerTitle> {
        val text = getJson(
            "${ani()}api/partner/v1/titles?q=${encode(query)}&limit=50&adult=0",
            referer = "${ani()}/"
        ) ?: return emptyList()
        return try {
            parseJson<PartnerTitlesResponse>(text).data
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun partnerTop(range: String, limit: Int): List<PartnerTitle> {
        val text = getJson(
            "${ani()}api/partner/v1/top?range=$range&limit=$limit&adult=0",
            referer = "${ani()}/"
        ) ?: return emptyList()
        return try {
            parseJson<PartnerTitlesResponse>(text).data
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun partnerRecent(page: Int): List<PartnerRecentItem> {
        val text = getJson(
            "${ani()}api/partner/v1/recent?page=$page&limit=40&adult=0",
            referer = "${ani()}/"
        ) ?: return emptyList()
        return try {
            parseJson<PartnerRecentResponse>(text).data
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun partnerSeries(anilistId: Int): PartnerSeries? {
        val text = getJson(
            "${ani()}api/partner/v1/series/$anilistId",
            referer = "${ani()}/"
        ) ?: return null
        return try {
            parseJson<PartnerSeriesResponse>(text).data
        } catch (_: Exception) {
            null
        }
    }

    // the beta edge keys its hls folders by anilist id plus the slug the site
    // itself uses, and the slug is not guessable, so it is read from here
    suspend fun betaServer(anilistId: Int): BetaServerResponse? {
        val text = getJson(
            "${ani()}api/anime/beta-server/$anilistId",
            referer = "${ani()}/"
        ) ?: return null
        return try {
            parseJson<BetaServerResponse>(text)
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
