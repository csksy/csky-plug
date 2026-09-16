package com.toonworld4all

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Extractors for the file hosts toonworld4all's archive links to:
 *
 *  - HubCloud   (hubcloud.ist, /video/{id} and /file/{id})
 *  - GDFlix     (gdflix.dev -> 302 -> new4.gdflix.io, /file/{id})
 *  - Filepress  (new4.filepress.baby, /file/{id}) - Cloudflare protected
 *
 * All parsing logic is adapted from the battle-tested extractors that ship in
 * this repository (AniShows `HubCloud`/`GDFlix`, TheMoviesFlix
 * `FileBee`/`GoFile`), which are actively used against the very same hosts
 * every day, so the DOM contracts below are known-good.
 */
object Tw4aExtractors {

    private const val TAG = "TW4A"

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val filepressKiller by lazy { CloudflareKiller() }

    // ------------------------------------------------------------------ //
    //  shared helpers
    // ------------------------------------------------------------------ //

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

    /** Follow a redirect chain with HEAD requests (max 7 hops). */
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

    // ------------------------------------------------------------------ //
    //  HubCloud
    // ------------------------------------------------------------------ //

    /**
     * hubcloud.ist/video/{id}  -> "vd" page with a single download link
     * hubcloud.ist/file/{id}   -> page whose script holds `var url = '...'`
     *                              (vcloud variant uses double atob) -> dl page
     * The dl page then lists the actual servers (FSL / Mega / pixeldrain /
     * 10Gbps / gofile).
     */
    suspend fun extractHubCloud(
        url: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val response = app.get(url, headers = mapOf("User-Agent" to UA), timeout = 30L)
        var html = response.text
        var currentUrl = response.url

        // JS-based redirect (hubcloud hides the real page behind
        // window.location.replace with a cookie handshake).
        val jsRedirect = Regex("""window\.location\.replace\(['"]([^'"]+)['"]\)""")
            .find(html)?.groupValues?.get(1)
        if (jsRedirect != null && jsRedirect != currentUrl) {
            val second = app.get(
                jsRedirect,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to currentUrl,
                    "Cookie" to response.headers.values("set-cookie")
                        .joinToString("; ") { it.substringBefore(";") }
                ),
                timeout = 30L
            )
            html = second.text
            currentUrl = second.url
        }
        if (html.contains("404") && html.contains("File Not Found", true) && html.length < 120) return

        val doc = Jsoup.parse(html)
        val base = "https://" + java.net.URI(currentUrl).host

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
            // some hubcloud pages link the servers directly
            emitHubServers(doc, base, quality, label, callback)
            return
        }
        if (!dlPageUrl.startsWith("http")) dlPageUrl = base + dlPageUrl

        val dlHtml = app.get(
            dlPageUrl, headers = mapOf("User-Agent" to UA, "Referer" to currentUrl), timeout = 30L
        ).text
        emitHubServers(Jsoup.parse(dlHtml), base, quality, label, callback)
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

        // Live dl pages (verified 2026-09) render the server buttons as bare
        // <a class="btn btn-success btn-lg h6">Download [FSL Server]</a> anchors
        // - NOT wrapped in <h2> anymore. Login/VPN/tutorial links carry no btn
        // class, so "a.btn" selects exactly the download servers (old h2-wrapped
        // variant is a subset and keeps working).
        for (btn in doc.select("a.btn")) {
            val text = btn.text()
            val link = btn.attr("href").ifBlank { continue }
            when {
                text.contains("FSL Server") ->
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud FSL $label", link, quality)

                text.contains("FSLv2") ->
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud FSLv2 $label", link, quality)

                text.contains("Mega Server") ->
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud Mega $label", link, quality)

                text.contains("ZipDisk") ->
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud ZipDisk $label", link, quality)

                text.contains("Download File") ->
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud ${pageTitle.ifBlank { label }}", link, quality)

                link.contains("pixeldrain") || text.contains("PixelServer") -> {
                    // the visible href is a decoy - the real one is rewritten from
                    // `var pxl = "..."` by an inline script
                    val pxl = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
                        .find(doc.toString())?.groupValues?.get(1) ?: link
                    val final = if (pxl.contains("download", true)) pxl
                    else "https://pixeldrain.com/api/file/${pxl.substringAfterLast("/")}?download"
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud Pixeldrain $label", final, quality)
                }

                text.contains("Server : 10Gbps") -> {
                    val resolved = resolveFinalUrl(link) ?: continue
                    val clean = if (resolved.contains("link=")) resolved.substringAfter("link=") else resolved
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud 10Gbps $label", clean, quality)
                }

                text.contains("Gofile", true) ->
                    extractGofile(link, quality, "HubCloud Gofile $label", callback)

                // generic "Download [Xxx Server]" buttons that may appear later
                text.contains("Download") && link.startsWith("http") ->
                    emit(callback, "ToonWorld4All HubCloud", "HubCloud ${text.substringAfter("Download").trim(' ', '[', ']')} $label", link, quality)
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  GDFlix
    // ------------------------------------------------------------------ //

    /** gdflix.dev/file/{id} (302 -> new4.gdfflix.io - app.get follows it). */
    suspend fun extractGdflix(
        url: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val response = app.get(url, headers = mapOf("User-Agent" to UA), timeout = 30L)
        val doc = response.document
        val baseUrl = "https://" + java.net.URI(response.url).host

        val name = doc.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ").trim()
        val size = doc.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ").trim()
        val info = listOf(name, size).filter { it.isNotBlank() }.joinToString(" ")

        for (anchor in doc.select("div.text-center a")) {
            val text = anchor.text()
            val link = anchor.attr("href").ifBlank { continue }
            when {
                text.contains("FSL V2") ->
                    emit(callback, "ToonWorld4All GDFlix", "GDFlix FSLv2 ${info.ifBlank { label }}", link, quality)

                text.contains("DIRECT DL") || text.contains("DIRECT SERVER") ->
                    emit(callback, "ToonWorld4All GDFlix", "GDFlix Direct ${info.ifBlank { label }}", link, quality)

                text.contains("CLOUD DOWNLOAD") ->
                    emit(callback, "ToonWorld4All GDFlix", "GDFlix Cloud ${info.ifBlank { label }}", link, quality)

                text.contains("GD Index") -> {
                    for (cfType in listOf(1, 2)) {
                        try {
                            val cfDoc = app.get(
                                "$baseUrl$link?type=$cfType",
                                headers = mapOf("User-Agent" to UA), timeout = 20L
                            ).document
                            for (btn in cfDoc.select("a.btn-success")) {
                                emit(callback, "ToonWorld4All GDFlix", "GDFlix CF$cfType ${info.ifBlank { label }}", btn.attr("href"), quality)
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "gdindex cf$cfType failed: ${e.message}")
                        }
                    }
                }

                text.contains("FAST CLOUD") -> {
                    try {
                        val dlink = app.get(
                            baseUrl + link, headers = mapOf("User-Agent" to UA), timeout = 20L
                        ).document.selectFirst("div.card-body a")?.attr("href")
                        if (!dlink.isNullOrBlank()) {
                            emit(callback, "ToonWorld4All GDFlix", "GDFlix FastCloud ${info.ifBlank { label }}", dlink, quality)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "fastcloud failed: ${e.message}")
                    }
                }

                link.contains("pixeldra") -> {
                    val final = if (link.contains("download", true)) link
                    else "https://pixeldrain.com/api/file/${link.substringAfterLast("/")}?download"
                    emit(callback, "ToonWorld4All GDFlix", "GDFlix Pixeldrain ${info.ifBlank { label }}", final, quality)
                }

                text.contains("Instant DL") -> {
                    try {
                        val instant = app.get(
                            link, allowRedirects = false, timeout = 20L,
                            headers = mapOf("User-Agent" to UA)
                        ).headers["location"]?.substringAfter("url=")
                        if (!instant.isNullOrBlank()) {
                            emit(callback, "ToonWorld4All GDFlix", "GDFlix Instant ${info.ifBlank { label }}", instant, quality)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "instant failed: ${e.message}")
                    }
                }

                text.contains("GoFile") || link.contains("gofile") ->
                    extractGofile(link, quality, "GDFlix Gofile ${info.ifBlank { label }}", callback)
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Filepress (Cloudflare protected)
    // ------------------------------------------------------------------ //

    suspend fun extractFilePress(
        url: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val html = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to "https://archive.toonworld4all.me/",
                ),
                interceptor = filepressKiller,
                timeout = 30L
            ).text
        } catch (e: Exception) {
            Log.d(TAG, "filepress failed: ${e.message}")
            return
        }
        val doc = Jsoup.parse(html)

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
                emit(callback, "ToonWorld4All Filepress", "Filepress ${a.text().ifBlank { label }}", final, quality)
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
                        emit(callback, "ToonWorld4All Filepress", "Filepress $label", href, quality)
                    }
                }
        }
    }

    // ------------------------------------------------------------------ //
    //  GoFile (hubcloud / gdflix both fall back to gofile servers)
    // ------------------------------------------------------------------ //

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

    // ------------------------------------------------------------------ //
    //  direct links
    // ------------------------------------------------------------------ //

    suspend fun emitDirect(
        url: String,
        quality: Int,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        emit(callback, "ToonWorld4All", label, url, quality)
    }
}
