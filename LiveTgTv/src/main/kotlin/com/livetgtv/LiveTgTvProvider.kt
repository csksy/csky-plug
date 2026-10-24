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
 * channel it carries. Every link is built exactly the way the site builds
 * its own, verified request by request against the site's traffic:
 *
 *  the site's server picker (servers.js) offers one server per entry of the
 *  stream config - the config's own urls first ("Server 1"), then each
 *  backup as its own server ("Server 2", "Server 3", ...), and when they all
 *  fail the page swaps to the embedded player (jjtvxweb pind), which plays
 *  the channel list urls with the rotating cookie endpoints. This provider
 *  emits the same ladder, one link per distinct stream:
 *
 *  1. Server 1 - the config's urls
 *  2. Server 2.. - every backup's urls with the backup's own cookie and keys
 *  3. the embedded player's urls - the channel list with the live cookies
 *
 *  The token that signs a url is fixed by its host, the same rule the
 *  embedded player enforces (its own source): jiotvpllive only ever accepts
 *  the per-channel token from the star cookie endpoint and must never be
 *  given the global one (403), every other cdn runs on the global token.
 *  When the right class has no token the url keeps its own query - it may
 *  still carry a working one.
 *
 *  The DRM pairs ride in a single clearkey license: the player template puts
 *  the first pair in and the remaining pairs are appended inside the kid
 *  string, so the finished license holds every pair the mpd asks for by its
 *  cenc:default_KID list (verified against the live mpds - the pllive ones
 *  carry two distinct kids).
 *
 *  The manifest url carries its token in the query and the same token rides
 *  as the __hdnea__ cookie so the player's segment requests pass the edge.
 */
class LiveTgTvProvider : MainAPI() {

    override var mainUrl = TgTvApi.SITE
    override var name = "LiveTgTv"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    // the site's stream function fronts a live JioTV fetch and can take half a
    // minute before it answers, so the whole link search gets the same budget
    // the slower providers in this repo run with
    override val loadLinksTimeoutMs: Long? = 3 * 60_000L

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
        return newLiveSearchResponse(name, "${TgTvApi.SITE}/watch/$id", TvType.Live) {
            posterUrl = c.optString("logo").takeIf { it.startsWith("http") }
        }
    }

    /**
     * The channel id out of any url form that can reach load() or loadLinks():
     * the site's own watch page (/watch/146), the bare id form the response
     * builders used to prefix with mainUrl (/146, stored in existing watch
     * history) and the bare id itself.
     */
    private fun channelIdOf(input: String): String? {
        var s = input.trim()
        if (s.isBlank()) return null
        s = s.substringBefore('?').substringBefore('#')
        if (s.startsWith(TgTvApi.SITE)) s = s.removePrefix(TgTvApi.SITE)
        s = s.trim('/')
        if (s.startsWith("watch/")) s = s.removePrefix("watch/")
        if (s.contains('/')) s = s.substringAfterLast('/')
        return s.takeIf { it.isNotBlank() }
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
        val id = channelIdOf(url)
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Channel not found: $url")
        val channel = TgTvApi.channelById(id)
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Channel not found: $id")
        val name = channel.optString("name")
        val category = channel.optString("category").ifBlank { "Live TV" }
        return newLiveStreamLoadResponse(name, "${TgTvApi.SITE}/watch/$id", id) {
            posterUrl = channel.optString("logo").takeIf { it.startsWith("http") }
            plot = "$name - live $category channel from TG TV"
        }
    }

    // ------------------------------------------------------------------ links

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

    /** DRM pairs carried by a config or backup entry (dict or single pair). */
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
        // data is the bare channel id load() stored; older history entries can
        // carry the mainUrl-prefixed form so both are parsed here
        val id = channelIdOf(data) ?: return false
        val channel = TgTvApi.channels().firstOrNull { it.optString("id") == id } ?: return false
        val chanKeys = channelKeys(channel)

        // the two rotating token sources the site's players use; the embedded
        // player fetches both the same way from the phone, they answer json or
        // an interstitial from some networks which simply means no token
        val starTok = TgTvApi.starCookie(id)
        val primTok = TgTvApi.primaryCookie()
        val config = TgTvApi.serverFn(id)
        println("LiveTgTv[$id] ${channel.optString("name")}: star=${starTok.isNotBlank()} primary=${primTok.isNotBlank()} config=${config != null}")

        // final url -> the key pairs that open it; links are emitted in the
        // site's own order (config urls, backups, then the channel list) and
        // only distinct urls survive, so two servers that hand out the same
        // stream do not double up in the picker
        val byUrl = LinkedHashMap<String, Map<String, String>>()

        fun offer(rawUrl: String, cookie: String, extraKeys: Map<String, String>) {
            var u = rawUrl.trim()
            if (!u.startsWith("http")) return
            u = u.replace("//bpk-tv/", "/bpk-tv/").replace("bpk-tv//", "bpk-tv/")
            val path = u.substringBefore('?')
            // the token class is fixed by the host, the rule the embedded
            // player enforces in its own source: jiotvpllive takes the
            // per-channel star token and nothing else, every other cdn takes
            // the global one - the config's fresh mint first, the live
            // endpoint behind it
            val pllive = isPlLive(path)
            val token = when {
                pllive -> starTok
                cookie.isNotBlank() -> cookie
                else -> primTok
            }
            // no token of the right class: keep the url as it stands, its own
            // query may still carry a working one
            val finalUrl = if (token.isBlank()) u else "$path?$token"
            if (finalUrl !in byUrl) {
                byUrl[finalUrl] = if (extraKeys.isEmpty()) chanKeys else chanKeys + extraKeys
            }
        }

        // 1. the site's Server 1: the stream config's own urls
        if (config != null) {
            val cfgKeys = keyPairsOf(config)
            val cfgCookie = config.optString("cookie")
            val urls = config.optJSONArray("urls")
            if (urls != null) {
                for (i in 0 until urls.length()) {
                    offer(urls.optString(i), cfgCookie, cfgKeys)
                }
            }
            // 2. the site's Server 2..N: every backup is its own server, with
            // its own urls, its own cookie and its own keys
            val backups = config.optJSONArray("backups")
            if (backups != null) {
                for (i in 0 until backups.length()) {
                    val b = backups.optJSONObject(i) ?: continue
                    val bUrls = b.optJSONArray("urls") ?: continue
                    val bKeys = keyPairsOf(b)
                    val bCookie = b.optString("cookie")
                    for (j in 0 until bUrls.length()) {
                        offer(bUrls.optString(j), bCookie, bKeys)
                    }
                }
            }
        }

        // 3. the embedded player's ladder: the channel's own urls with the
        // live cookie endpoints - the site falls back to exactly this when
        // every config server fails
        for (k in listOf("url", "url1", "url2", "url3")) {
            val u = channel.optString(k).takeIf { it.isNotBlank() } ?: continue
            offer(u, "", emptyMap())
        }

        if (byUrl.isEmpty()) return false

        var emitted = false
        for ((index, entry) in byUrl.entries.withIndex()) {
            val url = entry.key
            val keys = entry.value
            val label = "Server ${index + 1}"
            val token = tokenOf(url)
            val headers = TgTvApi.playerHeaders + (
                if (token != null) mapOf("Cookie" to "__hdnea__=$token") else emptyMap()
                )
            val encoded = keys.entries
                .mapNotNull { (kid, key) ->
                    val k = hexToBase64Url(kid)
                    val v = hexToBase64Url(key)
                    if (k != null && v != null) k to v else null
                }
            if (encoded.isEmpty()) {
                callback(
                    newExtractorLink(name, label, url, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
            } else {
                // the player template puts the first pair into a single entry
                // clearkey license; the remaining pairs ride inside the kid
                // string so the finished license holds every pair
                val kidField = StringBuilder(encoded.first().first)
                for (i in 1 until encoded.size) {
                    kidField.append("\"},{\"kty\":\"oct\",\"k\":\"").append(encoded[i].second)
                    kidField.append("\",\"kid\":\"").append(encoded[i].first)
                }
                callback(
                    newDrmExtractorLink(
                        name,
                        label,
                        url,
                        INFER_TYPE,
                        CLEARKEY_UUID
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.kty = "oct"
                        this.key = encoded.first().second
                        this.kid = kidField.toString()
                        this.headers = headers
                    }
                )
            }
            println("LiveTgTv[$id] $label: ${url.substringBefore('?')} token=${token != null} keys=${encoded.size}")
            emitted = true
        }
        return emitted
    }
}
