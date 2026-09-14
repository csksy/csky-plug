package com.toonworld4all

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver

/**
 * Walks the toonworld4all shortener chain inside a real Android WebView.
 *
 * VERIFIED LIVE CHAIN (2026-09, no guesswork):
 *
 *   archive.toonworld4all.me/redirect/{hash}
 *     -> fresh exe.io/{id} link (created per request via the exe.io API)
 *     -> 302 -> exeygo.com/{id}
 *        page 1  : 6s countdown, then <button data-ref="continue"
 *                   class="link-button" disabled> becomes enabled
 *        POST    : /{id} (form #before-captcha, fields _csrfToken, f_n=sle,
 *                   CakePHP _Token[fields]/_Token[unlocked]) - works WITHOUT
 *                   any captcha (verified with plain curl)
 *        page 2  : Cloudflare Turnstile widget; the submit button is
 *                   <button data-ref="captcha" id="invisibleCaptchaShortlink"
 *                   disabled> and only becomes enabled AFTER the Turnstile
 *                   is solved. On a real device with a residential IP the
 *                   managed Turnstile usually passes on its own.
 *        POST    : /{id} (form #link-view + cf-turnstile-response)
 *                   -> 302 -> hubcloud.ist / gdflix / filepress page
 *
 * The old version of this file clicked EVERY button-ish element on the page
 * every 1.2s (including ad links like a[href*="/go/"]) - that navigated the
 * WebView into popunder ads mid-challenge and invalidated the Turnstile,
 * which is exactly what the logcat showed (challenge looping forever).
 * This version clicks ONLY the four verified submit buttons and nothing else.
 *
 * The exe.io public API cannot reverse a short link (tested: /api?api=..&url=
 * an existing link just echoes it; /api/urls* requires session auth), so the
 * WebView walk is the only viable bypass.
 */
object Tw4aShortener {

    const val TAG = "TW4A"

    /**
     * Requests to any of these hosts/paths mean the chain reached the file.
     * (hubcloud / gdflix / filepress / filebee are the hosts used by the
     * archive; pixeldrain, gofile and google drive appear on their dl pages)
     */
    val FILE_HOST = Regex(
        """(?i)(hubcloud\.|gdflix|filepress|filebee|gdtot|appdrive""" +
                """|pixeldrain|gofile\.io|drive\.google\.com|googleusercontent""" +
                """|\.mp4|\.mkv|\.m3u8|\.ts(?![a-z]))"""
    )

    /**
     * SURGICAL clicker - pressed every ~1.5s while the chain runs.
     * ONLY the exact submit buttons of the shortener stages:
     *  - exeygo p1: button[data-ref="continue"]  (enabled after 6s countdown)
     *  - exeygo p2: button[data-ref="captcha"] / #invisibleCaptchaShortlink
     *               (enabled only after Turnstile is solved)
     *  - cuty.io:   button#submit-button
     *  - gplinks:   a.gate-btn-skip / button#VerifyBtn
     * NEVER generic links/buttons - those are popunder ads, and clicking them
     * destroys the Turnstile session (that was the v1 bug).
     */
    private val AUTO_CLICK = """
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
                if (clicks > 80) clearInterval(t);
            }, 1500);
        })();
    """.trimIndent()

    /** In-memory cache: shortener url -> resolved host url (or "" for failures). */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Resolve a shortener URL to the final file-host URL.
     * Result is cached: shortener links are single-use sessions, but the
     * destination (hubcloud/gdflix page) is stable for the file.
     */
    suspend fun resolve(destination: String): String? {
        if (destination.isBlank()) return null
        cache[destination]?.let { return it.ifBlank { null } }

        // 1. quick okhttp pass - some shorteners just 302 (exe.io does not,
        //    it serves an html page, but this is nearly free to try)
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

        // 2. full WebView walk with the surgical clicker.
        //    65s: 6s countdown + slow turnstile + ads + margin.
        val resolved = try {
            val resolver = WebViewResolver(
                interceptUrl = FILE_HOST,
                additionalUrls = listOf(FILE_HOST),
                script = AUTO_CLICK,
                useOkhttp = false,
                timeout = 65_000L
            )
            val finalUrl = app.get(
                destination,
                interceptor = resolver,
                // NiceHttp timeout is in SECONDS - must outlive the 65s walk
                timeout = 75L
            ).url
            if (FILE_HOST.containsMatchIn(finalUrl)) finalUrl else null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.d(TAG, "webview resolution failed: ${e.message}")
            null
        }
        // cache failures briefly too so we do not spin on dead links
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
                if (FILE_HOST.containsMatchIn(location)) return location
                current = location
                hops++
            } else {
                return null
            }
        }
        return null
    }

    fun clearCache() = cache.clear()
}
