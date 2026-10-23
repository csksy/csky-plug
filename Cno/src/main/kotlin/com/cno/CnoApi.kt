package com.cno

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal object CnoApi {

    const val MAIN_URL = "https://cno.to"

    private const val IMAGE_BASE = "https://image.tmdb.org/t/p"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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

    // the same headers the site's own pages send on their api calls, matching
    // them keeps the request off the bot heuristics as long as possible
    private fun browserHeaders(json: Boolean): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to UA,
            "Accept" to if (json) "application/json" else "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$MAIN_URL/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )
        return h
    }

    // a killer is only built once a challenge is actually met, constructing
    // one eagerly would wipe the app webview cookie jar on every launch
    private val killers = ConcurrentHashMap<String, CloudflareKiller>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun killerFor(host: String): CloudflareKiller =
        killers.getOrPut(host) { CloudflareKiller() }

    private fun lockFor(host: String): Mutex =
        locks.getOrPut(host) { Mutex() }

    private fun holdsClearance(host: String): Boolean =
        killers[host]?.savedCookies?.get(host)?.containsKey("cf_clearance") == true

    private fun isChallenge(res: NiceResponse): Boolean {
        if (res.headers["cf-mitigated"] == "challenge") return true
        if (res.code != 403 && res.code != 429 && res.code != 503) return false
        if ((res.headers["server"] ?: "").contains("cloudflare", ignoreCase = true)) return true
        val body = try {
            res.text
        } catch (_: Exception) {
            ""
        }
        return body.contains("just a moment") ||
            body.contains("checking your browser") ||
            body.contains("challenge-platform")
    }

    // nicehttp takes seconds, not milliseconds
    suspend fun fetch(url: String, json: Boolean = true, timeout: Long = 20L): NiceResponse? {
        val abs = absolute(url)
        val host = hostOf(abs) ?: return null

        // once a clearance is held the plain attempt is a guaranteed wasted
        // round trip, go straight through the killer that owns the cookies
        if (holdsClearance(host)) {
            return try {
                app.get(abs, headers = browserHeaders(json), interceptor = killerFor(host), timeout = timeout)
            } catch (_: Exception) {
                null
            }
        }

        val plain = try {
            app.get(abs, headers = browserHeaders(json), timeout = timeout)
        } catch (_: Exception) {
            null
        }
        if (plain != null && plain.isSuccessful && !isChallenge(plain)) return plain

        // the webview solve is slow and must not run twice in parallel
        return lockFor(host).withLock {
            // another call may have solved it while this one waited
            try {
                val solved = app.get(
                    abs,
                    headers = browserHeaders(json),
                    interceptor = killerFor(host),
                    timeout = 60L
                )
                if (solved.isSuccessful && !isChallenge(solved)) solved else null
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun json(path: String): JSONObject? {
        val res = fetch(path, json = true) ?: return null
        if (!res.isSuccessful) return null
        return try {
            JSONObject(res.text)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun text(url: String): String? {
        val res = fetch(url, json = false) ?: return null
        if (!res.isSuccessful) return null
        return res.text
    }

    // headers the player itself must send for anything still hosted on
    // cno.to, the clearance cookie is only accepted alongside the webview
    // user agent it was issued for, urls on other hosts need nothing
    fun streamHeaders(url: String): Map<String, String>? {
        val abs = absolute(url)
        val host = hostOf(abs) ?: return null
        val killer = killers[host] ?: return null
        if (!killer.savedCookies[host].orEmpty().containsKey("cf_clearance")) return null
        // the killer builds the pair itself, cookies plus the matching agent
        val out = mutableMapOf<String, String>()
        for ((name, values) in killer.getCookieHeaders(abs).toMultimap()) {
            val value = values.firstOrNull() ?: continue
            if (name.equals("user-agent", ignoreCase = true) || name.equals("cookie", ignoreCase = true)) {
                out[name] = value
            }
        }
        if (out.isEmpty()) return null
        out["Referer"] = "$MAIN_URL/"
        out["Accept"] = "*/*"
        return out
    }

    data class Variant(val url: String, val quality: Int)

    // the multi cdn server hands out absolute variant urls, the in house ones
    // use paths relative to cno.to, both resolve through the same URI call
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
            // server 20 names the quality in the path, that is exact, the
            // resolution line is the fallback for server 21's odd shapes
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
