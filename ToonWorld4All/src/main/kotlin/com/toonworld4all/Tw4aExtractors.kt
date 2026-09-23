package com.toonworld4all

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal object Tw4aExtractors {

    private const val TAG = "TW4A"

    private val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val rawClient: OkHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    private fun absolute(href: String, base: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("/") -> base.trimEnd('/') + href
        else -> base.trimEnd('/') + "/" + href
    }

    private suspend fun emit(
        callback: (ExtractorLink) -> Unit,
        source: String,
        name: String,
        url: String,
        quality: Int,
        referer: String? = null,
        headers: Map<String, String>? = null,
    ) {
        if (url.isBlank() || !url.startsWith("http")) return
        callback.invoke(
            newExtractorLink(source, name, url, ExtractorLinkType.VIDEO) {
                this.quality = if (quality > 0) quality else Qualities.Unknown.value
                this.referer = referer ?: ""
                if (headers != null) this.headers = headers
            }
        )
    }

    private fun fileSlug(url: String): String {
        val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
        return clean.substringAfterLast('/')
    }

    private fun hostOfUrl(url: String): String = tw4aHostOf(url)

    private fun pixeldrainDirect(link: String): String {
        if (link.contains("download", true)) return link
        val id = link.substringBefore("?").substringAfterLast("/")
        return "https://pixeldrain.com/api/file/$id?download"
    }

    internal suspend fun extractDriveFamily(
        url: String,
        quality: Int,
        label: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val response = tw4aGet(url, mapOf("User-Agent" to UA), timeout = 30_000L)
            val host = hostOfUrl(response.url)
            val base = "https://$host"
            var html = response.text

            val jsRedirect = Regex("""window\.location\.replace\(['"]([^'"]+)['"]\)""")
                .find(html)?.groupValues?.get(1)
            if (jsRedirect != null && jsRedirect.contains("/file/")) {
                val second = tw4aGet(
                    absolute(jsRedirect, base),
                    mapOf("User-Agent" to UA, "Referer" to response.url),
                    timeout = 30_000L
                )
                html = second.text
            }

            val doc = Jsoup.parse(html)
            val title = doc.selectFirst("title")?.text().orEmpty()
            if (title.contains("Google Drive Files Sharing Platform") && !html.contains("Instant DL") && !html.contains("/cloud/") && !html.contains("/wfile/")) {
                return
            }

            val info = listOf(
                doc.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ").trim(),
                doc.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").trim()
            ).filter { it.isNotBlank() }.joinToString(" ")
            val nameInfo = info.ifBlank { label }

            val slug = fileSlug(url)
            var emitted = false
            val emittedUrls = mutableSetOf<String>()

            if (slug.isNotBlank()) {
                val wfileLinks = resolveWfileLinks(base, slug)
                for (link in wfileLinks) {
                    if (emittedUrls.add(link)) {
                        emit(callback, sourceName, "$sourceName Direct $nameInfo", link, quality)
                        emitted = true
                    }
                }
            }

            for (anchor in doc.select("div.text-center a, div.card-body a, a.btn, a[href]")) {
                val text = anchor.text()
                val link = anchor.attr("href").trim()
                if (link.isBlank()) continue
                when {
                    text.contains("Instant DL") || link.contains("instant.busycdn") -> {
                        val direct = resolveBusycdn(absolute(link, base))
                        if (!direct.isNullOrBlank() && emittedUrls.add(direct)) {
                            emit(callback, sourceName, "$sourceName Instant $nameInfo", direct, quality, referer = base)
                            emitted = true
                        }
                    }

                    text.contains("FAST CLOUD") || link.contains("/cloud/") -> {
                        val ok = resolveCloudChain(absolute(link, base), host, quality, nameInfo, sourceName, callback, emittedUrls)
                        emitted = ok || emitted
                    }

                    text.contains("GD Index") || link.contains("/wfile/") -> {
                        val ok = resolveWfilePage(absolute(link, base), sourceName, nameInfo, quality, callback, emittedUrls)
                        emitted = ok || emitted
                    }

                    text.contains("GoFile") || link.contains("goflix") || link.contains("gofile") -> {
                        val gofile = absolute(link, base)
                        if (gofile.contains("gofile.io")) {
                            if (emittedUrls.add(gofile)) {
                                extractGofile(gofile, quality, "$sourceName GoFile $nameInfo", callback)
                                emitted = true
                            }
                        } else if (gofile.contains("/mirror/") || goflixSlug(gofile) != null) {
                            val resolved = resolveGoflixMirror(gofile)
                            if (resolved != null && emittedUrls.add(resolved)) {
                                extractGofile(resolved, quality, "$sourceName GoFile $nameInfo", callback)
                                emitted = true
                            }
                        }
                    }

                    link.contains("pixeldra") -> {
                        val final = pixeldrainDirect(absolute(link, base))
                        if (emittedUrls.add(final)) {
                            emit(callback, sourceName, "$sourceName Pixeldrain $nameInfo", final, quality)
                            emitted = true
                        }
                    }

                    (link.contains("drive.google") || link.contains("googleusercontent") ||
                            link.contains(".mp4") || link.contains(".mkv")) && link.startsWith("http") -> {
                        if (emittedUrls.add(link)) {
                            emit(callback, sourceName, "$sourceName ${text.ifBlank { nameInfo }}", link, quality)
                            emitted = true
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.d(TAG, "drive family failed for $url: ${e.message}")
        }
    }

    private fun goflixSlug(url: String): String? {
        val m = Regex("""/mirror/([a-f0-9]{16,})""").find(url)
        return m?.groupValues?.get(1)
    }

    private suspend fun resolveWfileLinks(base: String, slug: String): List<String> {
        val urls = mutableListOf<String>()
        try {
            coroutineScope {
                val jobs = listOf("", "?type=1", "?type=2").map { suffix ->
                    async {
                        try {
                            val page = tw4aGet(
                                "$base/wfile/$slug$suffix",
                                mapOf("User-Agent" to UA, "Referer" to "$base/file/$slug"),
                                timeout = 20_000L
                            )
                            val doc = Jsoup.parse(page.text)
                            doc.select("a.btn-success, a.btn[href*='workers.dev'], a[href*='workers.dev']").map { it.attr("href").trim() }
                                .filter { it.startsWith("http") }
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
                jobs.forEach { job ->
                    runCatching { urls.addAll(job.await()) }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "wfile failed: ${e.message}")
        }
        return urls.distinct()
    }

    private suspend fun resolveWfilePage(
        wfileUrl: String,
        sourceName: String,
        nameInfo: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
    ): Boolean {
        var emitted = false
        val targets = mutableListOf(wfileUrl)
        for (suffix in listOf("?type=1", "?type=2")) {
            targets.add(wfileUrl + suffix)
        }
        for (target in targets) {
            try {
                val page = tw4aGet(target, mapOf("User-Agent" to UA), timeout = 20_000L)
                val doc = Jsoup.parse(page.text)
                for (btn in doc.select("a.btn-success, a[href*='workers.dev']")) {
                    val href = btn.attr("href").trim()
                    if (href.startsWith("http") && seen.add(href)) {
                        emit(callback, sourceName, "$sourceName GDIndex $nameInfo", href, quality)
                        emitted = true
                    }
                }
            } catch (e: Exception) {
            }
        }
        return emitted
    }

    private suspend fun resolveGoflixMirror(url: String): String? {
        return try {
            val page = tw4aGet(url, mapOf("User-Agent" to UA), timeout = 20_000L)
            val doc = Jsoup.parse(page.text)
            doc.select("a[href*='gofile.io']").firstOrNull()?.attr("href")?.trim()?.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveBusycdn(instantUrl: String): String? {
        return try {
            val resp = app.get(instantUrl, allowRedirects = false, timeout = 20_000L)
            val loc = resp.headers["location"] ?: resp.headers["Location"]
            if (loc.isNullOrBlank()) return null
            if (loc.contains("url=")) {
                return java.net.URLDecoder.decode(loc.substringAfter("url="), "UTF-8")
            }
            if (loc.startsWith("http")) loc else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun multipartPost(host: String, url: String, parts: Map<String, String>): String? {
        val sessionHeaders = tw4aSessionHeaders(host)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply { parts.forEach { (k, v) -> addFormDataPart(k, v) } }
            .build()
        val req = Request.Builder().url(url)
            .header("User-Agent", sessionHeaders["User-Agent"] ?: UA)
            .header("Accept", "*/*")
            .header("Origin", "https://$host")
            .header("Referer", url)
            .header("x-token", host)
            .apply { sessionHeaders["Cookie"]?.let { header("Cookie", it) } }
            .post(body)
            .build()
        return try {
            withContext(Dispatchers.IO) {
                rawClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveCloudChain(
        cloudPageUrl: String,
        host: String,
        quality: Int,
        label: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
    ): Boolean {
        try {
            val page = tw4aGet(cloudPageUrl, mapOf("User-Agent" to UA), timeout = 30_000L)
            val html = page.text
            if (html.contains("File Not Found") || html.contains("404")) {
                if (html.length < 400) return false
            }
            val key = Regex("""formData\.append\("key",\s*"([a-f0-9]{40})""").find(html)?.groupValues?.get(1) ?: return false
            val startBody = multipartPost(
                host, cloudPageUrl,
                mapOf("action" to "cloud", "key" to key, "action_token" to "")
            ) ?: return false
            val start = try { JSONObject(startBody) } catch (e: Exception) { return false }
            if (start.optBoolean("error", true)) return false
            val tokenPath = start.optString("url").takeIf { it.isNotBlank() } ?: return false
            val tokenUrl = absolute(tokenPath, "https://$host")
            tw4aGet(tokenUrl, mapOf("User-Agent" to UA), timeout = 20_000L)
            var redirect: String? = null
            for (i in 1..15) {
                val pollResp = try {
                    app.get(
                        "$tokenUrl&xhr=1",
                        headers = tw4aSessionHeaders(host) + mapOf(
                            "User-Agent" to UA,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "*/*",
                            "Referer" to tokenUrl
                        ),
                        timeout = 20_000L
                    )
                } catch (e: Exception) { null }
                val pollBody = pollResp?.text
                if (pollBody != null) {
                    val poll = try { JSONObject(pollBody) } catch (e: Exception) { null }
                    if (poll?.optBoolean("done", false) == true) {
                        redirect = poll.optString("redirect").takeIf { it.isNotBlank() }
                        break
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
            val redirectPath = redirect ?: return false
            val finalUrl = absolute(redirectPath, "https://$host")
            val finalDoc = Jsoup.parse(tw4aGet(finalUrl, mapOf("User-Agent" to UA), timeout = 30_000L).text)
            val dl = finalDoc.selectFirst("a[href*='workers.dev/'], a[href*='cloud-dl']")?.attr("href")?.trim() ?: return false
            if (seen.add(dl)) {
                emit(callback, sourceName, "$sourceName Cloud $label", absolute(dl, "https://$host"), quality)
                return true
            }
            return false
        } catch (e: Exception) {
            Log.d(TAG, "cloud chain failed: ${e.message}")
            return false
        }
    }

    internal suspend fun extractHubCloud(
        url: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val variants = hubcloudVariants(url)
            for (candidate in variants) {
                try {
                    val response = tw4aGet(candidate, mapOf("User-Agent" to UA), timeout = 25_000L)
                    var html = response.text
                    if (html.contains("File Not Found") && html.length < 200) continue

                    val host = hostOfUrl(response.url)
                    val base = "https://$host"

                    val jsRedirect = Regex("""window\.location\.replace\(['"]([^'"]+)['"]\)""")
                        .find(html)?.groupValues?.get(1)
                    if (jsRedirect != null && jsRedirect != response.url) {
                        val second = tw4aGet(
                            absolute(jsRedirect, base),
                            mapOf(
                                "User-Agent" to UA,
                                "Referer" to response.url,
                                "Cookie" to response.headers.values("set-cookie")
                                    .joinToString("; ") { it.substringBefore(";") }
                            ),
                            timeout = 25_000L
                        )
                        html = second.text
                    }
                    if (html.contains("File Not Found") && html.length < 200) continue

                    val doc = Jsoup.parse(html)
                    var dlPageUrl: String? = null
                    when {
                        response.url.contains("/video/") -> {
                            dlPageUrl = doc.selectFirst("div.vd > center > a")?.attr("href")
                        }
                        else -> {
                            val script = doc.selectFirst("script:containsData(url)")?.data().orEmpty()
                            dlPageUrl = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
                                .find(script)?.groupValues?.get(1)
                                ?.let { runCatching { base64Decode(base64Decode(it)) }.getOrNull() }
                                ?: Regex("""var\s+url\s*=\s*['"]([^'"]*)['"]""").find(script)?.groupValues?.get(1)
                        }
                    }
                    if (dlPageUrl.isNullOrBlank()) {
                        val emitted = emitHubServers(doc, base, quality, label, callback)
                        if (emitted) return
                        continue
                    }

                    val dlHtml = tw4aGet(
                        absolute(dlPageUrl, base),
                        mapOf("User-Agent" to UA, "Referer" to response.url),
                        timeout = 25_000L
                    ).text
                    if (emitHubServers(Jsoup.parse(dlHtml), base, quality, label, callback)) return
                } catch (e: Exception) {
                    Log.d(TAG, "hubcloud variant failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "hubcloud failed: ${e.message}")
        }
    }

    private fun hubcloudVariants(url: String): List<String> {
        val out = mutableListOf<String>()
        val clean = url.substringBefore("?")
        val slug = fileSlug(clean)
        out.add(url)
        if (slug.isBlank() || !clean.contains("hubcloud")) return out
        val pathKind = when {
            clean.contains("/video/") -> "video"
            clean.contains("/drive/") -> "drive"
            clean.contains("/file/") -> "file"
            else -> ""
        }
        val pathKinds = mutableListOf<String>()
        if (pathKind.isNotBlank()) pathKinds.add(pathKind)
        for (k in listOf("drive", "video", "file")) {
            if (k != pathKind) pathKinds.add(k)
        }
        val primaryDomain = when {
            clean.contains("hubcloud.foo") -> "https://hubcloud.foo"
            else -> "https://hubcloud.ist"
        }
        val otherDomain = if (primaryDomain == "https://hubcloud.ist") "https://hubcloud.foo" else "https://hubcloud.ist"
        for (domain in listOf(primaryDomain, otherDomain)) {
            for (kind in pathKinds) {
                val candidate = "$domain/$kind/$slug"
                if (candidate != clean) out.add(candidate)
            }
        }
        return out.distinct()
    }

    private suspend fun emitHubServers(
        doc: Document,
        base: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var emitted = false
        val headerText = doc.selectFirst("div.card-header")?.text().orEmpty()
        val size = doc.selectFirst("i#size")?.text().orEmpty()
        val pageTitle = listOf(headerText, size).filter { it.isNotBlank() }.joinToString(" ")

        for (btn in doc.select("a.btn")) {
            val text = btn.text()
            val link = btn.attr("href")
            if (link.isBlank()) continue
            when {
                text.contains("FSL Server") -> {
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud FSL $label", absolute(link, base), quality); emitted = true
                }

                text.contains("FSLv2") -> {
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud FSLv2 $label", absolute(link, base), quality); emitted = true
                }

                text.contains("Mega Server") -> {
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud Mega $label", absolute(link, base), quality); emitted = true
                }

                text.contains("ZipDisk") -> {
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud ZipDisk $label", absolute(link, base), quality); emitted = true
                }

                text.contains("Download File") -> {
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud ${pageTitle.ifBlank { label }}", absolute(link, base), quality); emitted = true
                }

                link.contains("pixeldrain") || text.contains("PixelServer") -> {
                    val pxl = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
                        .find(doc.toString())?.groupValues?.get(1) ?: link
                    val final = if (pxl.contains("download", true)) pxl
                    else "https://pixeldrain.com/api/file/${pxl.substringAfterLast("/")}?download"
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud Pixeldrain $label", final, quality); emitted = true
                }

                text.contains("Server : 10Gbps") -> {
                    val resolved = resolveFinalUrl(absolute(link, base)) ?: continue
                    val clean = if (resolved.contains("link=")) resolved.substringAfter("link=") else resolved
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud 10Gbps $label", clean, quality); emitted = true
                }

                text.contains("Gofile", true) -> {
                    extractGofile(absolute(link, base), quality, "HubCloud Gofile $label", callback); emitted = true
                }

                text.contains("Download") && link.startsWith("http") -> {
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud ${text.substringAfter("Download").trim(' ', '[', ']')} $label", link, quality); emitted = true
                }
            }
        }
        return emitted
    }

    private suspend fun resolveFinalUrl(startUrl: String): String? {
        var current = startUrl
        repeat(7) {
            try {
                val res = app.head(
                    current, allowRedirects = false, timeout = 5000L,
                    headers = mapOf("User-Agent" to UA)
                )
                if (res.code in 300..399) {
                    val location = res.headers["location"] ?: return current
                    if (!location.startsWith("http")) return current
                    current = location
                } else {
                    return current
                }
            } catch (e: Exception) {
                return null
            }
        }
        return current
    }

    private suspend fun extractGofile(
        url: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val code = Regex("""/(?:\?c=|d/)([\da-zA-Z-]+)""").find(url)?.groupValues?.get(1)
                ?: url.trimEnd('/').substringAfterLast('/')
            if (code.isBlank()) return

            val apiHeaders = mapOf(
                "User-Agent" to UA,
                "Accept" to "application/json",
            )
            val token = JSONObject(
                app.post("https://api.gofile.io/accounts", headers = apiHeaders).text
            ).getJSONObject("data").getString("token")

            val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                app.get("https://gofile.io/dist/js/global.js", headers = apiHeaders).text
            )?.groupValues?.get(1)

            val content = app.get(
                "https://api.gofile.io/contents/$code" + (if (wt != null) "?wt=$wt" else ""),
                headers = apiHeaders.toMutableMap().apply { put("Authorization", "Bearer $token") }
            ).text
            val children = JSONObject(content).getJSONObject("data").getJSONObject("children")
            val fileObj = children.getJSONObject(children.keys().next())
            val link = fileObj.getString("link")
            val fileName = fileObj.optString("name", "")

            emit(
                callback, "ToonWorld4All GoFile",
                "GoFile ${fileName.ifBlank { label }}",
                link, quality,
                headers = mapOf("Authorization" to "Bearer $token")
            )
        } catch (e: Exception) {
            Log.d(TAG, "gofile failed: ${e.message}")
        }
    }

    internal suspend fun emitDirect(
        url: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        emit(callback, "ToonWorld4All", label, url, quality)
    }

    internal suspend fun emitMega(
        url: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        emit(callback, "ToonWorld4All MEGA", "MEGA $label (open in MEGA app)", url, quality)
    }
}
