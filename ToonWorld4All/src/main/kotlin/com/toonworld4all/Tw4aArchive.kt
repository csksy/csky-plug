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
 * Episode/movie page props shape (verified live):
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
 * Redirect chooser props shape (verified live):
 * { "userSystem":"24hour", "destination":"https://exe.io/xxxx", 
 *   "link":{ "domain":"https://hubcloud.ist/video/", "hidden":"<RANDOM JUNK>" }, "total":3 }
 *
 * NOTE: link.domain + link.hidden is deliberately randomized display data
 * (the Fh() helper in their bundle generates a random string of the same
 * length) - the ONLY real field is "destination".
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
            val html = app.get(url, headers = headers, timeout = 30L).text
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
     * the shortener (happens after the first hit, when the user-system cookie
     * is set), so both shapes are handled. Retries a couple of times because
     * the server occasionally rate-limits with an error body.
     */
    suspend fun resolveRedirectDestination(redirectPath: String): String? {
        val url = if (redirectPath.startsWith("http")) redirectPath else BASE + redirectPath
        repeat(3) { attempt ->
            try {
                val response = app.get(
                    url, headers = headers, allowRedirects = false, timeout = 30L
                )
                // direct 302 to the shortener
                val location = response.headers["location"]
                if (location != null && location.startsWith("http")) return location

                val html = response.text
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

    private fun parseProps(html: String): JSONObject? {
        val raw = PROPS_REGEX.find(html)?.groupValues?.get(1) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }
}
