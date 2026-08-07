package com.bollyflix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

fun getBaseUrl(url: String): String {
    return try {
        val u = URI(url)
        "${u.scheme}://${u.host}"
    } catch (e: Exception) {
        url
    }
}

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    val low = str.lowercase()
    return when {
        "8k" in low -> 4320
        "4k" in low -> 2160
        "2k" in low -> 1440
        else -> Qualities.Unknown.value
    }
}

// Re-label an existing ExtractorLink with a suffix and quality override.
// newExtractorLink is suspend so it cannot be called inside the non-suspend
// callback that loadExtractor expects; callers must collect links first and
// call this afterwards from a coroutine context.
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

// GDFlix file page extractor. Each page exposes multiple download buttons
// (Instant DL, CLOUD DOWNLOAD [R2], DRIVEBOT, FAST CLOUD, GoFile/Multiup).
// We surface every button as a separate ExtractorLink so the user sees all
// sources, and also fall back to loadExtractor for hosts CloudStream knows.
open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val baseUrl = getBaseUrl(url)
        val doc = try {
            app.get(url, timeout = 30_000L).document
        } catch (e: Exception) {
            Log.d("BollyFlix", "GDFlix page fetch failed: ${e.message}")
            return
        }
        val fileName = doc.select("ul > li.list-group-item:contains(Name)")
            .text().substringAfter("Name : ", "").trim()
        val quality = getIndexQuality(fileName)
        val audioTag = if (fileName.contains("{") && fileName.contains("}")) {
            fileName.substringAfter("{", "").substringBefore("}", "")
        } else ""
        val audioLabel = if (audioTag.isNotBlank()) " {$audioTag}" else ""

        // Every download button on the page lives inside div.text-center as an <a>.
        for (anchor in doc.select("div.text-center a")) {
            val text = anchor.text().trim()
            var href = anchor.attr("href").trim()
            if (href.isBlank()) continue
            // Relative hrefs (e.g. /cflare/...) need the base URL prepended.
            if (href.startsWith("/")) href = baseUrl + href

            when {
                text.contains("CLOUD DOWNLOAD", ignoreCase = true) ||
                    href.contains("fastcdn-dl.pages.dev") -> {
                    // fastcdn-dl wraps the R2 URL as a url= query param; the R2 URL
                    // is the real direct link and is also present in the page source.
                    val r2 = Regex("https://pub-[a-f0-9]+\\.r2\\.dev/[a-f0-9]+")
                        .find(href)?.value
                        ?: Regex("https://pub-[a-f0-9]+\\.r2\\.dev/[a-f0-9]+")
                            .find(doc.html())?.value
                    if (r2 != null) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name [Cloud R2]$audioLabel",
                                url = r2,
                                type = ExtractorLinkType.VIDEO,
                            ) { this.quality = quality }
                        )
                    }
                }
                text.contains("Instant DL", ignoreCase = true) ||
                    href.contains("busycdn") -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name [Instant]$audioLabel",
                            url = href,
                            type = ExtractorLinkType.VIDEO,
                        ) { this.quality = quality }
                    )
                }
                text.contains("DRIVEBOT", ignoreCase = true) || href.contains("drivebot.sbs") -> {
                    resolveDrivebot(href, name, quality, audioLabel, callback)
                }
                text.contains("FAST CLOUD", ignoreCase = true) || href.contains("/cflare/") -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name [Fast Cloud]$audioLabel",
                            url = href,
                            type = ExtractorLinkType.VIDEO,
                        ) { this.quality = quality }
                    )
                }
                text.contains("GoFile", ignoreCase = true) || href.contains("goflix.sbs") -> {
                    resolveGoflix(href, name, quality, audioLabel, subtitleCallback, callback)
                }
                text.contains("Telegram", ignoreCase = true) || href.contains("filesgram") -> {
                    // Telegram bot links cannot be played in-app.
                    continue
                }
                else -> {
                    // Unknown button: collect via built-in extractor then re-label.
                    val collected = mutableListOf<ExtractorLink>()
                    try {
                        loadExtractor(href, url, subtitleCallback) { el -> collected.add(el) }
                    } catch (e: Exception) {
                        Log.d("BollyFlix", "GDFlix unknown button $href: ${e.message}")
                    }
                    for (el in collected) relabelLink(el, audioLabel, quality, callback)
                }
            }
        }
    }

    private suspend fun resolveDrivebot(
        url: String,
        sourceName: String,
        quality: Int,
        audioLabel: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val doc = app.get(url, timeout = 30_000L).document
            val direct = doc.selectFirst(
                "a.btn[href*=download], a[href*=.mkv], a[href*=.mp4], meta[http-equiv=refresh][content*=url]"
            )
            direct?.let {
                val href = it.attr("href").ifBlank {
                    it.attr("content").substringAfter("url=", "").trim()
                }
                if (href.isNotBlank()) {
                    callback(
                        newExtractorLink(
                            source = sourceName,
                            name = "$sourceName [Drivebot]$audioLabel",
                            url = href,
                            type = ExtractorLinkType.VIDEO,
                        ) { this.quality = quality }
                    )
                }
            }
        } catch (e: Exception) {
            Log.d("BollyFlix", "Drivebot resolve failed: ${e.message}")
        }
    }

    private suspend fun resolveGoflix(
        url: String,
        sourceName: String,
        quality: Int,
        audioLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val doc = app.get(url, timeout = 30_000L).document
            for (a in doc.select("a[href]")) {
                val href = a.attr("href")
                if (href.isBlank()) continue
                val host = href.lowercase()
                if (!listOf(
                        "gofile", "1fichier", "megaup", "mixdrop", "mediafire",
                        "usersdrive", "clicknupload", "ddownload", "filelions",
                        "turbobit", "rapidgator", "nitroflare", "katfile",
                        "multiup", "uploadhaven", "keep2share"
                    ).any { it in host }
                ) continue
                val collected = mutableListOf<ExtractorLink>()
                try {
                    loadExtractor(href, url, subtitleCallback) { el -> collected.add(el) }
                } catch (e: Exception) {
                    Log.d("BollyFlix", "goflix host $href: ${e.message}")
                }
                for (el in collected) relabelLink(el, audioLabel, quality, callback)
            }
        } catch (e: Exception) {
            Log.d("BollyFlix", "Goflix resolve failed: ${e.message}")
        }
    }
}

class GdFlix1 : GDFlix() { override val mainUrl = "https://new1.gdflix.*" }
class GdFlix2 : GDFlix() { override val mainUrl = "https://new2.gdflix.*" }
class GDFlixNet : GDFlix() { override val mainUrl = "https://gdflix.net*" }
class GDFlixApp : GDFlix() { override val mainUrl = "https://gdflix.app*" }
class GDLink : GDFlix() { override val mainUrl = "https://gdlink.*" }

// fastdlserver links 302-redirect to a gdflix.* file page; hand the redirect
// target to loadExtractor so the GDFlix extractor above handles it.
class FastDlServer : ExtractorApi() {
    override val name = "fastdlserver"
    override val mainUrl = "https://fastdlserver.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val resp = app.get(url, timeout = 30_000L, allowRedirects = false)
            val location = resp.headers["location"]
            if (location != null) {
                loadExtractor(location, "", subtitleCallback, callback)
            } else {
                loadExtractor(url, "", subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.d("BollyFlix", "fastdlserver resolve failed: ${e.message}")
        }
    }
}
