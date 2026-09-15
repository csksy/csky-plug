package com.csksy.anisnatch

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

internal object AniSnatchApi {

    private const val HOME = "https://anisnatch.to/home"
    private const val ORIGIN = "https://anisnatch.to"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val JSON_TYPE = "application/json".toMediaType()

    private val client: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // link validation needs to fail fast so dead hosts don't stall the source list
    private val probeClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // quick fetch for the playback validator
    fun fetchText(url: String, headers: Map<String, String>): String? {
        return try {
            val req = Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (resp.code !in 200..399) null else resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ranged 1KB GET, -1 when unreachable
    fun probeRange(url: String, headers: Map<String, String>): Int {
        return try {
            val h = LinkedHashMap(headers)
            h["Range"] = "bytes=0-1023"
            val req = Request.Builder().url(url)
                .apply { h.forEach { (k, v) -> header(k, v) } }
                .build()
            probeClient.newCall(req).execute().use { resp ->
                resp.code
            }
        } catch (e: Exception) {
            -1
        }
    }

    private var config: AniSnatchCrypto.SiteConfig? = null
    private var cipher: AniSnatchCrypto.ShiftCipher? = null
    private var configExpireAt: Long = 0

    private var snatchToken: String? = null
    private var proxyParam: String = "1~2~3~4~5"

    private val configMutex = Mutex()

    class AniSnatchException(message: String) : Exception(message)

    // headers for the encrypted XHR style POSTs
    private fun baseHeaders(referer: String? = null): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = USER_AGENT
        h["Accept"] = "*/*"
        h["Origin"] = ORIGIN
        if (referer != null) h["Referer"] = referer
        return h
    }

    /*
     * Headers for plain page loads. Browsers never send Origin on a top
     * level navigation and ask for html - anything else is a bot tell for
     * Cloudflare, which is how the direct client kept getting challenged.
     */
    private fun docHeaders(referer: String? = null): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = USER_AGENT
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        h["Accept-Language"] = "en-US,en;q=0.9"
        if (referer != null) h["Referer"] = referer
        return h
    }

    private fun getWithCookies(url: String, headers: Map<String, String>): String? {
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.body?.string()
        }
    }

    /*
     * Page fetch with the WebView Cloudflare fallback: the direct request
     * is tried first, and when it is challenged the clearance cookie is
     * solved once in a real WebView and the request retried with it.
     */
    private suspend fun getHtml(url: String, referer: String? = null): String {
        return AniSnatchWeb.fetchHtml(url, docHeaders(referer)) { u, h ->
            try {
                getWithCookies(u, h)
            } catch (e: Exception) {
                null
            }
        } ?: ""
    }

    private suspend fun ensureConfig(force: Boolean = false): AniSnatchCrypto.SiteConfig {
        configMutex.withLock {
            val current = config
            if (!force && current != null && System.currentTimeMillis() < configExpireAt) {
                return current
            }
            val html = getHtml(HOME)
            if (html.isEmpty()) {
                if (current != null) return current
                throw AniSnatchException("Could not reach anisnatch.to (network or Cloudflare). Retry in a moment.")
            }
            val parsed = AniSnatchCrypto.parseConfig(html)
                ?: throw AniSnatchException("anisnatch.to changed its page format, plugin needs an update.")
            config = parsed
            cipher = AniSnatchCrypto.ShiftCipher(parsed.alphabet)
            // the page token lives for an hour server side, refresh well before that
            configExpireAt = System.currentTimeMillis() + 40 * 60 * 1000
            return parsed
        }
    }

    private suspend fun api(endpoint: String, payload: JSONObject): JSONObject {
        var refreshed = false
        var rateLimitRetries = 0
        while (true) {
            val cfg = ensureConfig()
            val shifter = cipher ?: throw AniSnatchException("cipher unavailable")
            val built = AniSnatchCrypto.buildRequest(cfg, shifter, endpoint, payload, Random.Default)

            val req = Request.Builder().url(built.url)
                .apply {
                    AniSnatchWeb.clearanceHeaders(
                        built.url, baseHeaders("$ORIGIN/home")
                    ).forEach { (k, v) -> header(k, v) }
                }
                .post(built.body.toRequestBody(JSON_TYPE))
                .build()

            var rateLimited = false
            val bytes = try {
                withContext(Dispatchers.IO) {
                    client.newCall(req).execute().use { resp ->
                        if (resp.code == 429) {
                            rateLimited = true
                            null
                        } else {
                            resp.body?.bytes()
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }

            if (rateLimited) {
                if (++rateLimitRetries > 2) {
                    throw AniSnatchException("AniSnatch is rate limiting requests, wait a moment and retry.")
                }
                delay(2500)
                continue
            }

            if (bytes == null) {
                if (++rateLimitRetries > 2) {
                    throw AniSnatchException("AniSnatch request failed, check your connection and retry.")
                }
                delay(1500)
                continue
            }

            val decoded = AniSnatchCrypto.decodeResponse(bytes, cfg, built.authenticator)
            if (decoded != null) {
                val json = JSONObject(decoded)
                if (json.optBoolean("success", false) || json.has("data") ||
                    json.has("episodes") || json.has("server") || json.has("anime")
                ) {
                    return json
                }
            }

            // marker gone or error payload: the page config rotated, re-derive once
            if (!refreshed) {
                refreshed = true
                ensureConfig(force = true)
                continue
            }
            val json = decoded?.let { runCatching { JSONObject(it) }.getOrNull() }
            val msg = json?.optString("message").orEmpty()
            throw AniSnatchException(
                if (msg.isNotBlank()) "AniSnatch: $msg" else "AniSnatch request failed, retry."
            )
        }
    }

    private class InitData(val token: String, val proxies: String)

    private var initExpireAt: Long = 0

    private suspend fun ensureInit(): InitData {
        snatchToken?.let {
            if (System.currentTimeMillis() < initExpireAt) return InitData(it, proxyParam)
        }
        val res = api("api/init", JSONObject().put("token", JSONObject.NULL))
        val token = res.optString("token")
        if (token.isBlank()) throw AniSnatchException("AniSnatch did not issue a session token.")
        val proxies = res.optJSONObject("proxys")?.keys()?.asSequence()?.joinToString("~")
        if (!proxies.isNullOrBlank()) proxyParam = proxies
        snatchToken = token
        initExpireAt = System.currentTimeMillis() + 30 * 60 * 1000
        return InitData(token, proxyParam)
    }

    suspend fun search(keyword: String, page: Int): JSONArray? =
        api("api/search", JSONObject().put("keyword", keyword).put("page", page))
            .optJSONObject("data")?.optJSONArray("anime")

    suspend fun filter(query: String, page: Int): JSONArray? =
        api("api/filter", JSONObject().put("query", query).put("page", page))
            .optJSONObject("data")?.optJSONArray("anime")

    suspend fun homeTrending(): JSONArray? =
        api("api/home", JSONObject()).optJSONArray("trending")

    suspend fun anime(id: Int): JSONObject? =
        api("api/anime", JSONObject().put("id", id).put("user", JSONObject.NULL))
            .optJSONObject("data")?.optJSONObject("anime")

    class Episode(
        val number: Int,
        val title: String?,
        val image: String?,
        val airDate: String?,
        val filler: Boolean,
        val hasSub: Boolean,
        val hasDub: Boolean
    )

    // the site sends sub/dub as booleans today but older payloads used 0/1
    private fun truthy(obj: JSONObject, key: String): Boolean {
        return when (val v = obj.opt(key)) {
            is Boolean -> v
            is Int -> v != 0
            is String -> v == "1" || v.equals("true", true)
            else -> false
        }
    }

    suspend fun episodes(id: Int): List<Episode> {
        return try {
            episodesOnce(id)
        } catch (e: AniSnatchException) {
            // a stale session token is the usual culprit, re-init and retry once
            snatchToken = null
            episodesOnce(id)
        }
    }

    private suspend fun episodesOnce(id: Int): List<Episode> {
        val init = ensureInit()
        val arr = api(
            "api/loadEPs",
            JSONObject().put("id", id).put("token", init.token)
        ).optJSONArray("episodes") ?: return emptyList()

        val out = ArrayList<Episode>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val num = e.optInt("number", 0)
            if (num <= 0) continue
            out.add(
                Episode(
                    number = num,
                    title = e.optString("title").takeIf { it.isNotBlank() },
                    image = e.optString("image").takeIf { it.isNotBlank() },
                    airDate = e.optString("airDate").takeIf { it.isNotBlank() },
                    filler = truthy(e, "filler"),
                    hasSub = truthy(e, "sub"),
                    hasDub = truthy(e, "dub")
                )
            )
        }
        return out
    }

    class ServerEntry(
        val title: String,
        val server: String,
        val source: String,
        val notes: List<String>
    )

    suspend fun servers(id: Int, episode: Int): Map<String, List<ServerEntry>> {
        return try {
            serversOnce(id, episode)
        } catch (e: AniSnatchException) {
            snatchToken = null
            serversOnce(id, episode)
        }
    }

    private suspend fun serversOnce(id: Int, episode: Int): Map<String, List<ServerEntry>> {
        val init = ensureInit()
        val serverObj = api(
            "api/loadSVs",
            JSONObject().put("id", id).put("ep", episode).put("token", init.token)
        ).optJSONObject("server") ?: return emptyMap()

        val out = HashMap<String, List<ServerEntry>>()
        for (category in serverObj.keys()) {
            val arr = serverObj.optJSONArray(category) ?: continue
            val list = ArrayList<ServerEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val src = s.optString("source")
                if (src.isBlank()) continue
                val notesArr = s.optJSONArray("notes")
                val notes = ArrayList<String>(notesArr?.length() ?: 0)
                notesArr?.let {
                    for (j in 0 until it.length()) {
                        it.optJSONObject(j)?.optString("note")?.takeIf { n -> n.isNotBlank() }?.let { n ->
                            notes.add(n)
                        }
                    }
                }
                list.add(ServerEntry(s.optString("title"), s.optString("server"), src, notes))
            }
            out[category] = list
        }
        return out
    }

    class VideoSource(val url: String, val label: String?, val type: String)

    class VideoPage(
        val sources: List<VideoSource>,
        val subtitles: List<Pair<String, String>>,
        val dashRaw: String?
    )

    private class CachedPage(val page: VideoPage?, val at: Long)

    private val videoCache = ConcurrentHashMap<String, CachedPage>()

    suspend fun videoPage(source: String, lang: String = "en"): VideoPage? {
        val cached = videoCache[source]
        if (cached != null && System.currentTimeMillis() - cached.at < 10 * 60 * 1000) {
            return cached.page
        }

        val url = "https://anisnatch.to/video/$source-$proxyParam-$lang"
        val body = getHtml(url, "$ORIGIN/")
        val page = parseVideoPage(body)
        // failures stay uncached so a flaky server can recover on the next click
        if (page != null) {
            videoCache[source] = CachedPage(page, System.currentTimeMillis())
        }
        return page
    }

    private fun parseVideoPage(body: String): VideoPage? {
        if (!body.contains("const source")) return null
        val sources = ArrayList<VideoSource>()

        Regex("\\bsrc\\s*:\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).find(body)?.let { m ->
            val type = Regex("\"type\"\\s*:\\s*\"([^\"]*)\"").find(m.groupValues[1])?.groupValues?.get(1)
            val url = Regex("\"url\"\\s*:\\s*\"([^\"]*)\"").find(m.groupValues[1])?.groupValues?.get(1)
            if (!url.isNullOrBlank()) {
                sources.add(VideoSource(url, null, type ?: "hls"))
            }
        }

        Regex("\"url\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"label\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"type\"\\s*:\\s*\"([^\"]*)\"")
            .findAll(body).forEach { m ->
                if (m.groupValues[1].isNotBlank()) {
                    sources.add(VideoSource(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
                }
            }

        if (sources.isEmpty() && !body.contains("mpdUrl")) return null

        val subtitles = ArrayList<Pair<String, String>>()
        Regex("\"file\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"label\"\\s*:\\s*\"([^\"]*)\"")
            .findAll(body).forEach { m ->
                val file = m.groupValues[1]
                val label = m.groupValues[2]
                if (file.startsWith("http") && subtitles.none { it.second == file }) {
                    subtitles.add(label to file)
                }
            }

        val dashRaw = Regex("const rawUrls = (\\{.*?\\});", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1)

        return VideoPage(sources, subtitles, dashRaw)
    }
}
