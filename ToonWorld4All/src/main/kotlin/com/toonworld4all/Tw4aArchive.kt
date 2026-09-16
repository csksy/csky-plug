package com.toonworld4all

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for archive.toonworld4all.me - the SSR React app that backs every
 * download link on toonworld4all.me.
 *
 * Every page (episode + movie + redirect chooser) embeds its whole state as
 * JSON inside `window.__PROPS__ = { ... };` right before </script>, so no
 * DOM scraping is needed - just a regex + JSON parse.
 *
 * Episode/movie page props shape:
 * {
 *   "data": { "data": {
 *       "updated_at": "...",
 *       "metadata": { slug, season, episode, type: "EPISODE"|"MOVIE", encoder,
 *                     show, air, name, overview, runtime, poster },
 *       "encodes": [ { resolution:"480p", is_hq, bit_depth:8, codec:"H.264",
 *                       size:"112603611",
 *                       readable:{ codec:"480p x264", size:"107.39 MB" },
 *                       files:[ { host:"HubCloud", link:"/redirect/{hash}", short:"hubcloud.ist" }, ... ] } ],
 *       "streams": []
 *   } },
 *   "userSelectedSystem": "24hour"
 * }
 *
 * Redirect endpoints come in two shapes. Fresh sessions get the 24hour
 * chooser page whose props hold the exe.io destination. After switching the
 * session to the manual system (POST /api/user/preference/system?id=manual)
 * every fetch answers a 302 straight to the shortener, and the destination
 * rotates through the site's three shorteners in a fixed cycle per file:
 * cuty.io -> gplinks.co -> exe.io (the order differs per encoder but always
 * contains all three).
 *
 * link.domain + link.hidden on the chooser page are random junk - requesting
 * gdflix.dev/file/{hidden} lands on a dead-file shell and hubcloud answers
 * "File Not Found", so the destination is the only real field.
 */
object Tw4aArchive {

    const val BASE = "https://archive.toonworld4all.me"

    private val PROPS_REGEX = Regex(
        """window\.__PROPS__\s*=\s*(\{.*?\})\s*;?\s*(?:</script>|$)""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://toonworld4all.me/",
    )

    class ArchiveData(
        val metadata: JSONObject?,
        val encodes: List<JSONObject>,
        val streams: List<JSONObject>,
    )

    /** Fetch an episode/movie page and pull the __PROPS__ JSON out of it. */
    suspend fun fetchArchive(url: String): ArchiveData? {
        return try {
            val html = Tw4aWebView.getWithCf(url, headers, timeoutSec = 30L)
                ?: return null
            val root = parseProps(html) ?: return null

            // episode/movie: { data: { data: { metadata, encodes, streams } } }
            val inner = root.optJSONObject("data")?.optJSONObject("data") ?: return null
            val encodes = mutableListOf<JSONObject>()
            inner.optJSONArray("encodes")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { encodes.add(it) }
            }
            val streams = mutableListOf<JSONObject>()
            inner.optJSONArray("streams")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { streams.add(it) }
            }
            ArchiveData(
                metadata = inner.optJSONObject("metadata"),
                encodes = encodes,
                streams = streams,
            )
        } catch (e: Exception) {
            Log.d("TW4A", "fetchArchive failed: ${e.message}")
            null
        }
    }

    /**
     * Resolve a /redirect/{hash} path to the shortener destination URL.
     *
     * The chooser page may either respond 200 with props, or 302 straight to
     * the shortener (always the case once the session is on the manual
     * system), so both shapes are handled. Retries a couple of times because
     * the server occasionally rate-limits with an error body.
     */
    suspend fun resolveRedirectDestination(redirectPath: String): String? {
        val url = if (redirectPath.startsWith("http")) redirectPath else BASE + redirectPath
        repeat(3) { attempt ->
            try {
                // raw call first so the 302 Location header stays readable
                var response = app.get(
                    url, headers = Tw4aWebView.cfHeaders(url, headers),
                    allowRedirects = false, timeout = 30L
                )
                var html = response.text

                // Cloudflare challenge -> solve once in the healthy WebView,
                // then refetch with the clearance cookies
                if (Tw4aWebView.isChallengeHtml(html)) {
                    if (Tw4aWebView.solveCloudflare(url) == null) {
                        kotlinx.coroutines.delay(500L * (attempt + 1))
                        return@repeat
                    }
                    response = app.get(
                        url, headers = Tw4aWebView.cfHeaders(url, headers),
                        allowRedirects = false, timeout = 30L
                    )
                    html = response.text
                }

                // direct 302 to the shortener
                val location = response.headers["location"]
                if (location != null && location.startsWith("http")) return location

                val props = parseProps(html)
                val dest = props?.optString("destination").orEmpty()
                if (dest.startsWith("http")) return dest

                // error body (e.g. "Invalid data") -> brief backoff and retry
                if (html.length < 200) {
                    kotlinx.coroutines.delay(400L * (attempt + 1))
                }
            } catch (e: Exception) {
                Log.d("TW4A", "redirect attempt $attempt failed: ${e.message}")
                kotlinx.coroutines.delay(400L * (attempt + 1))
            }
        }
        return null
    }

    /**
     * Collect every shortener destination a redirect hands out. The manual
     * system rotates through gplinks/exe/cuty in a fixed cycle, so a few
     * fetches hold all of them. gplinks comes first: its only wall is the
     * plain cloudflare interstitial, while exe.io and cuty.io sit behind an
     * invisible turnstile that WebView walks do not survive.
     */
    suspend fun resolveDestinations(redirectPath: String): List<String> {
        ensureManualSystem()

        val found = LinkedHashSet<String>()
        repeat(3) {
            val dest = resolveRedirectDestination(redirectPath) ?: return@repeat
            if (dest.startsWith("http")) found.add(dest)
            kotlinx.coroutines.delay(250)
        }
        return found.sortedByDescending { it.contains("gplinks") }
    }

    /**
     * The default 24hour system only ever hands out exe.io links. Switching
     * the session to manual makes the redirect rotate through all three
     * shorteners, which is what makes the gplinks path reachable at all.
     * Idempotent, and harmless when the cookie has not been minted yet -
     * the next redirect fetch sets it and the switch sticks from then on.
     */
    @Volatile
    private var manualSystemSet = false

    private suspend fun ensureManualSystem() {
        if (manualSystemSet) return
        try {
            app.post(
                "$BASE/api/user/preference/system?id=manual",
                headers = Tw4aWebView.cfHeaders(BASE, headers),
                timeout = 15L
            )
            manualSystemSet = true
        } catch (e: Exception) {
            Log.d("TW4A", "system preference save failed: ${e.message}")
        }
    }

    private fun parseProps(html: String): JSONObject? {
        val raw = PROPS_REGEX.find(html)?.groupValues?.get(1) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }
}
