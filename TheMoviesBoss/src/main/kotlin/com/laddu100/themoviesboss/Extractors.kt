package com.laddu100.themoviesboss

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import java.net.URI
import java.security.MessageDigest

private const val TAG = "TheMoviesBoss"

// the download hosts sit behind rotating subdomains, these extractors follow the
// cs3 reference implementations that fan out every server button on the drive pages
fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (e: Exception) {
        url
    }
}

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lower = str.lowercase()
    return when {
        lower.contains("8k") -> 4320
        lower.contains("4k") -> 2160
        lower.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

// 10Gbps style buttons chain through short lived redirects before the file url appears
suspend fun resolveFinalUrl(startUrl: String): String? {
    var currentUrl = startUrl
    repeat(7) {
        try {
            val res = app.head(currentUrl, allowRedirects = false, timeout = 2500L)
            if (res.code != 200 && res.code !in 300..399) return null
            val location = res.headers["location"] ?: return currentUrl
            currentUrl = location
        } catch (e: Exception) {
            return null
        }
    }
    return currentUrl
}

open class HubCloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.*"
    override val requiresReferer = false

    open val hostKeyword = "hubcloud"

    // vcloud hides its target behind a double atob, hubcloud uses a plain var url
    private fun extractDriveLink(html: String, url: String, baseUrl: String): String? {
        if (url.contains("/video/")) {
            Regex("""<div class="vd">\s*<center>\s*<a href="([^"]+)"""").find(html)?.groupValues?.get(1)?.let { return it }
        }
        val script = Regex("""<script[^>]*>([\s\S]*?url[\s\S]*?)</script>""").findAll(html)
            .firstOrNull { it.groupValues[1].contains("var url") || it.groupValues[1].contains("atob(atob") }
            ?.groupValues[1]
        if (script != null) {
            if (hostKeyword == "vcloud") {
                Regex("""atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""").find(script)?.groupValues?.get(1)?.let {
                    val decoded = try { base64Decode(base64Decode(it)) } catch (e: Exception) { null }
                    if (decoded != null && decoded.startsWith("http")) return decoded
                }
            }
            Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""").find(script)?.groupValues?.get(1)?.let {
                if (it.startsWith("http") || it.startsWith("/")) return it
            }
        }
        // some layouts skip the intermediate page and put the drive link straight in an anchor
        Regex("""href="(https?://$hostKeyword[^"]*/drive[^"]*)"""").find(html)?.groupValues?.get(1)?.let { return it }
        return null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val baseUrl = getBaseUrl(url)
            val pageHtml = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text

            val driveLink = extractDriveLink(pageHtml, url, baseUrl)
                ?: run { Log.d(TAG, "$name: no drive link on $url"); return }

            val driveUrl = if (driveLink.startsWith("http")) driveLink else baseUrl + driveLink
            val driveDoc = app.get(driveUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
            val header = driveDoc.select("div.card-header").text()
            val size = driveDoc.select("i#size").text()
            val quality = getIndexQuality(header)

            suspend fun emit(link: String, server: String) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name$server ${header.ifBlank { "file" }} [${size.ifBlank { "?" }}]",
                        url = link,
                        referer = baseUrl,
                        quality = quality,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }

            driveDoc.select("h2 a.btn, div.card-body h2 a.btn").forEach { btn ->
                val link = btn.attr("href")
                val text = btn.text()
                if (link.isBlank()) return@forEach
                when {
                    text.contains("FSL Server") -> emit(link, "[FSL Server]")
                    text.contains("FSLv2") -> emit(link, "[FSLv2]")
                    text.contains("Mega Server") -> emit(link, "[Mega Server]")
                    text.contains("Download File") -> emit(link, "")
                    text.contains("Buzz Server") || text.contains("BuzzServer") -> {
                        val buzzBase = getBaseUrl(link)
                        val buzzPath = app.get(link, headers = mapOf("User-Agent" to USER_AGENT))
                            .document.selectFirst(".download-btn")?.attr("href")
                        if (buzzPath != null) emit(buzzBase + buzzPath, "[Buzz Server]")
                    }
                    link.contains("pixeldra") -> {
                        val pxl = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(pageHtml)?.groupValues?.get(1)
                            ?: link
                        val pxlBase = getBaseUrl(pxl)
                        val direct = if (pxl.contains("download", true)) pxl
                        else "$pxlBase/api/file/${pxl.substringAfterLast("/")}?download"
                        emit(direct, "[Pixeldrain]")
                    }
                    text.contains("Gofile") -> loadExtractor(link, referer, subtitleCallback, callback)
                    text.contains("10Gbps") || text.contains("Server : 10Gbps") -> {
                        var final = resolveFinalUrl(link) ?: return@forEach
                        if (final.contains("link=")) final = final.substringAfter("link=")
                        emit(final, "[Download]")
                    }
                    else -> {
                        // unknown buttons still resolve when they are plain redirects to a file
                        if (link.contains("workers.dev") || link.contains(".workers.run")) emit(link, "[Server]")
                    }
                }
            }

            // single file layouts expose the direct download button outside the h2 group
            if (header.isBlank()) {
                driveDoc.select("a.btn").forEach { btn ->
                    val link = btn.attr("href")
                    if (link.startsWith("http") && (link.contains(".mp4") || link.contains(".mkv") || link.contains(".avi"))) {
                        emit(link, "")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "$name: ${e.message}")
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}

class VCloudExtractor : HubCloudExtractor() {
    override val name = "VCloud"
    override val mainUrl = "https://vcloud.*"
    override val hostKeyword = "vcloud"
}

class GofileExtractor : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    private val api = "https://api.gofile.io"
    private val browserLanguage = "en-GB"
    private val websiteSecret = "12af056dacea0b"
    private val userAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = Regex("""/(?:\?c=|d/)([\da-zA-Z-]+)""").find(url)?.groupValues?.get(1) ?: return

            val defaultHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            )
            val token = app.post("$api/accounts", headers = defaultHeaders)
                .parsedSafe<GofileAccount>()?.data?.token ?: return

            // gofile rejects api calls without a website token derived from the client profile
            val timeSlot = System.currentTimeMillis() / 1000 / 14400
            val raw = "$userAgent::$browserLanguage::$token::$timeSlot::$websiteSecret"
            val hashed = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            val headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to userAgent,
                "Authorization" to "Bearer $token",
                "X-BL" to browserLanguage,
                "X-Website-Token" to hashed
            )

            val parsed = app.get(
                "$api/contents/$id?page=1&pageSize=100&sortField=name&sortDirection=1",
                headers = headers
            ).parsedSafe<GofileContent>()

            val children = parsed?.data?.children ?: return
            for ((_, file) in children) {
                if (file.link.isNullOrEmpty() || file.type != "file") continue
                val fileName = file.name ?: continue
                val size = file.size ?: 0L
                val sizeLabel = when {
                    size >= 1_073_741_824L -> "%.2f GB".format(size.toDouble() / 1_073_741_824L)
                    size >= 1_048_576L -> "%.2f MB".format(size.toDouble() / 1_048_576L)
                    else -> "$size B"
                }
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name $fileName [$sizeLabel]",
                        url = file.link,
                        referer = mainUrl,
                        quality = getQualityFromName(fileName),
                        type = ExtractorLinkType.VIDEO,
                        headers = mapOf("Cookie" to "accountToken=$token")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gofile: ${e.message}")
        }
    }
}

private data class GofileAccount(@JsonProperty("data") val data: GofileAccountData? = null)
private data class GofileAccountData(@JsonProperty("token") val token: String? = null)
private data class GofileContent(@JsonProperty("data") val data: GofileContentData? = null)
private data class GofileContentData(@JsonProperty("children") val children: Map<String, GofileFile>? = null)
private data class GofileFile(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("size") val size: Long? = 0L
)

open class GDFlixExtractor : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val baseUrl = getBaseUrl(url)
            val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
            val fileName = doc.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name : ").trim()
            val fileSize = doc.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size : ").trim()
            val quality = getIndexQuality(fileName)

            suspend fun emit(link: String, server: String) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name$server $fileName [$fileSize]",
                        url = link,
                        referer = baseUrl,
                        quality = quality,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }

            doc.select("div.text-center a").forEach { anchor ->
                val text = anchor.text()
                val link = anchor.attr("href")
                if (link.isBlank()) return@forEach
                when {
                    text.contains("FSL V2") -> emit(link, "[FSL V2]")
                    text.contains("DIRECT DL") || text.contains("DIRECT SERVER") -> emit(link, "[Direct]")
                    text.contains("CLOUD DOWNLOAD [R2]") -> emit(link, "[Cloud]")
                    text.contains("GD Index") -> {
                        listOf(1, 2).forEach { cfType ->
                            try {
                                app.get("$baseUrl$link?type=$cfType").document
                                    .select("a.btn-success").forEach {
                                        val source = it.attr("href")
                                        if (source.startsWith("http")) emit(source, "[CF]")
                                    }
                            } catch (e: Exception) {
                                Log.d(TAG, "gdindex type $cfType: ${e.message}")
                            }
                        }
                    }
                    text.contains("FAST CLOUD") -> {
                        val fast = app.get("$baseUrl$link").document
                            .selectFirst("div.card-body a")?.attr("href")
                        if (!fast.isNullOrBlank()) emit(fast, "[Fast Cloud]")
                    }
                    link.contains("pixeldra") -> {
                        val pxlBase = getBaseUrl(link)
                        val direct = if (link.contains("download", true)) link
                        else "$pxlBase/api/file/${link.substringAfterLast("/")}?download"
                        emit(direct, "[Pixeldrain]")
                    }
                    text.contains("Instant DL") -> {
                        val location = app.get(link, allowRedirects = false).headers["location"]
                        val instant = location?.substringAfter("url=")
                        if (!instant.isNullOrBlank() && instant.startsWith("http")) emit(instant, "[Instant]")
                    }
                    text.contains("GoFile") -> loadExtractor(link, referer, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GDFlix: ${e.message}")
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}

open class DriveleechExtractor : ExtractorApi() {
    override val name = "Driveleech"
    override val mainUrl = "https://driveleech.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val baseUrl = getBaseUrl(url)
            val doc = if (url.contains("r?key=")) {
                val hop = app.get(url).document.selectFirst("script")?.data()
                    ?.substringAfter("replace(\"")?.substringBefore("\")").orEmpty()
                if (hop.isBlank()) return
                app.get(baseUrl + hop).document
            } else {
                app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
            }

            val fileName = doc.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name : ").trim()
            val fileSize = doc.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size : ").trim()
            val quality = getIndexQuality(fileName)

            suspend fun emit(link: String, server: String) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name$server $fileName [$fileSize]",
                        url = link,
                        referer = baseUrl,
                        quality = quality,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }

            doc.select("div.text-center > a").forEach { anchor ->
                val text = anchor.text()
                val href = anchor.attr("href")
                if (href.isBlank()) return@forEach
                when {
                    text.contains("Cloud Download") -> emit(href, "[Cloud]")
                    text.contains("Instant Download") -> {
                        val instant = app.get(href, allowRedirects = false)
                            .headers["location"]?.substringAfter("?url=")
                        if (!instant.isNullOrBlank() && instant.startsWith("http")) emit(instant, "[Instant]")
                    }
                    text.contains("Resume Worker Bot") -> {
                        // worker bot pages exchange a token for the file url in a json post
                        val page = app.get(href)
                        val token = Regex("""formData\.append\('token', '([a-f0-9]+)'\)""")
                            .find(page.document.toString())?.groupValues?.get(1)
                        val path = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""")
                            .find(page.document.toString())?.groupValues?.get(1)
                        if (token != null && path != null) {
                            val botBase = href.substringBefore("/download")
                            val body = app.post(
                                "$botBase/download?id=$path",
                                data = mapOf("token" to token),
                                cookies = mapOf("PHPSESSID" to page.cookies["PHPSESSID"].orEmpty()),
                                referer = href
                            ).text
                            Regex("""\"url\"\s*:\s*\"([^\"]+)\"""").find(body)?.groupValues?.get(1)
                                ?.let { emit(it, "[ResumeBot]") }
                        }
                    }
                    text.contains("Direct Links") -> {
                        listOf("1", "2").forEach { type ->
                            try {
                                app.get("$baseUrl$href?type=$type")
                                    .document.select("a.btn-success").forEach {
                                        val link = it.attr("href")
                                        if (link.startsWith("http")) emit(link, "[CF]")
                                    }
                            } catch (e: Exception) {
                                Log.d(TAG, "driveleech cf: ${e.message}")
                            }
                        }
                    }
                    text.contains("Resume Cloud") -> {
                        val resume = app.get(baseUrl + href).document
                            .selectFirst("a.btn-success")?.attr("href")
                        if (!resume.isNullOrBlank() && resume.startsWith("http")) emit(resume, "[ResumeCloud]")
                    }
                    text.contains("gofile", true) -> loadExtractor(href, referer, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Driveleech: ${e.message}")
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}

class DriveseedExtractor : DriveleechExtractor() {
    override val name = "Driveseed"
    override val mainUrl = "https://driveseed.*"
}
