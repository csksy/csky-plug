package com.cno

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONObject
import java.net.URI

internal object CnoApi {

    const val MAIN_URL = "https://cno.to"

    private const val IMAGE_BASE = "https://image.tmdb.org/t/p"

    fun image(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "$IMAGE_BASE/$size$path"
    }

    fun absolute(url: String): String =
        if (url.startsWith("http")) url else MAIN_URL + url

    private fun hostOf(url: String): String? = try {
        URI(absolute(url)).host
    } catch (_: Exception) {
        null
    }

    // the same shape of request the site's own pages send, it keeps the
    // plain path alive whenever the edge is feeling permissive
    private fun browserHeaders(json: Boolean): Map<String, String> = mapOf(
        "User-Agent" to CnoWebView.userAgent().orEmpty()
            .ifBlank { DEFAULT_UA },
        "Accept" to if (json) "application/json" else "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$MAIN_URL/",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    // a clearance is only honored next to the user agent it was minted for
    private fun cookieHeaders(): Map<String, String> {
        val jar = CnoWebView.cookies() ?: return emptyMap()
        val ua = CnoWebView.userAgent() ?: return emptyMap()
        if (jar.isBlank() || ua.isBlank()) return emptyMap()
        return mapOf(
            "Cookie" to jar,
            "User-Agent" to ua
        )
    }

    private val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36"

    // the edge answers a refused request with one of these, whatever the
    // transport says about itself above that is a real response
    private fun refused(res: NiceResponse, body: String): Boolean {
        if (res.headers["cf-mitigated"] == "challenge") return true
        if (res.isSuccessful && res.code == 200) {
            val type = res.headers["content-type"] ?: ""
            if (type.contains("text/html")) return true
            return body.contains("you have been blocked") ||
                body.contains("Attention Required") ||
                body.contains("challenge-platform")
        }
        if (res.code == 403 || res.code == 429 || res.code == 503) {
            val server = res.headers["server"] ?: ""
            if (server.contains("cloudflare", true)) return true
            return body.contains("you have been blocked") ||
                body.contains("Attention Required") ||
                body.contains("just a moment", true) ||
                body.contains("challenge-platform")
        }
        return !res.isSuccessful
    }

    /**
     * Everything the plugin reads off the site goes through here. The plain
     * call goes first because it is instant when the edge lets it through,
     * then the same call with the harvested clearance, and when the edge
     * still refuses the request is re-run from inside the site's own page
     * where the webview's browser stack carries it.
     */
    suspend fun text(url: String, json: Boolean = true): String? {
        val abs = absolute(url)
        if (hostOf(abs) != URI(MAIN_URL).host) {
            return try {
                val res = app.get(abs, headers = browserHeaders(json))
                if (res.isSuccessful) res.text else null
            } catch (_: Exception) {
                null
            }
        }

        val withCookies = cookieHeaders()
        if (withCookies.isNotEmpty()) {
            try {
                val res = app.get(abs, headers = browserHeaders(json) + withCookies, timeout = 15L)
                val body = res.text
                if (res.isSuccessful && !refused(res, body)) return body
            } catch (_: Exception) {
            }
        }

        try {
            val res = app.get(abs, headers = browserHeaders(json), timeout = 15L)
            val body = res.text
            if (res.isSuccessful && !refused(res, body)) return body
        } catch (_: Exception) {
        }

        return CnoWebView.fetch(abs)
    }

    suspend fun json(path: String): JSONObject? {
        val body = text(path, json = true) ?: return null
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
    }

    // headers for anything the player itself has to pull off cno.to, the
    // subtitle files are the only ones left, the stream playlists moved to
    // the site's open cdn hosts where nothing is checked
    fun streamHeaders(url: String): Map<String, String>? {
        if (hostOf(url) != URI(MAIN_URL).host) return null
        val base = cookieHeaders()
        if (base.isEmpty()) return null
        return base + mapOf(
            "Referer" to "$MAIN_URL/",
            "Accept" to "*/*"
        )
    }

    data class Variant(val url: String, val quality: Int)

    // the multi cdn server hands out absolute variant urls, both kinds
    // resolve through the same URI call
    fun parseVariants(master: String, masterUrl: String): List<Variant> {
        val lines = master.lines()
        val out = ArrayList<Variant>(8)
        for (i in 0 until lines.size - 1) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val target = lines[i + 1].trim()
            if (target.isEmpty() || target.startsWith("#")) continue
            val size = RESOLUTION.find(lines[i])
            val width = size?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val height = size?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val url = try {
                URI(absolute(masterUrl)).resolve(target).toString()
            } catch (_: Exception) {
                continue
            }
            val labeled = PATH_QUALITY.find(target)?.groupValues?.get(1)?.toIntOrNull()
            out.add(Variant(url, labeled ?: qualityOf(width, height)))
        }
        // a media playlist with no variants plays fine as a single link
        if (out.isEmpty() && master.startsWith("#EXTM3U")) {
            out.add(Variant(absolute(masterUrl), Qualities.Unknown.value))
        }
        return out
    }

    // scope masters are 3840 wide but barely 1600 tall, the width is what
    // makes those 4K, everything else buckets off the height
    private fun qualityOf(width: Int, height: Int): Int = when {
        width >= 3000 || height >= 1900 -> 2160
        height >= 1400 -> 1440
        height >= 1000 -> 1080
        height >= 700 -> 720
        height >= 500 -> 576
        height > 0 -> 480
        else -> Qualities.Unknown.value
    }

    private val RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val PATH_QUALITY = Regex("""/(\d{3,4})p/""")
}
