package com.csksy.anichan

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

// the watch api binds its answer to a session cookie handed to real browser clients, seals the
// payload with a key derived from the session nonce and hides the servers behind a tier split,
// so the fetch runs inside a real webview the same way the site's own player does
object AniChanWebView {

    private const val TAG = "AniChan"
    private const val PAGE_URL = "https://anichan.to/watch-session-probe"
    private const val FETCH_TIMEOUT = 40_000L
    private const val CACHE_TTL = 10 * 60 * 1000L
    private const val EMPTY_CACHE_TTL = 60 * 1000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var webView: WebView? = null
    @Volatile
    private var pageReady = false
    private val readyWaiters = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    private val pending = ConcurrentHashMap<String, (String?) -> Unit>()
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private class CacheEntry(val json: String, val ts: Long, val empty: Boolean)

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private class Bridge {
        @JavascriptInterface
        fun onResult(token: String, json: String) {
            pending.remove(token)?.invoke(json)
        }
    }

    private suspend fun waitForPage(): Boolean {
        if (pageReady && webView != null) return true
        val ok = suspendCancellableCoroutine { cont ->
            readyWaiters.add { ok -> if (cont.isActive) cont.resume(ok) }
            mainHandler.post { createIfNeeded() }
        }
        return ok
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createIfNeeded() {
        if (webView != null) return
        val ctx = appContext ?: run {
            readyWaiters.forEach { it(false) }
            readyWaiters.clear()
            return
        }
        try {
            val wv = WebView(ctx)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.addJavascriptInterface(Bridge(), "anichanBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null && url.startsWith("https://anichan.to")) {
                        pageReady = true
                        readyWaiters.forEach { it(true) }
                        readyWaiters.clear()
                    }
                }
            }
            webView = wv
            wv.loadUrl(PAGE_URL)
        } catch (e: Exception) {
            Log.e(TAG, "webview init failed: ${e.message}")
            readyWaiters.forEach { it(false) }
            readyWaiters.clear()
        }
    }

    private fun fetchScript(anilistId: Int, ep: Int, categories: List<String>, token: String): String {
        val cats = categories.joinToString(",") { "\"$it\"" }
        return """
(async () => {
    const token = "$token";
    const cats = [$cats];
    const tiers = ["fast", "rest"];
    const W = "9e04528d";
    const K1 = "9jwvrqLYo5sLdkMzuLA7g7u2+jqd250K9E+tFkQTb1A=";
    const K2 = "rv19AhQepRkeSdTetZolkfigCmWZyMITmGuhZ5cMHUI=";
    const b64 = (t) => Uint8Array.from(atob(t), (c) => c.charCodeAt(0));
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    const deliver = (o) => { try { anichanBridge.onResult(token, JSON.stringify(o)); } catch (e) {} };
    let hmacKey = null;
    try {
        const a = b64(K1), b = b64(K2);
        const x = new Uint8Array(a.length);
        for (let i = 0; i < a.length; i++) x[i] = a[i] ^ b[i];
        hmacKey = await crypto.subtle.importKey("raw", x, {name: "HMAC", hash: "SHA-256"}, false, ["sign"]);
    } catch (e) { hmacKey = null; }
    const newSession = async () => {
        try {
            const r = await fetch("/api/watch/session", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({token: ""}),
                cache: "no-store"
            });
            return r.ok ? await r.json() : null;
        } catch (e) { return null; }
    };
    let session = await newSession();
    // the api answers 401 and a stale sealed payload whenever the request lands on a worker
    // that does not know the session, a fresh session fixes both so retry that way
    const load = async (cat, tier) => {
        for (let i = 0; i < 4; i++) {
            if (!session || !session.n) {
                session = await newSession();
                if (!session || !session.n) { await sleep(400); continue; }
            }
            try {
                const r = await fetch("/api/watch/servers?anilistId=$anilistId&ep=$ep&category=" + cat + "&tier=" + tier,
                    {headers: {"X-Wk": W}, cache: "no-store"});
                if (r.status === 401) { session = await newSession(); await sleep(200); continue; }
                if (!r.ok) return {servers: []};
                let j = await r.json();
                if (j && j.v === 1) {
                    if (!hmacKey) return {servers: []};
                    const mac = await crypto.subtle.sign("HMAC", hmacKey, new TextEncoder().encode(session.n));
                    const ak = await crypto.subtle.importKey("raw", mac, {name: "AES-GCM"}, false, ["decrypt"]);
                    j = JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({name: "AES-GCM", iv: b64(j.i)}, ak, b64(j.d))));
                }
                return j || {servers: []};
            } catch (e) {
                session = await newSession();
                await sleep(200);
            }
        }
        return {servers: []};
    };
    const merged = [];
    const seen = new Set();
    for (const cat of cats) {
        for (const tier of tiers) {
            const j = await load(cat, tier);
            for (const sv of (j && j.servers) || []) {
                if (cat === "hsub" && !sv.subType && sv.type === "hls") sv.subType = "hard";
                const key = sv.type === "embed"
                    ? [sv.name, sv.label, sv.embed].join("|")
                    : [sv.name, sv.label, sv.subType].join("|");
                if (seen.has(key)) continue;
                seen.add(key);
                merged.push(sv);
            }
        }
    }
    deliver({servers: merged});
})();
"""
    }

    private suspend fun evaluate(script: String, token: String): String? {
        return withTimeoutOrNull(FETCH_TIMEOUT) {
            suspendCancellableCoroutine { cont ->
                pending[token] = { json -> if (cont.isActive) cont.resume(json) }
                cont.invokeOnCancellation { pending.remove(token) }
                mainHandler.post {
                    val wv = webView
                    if (wv == null) {
                        pending.remove(token)?.invoke(null)
                    } else {
                        wv.evaluateJavascript(script, null)
                    }
                }
            }
        }
    }

    suspend fun fetchServers(anilistId: Int, ep: Int, category: String): String? {
        val key = "$anilistId:$ep:$category"
        val now = System.currentTimeMillis()
        cache[key]?.let { entry ->
            val ttl = if (entry.empty) EMPTY_CACHE_TTL else CACHE_TTL
            if (now - entry.ts < ttl) return entry.json
            cache.remove(key)
        }

        if (!waitForPage()) {
            Log.e(TAG, "webview page unavailable")
            return null
        }

        // hardsub versions live on their own category, they ride along under the sub tab
        val categories = if (category == "dub") listOf("dub") else listOf("sub", "hsub")
        val firstToken = UUID.randomUUID().toString()
        var result = evaluate(fetchScript(anilistId, ep, categories, firstToken), firstToken) ?: run {
            Log.e(TAG, "servers fetch timed out for $key")
            return null
        }

        // the site serves empty lists under burst load too and retries itself, mirror that once
        if (isEmptyList(result)) {
            delay(2500L)
            val retryToken = UUID.randomUUID().toString()
            result = evaluate(fetchScript(anilistId, ep, categories, retryToken), retryToken) ?: result
        }

        val empty = isEmptyList(result)
        cache[key] = CacheEntry(result, System.currentTimeMillis(), empty)
        if (empty) Log.d(TAG, "empty server list for $key")
        return result
    }

    private fun isEmptyList(json: String): Boolean {
        return try {
            parseJson<ServersEnvelope>(json).servers.isNullOrEmpty()
        } catch (e: Exception) {
            Log.d(TAG, "servers parse check failed: ${e.message}")
            true
        }
    }
}
