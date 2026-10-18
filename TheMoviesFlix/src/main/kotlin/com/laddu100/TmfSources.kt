package com.laddu100

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

// hosts are resolved here instead of through loadExtractor, whose result depends on extension order
object TmfSources {

    class Stream(
        val name: String,
        val url: String,
        val type: ExtractorLinkType,
        val headers: Map<String, String> = emptyMap()
    )

    private fun absolute(link: String, base: String): String =
        if (link.startsWith("http")) link else base.trimEnd('/') + "/" + link.removePrefix("/")

    private suspend fun redirectOf(
        url: String,
        referer: String?,
        hops: Int = 8
    ): String? {
        var current = url
        repeat(hops) {
            val res = try {
                app.get(
                    current,
                    headers = TmfNet.browserHeaders(referer),
                    allowRedirects = false,
                    timeout = 15L
                )
            } catch (_: Exception) {
                return null
            }
            val loc = res.headers["location"]?.trim().orEmpty()
            if (loc.isEmpty()) return current
            current = when {
                loc.startsWith("http") -> loc
                loc.startsWith("/") -> TmfNet.originOf(current) + loc
                else -> return null
            }
        }
        return current
    }

    // hosts that are gone or sit behind a turnstile the player can never
    // pass, handing them to the player or another extractor only produces
    // dead entries
    private val deadHosts = Regex(
        "gofile\\.io|1fichier\\.com|megaup\\.net|vikingfile\\.com|gdtot\\.|dropgalaxy"
    )

    // fastdl serves a redirect stub with the drive link in the reurl variable,
    // the hubcdn wiki host serves the same stub with one extra base64 hop in r
    suspend fun resolveFastDl(url: String): List<Stream> {
        return try {
            val res = TmfCf.get(url, "https://mobilejsr.best/", 25L) ?: return emptyList()
            val text = res.text
            if (text.contains("File is Deleted") || text.contains("Something went wrong")) {
                return emptyList()
            }
            val reurl = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                ?: Regex("""'(https://fastdl\.[^']*dl\.php\?link=[^']+)'""").find(text)?.groupValues?.get(1)
                ?: Regex("""'(https://hubcdn\.[^']*dl\.php\?link=[^']+)'""").find(text)?.groupValues?.get(1)
                ?: return emptyList()
            val carrier = Regex("""[?&]r=([A-Za-z0-9+/=_-]+)""").find(reurl)?.groupValues?.get(1)
                ?.let { wrapped ->
                    val padded = if (wrapped.length % 4 > 0) {
                        wrapped + "=".repeat(4 - wrapped.length % 4)
                    } else wrapped
                    runCatching { base64Decode(padded) }.getOrNull()
                } ?: reurl
            val googleUrl = Regex("""link=(https?://[^&"']+)""").find(carrier)?.groupValues?.get(1)
                ?: return emptyList()
            val direct = URLDecoder.decode(googleUrl, "UTF-8")
            if (!direct.startsWith("http")) return emptyList()
            listOf(Stream("G-Direct", direct, ExtractorLinkType.VIDEO, mapOf("Referer" to TmfNet.originOf(url) + "/")))
        } catch (_: Exception) {
            emptyList()
        }
    }

    // the mcloud file page hides its real target behind a generate button, the
    // button decodes a double base64 url on the vcloud origin and swaps that
    // origin for the host the page sits on, the page behind it lists one r2
    // mirror and one worker bridge per file
    private val doubleAtob = Regex("""atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")

    private fun decodeDoubleBase64(text: String): String? =
        doubleAtob.find(text)?.groupValues?.get(1)
            ?.let { wrapped ->
                runCatching { base64Decode(wrapped) }.getOrNull()
                    ?.let { runCatching { base64Decode(it) }.getOrNull() }
            }
            ?.takeIf { it.startsWith("http") }

    private val linkParam = Regex("""link=(https?://[^&"']+)""")

    private suspend fun mcloudMirrors(pageHtmlDoc: org.jsoup.nodes.Document, pageUrl: String): List<Stream> {
        val out = mutableListOf<Stream>()
        val base = TmfNet.originOf(pageUrl)
        val buttons = pageHtmlDoc.select("div.mirrors-container a[href]").ifEmpty {
            pageHtmlDoc.select("div.card-body a.btn[href]")
        }
        for (a in buttons) {
            val text = a.text().lowercase()
            val href = a.attr("href").trim()
            if (!href.startsWith("http") || hubJunk.containsMatchIn(href)) continue
            when {
                text.contains("fsl") -> {
                    // the r2 mirrors expire quietly, the file is only offered when it answers
                    val code = TmfNet.probe(href, base)
                    if (code != null && code in 200..299) {
                        out.add(Stream("M-Cloud FSL", href, ExtractorLinkType.VIDEO))
                    }
                }
                text.contains("10gbps") || text.contains("server") -> {
                    val target = redirectOf(href, base) ?: continue
                    val direct = linkParam.find(target)?.groupValues?.get(1) ?: continue
                    if (direct.startsWith("http")) {
                        out.add(Stream("M-Cloud 10Gbps", direct, ExtractorLinkType.VIDEO))
                    }
                }
            }
        }
        return out.distinctBy { it.url }
    }

    suspend fun resolveMCloud(url: String): List<Stream> {
        return try {
            val res = TmfCf.get(url, "https://mobilejsr.best/", 25L) ?: return emptyList()
            if (!res.isSuccessful) return emptyList()
            val base = TmfNet.originOf(res.url)

            val direct = mcloudMirrors(res.document, res.url)
            if (direct.isNotEmpty()) return direct

            val hop = decodeDoubleBase64(res.text) ?: return emptyList()
            val hopUrl = hop.replace(
                Regex("""https?://(www\.)?vcloud\.beer""", RegexOption.IGNORE_CASE),
                base
            )
            val hopRes = TmfCf.get(hopUrl, "$base/", 25L) ?: return emptyList()
            if (!hopRes.isSuccessful) return emptyList()

            val viaHop = mcloudMirrors(hopRes.document, hopRes.url)
            if (viaHop.isNotEmpty()) return viaHop

            hubStreams(hopRes.document, TmfNet.originOf(hopRes.url), hopRes.url, 0)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // older vcloud pages carry a div.main h4 a hop first, the current ones
    // keep the target in a script variable that is either double base64 or a
    // plain url, and when the target is not the file itself it is a hub page
    suspend fun resolveVCloud(url: String): List<Stream> {
        return try {
            val res = TmfCf.get(url, "https://mobilejsr.best/", 30L) ?: return emptyList()
            if (!res.isSuccessful) return emptyList()
            val base = TmfNet.originOf(res.url)
            val originalDoc = res.document
            var doc = originalDoc
            var link: String? = null

            val hop = originalDoc.selectFirst("div.main h4 a")?.attr("href")?.trim()
            if (!hop.isNullOrBlank()) {
                val hopUrl = absolute(hop, base)
                val hopRes = try {
                    TmfCf.get(hopUrl, base, 30L)
                } catch (_: Exception) {
                    null
                }
                hopRes?.let {
                    doc = it.document
                    link = extractVCloudLink(doc)
                }
            }
            if (link.isNullOrBlank()) link = extractVCloudLink(originalDoc)

            if (link.isNullOrBlank() && res.url.contains("/video/")) {
                link = doc.selectFirst("div.vd > center > a")?.attr("href")?.trim()
            }
            if (link.isNullOrBlank()) return emptyList()

            val target = absolute(link, base)
            if (!target.startsWith("http")) return emptyList()

            val targetRes = try {
                TmfCf.get(target, base, 25L)
            } catch (_: Exception) {
                null
            }
            val targetDoc = targetRes?.document ?: return emptyList()
            val hub = hubStreams(targetDoc, TmfNet.originOf(targetRes.url), targetRes.url, 0)
            if (hub.isNotEmpty()) return hub
            if (target.contains("drive.google.com") || target.contains("googleusercontent")) {
                return listOf(Stream("V-Cloud", target, ExtractorLinkType.VIDEO))
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractVCloudLink(doc: org.jsoup.nodes.Document): String? {
        val script = doc.selectFirst("script:containsData(url)")?.data().orEmpty()
        if (script.isBlank()) return null
        Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
            .find(script)?.groupValues?.get(1)
            ?.let { encoded ->
                val decoded = runCatching { base64Decode(encoded) }.getOrNull()
                    ?.let { runCatching { base64Decode(it) }.getOrNull() }
                if (decoded != null && decoded.startsWith("http")) return decoded
            }
        Regex("""var\s+url\s*=\s*['"]([^'"]*)['"]""").find(script)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") }
            ?.let { return it }
        return doc.selectFirst("div.card-body h2 a.btn[href]")?.attr("href")?.trim()
            ?.takeIf { it.startsWith("http") }
    }

    // both drive frontends hand out the same provider bridge, skydrop streams
    // the drive file itself and the rest land on their own pages, the bridge
    // only answers when its own origin is the referer
    private suspend fun vegaStreams(endpoints: List<String>, label: String): List<Stream> {
        val out = mutableListOf<Stream>()
        for (endpoint in endpoints) {
            val base = TmfNet.originOf(endpoint)
            val final = redirectOf(endpoint, "$base/", hops = 6) ?: continue
            when {
                final.contains("googleusercontent") &&
                    !final.substringAfterLast("/").contains(".zip", true) ->
                    out.add(Stream("$label Stream (10Gbps)", final, ExtractorLinkType.VIDEO))

                final.contains("pixeldrain") && final.contains("/u/") -> {
                    val id = final.substringBefore("?").substringBefore("#").substringAfterLast("/")
                    if (id.isNotBlank()) {
                        out.add(
                            Stream(
                                "$label Pixeldrain",
                                "https://pixeldrain.com/api/file/$id",
                                ExtractorLinkType.VIDEO
                            )
                        )
                    }
                }

                final.contains("bzzhr.co") ->
                    out.add(Stream("$label Buzzheavier", final, ExtractorLinkType.VIDEO))
            }
        }
        return out.distinctBy { it.url }
    }

    // the vegadrive share page lists one bridge provider per host, the token
    // of the share url works on every provider endpoint of its own origin
    suspend fun resolveVegaDrive(url: String): List<Stream> {
        return try {
            val res = TmfCf.get(url, "https://mobilejsr.best/", 20L) ?: return emptyList()
            val base = TmfNet.originOf(res.url)
            val token = url.substringAfter("/s/").substringBefore("?")
            vegaStreams(
                listOf(
                    "$base/go/$token/skydrop",
                    "$base/d/$token/pixeldrain",
                    "$base/d/$token/buzzheavier"
                ),
                "V-Drive"
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    // the m-drive share page keeps the bridge endpoints in a base64 url map,
    // the buttons on the page only trigger the same map with javascript
    private val urlMapRegex = Regex("""const\s+urlMap\s*=\s*(\[.*?])\s*;""", RegexOption.DOT_MATCHES_ALL)

    suspend fun resolveMDrive(url: String): List<Stream> {
        return try {
            val res = TmfCf.get(url, "https://mobilejsr.best/", 20L) ?: return emptyList()
            if (!res.isSuccessful) return emptyList()
            val mapJson = urlMapRegex.find(res.text)?.groupValues?.get(1) ?: return emptyList()
            val entries = try {
                JSONArray(mapJson)
            } catch (_: Exception) {
                return emptyList()
            }
            val endpoints = (0 until entries.length()).mapNotNull { i ->
                val encoded = entries.optJSONObject(i)?.optString("url").orEmpty()
                if (encoded.isBlank()) return@mapNotNull null
                val decoded = runCatching { base64Decode(encoded.replace("\\", "")) }.getOrNull()
                decoded?.takeIf { it.startsWith("http") }
            }.distinct()

            vegaStreams(endpoints, "M-Drive")
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val FILEPRESS_ID = Regex("""/file/([a-f0-9]{16,40})""")
    private const val FILEBEE_API = "https://filebee.xyz/api"

    // filepress is a react app whose html pages sit behind an interactive
    // turnstile while the json api is open: file/get describes the file (its
    // name is the only reliable zip pack detector), downlaod/ queues a task
    // or answers instantly depending on the method, downlaod2/ turns a
    // finished task into the link
    suspend fun resolveFilePress(url: String): List<Stream> {
        val id = FILEPRESS_ID.find(url)?.groupValues?.get(1) ?: return emptyList()
        return try {
            val infoRes = app.get(
                "$FILEBEE_API/file/get/$id",
                headers = TmfNet.browserHeaders("https://filebee.xyz/"),
                timeout = 15L
            )
            if (!infoRes.isSuccessful) return emptyList()
            val info = try {
                JSONObject(infoRes.text).optJSONObject("data") ?: return emptyList()
            } catch (_: Exception) {
                return emptyList()
            }
            val name = info.optString("name")
            if (isArchiveName(name)) return emptyList()

            val out = mutableListOf<Stream>()

            // dotflix mirrors the drive file and serves it as an instant link
            val dotflix = filePressDownload(id, "dotFlixDownlaod")
            if (dotflix != null && dotflix.startsWith("http")) {
                val direct = resolveDotFlix(dotflix)
                if (direct != null && direct.startsWith("http")) {
                    out.add(Stream("FilePress Instant", direct, ExtractorLinkType.VIDEO))
                }
            }

            // the index worker proxies through its own host with a short
            // lived link, it is only emitted when the file actually answers
            val indexTask = filePressDownload(id, "indexDownlaod")
            if (indexTask != null && indexTask.matches(Regex("[a-f0-9]{16,40}"))) {
                val link = filePressFinal(indexTask, "indexDownlaod")
                if (link != null) {
                    val probe = TmfNet.probe(link, "https://filebee.xyz/")
                    if (probe != null && probe in 200..299) {
                        out.add(Stream("FilePress Direct", link, ExtractorLinkType.VIDEO))
                    }
                }
            }

            out.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun filePressDownload(id: String, method: String): String? {
        return try {
            val body = JSONObject()
                .put("captchaValue", "")
                .put("id", id)
                .put("method", method)
            val res = app.post(
                "$FILEBEE_API/file/downlaod/",
                headers = TmfNet.browserHeaders("https://filebee.xyz/")
                    .toMutableMap()
                    .apply {
                        put("Content-Type", "application/json")
                        put("Accept", "application/json")
                        put("Origin", "https://filebee.xyz")
                    },
                json = body.toString(),
                timeout = 25L
            )
            if (!res.isSuccessful) return null
            val parsed = try {
                JSONObject(res.text)
            } catch (_: Exception) {
                return null
            }
            if (!parsed.optBoolean("status")) return null
            when (val data = parsed.opt("data")) {
                is String -> data.takeIf { it.isNotBlank() }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun filePressFinal(taskId: String, method: String): String? {
        return try {
            val body = JSONObject()
                .put("captchaValue", "")
                .put("id", taskId)
                .put("method", method)
            val res = app.post(
                "$FILEBEE_API/file/downlaod2/",
                headers = TmfNet.browserHeaders("https://filebee.xyz/")
                    .toMutableMap()
                    .apply {
                        put("Content-Type", "application/json")
                        put("Accept", "application/json")
                        put("Origin", "https://filebee.xyz")
                    },
                json = body.toString(),
                timeout = 30L
            )
            if (!res.isSuccessful) return null
            val parsed = try {
                JSONObject(res.text)
            } catch (_: Exception) {
                return null
            }
            if (!parsed.optBoolean("status")) return null
            when (val data = parsed.opt("data")) {
                is String -> data.takeIf { it.startsWith("http") }
                is JSONArray -> (0 until data.length())
                    .firstNotNullOfOrNull { i ->
                        data.optString(i).takeIf { it.startsWith("http") }
                    }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // the extract service moved off dotflix.store, the old address answers
    // with a redirect that drops the post body
    private val EXTRACT_ENDPOINTS = listOf(
        "https://fajlix.cc/api/extract-download",
        "https://dotflix.store/api/extract-download"
    )

    // the dotflix share page carries a per file code in a btoa call, the
    // reversed base64 of it is posted to the extract endpoint which answers
    // with the drive file url
    private suspend fun resolveDotFlix(shareUrl: String): String? {
        return try {
            val origin = TmfNet.originOf(shareUrl)
            val page = app.get(
                shareUrl,
                headers = TmfNet.browserHeaders("$origin/"),
                timeout = 20L
            )
            val text = page.text
            val code = Regex("""btoa\('([^']+)'\)""").find(text)?.groupValues?.get(1)
                ?: return null
            val obfuscated = java.util.Base64.getEncoder()
                .encodeToString(code.toByteArray())
                .reversed()
            val requestId = List(13) {
                "abcdefghijklmnopqrstuvwxyz0123456789".random()
            }.joinToString("")
            val timestamp = System.currentTimeMillis().toString()
            val body = JSONObject()
                .put("requestId", requestId)
                .put("timestamp", timestamp)
                .put("data", obfuscated)
            for (endpoint in EXTRACT_ENDPOINTS) {
                val direct = try {
                    val res = app.post(
                        endpoint,
                        headers = TmfNet.browserHeaders("$origin/")
                            .toMutableMap()
                            .apply {
                                put("Content-Type", "application/json")
                                put("Accept", "application/json")
                                put("X-Request-ID", requestId)
                                put("X-Timestamp", timestamp)
                                put("Origin", origin)
                            },
                        json = body.toString(),
                        timeout = 25L
                    )
                    if (!res.isSuccessful) null
                    else try {
                        JSONObject(res.text)
                    } catch (_: Exception) {
                        null
                    }?.let { parsed ->
                        if (parsed.optBoolean("success")) {
                            parsed.optString("downloadUrl").takeIf { it.startsWith("http") }
                        } else null
                    }
                } catch (_: Exception) {
                    null
                }
                if (direct != null) return direct
            }
            null
        } catch (_: Exception) {
            null
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
        return "${TmfNet.originOf(link)}/api/file/$id"
    }

    private fun isArchiveName(name: String): Boolean =
        Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(name.trim())

    // the old hub layout with a generate button first and the full server
    // list behind it, still used by the vcloud target pages
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
                    timeout = 25L
                ).document
                val nested = hubStreams(genDoc, base, pageUrl, depth + 1)
                if (nested.isNotEmpty()) return nested
            } catch (_: Exception) {}
        }

        if (pageUrl.contains("/video/") && depth < 3) {
            val inner = doc.selectFirst("div.vd > center > a")?.attr("href")
            if (!inner.isNullOrBlank()) {
                try {
                    val innerDoc = app.get(
                        absolute(inner, base),
                        headers = TmfNet.browserHeaders(base),
                        timeout = 25L
                    ).document
                    val nested = hubStreams(innerDoc, base, inner, depth + 1)
                    if (nested.isNotEmpty()) return nested
                } catch (_: Exception) {}
            }
        }

        val pageHtml = doc.toString()
        for (btn in doc.select("a.btn, a[download]")) {
            out.addAll(hubButton(btn, base, pageHtml))
        }
        return out.distinctBy { it.url }
    }

    private suspend fun hubButton(
        btn: org.jsoup.nodes.Element,
        base: String,
        pageHtml: String
    ): List<Stream> {
        val text = btn.text().trim().lowercase()
        val link = btn.attr("href").trim()
        if (link.isBlank() || hubJunk.containsMatchIn(link)) return emptyList()
        val abs = absolute(link, base)
        return try {
            when {
                text.contains("fslv2") -> listOf(Stream("FSLv2", abs, ExtractorLinkType.VIDEO))
                text.contains("fsl") -> listOf(Stream("FSL Server", abs, ExtractorLinkType.VIDEO))
                text.contains("buzzserver") -> {
                    val dlink = app.get(
                        "$abs/download",
                        referer = abs,
                        allowRedirects = false,
                        timeout = 15L
                    ).headers["hx-redirect"] ?: ""
                    if (dlink.isNotBlank()) {
                        listOf(Stream("BuzzServer", absolute(dlink, base), ExtractorLinkType.VIDEO))
                    } else emptyList()
                }
                text.contains("10gbps") -> {
                    val target = redirectOf(abs, base)
                    if (target != null) {
                        val direct = if (target.contains("link=")) target.substringAfter("link=") else target
                        if (direct.startsWith("http")) {
                            listOf(Stream("10Gbps", direct, ExtractorLinkType.VIDEO))
                        } else emptyList()
                    } else emptyList()
                }
                text.contains("instant download") || text.contains("instant dl") -> {
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
                        listOf(Stream("Instant Download", direct, ExtractorLinkType.VIDEO))
                    } else emptyList()
                }
                text.contains("pixeldra") || text.contains("pixelserver") || text.contains("pixel server") ||
                    link.contains("pixeldra") -> {
                    val final = pixelFileUrl(pageHtml, link) ?: return emptyList()
                    listOf(Stream("Pixeldrain", final, ExtractorLinkType.VIDEO))
                }
                text.contains("s3 server") || text.contains("mega server") || text.contains("pdl") ->
                    listOf(Stream(btn.text().trim(), abs, ExtractorLinkType.VIDEO))
                text.contains("download file") || text.contains("download now") ->
                    listOf(Stream("Download File", abs, ExtractorLinkType.VIDEO))
                link.contains("fastdl.") || link.contains("hubcdn.") -> resolveFastDl(abs)
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun resolves(href: String): Boolean =
        href.contains("fastdl.") || href.contains("vcloud.") ||
            href.contains("vegadrive.") || href.contains("filebee.") ||
            href.contains("filepress.") || href.contains("fpgo.") ||
            href.contains("m-drive.") || href.contains("mcloud.")

    suspend fun resolveOne(href: String): List<Stream> = when {
        href.contains("mcloud.") || href.contains("vcloud.beer") -> resolveMCloud(href)
        href.contains("fastdl.") || href.contains("hubcdn.") -> resolveFastDl(href)
        href.contains("vcloud.") -> resolveVCloud(href)
        href.contains("vegadrive.") -> resolveVegaDrive(href)
        href.contains("m-drive.") -> resolveMDrive(href)
        href.contains("filebee.") || href.contains("filepress.") || href.contains("fpgo.") ->
            resolveFilePress(href)
        else -> emptyList()
    }

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
            hrefs.distinct().map { href ->
                async(Dispatchers.IO) {
                    if (deadHosts.containsMatchIn(href)) return@async
                    if (resolves(href)) {
                        val streams = try {
                            resolveOne(href)
                        } catch (_: Exception) {
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
                        } catch (_: Exception) {}
                    }
                }
            }.awaitAll()
        }
        return emitted.get()
    }
}
