package com.laddu100

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val cdnUrl get() = mainUrl.replace("://", "://cdn.")

    private val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val apiHeaders get() = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    @Volatile
    private var isUrlLoaded = false

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FirebaseConfig(
        @JsonProperty("animo_url") val animo_url: String? = null,
        @JsonProperty("animo") val animo: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val resp = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json", timeout = 10_000L).text
            val config = parseJson<FirebaseConfig>(resp)
            val url = config.animo_url ?: config.animo
            if (!url.isNullOrBlank()) mainUrl = url.removeSuffix("/")
            isUrlLoaded = true
        } catch (e: Exception) {
            isUrlLoaded = true
        }
    }

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
        loadFirebaseUrl()
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
            if (trimmed.startsWith("[")) parseJson(text)
            else parseJson<SearchResponseData>(text).data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        loadFirebaseUrl()
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
        loadFirebaseUrl()
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
        loadFirebaseUrl()
        val epData = try { parseJson<EpisodeData>(data) } catch (e: Exception) { return false }

        val playHeaders = mapOf(
            "Referer" to "$cdnUrl/",
            "Origin" to cdnUrl,
            "User-Agent" to ua
        )

        var found = false
        val typesToTry = if (epData.streamType == "dub") listOf("dub", "sub") else listOf("sub", "dub")
        for (type in typesToTry) {
            var subsPassedForType = false
            for (hd in 1..4) {
                val sourcesUrl = "$cdnUrl/stream/getSources?hd=$hd&id=${epData.animeId}&episode=${epData.episodeNum}&type=$type"
                try {
                    val response = animoGet(sourcesUrl, mapOf(
                        "User-Agent" to ua,
                        "Accept" to "application/json, text/plain, */*",
                        "Referer" to "$cdnUrl/embed/hd-$hd/${epData.animeId}/${epData.episodeNum}/$type",
                        "Origin" to cdnUrl
                    ))
                    if (response.code != 200) continue
                    val text = response.text
                    if (text.contains("Just a moment") || text.contains("challenge-platform")) continue

                    val sources = parseJson<GetSourcesResponse>(text)

                    sources.sources?.forEach { s ->
                        val file = s.file ?: return@forEach
                        val streamUrl = if (file.startsWith("http")) file else "$cdnUrl/${file.removePrefix("/")}"
                        val label = "$name HD$hd ($type)"
                        if (s.type == "hls" || streamUrl.contains(".m3u8")) {
                            try {
                                M3u8Helper.generateM3u8(label, streamUrl, "$cdnUrl/", headers = playHeaders).forEach(callback)
                            } catch (e: Exception) {
                                callback.invoke(
                                    newExtractorLink(label, label, streamUrl, type = ExtractorLinkType.M3U8) {
                                        this.referer = "$cdnUrl/"
                                        this.headers = playHeaders
                                    }
                                )
                            }
                            found = true
                        } else {
                            callback.invoke(
                                newExtractorLink(label, label, streamUrl, type = ExtractorLinkType.VIDEO) {
                                    this.referer = "$cdnUrl/"
                                    this.headers = playHeaders
                                }
                            )
                            found = true
                        }
                    }

                    if (!subsPassedForType && !sources.tracks.isNullOrEmpty()) {
                        sources.tracks.forEach { t ->
                            val file = t.file ?: return@forEach
                            val subUrl = if (file.startsWith("http")) file else "$cdnUrl/${file.removePrefix("/")}"
                            subtitleCallback.invoke(newSubtitleFile(t.label ?: "English", subUrl) {
                                this.headers = playHeaders
                            })
                        }
                        subsPassedForType = true
                    }
                } catch (e: Exception) {
                    Log.e("Animo", "getSources hd=$hd: ${e.message}")
                }
            }
            if (found) break
        }

        if (!found) {
            val watchUrl = if (epData.slug.isNotBlank()) {
                "$mainUrl/watch/${epData.slug}?ep=${epData.episodeNum}"
            } else {
                "$mainUrl/embed/${epData.embedId}"
            }
            val streamUrl = withTimeoutOrNull(30_000L) {
                extractStreamFromWebView(watchUrl)
            }
            if (streamUrl != null && streamUrl.isNotEmpty()) {
                if (streamUrl.contains(".m3u8")) {
                    try {
                        M3u8Helper.generateM3u8(name, streamUrl, "$cdnUrl/", headers = playHeaders).forEach(callback)
                    } catch (e: Exception) {
                        callback.invoke(
                            newExtractorLink(name, name, streamUrl, type = ExtractorLinkType.M3U8) {
                                this.referer = "$cdnUrl/"
                                this.headers = playHeaders
                            }
                        )
                    }
                    found = true
                } else {
                    callback.invoke(
                        newExtractorLink(name, name, streamUrl, type = ExtractorLinkType.VIDEO) {
                            this.referer = "$cdnUrl/"
                            this.headers = playHeaders
                        }
                    )
                    found = true
                }
            }
        }

        return found
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractStreamFromWebView(url: String): String? = withContext(Dispatchers.Main) {
        val context = CommonActivity.activity ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            var foundUrl: String? = null
            val webView = WebView(context)
            try {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = ua
                    mediaPlaybackRequiresUserGesture = false
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
                    override fun onLoadResource(view: WebView?, resourceUrl: String?) {
                        super.onLoadResource(view, resourceUrl)
                        if (resourceUrl != null && foundUrl == null) {
                            if (resourceUrl.contains(".m3u8") || resourceUrl.contains(".mp4")) {
                                foundUrl = resourceUrl
                                if (cont.isActive) cont.resume(resourceUrl)
                            }
                        }
                    }
                }
                webView.loadUrl(url)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation {
                try { webView.destroy() } catch (_: Exception) {}
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
        val animeId: Int, val episodeId: Int, val embedId: String?,
        val episodeNum: Int, val slug: String, val streamType: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeSearchItem(
        val id: Int? = null, val slug: String? = null, val titles: Titles? = null,
        val images: Images? = null, val type: String? = null, val status: String? = null,
        val episodes_count: Int? = null, val sub_count: Int? = null, val dub_count: Int? = null,
        val score: Double? = null, val season_year: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResponseData(val success: Boolean? = null, val data: List<AnimeSearchItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Images(val poster: String? = null, val banner: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Titles(val romaji: String? = null, val english: String? = null, val native: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDetails(
        val id: Int? = null, val slug: String? = null, val titles: Titles? = null,
        val synopsis: String? = null, val images: Images? = null, val type: String? = null,
        val status: String? = null, val score: Double? = null, val rating: String? = null,
        val air: Air? = null, val genres: List<String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Air(val start: String? = null, val end: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResponse(
        val anime_id: Int? = null, val total: Int? = null,
        val sub_count: String? = null, val dub_count: String? = null,
        val data: List<EpisodeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        val id: Int? = null, val number: Int? = null, val titles: EpisodeTitles? = null,
        val filler: Boolean? = null, val rating: String? = null, val thumbnail: String? = null,
        val sub: Boolean? = null, val dub: Boolean? = null,
        @JsonProperty("embed_id") val embed_id: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeTitles(val en: String? = null, val ja: String? = null, val romaji: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GetSourcesResponse(
        val sources: List<MegaSource>? = null, val tracks: List<MegaTrack>? = null,
        val encrypted: Boolean? = null, val server: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaSource(val file: String? = null, val type: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaTrack(val file: String? = null, val label: String? = null, val kind: String? = null, val default: Boolean? = null)

    companion object {
        private const val CF_TAG = "Animo_CF"
        private const val COOKIE_TTL = 15L * 60 * 60 * 1000
        private val cfMutex = Mutex()

        @Volatile private var cfCookies: String? = null
        @Volatile private var cfUA: String? = null
        @Volatile private var cfTimestamp: Long = 0L

        fun initCF() {
            try {
                cfCookies = CloudStreamApp.getKey<String>("ANIMO_CF_COOKIES")
                cfUA = CloudStreamApp.getKey<String>("ANIMO_CF_UA")
            } catch (e: Exception) {}
        }

        fun getCFCookies(): String? {
            val c = cfCookies
            if (c.isNullOrBlank()) return null
            if (System.currentTimeMillis() - cfTimestamp > COOKIE_TTL) { clearCF(); return null }
            return c
        }

        fun clearCF() {
            cfCookies = null; cfUA = null; cfTimestamp = 0L
            try { CloudStreamApp.setKey("ANIMO_CF_COOKIES", ""); CloudStreamApp.setKey("ANIMO_CF_UA", "") } catch (e: Exception) {}
        }

        private fun saveCF(cookies: String, ua: String) {
            cfCookies = cookies; cfUA = ua; cfTimestamp = System.currentTimeMillis()
            try {
                CloudStreamApp.setKey("ANIMO_CF_COOKIES", cookies)
                CloudStreamApp.setKey("ANIMO_CF_UA", ua)
            } catch (e: Exception) {}
        }

        suspend fun showCFDialogManual(host: String): Boolean = Animo().showCFDialogInternal(host)
    }

    private fun isCFBlocked(response: NiceResponse): Boolean {
        if (response.code == 503) return true
        val body = try { response.text.lowercase() } catch (e: Exception) { "" }
        if (response.code == 403) {
            return body.contains("just a moment") || body.contains("challenge-platform") || body.contains("cf-browser-verification")
        }
        return body.contains("just a moment") && body.contains("challenge-platform")
    }

    private suspend fun animoGet(url: String, headers: Map<String, String> = emptyMap()): NiceResponse {
        fun buildHeaders(): Map<String, String> {
            val h = headers.toMutableMap()
            getCFCookies()?.let { h["Cookie"] = it }
            cfUA?.let { h["User-Agent"] = it }
            return h
        }

        var response = app.get(url, headers = buildHeaders(), timeout = 30_000L)
        if (!isCFBlocked(response)) return response

        cfMutex.withLock {
            getCFCookies()?.let {
                response = app.get(url, headers = buildHeaders(), timeout = 30_000L)
                if (!isCFBlocked(response)) return response
            }
            clearCF()
            val host = try { Uri.parse(url).let { "${it.scheme}://${it.host}" } } catch (e: Exception) { url }
            val success = showCFDialogInternal(host)
            if (!success) return@withLock
            for (i in 1..2) {
                response = app.get(url, headers = buildHeaders(), timeout = 30_000L)
                if (!isCFBlocked(response)) return@withLock
            }
        }
        return response
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun showCFDialogInternal(host: String): Boolean = withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity as? AppCompatActivity
        if (activity == null || activity.isFinishing || activity.isDestroyed) return@withContext false

        suspendCancellableCoroutine { cont ->
            val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(activity)

            try {
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                    listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                        setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                    }
                    flush()
                }
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = ua
                    mediaPlaybackRequiresUserGesture = false
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (resolved.get()) return
                        CookieManager.getInstance().flush()
                        val cookies = CookieManager.getInstance().getCookie(host) ?: ""
                        if (cookies.contains("cf_clearance")) {
                            if (resolved.compareAndSet(false, true)) {
                                handler.removeCallbacksAndMessages(null)
                                saveCF(cookies, webView.settings.userAgentString)
                                try { webView.destroy() } catch (e: Exception) {}
                                if (cont.isActive) cont.resume(true)
                            }
                        }
                    }
                }

                val isTv = try { Globals.isLayout(Globals.TV) } catch (e: Throwable) { false }
                val dp = activity.resources.displayMetrics.density
                val screenH = activity.resources.displayMetrics.heightPixels
                val webH = (screenH * 0.65f).toInt()

                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
                }
                val status = TextView(activity).apply {
                    text = "Loading..."; textSize = 12f; setTextColor(Color.parseColor("#A0A0B0"))
                    setPadding(0, 0, 0, (4 * dp).toInt())
                }
                container.addView(status)

                val webContainer = FrameLayout(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, webH)
                    isFocusable = true; isFocusableInTouchMode = true
                }
                webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))

                if (isTv) {
                    val cs = (22 * dp).toInt()
                    val cursor = View(activity).apply {
                        layoutParams = FrameLayout.LayoutParams(cs, cs)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.argb(160, 255, 50, 50))
                            setStroke((2 * dp).toInt(), Color.WHITE)
                        }
                        elevation = 999f
                    }
                    webContainer.addView(cursor)
                    class P { var x = 0f; var y = 0f }
                    val p = P()
                    p.x = webH / 2f; p.y = webH / 2f
                    cursor.translationX = p.x - cs / 2f; cursor.translationY = p.y - cs / 2f
                    val step = 10f * dp
                    webContainer.setOnKeyListener { _, kc, ev ->
                        if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (kc) {
                            KeyEvent.KEYCODE_DPAD_UP -> { p.y = (p.y - step).coerceIn(0f, webContainer.height.toFloat()); cursor.translationY = p.y - cs / 2f; true }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { p.y = (p.y + step).coerceIn(0f, webContainer.height.toFloat()); cursor.translationY = p.y - cs / 2f; true }
                            KeyEvent.KEYCODE_DPAD_LEFT -> { p.x = (p.x - step).coerceIn(0f, webContainer.width.toFloat()); cursor.translationX = p.x - cs / 2f; true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { p.x = (p.x + step).coerceIn(0f, webContainer.width.toFloat()); cursor.translationX = p.x - cs / 2f; true }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                val t = SystemClock.uptimeMillis()
                                val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, p.x, p.y, 0)
                                val u = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, p.x, p.y, 0)
                                try { webView.dispatchTouchEvent(d); webView.dispatchTouchEvent(u) } catch (e: Exception) {}
                                finally { d.recycle(); u.recycle() }
                                true
                            }
                            else -> false
                        }
                    }
                    webContainer.requestFocus()
                }
                container.addView(webContainer)

                val btnRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = (8 * dp).toInt() }
                }
                btnRow.addView(Button(activity).apply {
                    text = "Done"
                    setOnClickListener {
                        CookieManager.getInstance().flush()
                        val c = CookieManager.getInstance().getCookie(host) ?: ""
                        if (c.contains("cf_clearance") && resolved.compareAndSet(false, true)) {
                            handler.removeCallbacksAndMessages(null)
                            saveCF(c, webView.settings.userAgentString)
                            try { webView.destroy() } catch (e: Exception) {}
                            if (cont.isActive) cont.resume(true)
                        } else { status.text = "No cf_clearance found." }
                    }
                })
                btnRow.addView(Button(activity).apply {
                    text = "Cancel"
                    setOnClickListener {
                        if (resolved.compareAndSet(false, true)) {
                            handler.removeCallbacksAndMessages(null)
                            try { webView.destroy() } catch (e: Exception) {}
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                })
                container.addView(btnRow)

                val dialog = AlertDialog.Builder(activity).setView(container).setCancelable(false).create()
                dialog.setOnDismissListener {
                    handler.removeCallbacksAndMessages(null)
                    if (!resolved.get()) {
                        resolved.set(true)
                        try { webView.destroy() } catch (e: Exception) {}
                        if (cont.isActive) cont.resume(false)
                    }
                }
                dialog.show()
                dialog.window?.apply {
                    setLayout((activity.resources.displayMetrics.widthPixels * 0.95f).toInt(), (screenH * 0.9f).toInt())
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                }

                val poll = object : Runnable {
                    override fun run() {
                        if (resolved.get() || !dialog.isShowing) return
                        CookieManager.getInstance().flush()
                        val c = CookieManager.getInstance().getCookie(host) ?: ""
                        if (c.contains("cf_clearance") && resolved.compareAndSet(false, true)) {
                            handler.removeCallbacksAndMessages(null)
                            saveCF(c, webView.settings.userAgentString)
                            try { webView.destroy() } catch (e: Exception) {}
                            try { dialog.dismiss() } catch (e: Exception) {}
                            if (cont.isActive) cont.resume(true)
                        } else {
                            handler.postDelayed(this, 1000L)
                        }
                    }
                }
                handler.postDelayed(poll, 1000L)
                handler.postDelayed({
                    if (!resolved.get()) {
                        resolved.set(true)
                        try { webView.destroy() } catch (e: Exception) {}
                        try { dialog.dismiss() } catch (e: Exception) {}
                        if (cont.isActive) cont.resume(false)
                    }
                }, 120_000L)

                webView.loadUrl(host)
            } catch (e: Exception) {
                Log.e(CF_TAG, "showCFDialog: ${e.message}")
                try { webView.destroy() } catch (ex: Exception) {}
                if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation { try { webView.destroy() } catch (e: Exception) {} }
        }
    }
}
