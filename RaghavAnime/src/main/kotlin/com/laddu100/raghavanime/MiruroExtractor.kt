package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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
        Log.d("RaghavAnime", "[Miruro] $name extractor: resolving ${url.take(80)}")
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/"
        )

        runCatching {
            val document = app.get(url, headers = headers).document
            val id = document.selectFirst("#megaplay-player")?.attr("data-id")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-id=["'](\d+)""").find(document.html())?.groupValues?.get(1)
                ?: document.selectFirst("#megaplay-player")?.attr("data-realid")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-realid=["'](\d+)""").find(document.html())?.groupValues?.get(1)
                ?: Regex("""/stream/s-\d+/(\d+)""").find(url)?.groupValues?.get(1)
            if (id == null) {
                Log.e("RaghavAnime", "[Miruro] $name extractor: could not extract player id from ${url.take(80)}")
                return@runCatching
            }
            Log.d("RaghavAnime", "[Miruro] $name extractor: extracted player id=$id")
            val rawText = app.get("$mainUrl/stream/getSources?id=$id", headers = headers).text
            Log.d("RaghavAnime", "[Miruro] $name extractor: getSources raw text fetched (len=${rawText.length})")
            val m3u8 = extractM3u8FromResponse(rawText)
            if (m3u8 == null) {
                Log.e("RaghavAnime", "[Miruro] $name extractor: raw-text m3u8 extraction failed for id=$id (len=${rawText.length})")
                return@runCatching
            }
            Log.d("RaghavAnime", "[Miruro] $name extractor: m3u8 found: ${m3u8.take(80)}")

            val vttMatches = Regex(""""file"\s*:\s*"([^"]+\.vtt[^"]*)"""").findAll(rawText).toList()
            Log.d("RaghavAnime", "[Miruro] $name extractor: generating m3u8 links, ${vttMatches.size} vtt tracks")
            generateM3u8(name, m3u8, mainUrl, headers = headers).forEach(callback)
            vttMatches.forEach { m ->
                Log.d("RaghavAnime", "[Miruro] $name extractor: subtitle 'English'")
                subtitleCallback(newSubtitleFile("English", m.groupValues[1]) {
                    this.headers = mapOf("Referer" to "$mainUrl/")
                })
            }
        }.onFailure { error ->
            Log.e("RaghavAnime", "[Miruro] $name extractor: direct resolve failed (${error.message}), trying WebViewResolver")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                script = """document.querySelector('.jw-icon-display')?.click();""",
                useOkhttp = false,
                timeout = 15_000L
            )
            val m3u8 = app.get(url, referer = mainUrl, interceptor = resolver).url
            Log.d("RaghavAnime", "[Miruro] $name extractor: WebViewResolver resolved ${m3u8.take(80)}")
            if (m3u8.contains(".m3u8")) {
                generateM3u8(name, m3u8, mainUrl, headers = headers).forEach(callback)
            } else {
                Log.e("RaghavAnime", "[Miruro] $name extractor: WebViewResolver found no m3u8 for ${url.take(80)}")
            }
        }
    }

    private fun extractM3u8FromResponse(raw: String): String? {
        val parsed = try {
            com.lagradost.cloudstream3.utils.AppUtils.parseJson<Response>(raw)
        } catch (_: Exception) { null }

        val directFile = parsed?.sources?.file
        if (directFile != null && directFile.startsWith("http")) {
            return directFile
        }

        Regex(""""(links\.hls\d+)"""").findAll(raw).toList().reversed().forEach { m ->
            val key = m.groupValues[1]
            val parts = key.split(".")
            if (parts.size == 2) {
                val obj = parts[0]
                val prop = parts[1]
                val pattern = """"$obj"\s*:\s*\{[^}]*"$prop"\s*:\s*"([^"]+)""""
                Regex(pattern).find(raw)?.let { return it.groupValues[1] }
            }
        }

        Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(raw)?.let { return it.groupValues[1] }

        Regex("""(https?://[^\s"\\<>]+\.m3u8[^\s"\\<>]*)""").find(raw)?.let { return it.groupValues[1] }

        return null
    }

    data class Response(
        @JsonProperty("sources") val sources: Sources? = null,
        @JsonProperty("tracks") val tracks: List<Track> = emptyList()
    )

    data class Sources(@JsonProperty("file") val file: String? = null)

    data class Track(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
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
        Log.d("RaghavAnime", "[Miruro] WebView extractor ($name): resolving ${url.take(80)}")
        runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 20_000L
            )
            val resolved = app.get(url, referer = referer ?: mainUrl, interceptor = resolver).url
            Log.d("RaghavAnime", "[Miruro] WebView extractor ($name): resolved ${resolved.take(80)}")
            val headers = mapOf("Referer" to url)
            when {
                resolved.contains(".m3u8", ignoreCase = true) -> {
                    Log.d("RaghavAnime", "[Miruro] WebView extractor ($name): m3u8 resolved, generating links")
                    generateM3u8(name, resolved, mainUrl, headers = headers).forEach(callback)
                }
                resolved.contains(".mp4", ignoreCase = true) -> {
                    Log.d("RaghavAnime", "[Miruro] WebView extractor ($name): mp4 resolved")
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
                    Log.e("RaghavAnime", "[Miruro] WebView extractor ($name): resolved url is neither m3u8 nor mp4: ${resolved.take(80)}")
                }
            }
        }.onFailure { error ->
            Log.e("RaghavAnime", "[Miruro] WebView extractor ($name): failed to resolve ${url.take(80)}: ${error.message}")
        }
    }
}
