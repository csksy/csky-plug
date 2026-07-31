package com.laddu100

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.google.gson.JsonObject
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

        for ((labelKey, urlFn) in embedFormats) {
            val embedUrl = urlFn()
            Log.i("Animo", "[$labelKey] Trying WebView approach for: $embedUrl")
            try {
                val result = extractViaWebView(embedUrl) ?: continue
                val (masterUrl, masterContent, subtitles) = result

                Log.i("Animo", "[$labelKey] Master URL: ${masterUrl.take(80)}...")
                Log.i("Animo", "[$labelKey] Master content starts with #EXTM3U: ${masterContent.trim().startsWith("#EXTM3U")}")
                Log.i("Animo", "[$labelKey] Subtitles found: ${subtitles.size}")

                val playHeaders = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "*/*",
                    "Referer" to embedUrl,
                    "Origin" to cdnUrl
                )

                if (masterContent.trim().startsWith("#EXTM3U")) {
                    // Parse variant streams from master playlist
                    val variantPattern = Regex("""#EXT-X-STREAM-INF:[^\n]*?(?:NAME="(\d+p)"|RESOLUTION=(\d+)x(\d+))[^\n]*\n([^\n#][^\n]*)""")
                    val variants = variantPattern.findAll(masterContent).toList()
                    Log.i("Animo", "[$labelKey] Parsed ${variants.size} variants from master playlist")

                    if (variants.isEmpty()) {
                        // Media playlist directly (no master)
                        val label = "$name $labelKey ($type)"
                        Log.i("Animo", "[$labelKey] No variants — media playlist, adding directly")
                        callback.invoke(
                            newExtractorLink(label, label, masterUrl, type = ExtractorLinkType.M3U8) {
                                this.referer = embedUrl
                                this.headers = playHeaders
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
                                    this.headers = playHeaders
                                }
                            )
                        }
                        found = true
                    }
                } else {
                    Log.e("Animo", "[$labelKey] Master content is not M3U8: ${masterContent.take(100)}")
                }

                // Add subtitle tracks
                subtitles.forEach { (label, subUrl) ->
                    Log.i("Animo", "[$labelKey] Adding subtitle: $label -> ${subUrl.take(80)}...")
                    subtitleCallback.invoke(newSubtitleFile(label, subUrl) {
                        this.headers = playHeaders
                    })
                }

                if (found) {
                    Log.i("Animo", "[$labelKey] Success! Breaking.")
                    break
                }
            } catch (e: Exception) {
                Log.e("Animo", "[$labelKey] Exception: ${e.message}")
            }
        }

        Log.i("Animo", "========== loadLinks END (found=$found) ==========")
        return found
    }

    data class SubtitleTrack(val label: String, val url: String)
    data class ExtractResult(val masterUrl: String, val masterContent: String, val subtitles: List<SubtitleTrack>)

    /**
     * Loads the embed page in a WebView. After page loads, uses evaluateJavascript
     * to:
     * 1. Extract the sourcesUrl token from the page's JavaScript
     * 2. Call the getSources API via fetch() (runs in WebView context, uses
     *    WebView's cf_clearance cookies automatically)
     * 3. Parse JSON to get master m3u8 URL + subtitle tracks
     * 4. Fetch the master m3u8 content via fetch() (in WebView context)
     *
     * Returns ExtractResult(masterUrl, masterContent, subtitles) or null.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractViaWebView(embedUrl: String): ExtractResult? = withContext(Dispatchers.Main) {
        val context = com.lagradost.cloudstream3.CommonActivity.activity ?: run {
            Log.e("Animo", "WebView: no activity context")
            return@withContext null
        }

        Log.i("Animo", "WebView: loading $embedUrl")

        val result = withTimeoutOrNull(60_000L) {
            suspendCancellableCoroutine<ExtractResult?> { cont ->
                val webView = WebView(context)
                var jsCalled = false

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

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.i("Animo", "WebView onPageFinished: $url")
                            // Don't process immediately — CF challenge may still be running.
                            // Use evaluateJavascript to check if sourcesUrl exists yet.
                            // Poll every 2 seconds up to 10 times.
                            if (!jsCalled && url != null && url.contains("/embed/")) {
                                jsCalled = true
                                Log.i("Animo", "WebView: starting JS polling for sourcesUrl")
                                pollForSourcesUrl(view, embedUrl, cont, attempt = 0)
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
            Log.e("Animo", "WebView: timed out after 60s")
        }
        result
    }

    /**
     * Polls the WebView page for sourcesUrl. CF challenge takes ~5s to solve,
     * so we retry every 2 seconds.
     */
    private fun pollForSourcesUrl(
        webView: WebView?,
        embedUrl: String,
        cont: kotlinx.coroutines.CancellableContinuation<ExtractResult?>,
        attempt: Int
    ) {
        if (webView == null || !cont.isActive) {
            Log.e("Animo", "pollForSourcesUrl: webView null or cont inactive")
            return
        }
        if (attempt >= 15) {
            Log.e("Animo", "pollForSourcesUrl: max attempts (15) reached")
            if (cont.isActive) cont.resume(null)
            return
        }

        Log.i("Animo", "pollForSourcesUrl: attempt ${attempt + 1}/15")

        // Check if sourcesUrl exists yet
        webView.evaluateJavascript("(function(){ return typeof window.sourcesUrl !== 'undefined' ? window.sourcesUrl : null; })();") { result ->
            Log.i("Animo", "pollForSourcesUrl: sourcesUrl check = ${(result ?: "null").take(80)}")

            if (result != null && result != "null" && result.contains("getSources")) {
                // sourcesUrl exists! Now do the full extraction
                Log.i("Animo", "pollForSourcesUrl: sourcesUrl found! Running full extraction...")
                runFullExtraction(webView, embedUrl, cont)
            } else {
                // sourcesUrl not ready yet (CF challenge still running). Retry in 2s.
                Log.i("Animo", "pollForSourcesUrl: not ready, retrying in 2s...")
                webView.postDelayed({
                    pollForSourcesUrl(webView, embedUrl, cont, attempt + 1)
                }, 2000L)
            }
        }
    }

    /**
     * Runs the full extraction: fetch getSources, fetch master m3u8, all via
     * synchronous XHR in the WebView's JS context.
     */
    private fun runFullExtraction(
        webView: WebView,
        embedUrl: String,
        cont: kotlinx.coroutines.CancellableContinuation<ExtractResult?>
    ) {
        Log.i("Animo", "runFullExtraction: injecting JS...")

        val js = """
            (function() {
                try {
                    var sourcesUrl = window.sourcesUrl;
                    if (!sourcesUrl) {
                        var scripts = document.querySelectorAll('script');
                        for (var i = 0; i < scripts.length; i++) {
                            var m = scripts[i].textContent.match(/sourcesUrl\s*=\s*['"]([^'"]+)['"]/);
                            if (m) { sourcesUrl = m[1]; break; }
                        }
                    }
                    if (!sourcesUrl) return JSON.stringify({error: 'no sourcesUrl found'});

                    var fullUrl = sourcesUrl.startsWith('http') ? sourcesUrl : (window.location.origin + sourcesUrl);

                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', fullUrl, false);
                    xhr.setRequestHeader('Accept', 'application/json');
                    try { xhr.send(); } catch(e) { return JSON.stringify({error: 'xhr send failed: ' + e.message}); }

                    if (xhr.status !== 200) return JSON.stringify({error: 'getSources status ' + xhr.status, body: xhr.responseText.substring(0, 200)});

                    var data = JSON.parse(xhr.responseText);
                    var m3u8File = data.sources && data.sources[0] ? data.sources[0].file : null;
                    if (!m3u8File) return JSON.stringify({error: 'no source file in response'});

                    var m3u8Url = m3u8File.startsWith('http') ? m3u8File : (window.location.origin + m3u8File);

                    var xhr2 = new XMLHttpRequest();
                    xhr2.open('GET', m3u8Url, false);
                    xhr2.setRequestHeader('Accept', '*/*');
                    try { xhr2.send(); } catch(e) { return JSON.stringify({error: 'm3u8 fetch failed: ' + e.message}); }

                    var masterContent = xhr2.responseText;
                    var tracks = [];
                    if (data.tracks) {
                        for (var i = 0; i < data.tracks.length; i++) {
                            var t = data.tracks[i];
                            if (t.kind === 'captions' || t.kind === 'subtitles' || !t.kind) {
                                var tFile = t.file.startsWith('http') ? t.file : (window.location.origin + t.file);
                                tracks.push({label: t.label || ('Sub ' + (i+1)), file: tFile});
                            }
                        }
                    }

                    return JSON.stringify({
                        masterUrl: m3u8Url,
                        masterContent: masterContent,
                        masterStatus: xhr2.status,
                        tracks: tracks
                    });
                } catch(e) {
                    return JSON.stringify({error: e.message, stack: e.stack ? e.stack.substring(0, 300) : ''});
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { resultStr ->
            Log.i("Animo", "runFullExtraction: JS result (first 200): ${(resultStr ?: "null").take(200)}")

            if (resultStr == null || resultStr == "null") {
                Log.e("Animo", "runFullExtraction: JS returned null")
                if (cont.isActive) cont.resume(null)
                return@evaluateJavascript
            }

            try {
                val jsonString = parseJson<String>(resultStr)
                val data = parseJson<JsonObject>(jsonString)

                if (data.has("error")) {
                    Log.e("Animo", "runFullExtraction: JS error: ${data.get("error").asString}")
                    if (cont.isActive) cont.resume(null)
                    return@evaluateJavascript
                }

                val masterUrl = data.get("masterUrl").asString
                val masterContent = data.get("masterContent").asString
                val masterStatus = data.get("masterStatus").asInt
                Log.i("Animo", "runFullExtraction: masterUrl=${masterUrl.take(80)}...")
                Log.i("Animo", "runFullExtraction: masterStatus=$masterStatus")
                Log.i("Animo", "runFullExtraction: masterContent starts #EXTM3U: ${masterContent.trim().startsWith("#EXTM3U")}")
                Log.i("Animo", "runFullExtraction: masterContent (first 200): ${masterContent.take(200)}")

                val tracks = mutableListOf<SubtitleTrack>()
                if (data.has("tracks") && data.get("tracks").isJsonArray) {
                    data.get("tracks").asJsonArray.forEach { trackElem ->
                        val track = trackElem.asJsonObject
                        tracks.add(SubtitleTrack(
                            track.get("label").asString,
                            track.get("file").asString
                        ))
                    }
                }
                Log.i("Animo", "runFullExtraction: tracks count=${tracks.size}")

                if (cont.isActive) cont.resume(ExtractResult(masterUrl, masterContent, tracks))
            } catch (e: Exception) {
                Log.e("Animo", "runFullExtraction: parse exception: ${e.message}")
                Log.e("Animo", "runFullExtraction: raw result: ${resultStr.take(500)}")
                if (cont.isActive) cont.resume(null)
            }
        }
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
