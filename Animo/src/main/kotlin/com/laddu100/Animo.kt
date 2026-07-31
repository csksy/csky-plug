package com.laddu100

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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
            Log.i("Animo", "[$labelKey] Trying WebView for: $embedUrl")
            try {
                val result = extractViaWebView(embedUrl) ?: continue
                val (masterUrl, masterContent, subtitles) = result

                Log.i("Animo", "[$labelKey] Master URL: ${masterUrl.take(80)}...")
                Log.i("Animo", "[$labelKey] Master starts #EXTM3U: ${masterContent.trim().startsWith("#EXTM3U")}")
                Log.i("Animo", "[$labelKey] Subtitles: ${subtitles.size}")

                val playHeaders = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "*/*",
                    "Referer" to embedUrl,
                    "Origin" to cdnUrl
                )

                if (masterContent.trim().startsWith("#EXTM3U")) {
                    val variantPattern = Regex("""#EXT-X-STREAM-INF:[^\n]*?(?:NAME="(\d+p)"|RESOLUTION=(\d+)x(\d+))[^\n]*\n([^\n#][^\n]*)""")
                    val variants = variantPattern.findAll(masterContent).toList()
                    Log.i("Animo", "[$labelKey] Variants: ${variants.size}")

                    if (variants.isEmpty()) {
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
                            Log.i("Animo", "[$labelKey] Adding: $quality")
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
                    Log.e("Animo", "[$labelKey] Master not M3U8: ${masterContent.take(100)}")
                }

                subtitles.forEach { (label, subUrl) ->
                    Log.i("Animo", "[$labelKey] Subtitle: $label")
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
     * Loads embed page in WebView. After CF challenge solves, injects async fetch()
     * to call getSources API + fetch master m3u8 — all in WebView's JS context
     * (uses WebView's cf_clearance cookies). Uses async fetch + polling pattern
     * (like Miruro) instead of synchronous XHR (which can deadlock).
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

        withTimeoutOrNull(60_000L) {
            suspendCancellableCoroutine<ExtractResult?> { cont ->
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                val fetchInjected = java.util.concurrent.atomic.AtomicBoolean(false)
                var webView: WebView? = null

                fun finish(result: ExtractResult?) {
                    if (done.compareAndSet(false, true)) {
                        try { webView?.destroy() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(result)
                    }
                }

                fun injectFetch(view: WebView?) {
                    if (done.get() || !fetchInjected.compareAndSet(false, true)) return
                    Log.i("Animo", "injectFetch: injecting async fetch JS")

                    // Inject async fetch() — stores OBJECT (not stringified) in window.__animo_result
                    // evaluateJavascript will auto-serialize objects to JSON, avoiding double-encoding
                    val js = """
                        (function() {
                            window.__animo_result = null;
                            window.__animo_error = null;
                            try {
                                var sourcesUrl = window.sourcesUrl;
                                if (!sourcesUrl) {
                                    var scripts = document.querySelectorAll('script');
                                    for (var i = 0; i < scripts.length; i++) {
                                        var m = scripts[i].textContent.match(/sourcesUrl\s*=\s*['"]([^'"]+)['"]/);
                                        if (m) { sourcesUrl = m[1]; break; }
                                    }
                                }
                                if (!sourcesUrl) {
                                    window.__animo_error = 'no sourcesUrl';
                                    return;
                                }
                                var fullSourcesUrl = sourcesUrl.startsWith('http') ? sourcesUrl : (window.location.origin + sourcesUrl);

                                fetch(fullSourcesUrl, {
                                    method: 'GET',
                                    credentials: 'include',
                                    headers: { 'Accept': 'application/json' }
                                }).then(function(r) {
                                    return r.json();
                                }).then(function(data) {
                                    var m3u8File = data.sources && data.sources[0] ? data.sources[0].file : null;
                                    if (!m3u8File) {
                                        window.__animo_error = 'no source file in getSources';
                                        return;
                                    }
                                    var m3u8Url = m3u8File.startsWith('http') ? m3u8File : (window.location.origin + m3u8File);

                                    return fetch(m3u8Url, {
                                        method: 'GET',
                                        credentials: 'include',
                                        headers: { 'Accept': '*/*' }
                                    }).then(function(r) {
                                        return r.text().then(function(text) {
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
                                            // Store OBJECT directly (NOT JSON.stringify)
                                            // evaluateJavascript will auto-serialize to JSON
                                            window.__animo_result = {
                                                masterUrl: m3u8Url,
                                                masterContent: text,
                                                tracks: tracks
                                            };
                                        });
                                    });
                                }).catch(function(e) {
                                    window.__animo_error = e.message;
                                });
                            } catch(e) {
                                window.__animo_error = e.message;
                            }
                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(js) {}

                    // Poll for result every 500ms (up to 20s = 40 attempts)
                    for (i in 1..40) {
                        val delay = (i * 500).toLong()
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (done.get()) return@postDelayed
                            // Return the object directly — evaluateJavascript auto-serializes objects to JSON
                            view?.evaluateJavascript(
                                "(function(){ if(window.__animo_result) return window.__animo_result; if(window.__animo_error) return 'ERROR:'+window.__animo_error; return null; })()"
                            ) { result ->
                                if (done.get()) return@evaluateJavascript
                                if (result == null || result == "null") return@evaluateJavascript

                                when {
                                    // JSON object (from window.__animo_result being an object)
                                    result.startsWith("{") -> {
                                        try {
                                            val data = parseJson<JsonObject>(result)
                                            val masterUrl = data.get("masterUrl")?.asString ?: run {
                                                Log.e("Animo", "No masterUrl in result")
                                                finish(null)
                                                return@evaluateJavascript
                                            }
                                            val masterContent = data.get("masterContent")?.asString ?: ""
                                            val tracksList = mutableListOf<SubtitleTrack>()
                                            if (data.has("tracks") && data.get("tracks")?.isJsonArray == true) {
                                                data.get("tracks")?.asJsonArray?.forEach { trackElem ->
                                                    val track = trackElem.asJsonObject
                                                    val tLabel = track.get("label")?.asString ?: "Sub"
                                                    val tFile = track.get("file")?.asString ?: return@forEach
                                                    tracksList.add(SubtitleTrack(tLabel, tFile))
                                                }
                                            }
                                            Log.i("Animo", "Got masterUrl=${masterUrl.take(60)}...")
                                            Log.i("Animo", "Got masterContent starts #EXTM3U: ${masterContent.trim().startsWith("#EXTM3U")}")
                                            Log.i("Animo", "Got ${tracksList.size} subtitles")
                                            finish(ExtractResult(masterUrl, masterContent, tracksList))
                                        } catch (e: Exception) {
                                            Log.e("Animo", "Parse exception: ${e.message}")
                                            Log.e("Animo", "Raw (first 300): ${result.take(300)}")
                                            finish(null)
                                        }
                                    }
                                    // Error string (from window.__animo_error)
                                    result.startsWith("\"") -> {
                                        try {
                                            val errorStr = parseJson<String>(result)
                                            if (errorStr.startsWith("ERROR:")) {
                                                Log.e("Animo", "JS error: $errorStr")
                                                finish(null)
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }, delay)
                    }
                }

                fun checkAndInject(view: WebView?) {
                    if (done.get() || fetchInjected.get()) return
                    // Check if CF challenge is solved by looking at document title
                    view?.evaluateJavascript("document.title") { titleResult ->
                        if (done.get() || fetchInjected.get()) return@evaluateJavascript
                        val title = titleResult?.trim()?.removeSurrounding("\"") ?: ""
                        Log.i("Animo", "checkAndInject: title='$title'")

                        val isChallenge = title.lowercase().contains("just a moment") ||
                                          title.lowercase().contains("attention required") ||
                                          title.lowercase().contains("cloudflare") ||
                                          title.lowercase().contains("blocked") ||
                                          title.isBlank()

                        if (!isChallenge) {
                            Log.i("Animo", "CF challenge solved! Injecting fetch...")
                            injectFetch(view)
                        }
                    }
                }

                try {
                    CookieManager.getInstance().setAcceptCookie(true)
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString = ua
                        settings.blockNetworkImage = true

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Log.i("Animo", "onPageFinished: $url")
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    checkAndInject(view)
                                }, 500)
                            }
                        }
                    }

                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    webView?.loadUrl(embedUrl)

                    // Periodic CF-solved check every 1s (CF can take 5-10s)
                    for (i in 1..20) {
                        val delay = (i * 1000).toLong()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            checkAndInject(webView)
                        }, delay)
                    }

                    // Overall timeout: 60s
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        finish(null)
                    }, 60000)
                } catch (e: Exception) {
                    Log.e("Animo", "WebView exception: ${e.message}")
                    finish(null)
                }
                cont.invokeOnCancellation {
                    finish(null)
                }
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
