package com.csksy.streamcorner

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object StreamCornerApi {
    private const val TAG = "StreamCorner"

    // the site keeps a list of mirrors, the cloud one answers whenever the
    // main domain is having a bad day
    private val DOMAINS = listOf("https://streamcorner.st", "https://streamcorner.cloud")

    // the schedule data lives behind a pool of cloudflare workers, the site
    // shuffles them and tries the first five so the load spreads, the plugin
    // does the same instead of hammering a single host
    private val DATA_HOSTS = listOf(
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

    // every feed the site splits its listings by, empty feeds just render no
    // row so whatever the site adds or drops needs no plugin change
    val FEEDS = linkedMapOf(
        "unl" to "UEFA Nations League",
        "alpha" to "Alpha",
        "beta" to "Beta",
        "001" to "Feed 001",
        "003" to "Feed 003",
        "extra001" to "Extra 001",
        "extra002" to "Extra 002",
        "extra003" to "Extra 003",
        "extra004" to "Extra 004",
        "channels" to "Channels",
        "slingtv_channels" to "SlingTV",
        "admin" to "24/7 Streams"
    )

    private const val LIST_TTL = 60_000L
    private const val READY_TIMEOUT = 45_000L
    private const val CALL_TIMEOUT = 90_000L

    private var appContext: Context? = null
    private var webView: WebView? = null

    @Volatile
    private var readySignal: CompletableDeferred<Unit>? = null

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicInteger(1)
    private val listCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val listInFlight = ConcurrentHashMap<String, CompletableDeferred<String>>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun context(): Context? = appContext

    private class Bridge {
        @JavascriptInterface
        fun onReady() {
            readySignal?.complete(Unit)
        }

        @JavascriptInterface
        fun onError(error: String) {
            Log.e(TAG, "data worker failed: $error")
            readySignal?.completeExceptionally(Exception(error))
        }

        @JavascriptInterface
        fun onReply(raw: String) {
            try {
                val msg = JSONObject(raw)
                val waiter = pending.remove(msg.optInt("requestId")) ?: return
                if (msg.has("error")) {
                    waiter.completeExceptionally(Exception(msg.optString("error")))
                } else {
                    waiter.complete(msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "reply parse failed: ${e.message}")
            }
        }
    }

    // the schedule api speaks an encrypted request/response protocol that is
    // implemented once, inside the site's own provider-data web worker. rather
    // than porting the cipher and chasing its rotations the plugin runs the
    // site's current worker file inside the site page itself: same origin, same
    // cors, and a worker update on their end is picked up on the next load
    private const val BOOTSTRAP = """
        (function() {
            if (window.__scBooting) return;
            window.__scBooting = true;
            var MIRROR = 'https://streamcorner.cloud';
            function fail(msg) { try { AndroidSc.onError(String(msg)); } catch (e) {} }
            function fetchText(u) { return fetch(u).then(function(r) { if (!r.ok) throw new Error(r.status); return r.text(); }); }
            function patch(src) {
                var raw = src.replace(
                    'function zt(n){return Array.isArray(n)?n:Array.isArray(n?.channels)?n.channels:[]}',
                    'function zt(n){return n}');
                return raw.indexOf('provider-list') >= 0 && raw.indexOf('Array.isArray(n?.channels)') < 0 ? raw : null;
            }
            function startWorker(src) {
                try {
                    var w = new Worker(URL.createObjectURL(new Blob([src], {type: 'application/javascript'})));
                    w.onmessage = function(e) { AndroidSc.onReply(JSON.stringify(e.data)); };
                    w.onerror = function(e) { fail(e.message || 'worker error'); };
                    window.__scCall = function(m) { w.postMessage(m); };
                    AndroidSc.onReady();
                } catch (e) { fail(e && e.message ? e.message : 'worker start failed'); }
            }
            function workerPathFromChunks(mainText) {
                var names = mainText.match(/"assets\/[A-Za-z0-9_.-]+\.js"/g) || [];
                var found = null;
                var jobs = names.map(function(n) {
                    return fetchText('/' + n.replace(/"/g, '')).then(function(t) {
                        var m = t.match(/[A-Za-z0-9_.-]+\.worker-[A-Za-z0-9_-]+\.js/);
                        if (m && !found) found = m[0];
                    }).catch(function() {});
                });
                return Promise.all(jobs).then(function() { return found; });
            }
            function boot() {
                var cached = null;
                try { cached = localStorage.getItem('__scWorker'); } catch (e) {}
                var known = cached ? fetchText('/assets/' + cached).then(function(t) {
                    var p = patch(t);
                    if (p) return p;
                    throw new Error('stale');
                }) : Promise.reject(new Error('no cache'));
                return known.catch(function() {
                    var mod = document.querySelector('script[type="module"][src^="/assets/"]');
                    if (!mod) throw new Error('no bundle on the page');
                    return fetchText(mod.getAttribute('src')).then(workerPathFromChunks).then(function(path) {
                        if (!path) throw new Error('worker file not found');
                        try { localStorage.setItem('__scWorker', path); } catch (e) {}
                        return fetchText('/assets/' + path).then(function(t) {
                            var p = patch(t);
                            if (!p) throw new Error('worker format changed');
                            return p;
                        });
                    });
                }).then(startWorker).catch(function(e) {
                    if (location.origin !== MIRROR) {
                        window.__scBooting = false;
                        location.replace(MIRROR + '/');
                        return;
                    }
                    fail(e && e.message ? e.message : 'boot failed');
                });
            }
            boot();
        })();
    """

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun awaitReady() {
        val ctx = appContext ?: throw IllegalStateException("plugin not initialised")
        // a completed signal is awaited again so a past failure rethrows
        readySignal?.takeIf { it.isCompleted }?.let {
            it.await()
            return
        }
        withContext(Dispatchers.Main) {
            if (readySignal?.isCompleted == true) return@withContext
            if (webView == null) {
                val signal = CompletableDeferred<Unit>()
                readySignal = signal
                webView = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.blockNetworkImage = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    addJavascriptInterface(Bridge(), "AndroidSc")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(BOOTSTRAP, null)
                        }
                    }
                    webChromeClient = WebChromeClient()
                    loadUrl(DOMAINS.first())
                }
            }
        }
        val signal = readySignal
            ?: throw IllegalStateException("the data worker could not start")
        withTimeoutOrNull(READY_TIMEOUT) { signal.await() }
            ?: throw IllegalStateException("streamcorner took too long to start")
    }

    private fun requestUrls(feed: String, id: String?): Pair<Int, JSONArray> {
        val path = buildString {
            append("/corner?p=")
            append(feed)
            if (!id.isNullOrBlank()) append("&id=").append(java.net.URLEncoder.encode(id, "UTF-8"))
        }
        val urls = JSONArray(DATA_HOSTS.shuffled().take(5).map { "https://$it$path" })
        return Pair(nextId.getAndIncrement(), urls)
    }

    private suspend fun callFeed(feed: String, id: String? = null): String {
        awaitReady()
        val (requestId, urls) = requestUrls(feed, id)
        val message = JSONObject()
            .put("requestId", requestId)
            .put("operation", "provider-list")
            .put("providerId", feed)
            .put("providerName", FEEDS[feed] ?: feed)
            .put("requestUrls", urls)
        val waiter = CompletableDeferred<JSONObject>()
        pending[requestId] = waiter
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.__scCall && window.__scCall($message)", null)
        }
        val reply = withTimeoutOrNull(CALL_TIMEOUT) { waiter.await() } ?: run {
            pending.remove(requestId)
            throw IllegalStateException("the $feed feed timed out")
        }
        val data = reply.opt("data") ?: throw IllegalStateException("the $feed feed returned no data")
        return data.toString()
    }

    private fun arrayFrom(raw: String): String {
        val trimmed = raw.trimStart()
        if (trimmed.startsWith("[")) return raw
        return try {
            JSONObject(raw).optJSONArray("channels")?.toString() ?: "[]"
        } catch (e: Exception) {
            "[]"
        }
    }

    suspend fun list(feed: String): List<ScEvent> {
        listCache[feed]?.takeIf { System.currentTimeMillis() - it.first < LIST_TTL }?.let {
            return parseJson(it.second)
        }
        // the home screen asks for the same feeds from several rows at once,
        // one flight per feed keeps the worker from answering the same thing
        // over and over on a cold load
        val inFlight = CompletableDeferred<String>()
        listInFlight.putIfAbsent(feed, inFlight)?.let { running ->
            return parseJson(running.await())
        }
        try {
            val raw = arrayFrom(callFeed(feed))
            listCache[feed] = Pair(System.currentTimeMillis(), raw)
            inFlight.complete(raw)
            return parseJson(raw)
        } catch (e: Exception) {
            inFlight.completeExceptionally(e)
            throw e
        } finally {
            listInFlight.remove(feed)
        }
    }

    suspend fun detail(feed: String, id: String): ScDetail? {
        val raw = callFeed(feed, id)
        if (raw.trim() == "[]") return null
        return try {
            parseJson<ScDetail>(raw)
        } catch (e: Exception) {
            Log.d(TAG, "detail parse failed: ${e.message}")
            null
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScEvent(
    @JsonProperty("stream_id") val id: String? = null,
    @JsonProperty("event_name") val name: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("category_logo") val categoryLogo: String? = null,
    @JsonProperty("league") val league: String? = null,
    @JsonProperty("home_team") val homeTeam: String? = null,
    @JsonProperty("home_team_logo") val homeTeamLogo: String? = null,
    @JsonProperty("away_team") val awayTeam: String? = null,
    @JsonProperty("away_team_logo") val awayTeamLogo: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("timestamp") val timestamp: Long? = null,
    @JsonProperty("start_time") val startTime: Long? = null,
    @JsonProperty("end_time") val endTime: Long? = null,
    @JsonProperty("time_et") val timeEt: String? = null,
    // the slingtv feed names its channels differently
    @JsonProperty("channel_id") val channelId: String? = null,
    @JsonProperty("channel_name") val channelName: String? = null,
    @JsonProperty("channel_logo") val channelLogo: String? = null
) {
    val eventId: String? get() = id ?: channelId
    val eventName: String? get() = name ?: channelName
    val posterUrl: String? get() = poster?.takeIf { it.isNotBlank() }
        ?: channelLogo?.takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScStream(
    @JsonProperty("source_name") val sourceName: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null,
    @JsonProperty("stream_keys") val streamKeys: String? = null,
    @JsonProperty("embed_url") val embedUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScDetail(
    @JsonProperty("stream_id") val id: String? = null,
    @JsonProperty("event_name") val name: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("streams") val streams: List<ScStream>? = null,
    // the slingtv channels carry one embed instead of a stream list
    @JsonProperty("embed_url") val embedUrl: String? = null
)
