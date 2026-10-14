package com.csksy.streamcorner

import android.content.Context
import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit

internal object CornerApi {

    private const val TAG = "StreamCorner"

    private val SITE_DOMAINS = listOf("https://streamcorner.st", "https://streamcorner.cloud")

    private val FALLBACK_HOSTS = listOf(
        "data.gigav.workers.dev", "data.yedmzoa.workers.dev", "data.ngagzipx.workers.dev",
        "data.miopks.workers.dev", "data.jccldjshj8sw.workers.dev", "data.l0o1afmju0.workers.dev",
        "data.nibflolsi9.workers.dev", "data.5j181.workers.dev", "data.rim1043.workers.dev",
        "data.kuig2.workers.dev", "data.senbon001.workers.dev", "data.senbon001-2.workers.dev",
        "data.senbon002.workers.dev", "data.senbon003.workers.dev", "data.kageyoshi001.workers.dev",
        "data.silentbyte125.workers.dev", "data.stealthwolf798-69b.workers.dev", "data.redjoy256.workers.dev",
        "data.anonfox144.workers.dev", "data.cripw4lk000.workers.dev", "data.phamviet444.workers.dev",
        "data.kanghaerin444.workers.dev", "data.minjikim444.workers.dev", "data.leehyein444.workers.dev",
        "data.daniellemarsh444.workers.dev"
    )

    private const val WORKER_CACHE_KEY = "sc_worker_source"
    private const val WORKER_URL_CACHE_KEY = "sc_worker_url"

    private var prefs: android.content.SharedPreferences? = null

    // mirrors the site's own 30 second request timeout
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .build()
    }

    private class SourceState(
        val hosts: List<String>,
        val workerUrl: String?,
        val keys: CornerCrypto.Keys
    ) {
        val createdAt = System.currentTimeMillis()
    }

    @Volatile
    private var source: SourceState? = null
    private val sourceMutex = Mutex()

    // provider lists change minute to minute, a short cache keeps the home
    // page and search from hammering the data hosts
    private data class ListCacheEntry(val json: Any?, val at: Long)

    private val listCache = ConcurrentHashMap<String, ListCacheEntry>()
    private val listMutexes = ConcurrentHashMap<String, Mutex>()
    private val listCacheMs = 2 * 60 * 1000L

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("streamcorner", Context.MODE_PRIVATE)
        }
    }

    private fun cachedWorkerSource(): String? = prefs?.getString(WORKER_CACHE_KEY, null)

    private fun saveWorkerSource(src: String, url: String?) {
        try {
            prefs?.edit()?.putString(WORKER_CACHE_KEY, src)?.putString(WORKER_URL_CACHE_KEY, url)?.apply()
        } catch (_: Exception) {
        }
    }

    private fun fetchText(url: String, referer: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetch failed for $url: ${e.message}")
            null
        }
    }

    private fun discoverLive(): Pair<String, List<String>>? {
        for (base in SITE_DOMAINS) {
            val html = try {
                val req = Request.Builder().url("$base/")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                }
            } catch (e: Exception) {
                Log.d(TAG, "homepage $base unreachable: ${e.message}")
                null
            } ?: continue
            val bundles = Regex("(?:src|href)=\"/assets/([A-Za-z0-9_-]+\\.js)\"").findAll(html)
                .map { it.groupValues[1] }.toList()
            if (bundles.isEmpty()) continue
            var workerUrl: String? = null
            val hosts = mutableListOf<String>()
            for (b in bundles) {
                val src = fetchText("$base/assets/$b", "$base/") ?: continue
                if (workerUrl == null) {
                    workerUrl = Regex("assets/(provider-data\\.worker-[A-Za-z0-9_-]+\\.js)")
                        .find(src)?.groupValues?.get(1)?.let { "$base/assets/$it" }
                }
                Regex("\"(data\\.[a-z0-9.-]+\\.workers\\.dev)\"").findAll(src)
                    .forEach { hosts.add(it.groupValues[1]) }
                if (workerUrl != null && hosts.isNotEmpty()) break
            }
            if (workerUrl != null) {
                return workerUrl to (if (hosts.isNotEmpty()) hosts.distinct() else FALLBACK_HOSTS)
            }
            // worker link missing from the bundles but hosts found: the worker
            // file name can still be probed from the homepage's asset folder
            if (hosts.isNotEmpty()) {
                val probe = Regex("provider-data\\.worker-[A-Za-z0-9_-]+\\.js").find(html)
                if (probe != null) {
                    return "$base/assets/${probe.value}" to hosts.distinct()
                }
            }
        }
        return null
    }

    private suspend fun loadSource(force: Boolean): SourceState {
        sourceMutex.withLock {
            val existing = source
            if (!force && existing != null &&
                System.currentTimeMillis() - existing.createdAt < 12 * 3600 * 1000L
            ) return existing

            // try the live site, fall back to the last worker source we saw
            val live = withContext(Dispatchers.IO) { discoverLive() }
            if (live != null) {
                val wsrc = withContext(Dispatchers.IO) { fetchText(live.first, "${SITE_DOMAINS[0]}/") }
                if (wsrc != null) {
                    try {
                        val keys = CornerCrypto.Keys(wsrc)
                        saveWorkerSource(wsrc, live.first)
                        val state = SourceState(live.second, live.first, keys)
                        source = state
                        return state
                    } catch (e: Exception) {
                        Log.d(TAG, "worker constants changed: ${e.message}")
                    }
                }
            }
            val cached = cachedWorkerSource()
            if (cached != null) {
                try {
                    val state = SourceState(
                        FALLBACK_HOSTS,
                        prefs?.getString(WORKER_URL_CACHE_KEY, null),
                        CornerCrypto.Keys(cached)
                    )
                    source = state
                    return state
                } catch (_: Exception) {
                }
            }
            throw IllegalStateException("streamcorner source unavailable")
        }
    }

    private fun postCorner(host: String, path: String, body: ByteArray): ByteArray {
        val req = Request.Builder()
            .url("https://$host$path")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/octet-stream")
            .header("Content-Type", "text/plain")
            .header("Origin", SITE_DOMAINS[0])
            .header("Referer", "${SITE_DOMAINS[0]}/")
            .post(body.toRequestBody("text/plain".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("data host returned ${resp.code}")
            return resp.body?.bytes() ?: throw IllegalStateException("empty data response")
        }
    }

    private fun shuffledHosts(hosts: List<String>): List<String> = hosts.shuffled().take(5)

    private suspend fun rawCall(path: String, provider: String, retryOnProtocolError: Boolean = true): Any? {
        val state = loadSource(false)
        var lastError: Exception? = null
        for (host in shuffledHosts(state.hosts)) {
            try {
                val body = state.keys.buildRequest(provider)
                val resp = withContext(Dispatchers.IO) { postCorner(host, path, body) }
                val json = state.keys.decryptResponse(resp, body)
                return parseJson(json)
            } catch (e: Exception) {
                lastError = e
                val protocol = e.message?.contains("mac") == true ||
                    e.message?.contains("format") == true ||
                    e.message?.contains("stale") == true
                if (protocol && retryOnProtocolError) {
                    // constants rotate with every deploy, refresh once
                    try {
                        val fresh = loadSource(true)
                        val body = fresh.keys.buildRequest(provider)
                        for (h2 in shuffledHosts(fresh.hosts)) {
                            try {
                                val resp = withContext(Dispatchers.IO) { postCorner(h2, path, body) }
                                return parseJson(fresh.keys.decryptResponse(resp, body))
                            } catch (e2: Exception) {
                                lastError = e2
                            }
                        }
                    } catch (e2: Exception) {
                        lastError = e2
                    }
                }
            }
        }
        throw lastError ?: IllegalStateException("all data hosts failed")
    }

    private fun parseJson(text: String): Any? = if (text.trimStart().startsWith("[")) {
        JSONArray(text)
    } else {
        JSONObject(text)
    }

    fun flatten(data: Any?): JSONArray {
        return when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("channels") ?: JSONArray().put(data)
            else -> JSONArray()
        }
    }

    suspend fun providerList(feedId: String, providerBody: String, id: String? = null): Any? {
        val cacheKey = "$feedId|$id"
        val cached = listCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.at < listCacheMs && id == null) {
            return cached.json
        }
        val mutex = listMutexes.computeIfAbsent(cacheKey) { Mutex() }
        return mutex.withLock {
            val again = listCache[cacheKey]
            if (again != null && System.currentTimeMillis() - again.at < listCacheMs && id == null) {
                return@withLock again.json
            }
            var path = "/corner?p=$feedId"
            if (!id.isNullOrEmpty()) path += "&id=${java.net.URLEncoder.encode(id, "UTF-8")}"
            val json = rawCall(path, providerBody)
            if (id == null) listCache[cacheKey] = ListCacheEntry(json, System.currentTimeMillis())
            json
        }
    }

    // branded feeds use fixed provider strings instead of the feed id
    suspend fun brandedList(feedId: String, provider: String, variant: Boolean): Any? {
        val body = when (provider) {
            "peacock" -> if (variant) "peacock_channels" else "peacock_schedule"
            "paramount" -> if (variant) "paramount_hero" else "paramount_schedule"
            else -> if (variant) "slingtv_channels" else "slingtv_sports"
        }
        return providerList(feedId, body)
    }

    suspend fun workerUrlForEmbeds(): String? = loadSource(false).workerUrl

    // stable embed poster helpers live on the data hosts too, but posters come
    // straight from the provider payloads, no extra call needed

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun userAgent(): String = USER_AGENT

    fun hexToBase64(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
