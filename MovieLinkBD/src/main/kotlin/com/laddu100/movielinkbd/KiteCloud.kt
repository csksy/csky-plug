package com.laddu100.movielinkbd

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Qualities
import okhttp3.HttpUrl.Companion.toHttpUrl

object KiteCloud {

    private const val MAIN_URL = "https://kitecloud.me"
    private const val TAG = "MovieLinkBD"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    data class KiteFile(
        val url: String,
        val fileName: String,
        val size: String,
        val quality: Int
    )

    fun extractCode(shareUrl: String): String? {
        val path = shareUrl.toHttpUrl().pathSegments.lastOrNull() ?: return null
        return path.takeIf { it.isNotBlank() }
    }

    fun qualityFrom(text: String): Int = when {
        Regex("2160p|4k", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P2160.value
        Regex("1080p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P1080.value
        Regex("720p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P720.value
        Regex("480p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P480.value
        Regex("360p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun fileFromRedirect(location: String): String? {
        val http = location.toHttpUrl()
        for (param in listOf("file", "url", "link")) {
            http.queryParameter(param)?.takeIf { it.startsWith("http") }?.let { return it }
        }
        return location.takeIf { it.startsWith("http") && !it.contains("kitecloud.me/") }
    }

    // every drive page button posts to its own url with a distinct key,
    // new servers show up there before we hardcode them
    private suspend fun driveButtonNames(driveUrl: String): List<String> {
        return try {
            val doc = app.get(driveUrl, headers = headers, timeout = 15_000L).document
            doc.select("form button[type=submit]").mapNotNull { btn ->
                btn.attr("name").takeIf { it.startsWith("get_") }
            }.distinct()
        } catch (e: Exception) {
            Log.d(TAG, "drive page failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun postForDirect(driveUrl: String, button: String): String? {
        return try {
            val resp = app.post(
                driveUrl,
                data = mapOf(button to ""),
                headers = headers,
                allowRedirects = false,
                timeout = 30_000L
            )
            if (resp.code in 300..399) {
                resp.headers["location"]?.let(::fileFromRedirect)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "drive post failed: ${e.message}")
            null
        }
    }

    suspend fun resolve(shareUrl: String): KiteFile? {
        val code = extractCode(shareUrl) ?: return null
        val page = try {
            app.get("$MAIN_URL/$code", headers = headers, timeout = 15_000L)
        } catch (e: Exception) {
            Log.d(TAG, "file page failed: ${e.message}")
            return null
        }

        val doc = page.document
        val tokenHref = doc.selectFirst("#generateBtn")?.attr("href") ?: return null
        val driveUrl = if (tokenHref.startsWith("http")) tokenHref else "$MAIN_URL$tokenHref"
        val fileName = doc.selectFirst("title")?.text()?.trim() ?: ""
        val size = doc.selectFirst("i#size")?.text()?.trim() ?: ""

        val direct = postForDirect(driveUrl, "get_10gbps_link")
            ?: driveButtonNames(driveUrl).firstNotNullOfOrNull { postForDirect(driveUrl, it) }
            ?: return null

        return KiteFile(
            url = direct,
            fileName = fileName,
            size = size,
            quality = qualityFrom("$fileName $shareUrl")
        )
    }
}
