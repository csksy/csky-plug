package com.toonworld4all

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

internal object Tw4aExtractors {

    private const val TAG = "TW4A"

    private val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val rawClient: OkHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    private fun absolute(href: String, base: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
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
        val id = link.substringBefore("?").substringBefore("#").substringAfterLast("/")
        return "https://pixeldrain.dev/api/file/$id?download"
    }

    internal suspend fun extractGdflix(
        url: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val response = tw4aGet(url, mapOf("User-Agent" to UA), timeout = 30_000L)
            var html = response.text
            var host = hostOfUrl(response.url)

            val jsRedirect = Regex("""window\.location\.replace\(['"]([^'"]+)['"]\)""")
                .find(html)?.groupValues?.get(1)
            if (jsRedirect != null && jsRedirect.contains("/file/")) {
                val second = tw4aGet(
                    absolute(jsRedirect, "https://$host"),
                    mapOf("User-Agent" to UA, "Referer" to response.url),
                    timeout = 30_000L
                )
                html = second.text
                host = hostOfUrl(second.url)
            }

            val doc = Jsoup.parse(html)
            val base = "https://$host"
            val title = doc.selectFirst("title")?.text().orEmpty()
            if (!title.contains("GDFlix") && !html.contains("Instant DL") &&
                !html.contains("/cloud/") && !html.contains("/wfile/") && !html.contains("r2.dev")
            ) {
                return
            }

            val info = listOf(
                doc.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ").trim(),
                doc.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").trim()
            ).filter { it.isNotBlank() }.joinToString(" ")
            val nameInfo = info.ifBlank { label }

            val emittedUrls = mutableSetOf<String>()
            var emitted = false
            val slug = fileSlug(url)

            for (anchor in doc.select("a[href]")) {
                val text = anchor.text()
                val link = anchor.attr("href").trim()
                if (link.isBlank()) continue
                when {
                    link.contains(".r2.dev") -> {
                        if (emittedUrls.add(link)) {
                            emit(callback, "ToonWorld4All GDFlix", "GDFlix Cloud R2 $nameInfo", link, quality)
                            emitted = true
                        }
                    }

                    text.contains("Instant DL") || link.contains("instant.busycdn") -> {
                        val direct = resolveBusycdn(absolute(link, base))
                        if (!direct.isNullOrBlank() && emittedUrls.add(direct)) {
                            emit(callback, "ToonWorld4All GDFlix", "GDFlix Instant $nameInfo", direct, quality)
                            emitted = true
                        }
                    }
                }
            }

            for (anchor in doc.select("a[href]")) {
                val text = anchor.text()
                val link = anchor.attr("href").trim()
                if (link.isBlank()) continue
                when {
                    (text.contains("GD Index") || link.contains("/wfile/")) && !emitted -> {
                        val ok = resolveWfilePage(absolute(link, base), nameInfo, quality, callback, emittedUrls)
                        emitted = ok || emitted
                    }

                    text.contains("FAST CLOUD") || link.contains("/cloud/") -> {
                        val ok = resolveCloudChain(absolute(link, base), host, quality, nameInfo, callback, emittedUrls)
                        emitted = ok || emitted
                    }

                    text.contains("GoFile") || link.contains("gofile") || link.contains("multiup") -> {
                        val mirror = absolute(link, base)
                        val gofile = if (mirror.contains("gofile.io")) mirror else resolveGofileMirror(mirror)
                        if (gofile != null && emittedUrls.add(gofile)) {
                            extractGofile(gofile, quality, "GDFlix GoFile $nameInfo", callback)
                            emitted = true
                        }
                    }

                    link.contains("pixeldra") -> {
                        val final = pixeldrainDirect(absolute(link, base))
                        if (emittedUrls.add(final)) {
                            emit(callback, "ToonWorld4All GDFlix", "GDFlix Pixeldrain $nameInfo", final, quality)
                            emitted = true
                        }
                    }

                    (link.contains("googleusercontent") || link.contains(".mp4") || link.contains(".mkv")) &&
                            link.startsWith("http") -> {
                        if (emittedUrls.add(link)) {
                            emit(callback, "ToonWorld4All GDFlix", "GDFlix Direct $nameInfo", link, quality)
                            emitted = true
                        }
                    }
                }
            }

            if (!emitted && slug.isNotBlank()) {
                for (link in resolveWfileLinks(base, slug)) {
                    if (emittedUrls.add(link)) {
                        emit(callback, "ToonWorld4All GDFlix", "GDFlix GDIndex $nameInfo", link, quality)
                        emitted = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "gdflix failed for $url: ${e.message}")
        }
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
                            Jsoup.parse(page.text).select("a.btn-success, a[href*='workers.dev']")
                                .map { it.attr("href").trim() }
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
        label: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
    ): Boolean {
        var emitted = false
        val targets = listOf(wfileUrl, wfileUrl + "?type=1", wfileUrl + "?type=2")
        for (target in targets) {
            try {
                val page = tw4aGet(target, mapOf("User-Agent" to UA), timeout = 20_000L)
                for (btn in Jsoup.parse(page.text).select("a.btn-success, a[href*='workers.dev']")) {
                    val href = btn.attr("href").trim()
                    if (href.startsWith("http") && seen.add(href)) {
                        emit(callback, "ToonWorld4All GDFlix", "GDFlix GDIndex $label", href, quality)
                        emitted = true
                    }
                }
            } catch (e: Exception) {
            }
        }
        return emitted
    }

    private suspend fun resolveGofileMirror(url: String): String? {
        return try {
            val page = tw4aGet(url, mapOf("User-Agent" to UA), timeout = 20_000L)
            Jsoup.parse(page.text).select("a[href*='gofile.io']").firstOrNull()
                ?.attr("href")?.trim()?.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveBusycdn(instantUrl: String): String? {
        return try {
            val resp = app.get(
                instantUrl, allowRedirects = false,
                headers = mapOf("User-Agent" to UA), timeout = 20_000L
            )
            val loc = resp.headers["location"] ?: resp.headers["Location"]
            if (loc.isNullOrBlank()) return null
            if (loc.contains("url=")) {
                return decodeUrlParam(loc.substringAfter("url="))
            }
            if (loc.startsWith("http")) loc else null
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeUrlParam(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        value
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
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
    ): Boolean {
        try {
            var page: com.lagradost.nicehttp.NiceResponse? = null
            for (attempt in 1..3) {
                val candidate = tw4aGet(cloudPageUrl, mapOf("User-Agent" to UA), timeout = 30_000L)
                if (candidate.code != 503) {
                    page = candidate
                    break
                }
                kotlinx.coroutines.delay(1200L)
            }
            val html = page?.text ?: return false
            val key = Regex("""formData\.append\("key",\s*"([a-f0-9]{40})"""")
                .find(html)?.groupValues?.get(1) ?: return false
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
            val dl = finalDoc.selectFirst("a[href*='workers.dev/'], a[href*='cloud-dl']")?.attr("href")?.trim()
                ?: return false
            if (seen.add(dl)) {
                emit(callback, "ToonWorld4All GDFlix", "GDFlix Cloud $label", absolute(dl, "https://$host"), quality)
                return true
            }
            return false
        } catch (e: Exception) {
            Log.d(TAG, "cloud chain failed: ${e.message}")
            return false
        }
    }

    internal suspend fun extractFilepress(
        url: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val response = tw4aGet(url, mapOf("User-Agent" to UA), timeout = 30_000L)
            val finalUrl = response.url
            val id = Regex("""/file/([A-Za-z0-9_-]+)""").find(finalUrl)?.groupValues?.get(1)
                ?: Regex("""/file/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
                ?: return
            val base = "https://${hostOfUrl(finalUrl)}"

            val apiHeaders = mapOf(
                "User-Agent" to UA,
                "Accept" to "application/json, text/plain, */*",
                "Origin" to base,
                "Referer" to "$base/file/$id",
            )

            var fileName = label
            var fileSize = ""
            try {
                val info = JSONObject(
                    app.get("$base/api/file/get/$id", headers = apiHeaders, timeout = 25_000L).text
                )
                val data = info.optJSONObject("data")
                fileName = data?.optString("name")?.takeIf { it.isNotBlank() } ?: label
                val sizeBytes = data?.optString("size")?.toLongOrNull()
                if (sizeBytes != null && sizeBytes > 0) {
                    fileSize = "%.2f MB".format(sizeBytes / (1024.0 * 1024.0))
                }
                data?.optJSONArray("alternativeSource")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val alt = arr.optJSONObject(i) ?: continue
                        val altUrl = alt.optString("url").takeIf { it.startsWith("http") } ?: continue
                        val altName = alt.optString("name").ifBlank { "Mirror" }
                        try {
                            loadExtractor(altUrl, "$base/file/$id", { }) { link ->
                                callback(link)
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "filepress $altName failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "filepress info failed: ${e.message}")
            }

            val nameInfo = listOf(fileName, fileSize).filter { it.isNotBlank() }.joinToString(" ")

            val postHeaders = apiHeaders.toMutableMap().apply {
                put("Content-Type", "application/json")
            }
            val token = try {
                val bodyOne = app.post(
                    "$base/api/file/downlaod/",
                    headers = postHeaders,
                    requestBody = JSONObject()
                        .put("captchaValue", "")
                        .put("id", id)
                        .put("method", "indexDownlaod")
                        .toString()
                        .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
                    timeout = 25_000L
                ).text
                JSONObject(bodyOne).optString("data").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.d(TAG, "filepress downlaod failed: ${e.message}")
                null
            } ?: return

            val link = try {
                val bodyTwo = app.post(
                    "$base/api/file/downlaod2/",
                    headers = postHeaders,
                    requestBody = JSONObject()
                        .put("captchaValue", JSONObject.NULL)
                        .put("id", token)
                        .put("method", "indexDownlaod")
                        .toString()
                        .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
                    timeout = 25_000L
                ).text
                val parsed = JSONObject(bodyTwo)
                when (val data = parsed.opt("data")) {
                    is String -> data.takeIf { it.startsWith("http") }
                    is org.json.JSONArray -> parsed.optJSONArray("data")?.optString(0)?.takeIf { it.startsWith("http") }
                    else -> null
                }
            } catch (e: Exception) {
                Log.d(TAG, "filepress downlaod2 failed: ${e.message}")
                null
            } ?: return

            emit(callback, "ToonWorld4All Filepress", "Filepress $nameInfo", link, quality, referer = base)
        } catch (e: Exception) {
            Log.d(TAG, "filepress failed for $url: ${e.message}")
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
                    if (html.contains("File Not Found") && html.length < 400) continue

                    var host = hostOfUrl(response.url)
                    var base = "https://$host"

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
                        host = hostOfUrl(second.url)
                        base = "https://$host"
                    }
                    if (html.contains("File Not Found") && html.length < 400) continue

                    val doc = Jsoup.parse(html)
                    val generator = doc.selectFirst("div.vd a[href*='hubcloud.php'], div.vd > center > a")
                        ?: doc.selectFirst("a[href*='sportverse']")
                        ?: doc.selectFirst("a[href*='gpdl2']")

                    val dlPageUrl = when {
                        generator != null -> generator.attr("href").trim().takeIf { it.startsWith("http") }
                        response.url.contains("/video/") ->
                            doc.selectFirst("div.vd > center > a")?.attr("href")?.trim()
                        else -> {
                            val script = doc.selectFirst("script:containsData(url)")?.data().orEmpty()
                            Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
                                .find(script)?.groupValues?.get(1)
                                ?.let { runCatching { base64Decode(base64Decode(it)) }.getOrNull() }
                                ?: Regex("""var\s+url\s*=\s*['"]([^'"]*)['"]""").find(script)?.groupValues?.get(1)
                        }
                    }

                    if (!dlPageUrl.isNullOrBlank() && (dlPageUrl.contains("sportverse") || dlPageUrl.contains("gpdl2") || dlPageUrl.contains("hubcloud.php"))) {
                        val dlHtml = tw4aGet(
                            absolute(dlPageUrl, base),
                            mapOf("User-Agent" to UA, "Referer" to response.url),
                            timeout = 25_000L
                        ).text
                        if (emitHubServers(Jsoup.parse(dlHtml), base, quality, label, callback)) return
                    }

                    if (emitHubServers(doc, base, quality, label, callback)) return
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
        val clean = url.substringBefore("?").substringBefore("#")
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
        for (k in listOf("video", "drive", "file")) {
            if (k != pathKind) pathKinds.add(k)
        }
        val primaryDomain = when {
            clean.contains("hubcloud.foo") -> "https://hubcloud.foo"
            clean.contains("hubcloud.cx") -> "https://hubcloud.cx"
            else -> "https://hubcloud.ist"
        }
        val domains = mutableListOf(primaryDomain)
        for (alt in listOf("https://hubcloud.ist", "https://hubcloud.cx", "https://hubcloud.foo")) {
            if (alt != primaryDomain) domains.add(alt)
        }
        for (domain in domains) {
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

        val anchors = mutableListOf<Element>()
        anchors.addAll(doc.select("a[download]"))
        anchors.addAll(doc.select("a.btn"))
        val seenHrefs = mutableSetOf<String>()

        for (btn in anchors) {
            val text = btn.text()
            val link = btn.attr("href").trim()
            if (link.isBlank() || !seenHrefs.add(link)) continue
            when {
                text.contains("PixelServer") || link.contains("pixeldrain") -> {
                    val final = pixeldrainDirect(link)
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud Pixeldrain $label", final, quality)
                    emitted = true
                }

                text.contains("Server : 10Gbps") || link.contains("gpdl2") -> {
                    val resolved = resolveFinalUrl(absolute(link, base)) ?: continue
                    val direct = if (resolved.contains("link=")) {
                        decodeUrlParam(resolved.substringAfter("link="))
                    } else resolved
                    if (direct.startsWith("http")) {
                        emit(callback, "ToonWorld4All HubCloud", "HubCloud 10Gbps $label", direct, quality)
                        emitted = true
                    }
                }

                link.contains("gofile.io") || text.contains("Gofile", true) -> {
                    extractGofile(absolute(link, base), quality, "HubCloud Gofile $label", callback)
                    emitted = true
                }

                text.contains("FSL Server") || text.contains("FSLv2") -> {
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud ${text.trim()} $label", absolute(link, base), quality)
                    emitted = true
                }

                text.contains("Mega Server") || text.contains("ZipDisk") -> {
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud ${text.trim()} $label", absolute(link, base), quality)
                    emitted = true
                }

                text.contains("Download File") -> {
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud ${pageTitle.ifBlank { label }}", absolute(link, base), quality)
                    emitted = true
                }
            }
        }
        return emitted
    }

    private suspend fun resolveFinalUrl(startUrl: String): String? {
        var current = startUrl
        repeat(7) {
            try {
                var res = app.head(
                    current, allowRedirects = false, timeout = 8000L,
                    headers = mapOf("User-Agent" to UA)
                )
                if (res.code == 405 || res.code == 400) {
                    res = app.get(
                        current, allowRedirects = false, timeout = 8000L,
                        headers = mapOf("User-Agent" to UA)
                    )
                }
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
                app.post("https://api.gofile.io/accounts", headers = apiHeaders, timeout = 20_000L).text
            ).getJSONObject("data").getString("token")

            val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                app.get("https://gofile.io/dist/js/global.js", headers = apiHeaders, timeout = 20_000L).text
            )?.groupValues?.get(1)

            val content = app.get(
                "https://api.gofile.io/contents/$code" + (if (wt != null) "?wt=$wt" else ""),
                headers = apiHeaders.toMutableMap().apply { put("Authorization", "Bearer $token") },
                timeout = 25_000L
            ).text
            val children = JSONObject(content).optJSONObject("data")?.optJSONObject("children") ?: return
            val fileObj = children.optJSONObject(children.keys().next()) ?: return
            val link = fileObj.optString("link").takeIf { it.startsWith("http") } ?: return
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
