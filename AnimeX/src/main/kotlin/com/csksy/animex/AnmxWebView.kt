package com.csksy.animex

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// the sites own server answers through the loader at cdn.vidcloud.se that
// exposes window.__oct, but inside a plain webview cloudflare challenges both
// the cross site script tag (opaque response blocking turns its html answer
// into a network error) and the handshake fetch, so the loader source is
// downloaded by the app, injected as text, and the loaders vidcloud fetches
// are rerouted through the app http stack via the js bridge
internal object AnmxWebView {

    private const val TAG = "AnimeX"

    private const val PAGE_LOAD_TIMEOUT = 15_000L
    private const val OPEN_TIMEOUT = 30_000L
    private const val OPEN_RETRIES = 2

    private const val PLAYER_ORIGIN = "https://plyr.animex.one"
    private const val VENDOR_URL = "https://cdn.vidcloud.se/vjs/vendor.js"
    private const val VENDOR_CACHE_MS = 30 * 60 * 1000L
    private const val BRIDGE_TIMEOUT = 20_000L

    // Cloudflare lets plain http clients through when the request carries the
    // marks of a browser script fetch, headless engines without them are
    // challenged instead
    private val VENDOR_HEADERS = mapOf(
        "User-Agent" to BRIDGE_UA,
        "Accept" to "*/*",
        "Referer" to "$PLAYER_ORIGIN/",
        "Sec-Fetch-Dest" to "script",
        "Sec-Fetch-Mode" to "no-cors",
        "Sec-Fetch-Site" to "cross-site"
    )

    const val BRIDGE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36"

    private var appContext: Context? = null

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var failedHard = false

    @Volatile
    private var vendorCache: String? = null

    @Volatile
    private var vendorCacheAt = 0L

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val tagCounter = AtomicLong(0)

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // one handshake at a time, the page gate is a single slot and two
    // overlapping episodes would complete each other's waits
    private val openMutex = Mutex()

    private class Bridge {
        @JavascriptInterface
        fun deliver(tag: String, body: String) {
            pending.remove(tag)?.complete(body)
        }

        @JavascriptInterface
        fun http(url: String, initJson: String): String = bridgeHttp(url, initJson)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView? {
        if (failedHard) return null
        webView?.let { return it }
        val ctx = appContext ?: return null
        val created = WebView(ctx)
        return try {
            created.settings.javaScriptEnabled = true
            created.settings.domStorageEnabled = true
            created.settings.mediaPlaybackRequiresUserGesture = false
            // the default webview UA carries the wv marker cloudflare scores
            // against, a plain browser UA keeps the native path alive
            created.settings.userAgentString = BRIDGE_UA
            try {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(created, true)
            } catch (_: Exception) {
            }
            created.addJavascriptInterface(Bridge(), "AnmxBridge")
            created.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val title = view?.title ?: ""
                    if (title.contains("just a moment", true) ||
                        title.contains("attention required", true)
                    ) return
                    url?.let { harvest ->
                        try {
                            CookieManager.getInstance().flush()
                        } catch (_: Exception) {
                        }
                        pageGate?.complete(harvest)
                    }
                }
            }
            webView = created
            created
        } catch (e: Exception) {
            Log.w(TAG, "anmx webview create failed: ${e.message}")
            failedHard = true
            try {
                created.destroy()
            } catch (_: Exception) {
            }
            null
        }
    }

    @Volatile
    private var pageGate: CompletableDeferred<String>? = null

    /**
     * Runs the site's own source loader for one episode, the answer is the
     * raw manifest json or null when the page or loader will not come up.
     */
    suspend fun openManifest(episodeId: Int, pageUrl: String): String? {
        if (failedHard) return null
        openMutex.withLock {
            val vendor = vendorSource() ?: return null
            return withContext(Dispatchers.Main) {
                val view = ensureWebView() ?: return@withContext null
                var lastReason = "no answer"
                for (attempt in 0 until OPEN_RETRIES) {
                    val loaded = loadFreshPage(view, pageUrl)
                    if (!loaded) {
                        lastReason = "player page did not come up"
                        continue
                    }
                    val opened = runOpen(view, episodeId, vendor)
                    if (opened != null) return@withContext opened
                    lastReason = "loader refused, reloading a fresh page"
                }
                Log.d(TAG, "anmx og unavailable: $lastReason")
                null
            }
        }
    }

    private suspend fun vendorSource(): String? {
        vendorCache?.let {
            if (System.currentTimeMillis() - vendorCacheAt < VENDOR_CACHE_MS) return it
        }
        return try {
            val resp = app.get(VENDOR_URL, headers = VENDOR_HEADERS, timeout = BRIDGE_TIMEOUT)
            if (!resp.isSuccessful) {
                Log.d(TAG, "anmx loader download: status ${resp.code}")
                null
            } else {
                resp.text.also {
                    vendorCache = it
                    vendorCacheAt = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "anmx loader download failed: ${e.message}")
            null
        }
    }

    // a fresh page per episode: the challenge answer is single episode, a
    // reused loader context answers with 403 for any other episode
    private suspend fun loadFreshPage(view: WebView, pageUrl: String): Boolean {
        val gate = CompletableDeferred<String>()
        pageGate = gate
        view.loadUrl(pageUrl)
        val reached = withTimeoutOrNull(PAGE_LOAD_TIMEOUT) { gate.await() }
        if (reached == null && pageGate === gate) pageGate = null
        return reached != null
    }

    private fun bridgeHttp(url: String, initJson: String): String {
        val fail = { reason: String ->
            JSONObject().put("status", 0).put("error", reason).toString()
        }
        val init = try {
            JSONObject(initJson)
        } catch (_: Exception) {
            return fail("bad init")
        }
        if (!isBridgeTarget(url)) return fail("bridge only speaks to vidcloud.se")
        val method = init.optString("method", "GET").uppercase().ifEmpty { "GET" }
        val headers = mutableMapOf(
            "User-Agent" to BRIDGE_UA,
            "Referer" to "$PLAYER_ORIGIN/",
            "Origin" to PLAYER_ORIGIN,
            "Accept" to "*/*"
        )
        val initHeaders = init.optJSONObject("headers")
        if (initHeaders != null) {
            for (key in initHeaders.keys()) {
                if (!key.equals("User-Agent", true)) headers[key] = initHeaders.optString(key)
            }
        }
        return try {
            runBlocking {
                val resp = if (method == "POST" || method == "PUT") {
                    val bytes = init.optString("bodyB64").takeIf { it.isNotEmpty() }
                        ?.let { Base64.decode(it, Base64.NO_WRAP) }
                        ?: init.optString("body").takeIf { it.isNotEmpty() }?.toByteArray()
                    val contentType = headers.entries.firstOrNull {
                        it.key.equals("Content-Type", true)
                    }?.value ?: "application/octet-stream"
                    val body = bytes ?: ByteArray(0)
                    app.post(
                        url,
                        headers = headers,
                        requestBody = body.toRequestBody(contentType.toMediaType()),
                        timeout = BRIDGE_TIMEOUT
                    )
                } else {
                    app.get(url, headers = headers, timeout = BRIDGE_TIMEOUT)
                }
                val bytes = try {
                    resp.body.bytes()
                } catch (_: Exception) {
                    ByteArray(0)
                }
                val outHeaders = JSONObject()
                try {
                    for (name in resp.headers.names()) {
                        outHeaders.put(name.lowercase(), resp.headers[name])
                    }
                } catch (_: Exception) {
                }
                JSONObject()
                    .put("status", resp.code)
                    .put("headers", outHeaders)
                    .put("bodyB64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .toString()
            }
        } catch (e: Exception) {
            fail(e.message ?: "network error")
        }
    }

    // the page may only make the app fetch the loader's own host family,
    // everything else keeps using the webview's own network
    private fun isBridgeTarget(url: String): Boolean {
        val host = try {
            java.net.URI(url).host?.lowercase() ?: return false
        } catch (_: Exception) {
            return false
        }
        return (host == "vidcloud.se" || host.endsWith(".vidcloud.se")) &&
            url.startsWith("https://")
    }

    private suspend fun runOpen(view: WebView, episodeId: Int, vendorSource: String): String? {
        val tag = "anmx" + tagCounter.incrementAndGet()
        val waiter = CompletableDeferred<String>()
        pending[tag] = waiter
        val vendorJson = JSONObject.quote(vendorSource)
        val script = """
            (async function(){
                var EPID = $episodeId, TAG = "$tag";
                function reply(err, payload){
                    try { window.AnmxBridge.deliver(TAG, err ? ('__err__' + payload) : payload); }
                    catch (e) {}
                }
                function isLoaderHost(u){
                    try { var h = new URL(u, location.href).hostname; return h === 'vidcloud.se' || h.endsWith('.vidcloud.se'); }
                    catch (e) { return false; }
                }
                function installFetchShim(){
                    if (window.__anmxShim) return;
                    window.__anmxShim = true;
                    var orig = window.fetch.bind(window);
                    async function viaApp(url, initObj){
                        var method = (initObj.method || 'GET').toUpperCase();
                        var headers = {};
                        try {
                            if (initObj.headers) {
                                if (initObj.headers instanceof Headers) {
                                    initObj.headers.forEach(function(v, k){ headers[k] = v; });
                                } else {
                                    for (var k in initObj.headers) headers[k] = initObj.headers[k];
                                }
                            }
                        } catch (e) {}
                        var body = null, bodyB64 = null;
                        var b = initObj.body;
                        if (b != null) {
                            if (typeof b === 'string') body = b;
                            else {
                                var u8 = null;
                                if (b instanceof ArrayBuffer) u8 = new Uint8Array(b);
                                else if (ArrayBuffer.isView(b)) u8 = new Uint8Array(b.buffer, b.byteOffset, b.byteLength);
                                else if (b.arrayBuffer) u8 = new Uint8Array(await b.arrayBuffer());
                                if (u8) {
                                    var s = '';
                                    for (var i = 0; i < u8.length; i += 8192) {
                                        s += String.fromCharCode.apply(null, u8.subarray(i, i + 8192));
                                    }
                                    bodyB64 = btoa(s);
                                } else body = String(b);
                            }
                        }
                        var raw = await window.AnmxBridge.http(
                            url,
                            JSON.stringify({method: method, headers: headers, body: body, bodyB64: bodyB64})
                        );
                        var info;
                        try { info = JSON.parse(raw); } catch (e) { info = null; }
                        if (!info || !info.status) throw new TypeError('Failed to fetch');
                        var bytes = info.bodyB64
                            ? Uint8Array.from(atob(info.bodyB64), function(c){ return c.charCodeAt(0); })
                            : new Uint8Array(0);
                        var text = '';
                        try { text = new TextDecoder().decode(bytes); } catch (e) {}
                        var resp = new Response(
                            info.status === 204 || info.status === 205 ? '' : text,
                            {status: info.status, headers: info.headers || {}}
                        );
                        Object.defineProperty(resp, 'arrayBuffer', {
                            value: function(){ return Promise.resolve(bytes.slice().buffer); }
                        });
                        return resp;
                    }
                    window.fetch = async function(input, init){
                        var url = typeof input === 'string' ? input : (input && input.url) || String(input);
                        if (!isLoaderHost(url)) return orig(input, init);
                        // the webview's own network gets the first try, the
                        // app stack takes over when cloudflare challenges it
                        try {
                            var native = await Promise.race([
                                orig(input, init),
                                new Promise(function(_, reject){ setTimeout(function(){ reject(new Error('native timeout')); }, 6000); })
                            ]);
                            if (native && native.ok) return native;
                        } catch (e) {}
                        return viaApp(url, init || {});
                    };
                }
                try {
                    installFetchShim();
                    if (!window.__oct) {
                        (0, eval)($vendorJson);
                        if (!window.__oct) { reply(true, 'loader source did not expose __oct'); return; }
                    }
                    var p = window.__oct.open(EPID);
                    var r = await p;
                    var m = Array.isArray(r) ? r[0] : r;
                    reply(false, JSON.stringify(m));
                } catch (e) {
                    reply(true, String(e && e.message || e));
                }
            })()
        """.trimIndent()
        try {
            view.evaluateJavascript(script, null)
        } catch (e: Exception) {
            pending.remove(tag)
            Log.d(TAG, "anmx script failed: ${e.message}")
            return null
        }
        val answer = try {
            withTimeoutOrNull(OPEN_TIMEOUT) { waiter.await() }
        } finally {
            pending.remove(tag)
        }
        val body = answer ?: return null
        if (body.startsWith("__err__")) {
            Log.d(TAG, "anmx open rejected: ${body.removePrefix("__err__")}")
            return null
        }
        return body
    }
}
