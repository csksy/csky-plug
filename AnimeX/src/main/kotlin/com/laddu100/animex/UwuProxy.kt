package com.laddu100.animex

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.api.Log

object UwuProxy {

    private const val TAG = "AnimeX"
    private const val PROXY_BASE = "https://cdnx.aniwatchtv.site"
    private const val PROXY_VAULT = "https://vault.aniwatchtv.site"
    private const val XOR_KEY = "10b06cdc1ca48c9fb0b94af97cc040cf"

    private val passthroughProviders = setOf("vee", "neko", "loli")

    data class QualityEntry(
        val url: String,
        val height: Int?,
        val label: String?
    )

    fun buildToken(url: String, referer: String, userAgent: String? = null): String {
        val payload = buildList {
            add(url.toByteArray(Charsets.UTF_8))
            add(byteArrayOf(0))
            add(referer.toByteArray(Charsets.UTF_8))
            if (!userAgent.isNullOrBlank()) {
                add(byteArrayOf(0))
                add(userAgent.toByteArray(Charsets.UTF_8))
            }
        }.reduce { acc, bytes -> acc + bytes }
        val key = XOR_KEY.toByteArray(Charsets.UTF_8)
        val out = ByteArray(payload.size)
        for (i in payload.indices) out[i] = (payload[i].toInt() xor key[i % 32].toInt()).toByte()
        return Base64.encodeToString(out, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }

    fun proxyUrl(url: String, referer: String, userAgent: String? = null, base: String = PROXY_BASE): String =
        "$base/uwu/${buildToken(url, referer, userAgent)}"

    private fun String.normalized(): String {
        var u = this.trim()
        while (u.contains(":///")) u = u.replace(":///", "://")
        if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
        return u
    }

    fun transformProviderUrl(
        rawUrl: String,
        providerId: String,
        headers: Map<String, String>?
    ): String {
        val original = rawUrl.normalized()
        if (!original.startsWith("http")) return original
        var url = original

        url = url.replace(
            "https://vivibebe.site/public/stream/",
            "https://hawk.aniwatchtv.site/media/"
        )
        if (url.startsWith("https://playeng.animeapps.top/r2/")) {
            url = "https://bd.aniwatchtv.site/media" +
                url.removePrefix("https://playeng.animeapps.top/r2")
        }

        val id = providerId.lowercase()
        val referer = headers?.get("Referer") ?: headers?.get("referer")
        val ua = headers?.get("User-Agent") ?: headers?.get("user-agent")

        val transformed: String = when (id) {
            "sora" -> proxyUrl(url, "https://krussdomi.com")
            "vee", "neko", "loli" -> url
            "yuki" -> proxyUrl(url, referer?.takeIf { it.startsWith("http") } ?: "https://megaplay.buzz", ua)
            "uwu" -> proxyUrl(url, "https://kwik.cx/", null, PROXY_VAULT)
            "kiwi" -> proxyUrl(url, "https://anidb.app/")
            "miku" -> proxyUrl(url, "https://allanime.uns.bio")
            "mochi" -> url.replace(
                "https://tools.fast4speed.rsvp",
                "https://mp4.24stream.xyz/storage"
            )
            "beep" -> when {
                url.startsWith("https://bd.24stream.xyz/media") ||
                    url.startsWith("https://bd.aniwatchtv.site/media") -> url
                url.startsWith("/") -> "https://bd.aniwatchtv.site/media" +
                    url.replace("/r2", "")
                else -> {
                    val path = url.replace(Regex("""https?://[^/]+"""), "").replace("/r2", "")
                    "https://bd.aniwatchtv.site/media$path"
                }
            }
            else -> url
        }

        if (transformed == original && !referer.isNullOrBlank() && id !in passthroughProviders) {
            return proxyUrl(original, referer, ua)
        }
        return transformed
    }

    fun transformSubtitleUrl(
        rawUrl: String,
        providerId: String,
        headers: Map<String, String>?
    ): String {
        val url = rawUrl.trim().let { u ->
            var v = u
            while (v.contains(":///")) v = v.replace(":///", "://")
            v
        }
        if (!url.startsWith("http")) return url
        if (providerId.equals("beep", true)) return url
        return transformProviderUrl(url, providerId, headers)
    }

    private fun resolveUri(masterUrl: String, uri: String): String {
        val clean = uri.trim()
        if (clean.startsWith("http")) {
            return clean
        }
        val m = Regex("""^(https?://[^/]+)(/[^\?#]*)?""").find(masterUrl) ?: return clean
        val (schemeHost, fullPath) = m.destructured
        if (clean.startsWith("/")) {
            return schemeHost + clean
        }
        val dir = fullPath.substringBeforeLast('/', "")
        return "$schemeHost$dir/$clean"
    }

    suspend fun expandQualities(
        playlistUrl: String,
        headers: Map<String, String> = mapOf("User-Agent" to AnimeXApi.USER_AGENT)
    ): List<QualityEntry> {
        val text = try {
            val resp = app.get(playlistUrl, headers = headers)
            if (resp.isSuccessful) resp.text else null
        } catch (e: Exception) {
            Log.d(TAG, "quality expand failed: ${e.message?.take(60)}")
            null
        }
        if (text == null || !text.contains("#EXTM3U")) {
            return listOf(QualityEntry(playlistUrl, null, null))
        }
        if (!text.contains("#EXT-X-STREAM-INF")) {
            return listOf(QualityEntry(playlistUrl, null, null))
        }
        val out = mutableListOf<QualityEntry>()
        val lines = text.lines()
        var pendingHeight: Int? = null
        var pendingName: String? = null
        var expectingUri = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                pendingHeight = Regex("""RESOLUTION=\d+x(\d+)""").find(trimmed)
                    ?.groupValues?.get(1)?.toIntOrNull()
                pendingName = Regex("""NAME="([^"]+)"""").find(trimmed)?.groupValues?.get(1)
                expectingUri = true
            } else if (expectingUri && trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val resolved = resolveUri(playlistUrl, trimmed)
                if (resolved.startsWith("http")) {
                    out.add(QualityEntry(resolved, pendingHeight, pendingName))
                }
                pendingHeight = null
                pendingName = null
                expectingUri = false
            }
        }
        return if (out.isEmpty()) listOf(QualityEntry(playlistUrl, null, null)) else out
    }
}
