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

// the ZEN tab points at flixcloud: the download route hands one plain file with
// every audio track inside, while the /e player only serves hls behind a wasm
// crypto layer, so downloadable formats are preferred and hls entries only
// contribute their subtitle tracks
object FlixCdnResolver {

    private const val TAG = "AnimeX"
    private const val FLIX = "https://flixcloud.cc"

    private val mapper = ObjectMapper()
    private val cfKiller by lazy { CloudflareKiller() }

    private val headers = mapOf(
        "User-Agent" to AnimeXApi.USER_AGENT,
        "Origin" to FLIX,
        "Referer" to "$FLIX/",
        "Accept" to "*/*"
    )

    // flixcloud sits behind cloudflare and starts challenging under bursty
    // traffic, the cleared webview cookies ride the retry when that happens
    private suspend fun flixGet(url: String, timeout: Long = 15_000L): String? {
        val direct = try {
            val resp = app.get(url, headers = headers, timeout = timeout)
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        }
        if (direct != null) return direct
        return try {
            val resp = app.get(url, headers = headers, timeout = timeout, interceptor = cfKiller)
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        }
    }

    class FlixFile(
        val url: String,
        val quality: Int?,
        val label: String
    )

    class ZenSubtitles(
        val entries: List<SubEntry>
    ) {
        class SubEntry(val url: String, val label: String)
    }

    // sveltekit de-references the payload into a flat array: the video fields
    // sit at fixed slots behind the key template
    private fun videoString(data: JsonNode, index: Int): String? =
        data.get(index)?.takeIf { it.isTextual }?.asText()

    suspend fun resolve(accessId: String): FlixFile? {
        return try {
            val raw = flixGet("$FLIX/d/$accessId/__data.json") ?: return null
            val data = runCatching {
                mapper.readTree(raw).get("nodes")?.get(1)?.get("data")
            }.getOrNull() ?: return null

            val fileId = videoString(data, 2) ?: return null
            val sizeHuman = videoString(data, 6)
            val resolution = videoString(data, 8)
            val format = videoString(data, 10) ?: return null
            val base = videoString(data, 16) ?: FLIX
            val token = videoString(data, 17) ?: return null

            // hls entries expose no download file at all, the route answers 404
            if (format.equals("HLS", ignoreCase = true)) return null

            val encodedToken = URLEncoder.encode(token, "UTF-8")
            val downloadUrl = "$base/download/$fileId?token=$encodedToken"
            val progressUrl = "$base/download/$fileId/progress?token=$encodedToken"

            // the progress stream doubles as the preparation trigger, a ready
            // status from a previous client can also be stale so the file is
            // probed and the stream reopened once before giving up
            var ready = openProgress(progressUrl)
            if (ready && !downloadReachable(downloadUrl)) {
                ready = openProgress(progressUrl)
            }
            if (!ready || !downloadReachable(downloadUrl)) return null

            val quality = resolution?.takeWhile { it.isDigit() }?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            val label = buildString {
                append("ZEN")
                quality?.let { append(" $it") }
                sizeHuman?.let { append(" ($it)") }
            }
            FlixFile(downloadUrl, quality, label)
        } catch (e: Exception) {
            Log.e(TAG, "flixcloud resolve failed: ${e.message}")
            null
        }
    }

    private suspend fun openProgress(progressUrl: String): Boolean = try {
        val text = flixGet(progressUrl, timeout = 20_000L)
        text?.contains("\"ready\"") == true
    } catch (_: Exception) {
        false
    }

    private suspend fun downloadReachable(url: String): Boolean = try {
        val resp = app.get(
            url,
            headers = headers + mapOf("Range" to "bytes=0-32"),
            timeout = 15_000L
        )
        resp.isSuccessful
    } catch (_: Exception) {
        false
    }

    suspend fun emit(file: FlixFile, callback: (ExtractorLink) -> Unit) {
        callback.invoke(
            newExtractorLink("AnimeX", file.label, file.url, type = ExtractorLinkType.VIDEO) {
                file.quality?.let { quality = it }
                this.headers = this@FlixCdnResolver.headers - "Accept"
            }
        )
    }

    // the /e page carries the subtitle list in plain sight, one fetch per entry
    suspend fun subtitles(accessId: String): List<ZenSubtitles.SubEntry> {
        val entries = mutableListOf<ZenSubtitles.SubEntry>()
        return try {
            val body = flixGet("$FLIX/e/$accessId?v=2") ?: return entries
            val start = body.indexOf("subtitles:[")
            if (start < 0) return entries
            val end = body.indexOf(']', start)
            if (end < 0) return entries
            val segment = body.substring(start + "subtitles:".length, end + 1)
            Regex("""url:"([^"]+)",language:"([^"]+)"""").findAll(segment).forEach { match ->
                val url = match.groupValues[1]
                val language = match.groupValues[2]
                if (url.startsWith("http")) {
                    entries.add(ZenSubtitles.SubEntry(url, language))
                }
            }
            entries
        } catch (e: Exception) {
            Log.e(TAG, "flixcloud subtitles failed: ${e.message}")
            entries
        }
    }

    suspend fun emitSubtitles(
        entries: List<ZenSubtitles.SubEntry>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        // the files live on the sub cdn hosts, a plain browser header set is
        // all they ask for
        val subHeaders = mapOf("User-Agent" to AnimeXApi.USER_AGENT)
        for (entry in entries) {
            subtitleCallback.invoke(
                newSubtitleFile(normalizeZenLabel(entry.label), entry.url) {
                    this.headers = subHeaders
                }
            )
        }
    }

    // "English (Signs and Songs)" becomes "en - Signs and Songs" so the player
    // keeps both the language code and the variant the file actually carries
    private fun normalizeZenLabel(label: String): String {
        val open = label.indexOf('(')
        val primary = if (open > 0) label.substring(0, open).trim() else label
        val variant = if (open > 0) label.substring(open).trim() else null
        val code = when (primary.lowercase()) {
            "english" -> "en"
            "arabic" -> "ar"
            "spanish" -> "es"
            "french" -> "fr"
            "german" -> "de"
            "portuguese" -> "pt"
            "russian" -> "ru"
            "italian" -> "it"
            "japanese" -> "ja"
            "hindi" -> "hi"
            else -> primary
        }
        return if (variant != null) "$code $variant" else code
    }
}
