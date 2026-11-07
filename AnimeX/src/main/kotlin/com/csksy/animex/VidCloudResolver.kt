package com.csksy.animex

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URLEncoder

// the Zoro tab embeds vidcloud.se, a megaplay-family host behind cloudflare:
// /s-1/{anilistId}/{ep}/{softsub|dub} keyed by anilist, /s-2/{malId} keyed by mal,
// stream list from /stream/getSources?id={id}&type={sub|dub}
object VidCloudResolver {

    private const val TAG = "AnimeX"
    private const val HOST = "https://vidcloud.se"

    private val mapper = ObjectMapper()
    private val cfKiller by lazy { CloudflareKiller() }

    private val pageHeaders = mapOf(
        "User-Agent" to AnimeXApi.USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "${AnimeXApi.SITE}/",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "cross-site"
    )

    class VidCloudStream(
        val m3u8: String,
        val subtitles: List<Pair<String, String>>
    )

    private fun isCloudflareBlock(status: Int, text: String): Boolean =
        status == 403 || status == 503 || text.contains("Just a moment...") ||
            text.contains("challenge-platform") || text.contains("Attention Required")

    private suspend fun fetchPage(url: String): String? {
        val direct = try {
            val resp = app.get(url, headers = pageHeaders, timeout = 15_000L)
            if (resp.isSuccessful && !isCloudflareBlock(resp.code, resp.text)) resp.text else null
        } catch (_: Exception) {
            null
        }
        if (direct != null) return direct
        return try {
            val resp = app.get(url, headers = pageHeaders, timeout = 20_000L, interceptor = cfKiller)
            if (resp.isSuccessful) resp.text else null
        } catch (e: Exception) {
            Log.d(TAG, "vidcloud page unavailable: ${e.message}")
            null
        }
    }

    private suspend fun fetchApi(url: String, referer: String): JsonNode? {
        val headers = mapOf(
            "User-Agent" to AnimeXApi.USER_AGENT,
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to HOST,
            "Referer" to referer
        )
        return try {
            val resp = app.get(url, headers = headers, timeout = 15_000L, interceptor = cfKiller)
            mapper.readTree(resp.text)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractStreamId(html: String): String? {
        Regex("""data-id=["'](\d+)""").find(html)?.groupValues?.get(1)?.let { return it }
        Regex("""data-realid=["'](\d+)""").find(html)?.groupValues?.get(1)?.let { return it }
        Regex("""["'](?:fileId|videoId|streamId)["']\s*:\s*["']?(\d+)""").find(html)
            ?.groupValues?.get(1)?.let { return it }
        return null
    }

    suspend fun resolveStream(anilistId: Int?, malId: Int?, epNum: Int, audio: String): VidCloudStream? {
        val language = if (audio == "dub") "dub" else "softsub"
        val urlKey = when {
            anilistId != null && anilistId > 0 -> "s-1/$anilistId"
            malId != null && malId > 0 -> "s-2/$malId"
            else -> return null
        }
        val embedUrl = "$HOST/$urlKey/$epNum/$language"

        val html = fetchPage(embedUrl) ?: return null
        val streamId = extractStreamId(html) ?: return null

        val apiType = if (audio == "dub") "dub" else "sub"
        for (type in listOf(apiType, language)) {
            val root = fetchApi(
                "$HOST/stream/getSources?id=$streamId&type=$type",
                embedUrl
            ) ?: continue
            val stream = extractFromSources(root)
            if (stream != null) return stream
        }
        return null
    }

    private suspend fun extractFromSources(root: JsonNode): VidCloudStream? {
        val sources = root.get("sources")
        val plain = when {
            sources == null || sources.isNull -> null
            sources.isObject -> sources.get("file")?.asText()
            sources.isArray && sources.size() > 0 -> sources.get(0)?.get("file")?.asText()
            else -> null
        }
        val url = when {
            !plain.isNullOrBlank() -> plain
            else -> {
                val enc = root.get("enc")?.takeIf { !it.isNull }?.asText()
                    ?: root.get("encrypted")?.takeIf { !it.isNull }?.asText()
                enc?.let { MegaPlayCipher.resolveEncStreamUrl(it, HOST) }
            }
        } ?: return null

        val subs = mutableListOf<Pair<String, String>>()
        val tracks = root.get("tracks")
        if (tracks != null && tracks.isArray) {
            for (element in tracks) {
                val kind = element.get("kind")?.asText() ?: continue
                if (kind != "captions" && kind != "subtitles") continue
                val file = element.get("file")?.asText() ?: continue
                if (file.isBlank()) continue
                subs.add((element.get("label")?.asText() ?: "English") to file)
            }
        }
        return VidCloudStream(url, subs)
    }

    private data class VariantEntry(val url: String, val quality: Int?)

    private fun parseVariants(masterUrl: String, masterText: String): List<VariantEntry> {
        val base = masterUrl.substringBefore('?').let { it.substringBeforeLast('/') + "/" }
        val out = mutableListOf<VariantEntry>()
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                val quality = res?.groupValues?.get(2)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    if (uri.isNotEmpty()) {
                        val absolute = if (uri.startsWith("http")) uri else base + uri
                        out.add(VariantEntry(absolute, quality))
                    }
                    i = j
                }
            }
            i++
        }
        return out
    }

    suspend fun emitLinks(
        source: String,
        label: String,
        stream: VidCloudStream,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playHeaders = mapOf(
            "User-Agent" to AnimeXApi.USER_AGENT,
            "Origin" to HOST,
            "Referer" to "$HOST/"
        )

        val masterText = try {
            app.get(stream.m3u8, headers = playHeaders, timeout = 15_000L).text
        } catch (_: Exception) {
            null
        }

        var found = false
        val hasAudioRenditions = masterText?.contains("#EXT-X-MEDIA:") == true
        val variants = if (hasAudioRenditions) {
            emptyList()
        } else {
            masterText?.let { parseVariants(stream.m3u8, it) }.orEmpty()
        }
        if (variants.isNotEmpty()) {
            for (variant in variants) {
                val suffix = variant.quality?.let { "${it}p" } ?: ""
                callback.invoke(
                    newExtractorLink(
                        source,
                        if (suffix.isEmpty()) label else "$label $suffix",
                        variant.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        variant.quality?.let { quality = it }
                        this.headers = playHeaders
                    }
                )
                found = true
            }
        } else if (masterText != null) {
            val type = if (stream.m3u8.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            callback.invoke(
                newExtractorLink(source, label, stream.m3u8, type = type) {
                    this.headers = playHeaders
                }
            )
            found = true
        }

        for ((subLabel, subUrl) in stream.subtitles) {
            subtitleCallback.invoke(
                newSubtitleFile(subLabel, subUrl) {
                    this.headers = playHeaders
                }
            )
        }
        return found
    }
}
