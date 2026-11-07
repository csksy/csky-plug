package com.csksy.animex

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The site's own server ("Our server" in the player) is not a plain embed:
 * its manifest needs a challenge handshake against s.vidcloud.se that only
 * the site's obfuscated loader (cdn.vidcloud.se/vjs/vendor.js, exposing
 * window.__oct) knows how to answer, and every answer is bound to a single
 * episode, so the handshake runs in a fresh page of the site's player for
 * each episode, exactly like navigating between episodes on the site.
 */
internal object AnmxWebView {

    private const val TAG = "AnimeX"

    private const val PAGE_LOAD_TIMEOUT = 15_000L
    private const val OPEN_TIMEOUT = 30_000L
    private const val OPEN_RETRIES = 2

    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var failedHard = false

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
     * Runs the site's own source loader for one episode and returns the raw
     * manifest object as JSON, or null when the player page or the loader
     * cannot be brought up.
     */
    suspend fun openManifest(episodeId: Int, pageUrl: String): String? {
        if (failedHard) return null
        openMutex.withLock {
            return withContext(Dispatchers.Main) {
                val view = ensureWebView() ?: return@withContext null
                var lastReason = "no answer"
                for (attempt in 0 until OPEN_RETRIES) {
                    val loaded = loadFreshPage(view, pageUrl)
                    if (!loaded) {
                        lastReason = "player page did not come up"
                        continue
                    }
                    val opened = runOpen(view, episodeId)
                    if (opened != null) return@withContext opened
                    lastReason = "loader refused, reloading a fresh page"
                }
                Log.d(TAG, "anmx og unavailable: $lastReason")
                null
            }
        }
    }

    // a fresh page per episode: the challenge answer is single episode, a
    // reused loader context answers with 403 for any other episode
    private suspend fun loadFreshPage(view: WebView, pageUrl: String): Boolean {
        val gate = CompletableDeferred<String>()
        pageGate = gate
        view.loadUrl(pageUrl)
        val reached = withTimeoutOrNull(PAGE_LOAD_TIMEOUT) { gate.await() }
        return reached != null
    }

    private suspend fun runOpen(view: WebView, episodeId: Int): String? {
        val tag = "anmx" + tagCounter.incrementAndGet()
        val waiter = CompletableDeferred<String>()
        pending[tag] = waiter
        val script = """
            (function(){
                var EPID = $episodeId, TAG = "$tag";
                function reply(err, payload){
                    try { window.AnmxBridge.deliver(TAG, err ? ('__err__' + payload) : payload); }
                    catch (e) {}
                }
                function runOpen(){
                    try {
                        var p = window.__oct.open(EPID);
                        p.then(function(r){
                            var m = Array.isArray(r) ? r[0] : r;
                            reply(false, JSON.stringify(m));
                        }, function(e){ reply(true, String(e && e.message || e)); });
                    } catch (e) { reply(true, String(e && e.message || e)); }
                }
                if (window.__oct) { runOpen(); return; }
                var s = document.createElement('script');
                s.src = 'https://cdn.vidcloud.se/vjs/vendor.js';
                s.onload = function(){ window.__oct ? runOpen() : reply(true, 'loader without __oct'); };
                s.onerror = function(){ reply(true, 'loader script failed'); };
                document.head.appendChild(s);
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
