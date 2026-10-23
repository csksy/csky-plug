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
 * Transport of last resort for cno.to's guarded endpoints. The site's edge
 * lets full browser stacks through and blocks plain http clients on the
 * stream and subtitle paths, so anything the plugin cannot reach directly
 * is fetched from inside a real page on the site, where requests carry the
 * webview's own fingerprint, cookies and the passive cloudflare script that
 * runs on every page load.
 */
internal object CnoWebView {

    private const val TAG = "Cno"

    private const val PAGE_LOAD_TIMEOUT = 12_000L
    private const val FETCH_TIMEOUT = 20_000L
    private const val RETRY_PAGE_LOADS = 2

    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadMutex = Mutex()

    @Volatile
    private var webView: WebView? = null

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
    private var siteCookies: String? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun userAgent(): String? = siteUa
    fun cookies(): String? = siteCookies?.takeIf { it.isNotBlank() }

    private fun harvest() {
        try {
            siteUa = webView?.settings?.userAgentString ?: siteUa
        } catch (_: Exception) {
        }
        try {
            siteCookies = CookieManager.getInstance().getCookie(CnoApi.MAIN_URL)
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
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // the passive challenge script needs a moment after paint
                    // before its clearance lands in the jar
                    mainHandler.postDelayed({
                        harvest()
                        pageReady = true
                        loadGate?.complete(true)
                    }, 400L)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    harvest()
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

    /**
     * Loads the site once, then runs the fetch from page context so it rides
     * the webview's own stack. Returns the response body or null.
     */
    suspend fun fetch(url: String): String? {
        if (failedHard) return null
        if (!url.startsWith(CnoApi.MAIN_URL)) return null
        return withContext(Dispatchers.Main) {
            val view = ensureWebView() ?: return@withContext null
            if (!pageReady) {
                val up = loadedOnce(view)
                if (up == null) {
                    Log.w(TAG, "webview page never came up")
                    return@withContext null
                }
            }
            val tag = "t" + tagCounter.incrementAndGet()
            val waiter = CompletableDeferred<String>()
            pending[tag] = waiter
            val js = buildString {
                append("(function(){try{fetch(")
                append(jsString(url.removePrefix(CnoApi.MAIN_URL)))
                append(",{method:'GET',credentials:'include',headers:{'Accept':'*/*'}})")
                append(".then(function(r){return r.text();})")
                append(".then(function(b){window.CnoBridge.deliver(")
                append(jsString(tag))
                append(",b);})")
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
                return@withContext null
            }
            val body = withTimeoutOrNull(FETCH_TIMEOUT) { waiter.await() }
            pending.remove(tag)
            if (pageReady) harvest()
            if (body != null && body.isNotEmpty()) body else null
        }
    }

    // one page load shared by every caller waiting on it, retried when the
    // site does not come up, null when the webview cannot get there at all
    private suspend fun loadedOnce(view: WebView): WebView? {
        var up: WebView? = null
        loadMutex.withLock {
            if (pageReady) {
                up = view
                return@withLock
            }
            for (attempt in 0 until RETRY_PAGE_LOADS) {
                val gate = CompletableDeferred<Boolean>()
                loadGate = gate
                view.loadUrl(CnoApi.MAIN_URL + "/")
                val ok = withTimeoutOrNull(PAGE_LOAD_TIMEOUT) {
                    gate.await()
                }
                if (ok == true && pageReady) {
                    up = view
                    return@withLock
                }
                Log.w(TAG, "webview page load attempt ${attempt + 1} failed")
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
