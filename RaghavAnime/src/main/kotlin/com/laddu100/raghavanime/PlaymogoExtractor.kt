package com.laddu100.raghavanime

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Playmogo (DoodStream-style host behind Cloudflare) extractor.
 * Overrides the broken built-in Playmogo extractor (which builds
 * "playmogo.comnull" URLs); this one renders the embed in a WebView
 * and intercepts the resulting .m3u8/.mp4.
 */
class RaghavPlaymogo : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RaghavAnime", "[Playmogo] resolving ${url.take(80)}")
        runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 20_000L
            )
            val resolved = app.get(url, referer = referer ?: mainUrl, interceptor = resolver).url
            Log.d("RaghavAnime", "[Playmogo] resolved ${resolved.take(80)}")
            val headers = mapOf("Referer" to url)
            when {
                resolved.contains(".m3u8", ignoreCase = true) -> {
                    Log.d("RaghavAnime", "[Playmogo] m3u8 resolved, generating links")
                    generateM3u8(name, resolved, mainUrl, headers = headers).forEach(callback)
                }
                resolved.contains(".mp4", ignoreCase = true) -> {
                    Log.d("RaghavAnime", "[Playmogo] mp4 resolved")
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
                }
                else -> {
                    Log.e("RaghavAnime", "[Playmogo] resolved url is neither m3u8 nor mp4: ${resolved.take(80)}")
                }
            }
        }.onFailure { error ->
            Log.e("RaghavAnime", "[Playmogo] failed to resolve ${url.take(80)}: ${error.message}")
        }
    }
}
