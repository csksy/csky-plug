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

        // Only load the requested stream type — no fallback
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
                if (embedResp.code != 200) {
                    Log.i("Animo", "[$labelKey] Skipping: non-200")
                    continue
                }
                val embedHtml = embedResp.text
                if (embedHtml.contains("Just a moment") || embedHtml.contains("cloudflare") || embedHtml.length < 500) {
                    Log.i("Animo", "[$labelKey] Skipping: CF challenge detected (len=${embedHtml.length})")
                    continue
                }
                Log.i("Animo", "[$labelKey] Embed HTML OK (len=${embedHtml.length})")

                val tokenMatch = Regex("getSources\\?t=([A-Za-z0-9_.-]+)").find(embedHtml)
                if (tokenMatch == null) {
                    Log.i("Animo", "[$labelKey] No getSources token found in HTML")
                    continue
                }
                val token = tokenMatch.groupValues[1].replace("\\u0026", "&").split("&")[0]
                Log.i("Animo", "[$labelKey] Found token: ${token.take(30)}...")

                val srcResp = app.get("$cdnUrl/stream/getSources?t=$token", headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to embedUrl
                ), timeout = 15_000L)
                Log.i("Animo", "[$labelKey] getSources response code: ${srcResp.code}")
                if (srcResp.code != 200) continue
                val srcText = srcResp.text
                if (srcText.contains("Just a moment") || srcText.contains("invalid token")) {
                    Log.i("Animo", "[$labelKey] getSources blocked/invalid")
                    continue
                }

                val sources = parseJson<GetSourcesResponse>(srcText)
                Log.i("Animo", "[$labelKey] Sources count: ${sources.sources?.size ?: 0}, Tracks: ${sources.tracks?.size ?: 0}")

                sources.sources?.forEach { s ->
                    val file = s.file ?: return@forEach
                    val streamUrl = if (file.startsWith("http")) file else "$cdnUrl/${file.removePrefix("/")}"
                    Log.i("Animo", "[$labelKey] Source file: $streamUrl (type=${s.type})")

                    // Fetch the master playlist using cookies to extract variant URLs
                    try {
                        val masterHeaders = mapOf(
                            "User-Agent" to ua,
                            "Accept" to "*/*",
                            "Referer" to embedUrl,
                            "Origin" to cdnUrl
                        )
                        Log.i("Animo", "[$labelKey] Fetching master playlist: $streamUrl")
                        val masterResp = app.get(streamUrl, headers = masterHeaders, timeout = 15_000L)
                        Log.i("Animo", "[$labelKey] Master playlist code: ${masterResp.code}")
                        val masterText = masterResp.text
                        Log.i("Animo", "[$labelKey] Master body (first 300): ${masterText.take(300)}")

                        if (masterResp.code == 200 && masterText.trim().startsWith("#EXTM3U")) {
                            // Parse variant streams
                            val variantPattern = Regex("""#EXT-X-STREAM-INF:[^
]*?(?:NAME="(\d+p)"|RESOLUTION=(\d+)x(\d+))[^
]*
([^
#][^
]*)""")
                            val variants = variantPattern.findAll(masterText).toList()
                            Log.i("Animo", "[$labelKey] Parsed ${variants.size} variants from master playlist")

                            if (variants.isEmpty()) {
                                // It's a media playlist directly (no master), pass it through
                                Log.i("Animo", "[$labelKey] No variants — media playlist, passing through")
                                val label = "$name $labelKey ($type)"
                                callback.invoke(
                                    newExtractorLink(label, label, streamUrl, type = ExtractorLinkType.M3U8) {
                                        this.referer = embedUrl
                                        this.headers = masterHeaders
                                    }
                                )
                                found = true
                            } else {
                                variants.forEach { match ->
                                    val quality = if (match.groupValues[1].isNotEmpty()) {
                                        match.groupValues[1]
                                    } else {
                                        "${match.groupValues[2]}p"
                                    }
                                    val variantUrl = match.groupValues[4].trim().let {
                                        if (it.startsWith("http")) it else "$cdnUrl/${it.removePrefix("/")}"
                                    }
                                    val label = "$name $labelKey ($type) - $quality"
                                    Log.i("Animo", "[$labelKey] Adding variant: $quality -> ${variantUrl.take(80)}...")
                                    callback.invoke(
                                        newExtractorLink(label, label, variantUrl, type = ExtractorLinkType.M3U8) {
                                            this.referer = embedUrl
                                            this.headers = masterHeaders
                                        }
                                    )
                                }
                                found = true
                            }
                        } else {
                            Log.e("Animo", "[$labelKey] Master playlist invalid: code=${masterResp.code} startsWithM3U8=${masterText.trim().startsWith("#EXTM3U")}")
                        }
                    } catch (e: Exception) {
                        Log.e("Animo", "[$labelKey] Failed to fetch/parse master playlist: ${e.message}")
                    }
                }

                sources.tracks?.forEach { t ->
                    val file = t.file ?: return@forEach
                    val subUrl = if (file.startsWith("http")) file else "$cdnUrl/${file.removePrefix("/")}"
                    Log.i("Animo", "[$labelKey] Subtitle track: ${t.label} -> $subUrl")
                    subtitleCallback.invoke(newSubtitleFile(t.label ?: "English", subUrl) {
                        this.headers = mapOf("Referer" to embedUrl, "User-Agent" to ua)
                    })
                }
                if (found) {
                    Log.i("Animo", "[$labelKey] Found sources via direct API, breaking")
                    break
                }
            } catch (e: Exception) {
                Log.e("Animo", "[$labelKey] Direct API exception: ${e.message}")
            }
        }

        // Phase 2: WebView fallback (CF blocked direct API)
        if (!found) {
            Log.i("Animo", "----- Phase 2: WebView fallback -----")
            for ((labelKey, urlFn) in embedFormats) {
                val embedUrl = urlFn()
                Log.i("Animo", "[$labelKey] Starting WebView for: $embedUrl")
                try {
                    val result = extractStreamUrlViaWebView(embedUrl)
                    if (result == null) {
                        Log.e("Animo", "[$labelKey] WebView returned null")
                        continue
                    }
                    val (masterUrl, cookies) = result
                    Log.i("Animo", "[$labelKey] WebView captured master URL: ${masterUrl.take(80)}...")
                    Log.i("Animo", "[$labelKey] Cookies: $cookies")
                    Log.i("Animo", "[$labelKey] Has cf_clearance: ${cookies.contains("cf_clearance")}")

                    // Fetch master playlist with cookies
                    val masterHeaders = mutableMapOf(
                        "User-Agent" to ua,
                        "Accept" to "*/*",
                        "Referer" to embedUrl,
                        "Origin" to cdnUrl
                    )
                    if (cookies.isNotEmpty()) {
                        masterHeaders["Cookie"] = cookies
                    }

                    Log.i("Animo", "[$labelKey] Fetching master playlist with cookies...")
                    val masterResp = app.get(masterUrl, headers = masterHeaders, timeout = 15_000L)
                    Log.i("Animo", "[$labelKey] Master playlist code: ${masterResp.code}")
                    val masterText = masterResp.text
                    Log.i("Animo", "[$labelKey] Master body (first 300): ${masterText.take(300)}")

                    if (masterResp.code == 200 && masterText.trim().startsWith("#EXTM3U")) {
                        val variantPattern = Regex("""#EXT-X-STREAM-INF:[^\n]*?(?:NAME="(\d+p)"|RESOLUTION=(\d+)x(\d+))[^\n]*\n([^\n#][^\n]*)""")
                        val variants = variantPattern.findAll(masterText).toList()
                        Log.i("Animo", "[$labelKey] Parsed ${variants.size} variants")

                        if (variants.isEmpty()) {
                            val label = "$name $labelKey ($type)"
                            Log.i("Animo", "[$labelKey] No variants — passing media playlist directly")
                            callback.invoke(
                                newExtractorLink(label, label, masterUrl, type = ExtractorLinkType.M3U8) {
                                    this.referer = embedUrl
                                    this.headers = masterHeaders
                                }
                            )
                            found = true
                        } else {
                            variants.forEach { match ->
                                val quality = if (match.groupValues[1].isNotEmpty()) {
                                    match.groupValues[1]
                                } else {
                                    "${match.groupValues[2]}p"
                                }
                                val variantUrl = match.groupValues[4].trim().let {
                                    if (it.startsWith("http")) it else "$cdnUrl/${it.removePrefix("/")}"
                                }
                                val label = "$name $labelKey ($type) - $quality"
                                Log.i("Animo", "[$labelKey] Adding variant: $quality")
                                callback.invoke(
                                    newExtractorLink(label, label, variantUrl, type = ExtractorLinkType.M3U8) {
                                        this.referer = embedUrl
                                        this.headers = masterHeaders
                                    }
                                )
                            }
                            found = true
                            Log.i("Animo", "[$labelKey] Successfully added ${variants.size} variants, breaking")
                            break
                        }
                    } else {
                        Log.e("Animo", "[$labelKey] Master playlist fetch failed: code=${masterResp.code} startsM3U8=${masterText.trim().startsWith("#EXTM3U")}")
                    }
                } catch (e: Exception) {
                    Log.e("Animo", "[$labelKey] WebView exception: ${e.message}")
                }
            }
        }

        Log.i("Animo", "========== loadLinks END (found=$found) ==========")
        return found
    }

    /**
     * Loads the embed page in a WebView and intercepts the /p?t= stream URL via
     * shouldInterceptRequest. Returns a DUMMY response so the WebView does NOT
     * consume the one-time token. Extracts cookies from multiple sources.
     *
     * Returns (streamUrl, cookies) or null.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractStreamUrlViaWebView(embedUrl: String): Pair<String, String>? = withContext(Dispatchers.Main) {
        val context = com.lagradost.cloudstream3.CommonActivity.activity ?: run {
            Log.e("Animo", "WebView: no activity context")
            return@withContext null
        }

        Log.i("Animo", "WebView: loading $embedUrl")

        val result = withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine<Pair<String, String>?> { cont ->
                val foundUrl = java.util.concurrent.atomic.AtomicReference<String?>(null)
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

                            // Intercept the /p?t= stream URL BEFORE the WebView loads it
                            if (url.contains("cdn.4animo.xyz/p?t=") && foundUrl.get() == null) {
                                foundUrl.set(url)
                                Log.i("Animo", "WebView INTERCEPTED: $url")

                                // Method 1: Get cookies from the request headers (most reliable)
                                val requestCookies = try {
                                    val headers = request.requestHeaders
                                    headers?.get("Cookie") ?: headers?.get("cookie") ?: ""
                                } catch (e: Exception) {
                                    Log.e("Animo", "WebView: failed to get request headers: ${e.message}")
                                    ""
                                }
                                Log.i("Animo", "WebView request cookies: $requestCookies")

                                // Method 2: Get cookies from CookieManager for cdn domain
                                val cmCdnCookies = try {
                                    CookieManager.getInstance().getCookie(cdnUrl) ?: ""
                                } catch (e: Exception) { "" }
                                Log.i("Animo", "WebView CookieManager (cdn): $cmCdnCookies")

                                // Method 3: Get cookies for main domain
                                val cmMainCookies = try {
                                    CookieManager.getInstance().getCookie(mainUrl) ?: ""
                                } catch (e: Exception) { "" }
                                Log.i("Animo", "WebView CookieManager (main): $cmMainCookies")

                                // Method 4: Get cookies for parent domain
                                val cmParentCookies = try {
                                    CookieManager.getInstance().getCookie("https://4animo.xyz") ?: ""
                                } catch (e: Exception) { "" }
                                Log.i("Animo", "WebView CookieManager (parent): $cmParentCookies")

                                // Combine all cookies, deduplicate by name
                                val allCookieStrs = listOf(requestCookies, cmCdnCookies, cmMainCookies, cmParentCookies)
                                    .filter { it.isNotEmpty() }
                                val combined = mutableMapOf<String, String>()
                                allCookieStrs.forEach { cookieStr ->
                                    cookieStr.split(";").forEach { pair ->
                                        val parts = pair.trim().split("=", limit = 2)
                                        if (parts.size == 2) {
                                            combined[parts[0].trim()] = parts[1].trim()
                                        }
                                    }
                                }
                                val finalCookies = combined.entries.joinToString("; ") { "${it.key}=${it.value}" }
                                Log.i("Animo", "WebView FINAL cookies: $finalCookies")
                                Log.i("Animo", "WebView has cf_clearance: ${finalCookies.contains("cf_clearance")}")

                                if (cont.isActive) cont.resume(Pair(url, finalCookies))

                                // Return dummy m3u8 so token is NOT consumed by WebView
                                return WebResourceResponse(
                                    "application/vnd.apple.mpegurl",
                                    "UTF-8",
                                    ByteArrayInputStream("#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray())
                                )
                            }
                            return null
                        }

                        override fun onLoadResource(view: WebView?, resourceUrl: String?) {
                            super.onLoadResource(view, resourceUrl)
                            if (resourceUrl != null && foundUrl.get() == null) {
                                if (resourceUrl.contains("/p?t=") && resourceUrl.contains("cdn.4animo.xyz")) {
                                    foundUrl.set(resourceUrl)
                                    Log.i("Animo", "WebView onLoadResource captured: $resourceUrl")
                                    val cmCdnCookies = try { CookieManager.getInstance().getCookie(cdnUrl) ?: "" } catch (_: Exception) { "" }
                                    val cmMainCookies = try { CookieManager.getInstance().getCookie(mainUrl) ?: "" } catch (_: Exception) { "" }
                                    val cmParentCookies = try { CookieManager.getInstance().getCookie("https://4animo.xyz") ?: "" } catch (_: Exception) { "" }
                                    val combined = mutableMapOf<String, String>()
                                    listOf(cmCdnCookies, cmMainCookies, cmParentCookies).filter { it.isNotEmpty() }.forEach { cookieStr ->
                                        cookieStr.split(";").forEach { pair ->
                                            val parts = pair.trim().split("=", limit = 2)
                                            if (parts.size == 2) combined[parts[0].trim()] = parts[1].trim()
                                        }
                                    }
                                    val finalCookies = combined.entries.joinToString("; ") { "${it.key}=${it.value}" }
                                    if (cont.isActive) cont.resume(Pair(resourceUrl, finalCookies))
                                }
                            }
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
            Log.e("Animo", "WebView: timed out after 30s")
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
