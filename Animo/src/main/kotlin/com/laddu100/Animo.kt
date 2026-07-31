package com.laddu100

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference
import java.net.URLEncoder
import kotlin.coroutines.resume

class Animo : MainAPI() {
    override var mainUrl = "https://4animo.xyz"
    override var name = "Animo"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "https://api.kryzox.xyz"
    private val cdnUrl = "https://cdn.4animo.xyz"

    private val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val apiHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        Pair("trending", "Trending"),
        Pair("recently-updated", "Recently Updated"),
        Pair("recently-added", "Recently Added"),
        Pair("top", "Top Rated"),
        Pair("movie", "Movies"),
        Pair("tv", "TV Series"),
        Pair("ova", "OVA"),
        Pair("ona", "ONA"),
        Pair("special", "Specials"),
        Pair("completed", "Completed")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("animo") ?: mainUrl
        val url = "$apiUrl/anime/${request.data}?page=$page&limit=20"
        return try {
            val items = parseAnimeList(app.get(url, headers = apiHeaders).text)
            val home = items.mapNotNull { it.toSearchResponse() }
            newHomePageResponse(request.name, home, hasNext = home.size == 20)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun parseAnimeList(text: String): List<AnimeSearchItem> {
        return try {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) {
                parseJson<List<AnimeSearchItem>>(text)
            } else {
                parseJson<SearchResponseData>(text).data ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("animo") ?: mainUrl
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$apiUrl/anime/search?keyword=$encoded&page=1&limit=20"
            val resp = parseJson<SearchResponseData>(app.get(url, headers = apiHeaders).text)
            resp.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("animo") ?: mainUrl
        val animeId = url.substringAfterLast("/").toIntOrNull() ?: return null

        val anime = try {
            parseJson<AnimeDetails>(app.get("$apiUrl/anime/$animeId", headers = apiHeaders).text)
        } catch (e: Exception) {
            return null
        }
        val title = anime.titles?.english ?: anime.titles?.romaji ?: return null

        val episodes = try {
            parseJson<EpisodesResponse>(app.get("$apiUrl/anime/$animeId/episodes", headers = apiHeaders).text).data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()

        episodes.forEach { ep ->
            val num = ep.number ?: return@forEach
            val epId = ep.id ?: return@forEach
            val epName = ep.titles?.en ?: ep.titles?.romaji ?: "Episode $num"
            if (ep.sub == true) {
                subEps.add(newEpisode(EpisodeData(animeId, epId, ep.embed_id, num, anime.slug ?: "", "sub").toJson()) {
                    this.episode = num
                    this.name = epName
                    this.posterUrl = ep.thumbnail
                })
            }
            if (ep.dub == true) {
                dubEps.add(newEpisode(EpisodeData(animeId, epId, ep.embed_id, num, anime.slug ?: "", "dub").toJson()) {
                    this.episode = num
                    this.name = epName
                    this.posterUrl = ep.thumbnail
                })
            }
        }

        val tvType = when (anime.type?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        val year = anime.air?.start?.substringBefore("-")?.toIntOrNull()
        val finalType = if (tvType == TvType.AnimeMovie && dubEps.isNotEmpty()) TvType.Anime else tvType

        return newAnimeLoadResponse(title, url, finalType) {
            this.posterUrl = anime.images?.poster
            this.plot = anime.synopsis
            this.year = year
            this.tags = anime.genres
            if (anime.score != null) this.score = Score.from10(anime.score.toString())
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            parseJson<EpisodeData>(data)
        } catch (e: Exception) {
            Log.e("Animo", "loadLinks: failed to parse data: ${e.message}")
            return false
        }

        Log.i("Animo", "========== loadLinks START ==========")
        Log.i("Animo", "episodeId=${epData.episodeId} embedId=${epData.embedId} animeId=${epData.animeId} epNum=${epData.episodeNum} streamType=${epData.streamType}")

        val type = epData.streamType
        Log.i("Animo", "Requested stream type: $type")

        val embedFormats = listOf<Pair<String, () -> String>>(
            Pair("a-1") { "$cdnUrl/embed/a-1/${epData.episodeId}/$type" },
            Pair("s-1") { "$cdnUrl/embed/s-1/${epData.embedId ?: epData.episodeId}/$type" },
            Pair("hd-1") { "$cdnUrl/embed/hd-1/ani/${epData.animeId}/${epData.episodeNum}/$type" },
            Pair("hd-2") { "$cdnUrl/embed/hd-2/ani/${epData.animeId}/${epData.episodeNum}/$type" }
        )

        var found = false

        // Phase 1: Try direct API (fast path)
        Log.i("Animo", "----- Phase 1: Direct API -----")
        for ((labelKey, urlFn) in embedFormats) {
            val embedUrl = urlFn()
            Log.i("Animo", "[$labelKey] Trying direct: $embedUrl")
            try {
                val embedResp = app.get(embedUrl, headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "text/html,application/xhtml+xml",
                    "Referer" to "$mainUrl/"
                ), timeout = 15_000L)
                Log.i("Animo", "[$labelKey] Embed response code: ${embedResp.code}")
                if (embedResp.code != 200) continue
                val embedHtml = embedResp.text
                if (embedHtml.contains("Just a moment") || embedHtml.contains("cloudflare") || embedHtml.length < 500) {
                    Log.i("Animo", "[$labelKey] CF challenge (len=${embedHtml.length})")
                    continue
                }

                val tokenMatch = Regex("getSources\\?t=([A-Za-z0-9_.-]+)").find(embedHtml)
                if (tokenMatch == null) continue
                val token = tokenMatch.groupValues[1].replace("\\u0026", "&").split("&")[0]
                Log.i("Animo", "[$labelKey] Token: ${token.take(30)}...")

                val srcResp = app.get("$cdnUrl/stream/getSources?t=$token", headers = mapOf(
                    "User-Agent" to ua, "Accept" to "application/json",
                    "Referer" to embedUrl
                ), timeout = 15_000L)
                if (srcResp.code != 200) continue
                val srcText = srcResp.text
                if (srcText.contains("Just a moment") || srcText.contains("invalid token")) continue

                val sources = parseJson<GetSourcesResponse>(srcText)
                Log.i("Animo", "[$labelKey] Sources: ${sources.sources?.size ?: 0}, Tracks: ${sources.tracks?.size ?: 0}")

                sources.sources?.forEach { s ->
                    val file = s.file ?: return@forEach
                    val streamUrl = if (file.startsWith("http")) file else "$cdnUrl/${file.removePrefix("/")}"
                    val masterHeaders = mapOf("User-Agent" to ua, "Accept" to "*/*", "Referer" to embedUrl)
                    try {
                        val masterResp = app.get(streamUrl, headers = masterHeaders, timeout = 15_000L)
                        val masterText = masterResp.text
                        if (masterResp.code == 200 && masterText.trim().startsWith("#EXTM3U")) {
                            val variantPattern = Regex("""#EXT-X-STREAM-INF:[^\n]*?(?:NAME="(\d+p)"|RESOLUTION=(\d+)x(\d+))[^\n]*\n([^\n#][^\n]*)""")
                            val variants = variantPattern.findAll(masterText).toList()
                            if (variants.isEmpty()) {
                                val label = "$name $labelKey ($type)"
                                callback.invoke(newExtractorLink(label, label, streamUrl, type = ExtractorLinkType.M3U8) {
                                    this.referer = embedUrl; this.headers = masterHeaders
                                })
                                found = true
                            } else {
                                variants.forEach { match ->
                                    val quality = if (match.groupValues[1].isNotEmpty()) match.groupValues[1] else "${match.groupValues[2]}p"
                                    val variantUrl = match.groupValues[4].trim().let { if (it.startsWith("http")) it else "$cdnUrl/${it.removePrefix("/")}" }
                                    val label = "$name $labelKey ($type) - $quality"
                                    callback.invoke(newExtractorLink(label, label, variantUrl, type = ExtractorLinkType.M3U8) {
                                        this.referer = embedUrl; this.headers = masterHeaders
                                    })
                                }
                                found = true
                            }
                        }
                    } catch (e: Exception) { Log.e("Animo", "[$labelKey] master fetch: ${e.message}") }
                }

                sources.tracks?.forEach { t ->
                    val file = t.file ?: return@forEach
                    val subUrl = if (file.startsWith("http")) file else "$cdnUrl/${file.removePrefix("/")}"
                    subtitleCallback.invoke(newSubtitleFile(t.label ?: "English", subUrl) {
                        this.headers = mapOf("Referer" to embedUrl, "User-Agent" to ua)
                    })
                }
                if (found) break
            } catch (e: Exception) {
                Log.e("Animo", "[$labelKey] Direct: ${e.message}")
            }
        }

        // Phase 2: WebView fallback — intercept ALL /p?t= URLs, check each
        if (!found) {
            Log.i("Animo", "----- Phase 2: WebView fallback -----")
            for ((labelKey, urlFn) in embedFormats) {
                val embedUrl = urlFn()
                Log.i("Animo", "[$labelKey] Starting WebView for: $embedUrl")
                try {
                    val result = extractStreamViaWebView(embedUrl, subtitleCallback)
                    if (result == null) {
                        Log.e("Animo", "[$labelKey] WebView returned null")
                        continue
                    }

                    val (masterUrl, variants, subtitleUrls) = result
                    Log.i("Animo", "[$labelKey] Master URL: ${masterUrl.take(80)}...")
                    Log.i("Animo", "[$labelKey] Variants found: ${variants.size}")
                    Log.i("Animo", "[$labelKey] Subtitles found: ${subtitleUrls.size}")

                    val playHeaders = mapOf(
                        "User-Agent" to ua,
                        "Accept" to "*/*",
                        "Referer" to embedUrl,
                        "Origin" to cdnUrl
                    )

                    if (variants.isNotEmpty()) {
                        // Master playlist was consumed — variant URLs have fresh tokens
                        variants.forEach { (quality, variantUrl) ->
                            val label = "$name $labelKey ($type) - $quality"
                            Log.i("Animo", "[$labelKey] Adding variant: $quality -> ${variantUrl.take(80)}...")
                            callback.invoke(
                                newExtractorLink(label, label, variantUrl, type = ExtractorLinkType.M3U8) {
                                    this.referer = embedUrl
                                    this.headers = playHeaders
                                }
                            )
                        }
                        found = true
                    }

                    // Add any subtitle tracks found
                    subtitleUrls.forEach { (label, subUrl) ->
                        Log.i("Animo", "[$labelKey] Adding subtitle: $label")
                        subtitleCallback.invoke(newSubtitleFile(label, subUrl) {
                            this.headers = playHeaders
                        })
                    }

                    if (found) {
                        Log.i("Animo", "[$labelKey] Success! Breaking.")
                        break
                    }
                } catch (e: Exception) {
                    Log.e("Animo", "[$labelKey] WebView: ${e.message}")
                }
            }
        }

        Log.i("Animo", "========== loadLinks END (found=$found) ==========")
        return found
    }

    data class VariantStream(val quality: String, val url: String)
    data class SubtitleTrack(val label: String, val url: String)
    data class WebViewResult(val masterUrl: String, val variants: List<VariantStream>, val subtitles: List<SubtitleTrack>)

    /**
     * Loads the embed page in a WebView. Intercepts ALL /p?t= URLs via
     * shouldInterceptRequest. For each, fetches it to check if it's m3u8 or
     * subtitle. The m3u8 (master) is parsed for variant URLs (which have
     * fresh, unconsumed tokens). Subtitle URLs are collected separately.
     *
     * Returns WebViewResult(masterUrl, variants, subtitles) or null.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractStreamViaWebView(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): WebViewResult? = withContext(Dispatchers.Main) {
        val context = com.lagradost.cloudstream3.CommonActivity.activity ?: run {
            Log.e("Animo", "WebView: no activity context")
            return@withContext null
        }

        Log.i("Animo", "WebView: loading $embedUrl")

        val result = withTimeoutOrNull(40_000L) {
            suspendCancellableCoroutine<WebViewResult?> { cont ->
                val foundMaster = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val variants = java.util.concurrent.atomic.AtomicReference<List<VariantStream>>(emptyList())
                val subtitles = java.util.concurrent.atomic.AtomicReference<List<SubtitleTrack>>(emptyList())
                val webView = WebView(context)
                try {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = ua
                        blockNetworkImage = true
                    }
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null

                            // Intercept ALL /p?t= URLs
                            if (url.contains("cdn.4animo.xyz/p?t=") && foundMaster.get() == null) {
                                Log.i("Animo", "WebView INTERCEPTED /p?t=: ${url.take(100)}...")

                                // Fetch this URL ourselves to check content type
                                val fetchHeaders = mapOf(
                                    "User-Agent" to ua,
                                    "Accept" to "*/*",
                                    "Referer" to embedUrl,
                                    "Origin" to cdnUrl
                                )
                                try {
                                    val resp = kotlinx.coroutines.runBlocking {
                                        app.get(url, headers = fetchHeaders, timeout = 15_000L)
                                    }
                                    val body = resp.text
                                    val bodyStart = body.take(200)
                                    Log.i("Animo", "WebView /p?t= code=${resp.code} bodyStart=${bodyStart.take(100)}")

                                    if (resp.code == 200 && body.trim().startsWith("#EXTM3U")) {
                                        // This is the m3u8!
                                        Log.i("Animo", "WebView: Found M3U8 playlist!")
                                        foundMaster.set(url)

                                        // Check if master playlist (has variants)
                                        val variantPattern = Regex("""#EXT-X-STREAM-INF:[^\n]*?(?:NAME="(\d+p)"|RESOLUTION=(\d+)x(\d+))[^\n]*\n([^\n#][^\n]*)""")
                                        val foundVariants = variantPattern.findAll(body).map { match ->
                                            val quality = if (match.groupValues[1].isNotEmpty()) {
                                                match.groupValues[1]
                                            } else {
                                                "${match.groupValues[2]}p"
                                            }
                                            val variantUrl = match.groupValues[4].trim().let {
                                                if (it.startsWith("http")) it else "$cdnUrl/${it.removePrefix("/")}"
                                            }
                                            Log.i("Animo", "WebView: Variant $quality -> ${variantUrl.take(80)}...")
                                            VariantStream(quality, variantUrl)
                                        }.toList()

                                        if (foundVariants.isEmpty()) {
                                            // Media playlist directly (no master)
                                            Log.i("Animo", "WebView: Media playlist (no variants), using directly")
                                            variants.set(listOf(VariantStream("default", url)))
                                        } else {
                                            variants.set(foundVariants)
                                        }

                                        // Resume the coroutine with the result
                                        val currentSubs = subtitles.get()
                                        if (cont.isActive) cont.resume(WebViewResult(url, variants.get(), currentSubs))

                                        // Return dummy so WebView doesn't consume the token
                                        return WebResourceResponse(
                                            "application/vnd.apple.mpegurl", "UTF-8",
                                            ByteArrayInputStream("#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray())
                                        )
                                    } else if (resp.code == 200 && (body.contains("WEBVTT") || body.contains("-->"))) {
                                        // This is a subtitle track
                                        Log.i("Animo", "WebView: Found subtitle track")
                                        val subLabel = "Sub ${subtitles.get().size + 1}"
                                        val currentSubs = subtitles.get()
                                        subtitles.set(currentSubs + SubtitleTrack(subLabel, url))
                                        // Return dummy subtitle so WebView doesn't consume
                                        return WebResourceResponse(
                                            "text/vtt", "UTF-8",
                                            ByteArrayInputStream("WEBVTT\n\n".toByteArray())
                                        )
                                    } else {
                                        Log.i("Animo", "WebView: Unknown content (code=${resp.code}), skipping")
                                        // Return dummy to avoid consuming
                                        return WebResourceResponse(
                                            "application/octet-stream", "UTF-8",
                                            ByteArrayInputStream(ByteArray(0))
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e("Animo", "WebView: fetch failed: ${e.message}")
                                    return null
                                }
                            }
                            return null
                        }

                        override fun onLoadResource(view: WebView?, resourceUrl: String?) {
                            super.onLoadResource(view, resourceUrl)
                            if (resourceUrl != null && foundMaster.get() == null) {
                                if (resourceUrl.contains("/p?t=") && resourceUrl.contains("cdn.4animo.xyz")) {
                                    Log.i("Animo", "WebView onLoadResource: ${resourceUrl.take(100)}...")
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.i("Animo", "WebView onPageFinished: $url")
                        }
                    }
                    webView.loadUrl(embedUrl)
                } catch (e: Exception) {
                    Log.e("Animo", "WebView exception: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    try { webView.destroy() } catch (_: Exception) {}
                }
            }
        }

        if (result == null) {
            Log.e("Animo", "WebView: timed out after 40s")
        }
        result
    }

    private fun AnimeSearchItem.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val title = titles?.english ?: titles?.romaji ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
            this.posterUrl = images?.poster
            addDubStatus(dubExist = (dub_count ?: 0) > 0, subExist = (sub_count ?: 0) > 0)
        }
    }

    data class EpisodeData(
        val animeId: Int,
        val episodeId: Int,
        val embedId: String?,
        val episodeNum: Int,
        val slug: String,
        val streamType: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeSearchItem(
        val id: Int? = null,
        val slug: String? = null,
        val titles: Titles? = null,
        val images: Images? = null,
        val type: String? = null,
        val status: String? = null,
        val episodes_count: Int? = null,
        val sub_count: Int? = null,
        val dub_count: Int? = null,
        val score: Double? = null,
        val season_year: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResponseData(
        val success: Boolean? = null,
        val data: List<AnimeSearchItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Images(
        val poster: String? = null,
        val banner: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Titles(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDetails(
        val id: Int? = null,
        val slug: String? = null,
        val titles: Titles? = null,
        val synopsis: String? = null,
        val images: Images? = null,
        val type: String? = null,
        val status: String? = null,
        val score: Double? = null,
        val rating: String? = null,
        val air: Air? = null,
        val genres: List<String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Air(
        val start: String? = null,
        val end: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResponse(
        val anime_id: Int? = null,
        val total: Int? = null,
        val sub_count: String? = null,
        val dub_count: String? = null,
        val data: List<EpisodeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        val id: Int? = null,
        val number: Int? = null,
        val titles: EpisodeTitles? = null,
        val filler: Boolean? = null,
        val rating: String? = null,
        val thumbnail: String? = null,
        val sub: Boolean? = null,
        val dub: Boolean? = null,
        @JsonProperty("embed_id") val embed_id: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeTitles(
        val en: String? = null,
        val ja: String? = null,
        val romaji: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GetSourcesResponse(
        val sources: List<MegaSource>? = null,
        val tracks: List<MegaTrack>? = null,
        val encrypted: Boolean? = null,
        val server: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaSource(
        val file: String? = null,
        val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaTrack(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null,
        val default: Boolean? = null
    )
}
