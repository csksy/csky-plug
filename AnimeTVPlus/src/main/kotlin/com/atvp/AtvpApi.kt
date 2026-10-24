package com.atvp

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject

/**
 * Catalog + player API client for animetvplus.xyz.
 *
 * Every endpoint, header and response shape here was captured from the site's own
 * traffic (HAR, 2026-10-24) and re-verified live, this time with the exact
 * okhttp stack the app itself uses:
 *  - catalog.animetvplus.site  : search / rows / by-id / episodes / cr-card / fanart
 *  - search.animetvplus.site   : the search mirror the site's client falls back to
 *  - embed.animetvplus.site    : hls3/uq episode resolver (the "edge" / Multi Audio server)
 *  - edge1-frankfut.animetvplus.site : idx/latest (new uploads feed)
 *
 * Calls are retried and the real failure reason is kept instead of being
 * swallowed into a null, so a broken request reports "HTTP 403 from
 * catalog.animetvplus.site" instead of a misleading "Title not found".
 */
object AtvpApi {

    const val SITE = "https://animetvplus.xyz"
    const val CATALOG = "https://catalog.animetvplus.site"
    const val SEARCH_MIRROR = "https://search.animetvplus.site"
    const val EMBED = "https://embed.animetvplus.site"
    const val IDX = "https://edge1-frankfut.animetvplus.site"

    const val EPISODES_VERSION = "v2-cache-guard"
    const val CARD_VERSION = "canonical-v19-absolute-parts"

    val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

    /** Headers the site's own pages send to the catalog API. */
    val catalogHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json",
        "Origin" to SITE,
        "Referer" to "$SITE/"
    )

    /** Headers the embed player sends when fetching playlists / subtitles off the edge CDN. */
    val edgeMediaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "*/*",
        "Origin" to EMBED,
        "Referer" to "$EMBED/"
    )

    /** Headers the megaplay iframe sends to its own API and CDN. */
    val megaplayHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "*/*",
        "Origin" to "https://megaplay.buzz",
        "Referer" to "https://megaplay.buzz/"
    )

    /** The last transport failure a catalog call hit, for real error messages. */
    @Volatile
    var lastCatalogError: String? = null
        private set

    /**
     * One GET parsed as JSON. Two attempts per call because a single dropped
     * request should not blank a whole page, and the reason for a hard failure
     * is kept for the caller to report.
     */
    private suspend fun getJson(
        url: String,
        headers: Map<String, String> = catalogHeaders,
        attempts: Int = 2
    ): JSONObject? {
        var lastReason = "no attempt"
        repeat(attempts) {
            try {
                val res = app.get(url, headers = headers, timeout = 15L)
                val text = res.text
                if (text.isBlank()) {
                    lastReason = "empty body (HTTP ${res.code})"
                } else {
                    try {
                        return JSONObject(text)
                    } catch (e: Exception) {
                        lastReason = "HTTP ${res.code}, body is not JSON: ${text.take(60)}"
                    }
                }
            } catch (e: Exception) {
                lastReason = "${e.javaClass.simpleName}: ${e.message?.take(80) ?: "no message"}"
            }
        }
        lastCatalogError = "$lastReason <- $url"
        return null
    }

    /** /api/search?q=... - returns the site's canonical entries. */
    suspend fun search(query: String, page: Int, limit: Int = 40): List<JSONObject> {
        val path = "/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit&page=$page"
        val items = getJson("$CATALOG$path")?.optJSONArray("items")
            // the search mirror answers the same shape; the site's own client
            // keeps a fallback origin list for exactly this reason
            ?: getJson("$SEARCH_MIRROR$path")?.optJSONArray("items")
            ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
    }

    /** Catalog rows the home page mirrors. */
    suspend fun row(path: String, page: Int, limit: Int = 40): List<JSONObject> {
        val url = "$CATALOG/api/anime/$path?limit=$limit&page=$page"
        val items = getJson(url)?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
    }

    /**
     * New-uploads feed from the edge index (today / yesterday / earlier buckets),
     * item shape differs from the catalog rows so callers map it separately.
     */
    suspend fun latestUploads(limit: Int = 24): List<JSONObject> {
        val root = getJson("$IDX/idx/latest?limit=$limit&kind=releases", attempts = 1) ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        for (bucket in listOf("today", "yesterday", "earlier")) {
            val arr = root.optJSONArray(bucket) ?: continue
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(it) }
            }
        }
        return out
    }

    /** /api/anime/by-id/{mal} - canonical metadata for one title. */
    suspend fun byId(mal: String): JSONObject? =
        getJson("$CATALOG/api/anime/by-id/$mal")?.optJSONObject("item")

    /**
     * /api/cr/card/{mal} - season map + synopsis. Seasons carry owner_mal +
     * cr_season_id; every episode is addressed by (owner_mal, absolute stream_ep).
     */
    suspend fun crCard(mal: String): JSONObject? =
        getJson("$CATALOG/api/cr/card/$mal?v=$CARD_VERSION&full=1")

    /**
     * /api/episodes/{mal} - episode list with real titles.
     *  - plain mode (v2-cache-guard): one flat list, ep 0 specials included
     *  - season mode: cr_season_id -> that season's episodes, numbered absolutely
     */
    suspend fun episodes(mal: String, crSeasonId: String? = null, page: Int = 1): JSONObject {
        val url = buildString {
            append("$CATALOG/api/episodes/$mal?v=")
            append(if (crSeasonId == null) EPISODES_VERSION else CARD_VERSION)
            if (crSeasonId != null) append("&cr_season_id=").append(crSeasonId)
            append("&page=$page&limit=100")
        }
        return getJson(url, attempts = 1) ?: JSONObject().put("episodes", JSONArray())
    }

    /** Walks every page of an episode listing (the API caps at 100 per page). */
    suspend fun allEpisodes(mal: String, crSeasonId: String? = null): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var page = 1
        var seen = 0
        while (page < 40) {
            val root = episodes(mal, crSeasonId, page)
            val arr = root.optJSONArray("episodes") ?: break
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
            seen += arr.length()
            val total = root.optInt("num_episodes", 0)
            val hasMore = root.optBoolean("has_more", false)
            if (!hasMore && (total == 0 || seen >= total)) break
            if (arr.length() == 0) break
            page++
        }
        // de-duplicate by episode number (the site's client does the same)
        val seenEps = HashSet<Int>()
        return out.filter { e ->
            val n = e.optInt("ep", e.optInt("episode_number", -1))
            if (seenEps.contains(n)) false else seenEps.add(n)
        }
    }

    /**
     * embed.animetvplus.site/hls3/uq/{mal}?episode={n} - the site's own storage.
     * Returns {m3u8, meta, storyboard, subtitles[]} or an error body when absent.
     */
    suspend fun hls3uq(mal: String, episode: Int): JSONObject? {
        return try {
            val text = app.get(
                "$EMBED/hls3/uq/$mal?episode=$episode",
                headers = mapOf("User-Agent" to UA, "Accept" to "*/*", "Referer" to "$EMBED/"),
                timeout = 15L
            ).text
            if (text.isBlank()) null else JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }
}
