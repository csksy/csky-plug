package com.laddu100

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

class MiruroVidWish : MiruroMegaPlay("VidWish") {
    override val mainUrl = "https://vidwish.live"
}

// megaplay and vidwish power miruro's embed providers; both expose the stream
// through the same /stream/getSources json endpoint, only the host differs
open class MiruroMegaPlay(private val sourceName: String = "MegaPlay") : ExtractorApi() {

    override val name = sourceName
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
        "Accept" to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching { extractViaApi(url, subtitleCallback, callback) }
            .onFailure { error ->
                Log.d(name, "api extraction failed, falling back to webview: ${error.message}")
                extractViaWebView(url, callback)
            }
    }

    private suspend fun extractViaApi(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, headers = headers).document
        val html = document.html()
        val id = document.selectFirst("#megaplay-player")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: Regex("""data-id=["'](\d+)""").find(html)?.groupValues?.get(1)
            ?: document.selectFirst("#megaplay-player")?.attr("data-realid")?.takeIf { it.isNotBlank() }
            ?: Regex("""data-realid=["'](\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""/stream/s-\d+/(\d+)""").find(url)?.groupValues?.get(1)
            ?: throw Exception("no embed id in page")

        val raw = app.get("$mainUrl/stream/getSources?id=$id", headers = headers).text
        val m3u8 = findM3u8(raw) ?: throw Exception("no m3u8 in getSources response")

        generateM3u8(name, m3u8, mainUrl, headers = headers).forEach(callback)

        Regex(""""file"\s*:\s*"([^"]+\.vtt[^"]*)"""").findAll(raw).forEach { match ->
            subtitleCallback(newSubtitleFile("English", match.groupValues[1]) {
                this.headers = mapOf("Referer" to "$mainUrl/")
            })
        }
    }

    private fun findM3u8(raw: String): String? {
        try {
            val parsed = parseJson<GetSourcesResponse>(raw)
            parsed.sources?.file?.takeIf { it.startsWith("http") }?.let { return it }
        } catch (e: Exception) {
            // response is not always clean json, fall through to regex scraping
        }

        // links.hlsN keys appear alongside an object map, later entries win
        Regex(""""(links\.hls\d+)"""").findAll(raw).toList().reversed().forEach { match ->
            val parts = match.groupValues[1].split(".")
            if (parts.size == 2) {
                val pattern = """"${parts[0]}"\s*:\s*\{[^}]*"${parts[1]}"\s*:\s*"([^"]+)""""
                Regex(pattern).find(raw)?.let { return it.groupValues[1] }
            }
        }

        Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(raw)?.let { return it.groupValues[1] }
        Regex("""(https?://[^\s"\\<>]+\.m3u8[^\s"\\<>]*)""").find(raw)?.let { return it.groupValues[1] }
        return null
    }

    private suspend fun extractViaWebView(url: String, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                script = """document.querySelector('.jw-icon-display')?.click();""",
                useOkhttp = false,
                timeout = 15_000L
            )
            val resolved = app.get(url, referer = mainUrl, interceptor = resolver).url
            if (resolved.contains(".m3u8")) {
                generateM3u8(name, resolved, mainUrl, headers = headers).forEach(callback)
            }
        }.onFailure { error ->
            Log.d(name, "webview extraction failed: ${error.message}")
        }
    }

    private data class GetSourcesResponse(
        @JsonProperty("sources") val sources: Sources? = null
    )

    private data class Sources(@JsonProperty("file") val file: String? = null)
}

// last resort for embeds the repo has no dedicated extractor for: let a real
// browser run the page and grab the first media url it requests
class MiruroWebViewExtractor(
    private val sourceName: String,
    private val baseUrl: String
) : ExtractorApi() {

    override val name = sourceName
    override val mainUrl = baseUrl
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 20_000L
            )
            val resolved = app.get(url, referer = referer ?: mainUrl, interceptor = resolver).url
            val headers = mapOf("Referer" to url)
            when {
                resolved.contains(".m3u8", ignoreCase = true) ->
                    generateM3u8(name, resolved, mainUrl, headers = headers).forEach(callback)

                resolved.contains(".mp4", ignoreCase = true) && !resolved.contains(".txt") ->
                    callback(
                        newExtractorLink(source = name, name = name, url = resolved, type = INFER_TYPE) {
                            quality = getQualityFromName(resolved)
                            this.headers = headers
                        }
                    )
            }
        }.onFailure { error ->
            Log.d(name, "webview extraction failed: ${error.message}")
        }
    }

    companion object {
        fun forUrl(url: String): MiruroWebViewExtractor {
            val host = try {
                java.net.URL(url).host
            } catch (e: Exception) {
                ""
            }
            val base = if (host.isNotEmpty()) "https://$host" else url
            return MiruroWebViewExtractor(host.ifEmpty { "Embed" }, base)
        }
    }
}
