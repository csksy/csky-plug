package com.cinemalux

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URI

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    val low = str.lowercase()
    return when {
        "8k" in low -> 4320
        "4k" in low -> 2160
        "2160" in low -> 2160
        "1080" in low -> 1080
        "720" in low -> 720
        "480" in low -> 480
        else -> Qualities.Unknown.value
    }
}

// Decode the tpi.li shortlink token to get the linkstore.zip destination URL.
// The tpi.li page has a hidden form with a token field like:
//   <hash><alias><timestamp><base64-encoded-url>
// The base64 part starts with "aHR0c" ("http") and decodes to the real URL.
suspend fun resolveTpiLi(tpiUrl: String): String? {
    return try {
        val html = app.get(tpiUrl, timeout = 30_000L).text
        val tokenMatch = Regex("""name="token"\s+value="([^"]+)"""").find(html)
            ?: return null
        val token = tokenMatch.groupValues[1]
        val b64Start = token.indexOf("aHR0c")
        if (b64Start < 0) return null
        val b64Part = token.substring(b64Start)
        val decoded = String(Base64.decode(b64Part, Base64.DEFAULT), Charsets.UTF_8)
        decoded.trim()
    } catch (e: Exception) {
        Log.d("CinemaLux", "tpi.li resolve failed for $tpiUrl: ${e.message}")
        null
    }
}

// Fetch a linkstore.zip page and extract all download links from ep-simple-button
// anchors. For movies this yields direct file-host URLs (hubcloud, gdflix).
// For series this yields per-episode drive.linkstore.zip links with labels.
data class LinkstoreEntry(
    val url: String,
    val label: String,
)

suspend fun fetchLinkstoreLinks(linkstoreUrl: String): List<LinkstoreEntry> {
    return try {
        val doc = app.get(linkstoreUrl, timeout = 30_000L).document
        val entries = mutableListOf<LinkstoreEntry>()
        for (anchor in doc.select("a.ep-simple-button")) {
            val href = anchor.attr("href").trim()
            if (href.isBlank()) continue
            val label = anchor.select("span").text().ifBlank { anchor.text() }.trim()
            entries.add(LinkstoreEntry(href, label))
        }
        entries
    } catch (e: Exception) {
        Log.d("CinemaLux", "linkstore fetch failed for $linkstoreUrl: ${e.message}")
        emptyList()
    }
}

// Re-label an existing ExtractorLink with a suffix and quality override.
suspend fun relabelLink(
    el: ExtractorLink,
    suffix: String,
    qualityOverride: Int,
    callback: (ExtractorLink) -> Unit,
) {
    callback(
        newExtractorLink(
            source = el.source,
            name = "${el.name}$suffix".trim(),
            url = el.url,
            type = if (el.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
        ) {
            this.referer = el.referer
            this.headers = el.headers
            this.quality = if (el.quality <= 0) qualityOverride else el.quality
        }
    )
}

// LuxeDrive page extractor. drive.linkstore.zip/file/<hash> 301-redirects to
// new7.luxedrive.dad/file/<hash> which serves an HTML page with multiple
// download buttons: GDFlix, direct R2 (Cloudflare worker), Pixeldrain mirror.
// We surface each button as a separate ExtractorLink.
class LuxeDrive : ExtractorApi() {
    override val name = "LuxeDrive"
    override val mainUrl = "https://drive.linkstore.zip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val doc = try {
            app.get(url, timeout = 30_000L).document
        } catch (e: Exception) {
            Log.d("CinemaLux", "LuxeDrive page fetch failed: ${e.message}")
            return
        }
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val quality = getIndexQuality(title)

        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href").trim()
            if (href.isBlank()) continue
            val text = anchor.text().trim()

            when {
                href.contains("gdflix") -> {
                    // GDFlix file page — fetch it and extract the R2 direct URL.
                    resolveGDFlixPage(href, name, quality, callback)
                }
                href.contains("ultra-fast-r2-cdn.workers.dev") ||
                    href.contains("workers.dev") -> {
                    // Cloudflare worker serving the R2 file directly.
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name [R2 Direct]",
                            url = href,
                            type = ExtractorLinkType.VIDEO,
                        ) { this.quality = quality }
                    )
                }
                href.contains("pixeldrain") -> {
                    try {
                        loadExtractor(href, url, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.d("CinemaLux", "LuxeDrive pixeldrain $href: ${e.message}")
                    }
                }
                href.contains("hubcloud") -> {
                    try {
                        loadExtractor(href, url, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.d("CinemaLux", "LuxeDrive hubcloud $href: ${e.message}")
                    }
                }
                href.contains("gofile") || href.contains("1fichier") ||
                    href.contains("megaup") || href.contains("mixdrop") ||
                    href.contains("mediafire") || href.contains("multiup") -> {
                    try {
                        loadExtractor(href, url, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.d("CinemaLux", "LuxeDrive host $href: ${e.message}")
                    }
                }
            }
        }
    }

    // Fetch a GDFlix file page and extract the R2 Cloudflare direct MKV URL.
    // GDFlix pages contain pub-<hash>.r2.dev/<fileid> URLs in their HTML source
    // which serve the video directly with byte-range seeking.
    private suspend fun resolveGDFlixPage(
        url: String,
        sourceName: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val html = app.get(url, timeout = 30_000L).text
            val r2 = Regex("https://pub-[a-f0-9]+\\.r2\\.dev/[a-f0-9]+")
                .find(html)?.value
            if (r2 != null) {
                callback(
                    newExtractorLink(
                        source = sourceName,
                        name = "$sourceName [GDFlix Cloud]",
                        url = r2,
                        type = ExtractorLinkType.VIDEO,
                    ) { this.quality = quality }
                )
            }
        } catch (e: Exception) {
            Log.d("CinemaLux", "GDFlix page resolve failed: ${e.message}")
        }
    }
}
