package com.toonworld4all

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

internal object Tw4aExtractors {

    private const val TAG = "TW4A"

    private val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val rawClient: OkHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    @Volatile
    private var domainOverrides: Map<String, String>? = null

    private suspend fun latestDomain(hostKey: String, fallback: String): String {
        val cached = domainOverrides
        if (cached != null) return cached[hostKey] ?: fallback
        return try {
            val fetched = app.get(
                "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json",
                timeout = 10_000L
            ).text
            val parsed = JSONObject(fetched)
            val map = mutableMapOf<String, String>()
            for (k in parsed.keys()) map[k] = parsed.optString(k)
            domainOverrides = map
            map[hostKey] ?: fallback
        } catch (e: Exception) {
            domainOverrides = emptyMap()
            fallback
        }
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

    internal suspend fun extractHubCloud(
        url: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val host = tw4aHostOf(url)
            val latestHost = if (host.contains("hubcloud")) latestDomain("hubcloud", host) else host
            val targetUrl = if (latestHost != host) url.replaceFirst(host, latestHost) else url
            val base = "https://$latestHost"

            val response = tw4aGet(targetUrl, mapOf("User-Agent" to UA), timeout = 30_000L)
            var html = response.text
            var currentUrl = response.url

            val jsRedirect = Regex("""window\.location\.replace\(['"]([^'"]+)['"]\)""")
                .find(html)?.groupValues?.get(1)
            if (jsRedirect != null && jsRedirect != currentUrl) {
                val second = tw4aGet(
                    absolute(jsRedirect, base),
                    mapOf(
                        "User-Agent" to UA,
                        "Referer" to currentUrl,
                        "Cookie" to response.headers.values("set-cookie")
                            .joinToString("; ") { it.substringBefore(";") }
                    ),
                    timeout = 30_000L
                )
                html = second.text
                currentUrl = second.url
            }
            if (html.contains("404") && html.contains("File Not Found", true) && html.length < 200) return

            val doc = Jsoup.parse(html)

            var dlPageUrl: String? = null
            when {
                currentUrl.contains("/video/") -> {
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
                emitHubServers(doc, base, quality, label, callback)
                return
            }

            val dlHtml = tw4aGet(
                absolute(dlPageUrl, base),
                mapOf("User-Agent" to UA, "Referer" to currentUrl),
                timeout = 30_000L
            ).text
            emitHubServers(Jsoup.parse(dlHtml), base, quality, label, callback)
        } catch (e: Exception) {
            Log.d(TAG, "hubcloud failed: ${e.message}")
        }
    }

    private suspend fun emitHubServers(
        doc: org.jsoup.nodes.Document,
        base: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headerText = doc.selectFirst("div.card-header")?.text().orEmpty()
        val size = doc.selectFirst("i#size")?.text().orEmpty()
        val pageTitle = listOf(headerText, size).filter { it.isNotBlank() }.joinToString(" ")

        for (btn in doc.select("a.btn")) {
            val text = btn.text()
            val link = btn.attr("href").ifBlank { continue }
            when {
                text.contains("FSL Server") ->
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud FSL $label", absolute(link, base), quality)

                text.contains("FSLv2") ->
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud FSLv2 $label", absolute(link, base), quality)

                text.contains("Mega Server") ->
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud Mega $label", absolute(link, base), quality)

                text.contains("ZipDisk") ->
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud ZipDisk $label", absolute(link, base), quality)

                text.contains("Download File") ->
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud ${pageTitle.ifBlank { label }}", absolute(link, base), quality)

                link.contains("pixeldrain") || text.contains("PixelServer") -> {
                    val pxl = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
                        .find(doc.toString())?.groupValues?.get(1) ?: link
                    val final = if (pxl.contains("download", true)) pxl
                    else "https://pixeldrain.com/api/file/${pxl.substringAfterLast("/")}?download"
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud Pixeldrain $label", final, quality)
                }

                text.contains("Server : 10Gbps") -> {
                    val resolved = resolveFinalUrl(absolute(link, base)) ?: continue
                    val clean = if (resolved.contains("link=")) resolved.substringAfter("link=") else resolved
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud 10Gbps $label", clean, quality)
                }

                text.contains("Gofile", true) ->
                    extractGofile(absolute(link, base), quality, "HubCloud Gofile $label", callback)

                text.contains("Download") && link.startsWith("http") ->
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud ${text.substringAfter("Download").trim(' ', '[', ']')} $label", link, quality)
            }
        }
    }

    private suspend fun resolveBusycdn(instantUrl: String): String? {
        return try {
            val resp = app.get(instantUrl, allowRedirects = false, timeout = 20_000L)
            val loc = resp.headers["location"] ?: resp.headers["Location"]
            if (!loc.isNullOrBlank() && loc.contains("url=")) {
                java.net.URLDecoder.decode(loc.substringAfter("url="), "UTF-8")
            } else if (!loc.isNullOrBlank()) {
                loc
            } else null
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

    internal suspend fun extractDriveFamily(
        url: String,
        quality: Int,
        label: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val response = tw4aGet(url, mapOf("User-Agent" to UA), timeout = 30_000L)
            val host = tw4aHostOf(response.url)
            val base = "https://$host"
            val doc = Jsoup.parse(response.text)

            val info = listOf(
                doc.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ").trim(),
                doc.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").trim()
            ).filter { it.isNotBlank() }.joinToString(" ")

            val instant = doc.selectFirst("a[href*='instant.busycdn.xyz']")?.attr("href")?.trim()
            if (!instant.isNullOrBlank()) {
                val direct = resolveBusycdn(absolute(instant, base))
                if (!direct.isNullOrBlank()) {
                    emit(callback, sourceName, "$sourceName Instant ${info.ifBlank { label }}", direct, quality)
                }
            }

            for (anchor in doc.select("div.text-center a, div.card-body a, a.btn")) {
                val text = anchor.text()
                val link = anchor.attr("href").trim()
                if (link.isBlank()) continue
                when {
                    text.contains("FSL V2") ->
                        emit(callback, sourceName, "$sourceName FSLv2 ${info.ifBlank { label }}", absolute(link, base), quality)

                    text.contains("DIRECT DL") || text.contains("DIRECT SERVER") ->
                        emit(callback, sourceName, "$sourceName Direct ${info.ifBlank { label }}", absolute(link, base), quality)

                    text.contains("CLOUD DOWNLOAD") ->
                        emit(callback, sourceName, "$sourceName Cloud ${info.ifBlank { label }}", absolute(link, base), quality)

                    text.contains("GD Index") -> {
                        for (cfType in listOf(1, 2)) {
                            try {
                                val cfDoc = Jsoup.parse(
                                    tw4aGet(
                                        absolute("$link?type=$cfType", base),
                                        mapOf("User-Agent" to UA),
                                        timeout = 20_000L
                                    ).text
                                )
                                for (btn in cfDoc.select("a.btn-success")) {
                                    emit(callback, sourceName, "$sourceName CF$cfType ${info.ifBlank { label }}", absolute(btn.attr("href"), base), quality)
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }

                    link.contains("/cloud/") -> {
                        resolveCloudChain(absolute(link, base), host, quality, info.ifBlank { label }, sourceName, callback)
                    }

                    link.contains("pixeldra") -> {
                        val final = if (link.contains("download", true)) link
                        else "https://pixeldrain.com/api/file/" + link.substringAfterLast("/") + "?download"
                        emit(callback, sourceName, "$sourceName Pixeldrain ${info.ifBlank { label }}", final, quality)
                    }

                    text.contains("Instant DL") -> {
                        val direct = resolveBusycdn(absolute(link, base))
                        if (!direct.isNullOrBlank()) {
                            emit(callback, sourceName, "$sourceName Instant ${info.ifBlank { label }}", direct, quality)
                        }
                    }

                    text.contains("GoFile") || link.contains("gofile") ->
                        extractGofile(absolute(link, base), quality, "$sourceName Gofile ${info.ifBlank { label }}", callback)
                }
            }

            if (host.contains("filepress") || host.contains("filebee")) {
                val seen = mutableSetOf<String>()
                for (a in doc.select("a[href]")) {
                    val href = a.attr("href")
                    if (href.startsWith("http") &&
                        (href.contains("drive.google") || href.contains("googleusercontent") ||
                                href.contains(".mp4") || href.contains(".mkv") ||
                                href.contains("pixeldrain")) && seen.add(href)
                    ) {
                        val final = if (href.contains("pixeldrain") && !href.contains("download", true))
                            "https://pixeldrain.com/api/file/${href.substringAfterLast("/")}?download"
                        else href
                        emit(callback, sourceName, "$sourceName ${a.text().ifBlank { label }}", final, quality)
                    }
                }
                for (script in doc.select("script")) {
                    val data = script.data()
                    if (!data.contains("drive.google") && !data.contains("googleusercontent") &&
                        !data.contains(".mp4") && !data.contains(".mkv")
                    ) continue
                    Regex("""(https?://[^"'\s]+(?:drive\.google|googleusercontent|\.mp4|\.mkv)[^"'\s]*)""")
                        .findAll(data).forEach { m ->
                            val href = m.groupValues[1]
                            if (seen.add(href)) {
                                emit(callback, sourceName, "$sourceName $label", href, quality)
                            }
                        }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "drive family failed for $url: ${e.message}")
        }
    }

    private suspend fun resolveCloudChain(
        cloudPageUrl: String,
        host: String,
        quality: Int,
        label: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val page = tw4aGet(cloudPageUrl, mapOf("User-Agent" to UA), timeout = 30_000L)
            val html = page.text
            val key = Regex("""formData\.append\("key",\s*"([a-f0-9]{40})""").find(html)?.groupValues?.get(1) ?: return
            val startBody = multipartPost(
                host, cloudPageUrl,
                mapOf("action" to "cloud", "key" to key, "action_token" to "")
            ) ?: return
            val start = try { JSONObject(startBody) } catch (e: Exception) { return }
            if (start.optBoolean("error", true)) return
            val tokenPath = start.optString("url").takeIf { it.isNotBlank() } ?: return
            val tokenUrl = absolute(tokenPath, "https://$host")
            tw4aGet(tokenUrl, mapOf("User-Agent" to UA), timeout = 20_000L)
            var redirect: String? = null
            for (i in 1..20) {
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
                kotlinx.coroutines.delay(1500)
            }
            val redirectPath = redirect ?: return
            val finalUrl = absolute(redirectPath, "https://$host")
            val finalDoc = Jsoup.parse(tw4aGet(finalUrl, mapOf("User-Agent" to UA), timeout = 30_000L).text)
            val dl = finalDoc.selectFirst("a[href*='workers.dev/'], a[href*='cloud-dl']")?.attr("href")?.trim() ?: return
            emit(callback, sourceName, "$sourceName Cloud ${label}", absolute(dl, "https://$host"), quality)
        } catch (e: Exception) {
            Log.d(TAG, "cloud chain failed: ${e.message}")
        }
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
