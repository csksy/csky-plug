package com.vegamovies

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

internal object VegaSources {

    class Mirror(val url: String, val headers: Map<String, String> = emptyMap())

    // megaup hands out an html page instead of the file and vikingfile sits behind a turnstile captcha
    private val deadHosts = Regex("""(?i)megaup|viking|vik1ng|telegram|t\.me""")

    fun familyOf(href: String): String? {
        if (!href.startsWith("http") || deadHosts.containsMatchIn(href)) return null
        val h = href.lowercase()
        return when {
            h.contains("vcloud") -> "V-Cloud"
            h.contains("vegadrive") -> "V-Drive"
            h.contains("fastdl") -> "G-Direct"
            h.contains("filebee") || h.contains("filepress") || h.contains("fpgo") -> "Filepress"
            h.contains("gofile") -> "GoFile"
            h.contains("hubcloud") -> "Hub-Cloud"
            else -> null
        }
    }

    fun isServerLink(href: String): Boolean {
        return href.startsWith("http") &&
            (href.contains("nexdrive", true) || familyOf(href) != null)
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
            if (label.isBlank() || !isServerLink(href)) continue
            if (Regex("""(?i)batch|zip\b""").containsMatchIn(label)) continue
            if (href.contains("vglist")) continue
            servers.add(label to href)
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
                    if (label.isBlank() || !isServerLink(href)) continue
                    if (Regex("""(?i)batch|zip\b""").containsMatchIn(label)) continue
                    links.add(label to href)
                }
                sib = sib.nextElementSibling()
            }
            if (links.isNotEmpty() && !episodes.containsKey(num)) {
                episodes[num] = links
            }
        }
        return DrivePage(servers.distinctBy { it.second }, episodes)
    }

    suspend fun mirrorsFor(family: String, url: String): List<Mirror> {
        return try {
            when (family) {
                "V-Cloud" -> vcloudMirrors(url)
                "V-Drive" -> vegadriveMirrors(url)
                "G-Direct" -> fastdlMirrors(url)
                "Filepress" -> filepressMirrors(url)
                "GoFile" -> gofileMirrors(url)
                "Hub-Cloud" -> hubcloudMirrors(url)
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val vcloudTokenRegex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]""")
    private val plainUrlRegex = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""")

    // the share page hides the token page in a double base64 variable, the token page carries the mirrors
    private suspend fun vcloudTarget(url: String): String? {
        val first = VegaNet.fetchWithCf(url, timeout = 25L) ?: return null
        val base = VegaNet.originOf(first.url)
        var target = vcloudTokenRegex.find(first.text)?.groupValues?.get(1)
            ?.let { VegaNet.decodeDoubleBase64(it) }
            ?: plainUrlRegex.find(first.text)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        if (target == null) {
            val hop = Jsoup.parse(first.text).selectFirst("div.main h4 a")?.attr("href")?.trim() ?: return null
            val hopUrl = if (hop.startsWith("http")) hop else base + hop
            val hopRes = VegaNet.fetchWithCf(hopUrl, timeout = 25L) ?: return null
            target = vcloudTokenRegex.find(hopRes.text)?.groupValues?.get(1)
                ?.let { VegaNet.decodeDoubleBase64(it) }
        }
        return target?.takeIf { it.startsWith("http") }
    }

    private suspend fun vcloudMirrors(url: String): List<Mirror> {
        val target = vcloudTarget(url) ?: return emptyList()
        val page = VegaNet.fetchWithCf(target, timeout = 25L) ?: return emptyList()
        val r2dev = mutableListOf<String>()
        val r2signed = mutableListOf<String>()
        val generated = mutableListOf<String>()
        var pixel: String? = null
        for (a in Jsoup.parse(page.text).select("a[href]")) {
            val href = a.attr("href").trim()
            when {
                href.contains(".r2.dev") -> r2dev.add(href)
                href.contains(".r2.cloudflarestorage.com") -> r2signed.add(href)
                href.contains("pixeldrain") -> pixel = pixelFileUrl(page.text, href)
                href.contains("gofile.io") -> Unit
                a.text().contains("download", true) && href.startsWith("http") -> generated.add(href)
            }
        }
        val out = mutableListOf<Mirror>()
        r2dev.forEach { out.add(Mirror(it)) }
        r2signed.forEach { out.add(Mirror(it)) }
        for (href in generated) {
            if (Regex("""\.(mkv|mp4|avi|mov)(\?|$)""").containsMatchIn(href) ||
                href.contains("googleusercontent")
            ) {
                out.add(Mirror(href))
            } else {
                gpdlDirect(href)?.let { out.add(Mirror(it)) }
            }
        }
        pixel?.let { out.add(Mirror(it)) }
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

    // the gpdl button bounces through a worker and a generator page that only names the file in its query
    private suspend fun gpdlDirect(url: String): String? {
        var cur = url
        try {
            for (i in 0 until 5) {
                val res = app.get(cur, headers = VegaNet.browserHeaders(), allowRedirects = false, timeout = 15L)
                val loc = res.headers["location"]
                if (loc.isNullOrBlank() || res.code !in 301..308) break
                cur = if (loc.startsWith("http")) loc else VegaNet.originOf(cur) + loc
            }
        } catch (_: Exception) {
            return null
        }
        for (pair in cur.substringAfter("?", "").split("&")) {
            if (pair.substringBefore("=") != "link") continue
            val value = pair.substringAfter("=")
            val direct = if (value.contains("%")) URLDecoder.decode(value, "UTF-8") else value
            return direct.takeIf { it.startsWith("http") }
        }
        return null
    }

    // vegadrive only answers skydrop on the json api and mints the google mirror asynchronously
    private suspend fun vegadriveMirrors(url: String): List<Mirror> {
        val page = try {
            app.get(url, headers = VegaNet.browserHeaders("https://nexdrive.beer/"), timeout = 20L)
        } catch (_: Exception) {
            return emptyList()
        }
        val base = VegaNet.originOf(page.url)
        val token = url.substringAfter("/s/").substringBefore("?").substringBefore("#")
            .substringAfter("/d/").substringBefore("/")
        if (token.isBlank() || token == url) return emptyList()
        for (attempt in 0 until 3) {
            val res = try {
                app.get(
                    "$base/download-link/$token/skydrop",
                    headers = VegaNet.browserHeaders("$base/s/$token"),
                    timeout = 15L
                )
            } catch (_: Exception) {
                return emptyList()
            }
            val parsed = runCatching { JSONObject(res.text) }.getOrNull()
            val link = parsed?.optString("download_url").orEmpty()
            if (res.code == 200 && link.startsWith("http")) return listOf(Mirror(link))
            if (res.code != 202) return emptyList()
            delay((parsed?.optInt("retry_after_ms", 2500) ?: 2500).toLong().coerceAtMost(3000L))
        }
        return emptyList()
    }

    // the embed page wraps the google url in a redirect variable, the link= param is the file
    private suspend fun fastdlMirrors(url: String): List<Mirror> {
        return try {
            val res = app.get(url, headers = VegaNet.browserHeaders(), timeout = 20L)
            val reurl = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(res.text)?.groupValues?.get(1)
                ?: Regex("""'(https://fastdl[^']*dl\.php\?link=[^']+)'""").find(res.text)?.groupValues?.get(1)
                ?: return emptyList()
            val carrier = Regex("""[?&]r=([A-Za-z0-9+/=_-]+)""").find(reurl)?.groupValues?.get(1)?.let { wrapped ->
                val padded = if (wrapped.length % 4 > 0) {
                    wrapped + "=".repeat(4 - wrapped.length % 4)
                } else wrapped
                runCatching { base64Decode(padded) }.getOrNull()
            } ?: reurl
            val encoded = Regex("""link=(https?://[^&"']+)""").find(carrier)?.groupValues?.get(1)
                ?: return emptyList()
            val direct = URLDecoder.decode(encoded, "UTF-8")
            if (direct.startsWith("http")) listOf(Mirror(direct)) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val filePressId = Regex("""/file/([a-f0-9]{16,40})""")

    // the react page only adds a turnstile, the whole api sits on the short domain
    private suspend fun filepressMirrors(url: String): List<Mirror> {
        val id = filePressId.find(url)?.groupValues?.get(1) ?: return emptyList()
        val base = "https://filebee.xyz"
        val out = mutableListOf<Mirror>()

        val task = filepressTask(id, "indexDownlaod", base)
        if (task != null && task.matches(Regex("[a-f0-9]{16,40}"))) {
            filepressFinal(task, base)?.let { out.add(Mirror(it)) }
        } else if (task != null && task.startsWith("http")) {
            out.add(Mirror(task))
        }

        val share = filepressTask(id, "dotFlixDownlaod", base)
        if (share != null && share.startsWith("http")) {
            resolveDotFlix(share)?.let { out.add(Mirror(it)) }
        }
        return out.distinctBy { it.url }
    }

    private suspend fun filepressTask(id: String, method: String, base: String): String? {
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

    private suspend fun filepressFinal(taskId: String, base: String): String? {
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

    // gofile folders hold sample files next to the video, the biggest child is the movie
    private suspend fun gofileMirrors(url: String): List<Mirror> {
        val code = url.substringAfterLast("/").substringBefore("#").substringBefore("?")
        if (code.isBlank()) return emptyList()
        return try {
            val api = "https://api.gofile.io"
            val token = JSONObject(app.post("$api/accounts", timeout = 15L).text)
                .getJSONObject("data").getString("token")
            val wt = runCatching {
                Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                    app.get("$api/dist/js/global.js", timeout = 15L).text
                )?.groupValues?.get(1)
            }.getOrNull()
            val contentUrl = if (wt.isNullOrBlank()) "$api/contents/$code?wt=$token" else "$api/contents/$code?wt=$wt"
            val data = JSONObject(
                app.get(contentUrl, headers = mapOf("Authorization" to "Bearer $token"), timeout = 20L).text
            ).getJSONObject("data")
            val children = data.optJSONObject("children") ?: return emptyList()
            var best: String? = null
            var bestSize = -1L
            for (key in children.keys()) {
                val child = children.optJSONObject(key) ?: continue
                if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(child.optString("name"))) continue
                val link = child.optString("link").takeIf { it.startsWith("http") } ?: continue
                if (child.optLong("size", 0L) > bestSize) {
                    bestSize = child.optLong("size", 0L)
                    best = link
                }
            }
            best?.let { listOf(Mirror(it)) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // the drive page only exposes a generate link, the generated page carries the real mirrors
    private suspend fun hubcloudMirrors(url: String): List<Mirror> {
        val res = try {
            app.get(url, headers = VegaNet.browserHeaders(), timeout = 20L)
        } catch (_: Exception) {
            return emptyList()
        }
        val doc = Jsoup.parse(res.text)
        val header = doc.selectFirst("div.card-header")?.text()?.trim().orEmpty()
        if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(header)) return emptyList()
        val generate = doc.select("a.btn, a[download]")
            .firstOrNull { it.text().contains("generate", true) }
            ?.attr("href")?.trim() ?: return emptyList()
        val genUrl = if (generate.startsWith("http")) generate else VegaNet.originOf(res.url) + generate
        val genRes = try {
            app.get(genUrl, headers = VegaNet.browserHeaders(res.url), timeout = 25L)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<Mirror>()
        for (a in Jsoup.parse(genRes.text).select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http")) continue
            if (Regex("""(?i)tinyurl|t\.me|telegram|one\.one\.one\.one""").containsMatchIn(href)) continue
            when {
                href.contains(".r2.cloudflarestorage.com") || href.contains(".r2.dev") -> out.add(Mirror(href))
                href.contains("gpdl") -> gpdlDirect(href)?.let { out.add(Mirror(it)) }
            }
        }
        return out.distinctBy { it.url }
    }

    private val verified = ConcurrentHashMap.newKeySet<String>()

    fun resetSession() {
        verified.clear()
    }

    // a link that answers a ranged get is a file the player can open, html pages and dead mirrors are not
    suspend fun alive(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        if (!url.startsWith("http")) return false
        if (url in verified) return true
        val ok = try {
            val h = VegaNet.browserHeaders().toMutableMap()
            h.putAll(headers)
            h["Range"] = "bytes=0-1023"
            app.get(url, headers = h, timeout = 12L).code in 200..399
        } catch (_: Exception) {
            false
        }
        if (ok) verified.add(url)
        return ok
    }
}
