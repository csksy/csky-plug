package com.vidcloud

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
 * The site sits behind Cloudflare's bot check and refuses every plain http
 * client on the api paths while letting full browser stacks through, so
 * anything the plugin cannot reach directly is fetched from inside a real
 * page on the site, where requests ride the webview's own fingerprint and
 * cookie jar. The page's own script also mints the auth_token cookie the api
 * wants, so the jar carries both the php session and the bearer.
 */
internal object VidCloudWebView {

    private const val TAG = "VidCloud"

    private const val PAGE_LOAD_TIMEOUT = 20_000L
    private const val FETCH_TIMEOUT = 25_000L
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

    fun token(): String? =
        siteCookies?.let { Regex("auth_token=([^;]+)").find(it)?.groupValues?.get(1) }

    private fun harvest() {
        try {
            siteUa = webView?.settings?.userAgentString ?: siteUa
        } catch (_: Exception) {
        }
        try {
            siteCookies = CookieManager.getInstance().getCookie(VidCloudApi.SITE)
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
            created.addJavascriptInterface(Bridge(), "VidCloudBridge")
            created.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // the site's own script mints the auth token right after
                    // paint, give it a beat before the jar is read
                    mainHandler.postDelayed({
                        harvest()
                        pageReady = true
                        loadGate?.complete(true)
                    }, 600L)
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
     * Runs the fetch from page context so it rides the webview's own stack,
     * with the site's own header set on it. The token comes from the page's
     * cookie jar; when the page has none yet it is minted the same way the
     * site's own script mints it. The html flag switches the ask to the
     * header set a real page load wears, which is what a document route
     * like the player page expects. Returns status and body, or null.
     */
    suspend fun fetch(path: String, html: Boolean = false): Pair<Int, String>? {
        if (failedHard) return null
        if (!path.startsWith("/")) return null
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
                append("(function(){")
                append("var TAG=")
                append(jsString(tag))
                append(",PATH=")
                append(jsString(path))
                append(",RID=")
                append(jsString(VidCloudApi.randomId))
                append(",HTML=")
                append(if (html) "true" else "false")
                append(";")
                append("function done(s,b){try{window.VidCloudBridge.deliver(TAG,s+'|'+b);}catch(e){}}")
                append("function go(tok){")
                append("if(HTML){fetch(PATH,{method:'GET',credentials:'include',")
                append("headers:{'Accept':'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8'}})")
                append(".then(function(r){r.text().then(function(b){done(String(r.status),b);});})")
                append(".catch(function(e){done('0','');});return;}")
                append("var h={'Accept':'application/json, text/plain, */*','Randomid':RID,'Client-Type':'WEB','Client-Id':'5eb393ee95fab7468a79d189','Client-Version':'2.1.6','Version':'0.0.1'};")
                append("if(tok){h['Authorization']='Bearer '+tok;}")
                append("fetch(PATH,{method:'GET',credentials:'include',headers:h})")
                append(".then(function(r){r.text().then(function(b){done(String(r.status),b);});})")
                append(".catch(function(e){done('0','');});}")
                append("var m=/(?:^|;\\s*)auth_token=([^;]+)/.exec(document.cookie||'');")
                append("if(m&&m[1]){go(m[1]);}")
                append("else{")
                append("fetch('/generate_token.php',{credentials:'include',headers:{'Accept':'*/*'}})")
                append(".then(function(r){return r.text();})")
                append(".then(function(t){")
                append("var tok='';try{tok=(JSON.parse(t).access_token)||'';}catch(e){}")
                append("if(tok){try{document.cookie='auth_token='+tok+'; path=/; secure';}catch(e){}}")
                append("go(tok);})")
                append(".catch(function(){go('');});}")
                append("})();")
            }
            try {
                view.evaluateJavascript(js, null)
            } catch (e: Exception) {
                pending.remove(tag)
                Log.w(TAG, "webview script failed: ${e.message}")
                return@withContext null
            }
            val delivered = withTimeoutOrNull(FETCH_TIMEOUT) { waiter.await() }
            pending.remove(tag)
            harvest()
            if (delivered == null) return@withContext null
            val sep = delivered.indexOf('|')
            if (sep <= 0) return@withContext null
            val status = delivered.substring(0, sep).toIntOrNull() ?: return@withContext null
            val body = delivered.substring(sep + 1)
            return@withContext status to body
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
                view.loadUrl(VidCloudApi.SITE + "/")
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
