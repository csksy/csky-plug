package com.laddu100

import com.lagradost.api.Log
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
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Every host the drive pages link to, resolved through this plugin's own
 * logic so the result never depends on which other extension registered
 * last for the same host.
 *
 * fastdl          -> G-Direct, the drive file is inside the reurl variable
 * vcloud          -> V-Cloud, script variable on the page (atob double or plain)
 * vegadrive       -> V-Drive, a /s/ picker page with one bridge provider per
 *                    host (vegadrop serves the drive file directly, pixeldrain
 *                    hands out its own file id)
 * filebee/filepress -> FilePress, a react app whose json api is open while
 *                    the html pages sit behind an interactive cloudflare
 *                    turnstile, dotflix mirrors the drive file as an instant
 *                    link and telegram hands out a tgfiles redirect
 */
object TmfSources {
    private const val TAG = "TMF"

    class Stream(
        val name: String,
        val url: String,
        val type: ExtractorLinkType,
        val headers: Map<String, String> = emptyMap()
    )

    private fun originOf(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) {
        url
    }

    private fun absolute(link: String, base: String): String =
        if (link.startsWith("http")) link else base.trimEnd('/') + "/" + link.removePrefix("/")

    private suspend fun redirectOf(
        url: String,
        referer: String?,
        hops: Int = 6
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
            } catch (e: Exception) {
                return null
            }
            val loc = res.headers["location"]?.trim().orEmpty()
            if (loc.isEmpty()) return current
            current = when {
                loc.startsWith("http") -> loc
                loc.startsWith("/") -> originOf(current) + loc
                else -> return null
            }
        }
        return current
    }

    // ---------------------------------------------------------------- fastdl

    // fastdl serves a tiny redirect stub, the google drive link always sits
    // in the reurl variable, the hubcdn wiki host serves the same stub with
    // one extra base64 hop inside the r parameter
    suspend fun resolveFastDl(url: String): List<Stream> {
        return try {
            val res = app.get(
                url,
                headers = TmfNet.browserHeaders("https://nexdrive.fit/"),
                timeout = 20L
            )
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
            listOf(Stream("G-Direct", direct, ExtractorLinkType.VIDEO, mapOf("Referer" to originOf(url) + "/")))
        } catch (e: Exception) {
            Log.d(TAG, "fastdl: ${e.message}")
            emptyList()
        }
    }

    // ---------------------------------------------------------------- vcloud

    // vcloud hides the file behind its own page, older pages carry a
    // div.main h4 a hop first, the current ones keep the target in a script
    // variable that is either double base64 or a plain url, the target page
    // itself is a hub page when it is not the file directly
    suspend fun resolveVCloud(url: String): List<Stream> {
        return try {
            val res = app.get(
                url,
                headers = TmfNet.browserHeaders("https://nexdrive.fit/"),
                timeout = 30L
            )
            if (!res.isSuccessful) return emptyList()
            val base = originOf(res.url)
            val originalDoc = res.document
            var doc = originalDoc
            var link: String? = null

            // older layout: a download hop page first
            val hop = originalDoc.selectFirst("div.main h4 a")?.attr("href")?.trim()
            if (!hop.isNullOrBlank()) {
                val hopUrl = absolute(hop, base)
                val hopRes = try {
                    app.get(hopUrl, headers = TmfNet.browserHeaders(base), timeout = 30L)
                } catch (e: Exception) {
                    null
                }
                hopRes?.let {
                    doc = it.document
                    link = extractVCloudLink(doc)
                }
            }
            // the current pages keep the target in their own script, the hop
            // parse only wins when it actually found a link
            if (link.isNullOrBlank()) link = extractVCloudLink(originalDoc)

            // video pages keep the file behind a center anchor
            if (link.isNullOrBlank() && res.url.contains("/video/")) {
                link = doc.selectFirst("div.vd > center > a")?.attr("href")?.trim()
            }
            if (link.isNullOrBlank()) return emptyList()

            val target = absolute(link, base)
            if (!target.startsWith("http")) return emptyList()

            // when the target is a hub page the real servers sit on it
            val targetRes = try {
                app.get(target, headers = TmfNet.browserHeaders(base), timeout = 25L)
            } catch (e: Exception) {
                null
            }
            val targetDoc = targetRes?.document ?: return emptyList()
            val hub = hubStreams(targetDoc, originOf(targetRes.url), targetRes.url, 0)
            if (hub.isNotEmpty()) return hub
            if (target.contains("drive.google.com") || target.contains("googleusercontent")) {
                return listOf(Stream("V-Cloud", target, ExtractorLinkType.VIDEO))
            }
            emptyList()
        } catch (e: Exception) {
            Log.d(TAG, "vcloud: ${e.message}")
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

    // -------------------------------------------------------------- vegadrive

    // the vegadrive share page lists one bridge provider per host, vegadrop
    // (skydrop) streams the drive file itself, pixeldrain hands out its own
    // file id, everything else lands on the partner page
    suspend fun resolveVegaDrive(url: String): List<Stream> {
        return try {
            val page = app.get(
                url,
                headers = TmfNet.browserHeaders("https://nexdrive.fit/"),
                timeout = 20L
            )
            val base = originOf(page.url)
            val token = url.substringAfter("/s/").substringBefore("?")

            val out = mutableListOf<Stream>()

            // vegadrop: the go link walks through the bridge and lands on
            // the drive file directly, season packs arrive as zip archives
            // and are skipped
            val drop = resolveVegaProvider(base, token, "skydrop")
            if (drop != null && drop.contains("googleusercontent") &&
                !drop.substringAfterLast("/").contains(".zip", true)
            ) {
                out.add(Stream("V-Drive Vegadrop (10Gbps)", drop, ExtractorLinkType.VIDEO))
            }

            // pixeldrain: bridge hands out the share page, the file id in it
            // streams through the pixeldrain api
            val pixel = resolveVegaProvider(base, token, "pixeldrain")
            if (pixel != null && pixel.contains("pixeldrain")) {
                val id = pixel.substringBefore("?").substringBefore("#").substringAfterLast("/")
                if (id.isNotBlank()) {
                    out.add(
                        Stream(
                            "V-Drive Pixeldrain",
                            "https://pixeldrain.com/api/file/$id",
                            ExtractorLinkType.VIDEO
                        )
                    )
                }
            }

            // buzzheavier and telegram serve the file through their own hosts
            val buzz = resolveVegaProvider(base, token, "buzzheavier")
            if (buzz != null && buzz.contains("bzzhr.co")) {
                out.add(Stream("V-Drive Buzzheavier", buzz, ExtractorLinkType.VIDEO))
            }
            val telegram = resolveVegaProvider(base, token, "telegram")
            if (telegram != null && telegram.contains("tgfiles")) {
                out.add(Stream("V-Drive Telegram", telegram, ExtractorLinkType.VIDEO))
            }

            out.distinctBy { it.url }
        } catch (e: Exception) {
            Log.d(TAG, "vegadrive: ${e.message}")
            emptyList()
        }
    }

    // the provider pages only answer when the share page is sent as referer,
    // without it they bounce straight back to the picker
    private suspend fun resolveVegaProvider(
        base: String,
        token: String,
        provider: String
    ): String? {
        return try {
            val start = when (provider) {
                "skydrop" -> "$base/go/$token/skydrop"
                else -> "$base/d/$token/$provider"
            }
            redirectOf(start, "$base/", hops = 6)
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------- filepress

    private val FILEPRESS_ID = Regex("""/file/([a-f0-9]{16,40})""")
    private const val FILEBEE_API = "https://filebee.xyz/api"

    // filepress is a react app now, the html pages sit behind an interactive
    // turnstile but the json api is open: file/get describes the file (the
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
            } catch (e: Exception) {
                return emptyList()
            }
            val name = info.optString("name")
            if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(name.trim())) return emptyList()

            val out = mutableListOf<Stream>()

            // dotflix mirrors the drive file and serves it as an instant link
            val dotflix = filePressDownload(id, "dotFlixDownlaod")
            if (dotflix != null && dotflix.startsWith("http")) {
                val direct = resolveDotFlix(dotflix)
                if (direct != null && direct.startsWith("http")) {
                    out.add(Stream("FilePress Instant", direct, ExtractorLinkType.VIDEO))
                }
            }

            // telegram answers with a tgfiles redirect right away
            val telegram = filePressDownload(id, "telegramDownload")
            if (telegram != null && telegram.startsWith("http")) {
                out.add(Stream("FilePress Telegram", telegram, ExtractorLinkType.VIDEO))
            }

            // the index worker proxies through its own host, the link is
            // short lived so it is only emitted when the file actually answers
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
        } catch (e: Exception) {
            Log.d(TAG, "filepress: ${e.message}")
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
            } catch (e: Exception) {
                return null
            }
            if (!parsed.optBoolean("status")) return null
            when (val data = parsed.opt("data")) {
                is String -> data.takeIf { it.isNotBlank() }
                else -> null
            }
        } catch (e: Exception) {
            Log.d(TAG, "filepress $method: ${e.message}")
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
            } catch (e: Exception) {
                return null
            }
            if (!parsed.optBoolean("status")) return null
            when (val data = parsed.opt("data")) {
                is String -> data.takeIf { it.startsWith("http") }
                is org.json.JSONArray -> (0 until data.length())
                    .firstNotNullOfOrNull { i ->
                        data.optString(i).takeIf { it.startsWith("http") }
                    }
                else -> null
            }
        } catch (e: Exception) {
            Log.d(TAG, "filepress final: ${e.message}")
            null
        }
    }

    // the dotflix share page carries a per file code in a btoa call, the
    // reversed base64 of it is posted to the extract endpoint which answers
    // with the drive file url
    private suspend fun resolveDotFlix(shareUrl: String): String? {
        return try {
            val page = app.get(
                shareUrl,
                headers = TmfNet.browserHeaders("https://new2.dotflix.shop/"),
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
            val res = app.post(
                "https://dotflix.store/api/extract-download",
                headers = TmfNet.browserHeaders("https://new2.dotflix.shop/")
                    .toMutableMap()
                    .apply {
                        put("Content-Type", "application/json")
                        put("Accept", "application/json")
                        put("X-Request-ID", requestId)
                        put("X-Timestamp", timestamp)
                        put("Origin", "https://new2.dotflix.shop")
                    },
                json = body.toString(),
                timeout = 25L
            )
            if (!res.isSuccessful) return null
            val parsed = try {
                JSONObject(res.text)
            } catch (e: Exception) {
                return null
            }
            if (!parsed.optBoolean("success")) return null
            parsed.optString("downloadUrl").takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            Log.d(TAG, "dotflix: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------- hub

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
        return "${originOf(link)}/api/file/$id"
    }

    private fun isArchiveName(name: String): Boolean =
        Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(name.trim())

    // the old hub layout: a generate button first, then the full server
    // list, still used by the vcloud target pages
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
                    val target = redirectOf(abs, base)
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
                link.contains("fastdl.") || link.contains("hubcdn.") -> {
                    out.addAll(resolveFastDl(abs))
                }
            }
        }
        return out.distinctBy { it.url }
    }

    // --------------------------------------------------------------- router

    fun resolves(href: String): Boolean =
        href.contains("fastdl.") || href.contains("vcloud.") ||
            href.contains("vegadrive.") || href.contains("filebee.") ||
            href.contains("filepress.") || href.contains("fpgo.") ||
            href.contains("hubcloud.")

    suspend fun resolveOne(href: String): List<Stream> = when {
        href.contains("fastdl.") || href.contains("hubcdn.") -> resolveFastDl(href)
        href.contains("vcloud.") -> resolveVCloud(href)
        href.contains("vegadrive.") -> resolveVegaDrive(href)
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
