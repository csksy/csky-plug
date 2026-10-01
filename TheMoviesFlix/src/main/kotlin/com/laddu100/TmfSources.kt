package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

object TmfSources {
    private const val TAG = "TMF"

    private val cfKiller by lazy { CloudflareKiller() }

    class Stream(
        val name: String,
        val url: String,
        val type: ExtractorLinkType,
        val headers: Map<String, String> = emptyMap()
    )

    // fastdl embeds a dl.php hop that carries the google drive file in its
    // link parameter
    suspend fun resolveFastDl(url: String): List<Stream> {
        return try {
            val html = app.get(
                url,
                headers = TmfNet.browserHeaders("https://nexdrive.fit/"),
                timeout = 20L
            ).text
            if (html.contains("File is Deleted") || html.contains("Something went wrong")) return emptyList()
            val reurl = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: Regex("""'(https://fastdl\.[^']+/dl\.php\?link=[^']+)'""").find(html)?.groupValues?.get(1)
                ?: return emptyList()
            val googleUrl = Regex("""link=(https?://[^&"']+)""").find(reurl)?.groupValues?.get(1)
                ?: return emptyList()
            val direct = URLDecoder.decode(googleUrl, "UTF-8")
            if (!direct.startsWith("http")) return emptyList()
            listOf(Stream("G-Direct", direct, ExtractorLinkType.VIDEO, mapOf("Referer" to "https://fastdl.zip/")))
        } catch (e: Exception) {
            Log.d(TAG, "fastdl: ${e.message}")
            emptyList()
        }
    }

    // vcloud hides the file behind a second page whose script hands out a
    // double base64 or a plain url variable
    suspend fun resolveVCloud(url: String): List<Stream> {
        return try {
            val headers = TmfNet.browserHeaders("https://nexdrive.fit/")
            val doc = app.get(url, headers = headers, interceptor = cfKiller, timeout = 30L).document

            val downloadLink = doc.selectFirst("div.main h4 a")?.attr("href") ?: return emptyList()
            val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "https://vcloud.fit$downloadLink"
            val doc2 = app.get(fullUrl, headers = headers, interceptor = cfKiller, timeout = 30L).document

            val scriptData = doc2.selectFirst("script:containsData(url)")?.data() ?: return emptyList()
            val encoded = Regex("""atob\(atob\('([^']+)'\)\)""").find(scriptData)?.groupValues?.get(1)
            if (encoded != null) {
                val decoded = try {
                    base64Decode(base64Decode(encoded))
                } catch (e: Exception) {
                    null
                }
                if (decoded != null && decoded.startsWith("http")) {
                    return listOf(Stream("V-Cloud", decoded, ExtractorLinkType.VIDEO))
                }
            }
            val varUrl = Regex("""var\s+url\s*=\s*'([^']*)'""").find(scriptData)?.groupValues?.get(1)
            if (varUrl != null && varUrl.startsWith("http")) {
                return listOf(Stream("V-Cloud", varUrl, ExtractorLinkType.VIDEO))
            }
            val btn = doc2.selectFirst("div.card-body h2 a.btn[href^=http]")?.attr("href")
            if (btn != null) listOf(Stream("V-Cloud", btn, ExtractorLinkType.VIDEO)) else emptyList()
        } catch (e: Exception) {
            Log.d(TAG, "vcloud: ${e.message}")
            emptyList()
        }
    }

    // vegadrive fronts google drive files behind its own bridge, the share
    // page links a skydrop page whose go link then redirects through the
    // bridge to the drive file
    suspend fun resolveVegaDrive(url: String): List<Stream> {
        return try {
            val page = app.get(
                url,
                headers = TmfNet.browserHeaders("https://nexdrive.fit/"),
                timeout = 20L
            )
            val base = URI(page.url).let { "${it.scheme}://${it.host}" }
            val token = url.substringAfter("/s/").substringBefore("?")

            val skydropHref = page.document.select("a[href]").firstOrNull {
                it.attr("href").contains("skydrop")
            }?.attr("href")?.let { absolute(it, base) } ?: "$base/d/$token/skydrop"

            val goLink = try {
                val skydropPage = app.get(
                    skydropHref,
                    headers = TmfNet.browserHeaders(base),
                    timeout = 20L
                )
                skydropPage.document.select("a[href]").firstOrNull {
                    it.attr("href").contains("/go/")
                }?.attr("href")?.let { absolute(it, base) }
            } catch (e: Exception) {
                null
            } ?: "$base/go/$token/skydrop"

            var current = goLink
            repeat(4) {
                val res = app.get(
                    current,
                    headers = TmfNet.browserHeaders(base),
                    allowRedirects = false,
                    timeout = 20L
                )
                val loc = res.headers["location"]?.trim().orEmpty()
                if (loc.isEmpty()) return@repeat
                current = if (loc.startsWith("http")) loc else base + loc
            }
            if (current != goLink && current.startsWith("http") && !current.contains("vegadrive")) {
                listOf(Stream("V-Drive", current, ExtractorLinkType.VIDEO))
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.d(TAG, "vegadrive: ${e.message}")
            emptyList()
        }
    }

    // ads and site plumbing that sit next to the real download buttons on
    // the hub pages, none of them carry a file
    private val hubJunk = Regex(
        "tinyurl|t\\.me|telegram|/tg/|winexch|a-ads|snvhost|one\\.one\\.one\\.one|" +
            "google\\.com/search|hubcloud\\.fans|drive/admin"
    )

    private val pxlRegex = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")

    // the pixel button href is a dead placeholder that stays the same for
    // every file, the real pixeldrain link sits in the pxl variable of the
    // page, following the placeholder is what hands every quality the same
    // dead file id
    private fun pixelFileUrl(pageHtml: String, buttonHref: String): String? {
        val pxl = pxlRegex.find(pageHtml)?.groupValues?.get(1)
        val link = pxl?.takeIf { it.startsWith("http") } ?: buttonHref
        if (!link.startsWith("http")) return null
        if (link.contains("download", true)) return link
        val id = link.substringBefore("?").substringBefore("#").substringAfterLast("/")
        if (id.isBlank()) return null
        val host = try {
            URI(link).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            return null
        }
        return "$host/api/file/$id?download"
    }

    private fun isArchiveName(name: String): Boolean =
        Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(name.trim())

    private fun absolute(link: String, base: String): String =
        if (link.startsWith("http")) link else base.trimEnd('/') + "/" + link.removePrefix("/")

    // the hub pages (filepress, filebee, hubcloud) only carry a generate
    // button half the time, the real servers sit behind it, everything
    // already on the page is handled directly
    private suspend fun hubStreams(
        doc: org.jsoup.nodes.Document,
        base: String,
        pageUrl: String,
        depth: Int
    ): List<Stream> {
        val out = mutableListOf<Stream>()
        val header = doc.selectFirst("div.card-header")?.text().orEmpty()
        if (isArchiveName(header)) return emptyList()

        val generate = doc.select("a.btn, a[download]")
            .firstOrNull { it.text().contains("generate", true) }
            ?.attr("href")?.trim()
        if (!generate.isNullOrBlank() && depth < 3) {
            try {
                val genDoc = app.get(
                    absolute(generate, base),
                    headers = TmfNet.browserHeaders(pageUrl),
                    interceptor = cfKiller,
                    timeout = 25L
                ).document
                val nested = hubStreams(genDoc, base, pageUrl, depth + 1)
                if (nested.isNotEmpty()) return nested
            } catch (e: Exception) {
                Log.d(TAG, "generate: ${e.message}")
            }
        }

        if (pageUrl.contains("/video/") && depth < 3) {
            val inner = doc.selectFirst("div.vd > center > a")?.attr("href")
            if (!inner.isNullOrBlank()) {
                try {
                    val innerDoc = app.get(
                        absolute(inner, base),
                        headers = TmfNet.browserHeaders(base),
                        interceptor = cfKiller,
                        timeout = 25L
                    ).document
                    val nested = hubStreams(innerDoc, base, inner, depth + 1)
                    if (nested.isNotEmpty()) return nested
                } catch (e: Exception) {
                    Log.d(TAG, "video page: ${e.message}")
                }
            }
        }

        val pageHtml = doc.toString()
        for (btn in doc.select("a.btn, a[download]")) {
            val text = btn.text().trim().lowercase()
            val link = btn.attr("href").trim()
            if (link.isBlank() || hubJunk.containsMatchIn(link)) continue
            val abs = absolute(link, base)
            when {
                text.contains("fslv2") -> out.add(Stream("FSLv2", abs, ExtractorLinkType.VIDEO))
                text.contains("fsl") -> out.add(Stream("FSL Server", abs, ExtractorLinkType.VIDEO))
                text.contains("buzzserver") -> {
                    try {
                        val dlink = app.get(
                            "$abs/download",
                            referer = abs,
                            allowRedirects = false,
                            timeout = 15L
                        ).headers["hx-redirect"] ?: ""
                        if (dlink.isNotBlank()) {
                            out.add(Stream("BuzzServer", absolute(dlink, base), ExtractorLinkType.VIDEO))
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "buzzserver: ${e.message}")
                    }
                }
                text.contains("10gbps") -> {
                    val target = followRedirects(abs, base)
                    if (target != null) {
                        val direct = if (target.contains("link=")) target.substringAfter("link=") else target
                        if (direct.startsWith("http")) {
                            out.add(Stream("10Gbps", direct, ExtractorLinkType.VIDEO))
                        }
                    }
                }
                text.contains("instant download") || text.contains("instant dl") -> {
                    try {
                        val loc = app.get(
                            abs,
                            headers = TmfNet.browserHeaders(base),
                            allowRedirects = false,
                            timeout = 15L
                        ).headers["location"]?.trim()
                        val direct = when {
                            loc == null -> null
                            loc.contains("url=") -> loc.substringAfter("url=")
                            loc.contains("link=") -> loc.substringAfter("link=")
                            else -> loc
                        }?.takeIf { it.startsWith("http") }
                        if (direct != null) {
                            out.add(Stream("Instant Download", direct, ExtractorLinkType.VIDEO))
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "instant: ${e.message}")
                    }
                }
                text.contains("pixeldra") || text.contains("pixelserver") || text.contains("pixel server") ||
                    link.contains("pixeldra") -> {
                    val final = pixelFileUrl(pageHtml, link) ?: continue
                    out.add(Stream("Pixeldrain", final, ExtractorLinkType.VIDEO))
                }
                text.contains("s3 server") || text.contains("mega server") || text.contains("pdl") ->
                    out.add(Stream(btn.text().trim(), abs, ExtractorLinkType.VIDEO))
                text.contains("download file") || text.contains("download now") ->
                    out.add(Stream("Download File", abs, ExtractorLinkType.VIDEO))
                link.contains("gofile.io") -> out.addAll(gofileStreams(abs))
            }
        }
        return out.distinctBy { it.url }
    }

    private suspend fun followRedirects(start: String, base: String): String? {
        var current = start
        repeat(7) {
            val res = try {
                app.get(
                    current,
                    headers = TmfNet.browserHeaders(base),
                    allowRedirects = false,
                    timeout = 10L
                )
            } catch (e: Exception) {
                return null
            }
            val loc = res.headers["location"]?.trim().orEmpty()
            if (loc.isEmpty()) return current.takeIf { it.startsWith("http") }
            current = when {
                loc.startsWith("http") -> loc
                else -> return null
            }
        }
        return null
    }

    suspend fun gofileStreams(url: String): List<Stream> {
        return try {
            val code = Regex("""/(?:\?c=|d/)([\da-zA-Z-]+)""").find(url)?.groupValues?.get(1)
                ?: return emptyList()
            val apiHeaders = mapOf(
                "User-Agent" to TmfNet.DESKTOP_UA,
                "Accept" to "application/json"
            )
            val token = JSONObject(
                app.post("https://api.gofile.io/accounts", headers = apiHeaders).text
            ).getJSONObject("data").getString("token")

            val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                app.get("https://gofile.io/dist/js/global.js", headers = apiHeaders).text
            )?.groupValues?.get(1)

            val contentUrl = "https://api.gofile.io/contents/$code" + (if (wt != null) "?wt=$wt" else "")
            val contentResp = app.get(contentUrl, headers = apiHeaders.toMutableMap().apply {
                put("Authorization", "Bearer $token")
            }).text

            val children = JSONObject(contentResp).optJSONObject("data")
                ?.optJSONObject("children") ?: return emptyList()
            if (children.length() == 0) return emptyList()
            val fileObj = children.getJSONObject(children.keys().next())
            listOf(
                Stream(
                    "GoFile",
                    fileObj.getString("link"),
                    ExtractorLinkType.VIDEO,
                    mapOf("Authorization" to "Bearer $token")
                )
            )
        } catch (e: Exception) {
            Log.d(TAG, "gofile: ${e.message}")
            emptyList()
        }
    }

    suspend fun resolveHub(url: String): List<Stream> {
        return try {
            val res = app.get(
                url,
                headers = TmfNet.browserHeaders("https://nexdrive.fit/"),
                interceptor = cfKiller,
                timeout = 25L
            )
            if (!res.isSuccessful) return emptyList()
            val base = try {
                URI(res.url).let { "${it.scheme}://${it.host}" }
            } catch (e: Exception) {
                return emptyList()
            }
            hubStreams(res.document, base, res.url, 0)
        } catch (e: Exception) {
            Log.d(TAG, "hub: ${e.message}")
            emptyList()
        }
    }

    fun resolves(href: String): Boolean =
        href.contains("fastdl.") || href.contains("vcloud.") ||
            href.contains("vegadrive.") || href.contains("filebee.") ||
            href.contains("filepress.") || href.contains("hubcloud.") ||
            href.contains("gofile.io")

    suspend fun resolveOne(href: String): List<Stream> = when {
        href.contains("fastdl.") -> resolveFastDl(href)
        href.contains("vcloud.") -> resolveVCloud(href)
        href.contains("vegadrive.") -> resolveVegaDrive(href)
        href.contains("gofile.io") -> gofileStreams(href)
        href.contains("filebee.") || href.contains("filepress.") ||
            href.contains("hubcloud.") -> resolveHub(href)
        else -> emptyList()
    }

    // every host this plugin knows resolves through its own logic so the
    // result never depends on which other extension registered last for
    // the same host, unknown hosts still fall through to loadExtractor
    // because another extension may know them
    suspend fun emitAll(
        hrefs: List<String>,
        qualityHint: Int?,
        info: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emitted = java.util.concurrent.atomic.AtomicBoolean(false)
        coroutineScope {
            hrefs.map { href ->
                async(Dispatchers.IO) {
                    if (resolves(href)) {
                        val streams = try {
                            resolveOne(href)
                        } catch (e: Exception) {
                            Log.d(TAG, "source failed: ${e.message}")
                            emptyList()
                        }
                        for (s in streams) {
                            val label = if (info.isBlank()) s.name else "${s.name} · $info"
                            callback.invoke(
                                ExtractorLink(
                                    source = "TheMoviesFlix",
                                    name = label,
                                    url = s.url,
                                    referer = "",
                                    quality = qualityHint ?: Qualities.Unknown.value,
                                    type = s.type,
                                    headers = s.headers
                                )
                            )
                            emitted.set(true)
                        }
                    } else {
                        try {
                            com.lagradost.cloudstream3.utils.loadExtractor(href, referer, subtitleCallback) { link ->
                                callback.invoke(link)
                                emitted.set(true)
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "fallback failed: ${e.message}")
                        }
                    }
                }
            }.awaitAll()
        }
        return emitted.get()
    }
}
