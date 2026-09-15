package com.csksy.anisnatch

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * Cloudflare fallback for anisnatch.to. The plain OkHttp client gets
 * challenged on some mobile networks (the request never returns real html),
 * so the page is solved once inside a real WebView and the cf_clearance
 * cookie is reused for every later request.
 *
 * Same settings as the ToonWorld4All / AnimeWorldIndia bypasses that already
 * work on real devices: default user agent, third-party cookies accepted,
 * popups blocked. Only one WebView may run at a time.
 */
internal object AniSnatchWeb {

    private const val TAG = "AniSnatch"
    private const val HOST = "anisnatch.to"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private val webViewMutex = Mutex()

    // host -> cookie header + webview user agent
    private val clearances = HashMap<String, Pair<String, String>>()

    fun isChallengeHtml(html: String): Boolean {
        if (html.length > 400_000) return false
        return html.contains("Just a moment") ||
            html.contains("cf_chl_") ||
            html.contains("challenge-platform") ||
            html.contains("cf-browser-verification") ||
            html.contains("Verify you are human") ||
            html.contains("cf-chl-opt")
    }

    fun clearanceHeaders(url: String, base: Map<String, String>): Map<String, String> {
        val host = try {
            java.net.URI(url).host ?: HOST
        } catch (e: Exception) {
            HOST
        }
        val cached = synchronized(clearances) { clearances[host] } ?: return base
        val out = LinkedHashMap(base)
        out["Cookie"] = cached.first
        out["User-Agent"] = cached.second
        return out
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView? {
        val ctx = appContext ?: return null
        val webView = WebView(ctx)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // the user agent stays at the device default, Cloudflare matches
            // it against the browser environment
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val host = request.url.host ?: return false
                return host != HOST && !host.endsWith(".$HOST") &&
                    host != "challenges.cloudflare.com"
            }
        }
        webView.webChromeClient = object : WebChromeClient() {}
        return webView
    }

    /*
     * Loads the url in the WebView and waits for the cf_clearance cookie.
     * Returns the cookie header together with the WebView user agent, or
     * null when the challenge did not resolve in time.
     */
    suspend fun solveCloudflare(url: String, timeoutMs: Long = 40_000L): Pair<String, String>? =
        webViewMutex.withLock {
            val host = try {
                java.net.URI(url).host ?: HOST
            } catch (e: Exception) {
                HOST
            }
            val targetUrl = "https://$host/"

            val webView = withContext(Dispatchers.Main) {
                runCatching {
                    CookieManager.getInstance().apply {
                        listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                            setCookie(targetUrl, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                        }
                        flush()
                    }
                }
                val wv = buildWebView() ?: return@withContext null
                wv.loadUrl(url)
                wv
            } ?: run {
                Log.d(TAG, "webview unavailable (no context)")
                return null
            }

            try {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val cookie = runCatching {
                        CookieManager.getInstance().getCookie(targetUrl)
                    }.getOrNull()
                    if (cookie != null && cookie.contains("cf_clearance")) {
                        runCatching { CookieManager.getInstance().flush() }
                        val ua = withContext(Dispatchers.Main) { webView.settings.userAgentString }
                        synchronized(clearances) { clearances[host] = cookie to ua }
                        return cookie to ua
                    }
                    delay(400)
                }
                Log.d(TAG, "cloudflare solve timed out for $host")
                return null
            } finally {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                    runCatching { webView.stopLoading() }
                    runCatching { webView.destroy() }
                }
            }
        }

    /*
     * Fetch a page and solve the Cloudflare challenge when the direct request
     * is blocked. Returns the html, or null when it ultimately failed.
     */
    suspend fun fetchHtml(
        url: String,
        headers: Map<String, String>,
        fetch: suspend (String, Map<String, String>) -> String?
    ): String? {
        val first = try {
            fetch(url, clearanceHeaders(url, headers))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        if (first != null && !isChallengeHtml(first) && first.isNotEmpty()) return first

        Log.d(TAG, "cloudflare challenge on $url - solving in webview")
        val solved = solveCloudflare(url)
        if (solved == null) return first

        return try {
            fetch(url, clearanceHeaders(url, headers))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }
}
