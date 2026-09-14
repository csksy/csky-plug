package com.anikoto

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class Vidwish : MegaPlay() {
    override val name = "Vidwish"
    override val mainUrl = "https://vidwish.live"
}

class Vidtube : MegaPlay() {
    override val name = "Vidtube"
    override val mainUrl = "https://vidtube.site"
}

/**
 * MegaPlay extractor — megaplay.buzz and clones.
 *
 * The legacy /stream/getSources endpoint now returns sources:null; the real
 * payload lives behind /stream/getSourcesNew with an AES-CBC encrypted "enc"
 * field. See MegaPlayResolver for the verified details.
 */
open class MegaPlay : ExtractorApi() {
    override val name = "MegaPlay"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val stream = MegaPlayResolver.resolveStream(url, referer)
            ?: run {
                Log.e("MegaPlay", "resolveStream failed for $url")
                return
            }

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
        )

        val generated = try {
            M3u8Helper.generateM3u8(name, stream.m3u8, mainUrl, headers = playbackHeaders)
        } catch (e: Exception) {
            Log.e("MegaPlay", "m3u8 expansion failed: ${e.message}")
            emptyList()
        }
        if (generated.isNotEmpty()) {
            generated.forEach(callback)
        } else {
            callback(
                newExtractorLink(name, name, stream.m3u8, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.headers = playbackHeaders
                }
            )
        }

        for ((label, file) in stream.subtitles) {
            subtitleCallback.invoke(
                com.lagradost.cloudstream3.newSubtitleFile(label, file) {
                    this.headers = playbackHeaders
                }
            )
        }
    }
}
