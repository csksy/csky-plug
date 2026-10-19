package com.laddu100

import android.util.Log
import android.webkit.CookieManager
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisodeCounts(
    val raw: Int? = null,
    val sub: Int? = null,
    val dub: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroExternalIds(
    val anilist: List<String>? = null,
    val mal: List<String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroAnime(
    val id: String = "",
    val title: MiruroTitle? = null,
    val format: String? = null,
    val status: String? = null,
    val season_year: Int? = null,
    val average_score: Int? = null,
    val episode_counts: MiruroEpisodeCounts? = null,
    val episode_duration_minutes: Int? = null,
    val description: String? = null,
    val cover_url: String? = null,
    val banner_url: String? = null,
    val genres: List<String>? = null,
    val external_ids: MiruroExternalIds? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroListPage(
    val data: List<MiruroAnime> = emptyList(),
    val next_cursor: String? = null,
    val has_more: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisode(
    val episode_number: Int? = null,
    val title: String? = null,
    val synopsis: String? = null,
    val thumbnail_url: String? = null,
    val aired_season_number: Int? = null,
    val kind: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisodesPage(
    val data: List<MiruroEpisode> = emptyList(),
    val has_more: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroResolution(val width: Int? = null, val height: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlayStream(
    val url: String? = null,
    val format: String? = null,
    val quality: String? = null,
    val resolution: MiruroResolution? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEmbed(val url: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlayServer(
    val server: String = "",
    val headers: Map<String, String>? = null,
    val streams: List<MiruroPlayStream> = emptyList(),
    val embed: MiruroEmbed? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlaySubtitle(
    val file: String? = null,
    val language: String? = null,
    val label: String? = null,
    val format: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlayProvider(
    val provider: String = "",
    val subtitles: List<MiruroPlaySubtitle> = emptyList(),
    val servers: List<MiruroPlayServer> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlayTrack(
    val track: String = "",
    val providers: List<MiruroPlayProvider> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlay(
    val episode_number: Int? = null,
    val tracks: List<MiruroPlayTrack> = emptyList()
)

object MiruroApi {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"

    // the backend only accepts the page sizes its own web client sends,
    // anything else is answered with a plain 400
    private const val RAIL_LIMIT = 12
    private const val SEARCH_LIMIT = 15
    private const val EPISODES_LIMIT = 10000

    private val mapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // catalog responses arrive as binary octet-stream: xor with this key
    // then gunzip, the web client decodes the exact same way
    private val catalogKey = "miruro/catalog".toByteArray(Charsets.US_ASCII)

    private val trendingPath = "/api/v1/anime/trending?limit=$RAIL_LIMIT&status=RELEASING"
    private val popularPath = "/api/v1/anime?sort=-popularity&limit=$RAIL_LIMIT"
    private val recentPath = "/api/v1/anime?sort=-updated_at&limit=$RAIL_LIMIT"
    private val moviesPath = "/api/v1/anime?format=MOVIE&sort=-score&limit=$RAIL_LIMIT"
    private val topPath = "/api/v1/anime?sort=-score&limit=$RAIL_LIMIT"
    private val homeRailPaths =
        listOf(trendingPath, popularPath, recentPath, moviesPath, topPath)

    private val detailRegex = Regex("""^/api/v1/anime/([A-Za-z0-9_-]{32})$""")
    private val episodesRegex = Regex("""^/api/v1/anime/([A-Za-z0-9_-]{32})/episodes""")

    // the site publishes more mirrors in its own page head, keep every
    // known host around because any single one can be dns broken per isp
    private val fallbackDomains = listOf(
        "https://www.miruro.bz",
        "https://www.miruro.to",
        "https://www.miruro.tv",
        "https://www.miruro.ru",
        "https://www.miruro.cx"
    )

    // stream urls inside a play payload are short lived tokens so that body
    // is held for less time than the catalog lists
    private const val LIST_TTL_MS = 5 * 60 * 1000L
    private const val PLAY_TTL_MS = 2 * 60 * 1000L
    private const val MAX_CACHE_BYTES = 6L * 1024 * 1024

    // the zone challenges every client that is not a real browser, a failed
    // attempt must not be repeated on each call or the ui hangs for minutes
    private const val DIRECT_FAIL_TTL_MS = 10 * 60 * 1000L
    private const val WEB_FAIL_TTL_MS = 3 * 60 * 1000L
    private const val DIRECT_TIMEOUT_MS = 6_000L
    private const val WEBVIEW_TIMEOUT_MS = 25_000L
    private const val MINT_TIMEOUT_MS = 20_000L

    @Volatile
    private var domainCache: Pair<Long, List<String>>? = null

    @Volatile
    private var lastGoodDomain: String? = null

    private val directFailedAt = ConcurrentHashMap<String, Long>()
    private val webFailedAt = ConcurrentHashMap<String, Long>()
    private val fetchLock = Mutex()

    private class CachedBody(val text: String, val at: Long, val ttl: Long)

    private val cacheLock = Any()
    private val cacheMap = HashMap<String, CachedBody>()
    private val cacheOrder = ArrayDeque<String>()
    private var cacheBytes = 0L

    private fun cachePut(path: String, text: String) {
        val ttl = if ("/play" in path) PLAY_TTL_MS else LIST_TTL_MS
        synchronized(cacheLock) {
            cacheMap.remove(path)?.let { old ->
                cacheBytes -= old.text.length
                cacheOrder.remove(path)
            }
            cacheMap[path] = CachedBody(text, System.currentTimeMillis(), ttl)
            cacheOrder.addLast(path)
            cacheBytes += text.length
            while (cacheBytes > MAX_CACHE_BYTES && cacheOrder.size > 1) {
                val oldest = cacheOrder.removeFirst()
                cacheMap.remove(oldest)?.let { cacheBytes -= it.text.length }
            }
        }
    }

    private fun cacheGet(path: String): String? {
        synchronized(cacheLock) {
            val hit = cacheMap[path] ?: return null
            if (System.currentTimeMillis() - hit.at > hit.ttl) {
                cacheMap.remove(path)
                cacheOrder.remove(path)
                cacheBytes -= hit.text.length
                return null
            }
            cacheOrder.remove(path)
            cacheOrder.addLast(path)
            return hit.text
        }
    }

    private suspend fun domains(): List<String> {
        val now = System.currentTimeMillis()
        domainCache?.let { (at, list) ->
            if (now - at < 300_000L) return preferLastGood(list)
        }
        val override = try {
            FirebaseDomainHelper.getDomain("miruro")
        } catch (e: Exception) {
            null
        }
        val list = mutableListOf<String>()
        override?.removeSuffix("/")
            ?.takeIf { it.startsWith("http") }
            ?.let { list.add(it) }
        for (domain in fallbackDomains) {
            if (domain !in list) list.add(domain)
        }
        domainCache = now to list
        return preferLastGood(list)
    }

    private fun preferLastGood(list: List<String>): List<String> {
        val preferred = lastGoodDomain ?: return list
        return list.sortedByDescending { it == preferred }
    }

    // cookies solved inside the app webview are valid for okhttp too when the
    // user agent matches, so the fast path borrows them before giving up
    private fun cookieHeaderFor(domain: String): String? = try {
        CookieManager.getInstance().getCookie("$domain/")
    } catch (e: Exception) {
        null
    }

    // the api answers 403 with a challenge until the webview owns a
    // cf_clearance cookie, that cookie is the only thing that unlocks it
    private fun hasClearance(domain: String): Boolean = try {
        CookieManager.getInstance().getCookie("$domain/")?.contains("cf_clearance") == true
    } catch (e: Exception) {
        false
    }

    private fun directHeaders(domain: String): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to (WebViewResolver.webViewUserAgent ?: USER_AGENT),
            "Accept" to "*/*",
            "Referer" to "$domain/",
            "Accept-Language" to "en-US,en;q=0.9",
        )
        cookieHeaderFor(domain)?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        return headers
    }

    private fun decodeBytes(raw: ByteArray): String? {
        if (raw.isEmpty()) return null
        val head = raw[0]
        val text = if (head == '{'.code.toByte() || head == '['.code.toByte()) {
            String(raw, Charsets.UTF_8)
        } else {
            val plain = ByteArray(raw.size) {
                (raw[it].toInt() xor catalogKey[it % catalogKey.size].toInt()).toByte()
            }
            if (plain.size > 2 && plain[0] == 0x1f.toByte() && plain[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(plain)).use { bytes ->
                    bytes.readBytes().toString(Charsets.UTF_8)
                }
            } else {
                plain.toString(Charsets.UTF_8)
            }
        }
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) trimmed else null
    }

    // one webview session is expensive so it covers everything the caller and
    // its siblings will ask for: the whole first homepage at once, or the
    // detail payload together with its episode list
    private fun fetchTargets(path: String): List<String> {
        if (path in homeRailPaths) return homeRailPaths
        detailRegex.find(path)?.let {
            return listOf(path, "/api/v1/anime/${it.groupValues[1]}/episodes?limit=$EPISODES_LIMIT")
        }
        episodesRegex.find(path)?.let {
            return listOf("/api/v1/anime/${it.groupValues[1]}", path)
        }
        return listOf(path)
    }

    private fun fetchScript(origin: String, targets: List<String>): String {
        val targetsJson = mapper.writeValueAsString(targets)
        return """
(function(){
var w = window;
if (w.__mrItems && w.__mrItems.length) {
  var item = w.__mrItems.shift();
  w.__mrOut = (w.__mrOut || 0) + 1;
  var n = w.__mrSeq = (w.__mrSeq || 0) + 1;
  fetch('/__mr_next__?n=' + n).catch(function(){});
  return item;
}
if (w.__mrOut) {
  w.__mrOut = 0;
  var d = w.__mrSeq = (w.__mrSeq || 0) + 1;
  fetch('/__mr_done__?n=' + d).catch(function(){});
  return '';
}
if (w.__mrBusy) return '';
if (location.origin !== '$origin') return '';
w.__mrBusy = 1;
var T = $targetsJson;
var K = new TextEncoder().encode('miruro/catalog');
function d(b){
  var u = new Uint8Array(b);
  if (u[0] === 0x7b || u[0] === 0x5b) return Promise.resolve(new TextDecoder().decode(u));
  var p = new Uint8Array(u.length);
  for (var i = 0; i < u.length; i++) p[i] = u[i] ^ K[i % K.length];
  if (p[0] === 0x1f && p[1] === 0x8b) {
    return new Response(new Blob([p]).stream().pipeThrough(new DecompressionStream('gzip'))).text();
  }
  return Promise.resolve(new TextDecoder().decode(p));
}
function attempt(t, k){
  return fetch(t, {credentials: 'same-origin', cache: 'no-store'}).then(function(r){
    if ((r.status === 403 || r.status === 503) && k > 0) {
      return new Promise(function(res){
        setTimeout(function(){ res(attempt(t, k - 1)); }, 900);
      });
    }
    return r.arrayBuffer().then(function(b){
      return d(b).then(function(x){ return [t, r.status, x]; }, function(){ return [t, r.status, '']; });
    });
  }).catch(function(){ return [t, 0, '']; });
}
Promise.all(T.map(function(t){ return attempt(t, 4); })).then(function(items){
  w.__mrItems = items;
  var n = w.__mrSeq = (w.__mrSeq || 0) + 1;
  fetch('/__mr_next__?n=' + n).catch(function(){});
}, function(){
  w.__mrItems = [];
  w.__mrBusy = 0;
  var n = w.__mrSeq = (w.__mrSeq || 0) + 1;
  fetch('/__mr_next__?n=' + n).catch(function(){});
});
return '';
})()
""".trim()
    }

    private class FetchedItem(val path: String, val code: Int, val body: String)

    // the site passes real browsers without a challenge while okhttp only ever
    // sees one, so the catalog is fetched from inside the page itself: the
    // script runs in the site origin, decodes the payloads and hands them back
    // one per request round, a final sentinel url ends the session
    private suspend fun webViewFetch(domain: String, targets: List<String>): List<FetchedItem> {
        val origin = domain.removeSuffix("/")
        // an edge cached homepage skips the cloudflare javascript checks that
        // hand out the clearance cookie, a fresh url forces the origin page
        val home = if (hasClearance(domain)) "$origin/" else "$origin/?_mr=" + System.currentTimeMillis()
        val captured = ConcurrentLinkedQueue<String>()
        val resolver = WebViewResolver(
            interceptUrl = Regex("__mr_done__"),
            additionalUrls = emptyList(),
            userAgent = null,
            useOkhttp = false,
            script = fetchScript(origin, targets),
            scriptCallback = { value ->
                if (value.length > 4) captured.add(value)
            },
            timeout = WEBVIEW_TIMEOUT_MS,
        )
        resolver.resolveUsingWebView(home)
        val out = mutableListOf<FetchedItem>()
        for (raw in captured) {
            try {
                val arr = mapper.readValue(raw, Array<Any>::class.java)
                if (arr.size == 3) {
                    val p = arr[0] as? String ?: continue
                    val c = (arr[1] as? Number)?.toInt() ?: continue
                    val b = arr[2] as? String ?: ""
                    out.add(FetchedItem(p, c, b))
                }
            } catch (e: Exception) {
                Log.d("Miruro", "webview payload rejected: ${e.message}")
            }
        }
        return out
    }

    private fun adopt(items: List<FetchedItem>, path: String): Boolean {
        var useful = false
        for (item in items) {
            if (item.code == 200 && item.body.startsWith("{")) {
                cachePut(item.path, item.body)
                if (item.path == path) useful = true
            }
        }
        return useful
    }

    private fun challenged(items: List<FetchedItem>): Boolean =
        items.isNotEmpty() &&
            (items.any { it.code == 403 || it.code == 503 } || items.all { it.code == 0 })

    // loading a protected api url as a document makes the zone run its
    // managed challenge inside the webview, a real chromium solves it on its
    // own and the cf_clearance cookie lands in the shared cookie jar
    private suspend fun mintClearance(domain: String): Boolean {
        val origin = domain.removeSuffix("/")
        if (hasClearance(domain)) return true
        val resolver = WebViewResolver(
            interceptUrl = Regex(".^"),
            additionalUrls = listOf(Regex(".")),
            userAgent = null,
            useOkhttp = false,
            script = null,
            scriptCallback = null,
            timeout = MINT_TIMEOUT_MS,
        )
        return try {
            resolver.resolveUsingWebView(origin + recentPath) { hasClearance(domain) }
            hasClearance(domain)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("Miruro", "clearance attempt failed for $domain: ${e.message}")
            false
        }
    }

    private suspend fun directFetch(domain: String, path: String): String? {
        val res = app.get(
            domain + path,
            headers = directHeaders(domain),
            timeout = DIRECT_TIMEOUT_MS
        )
        if (res.code == 200) {
            res.body.bytes().takeIf { it.isNotEmpty() }?.let { return decodeBytes(it) }
        }
        return null
    }

    suspend fun fetch(path: String): String {
        cacheGet(path)?.let { return it }
        val targets = fetchTargets(path)
        var lastError: Exception? = null
        for (domain in domains()) {
            val now = System.currentTimeMillis()
            val directBlocked = directFailedAt[domain]?.let { now - it < DIRECT_FAIL_TTL_MS } == true
            if (!directBlocked) {
                try {
                    val text = directFetch(domain, path)
                    if (!text.isNullOrBlank()) {
                        directFailedAt.remove(domain)
                        lastGoodDomain = domain
                        cachePut(path, text)
                        return text
                    }
                    directFailedAt[domain] = System.currentTimeMillis()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    directFailedAt[domain] = System.currentTimeMillis()
                }
            }
            cacheGet(path)?.let { return it }
            webPhase(domain, targets, path)
            cacheGet(path)?.let {
                lastGoodDomain = domain
                return it
            }
        }
        Log.d("Miruro", "catalog request failed for $path: ${lastError?.message}")
        throw lastError ?: Exception("miruro is unreachable")
    }

    private suspend fun webPhase(domain: String, targets: List<String>, path: String) {
        val now = System.currentTimeMillis()
        if (webFailedAt[domain]?.let { now - it < WEB_FAIL_TTL_MS } == true) return
        fetchLock.withLock {
            cacheGet(path)?.let { return }
            var items = try {
                webViewFetch(domain, targets)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (items != null) {
                Log.d("Miruro", "webview $domain codes: " + items.joinToString(",") { it.code.toString() })
                if (adopt(items, path)) {
                    webFailedAt.remove(domain)
                    return
                }
            }
            if ((items == null || challenged(items)) && !hasClearance(domain)) {
                if (mintClearance(domain)) {
                    directFailedAt.remove(domain)
                    webFailedAt.remove(domain)
                    try {
                        directFetch(domain, path)?.let {
                            cachePut(path, it)
                            return
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // the webview retry below is the reliable path
                    }
                    val retried = try {
                        webViewFetch(domain, targets)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (retried != null) {
                        Log.d("Miruro", "webview $domain retry codes: " + retried.joinToString(",") { it.code.toString() })
                        if (adopt(retried, path)) {
                            webFailedAt.remove(domain)
                            return
                        }
                    }
                }
            }
            webFailedAt[domain] = System.currentTimeMillis()
        }
    }

    suspend fun trending(): MiruroListPage =
        mapper.readValue(fetch(trendingPath), MiruroListPage::class.java)

    suspend fun popular(cursor: String?): MiruroListPage =
        list(popularPath, cursor)

    suspend fun recent(cursor: String?): MiruroListPage =
        list(recentPath, cursor)

    suspend fun movies(cursor: String?): MiruroListPage =
        list(moviesPath, cursor)

    suspend fun topRated(cursor: String?): MiruroListPage =
        list(topPath, cursor)

    suspend fun search(query: String): MiruroListPage =
        list("/api/v1/anime?q=$query&limit=$SEARCH_LIMIT", null)

    private suspend fun list(path: String, cursor: String?): MiruroListPage {
        val sep = if ("?" in path) "&" else "?"
        val full = if (cursor != null) "$path${sep}cursor=$cursor" else path
        return mapper.readValue(fetch(full), MiruroListPage::class.java)
    }

    suspend fun detail(id: String): MiruroAnime =
        mapper.readValue(fetch("/api/v1/anime/$id"), MiruroAnime::class.java)

    suspend fun episodes(id: String): List<MiruroEpisode> =
        mapper.readValue(
            fetch("/api/v1/anime/$id/episodes?limit=$EPISODES_LIMIT"),
            MiruroEpisodesPage::class.java
        ).data

    suspend fun play(id: String, episode: Int): MiruroPlay =
        mapper.readValue(
            fetch("/api/v1/anime/$id/episodes/$episode/play"),
            MiruroPlay::class.java
        )
}
