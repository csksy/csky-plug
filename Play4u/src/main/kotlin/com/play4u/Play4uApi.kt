package com.play4u

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder

object Play4uApi {

    const val MAIN_URL = "https://play4u.org"
    private const val TAG = "Play4u"

    // every sealed url in a page decodes with the key the page itself ships
    // right next to the payload, the prefix marks a sealed value
    private const val SEAL_PREFIX = "pa!~"
    private const val DEFAULT_SEAL_KEY = "pa4u-stream-seal-v1"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36"

    val browserHeaders: Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$MAIN_URL/"
    )

    // playback and manifest requests on the stream hosts are answered with
    // a plain CORS GET as long as they carry the site origin, without them
    // the hosts serve an html decoy page instead of the playlist
    val streamHeaders: Map<String, String> = mapOf(
        "Referer" to "$MAIN_URL/",
        "Origin" to MAIN_URL
    )

    // the site helper swaps the url safe alphabet to the standard one and
    // runs atob on the result, so the decode has to run against the standard
    // table too: android's URL_SAFE table treats + and / as whitespace and
    // silently drops them, which bit shifts every byte after the first drop
    // and turns most links into mojibake that the cdn answers with 400/403
    fun unseal(value: String?, key: String = DEFAULT_SEAL_KEY): String {
        if (value.isNullOrBlank()) return ""
        if (!value.startsWith(SEAL_PREFIX)) return value
        var b64 = value.substring(SEAL_PREFIX.length)
            .replace("-", "+")
            .replace("_", "/")
        when (b64.length % 4) {
            1 -> return ""
            2 -> b64 += "=="
            3 -> b64 += "="
        }
        return try {
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            val kb = key.toByteArray(Charsets.UTF_8)
            val out = StringBuilder(raw.size)
            for (i in raw.indices) {
                out.append(((raw[i].toInt() and 0xFF) xor (kb[i % kb.size].toInt() and 0xFF)).toChar())
            }
            val text = out.toString()
            // a decoded url is plain ascii, anything else means the seal
            // format moved and the bytes came out wrong, handing that to the
            // player only buys a confusing source error
            if (text.isEmpty() || text.any { it < ' ' || it > '~' }) "" else text
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "unseal: ${e.message}")
            ""
        }
    }

    // the seal key rides in a window var on every page, read it from there
    // so a key rotation on the site needs no plugin update
    private val SEAL_KEY_PATTERN = Regex("""window\.__PA_SEAL_KEY__\s*=\s*"([^"]*)"""")

    fun sealKeyOf(html: String): String =
        SEAL_KEY_PATTERN.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SEAL_KEY

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    suspend fun page(path: String): String? {
        val target = if (path.startsWith("http")) path else "$MAIN_URL$path"
        return try {
            val res = app.get(target, headers = browserHeaders, timeout = 25L)
            if (res.isSuccessful) res.text else null
        } catch (e: Exception) {
            Log.d(TAG, "page $target: ${e.message}")
            null
        }
    }

    // the same call the site header search box makes, it accepts the same
    // filters as the html browse so every mainpage row rides one endpoint
    suspend fun search(
        query: String?,
        page: Int,
        perPage: Int = 24,
        type: String? = null,
        genre: String? = null,
        industry: String? = null,
        lang: String? = null
    ): SearchPage? {
        val url = buildString {
            append("$MAIN_URL/api/search.php?q=")
            append(urlEncode(query ?: ""))
            append("&page=")
            append(page.coerceAtLeast(1))
            append("&per_page=")
            append(perPage)
            type?.let { append("&type=").append(it) }
            genre?.let { append("&genre=").append(it) }
            industry?.let { append("&industry=").append(it) }
            lang?.let { append("&lang=").append(it) }
        }
        return try {
            val res = app.get(url, headers = browserHeaders, timeout = 20L)
            if (!res.isSuccessful) return null
            parseJson<SearchPage>(res.text)
        } catch (e: Exception) {
            Log.d(TAG, "search: ${e.message}")
            null
        }
    }

    // pulls a window.__PA_*__ object literal out of a page, these are plain
    // json from the server so the slice between the outer braces parses as is
    fun extractVar(html: String, name: String): String? {
        val marker = "window.$name = "
        val at = html.indexOf(marker)
        if (at < 0) return null
        var start = html.indexOf('=', at) + 1
        while (start < html.length && html[start].isWhitespace()) start++
        if (start >= html.length || html[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            when (c) {
                '\\' -> escaped = true
                '"' -> inString = !inString
                '{', '[' -> if (!inString) depth++
                '}', ']' -> if (!inString) {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun playVar(html: String): PlayPayload? =
        extractVar(html, "__PA_PLAY__")?.let {
            try {
                parseJson<PlayPayload>(it)
            } catch (e: Exception) {
                Log.d(TAG, "play var: ${e.message}")
                null
            }
        }

    fun seriesVar(html: String): SeriesPayload? =
        extractVar(html, "__PA_SERIES__")?.let {
            try {
                parseJson<SeriesPayload>(it)
            } catch (e: Exception) {
                Log.d(TAG, "series var: ${e.message}")
                null
            }
        }
}
