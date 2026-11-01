package com.cno

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
 * Transport of last resort for the guarded hosts. Both beta.cno.to and
 * ani.pm sit behind cloudflare bot management that lets full browser
 * stacks through while refusing plain http clients on some networks, so
 * anything the plugin cannot reach directly is fetched from inside a real
 * page of the site, where the request rides the webview's own
 * fingerprint, cookie jar and the passive challenge script that runs on
 * every page load.
 */
internal object CnoWebView {

    private const val TAG = "Cno"

    private const val PAGE_LOAD_TIMEOUT = 15_000L
    private const val FETCH_TIMEOUT = 20_000L
    private const val RETRY_PAGE_LOADS = 2

    // the passive script needs a moment after paint before its clearance
    // lands in the jar, on an open network no clearance ever arrives so
    // the wait gives up on its own
    private const val CLEARANCE_WAIT_MS = 2500L
    private const val CLEARANCE_POLL_MS = 400L

    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadMutex = Mutex()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var pageOrigin: String? = null

    @Volatile
    private var pageReady = false

    @Volatile
    private var failedHard = false

    @Volatile
    private var loadGate: CompletableDeferred<Boolean>? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val tagCounter = AtomicLong(0)

    @Volatile
    private var siteUa: String? = null

    @Volatile
    private var cookieJar = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun userAgent(): String? = siteUa?.takeIf { it.isNotBlank() }

    // the jar is per host, a clearance only means anything next to the
    // host it was minted on
    fun cookies(url: String): String? {
        val origin = originOf(url) ?: return null
        return cookieJar[origin]?.takeIf { it.isNotBlank() }
    }

    private fun originOf(url: String): String? = try {
        val cut = url.indexOf('/', url.indexOf("//") + 2)
        if (cut <= 0) url else url.substring(0, cut)
    } catch (_: Exception) {
        null
    }

    private fun harvest(origin: String) {
        try {
            siteUa = webView?.settings?.userAgentString ?: siteUa
        } catch (_: Exception) {
        }
        try {
            val jar = CookieManager.getInstance().getCookie(origin.removePrefix("https://").removePrefix("http://"))
            if (!jar.isNullOrBlank()) cookieJar[origin] = jar
        } catch (_: Exception) {
        }
    }

    private class Bridge {
        @JavascriptInterface
        fun deliver(tag: String, body: String) {
            pending.remove(tag)?.complete(body)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView? {
        if (failedHard) return null
        val existing = webView
        if (existing != null) return existing
        val ctx = appContext ?: return null
        val created = WebView(ctx)
        try {
            created.settings.javaScriptEnabled = true
            created.settings.domStorageEnabled = true
            created.settings.mediaPlaybackRequiresUserGesture = false
            CookieManager.getInstance().setAcceptCookie(true)
            try {
                CookieManager.getInstance().setAcceptThirdPartyCookies(created, true)
            } catch (_: Exception) {
            }
            created.addJavascriptInterface(Bridge(), "CnoBridge")
            created.webViewClient = object : WebViewClient() {
                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    url?.let { originOf(it) }?.let { harvest(it) }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val origin = url?.let { originOf(it) } ?: return
                    // a challenge interstitial paints as its own page first,
                    // only the real document behind it counts as loaded
                    val title = view?.title ?: ""
                    if (title.contains("just a moment", true) ||
                        title.contains("attention required", true)
                    ) {
                        return
                    }
                    mainHandler.postDelayed({ settleAndOpen(origin) }, CLEARANCE_POLL_MS)
                }
            }
            webView = created
            return created
        } catch (e: Exception) {
            Log.w(TAG, "webview create failed: ${e.message}")
            failedHard = true
            try {
                created.destroy()
            } catch (_: Exception) {
            }
            return null
        }
    }

    // waits out the passive challenge script after paint, then lets every
    // caller waiting on this page through
    private fun settleAndOpen(origin: String) {
        val start = System.currentTimeMillis()
        mainHandler.post(object : Runnable {
            override fun run() {
                // a newer load for another origin may have taken the view
                // over, that load owns the state from here on
                val current = try {
                    originOf(webView?.url ?: "")
                } catch (_: Exception) {
                    null
                }
                if (current != null && current != origin) return
                harvest(origin)
                val settled = cookieJar[origin]?.contains("cf_clearance") == true ||
                    System.currentTimeMillis() - start >= CLEARANCE_WAIT_MS
                if (settled) {
                    try {
                        CookieManager.getInstance().flush()
                    } catch (_: Exception) {
                    }
                    pageOrigin = origin
                    pageReady = true
                    loadGate?.complete(true)
                } else {
                    mainHandler.postDelayed(this, CLEARANCE_POLL_MS)
                }
            }
        })
    }

    /**
     * Runs the fetch from inside a page of the site itself so it carries
     * the webview's whole browser stack. The page is loaded once and
     * shared, a refused answer invalidates it and reloads once before
     * giving up. Returns the body or null.
     */
    suspend fun fetch(url: String): String? {
        if (failedHard) return null
        val origin = originOf(url) ?: return null
        return withContext(Dispatchers.Main) {
            val view = ensureWebView() ?: return@withContext null
            if (pageReady && pageOrigin == origin) {
                val body = runInPage(view, url)
                if (body != null) return@withContext body
                // the page went stale, most likely an expired clearance,
                // so a fresh page is pulled before the one retry
                Log.d(TAG, "page context refused, reloading ${origin.removePrefix("https://")}")
                pageReady = false
            }
            if (!loadedOnce(view, origin)) {
                Log.w(TAG, "webview page never came up for ${origin.removePrefix("https://")}")
                return@withContext null
            }
            runInPage(view, url)
        }
    }

    private suspend fun runInPage(view: WebView, url: String): String? {
        val tag = "t" + tagCounter.incrementAndGet()
        val waiter = CompletableDeferred<String>()
        pending[tag] = waiter
        val js = buildString {
            append("(function(){try{fetch(")
            append(jsString(url))
            append(",{method:'GET',credentials:'include',headers:{'Accept':'*/*'}})")
            append(".then(function(r){r.text().then(function(b){window.CnoBridge.deliver(")
            append(jsString(tag))
            append(",r.ok?b:'');});})")
            append(".catch(function(e){window.CnoBridge.deliver(")
            append(jsString(tag))
            append(",'');});}catch(e){window.CnoBridge.deliver(")
            append(jsString(tag))
            append(",'');}})();")
        }
        try {
            view.evaluateJavascript(js, null)
        } catch (e: Exception) {
            pending.remove(tag)
            Log.w(TAG, "webview script failed: ${e.message}")
            return null
        }
        val body = try {
            withTimeoutOrNull(FETCH_TIMEOUT) { waiter.await() }
        } finally {
            // a cancelled caller must not leave its tag behind, the bridge
            // would complete a deferred nobody is waiting on anymore
            pending.remove(tag)
        }
        originOf(url)?.let { harvest(it) }
        return body?.takeIf { it.isNotEmpty() }
    }

    // one page load shared by every caller waiting on it, retried when the
    // site does not come up, false when the webview cannot get there at all
    private suspend fun loadedOnce(view: WebView, origin: String): Boolean {
        var up = false
        loadMutex.withLock {
            if (pageReady && pageOrigin == origin) {
                return true
            }
            // the page that is up belongs to another origin, callers of that
            // origin must not run against the page about to replace it
            pageReady = false
            for (attempt in 0 until RETRY_PAGE_LOADS) {
                val gate = CompletableDeferred<Boolean>()
                loadGate = gate
                view.loadUrl("$origin/")
                val ok = withTimeoutOrNull(PAGE_LOAD_TIMEOUT) { gate.await() }
                if (ok == true && pageReady && pageOrigin == origin) {
                    up = true
                    break
                }
                Log.d(TAG, "webview page load attempt ${attempt + 1} failed")
            }
        }
        return up
    }

    private fun jsString(s: String): String {
        val out = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '/' -> out.append("\\/")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }
}
