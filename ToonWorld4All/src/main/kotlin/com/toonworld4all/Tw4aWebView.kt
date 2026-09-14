package com.toonworld4all

import android.annotation.SuppressLint
import android.content.Context
import android.os.Message
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Healthy background WebView used for everything the plain OkHttp client
 * cannot do:
 *
 *  1. WALKING THE exe.io -> exeygo.com SHORTENER CHAIN
 *     (Tw4aShortener.solveShortener)
 *  2. SOLVING CLOUDFLARE MANAGED CHALLENGES on toonworld4all.me /
 *     archive.toonworld4all.me (solveCloudflare + getWithCf)
 *
 * WHY A CUSTOM WEBVIEW AND NOT WebViewResolver (v2 did that and it failed):
 *  - WebViewResolver NEVER calls
 *      CookieManager.setAcceptCookie(true) /
 *      CookieManager.setAcceptThirdPartyCookies(webView, true)
 *    Cloudflare Turnstile runs inside a challenges.cloudflare.com IFRAME on
 *    exeygo.com and REQUIRES third-party cookies - with them disabled the
 *    challenge loops on ".../failure_retry/..." forever, which is exactly
 *    what the user logcat showed (web-view timeout after 65s, no sources).
 *  - WebViewResolver does not block popup windows, so exeygo's popunder ads
 *    (window.open / target=_blank to 4ace.online, demand.supply, ...) can
 *    hijack the main frame mid-challenge and invalidate the Turnstile.
 *  - WebViewResolver injects its script on every sub-resource request; we
 *    inject once per page load instead.
 *
 * The exact same settings are the ones used by the AnimeWorldIndia CF bypass
 * that already ships in this repository (setAcceptCookie(true) +
 * setAcceptThirdPartyCookies(true)), so the pattern is known-good on real
 * devices.
 *
 * All WebViews are created on the main thread and there is NEVER more than
 * one alive at a time (mutex) - parallel Turnstile challenges on one device
 * sabotage each other.
 */
object Tw4aWebView {

    private const val TAG = "TW4A"

    /** Context captured from Plugin.load() - always the application context. */
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /** Only one WebView may ever run at a time (challenges sabotage each other). */
    private val webViewMutex = Mutex()

    /**
     * Hosts the shortener chain may navigate the main frame to.
     * Everything else (popunder ad networks) is blocked, which is what
     * destroyed the v1/v2 walks.
     */
    private val ALLOWED_NAV_HOSTS = listOf(
        "exe.io", "exeygo.com",           // the verified chain
        "cuty.io", "gplinks.com",         // alternate shorteners the archive can hand out
        "challenges.cloudflare.com",      // turnstile widget
        "cloudflareinsights.com",         // beacon (harmless)
        "toonworld4all.me",               // home site
    )

    /**
     * A main-frame navigation that lands here means the chain reached the
     * actual file host. (hubcloud / gdflix / filepress / filebee / gdtot /
     * appdrive / pixeldrain / gofile / gdrive / direct files)
     */
    val FILE_HOST = Regex(
        """(?i)(hubcloud\.|gdflix|filepress|filebee|gdtot|appdrive""" +
                """|pixeldrain|gofile\.io|drive\.google\.com|googleusercontent""" +
                """|\.mp4|\.mkv|\.m3u8|\.ts(?![a-z]))"""
    )

    /** Cloudflare managed-challenge interstitial markers (page-level, NOT the
     *  legit turnstile <script src> tag that normal pages embed). */
    private val CHALLENGE_MARKERS = listOf(
        "Just a moment",
        "cf-browser-verification",
        "Checking your browser before accessing",
        "Verify you are human",
        "Attention Required! | Cloudflare",
        "__cf_chl_",
        "cf-chl-opt",
    )

    // ------------------------------------------------------------------ //
    //  Cloudflare clearance cache (host -> cookie header + webview UA)
    // ------------------------------------------------------------------ //

    private val cfCookies = ConcurrentHashMap<String, Pair<String, String>>()

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: ""
    } catch (e: Exception) {
        ""
    }

    /** True when the html looks like a Cloudflare block page. */
    fun isChallengeHtml(html: String): Boolean {
        if (html.length > 400_000) return false
        return CHALLENGE_MARKERS.any { html.contains(it) }
    }

    /** Base headers + cached Cloudflare clearance (cookie + webview UA)
     *  for the given url's host, when we have solved one. */
    fun cfHeaders(url: String, base: Map<String, String>): Map<String, String> {
        val cached = cfCookies[hostOf(url)] ?: return base
        val out = base.toMutableMap()
        out["Cookie"] = cached.first
        out["User-Agent"] = cached.second
        return out
    }

    /**
     * GET with automatic Cloudflare bypass:
     *   1. plain fetch (with cached clearance cookies if we have them)
     *   2. if the body looks like a challenge -> solve it once in the
     *      healthy WebView (waits for cf_clearance) -> re-fetch
     * Returns the html, or null when the request ultimately failed.
     */
    suspend fun getWithCf(
        url: String,
        headers: Map<String, String>,
        timeoutSec: Long = 25L,
        allowRedirects: Boolean = true,
    ): String? {
        val host = hostOf(url)

        // pass 1 (and cached-cookie fast path)
        val html = try {
            com.lagradost.cloudstream3.app.get(
                url, headers = cfHeaders(url, headers), timeout = timeoutSec,
                allowRedirects = allowRedirects
            ).text
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(TAG, "getWithCf fetch failed (${e.message})")
            return null
        }
        if (!isChallengeHtml(html)) return html

        // pass 2: solve the challenge in the healthy WebView, then retry
        Log.d(TAG, "cloudflare challenge on $host - solving in webview")
        val solved = solveCloudflare(url)
        if (solved == null) {
            Log.d(TAG, "cloudflare not solved for $host")
            return html
        }
        return try {
            com.lagradost.cloudstream3.app.get(
                url, headers = cfHeaders(url, headers), timeout = timeoutSec,
                allowRedirects = allowRedirects
            ).text
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    // ------------------------------------------------------------------ //
    //  shared WebView factory
    // ------------------------------------------------------------------ //

    /**
     * Build a WebView with every setting the challenges need:
     *  - JS + DOM storage
     *  - cookies ACCEPTED, including THIRD-PARTY (the Turnstile fix)
     *  - DEFAULT user agent (Cloudflare breaks on custom UAs)
     *  - popup windows blocked (popunder ads can no longer hijack the walk)
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(
        onNavigate: (String) -> Unit,
        onPageLoaded: (WebView, String?) -> Unit
    ): WebView? {
        val ctx = appContext ?: return null
        val webView = WebView(ctx)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)          // popups go to onCreateWindow
            javaScriptCanOpenWindowsAutomatically = false
            // userAgent left at the device default on purpose - Cloudflare
            // fingerprint-matches the UA against the TLS/JS environment.
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true) // REQUIRED by Turnstile
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                return !isAllowedNavigation(url)     // true = block (ads)
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String?,
                isReload: Boolean
            ) {
                url?.let(onNavigate)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                onPageLoaded(view, url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            // swallow every window.open / target=_blank - those are popunder
            // ads; blocking them keeps the challenge session intact
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean = false
        }
        return webView
    }

    private fun isAllowedNavigation(url: String): Boolean {
        if (!url.startsWith("http")) return true          // about:, data:, blob:
        if (FILE_HOST.containsMatchIn(url)) return true   // the goal
        val host = hostOf(url).lowercase()
        if (host.isEmpty()) return true
        return ALLOWED_NAV_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    // ------------------------------------------------------------------ //
    //  1. shortener walk
    // ------------------------------------------------------------------ //

    /**
     * SURGICAL clicker - injected once per page load.
     * ONLY the exact submit buttons of the verified shortener stages are
     * ever pressed (never generic links - those are popunder ads):
     *  - exeygo p1: button[data-ref="continue"]  (enables after 6s countdown)
     *  - exeygo p2: button[data-ref="captcha"]   (enables after Turnstile)
     *  - cuty.io:   button#submit-button
     *  - gplinks:   a.gate-btn-skip / button#VerifyBtn
     */
    private val CLICKER = """
        (function () {
            if (window.__tw4aClicker) return;
            window.__tw4aClicker = 1;
            var clicks = 0;
            var t = setInterval(function () {
                clicks++;
                try {
                    var b;
                    if ((b = document.querySelector('button[data-ref="continue"]')) &&
                        !b.disabled) { b.click(); return; }
                    if ((b = document.querySelector('button[data-ref="captcha"]')) &&
                        !b.disabled) { b.click(); return; }
                    if ((b = document.querySelector('button#invisibleCaptchaShortlink')) &&
                        !b.disabled) { b.click(); return; }
                    if ((b = document.querySelector('button#submit-button')) &&
                        !b.disabled) { b.click(); return; }
                    if ((b = document.querySelector('button#VerifyBtn')) &&
                        !b.disabled) { b.click(); return; }
                    var g = document.querySelector('a.gate-btn-skip');
                    if (g && g.href && g.href.indexOf(location.origin) !== 0 &&
                        g.href.charAt(g.href.length - 1) !== '#') { g.click(); return; }
                } catch (e) { }
                if (clicks > 90) clearInterval(t);
            }, 1500);
        })();
    """.trimIndent()

    /**
     * Walk the shortener chain (exe.io -> exeygo.com p1 -> p2 -> file host)
     * in a real, healthy WebView and return the file-host URL.
     *
     * The WebView follows redirects on its own; the clicker presses the
     * stage buttons; doUpdateVisitedHistory reports the landing URL.
     * Returns null on timeout.
     */
    suspend fun solveShortener(
        startUrl: String,
        timeoutMs: Long = 80_000L
    ): String? = webViewMutex.withLock {
        // atomic: written from the WebView (main) thread, polled from IO
        val resultUrl = java.util.concurrent.atomic.AtomicReference<String?>(null)

        val webView = withContext(Dispatchers.Main) {
            val wv = buildWebView(
                onNavigate = { url ->
                    if (resultUrl.get() == null &&
                        FILE_HOST.containsMatchIn(url) &&
                        !isShortenerUrl(url)
                    ) {
                        resultUrl.set(url)
                    }
                },
                onPageLoaded = { view, _ ->
                    // re-injected per page; the __tw4aClicker guard makes it
                    // a no-op when the interval is already running
                    runCatching { view.evaluateJavascript(CLICKER, null) }
                }
            ) ?: return@withContext null
            wv.loadUrl(startUrl)
            wv
        } ?: run {
            Log.d(TAG, "no context for webview (plugin not loaded?)")
            return null
        }

        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                resultUrl.get()?.let { return it }
                delay(250)
            }
            Log.d(TAG, "shortener walk timed out: $startUrl")
            return null
        } finally {
            // NonCancellable: the walk may be aborted mid-flight (user backed
            // out) - the WebView must still be destroyed or it leaks
            withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                runCatching { webView.stopLoading() }
                runCatching { webView.destroy() }
            }
        }
    }

    private fun isShortenerUrl(url: String): Boolean {
        val host = hostOf(url).lowercase()
        return host == "exe.io" || host.endsWith(".exe.io") ||
                host == "exeygo.com" || host.endsWith(".exeygo.com")
    }

    // ------------------------------------------------------------------ //
    //  2. Cloudflare managed challenge solver
    // ------------------------------------------------------------------ //

    /**
     * Load a Cloudflare-challenged URL in the healthy WebView and wait for
     * the cf_clearance cookie. Returns (cookieHeader, userAgent) for the
     * OkHttp retry, or null when the challenge did not resolve in time.
     */
    suspend fun solveCloudflare(
        url: String,
        timeoutMs: Long = 45_000L
    ): Pair<String, String>? = webViewMutex.withLock {
        val host = hostOf(url)
        val targetUrl = "https://$host/"

        val webView = withContext(Dispatchers.Main) {
            // drop any stale clearance for this host first so the challenge
            // runs fresh (same approach as the AnimeWorldIndia bypass)
            runCatching {
                CookieManager.getInstance().apply {
                    listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                        setCookie(targetUrl, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                    }
                    flush()
                }
            }
            val wv = buildWebView(
                onNavigate = { },
                onPageLoaded = { _, _ -> }
            ) ?: return@withContext null
            wv.loadUrl(url)
            wv
        } ?: run {
            Log.d(TAG, "no context for webview (plugin not loaded?)")
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
                    Log.d(TAG, "cloudflare solved for $host")
                    cfCookies[host] = cookie to ua   // cache for cfHeaders()
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
}
