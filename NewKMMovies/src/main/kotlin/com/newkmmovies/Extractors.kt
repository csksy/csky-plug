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
import java.net.URLEncoder

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
            val id = url.substringAfter("id=", "")
            if (id.isBlank()) return

            val apiUrl = "$mainUrl/api.php?id=$id"
            var retryCount = 0
            var directUrl: String? = null

            while (retryCount < 5 && directUrl == null) {
                try {
                    val resp = app.get(apiUrl, timeout = 15_000L).text
                    val mapper = ObjectMapper()
                    val node = mapper.readTree(resp)
                    val success = node.get("success")?.asBoolean() ?: false
                    if (success) {
                        directUrl = node.get("link")?.asText()
                            ?: node.get("direct_download_url")?.asText()
                            ?: node.get("download_url")?.asText()
                    } else {
                        val pending = node.get("pending")?.asBoolean() ?: false
                        if (pending) {
                            val pollAfter = node.get("poll_after_ms")?.asLong() ?: 3000L
                            Thread.sleep(minOf(maxOf(pollAfter, 3000), 10000))
                        }
                    }
                } catch (e: Exception) {
                    Thread.sleep(2000)
                }
                retryCount++
            }

            if (directUrl.isNullOrBlank()) {
                Log.d(TAG, "Skydrop: no link after $retryCount retries for $url")
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
            val resp = app.get(url, allowRedirects = true, timeout = 30_000L)
            val html = resp.text

            val links = Regex("""<a[^>]*href="(https?://[^"]*)"[^>]*>(.*?)</a>""").findAll(html).toList()
            for (match in links) {
                val href = match.groupValues[1]
                val linkText = match.groupValues[2]
                val textClean = Regex("<[^>]+>").replace(linkText, "").trim()

                when {
                    href.contains("skydrop.sbs") || href.contains("flexplayer") -> {
                        try { loadExtractor(href, url, subtitleCallback, callback) } catch (_: Exception) {}
                    }
                    href.contains("hubcloud") -> {
                        try { loadExtractor(href, url, subtitleCallback, callback) } catch (_: Exception) {}
                    }
                    href.contains("gdtot") -> {
                        try { loadExtractor(href, url, subtitleCallback, callback) } catch (_: Exception) {}
                    }
                    href.contains("kmphotos") && href.contains("download") -> {
                        try {
                            val dlResp = app.get(href, allowRedirects = true, timeout = 30_000L)
                            val finalUrl = dlResp.url
                            if (finalUrl != href && !finalUrl.contains("cloudflare")) {
                                callback(
                                    newExtractorLink(
                                        source = "KMPhotos",
                                        name = "KMPhotos $textClean",
                                        url = finalUrl,
                                        type = ExtractorLinkType.VIDEO,
                                    ) { this.quality = Qualities.Unknown.value }
                                )
                            }
                        } catch (_: Exception) {}
                    }
                    href.contains("skytech.works") -> {
                        val videoUrl = Regex("videoUrl=([^&\"']+)").find(href)?.groupValues?.get(1)
                        if (videoUrl != null) {
                            val decoded = java.net.URLDecoder.decode(videoUrl, "UTF-8")
                            try {
                                val dlResp = app.get(decoded, allowRedirects = true, timeout = 30_000L)
                                val finalUrl = dlResp.url
                                callback(
                                    newExtractorLink(
                                        source = "KMPhotos",
                                        name = "Watch Online",
                                        url = finalUrl,
                                        type = ExtractorLinkType.VIDEO,
                                    ) { this.quality = Qualities.Unknown.value }
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "MagicLinks error: ${e.message}")
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
            val html = app.get(url, timeout = 30_000L).text
            val epRows = Regex("""<div class="ep-row">.*?<span class="ep-name">([^<]*)</span>.*?<a[^>]*href="([^"]*)"[^>]*class="dl-btn""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()

            for (ep in epRows) {
                val epName = ep.groupValues[1].trim()
                val dlUrl = ep.groupValues[2].trim()
                if (dlUrl.contains("skydrop")) {
                    try { loadExtractor(dlUrl, url, subtitleCallback, callback) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "EpisodesML error: ${e.message}")
        }
    }
}
