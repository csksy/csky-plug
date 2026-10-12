package com.laddu100

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request

data class ChannelStreamResponse(
    val streamUrls: List<StreamUrl>? = null,
    val nextChannel: String? = null,
    val prevChannel: String? = null,
    val related: List<String>? = null
)

data class StreamUrl(
    val id: Int? = null,
    val title: String? = null,
    val link: String? = null,
    val api: String? = null,
    val type: String? = null,
    val webLink: String? = null
)

class LIVETVLiveEventsProvider(
    private val customName: String = "LIVE TV Live Events",
    private val customCatLink: String? = null
) : MainAPI() {

    companion object {
        var context: Context? = null
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    }

    override var mainUrl = "https://cfyhljddgbkkufh82.top"
    override var name = customName
    override var lang = "hi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class LiveEventLoadData(
        val eventId: Int,
        val title: String,
        val poster: String,
        val slug: String,
        val formats: List<LiveEventFormat>,
        val eventInfo: LiveEventInfo?
    )

    private fun createDisplayTitle(event: LiveEventData): String {
        val info = event.eventInfo ?: return event.title
        val teamA = info.teamA
        val teamB = info.teamB
        if (teamA.isNullOrBlank() || teamB.isNullOrBlank()) return event.title
        return if (teamA == teamB) teamA else "$teamA vs $teamB"
    }

    private fun getEventStatus(event: LiveEventData): String {
        val info = event.eventInfo ?: return ""
        val now = System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val start = info.startTime?.let { fmt.parse(it)?.time }
            val end = info.endTime?.let { fmt.parse(it)?.time }
            when {
                end != null && now >= end -> "[Ended]"
                start != null && now >= start -> "[LIVE]"
                start != null && now < start -> "[Upcoming]"
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun isEventLive(event: LiveEventData): Boolean {
        val info = event.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val start = info.startTime?.let { fmt.parse(it)?.time }
            val end = info.endTime?.let { fmt.parse(it)?.time }
            if (end != null && now >= end) false
            else start != null && now >= start
        } catch (_: Exception) {
            false
        }
    }

    private fun isEventEnded(event: LiveEventData): Boolean {
        val info = event.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val end = info.endTime?.let { fmt.parse(it)?.time }
            end != null && now >= end
        } catch (_: Exception) {
            false
        }
    }

    private fun generateMatchCardUrl(event: LiveEventData): String {
        val info = event.eventInfo
        val encode: (String) -> String = { URLEncoder.encode(it, "UTF-8") }

        val title = encode(info?.eventName ?: event.title)
        val teamA = encode(info?.teamA ?: "Team A")
        val teamB = encode(info?.teamB ?: "Team B")
        val teamAImg = info?.teamAFlag ?: ""
        val teamBImg = info?.teamBFlag ?: ""
        val eventLogo = info?.eventLogo ?: ""
        val isLive = isEventLive(event)
        val isEnded = isEventEnded(event)

        val time = try {
            info?.startTime?.let {
                val df = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                val disp = SimpleDateFormat("MMM dd, yyyy hh:mm a 'UTC'", Locale.US)
                disp.timeZone = TimeZone.getTimeZone("UTC")
                df.parse(it)?.let { d -> encode(disp.format(d)) } ?: ""
            } ?: ""
        } catch (_: Exception) {
            ""
        }

        return buildString {
            append("https://live-card-png.cricify.workers.dev/?")
            append("title=$title")
            append("&teamA=$teamA")
            append("&teamB=$teamB")
            if (teamAImg.isNotBlank()) append("&teamAImg=$teamAImg")
            if (teamBImg.isNotBlank()) append("&teamBImg=$teamBImg")
            if (eventLogo.isNotBlank()) append("&eventLogo=$eventLogo")
            if (time.isNotBlank()) append("&time=$time")
            append("&isLive=$isLive")
            append("&isEnded=$isEnded")
        }
    }

    private fun eventStartTimeOrNull(event: LiveEventData): Long? {
        return try {
            val info = event.eventInfo ?: return null
            val startTime = info.startTime ?: return null
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            fmt.parse(startTime)?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun statusRank(event: LiveEventData): Int {
        return when {
            isEventLive(event) -> 0
            getEventStatus(event) == "[Upcoming]" -> 1
            isEventEnded(event) -> 2
            else -> 3
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LIVETVProviderManager.refreshFromFirebase()
        DonationManager.checkAndShow()
        val events = if (customCatLink != null) {
            LIVETVProviderManager.fetchCustomEvents(customCatLink)
        } else {
            LIVETVProviderManager.fetchLiveEvents()
        }

        val pages = events
            .groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }
            .map { (category, catEvents) ->
                val items = catEvents
                    .sortedWith(
                        compareBy({ statusRank(it) },
                            { eventStartTimeOrNull(it) ?: Long.MAX_VALUE })
                    )
                    .map { event ->
                        val displayTitle = createDisplayTitle(event)
                        val status = getEventStatus(event)
                        val fullTitle =
                            if (status.isNotBlank()) "$status $displayTitle" else displayTitle
                        val poster = if (event.cat == "Custom" && !event.image.isNullOrBlank()) {
                            event.image
                        } else {
                            generateMatchCardUrl(event)
                        }
                        val loadData = LiveEventLoadData(
                            eventId = event.id,
                            title = displayTitle,
                            poster = poster,
                            slug = event.slug,
                            formats = event.formats ?: emptyList(),
                            eventInfo = event.eventInfo
                        )
                        newLiveSearchResponse(fullTitle, loadData.toJson(), TvType.Live) {
                            this.posterUrl = poster
                        }
                    }
                HomePageList(category, items, isHorizontalImages = true)
            }
            .sortedBy { list ->
                when {
                    list.name.contains("Cricket", ignoreCase = true) -> 0
                    list.name.contains("Football", ignoreCase = true) -> 1
                    list.name.contains("Basketball", ignoreCase = true) -> 2
                    else -> 10
                }
            }

        return newHomePageResponse(pages, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val events = if (customCatLink != null) {
            LIVETVProviderManager.fetchCustomEvents(customCatLink)
        } else {
            LIVETVProviderManager.fetchLiveEvents()
        }
        return events
            .filter { event ->
                listOfNotNull(
                    event.title,
                    event.eventInfo?.teamA,
                    event.eventInfo?.teamB,
                    event.eventInfo?.eventName,
                    event.eventInfo?.eventType
                ).joinToString(" ").contains(query, ignoreCase = true)
            }
            .map { event ->
                val displayTitle = createDisplayTitle(event)
                val status = getEventStatus(event)
                val fullTitle =
                    if (status.isNotBlank()) "$status $displayTitle" else displayTitle
                val poster = if (event.cat == "Custom" && !event.image.isNullOrBlank()) {
                    event.image
                } else {
                    generateMatchCardUrl(event)
                }
                val loadData = LiveEventLoadData(
                    eventId = event.id,
                    title = displayTitle,
                    poster = poster,
                    slug = event.slug,
                    formats = event.formats ?: emptyList(),
                    eventInfo = event.eventInfo
                )
                newLiveSearchResponse(fullTitle, loadData.toJson(), TvType.Live) {
                    this.posterUrl = poster
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LiveEventLoadData>(url)
        val info = data.eventInfo
        val plot = buildString {
            info?.let { i ->
                i.eventType?.let { append("$it\n") }
                i.eventName?.let { append("$it\n") }
                i.startTime?.let {
                    try {
                        val df = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                        val disp = SimpleDateFormat("MMM dd, yyyy HH:mm 'UTC'", Locale.US)
                        disp.timeZone = TimeZone.getTimeZone("UTC")
                        df.parse(it)?.let { d -> append("${disp.format(d)}\n") }
                    } catch (_: Exception) {
                        append("$it\n")
                    }
                }
            }
            append("\nAvailable Servers: ${data.formats.size}")
        }
        return newLiveStreamLoadResponse(data.title, url, url) {
            this.posterUrl = data.poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LiveEventLoadData>(data)
        val streamResponse = fetchChannelStreams(loadData.slug) ?: return false
        val streamUrls = streamResponse.streamUrls
        if (streamUrls.isNullOrEmpty()) return false

        streamUrls.forEach { stream ->
            try {
                val serverName = stream.title ?: "Server"
                val streamLink = stream.link ?: return@forEach
                val (url, headers) = parseStreamLink(streamLink)
                if (url.isBlank()) return@forEach

                val resolvedUrl = resolveEmbedUrlIfNeeded(url) ?: return@forEach

                if (stream.type == "7") {
                    val drmInfo = stream.api?.split(":")
                    if (drmInfo != null && drmInfo.size == 2) {
                        val kidBase64 = hexToBase64(drmInfo[0])
                        val keyBase64 = hexToBase64(drmInfo[1])
                        callback.invoke(
                            newDrmExtractorLink(
                                this.name, serverName, resolvedUrl,
                                INFER_TYPE, CLEARKEY_UUID
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.key = keyBase64
                                this.kid = kidBase64
                                if (headers.isNotEmpty()) this.headers = headers
                            }
                        )
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                this.name, serverName, resolvedUrl,
                                ExtractorLinkType.DASH
                            ) {
                                this.quality = Qualities.Unknown.value
                                if (headers.isNotEmpty()) this.headers = headers
                            }
                        )
                    }
                } else {
                    val linkType = when {
                        resolvedUrl.contains(".mpd") -> ExtractorLinkType.DASH
                        resolvedUrl.contains(".m3u") -> ExtractorLinkType.M3U8
                        resolvedUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                        else -> INFER_TYPE
                    }
                    val finalHeaders = headers.toMutableMap()
                    if (linkType == ExtractorLinkType.M3U8 &&
                        !finalHeaders.containsKey("User-Agent")
                    ) {
                        finalHeaders["User-Agent"] = MOBILE_UA
                    }
                    callback.invoke(
                        newExtractorLink(
                            this.name, serverName, resolvedUrl, linkType
                        ) {
                            this.quality = Qualities.Unknown.value
                            if (finalHeaders.isNotEmpty()) this.headers = finalHeaders
                        }
                    )
                }
            } catch (_: Exception) {
            }
        }
        return true
    }

    private fun isDirectStreamUrl(url: String): Boolean {
        return url.contains(".m3u8") || url.contains(".mpd") || url.contains(".mp4") ||
            url.contains(".ts") || url.contains(".mkv") || url.contains(".webm")
    }

    private suspend fun resolveEmbedUrlIfNeeded(url: String): String? {
        if (isDirectStreamUrl(url)) return url
        return loadEmbedInWebView(url)
    }

    private suspend fun fetchChannelStreams(slug: String): ChannelStreamResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = LIVETVProviderManager.getBaseUrl() ?: return@withContext null
                val url = "$baseUrl/channels/${slug.lowercase(Locale.ROOT)}.txt"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val body = response.body.string()
                if (body.isBlank()) return@withContext null
                val decrypted = LIVETVCryptoUtils.decryptLIVETV(body.trim())
                    ?: return@withContext null
                parseJson<ChannelStreamResponse>(decrypted)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        if (!link.contains("|")) {
            return Pair(link, emptyMap())
        }
        val parts = link.split("|", limit = 2)
        val url = parts[0]
        val headers = linkedMapOf<String, String>()
        if (parts.size > 1) {
            parts[1].split("&").forEach { headerPair ->
                val keyValue = headerPair.split("=", limit = 2)
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim()
                    val value = keyValue[1].trim()
                    val headerName = when (key.lowercase(Locale.ROOT)) {
                        "cookie" -> "Cookie"
                        "origin" -> "Origin"
                        "user-agent" -> "User-Agent"
                        "referer" -> "Referer"
                        else -> key
                    }
                    headers[headerName] = value
                }
            }
        }
        return Pair(url, headers)
    }

    private fun hexToBase64(hex: String): String {
        val bytes = hex.replace("-", "").chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    // embed-only providers hide the stream behind a player page, so let it run and grab the first media request
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadEmbedInWebView(embedUrl: String): String? {
        val appContext = context ?: return null
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        val webView = WebView(appContext)
                        val settings: WebSettings = webView.settings
                        settings.javaScriptEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.domStorageEnabled = true
                        settings.allowContentAccess = true
                        settings.allowFileAccess = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false

                        var urlCaptured = false

                        fun finish(result: String?) {
                            if (continuation.isActive) {
                                continuation.resume(result)
                            }
                        }

                        val bridge = object : Any() {
                            @JavascriptInterface
                            fun onStreamUrlFound(url: String) {
                                if (!urlCaptured && url.isNotBlank()) {
                                    urlCaptured = true
                                    Handler(Looper.getMainLooper()).post {
                                        try {
                                            webView.destroy()
                                        } catch (_: Exception) {
                                        }
                                        finish(url)
                                    }
                                }
                            }
                        }

                        webView.addJavascriptInterface(bridge, "StreamBridge")
                        webView.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                val requestUrl = request.url.toString()
                                if (isDirectStreamUrl(requestUrl) && !urlCaptured) {
                                    urlCaptured = true
                                    Handler(Looper.getMainLooper()).post {
                                        try {
                                            webView.destroy()
                                        } catch (_: Exception) {
                                        }
                                        finish(requestUrl)
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView, pageUrl: String) {
                                super.onPageFinished(view, pageUrl)
                                if (!urlCaptured) {
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        try {
                                            view.evaluateJavascript(
                                                "(function() {\n" +
                                                    "    if (typeof playbackURL !== 'undefined' && playbackURL) {\n" +
                                                    "        window.StreamBridge.onStreamUrlFound(playbackURL);\n" +
                                                    "    }\n" +
                                                    "})();",
                                                null
                                            )
                                        } catch (_: Exception) {
                                        }
                                    }, 500L)
                                }
                                if (!urlCaptured) {
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        if (!urlCaptured) {
                                            try {
                                                view.destroy()
                                            } catch (_: Exception) {
                                            }
                                            finish(null)
                                        }
                                    }, 3000L)
                                }
                            }
                        }
                        webView.webChromeClient = WebChromeClient()
                        webView.loadUrl(embedUrl)

                        continuation.invokeOnCancellation {
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    webView.destroy()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        }
    }
}
