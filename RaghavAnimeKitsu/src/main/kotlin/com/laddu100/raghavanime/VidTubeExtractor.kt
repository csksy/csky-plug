package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink

// vidtube.site embeds: data-id on the player element -> /stream/getSourcesNew?id=
// playback needs Referer https://vidtube.site/ (CDN 403s otherwise)
class VidTubeExtractor(private val sourceName: String = "VidTube") : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = "https://vidtube.site"
    override val requiresReferer = false

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VTResponse(
        @JsonProperty("sources") val sources: VTSources? = null,
        @JsonProperty("tracks") val tracks: List<VTTrack>? = null
    )

    data class VTSources(@JsonProperty("file") val file: String? = null)

    data class VTTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RaghavAnimeKitsu", "[VidTube] getUrl: ${url.take(120)} referer=$referer")
        try {
            val doc = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document
            val id = doc.selectFirst("[data-id]")?.attr("data-id")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-id=["']([^"']+)["']""").find(doc.html())?.groupValues?.get(1)
            if (id.isNullOrBlank()) {
                Log.d("RaghavAnimeKitsu", "[VidTube] no data-id found on embed page (htmlLen=${doc.html().length})")
                return
            }
            Log.d("RaghavAnimeKitsu", "[VidTube] streamId=$id")

            // the embed url itself is the required referer for the ajax call
            val ajaxHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Accept" to "*/*"
            )

            var payload: VTResponse? = null
            for (endpoint in listOf("getSourcesNew", "getSources")) {
                try {
                    val text = app.get("$mainUrl/stream/$endpoint?id=$id", headers = ajaxHeaders).text
                    payload = parseJson(text)
                    if (payload?.sources?.file != null) {
                        Log.d("RaghavAnimeKitsu", "[VidTube] $endpoint OK: ${payload.sources.file.take(100)}")
                        break
                    }
                } catch (e: Exception) {
                    Log.d("RaghavAnimeKitsu", "[VidTube] $endpoint failed: ${e.message}")
                }
            }

            val m3u8 = payload?.sources?.file
            if (m3u8.isNullOrBlank()) {
                Log.d("RaghavAnimeKitsu", "[VidTube] no m3u8 in either endpoint for id=$id")
                return
            }

            val playHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            )
            generateM3u8(name, m3u8, mainUrl, headers = playHeaders).forEach(callback)
            Log.d("RaghavAnimeKitsu", "[VidTube] emitted m3u8 links from $m3u8")

            payload.tracks?.forEach { track ->
                val file = track.file ?: return@forEach
                if (track.kind == "captions" || track.kind == "subtitles") {
                    subtitleCallback(
                        newSubtitleFile(track.label ?: "English", file) {
                            this.headers = playHeaders
                        }
                    )
                    Log.d("RaghavAnimeKitsu", "[VidTube] subtitle: ${track.label} ${file.take(100)}")
                }
            }
        } catch (e: Exception) {
            Log.e("RaghavAnimeKitsu", "[VidTube] extraction failed for ${url.take(120)}: ${e.message}")
        }
    }
}
