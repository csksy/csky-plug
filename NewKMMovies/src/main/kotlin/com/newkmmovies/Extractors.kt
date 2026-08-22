package com.newkmmovies

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay

private const val TAG = "NKM_EXT"

class SkydropExtractor : ExtractorApi() {
    override val name = "Skydrop"
    override val mainUrl = "https://w1.skydrop.sbs"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = url.substringAfter("id=", "").substringBefore("&")
            if (id.isBlank()) return

            val pageUrl = "$mainUrl/download.php?id=$id"
            val apiUrl = "$mainUrl/api.php?id=$id"
            val apiHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Referer" to pageUrl,
                "Accept" to "application/json, text/plain, */*",
                "X-Requested-With" to "XMLHttpRequest",
            )

            var directUrl: String? = null
            var attempt = 0
            while (attempt < 10 && directUrl == null) {
                attempt++
                try {
                    val respText = app.get(apiUrl, headers = apiHeaders, timeout = 15_000L).text
                    val mapper = ObjectMapper()
                    val node = mapper.readTree(respText)
                    val success = node.get("success")?.asBoolean() ?: false

                    if (success) {
                        val link = node.get("link")?.asText()
                            ?: node.get("direct_download_url")?.asText()
                            ?: node.get("download_url")?.asText()
                        if (!link.isNullOrBlank()) {
                            directUrl = link
                            break
                        }
                        val pending = node.get("pending")?.asBoolean() ?: false
                        if (pending) {
                            val pollAfter = node.get("poll_after_ms")?.asLong() ?: 3000L
                            delay(minOf(maxOf(pollAfter, 3000), 10000))
                            continue
                        }
                    }

                    val busy = node.get("busy")?.asBoolean() ?: false
                    if (busy) {
                        delay(2000)
                        continue
                    }

                    val errorMsg = node.get("error")?.asText() ?: ""
                    if (errorMsg.contains("Invalid", true) ||
                        errorMsg.contains("expired", true) ||
                        errorMsg.contains("corrupted", true) ||
                        errorMsg.contains("not found", true)) {
                        Log.d(TAG, "Skydrop: $errorMsg for $url")
                        break
                    }
                    delay(2000)
                } catch (e: Exception) {
                    delay(2000)
                }
            }

            if (directUrl.isNullOrBlank()) {
                Log.d(TAG, "Skydrop: no link after $attempt attempts for $url")
                return
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = directUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            Log.d(TAG, "Skydrop error: ${e.message}")
        }
    }
}

class MagicLinksExtractor : ExtractorApi() {
    override val name = "MagicLinks"
    override val mainUrl = "https://w3.magiclinks.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resp = app.get(url, allowRedirects = true, timeout = 30_000L, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://kmmovies.pics/",
            ))
            val doc = resp.document
            val mirrorLinks = doc.select("a.download-button, .download-buttons a[href]")
            for (mirror in mirrorLinks) {
                val href = mirror.attr("href").trim()
                if (href.isBlank() || !href.startsWith("http")) continue
                try {
                    loadExtractor(href, url, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.d(TAG, "MagicLinks error: ${e.message}")
        }
    }
}

class EpisodesMagicLinksExtractor : ExtractorApi() {
    override val name = "EpisodesML"
    override val mainUrl = "https://episodes.magiclinks.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resp = app.get(url, timeout = 30_000L, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://kmmovies.pics/",
            ))
            val doc = resp.document
            val epRows = doc.select(".ep-row, .ep-list .ep-row")
            for (row in epRows) {
                val epName = row.selectFirst(".ep-name")?.text()?.trim() ?: continue
                val dlBtn = row.selectFirst("a.dl-btn") ?: row.selectFirst("a[href*='skydrop']")
                val href = dlBtn?.attr("href")?.trim() ?: continue
                if (href.isNotBlank() && href.startsWith("http")) {
                    try {
                        loadExtractor(href, url, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "EpisodesML error: ${e.message}")
        }
    }
}

class GDTOTExtractor : ExtractorApi() {
    override val name = "GDTOT"
    override val mainUrl = "https://gdtot.dad"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resp = app.get(url, allowRedirects = true, timeout = 30_000L)
            val html = resp.text

            val gdLink = Regex("""https?://(?:drive\.google\.com|docs\.google\.com)[^\s"'<>]+""").find(html)?.value
            if (gdLink != null) {
                try { loadExtractor(gdLink, url, subtitleCallback, callback) } catch (_: Exception) {}
                return
            }

            val downloadBtn = Regex("""<a[^>]*href="([^"]*download[^"]*)"""", RegexOption.IGNORE_CASE).find(html)
            if (downloadBtn != null) {
                val dlHref = downloadBtn.groupValues[1]
                val fullUrl = if (dlHref.startsWith("http")) dlHref else "${resp.url.substringBeforeLast("/")}/$dlHref"
                try {
                    val dlResp = app.get(fullUrl, allowRedirects = true, timeout = 30_000L)
                    val dlHtml = dlResp.text
                    val gdLink2 = Regex("""https?://(?:drive\.google\.com|docs\.google\.com)[^\s"'<>]+""").find(dlHtml)?.value
                    if (gdLink2 != null) {
                        try { loadExtractor(gdLink2, url, subtitleCallback, callback) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.d(TAG, "GDTOT error: ${e.message}")
        }
    }
}
