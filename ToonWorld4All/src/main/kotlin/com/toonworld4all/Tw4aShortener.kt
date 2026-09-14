package com.toonworld4all

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver

/**
 * Walks the toonworld4all shortener chain inside a real Android WebView.
 *
 * The chain (verified live) is one of:
 *   exe.io  -> exeygo.com   (6s countdown -> Continue -> Turnstile -> ads -> host)
 *   cuty.io -> cuttty.com   (countdown -> submit form -> Turnstile -> ads -> host)
 *   gplinks.co             (splash-check rounds -> gate -> skrresults ads -> host)
 *
 * Every one of them ends in a Cloudflare Turnstile which cannot be solved
 * headlessly - but on a real device with a residential IP, Turnstile is
 * "managed" mode and auto-passes almost instantly. The injected script
 * presses the Continue / Verify / Get-Link / skip buttons on a timer so the
 * chain keeps moving without user interaction; WebViewResolver watches the
 * network until a request to a known file host shows up.
 */
object Tw4aShortener {

    /** Requests to any of these hosts/paths mean the chain reached the file. */
    val FILE_HOST = Regex(
        """(?i)(hubcloud\.ist|gdflix|filepress|filebee|mega\.nz|pixeldrain""" +
                """|drive\.google\.com|googleusercontent|\.mp4|\.mkv|\.m3u8)"""
    )

    /**
     * Buttons pressed every ~1.2s while the chain runs:
     *  - exeygo:   button[data-ref="continue"] (after countdown) + Turnstile confirm
     *  - cutty:    button#submit-button (data-ref first/captcha)
     *  - gplinks:  .gate-btn-skip / a[href*="skip_sub"] (Continue with ads),
     *              #VerifyBtn on the skrresults ad page
     *  - hosts:    #download / a.btn-success style buttons
     */
    private val AUTO_CLICK = """
        (function () {
            var ticks = 0;
            var timer = setInterval(function () {
                ticks++;
                try {
                    var selectors = [
                        'button[data-ref="continue"]',
                        'button[data-ref="captcha"]',
                        'button[data-ref="first"]',
                        'button#submit-button',
                        'button#VerifyBtn',
                        'a.gate-btn-skip',
                        '.gate-btn-skip',
                        'a[href*="skip_sub"]',
                        'button.link-button',
                        'a.button.link-button',
                        'a[href*="/go/"]',
                        '#download',
                        'a#download',
                        'button.btn-success',
                        'a.btn-success'
                    ];
                    for (var i = 0; i < selectors.length; i++) {
                        var el = document.querySelector(selectors[i]);
                        if (el) {
                            if (el.disabled === true) continue;
                            if (el.tagName === 'A' && el.getAttribute('href') &&
                                el.getAttribute('href').charAt(0) === '#') continue;
                            el.click();
                        }
                    }
                } catch (e) { }
                if (ticks > 90) clearInterval(timer);
            }, 1200);
        })();
    """.trimIndent()

    /** Resolve the shortener URL to the final file-host URL. */
    suspend fun resolve(destination: String): String? {
        if (destination.isBlank()) return null

        // A few shorteners answer with a plain redirect - try OkHttp first
        // (fast path, no WebView needed).
        try {
            val quickHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )
            var current = destination
            var hops = 0
            while (hops < 6) {
                val resp = app.get(
                    current, allowRedirects = false, timeout = 15L, headers = quickHeaders
                )
                val location = resp.headers["location"]
                if (location != null && location.startsWith("http")) {
                    if (FILE_HOST.containsMatchIn(location)) return location
                    current = location
                    hops++
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            Log.d("TW4A", "okhttp fast-path failed: ${e.message}")
        }

        // Full WebView walk.
        return try {
            val resolver = WebViewResolver(
                interceptUrl = FILE_HOST,
                additionalUrls = listOf(FILE_HOST),
                script = AUTO_CLICK,
                useOkhttp = false,
                timeout = 40_000L
            )
            val resolved = app.get(destination, interceptor = resolver).url
            if (FILE_HOST.containsMatchIn(resolved)) resolved else null
        } catch (e: Exception) {
            Log.d("TW4A", "webview resolution failed: ${e.message}")
            null
        }
    }
}
