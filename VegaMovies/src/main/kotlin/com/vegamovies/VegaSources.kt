package com.vegamovies

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

internal object VegaSources {

    class Stream(val name: String, val url: String, val headers: Map<String, String> = emptyMap())

    private val serverHosts = listOf(
        "nexdrive", "vcloud", "vegadrive", "filebee", "filepress", "fpgo",
        "fastdl", "gofile", "vikingfile", "vik1ngfile", "megaup", "hubcloud",
        "pixeldrain", "buzzheavier", "bzzhr", "dotflix"
    )

    fun knownServer(href: String, label: String): Boolean {
        if (serverHosts.any { href.contains(it, true) }) return true
        return Regex("""(?i)v-?cloud|v-?drive|filepress|g-?direct|gofile|viking|megaup""").containsMatchIn(label)
    }

    private fun hostOf(url: String): String = try {
        URI(url).host?.lowercase() ?: ""
    } catch (_: Exception) {
        ""
    }

    // the middle hop every button leads to, it carries servers or one section per episode
    class DrivePage(
        val servers: List<Pair<String, String>>,
        val episodes: Map<Int, List<Pair<String, String>>>
    )

    suspend fun fetchDrivePage(url: String): DrivePage? {
        val res = VegaNet.fetchWithCf(url, referer = "https://nexdrive.beer/") ?: return null
        val html = res.text
        if (Regex("""(?i)file (is )?deleted|not found""").containsMatchIn(html)) return null
        val doc = Jsoup.parse(html)
        val scope = doc.selectFirst(".entry, article, .post-inner") ?: doc

        val servers = mutableListOf<Pair<String, String>>()
        for (a in scope.select("a[href]")) {
            val href = a.attr("href").trim()
            val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                ?: a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank() || !href.startsWith("http")) continue
            if (Regex("""(?i)batch|zip\b""").containsMatchIn(label)) continue
            if (!knownServer(href, label)) continue
            if (href.contains("vglist") || href.contains("telegram") || href.contains("t.me")) continue
            servers.add(shortServerName(label) to href)
        }

        val episodes = LinkedHashMap<Int, List<Pair<String, String>>>()
        for (head in scope.select("h3, h4, h5")) {
            val text = head.text()
            val num = Regex("""(?i)episodes?\s*:?\s*0*(\d{1,3})""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val links = mutableListOf<Pair<String, String>>()
            var sib = head.nextElementSibling()
            while (sib != null && !listOf("h3", "h4", "h5").contains(sib.tagName())) {
                for (a in sib.select("a[href]")) {
                    val href = a.attr("href").trim()
                    val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                        ?: a.text().replace(Regex("""\s+"""), " ").trim()
                    if (label.isBlank() || !href.startsWith("http")) continue
                    if (Regex("""(?i)batch|zip\b""").containsMatchIn(label)) continue
                    if (!knownServer(href, label)) continue
                    links.add(shortServerName(label) to href)
                }
                sib = sib.nextElementSibling()
            }
            if (links.isNotEmpty() && !episodes.containsKey(num)) {
                episodes[num] = links
            }
        }
        return DrivePage(servers.distinctBy { it.second }, episodes)
    }

    private fun shortServerName(label: String): String {
        val clean = label.replace("&#x26a1;", "").replace(Regex("""\s+"""), " ").trim()
        return when {
            clean.contains("V-Cloud", true) -> "V-Cloud"
            clean.contains("V-Drive", true) || clean.contains("VegaDrive", true) -> "V-Drive"
            clean.contains("Filepress", true) || clean.contains("FilePress", true) -> "Filepress"
            clean.contains("G-Direct", true) -> "G-Direct"
            clean.contains("GoFile", true) -> "GoFile"
            clean.contains("Viking", true) -> "VikingFile"
            clean.contains("MegaUp", true) -> "MegaUp"
            clean.contains("Pixel", true) -> "PixelDrain"
            else -> clean.take(24)
        }
    }

    suspend fun resolveAll(label: String, url: String): List<Stream> {
        val host = hostOf(url)
        return try {
            when {
                host.contains("vcloud") -> resolveVCloud(url)
                host.contains("vegadrive") -> resolveVegaDrive(url)
                host.contains("filebee") || host.contains("filepress") || host.contains("fpgo") ->
                    resolveFilePress(url)
                host.contains("fastdl") -> resolveFastDl(url)
                host.contains("gofile") -> resolveGofile(url)
                host.contains("nexdrive") -> {
                    val page = fetchDrivePage(url) ?: return emptyList()
                    page.servers.flatMap { (name, href) -> resolveAll(name, href) }
                }
                else -> resolveGeneric(label, url)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveGeneric(label: String, url: String): List<Stream> {
        if (Regex("""\.(mkv|mp4|avi|mov)(\?|$)""").containsMatchIn(url) ||
            url.contains("googleusercontent") || url.contains(".r2.dev")
        ) {
            return listOf(Stream(label.ifBlank { "Direct" }, url))
        }
        val follow = try {
            app.get(url, headers = VegaNet.browserHeaders(), allowRedirects = false, timeout = 15L)
        } catch (_: Exception) {
            return emptyList()
        }
        val loc = follow.headers["location"]
        if (loc != null && loc.startsWith("http")) return resolveAll(label, loc)
        return emptyList()
    }

    private val vcloudTokenRegex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]""")
    private val plainUrlRegex = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""")

    // the target hides in a double base64 variable, the token page carries the r2 links
    private suspend fun resolveVCloud(url: String): List<Stream> {
        val first = VegaNet.fetchWithCf(url, timeout = 25L) ?: return emptyList()
        val base = VegaNet.originOf(first.url)
        var target: String? = vcloudTokenRegex.find(first.text)?.groupValues?.get(1)
            ?.let { VegaNet.decodeDoubleBase64(it) }
        if (target == null) {
            target = plainUrlRegex.find(first.text)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        }
        if (target == null) {
            val hop = Jsoup.parse(first.text).selectFirst("div.main h4 a")?.attr("href")?.trim()
            if (!hop.isNullOrBlank()) {
                val hopUrl = if (hop.startsWith("http")) hop else base + hop
                val hopRes = VegaNet.fetchWithCf(hopUrl, timeout = 25L)
                if (hopRes != null) {
                    target = vcloudTokenRegex.find(hopRes.text)?.groupValues?.get(1)
                        ?.let { VegaNet.decodeDoubleBase64(it) }
                }
            }
        }
        val tokenUrl = target?.takeIf { it.startsWith("http") } ?: return emptyList()

        val second = VegaNet.fetchWithCf(tokenUrl, timeout = 25L) ?: return emptyList()
        val doc = Jsoup.parse(second.text)
        val out = mutableListOf<Stream>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            val text = a.text().lowercase()
            when {
                href.contains(".r2.cloudflarestorage.com") ->
                    out.add(Stream("V-Cloud FSLv2", href))
                href.contains(".r2.dev") -> out.add(Stream("V-Cloud FSL", href))
                href.contains("gofile.io") -> out.addAll(resolveGofile(href))
                href.contains("pixeldrain") -> {
                    pixelFileUrl(second.text, href)?.let { out.add(Stream("V-Cloud PixelDrain", it)) }
                }
                text.contains("download") && href.startsWith("http") &&
                    !href.contains("telegram") && !href.contains("t.me") &&
                    !href.contains("vglist") -> out.add(Stream("V-Cloud", href))
            }
        }
        return out.distinctBy { it.url }
    }

    private val pxlRegex = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")

    private fun pixelFileUrl(pageHtml: String, buttonHref: String): String? {
        val pxl = pxlRegex.find(pageHtml)?.groupValues?.get(1)
        val link = pxl?.takeIf { it.startsWith("http") } ?: buttonHref
        if (!link.startsWith("http")) return null
        if (link.contains("download", true)) return link
        val id = link.substringBefore("?").substringBefore("#").substringAfterLast("/")
        if (id.isBlank()) return null
        return "${VegaNet.originOf(link)}/api/file/$id"
    }

    // one bridge provider per host, prepare warms the mirror and download-link returns the url
    private suspend fun resolveVegaDrive(url: String): List<Stream> {
        val page = try {
            app.get(url, headers = VegaNet.browserHeaders(), timeout = 20L)
        } catch (_: Exception) {
            return emptyList()
        }
        val base = VegaNet.originOf(page.url)
        val token = url.substringAfter("/s/").substringBefore("?").substringBefore("#")
            .substringAfter("/d/").substringBefore("/")
        if (token.isBlank() || token == url) return emptyList()

        val out = mutableListOf<Stream>()
        for (provider in listOf("skydrop", "pixeldrain", "buzzheavier", "transferit")) {
            val link = vegaProviderLink(base, token, provider) ?: continue
            when {
                provider == "skydrop" && link.contains("googleusercontent") ->
                    out.add(Stream("V-Drive 10Gbps", link))
                provider == "pixeldrain" && link.contains("pixeldrain") -> {
                    val id = link.substringBefore("?").substringBefore("#").substringAfterLast("/")
                    if (id.isNotBlank()) {
                        out.add(Stream("V-Drive PixelDrain", "https://pixeldrain.com/api/file/$id"))
                    }
                }
                provider == "buzzheavier" && (link.contains("bzzhr") || link.contains("buzzheavier")) ->
                    out.add(Stream("V-Drive Buzzheavier", link))
                provider == "transferit" && link.startsWith("http") ->
                    out.add(Stream("V-Drive Transferit", link))
            }
        }
        return out.distinctBy { it.url }
    }

    private suspend fun vegaProviderLink(base: String, token: String, provider: String): String? {
        val headers = VegaNet.browserHeaders("$base/s/$token")
        return try {
            repeat(2) { attempt ->
                val prep = app.get(
                    "$base/__vega_bridge/prepare/$token?provider=$provider",
                    headers = headers,
                    timeout = 15L
                )
                val ready = try {
                    JSONObject(prep.text).optBoolean("ready", false)
                } catch (_: Exception) {
                    false
                }
                if (ready) return@repeat
                if (attempt == 0) delay(1200L)
            }
            val res = app.get(
                "$base/download-link/$token/$provider",
                headers = headers,
                timeout = 15L
            )
            JSONObject(res.text).optString("download_url").takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private val filePressId = Regex("""/file/([a-f0-9]{16,40})""")

    // the whole api sits on the short domain, the react page only adds a turnstile
    private suspend fun resolveFilePress(url: String): List<Stream> {
        val id = filePressId.find(url)?.groupValues?.get(1) ?: return emptyList()
        val base = "https://filebee.xyz"
        val headers = VegaNet.browserHeaders("$base/")

        val info = try {
            val res = app.get("$base/api/file/get/$id", headers = headers, timeout = 15L)
            if (!res.isSuccessful) return emptyList()
            JSONObject(res.text).optJSONObject("data") ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(info.optString("name").trim())) {
            return emptyList()
        }

        val out = mutableListOf<Stream>()

        val dotflix = filePressDownload(id, "dotFlixDownlaod", base)
        if (dotflix != null && dotflix.startsWith("http")) {
            resolveDotFlix(dotflix)?.let { out.add(Stream("Filepress Instant", it)) }
        }

        val telegram = filePressDownload(id, "telegramDownload", base)
        if (telegram != null && telegram.startsWith("http")) {
            out.add(Stream("Filepress Telegram", telegram))
        }

        val task = filePressDownload(id, "indexDownlaod", base)
        if (task != null && task.matches(Regex("[a-f0-9]{16,40}"))) {
            filePressFinal(task, base)?.let { out.add(Stream("Filepress Direct", it)) }
        }
        return out.distinctBy { it.url }
    }

    private suspend fun filePressDownload(id: String, method: String, base: String): String? {
        return try {
            val res = app.post(
                "$base/api/file/downlaod/",
                headers = VegaNet.browserHeaders("$base/").toMutableMap().apply {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put("Origin", base)
                },
                json = JSONObject()
                    .put("captchaValue", "")
                    .put("id", id)
                    .put("method", method)
                    .toString(),
                timeout = 25L
            )
            if (!res.isSuccessful) return null
            val parsed = JSONObject(res.text)
            if (!parsed.optBoolean("status")) return null
            (parsed.opt("data") as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun filePressFinal(taskId: String, base: String): String? {
        return try {
            val res = app.post(
                "$base/api/file/downlaod2/",
                headers = VegaNet.browserHeaders("$base/").toMutableMap().apply {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put("Origin", base)
                },
                json = JSONObject()
                    .put("captchaValue", JSONObject.NULL)
                    .put("id", taskId)
                    .put("method", "indexDownlaod")
                    .toString(),
                timeout = 35L
            )
            if (!res.isSuccessful) return null
            val parsed = JSONObject(res.text)
            if (!parsed.optBoolean("status")) return null
            when (val data = parsed.opt("data")) {
                is String -> data.takeIf { it.startsWith("http") }
                is org.json.JSONArray -> (0 until data.length())
                    .firstNotNullOfOrNull { i -> data.optString(i).takeIf { it.startsWith("http") } }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // the share page carries a per file code in a btoa call, reversed base64 goes to the extract endpoint
    private suspend fun resolveDotFlix(shareUrl: String): String? {
        return try {
            val text = app.get(
                shareUrl,
                headers = VegaNet.browserHeaders("https://new3.dotflix.shop/"),
                timeout = 20L
            ).text
            val code = Regex("""btoa\('([^']+)'\)""").find(text)?.groupValues?.get(1) ?: return null
            val obfuscated = java.util.Base64.getEncoder()
                .encodeToString(code.toByteArray()).reversed()
            val requestId = List(13) { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            val timestamp = System.currentTimeMillis().toString()
            val res = app.post(
                "https://dotflix.fit/api/extract-download",
                headers = VegaNet.browserHeaders("https://new3.dotflix.shop/").toMutableMap().apply {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put("X-Request-ID", requestId)
                    put("X-Timestamp", timestamp)
                    put("Origin", "https://new3.dotflix.shop")
                },
                json = JSONObject()
                    .put("requestId", requestId)
                    .put("timestamp", timestamp)
                    .put("data", obfuscated)
                    .toString(),
                timeout = 25L
            )
            if (!res.isSuccessful) return null
            val parsed = JSONObject(res.text)
            if (!parsed.optBoolean("success")) return null
            parsed.optString("downloadUrl").takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    // the embed page wraps the google url in a redirect variable, the link= param is the file
    private suspend fun resolveFastDl(url: String): List<Stream> {
        return try {
            val res = app.get(url, headers = VegaNet.browserHeaders(), timeout = 20L)
            val text = res.text
            val reurl = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                ?: Regex("""'(https://fastdl[^']*dl\.php\?link=[^']+)'""").find(text)?.groupValues?.get(1)
                ?: return emptyList()
            val carrier = Regex("""[?&]r=([A-Za-z0-9+/=_-]+)""").find(reurl)?.groupValues?.get(1)?.let { wrapped ->
                val padded = if (wrapped.length % 4 > 0) {
                    wrapped + "=".repeat(4 - wrapped.length % 4)
                } else wrapped
                runCatching { com.lagradost.cloudstream3.base64Decode(padded) }.getOrNull()
            } ?: reurl
            val encoded = Regex("""link=(https?://[^&"']+)""").find(carrier)?.groupValues?.get(1)
                ?: return emptyList()
            val direct = URLDecoder.decode(encoded, "UTF-8")
            if (!direct.startsWith("http")) return emptyList()
            listOf(Stream("G-Direct", direct, mapOf("Referer" to "${VegaNet.originOf(url)}/")))
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun resolveGofile(url: String): List<Stream> {
        val code = url.substringAfterLast("/").substringBefore("#").substringBefore("?")
        if (code.isBlank()) return emptyList()
        return try {
            val api = "https://api.gofile.io"
            val token = try {
                JSONObject(app.post("$api/accounts", timeout = 15L).text)
                    .getJSONObject("data").getString("token")
            } catch (_: Exception) {
                return emptyList()
            }
            val wt = try {
                Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                    app.get("$api/dist/js/global.js", timeout = 15L).text
                )?.groupValues?.get(1)
            } catch (_: Exception) {
                null
            }
            val contentUrl = if (wt.isNullOrBlank()) "$api/contents/$code?wt=$token" else "$api/contents/$code?wt=$wt"
            val data = try {
                JSONObject(
                    app.get(contentUrl, headers = mapOf("Authorization" to "Bearer $token"), timeout = 20L).text
                ).getJSONObject("data")
            } catch (_: Exception) {
                return emptyList()
            }
            val children = data.optJSONObject("children") ?: return emptyList()
            val out = mutableListOf<Stream>()
            for (key in children.keys()) {
                val child = children.optJSONObject(key) ?: continue
                val link = child.optString("link").takeIf { it.startsWith("http") } ?: continue
                if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(child.optString("name"))) continue
                val size = child.optLong("size", 0L)
                val sizeText = if (size > 0) " ${size / 1024 / 1024}MB" else ""
                out.add(Stream("GoFile$sizeText", link))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val emitted = ConcurrentHashMap.newKeySet<String>()

    fun emit(
        source: String,
        quality: Int,
        streams: List<Stream>,
        callback: (ExtractorLink) -> Unit
    ) {
        for (s in streams) {
            if (!s.url.startsWith("http")) continue
            if (!emitted.add(s.url)) continue
            callback(
                ExtractorLink(
                    source = source,
                    name = s.name,
                    url = s.url,
                    referer = "",
                    quality = quality,
                    type = ExtractorLinkType.VIDEO,
                    headers = s.headers
                )
            )
        }
    }

    fun resetSession() {
        emitted.clear()
    }

    suspend fun resolveMany(
        items: List<Pair<String, String>>,
        source: String,
        qualityOf: (String) -> Int,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val gate = Semaphore(4)
        items.distinctBy { it.second }.forEach { (label, url) ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    try {
                        val streams = resolveAll(label, url)
                        emit(source, qualityOf(label), streams, callback)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
