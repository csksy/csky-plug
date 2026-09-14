package com.toonworld4all

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app

/**
 * Walks the toonworld4all shortener chain inside a real, healthy Android
 * WebView (Tw4aWebView).
 *
 * VERIFIED LIVE CHAIN (2026-09, no guesswork):
 *
 *   archive.toonworld4all.me/redirect/{hash}
 *     -> fresh exe.io/{id} link (created per request via the exe.io API -
 *        the site's own tokens are visible in the archive's routes.js)
 *     -> 302 -> exeygo.com/{id}
 *        page 1  : 6s countdown, then <button data-ref="continue"> enables
 *        POST    : /{id} (form #before-captcha, fields _csrfToken, f_n=sle,
 *                  CakePHP _Token[fields]/_Token[unlocked])
 *        page 2  : Cloudflare Turnstile widget (iframe from
 *                  challenges.cloudflare.com); <button data-ref="captcha">
 *                  enables ONLY after the Turnstile solves -> needs
 *                  THIRD-PARTY COOKIES (Tw4aWebView enables them)
 *        POST    : /{id} (form #link-view + cf-turnstile-response)
 *                  -> 302 -> hubcloud.ist / gdflix / filepress page
 *
 * The link.domain + link.hidden values shown on the archive chooser page
 * are RANDOM JUNK (Fh() in their bundle; requesting hubcloud.ist/video/{hidden}
 * returns "404 ! File Not Found" with a 200 status - tested live). The ONLY
 * real field is "destination".
 *
 * POSTing the link-view form without a solved turnstile token is rejected
 * (tested live: 302 back to page 1), so the WebView walk is the only way.
 *
 * The exe.io public API cannot reverse a short link either (tested: /api?api=
 * ..&url= with an existing link just echoes it), so there is no shortcut.
 */
object Tw4aShortener {

    private const val TAG = "TW4A"

    /** In-memory cache: shortener url -> resolved host url (blank = failure). */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Failed resolutions are remembered briefly so dead links do not spin. */
    private val failTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val FAIL_TTL = 5 * 60_000L

    /**
     * Resolve a shortener URL to the final file-host URL.
     * Cached: shortener links are single-use sessions, but the destination
     * (hubcloud/gdflix page) is stable for the file.
     */
    suspend fun resolve(destination: String): String? {
        if (destination.isBlank()) return null
        cache[destination]?.let { return it.ifBlank { null } }

        val fresh = System.currentTimeMillis() - (failTimes[destination] ?: 0L) > FAIL_TTL
        if (!fresh) return null

        // 1. quick okhttp pass - some shorteners just 302 (exe.io serves an
        //    html page, but this is nearly free to try)
        val quick = try {
            okhttpFastPath(destination)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        if (quick != null) {
            cache[destination] = quick
            return quick
        }

        // 2. full WebView walk in the healthy WebView (third-party cookies
        //    enabled for the Turnstile, popunder ads blocked, surgical
        //    clicker). 80s: 6s countdown + turnstile + ads + margin.
        val resolved = Tw4aWebView.solveShortener(destination, timeoutMs = 80_000L)
        if (resolved == null) {
            Log.d(TAG, "shortener bypass failed: $destination")
            failTimes[destination] = System.currentTimeMillis()
        }
        cache[destination] = resolved.orEmpty()
        return resolved
    }

    /** Follow plain HTTP redirects; returns a host url if one appears early. */
    private suspend fun okhttpFastPath(destination: String): String? {
        val quickHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        var current = destination
        var hops = 0
        while (hops < 6) {
            val resp = app.get(
                current, allowRedirects = false, timeout = 8L, headers = quickHeaders
            )
            val location = resp.headers["location"]
            if (location != null && location.startsWith("http")) {
                if (Tw4aWebView.FILE_HOST.containsMatchIn(location)) return location
                current = location
                hops++
            } else {
                return null
            }
        }
        return null
    }

    fun clearCache() {
        cache.clear()
        failTimes.clear()
    }
}
