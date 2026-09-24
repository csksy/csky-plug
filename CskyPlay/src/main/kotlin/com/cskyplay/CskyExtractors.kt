package com.cskyplay

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

internal const val CSKY_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal object CskyNet {
    const val TAG = "CskyPlay"

    val cfKiller: CloudflareKiller by lazy { CloudflareKiller() }

    fun headers(referer: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = mutableMapOf("User-Agent" to CSKY_UA)
        if (referer != null) h["Referer"] = referer
        h.putAll(extra)
        return h
    }

    fun getBaseUrl(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) {
        url
    }

    fun getIndexQuality(str: String?): Int {
        if (str.isNullOrBlank()) return Qualities.Unknown.value
        Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val lower = str.lowercase()
        return when {
            lower.contains("8k") -> 4320
            lower.contains("4k") || lower.contains("uhd") -> 2160
            lower.contains("2k") -> 1440
            else -> Qualities.Unknown.value
        }
    }

    fun rot13(input: String): String = buildString {
        for (c in input) {
            when (c) {
                in 'a'..'z' -> append('a' + (c - 'a' + 13) % 26)
                in 'A'..'Z' -> append('A' + (c - 'A' + 13) % 26)
                else -> append(c)
            }
        }
    }

    fun normalizeTitle(s: String?): String =
        (s ?: "").lowercase().replace(Regex("[^a-z0-9]"), "")

    fun deEsc(s: String): String =
        s.replace("\\/", "/").replace("\\\"", "\"").replace("&amp;", "&")

    fun slugify(s: String): String {
        val cleaned = s.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), "").trim()
        return Regex("\\s+").replace(cleaned, "-").lowercase()
    }

    fun absolute(href: String, base: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> base.trimEnd('/') + href
        else -> base.trimEnd('/') + "/" + href
    }

    suspend fun decryptIdLink(url: String, referer: String? = null): String? {
        return try {
            val res = app.get(
                url,
                headers = headers(referer),
                allowRedirects = false,
                timeout = 15000L
            )
            val text = res.text
            val m1 = Regex("""s\('o','([A-Za-z0-9+/=]+)'""").findAll(text)
                .map { it.groupValues[1] }.toList()
            val m2 = Regex("""ck\('_wp_http_\d+','([^']+)'""").findAll(text)
                .map { it.groupValues[1] }.toList()
            val concat = (m1 + m2).joinToString("")
            if (concat.isBlank()) return null
            val decoded = runCatching {
                base64Decode(rot13(base64Decode(base64Decode(concat))))
            }.getOrNull() ?: return null
            val obj = try {
                JSONObject(decoded)
            } catch (e: Exception) {
                null
            }
            if (obj == null) {
                val direct = runCatching { base64Decode(decoded) }.getOrNull() ?: decoded
                return direct.trim().takeIf { it.startsWith("http") }
            }
            val o = obj.optString("o").trim()
            val data = obj.optString("data").trim()
            val blog = obj.optString("blog_url").trim()
            if (data.isNotBlank() && blog.isNotBlank()) {
                val reRes = app.get(
                    "$blog?re=$data",
                    headers = headers(url),
                    allowRedirects = false,
                    timeout = 15000L
                )
                val body = reRes.document.body()?.text()?.trim().orEmpty()
                return body.ifBlank { o }.ifBlank { null }
            }
            val target = o.ifBlank { obj.optString("l").trim() }
            if (target.startsWith("http")) return target
            val unbased = runCatching { base64Decode(target) }.getOrNull() ?: target
            return unbased.trim().takeIf { it.startsWith("http") } ?: target.ifBlank { null }
        } catch (e: Exception) {
            Log.d(TAG, "decryptIdLink: ${e.message}")
            null
        }
    }

    suspend fun emitSiteLink(
        siteName: String,
        url: String,
        label: String,
        quality: Int? = null,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (url.isBlank()) return
        try {
            val before = System.currentTimeMillis()
            val collected = mutableListOf<Pair<ExtractorLink, String>>()
            loadExtractor(url, referer, subtitleCallback) { link ->
                collected.add(link to (link.name ?: ""))
            }
            for ((link, name) in collected) {
                val labelParts = listOfNotNull(
                    siteName,
                    name.takeIf { it.isNotBlank() },
                    label.takeIf { it.isNotBlank() }
                )
                callback(
                    newExtractorLink(
                        siteName,
                        labelParts.joinToString(" "),
                        link.url,
                        link.type
                    ) {
                        this.quality = quality ?: link.quality
                        this.referer = link.referer
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
            if (collected.isEmpty()) {
                Log.d(TAG, "$siteName no links from $url (${System.currentTimeMillis() - before}ms)")
            }
        } catch (e: Exception) {
            Log.d(TAG, "$siteName emit: ${e.message}")
        }
    }
}

class CskyHubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.ist"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(url, headers = CskyNet.headers(referer), timeout = 20000L).document
        } catch (e: Exception) {
            return
        }
        emitHubServers(doc, CskyNet.getBaseUrl(url), url, subtitleCallback, callback)
    }

    companion object {
        suspend fun emitHubServers(
            doc: Document,
            base: String,
            pageUrl: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            var emitted = false
            val header = doc.selectFirst("div.card-header")?.text().orEmpty()
            val size = doc.selectFirst("i#size")?.text().orEmpty()
            val quality = CskyNet.getIndexQuality(header)
            val labelExtras = listOf(header, size).filter { it.isNotBlank() }.joinToString(" ")

            val inner = when {
                pageUrl.contains("/video/") -> doc.selectFirst("div.vd > center > a")?.attr("href")
                else -> null
            }
            if (!inner.isNullOrBlank()) {
                try {
                    val innerDoc = app.get(
                        CskyNet.absolute(inner, base),
                        headers = CskyNet.headers(base),
                        timeout = 20000L
                    ).document
                    if (emitHubServers(innerDoc, base, inner, subtitleCallback, callback)) return true
                } catch (e: Exception) {
                }
            }

            for (btn in doc.select("a.btn, a[download]")) {
                val text = btn.text()
                val link = btn.attr("href").trim()
                if (link.isBlank()) continue
                if (listOf("tinyurl", "telegram", "/tg/").any { link.contains(it) }) continue
                val label = text.trim().lowercase()
                val abs = CskyNet.absolute(link, base)
                when {
                    label.contains("fslv2") -> {
                        callback(newExtractorLink("Hub-Cloud", "FSLv2 [$labelExtras]", abs, ExtractorLinkType.VIDEO) { this.quality = quality })
                        emitted = true
                    }
                    label.contains("fsl") -> {
                        callback(newExtractorLink("Hub-Cloud", "FSL Server [$labelExtras]", abs, ExtractorLinkType.VIDEO) { this.quality = quality })
                        emitted = true
                    }
                    label.contains("buzzserver") -> {
                        try {
                            val dlink = app.get(
                                "$abs/download",
                                referer = abs,
                                allowRedirects = false,
                                timeout = 15000L
                            ).headers["hx-redirect"] ?: ""
                            if (dlink.isNotBlank()) {
                                callback(newExtractorLink("Hub-Cloud", "BuzzServer [$labelExtras]", CskyNet.absolute(dlink, CskyNet.getBaseUrl(abs)), ExtractorLinkType.VIDEO) { this.quality = quality })
                                emitted = true
                            }
                        } catch (e: Exception) {
                        }
                    }
                    label.contains("pixeldra") || label.contains("pixelserver") || label.contains("pixel server") || link.contains("pixeldra") -> {
                        val pixelBase = CskyNet.getBaseUrl(link)
                        val final = if (link.contains("download", true)) link
                        else "$pixelBase/api/file/${link.substringAfterLast("/")}?download"
                        callback(newExtractorLink("Hub-Cloud", "Pixeldrain [$labelExtras]", final, ExtractorLinkType.VIDEO) { this.quality = quality })
                        emitted = true
                    }
                    label.contains("s3 server") || label.contains("mega server") || label.contains("pdl") -> {
                        callback(newExtractorLink("Hub-Cloud", "${text.trim()} [$labelExtras]", abs, ExtractorLinkType.VIDEO) { this.quality = quality })
                        emitted = true
                    }
                    label.contains("download file") -> {
                        callback(newExtractorLink("Hub-Cloud", "Download File [$labelExtras]", abs, ExtractorLinkType.VIDEO) { this.quality = quality })
                        emitted = true
                    }
                    link.contains("gofile.io") -> {
                        CskyGofile().getUrl(abs, base, subtitleCallback, callback)
                        emitted = true
                    }
                }
            }
            return emitted
        }
    }
}

class CskyVCloud : ExtractorApi() {
    override val name = "V-Cloud"
    override val mainUrl = "https://vcloud.fit"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(
                url,
                headers = CskyNet.headers(referer),
                interceptor = CskyNet.cfKiller,
                timeout = 20000L
            )
            val doc = res.document
            val base = CskyNet.getBaseUrl(res.url)
            var link: String? = null
            if (res.url.contains("/video/")) {
                link = doc.selectFirst("div.vd > center > a")?.attr("href")
            } else {
                val script = doc.selectFirst("script:containsData(url)")?.data().orEmpty()
                link = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
                    .find(script)?.groupValues?.get(1)
                    ?.let { runCatching { base64Decode(base64Decode(it)) }.getOrNull() }
                    ?: Regex("""var\s+url\s*=\s*['"]([^'"]*)['"]""").find(script)?.groupValues?.get(1)
            }
            if (link.isNullOrBlank()) return
            val abs = if (link.startsWith("http")) link else base + link
            val target = if (abs.contains("hubcloud")) CskyHubCloud() else CskyHubCloud()
            val targetDoc = try {
                app.get(abs, headers = CskyNet.headers(base), timeout = 20000L).document
            } catch (e: Exception) {
                return
            }
            CskyHubCloud.emitHubServers(targetDoc, CskyNet.getBaseUrl(abs), abs, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "vcloud: ${e.message}")
        }
    }
}

class CskyFastDl : ExtractorApi() {
    override val name = "G-Direct"
    override val mainUrl = "https://fastdl.zip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(
                url,
                headers = CskyNet.headers(referer ?: "https://nexdrive.fit/"),
                timeout = 20000L
            )
            val text = res.text
            val embedded = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                ?: Regex("""'(https://fastdl[^']*dl\.php\?link=[^']+)'""").find(text)?.groupValues?.get(1)
                ?: return
            val dlLink = if (embedded.startsWith("http")) embedded else "https://fastdl.zip$embedded"
            val encoded = Regex("""link=(https?://[^&"']+)""").find(dlLink)?.groupValues?.get(1) ?: return
            val direct = URLDecoder.decode(encoded, "UTF-8")
            if (direct.startsWith("http")) {
                callback(
                    newExtractorLink(
                        "G-Direct",
                        "G-Direct Direct Download",
                        direct,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.headers = mapOf("Referer" to "https://fastdl.zip/")
                    }
                )
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "fastdl: ${e.message}")
        }
    }
}

class CskyHblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.lol"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(
                url,
                headers = CskyNet.headers(referer),
                interceptor = CskyNet.cfKiller,
                timeout = 20000L
            ).document
            val seen = mutableSetOf<String>()
            for (a in doc.select("div.entry-content a[href]")) {
                val href = a.attr("href").trim()
                if (!href.startsWith("http") || !seen.add(href)) continue
                loadExtractor(href, url, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "hblinks: ${e.message}")
        }
    }
}

class CskyHubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.pics"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(
                url,
                headers = CskyNet.headers(referer),
                interceptor = CskyNet.cfKiller,
                timeout = 20000L
            ).document
            val seen = mutableSetOf<String>()
            for (a in doc.select("div.entry-content a[href], main a[href], a[href*='hubcloud']")) {
                val href = a.attr("href").trim()
                if (!href.startsWith("http") || href.contains("/tg/") || !seen.add(href)) continue
                if (href.contains("hubcloud")) {
                    loadExtractor(href, url, subtitleCallback, callback)
                }
            }
            if (seen.isEmpty()) {
                for (a in doc.select("a[href]")) {
                    val href = a.attr("href").trim()
                    if (href.startsWith("http") && !href.contains("hubdrive") && !seen.add(href)) continue
                    if (href.contains("hubcloud") || href.contains("gofile")) {
                        loadExtractor(href, url, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "hubdrive: ${e.message}")
        }
    }
}

class CskyHdStream4u : VidHidePro() {
    override val name = "HdStream4u"
    override val mainUrl = "https://hdstream4u.com"
}

class CskyFileBee : ExtractorApi() {
    override val name = "FileBee"
    override val mainUrl = "https://filebee.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(
                url,
                headers = CskyNet.headers(referer),
                interceptor = CskyNet.cfKiller,
                timeout = 20000L
            )
            val doc = res.document
            val base = CskyNet.getBaseUrl(res.url)
            val seen = mutableSetOf<String>()
            for (a in doc.select("a[href]")) {
                val href = a.attr("href").trim()
                if (!href.startsWith("http") || !seen.add(href)) continue
                if (Regex("""drive\.google|googleusercontent|\.mp4|\.mkv""").containsMatchIn(href)) {
                    val quality = CskyNet.getIndexQuality(a.text() + " " + href)
                    callback(
                        newExtractorLink(
                            "FileBee",
                            "FileBee ${a.text().trim().take(40)}",
                            href,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.headers = mapOf("Referer" to "$base/")
                        }
                    )
                }
            }
            for (script in doc.select("script")) {
                val data = script.data()
                if (data.length > 200000) continue
                for (m in Regex("""(https?://[^"'\s\\]+(?:drive\.google|googleusercontent|\.mp4|\.mkv)[^"'\s\\]*)""").findAll(data)) {
                    val href = m.groupValues[1]
                    if (seen.add(href)) {
                        callback(
                            newExtractorLink(
                                "FileBee",
                                "FileBee Direct",
                                href,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.headers = mapOf("Referer" to "$base/")
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "filebee: ${e.message}")
        }
    }
}

class CskyGofile : ExtractorApi() {
    override val name = "GoFile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = url.substringAfterLast("/").substringBefore("#")
            if (code.isBlank()) return
            val api = "https://api.gofile.io"
            val token = try {
                JSONObject(
                    app.post("$api/accounts", timeout = 15000L).text
                ).getJSONObject("data").getString("token")
            } catch (e: Exception) {
                return
            }
            val wt = try {
                Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                    app.get("$api/dist/js/global.js", timeout = 15000L).text
                )?.groupValues?.get(1)
            } catch (e: Exception) {
                null
            }
            val contentUrl = if (wt.isNullOrBlank()) "$api/contents/$code?wt=$token"
            else "$api/contents/$code?wt=$wt"
            val contentRes = app.get(
                contentUrl,
                headers = mapOf("Authorization" to "Bearer $token"),
                timeout = 20000L
            ).text
            val data = JSONObject(contentRes).getJSONObject("data")
            val children = data.optJSONObject("children") ?: return
            for (key in children.keys()) {
                val child = children.optJSONObject(key) ?: continue
                val link = child.optString("link").takeIf { it.startsWith("http") } ?: continue
                val name = child.optString("name")
                val size = child.optLong("size", 0L)
                val sizeMb = if (size > 0) " ${size / 1024 / 1024}MB" else ""
                val quality = CskyNet.getIndexQuality(name)
                callback(
                    newExtractorLink(
                        "GoFile",
                        "GoFile ${name.take(50)}$sizeMb",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.headers = mapOf("Authorization" to "Bearer $token")
                    }
                )
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "gofile: ${e.message}")
        }
    }
}

internal object CskyPacker {
    fun findM3u8(unpacked: String): String? {
        listOf(
            Regex("\"hls2\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"hls3\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"hls4\"\\s*:\\s*\"([^\"]+)\""),
            Regex("""file\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""")
        ).forEach { rx ->
            rx.find(unpacked)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    suspend fun emitM3u8(
        m3u8: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (m3u8.isBlank() || !m3u8.startsWith("http")) return false
        return try {
            val master = app.get(m3u8, headers = CskyNet.headers(referer), timeout = 15000L).text
            if (!master.contains("#EXTM3U")) return false
            val quality = Regex("""RESOLUTION=\d+x(\d+)""").find(master)?.groupValues?.get(1)?.toIntOrNull()
            callback(
                newExtractorLink(
                    "CskyPlay",
                    label,
                    m3u8,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = quality ?: Qualities.Unknown.value
                    this.referer = referer
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun resolvePackedEmbed(
        embedUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val res = app.get(embedUrl, headers = CskyNet.headers(referer), timeout = 20000L)
            val text = res.text
            val unpacked = if (text.contains("eval(function(p,a,c,k,e,d)")) {
                runCatching { getAndUnpack(text) }.getOrNull() ?: text
            } else text
            val m3u8 = findM3u8(unpacked)
                ?: Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""").find(unpacked)?.groupValues?.get(1)
                ?: return false
            emitM3u8(m3u8, CskyNet.getBaseUrl(res.url), label, callback)
        } catch (e: Exception) {
            false
        }
    }
}

internal object CskyModiplay {
    suspend fun resolve(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = CskyNet.getBaseUrl(embedUrl)
        if (base.isBlank()) return
        val html = try {
            app.get(embedUrl, headers = CskyNet.headers("https://multimovies.casa/"), timeout = 20000L).text
        } catch (e: Exception) {
            return
        }
        val servers = Regex("""switchServer\('([^']+)','([^']+)','([^']+)','([^']+)','([^']*)'""")
            .findAll(html).map { m ->
                listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            }.distinct().toList()

        loadSubs(base, embedUrl, subtitleCallback)

        if (servers.isEmpty()) {
            CskyPacker.resolvePackedEmbed(embedUrl, label, "https://multimovies.casa/", callback)
            return
        }
        for ((embed, platform, name, code) in servers) {
            val linkLabel = "$label $name"
            var handled = false
            if (embed.startsWith("http")) {
                handled = CskyPacker.resolvePackedEmbed(embed, linkLabel, base, callback)
            }
            if (!handled) {
                try {
                    resolveProxyFile(base, platform, code, linkLabel, callback)
                } catch (e: Exception) {
                }
            }
        }
    }

    private suspend fun resolveProxyFile(
        base: String,
        platform: String,
        fileCode: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val proxyUrl = "$base/proxy.php?p=$platform&c=$fileCode&title=&site_ref=&noredirect=1"
        val page = try {
            app.get(proxyUrl, headers = CskyNet.headers(base), timeout = 20000L).text
        } catch (e: Exception) {
            return false
        }
        val src = Regex("""var\s+src\s*=\s*"([^"]+)"""").find(page)?.groupValues?.get(1)?.let { CskyNet.deEsc(it) }
        val segRef = Regex("""var\s+SEG_REF\s*=\s*"([^"]+)"""").find(page)?.groupValues?.get(1)?.let { CskyNet.deEsc(it) }
        if (src.isNullOrBlank()) return false
        val masterUrl = CskyNet.absolute(src, base)
        return CskyPacker.emitM3u8(masterUrl, segRef ?: base, label, callback)
    }

    private suspend fun loadSubs(
        base: String,
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val imdbId = Regex("[?&]id=(tt\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            val tmdbId = Regex("[?&]id=(\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            val season = Regex("[?&]s=(\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            val ep = Regex("[?&]e=(\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            if (imdbId.isBlank() && tmdbId.isBlank()) return
            val seen = mutableSetOf<String>()
            for (lang in listOf("en", "hi", "")) {
                val resp = try {
                    app.get(
                        "$base/api/subtitle_fetch.php?tmdb_id=$tmdbId&imdb_id=$imdbId&season=$season&ep=$ep&lang=$lang",
                        headers = CskyNet.headers(base),
                        timeout = 15000L
                    ).text
                } catch (e: Exception) {
                    continue
                }
                val url = Regex(""""url"\s*:\s*"([^"]+)"""").find(resp)?.groupValues?.get(1)?.let { CskyNet.deEsc(it) }
                    ?: continue
                if (!url.startsWith("http")) continue
                val langName = Regex(""""lang"\s*:\s*"([^"]+)"""").find(resp)?.groupValues?.get(1)?.ifBlank { null }
                    ?: "English"
                if (seen.add(url)) {
                    subtitleCallback(newSubtitleFile(langName, url) {})
                }
            }
        } catch (e: Exception) {
        }
    }
}

internal object CskyGdmirror {
    suspend fun resolve(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(
                embedUrl,
                headers = CskyNet.headers("https://multimovies.casa/"),
                timeout = 20000L
            )
            val page = res.text
            val finalUrl = res.url
            val playerBase = Regex("""player_base\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
                ?: "https://pro.iqsmartgames.com"
            val apiUrl = Regex("""api_url\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
            val myKey = Regex("""myKey\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
            val finalId = Regex("""FinalID\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
            val idType = Regex("""idType\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
            val sid = Regex("""const\s+sid\s*=\s*"([^"]+)"""").find(page)?.groupValues?.get(1)
                ?: finalUrl.substringAfterLast("/").takeIf { it.isNotBlank() && it != "svid" && !it.contains("?") }

            val sids = mutableSetOf<String>()
            if (!sid.isNullOrBlank()) sids.add(sid)

            if (apiUrl != null && myKey != null && finalId != null) {
                val apiQuery = if (finalUrl.contains("/tv/") || page.contains("myseriesapi")) {
                    val season = Regex("""[?&]s=(\d+)""").find(finalUrl)?.groupValues?.get(1) ?: "1"
                    val ep = Regex("""[?&]e=(\d+)""").find(finalUrl)?.groupValues?.get(1) ?: "1"
                    "$apiUrl/myseriesapi?${idType ?: "imdbid"}=$finalId&season=$season&epname=$ep&key=$myKey"
                } else {
                    "$apiUrl/mymovieapi?${idType ?: "imdbid"}=$finalId&key=$myKey"
                }
                try {
                    val apiRes = app.get(apiQuery, headers = CskyNet.headers(apiUrl), timeout = 20000L).text
                    collectSlugs(apiRes, sids)
                } catch (e: Exception) {
                }
            }

            if (sids.isEmpty()) return

            for (s in sids) {
                try {
                    val helperRes = app.post(
                        "$playerBase/embedhelper2.php",
                        headers = mapOf(
                            "User-Agent" to CSKY_UA,
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Referer" to finalUrl,
                            "Origin" to CskyNet.getBaseUrl(finalUrl),
                            "X-Requested-With" to "XMLHttpRequest"
                        ),
                        data = mapOf("sid" to s, "UserFavSite" to "", "currentDomain" to "[]"),
                        timeout = 20000L
                    ).text
                    val helper = JSONObject(helperRes)
                    val mresult = helper.optString("mresult")
                    val codes = if (mresult.isNotBlank()) {
                        runCatching {
                            parseJson<Map<String, String>>(base64Decode(mresult))
                        }.getOrNull() ?: emptyMap()
                    } else emptyMap()
                    val sources = helper.optJSONObject("sources") ?: continue
                    for (key in sources.keys()) {
                        val src = sources.optJSONObject(key) ?: continue
                        val siteUrl = src.optString("siteUrl").takeIf { it.startsWith("http") } ?: continue
                        val suffix = src.optString("embed_suffix").takeIf { it != "null" && it.isNotBlank() } ?: ""
                        val code = codes[key] ?: continue
                        val friendly = src.optString("friendlyName").ifBlank { key }
                        val embed = "$siteUrl$code$suffix"
                        CskyPacker.resolvePackedEmbed(embed, "$label $friendly", playerBase, callback)
                    }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "gdmirror: ${e.message}")
        }
    }

    private fun collectSlugs(body: String, out: MutableSet<String>) {
        try {
            val obj = JSONObject(body)
            fun walk(o: Any?) {
                when (o) {
                    is JSONObject -> {
                        for (k in listOf("fileslug", "slug", "sid")) {
                            val v = o.optString(k)
                            if (v.isNotBlank()) out.add(v)
                        }
                        for (key in o.keys()) walk(o.get(key))
                    }
                    is org.json.JSONArray -> {
                        for (i in 0 until o.length()) walk(o.get(i))
                    }
                }
            }
            walk(obj)
        } catch (e: Exception) {
        }
    }
}
