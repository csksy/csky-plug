package com.laddu100.rareanimes

import android.net.Uri
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal const val RAI_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

internal const val MAIN_HOST = "www.rareanimes.mov"
internal const val STORE_HOST = "store.animetoonhindi.com"
internal const val CODEDEW_HOST = "codedew.com"
internal const val ARGON_HOST = "argon.razorshell.space"
internal const val HUBCLOUD_HOST = "hubcloud.ist"
internal const val PIXELDRAIN_HOST = "pixeldrain.net"

private val hostMutex = Mutex()

private fun hostOf(url: String): String {
    return try {
        Uri.parse(url).host ?: ""
    } catch (e: Exception) {
        ""
    }
}

private fun buildHeaders(url: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
    val host = hostOf(url)
    val h = extra.toMutableMap()
    if (!h.containsKey("Accept")) {
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    }
    h["User-Agent"] = RAICFStore.getUserAgent(host) ?: RAI_UA
    if (!h.containsKey("Cookie")) {
        RAICFStore.getCookies(host)?.let { h["Cookie"] = it }
    }
    return h
}

internal suspend fun raiGet(
    url: String,
    headers: Map<String, String> = emptyMap(),
    allowRedirects: Boolean = true
): NiceResponse {
    var response = app.get(
        url,
        headers = buildHeaders(url, headers),
        timeout = 30_000L,
        allowRedirects = allowRedirects
    )

    if (!isRAICloudflareBlocked(response)) return response

    hostMutex.withLock {
        val host = hostOf(url)
        val base = "https://$host"
        val cachedCookies = RAICFStore.getCookies(host)
        if (cachedCookies != null) {
            response = app.get(
                url,
                headers = buildHeaders(url, headers),
                timeout = 30_000L,
                allowRedirects = allowRedirects
            )
            if (!isRAICloudflareBlocked(response)) return response
        }
        RAICFStore.clear(host)
        val bypassSuccess = showRAICFBypassDialogAndWait(base)
        if (!bypassSuccess) return@withLock
        for (attempt in 1..2) {
            response = app.get(
                url,
                headers = buildHeaders(url, headers),
                timeout = 30_000L,
                allowRedirects = allowRedirects
            )
            if (!isRAICloudflareBlocked(response)) return response
        }
    }
    return response
}

internal suspend fun raiPostBody(
    url: String,
    body: String,
    contentType: String,
    headers: Map<String, String> = emptyMap()
): NiceResponse {
    val requestHeaders = buildHeaders(url, headers).toMutableMap().apply {
        put("Content-Type", contentType)
    }
    var response = app.post(
        url,
        requestBody = body.toRequestBody(contentType.toMediaType()),
        headers = requestHeaders,
        timeout = 30_000L
    )

    if (!isRAICloudflareBlocked(response)) return response

    hostMutex.withLock {
        val host = hostOf(url)
        val base = "https://$host"
        val cachedCookies = RAICFStore.getCookies(host)
        if (cachedCookies != null) {
            response = app.post(
                url,
                requestBody = body.toRequestBody(contentType.toMediaType()),
                headers = requestHeaders,
                timeout = 30_000L
            )
            if (!isRAICloudflareBlocked(response)) return response
        }
        RAICFStore.clear(host)
        val bypassSuccess = showRAICFBypassDialogAndWait(base)
        if (!bypassSuccess) return@withLock
        for (attempt in 1..2) {
            response = app.post(
                url,
                requestBody = body.toRequestBody(contentType.toMediaType()),
                headers = requestHeaders,
                timeout = 30_000L
            )
            if (!isRAICloudflareBlocked(response)) return response
        }
    }
    return response
}

internal suspend fun raiPostJson(
    url: String,
    json: String,
    headers: Map<String, String> = emptyMap()
): NiceResponse = raiPostBody(url, json, "application/json", headers)

internal sealed class ResolvedTarget {
    data class Argon(val code: String) : ResolvedTarget()
    data class ArgonDownload(val code: String) : ResolvedTarget()
    data class PixelDrain(val id: String) : ResolvedTarget()
    data class HubCloud(val id: String) : ResolvedTarget()
    data class Archive(val url: String) : ResolvedTarget()
    data class Direct(val url: String) : ResolvedTarget()
    data class StreamBeta(val id: String) : ResolvedTarget()
    data class GoFile(val code: String) : ResolvedTarget()
    data class MediaFire(val url: String) : ResolvedTarget()
    data class Mega(val url: String) : ResolvedTarget()
}

private class CodedewChainException(message: String) : Exception(message)

internal fun htmlUnescape(s: String): String {
    return if (!s.contains('&')) s else s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#039;", "'")
}

internal class CodedewResolver {

    private fun absolutize(base: String, location: String): String {
        if (location.startsWith("http")) return location
        return try {
            val baseUri = Uri.parse(base)
            val origin = "${baseUri.scheme}://${baseUri.host}"
            when {
                location.startsWith("/") -> origin + location
                location.startsWith("?") -> origin + (baseUri.path ?: "") + location
                else -> "$origin/$location"
            }
        } catch (e: Exception) {
            if (location.startsWith("/")) "https://$CODEDEW_HOST$location" else location
        }
    }

    private fun classifyUrl(url: String): ResolvedTarget? {
        val u = htmlUnescape(url.substringBefore("#"))
        return when {
            u.contains("$CODEDEW_HOST/streambeta/") -> {
                val id = Uri.parse(u).getQueryParameter("url")
                if (id.isNullOrBlank()) null else ResolvedTarget.StreamBeta(id)
            }
            u.contains("gofile.io/") -> {
                val code = u.substringAfter("/d/").substringBefore("?").substringBefore("#")
                if (code.isBlank()) null else ResolvedTarget.GoFile(code)
            }
            u.contains("mega.nz/") || u.contains("mega.io/") -> ResolvedTarget.Mega(u)
            u.contains("$CODEDEW_HOST/multiquality/") -> {
                val code = Uri.parse(u).getQueryParameter("url")
                if (code.isNullOrBlank()) null else ResolvedTarget.Argon(code)
            }
            u.contains("$CODEDEW_HOST/cdn/ziptron.php/") || u.contains("$CODEDEW_HOST/ziptron.php/") -> {
                val afterQ = u.substringAfter("?")
                val code = afterQ.substringBefore("&").substringBefore("/").substringBefore("#")
                if (code.isBlank()) null else ResolvedTarget.ArgonDownload(code)
            }
            u.contains("$CODEDEW_HOST/watchbeta/") -> {
                val id = Uri.parse(u).getQueryParameter("url")
                if (id.isNullOrBlank()) null else ResolvedTarget.PixelDrain(id)
            }
            u.contains("$HUBCLOUD_HOST/drive/") -> {
                val id = u.substringAfter("$HUBCLOUD_HOST/drive/").substringBefore("?").substringBefore("/")
                if (id.isBlank()) null else ResolvedTarget.HubCloud(id)
            }
            u.contains("pixeldrain.net/u/") || u.contains("pixeldrain.dev/u/") ||
                u.contains("pixeldra.in/u/") -> {
                val id = u.substringAfter("/u/").substringBefore("?").substringBefore("/")
                if (id.isBlank()) null else ResolvedTarget.PixelDrain(id)
            }
            u.contains("$ARGON_HOST/embed/") -> {
                val code = u.substringAfter("/embed/").substringBefore("?").substringBefore("/")
                if (code.isBlank()) null else ResolvedTarget.Argon(code)
            }
            u.contains("$ARGON_HOST/downlead/") -> {
                val code = u.substringAfter("/downlead/").substringBefore("?")
                    .substringBefore("/").substringBefore("#")
                if (code.isBlank()) null else ResolvedTarget.ArgonDownload(code)
            }
            u.contains("mediafire.com/file/") -> ResolvedTarget.MediaFire(u)
            u.contains("$STORE_HOST/archives/") -> ResolvedTarget.Archive(u)
            u.contains("$CODEDEW_HOST/") -> null
            else -> null
        }
    }

    private suspend fun chaseDataHrefs(startUrl: String, referer: String): String {
        var current = startUrl
        var ref = referer
        for (hop in 0 until 8) {
            val resp = raiGet(
                current,
                headers = mapOf("Referer" to ref),
                allowRedirects = false
            )
            if (resp.code in 300..399) {
                val loc = resp.headers["location"] ?: return current
                current = absolutize(current, htmlUnescape(loc))
                ref = current
                classifyUrl(current)?.let { return current }
                continue
            }
            if (resp.code != 200) throw CodedewChainException("codedew hop $hop returned ${resp.code}")
            val body = resp.text
            val m = DATA_HREF.find(body) ?: return current
            current = absolutize(current, htmlUnescape(m.groupValues[1]))
            ref = current
            classifyUrl(current)?.let { return current }
        }
        return current
    }

    suspend fun resolve(url: String): ResolvedTarget {
        classifyUrl(url)?.let { return it }
        val current = when {
            url.contains("$CODEDEW_HOST/zipper/") ||
                url.contains("$CODEDEW_HOST/watchbeta/") ||
                url.contains("$CODEDEW_HOST/streambeta/") -> url
            url.contains("$CODEDEW_HOST/zipcloud/") -> {
                val afterQ = url.substringAfter("zipcloud/", "").substringAfter("?")
                val id = afterQ.substringBefore("&").substringBefore("/")
                if (id.isBlank()) throw CodedewChainException("empty zipcloud id")
                return ResolvedTarget.HubCloud(id)
            }
            url.contains("$HUBCLOUD_HOST/drive/") -> return classifyUrl(url)
                ?: ResolvedTarget.HubCloud(
                    url.substringAfter("$HUBCLOUD_HOST/drive/").substringBefore("?").substringBefore("/")
                )
            url.contains("pixeldrain") && url.contains("/u/") -> return classifyUrl(url)
                ?: throw CodedewChainException("bad pixeldrain url")
            url.contains("$STORE_HOST/archives/") -> return ResolvedTarget.Archive(url)
            else -> throw CodedewChainException("unhandled url: $url")
        }

        var target = current
        var referer = "https://$CODEDEW_HOST/"

        for (hop in 0 until 8) {
            val resp = raiGet(target, headers = mapOf("Referer" to referer), allowRedirects = false)

            if (resp.code in 300..399) {
                val loc = resp.headers["location"] ?: throw CodedewChainException("redirect without location")
                target = absolutize(target, htmlUnescape(loc))
                referer = "https://$CODEDEW_HOST/"
                classifyUrl(target)?.let { return it }
                continue
            }

            if (resp.code != 200) throw CodedewChainException("codedew returned ${resp.code}")

            classifyUrl(target)?.let { return it }

            val body = resp.text
            val dh = DATA_HREF.find(body) ?: throw CodedewChainException("no data-href on codedew page")
            target = absolutize(target, htmlUnescape(dh.groupValues[1]))
            referer = target
            classifyUrl(target)?.let { return it }
        }
        throw CodedewChainException("codedew chain too deep")
    }

    companion object {
        private val DATA_HREF = Regex("""data-href="([^"]+)"""")

        private val instance = CodedewResolver()

        suspend fun resolveUrl(url: String): ResolvedTarget = instance.resolve(url)
    }
}
