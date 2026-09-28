package com.laddu100.eonmovies

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.HttpUrl.Companion.toHttpUrl

object EonSources {

    private const val TAG = "EonMovies"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private val toxTempRegex = Regex("""ACTIVE_TEMP_ID\s*=\s*"([^"]+)"""")
    private val toxPdIdRegex = Regex("""data-server="pixeldrain"\s+data-id="([^"]+)"""")
    private val toxSessionRegex = Regex("""session\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
    private val toxInstantRegex =
        Regex("""href="(https://video-downloads\.googleusercontent\.com/[^"]+)"""")
    private val dotflixPdRegex = Regex("""pixeldrainLink\\*":\\*"([^"\\]+)""")
    private val hubDownloadRegex = Regex("""id="download"\s+href="([^"]+)"""")
    private val hubPxlRegex = Regex("""var pxl\s*=\s*"([^"]+)"""")
    private val hubPdFallbackRegex = Regex("""href="(https://pixeldrain\.dev/u/[^"]+)"""")
    private val pdPageRegex = Regex("""https?://(pixeldrain\.(?:com|dev))/u/([A-Za-z0-9]+)""")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WorkerReply(val success: Boolean? = null, val url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SessionReply(val url: String? = null, val status: String? = null)

    private suspend fun get(url: String): String? {
        return try {
            app.get(url, headers = headers, timeout = 20_000L).text
        } catch (e: Exception) {
            Log.d(TAG, "fetch failed: ${e.message}")
            null
        }
    }

    fun pixelDrainApi(url: String): String? {
        pdPageRegex.find(url)?.let {
            return "https://${it.groupValues[1]}/api/file/${it.groupValues[2]}"
        }
        return url.takeIf { it.contains("/api/file/") }
    }

    // azonahub fronts files with cloudflare; once past it the page exposes a
    // pixeldrain backed worker id whose wrapper page carries the real
    // pixeldrain file link in its session payload
    private suspend fun resolveToxCloud(url: String): String? {
        val page = get(url) ?: return null
        val tempId = toxTempRegex.find(page)?.groupValues?.get(1) ?: return null

        val pdId = toxPdIdRegex.find(page)?.groupValues?.get(1)
        if (pdId != null) {
            val api = get("https://pd.toxhost.workers.dev/api/pixeldrain/$pdId")
            val workerUrl = api?.let {
                try {
                    val reply = parseJson<WorkerReply>(it)
                    reply.url?.takeIf { reply.success == true }
                } catch (e: Exception) {
                    null
                }
            }
            if (workerUrl != null) {
                val wrap = get(workerUrl)
                val session = wrap?.let { toxSessionRegex.find(it)?.groupValues?.get(1) }
                if (session != null) {
                    try {
                        val payload = parseJson<SessionReply>(session)
                        if (payload.url != null) {
                            pixelDrainApi(payload.url!!)?.let { return it }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "toxcloud session parse failed: ${e.message}")
                    }
                }
            }
        }

        // the instant worker mints a fresh drive link on demand, used when the
        // pixeldrain mirror is cold or the wrapper session is not ready yet
        val token = Base64.encodeToString(tempId.toByteArray(), Base64.NO_WRAP)
        val instant = get("https://instant.gomonster.dpdns.org/instant?token=$token")
        return instant?.let { toxInstantRegex.find(it)?.groupValues?.get(1) }
    }

    private suspend fun resolveDotflix(url: String): String? {
        val target = if (url.contains("//dtflix.")) url else url.replace("https://dotflix.", "https://dtflix.")
        val page = get(target) ?: return null
        return dotflixPdRegex.find(page)?.groupValues?.get(1)?.let { pixelDrainApi(it) }
    }

    // hubcloud swaps the button href with a script variable on load, the
    // printed href is a decoy that 404s so the script value is the real one
    private suspend fun resolveHubCloud(url: String): String? {
        val page = get(url) ?: return null
        val dlHref = hubDownloadRegex.find(page)?.groupValues?.get(1) ?: return null
        val dl = get(dlHref.replace("&amp;", "&")) ?: return null
        hubPxlRegex.find(dl)?.groupValues?.get(1)?.let { return pixelDrainApi(it) }
        return hubPdFallbackRegex.find(dl)?.groupValues?.get(1)?.let { pixelDrainApi(it) }
    }

    // gcloud mirrors need a per user google connect so they stay unresolvable,
    // everything else funnels into a pixeldrain api link
    suspend fun resolve(url: String): String? {
        return when {
            url.contains("azonahub.biz") -> resolveToxCloud(url)
            url.contains("dtflix.") || url.contains("dotflix.") -> resolveDotflix(url)
            url.contains("hubcloud.") -> resolveHubCloud(url)
            url.contains("pixeldrain.com") || url.contains("pixeldrain.dev") -> pixelDrainApi(url)
            else -> null
        }
    }

    fun sourceName(url: String): String {
        return when {
            url.contains("azonahub.biz") -> "ToxCloud"
            url.contains("dtflix.") || url.contains("dotflix.") -> "Dotflix"
            url.contains("hubcloud.") -> "HubCloud"
            url.contains("gdshare.top") || url.contains("gcloud.cyou") -> "GCloud"
            else -> try {
                url.toHttpUrl().host
            } catch (e: Exception) {
                url
            }
        }
    }
}
