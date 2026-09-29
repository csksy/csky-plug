package com.justplay

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

internal const val PLAY_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal object PlayNet {
    const val TAG = "JustPlay"

    val cfKiller: CloudflareKiller by lazy { CloudflareKiller() }

    fun headers(referer: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = mutableMapOf("User-Agent" to PLAY_UA)
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

    fun seasonsOf(text: String): Set<Int>? {
        val seasons = mutableSetOf<Int>()
        Regex("(?i)Season\\s*(\\d{1,2})\\s*[-\\u2013]\\s*(\\d{1,2})").findAll(text).forEach {
            val a = it.groupValues[1].toIntOrNull() ?: return@forEach
            val b = it.groupValues[2].toIntOrNull() ?: return@forEach
            (a..b).forEach { s -> if (s in 1..50) seasons.add(s) }
        }
        Regex("(?i)\\bS(\\d{1,2})\\s*[-\\u2013]\\s*S?(\\d{1,2})\\b").findAll(text).forEach {
            val a = it.groupValues[1].toIntOrNull() ?: return@forEach
            val b = it.groupValues[2].toIntOrNull() ?: return@forEach
            (a..b).forEach { s -> if (s in 1..50) seasons.add(s) }
        }
        Regex("(?i)Season\\s*(\\d{1,2})").findAll(text).forEach {
            it.groupValues[1].toIntOrNull()?.let { s -> if (s in 1..50) seasons.add(s) }
        }
        Regex("""\bS(\d{1,2})\b""").findAll(text).forEach {
            it.groupValues[1].toIntOrNull()?.let { s -> if (s in 1..50) seasons.add(s) }
        }
        return seasons.ifEmpty { null }
    }

    // short names like It would match half the catalog so they need a full match,
    // and short phrases like The Boys would otherwise also match To All the Boys,
    // so those need the post to start with them once the site prefix is gone
    fun titleMatches(postTitle: String?, query: String): Boolean {
        if (postTitle.isNullOrBlank()) return false
        val normQuery = normalizeTitle(query)
        if (normQuery.isBlank()) return false
        if (!normalizeTitle(postTitle).contains(normQuery)) return false
        if (normQuery.length < 4) return normalizeTitle(postTitle) == normQuery
        if (normQuery.length >= 10) return true
        var stripped = normalizeTitle(postTitle)
        for (prefix in listOf("download", "watch")) {
            if (stripped.startsWith(prefix) && stripped.length > prefix.length) {
                stripped = stripped.substring(prefix.length)
            }
        }
        return stripped.startsWith(normQuery)
    }

    fun yearMatches(text: String, year: Int?): Boolean {
        if (year == null) return true
        val years = Regex("(19|20)\\d{2}").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
        if (years.isEmpty()) return true
        return years.any { kotlin.math.abs(it - year) <= 1 }
    }

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

    // greenmotors and the wp shorteners answer with a redirect first, the payload
    // with the real target only sits on the page after following it
    suspend fun decryptIdLink(url: String, referer: String? = null): String? {
        return try {
            val res = app.get(
                url,
                headers = headers(referer),
                allowRedirects = true,
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
                val body = reRes.document.body().text().trim()
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
        site: String,
        url: String,
        label: String,
        quality: Int? = null,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (url.isBlank()) return
        val prefix = "[$site]"
        try {
            val collected = mutableListOf<ExtractorLink>()
            loadExtractor(url, referer, subtitleCallback) { link ->
                collected.add(link)
            }
            for (link in collected) {
                val info = listOfNotNull(
                    link.name.takeIf { name -> name.isNotBlank() },
                    label.takeIf { l -> l.isNotBlank() }
                ).joinToString(" ")
                callback(
                    newExtractorLink(
                        prefix,
                        if (info.isBlank()) prefix else "$prefix - $info",
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
        } catch (e: Exception) {
            Log.d(TAG, "$site emit: ${e.message}")
        }
    }
}
