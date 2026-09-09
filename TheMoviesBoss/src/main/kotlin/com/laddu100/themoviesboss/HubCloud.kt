package com.laddu100.themoviesboss

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

// hubcloud hosts the per-episode files behind tmbcloud; the video page leads to a drive
// page whose 10Gbps link redirects twice before landing on the direct google file url
object HubCloud {
    private const val TAG = "TheMoviesBoss"

    private val UA =
        "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    private val EPISODE_REGEX = Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val QUALITY_REGEX = Regex("""(\d{3,4})[pP]""")

    fun episodeFromFileName(text: String): Pair<Int, Int>? {
        val m = EPISODE_REGEX.find(text) ?: return null
        val season = m.groupValues[1].toIntOrNull() ?: return null
        val episode = m.groupValues[2].toIntOrNull() ?: return null
        return season to episode
    }

    private fun qualityFromFileName(text: String): Int =
        QUALITY_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value

    // returns false when the chain could not be completed so the caller can keep counting failures
    suspend fun resolve(
        videoPageUrl: String,
        fileName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            val videoPage = app.get(videoPageUrl, headers = headers)
            val driveUrl = videoPage.document
                .selectFirst("a[href*=\"hubcloud.php\"]")?.attr("href")
                ?: videoPage.document.selectFirst("a[href*=\"/drive?\"]")?.attr("href")
                ?: return false

            val drivePage = app.get(driveUrl, headers = headers + ("Referer" to videoPageUrl))
            val driveDoc = drivePage.document

            // prefer the 10Gbps server, it redirects down to the direct file url
            val firstHop = driveDoc.select("a[href]")
                .firstOrNull { it.attr("href").contains("gpdl", true) }?.attr("href")
                ?: driveDoc.select("a[href]")
                    .firstOrNull {
                        it.text().contains("Gbps", true) &&
                            !it.attr("href").contains("gofile") &&
                            it.attr("href").startsWith("http")
                    }?.attr("href")
                ?: return false

            val secondHop = followRedirect(firstHop, headers, driveUrl) ?: return false
            val directUrl = unwrapDirectUrl(secondHop, headers, firstHop) ?: return false

            val quality = qualityFromFileName(fileName)
            val label = if (quality == Qualities.Unknown.value) "HubCloud" else "HubCloud ${quality}p"
            callback.invoke(
                newExtractorLink(
                    source = "TheMoviesBoss",
                    name = label,
                    url = directUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.headers = mapOf("User-Agent" to UA)
                }
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "hubcloud resolve: ${e.message}")
            false
        }
    }

    private suspend fun followRedirect(url: String, headers: Map<String, String>, referer: String): String? {
        return try {
            val res = app.get(url, headers = headers + ("Referer" to referer), allowRedirects = false)
            val location = res.headers["location"]?.takeIf { it.isNotBlank() } ?: return null
            fixRelative(location, url)
        } catch (e: Exception) {
            Log.d(TAG, "hubcloud redirect: ${e.message}")
            null
        }
    }

    // the last hop is a dl.php wrapper carrying the real url in its link parameter,
    // some chains hand back the direct url straight away instead
    private suspend fun unwrapDirectUrl(url: String, headers: Map<String, String>, referer: String): String? {
        val looksWrapped = url.contains("dl.php?link=") || url.contains("dl.php&amp;link=")
        if (!looksWrapped) return url
        return try {
            val res = app.get(url, headers = headers + ("Referer" to referer), allowRedirects = false)
            val location = res.headers["location"]?.takeIf { it.isNotBlank() }
            val target = when {
                location != null -> location
                url.contains("link=") -> url.substringAfter("link=").substringBefore("&")
                else -> null
            } ?: return null
            val decoded = runCatching { URLDecoder.decode(target, "UTF-8") }.getOrDefault(target)
            decoded.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            Log.d(TAG, "hubcloud unwrap: ${e.message}")
            null
        }
    }

    private fun fixRelative(location: String, baseUrl: String): String {
        if (location.startsWith("http")) return location
        val proto = baseUrl.substringBefore("://")
        val host = baseUrl.removePrefix("$proto://").substringBefore('/')
        return "$proto://$host${if (location.startsWith("/")) location else "/$location"}"
    }
}
