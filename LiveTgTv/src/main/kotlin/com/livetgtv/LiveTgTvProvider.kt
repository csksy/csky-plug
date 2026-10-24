package com.livetgtv

import android.util.Base64
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * TG TV (livetgtv.lovable.app) - the JioTV mirror the site runs, with every
 * channel it carries. Links are resolved exactly the way the site does:
 *
 *  1. the site's own server function hands out the stream config - urls with
 *     fresh CDN tokens, ClearKey DRM pairs and backup servers; tokenless
 *     urls are dead on the JioTV edge so the tokened variant of each stream
 *     is the one that gets emitted
 *  2. the channel list urls plus the rotating tokens from the embedded
 *     player's cookie endpoints cover the same CDNs when the server function
 *     is unreachable
 *
 * The CDN signs every request (manifest and segment alike). The manifest url
 * carries the token in its query and the same token rides as the __hdnea__
 * cookie so the player's segment requests pass the edge too.
 */
class LiveTgTvProvider : MainAPI() {

    override var mainUrl = TgTvApi.SITE
    override var name = "LiveTgTv"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = listOf(
        MainPageData("All Channels", "all"),
        MainPageData("Sports", "Sports"),
        MainPageData("Movies", "Movies"),
        MainPageData("Entertainment", "Entertainment"),
        MainPageData("News", "News"),
        MainPageData("Kids", "Kids"),
        MainPageData("Music", "Music"),
        MainPageData("Devotional", "Devotional"),
        MainPageData("Infotainment", "Infotainment"),
        MainPageData("Lifestyle", "Lifestyle"),
        MainPageData("Educational", "Educational"),
        MainPageData("Business News", "Business News"),
        MainPageData("English", "English"),
        MainPageData("Tamil", "Tamil"),
        MainPageData("Telugu", "Telugu"),
        MainPageData("Malayalam", "Malayalam"),
        MainPageData("Kannada", "Kannada"),
        MainPageData("Marathi", "Marathi"),
        MainPageData("Bengali", "Bengali"),
        MainPageData("Punjabi", "Punjabi"),
        MainPageData("Gujarati", "Gujarati"),
        MainPageData("Urdu", "Urdu"),
        MainPageData("Odia", "Odia"),
        MainPageData("Assamese", "Assamese"),
        MainPageData("Bhojpuri", "Bhojpuri")
    )

    companion object {
        private const val PAGE_SIZE = 60
    }

    // ------------------------------------------------------------------ listing

    private fun toSearch(c: JSONObject): SearchResponse? {
        val id = c.optString("id")
        val name = c.optString("name")
        if (id.isBlank() || name.isBlank()) return null
        return newLiveSearchResponse(name, id, TvType.Live) {
            posterUrl = c.optString("logo").takeIf { it.startsWith("http") }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = TgTvApi.channels()
        val filtered = if (request.data == "all") {
            channels
        } else {
            channels.filter { it.optString("category") == request.data }
        }
        val from = (page - 1) * PAGE_SIZE
        val slice = if (from >= filtered.size) {
            emptyList()
        } else {
            filtered.subList(from, minOf(from + PAGE_SIZE, filtered.size))
        }
        val items = slice.mapNotNull(::toSearch)
        return newHomePageResponse(request.name, items, hasNext = from + PAGE_SIZE < filtered.size)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        return TgTvApi.channels()
            .filter { it.optString("name").lowercase().contains(q) }
            .take(50)
            .mapNotNull(::toSearch)
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = TgTvApi.channelById(url)
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Channel not found")
        val name = channel.optString("name")
        val category = channel.optString("category").ifBlank { "Live TV" }
        return newLiveStreamLoadResponse(name, url, url) {
            posterUrl = channel.optString("logo").takeIf { it.startsWith("http") }
            plot = "$name - live $category channel from TG TV"
        }
    }

    // ------------------------------------------------------------------ links

    /** One playable stream: a final url plus the DRM key pairs that open it. */
    private data class Candidate(
        val path: String,
        val url: String,
        val keys: Map<String, String>,
        val headers: Map<String, String>
    )

    private fun keyPairsOf(vararg sources: Any?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun addPair(kid: Any?, key: Any?) {
            val k = kid?.toString()?.lowercase() ?: return
            val v = key?.toString()?.lowercase() ?: return
            if (k.length == 32 && v.length == 32 && k.matches(Regex("[0-9a-f]+")) && v.matches(Regex("[0-9a-f]+"))) {
                out[k] = v
            }
        }
        for (src in sources) {
            if (src is JSONObject) {
                val keys = src.optJSONObject("clearKeys")
                if (keys != null) {
                    for (k in keys.keys()) addPair(k, keys.opt(k))
                }
                addPair(src.optString("keyId"), src.optString("key"))
            }
        }
        return out
    }

    /** DRM pairs attached to the channel entry itself (single or list form). */
    private fun channelKeys(channel: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val multi = channel.optJSONArray("keys")
        if (multi != null) {
            for (i in 0 until multi.length()) {
                val e = multi.optJSONObject(i) ?: continue
                val kid = e.optString("keyId").lowercase()
                val key = e.optString("key").lowercase()
                if (kid.length == 32 && key.length == 32) out[kid] = key
            }
        }
        val kid = channel.optString("keyId").lowercase()
        val key = channel.optString("key").lowercase()
        if (kid.length == 32 && key.length == 32) out[kid] = key
        return out
    }

    private fun hexToBase64Url(hex: String): String? {
        val clean = hex.replace("-", "")
        if (clean.length % 2 != 0 || !clean.matches(Regex("^[0-9a-fA-F]+$"))) return null
        return try {
            val bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun tokenOf(url: String): String? =
        Regex("[?&]__hdnea__=([^&]+)").find(url)?.groupValues?.get(1)

    private fun isPlLive(url: String): Boolean = url.contains("//jiotvpllive.cdn.jio.com/")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channels = TgTvApi.channels()
        val channel = channels.firstOrNull { it.optString("id") == data } ?: return false
        val chanKeys = channelKeys(channel)

        // path -> best candidate; the tokened variant of a stream replaces a
        // tokenless one, mirroring how the site falls through its servers
        val byPath = LinkedHashMap<String, Candidate>()

        fun offer(url: String, cookie: String, extraKeys: Map<String, String>, headers: Map<String, String>) {
            var u = url.replace("//bpk-tv/", "/bpk-tv/").replace("bpk-tv//", "bpk-tv/")
            if (cookie.isNotBlank()) u = u.substringBefore('?') + "?" + cookie
            if (!u.startsWith("http")) return
            val path = u.substringBefore('?')
            val keys = if (extraKeys.isEmpty()) chanKeys else chanKeys + extraKeys
            val token = tokenOf(u)
            val withCookie = if (token != null) {
                headers + mapOf("Cookie" to "__hdnea__=$token")
            } else headers
            val candidate = Candidate(path, u, keys, withCookie)
            val existing = byPath[path]
            val existingTokened = existing != null && tokenOf(existing.url) != null
            if (existing == null) {
                byPath[path] = candidate
            } else if (!existingTokened && token != null) {
                byPath[path] = candidate
            }
        }

        // 1. the site's own stream config
        val config = TgTvApi.serverFn(data)
        if (config != null) {
            val mainKeys = keyPairsOf(config)
            val mainCookie = config.optString("cookie")
            val urls = config.optJSONArray("urls")
            if (urls != null) {
                for (i in 0 until urls.length()) {
                    offer(urls.optString(i), mainCookie, mainKeys, TgTvApi.siteHeaders)
                }
            }
            val backups = config.optJSONArray("backups")
            if (backups != null) {
                for (i in 0 until backups.length()) {
                    val b = backups.optJSONObject(i) ?: continue
                    val bKeys = keyPairsOf(b)
                    val bCookie = b.optString("cookie")
                    val bUrls = b.optJSONArray("urls") ?: continue
                    for (j in 0 until bUrls.length()) {
                        offer(bUrls.optString(j), bCookie, bKeys, TgTvApi.siteHeaders)
                    }
                }
            }
        }

        // 2. the channel list urls with the embedded player's tokens
        val plLiveUrls = listOf("url", "url1", "url2", "url3")
            .mapNotNull { channel.optString(it).takeIf { u -> u.isNotBlank() } }
            .filter { isPlLive(it) }
        for (u in plLiveUrls) {
            offer(u, TgTvApi.starCookie(data), emptyMap(), TgTvApi.playerHeaders)
        }
        for (key in listOf("url", "url1", "url2", "url3")) {
            val u = channel.optString(key).takeIf { it.isNotBlank() } ?: continue
            if (isPlLive(u)) continue
            offer(u, TgTvApi.primaryCookie(), emptyMap(), TgTvApi.playerHeaders)
        }

        if (byPath.isEmpty()) return false

        var emitted = false
        for ((index, cand) in byPath.values.withIndex()) {
            val label = "Server ${index + 1}"
            val encoded = cand.keys.entries
                .mapNotNull { (kid, key) ->
                    val k = hexToBase64Url(kid)
                    val v = hexToBase64Url(key)
                    if (k != null && v != null) k to v else null
                }
            if (encoded.isEmpty()) {
                callback(
                    newExtractorLink(name, label, cand.url, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                        this.headers = cand.headers
                    }
                )
            } else {
                // the player drops kid and key into a single entry clearkey
                // license; the remaining pairs ride inside the kid string so
                // the finished license holds every pair
                val kidField = StringBuilder(encoded.first().first)
                for (i in 1 until encoded.size) {
                    kidField.append("\"},{\"kty\":\"oct\",\"k\":\"").append(encoded[i].second)
                    kidField.append("\",\"kid\":\"").append(encoded[i].first)
                }
                callback(
                    newDrmExtractorLink(
                        name,
                        label,
                        cand.url,
                        INFER_TYPE,
                        CLEARKEY_UUID
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.kty = "oct"
                        this.key = encoded.first().second
                        this.kid = kidField.toString()
                        this.headers = cand.headers
                    }
                )
            }
            emitted = true
        }
        return emitted
    }
}
