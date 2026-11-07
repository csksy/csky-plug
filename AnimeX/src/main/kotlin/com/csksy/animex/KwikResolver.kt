package com.csksy.animex

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.jsoup.Jsoup

// the site's download sheet resolves its kiwi rows to kwik file pages; the
// page hides the file inside a packed script and only answers the token POST
// with a redirect after a few tries
object KwikResolver {

    private const val TAG = "AnimeX"

    private val sourceRegex = Regex("""source\s*=\s*["']([^"']+)["']""")
    private val formActionRegex = Regex("""action="([^"]+)"""")
    private val formTokenRegex = Regex("""value="([^"]+)"""")

    // returns the playable file and the kwik page it came from, the page is
    // the referer the cdn wants
    suspend fun resolve(kwikUrl: String, referer: String): Pair<String, String>? {
        val page = try {
            app.get(
                kwikUrl,
                headers = mapOf(
                    "User-Agent" to AnimeXApi.USER_AGENT,
                    "Referer" to referer
                ),
                timeout = 20_000L
            )
        } catch (e: Exception) {
            Log.d(TAG, "kwik page failed: ${e.message}")
            return null
        }
        val html = page.text

        val packed = try {
            Jsoup.parse(html).selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
        } catch (_: Exception) {
            null
        }
        val unpacked = packed?.let {
            try {
                getAndUnpack(it)
            } catch (_: Exception) {
                null
            }
        }

        if (unpacked != null) {
            sourceRegex.find(unpacked)?.groupValues?.get(1)?.let { src ->
                if (src.startsWith("http")) return src to page.url
            }
            val action = formActionRegex.find(unpacked)?.groupValues?.get(1)
            val token = formTokenRegex.find(unpacked)?.groupValues?.get(1)
            if (action != null && token != null) {
                return postForFile(action, token, page.url)
            }
        }

        sourceRegex.find(html)?.groupValues?.get(1)?.let { src ->
            if (src.startsWith("http") && (src.contains(".m3u8") || src.contains(".mp4"))) {
                return src to page.url
            }
        }
        return null
    }

    // kwik answers the token POST with a 302 to the file only after a few tries
    private suspend fun postForFile(action: String, token: String, pageUrl: String): Pair<String, String>? {
        repeat(10) {
            try {
                val res = app.post(
                    action,
                    headers = mapOf(
                        "User-Agent" to AnimeXApi.USER_AGENT,
                        "Referer" to pageUrl
                    ),
                    data = mapOf("_token" to token),
                    allowRedirects = false
                )
                if (res.code == 302) {
                    res.headers["location"]?.takeIf { it.startsWith("http") }?.let { return it to pageUrl }
                }
            } catch (e: Exception) {
                Log.d(TAG, "kwik post failed: ${e.message}")
            }
        }
        return null
    }
}
