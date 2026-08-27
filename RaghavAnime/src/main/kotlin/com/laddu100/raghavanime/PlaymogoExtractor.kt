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
import kotlin.random.Random

/**
 * Playmogo extractor (DoodStream white-label behind Cloudflare Turnstile).
 *
 * The embed page usually serves a captcha interstitial: clicking `.captcha_l`
 * renders a Cloudflare Turnstile widget which auto-solves, GETs
 * /dood?op=validate and reloads the page - only then does the real Dood
 * player appear. So:
 *  1. fast path - if the page already carries a pass_md5 token, do the
 *     standard DoodStream dance directly (no WebView needed);
 *  2. WebView path - click the captcha (or the play button), let Turnstile
 *     auto-solve, the page reloads, the player starts and the resulting
 *     .mp4/.m3u8 request gets intercepted.
 */
class RaghavPlaymogo : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = true

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RaghavAnime", "[Playmogo] resolving ${url.take(80)}")

        // ---- 1. fast path: standard DoodStream pass_md5 dance ----
        try {
            val html = app.get(url, referer = referer ?: mainUrl, headers = baseHeaders).text
            val md5 = Regex("""['"]*/pass_md5/([^'"?]+)""").find(html)?.groupValues?.get(1)
            if (md5 != null) {
                val host = Regex("""https?://([^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl.removePrefix("https://")
                // token appears in the embed page (used for the playback url signature)
                val token = Regex("""['"]*/token/([^'"?]+)""").find(html)?.groupValues?.get(1)
                val apiUrl = "https://$host/pass_md5/$md5" + if (token != null) "?token=$token&expiry=${System.currentTimeMillis()}" else ""
                val backendUrl = app.get(apiUrl, referer = url, headers = baseHeaders).text
                if (backendUrl.startsWith("http")) {
                    val random = (1..10).map { Random.nextInt(97, 123).toChar() }.joinToString("")
                    val expiry = System.currentTimeMillis()
                    val videoUrl = "$backendUrl$random" + if (token != null) "?token=$token&expiry=$expiry" else ""
                    Log.d("RaghavAnime", "[Playmogo] fast path resolved ${videoUrl.take(80)}")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = INFER_TYPE
                        ) {
                            quality = getQualityFromName(videoUrl)
                            this.headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to baseHeaders["User-Agent"]!!
                            )
                        }
                    )
                    return
                }
            }
            if (!html.contains("captcha_l") && md5 == null) {
                Log.d("RaghavAnime", "[Playmogo] no pass_md5 and no captcha in page (len=${html.length}), falling through to WebView")
            }
        } catch (e: Exception) {
            Log.d("RaghavAnime", "[Playmogo] fast path failed (${e.message}), falling through to WebView")
        }

        // ---- 2. WebView path: trigger the Turnstile captcha, let it auto-solve ----
        runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:[?#/]|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:[?#/]|$)""")),
                script = """
                    (function(){
                        var c = document.querySelector('.captcha_l');
                        if (c) { c.click(); return; }
                        var b = document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button,.vjs-big-play-button');
                        if (b) b.click();
                    })();
                """.trimIndent(),
                useOkhttp = false,
                timeout = 30_000L
            )
            val resolved = app.get(url, referer = referer ?: mainUrl, interceptor = resolver).url
            Log.d("RaghavAnime", "[Playmogo] WebView resolved ${resolved.take(80)}")
            val headers = mapOf(
                "Referer" to url,
                "User-Agent" to baseHeaders["User-Agent"]!!
            )
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
                    Log.e("RaghavAnime", "[Playmogo] WebView resolved url is neither m3u8 nor mp4: ${resolved.take(80)}")
                }
            }
        }.onFailure { error ->
            Log.e("RaghavAnime", "[Playmogo] failed to resolve ${url.take(80)}: ${error.message}")
        }
    }
}
