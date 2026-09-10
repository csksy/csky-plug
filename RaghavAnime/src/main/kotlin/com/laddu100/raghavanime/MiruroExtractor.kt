package com.laddu100.raghavanime

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

class MiruroVidWish(sourceName: String = "VidWish") : MiruroMegaPlay(sourceName) {
    override val mainUrl = "https://vidwish.live"
}

open class MiruroMegaPlay(private val sourceName: String = "MegaPlay") : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RaghavAnime", "[Miruro][${name}] getUrl: url=${url.take(120)} referer=$referer")
        val stream = MegaPlayHelper.resolveStream(url, referer ?: "$mainUrl/", "Miruro")
        if (stream != null) {
            MegaPlayHelper.emitLinks(
                name, name, stream.m3u8, "$mainUrl/",
                stream.subtitles, subtitleCallback, callback
            )
            return
        }

        // the megaplay family occasionally hides the playlist behind a player
        // only a real browser can drive, so fall back to interception
        Log.e("RaghavAnime", "[Miruro][${name}] direct extraction failed, trying WebViewResolver fallback")
        runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                script = """document.querySelector('.jw-icon-display')?.click();""",
                useOkhttp = false,
                timeout = 30_000L
            )
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
                "Referer" to "$mainUrl/"
            )
            val m3u8 = app.get(url, referer = mainUrl, interceptor = resolver).url
            Log.d("RaghavAnime", "[Miruro][${name}] WebViewResolver resolved: ${m3u8.take(120)}")
            if (m3u8.contains(".m3u8")) {
                generateM3u8(name, m3u8, mainUrl, headers = headers).forEach(callback)
            }
        }.onFailure { error ->
            Log.e("RaghavAnime", "[Miruro][${name}] WebViewResolver fallback failed: ${error.message}")
        }
    }
}

class MiruroWebView(private val sourceName: String, private val baseUrl: String) : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = baseUrl
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RaghavAnime", "[Miruro][WebView] getUrl: source=$sourceName url=${url.take(120)} referer=${referer?.take(120)}")
        runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 30_000L
            )
            val resolved = app.get(url, referer = referer ?: mainUrl, interceptor = resolver).url
            Log.d("RaghavAnime", "[Miruro][WebView] resolution result: ${resolved.take(120)}")
            val headers = mapOf("Referer" to url)
            when {
                resolved.contains(".m3u8", ignoreCase = true) -> {
                    Log.d("RaghavAnime", "[Miruro][WebView] m3u8 resolved, generating M3u8 links")
                    generateM3u8(name, resolved, mainUrl, headers = headers).forEach(callback)
                }
                resolved.contains(".mp4", ignoreCase = true) -> {
                    Log.d("RaghavAnime", "[Miruro][WebView] mp4 resolved, emitting direct link")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = resolved,
                            type = INFER_TYPE
                        ) {
                            quality = getQualityFromName(resolved)
                            this.headers = headers
                        }
                    )
                    Log.d("RaghavAnime", "[Miruro][WebView] emit link: $name url=${resolved.take(120)}")
                }
            }
        }.onFailure { error ->
            Log.e("RaghavAnime", "[Miruro][WebView] getUrl failed: ${error.message}")
        }
    }
}
