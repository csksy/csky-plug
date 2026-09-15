package com.anikoto

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

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
 * field, the master m3u8 needs an HMAC token, and the ?s= (tcdn) CDN flavor
 * must be avoided. See MegaPlayResolver for the verified details.
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

        MegaPlayResolver.emitLinks(
            name, name, stream.m3u8, "$mainUrl/",
            stream.subtitles, subtitleCallback, callback
        )
    }
}
