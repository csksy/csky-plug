package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class TimStreamsProvider : MainAPI() {
    override var mainUrl = "https://timstreams.st"
    override var name = "TimStreams"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    private val fallbackApiUrl = "https://api.timstreams.st/api"
    private val fallbackCdnBase = "https://logic.icelanders.st/"
    private val TAG = "TimStreams"

    private suspend fun apiUrl(): String {
        val domain = FirebaseDomainHelper.getDomain("timstreams_api")
        return (domain ?: fallbackApiUrl).removeSuffix("/") + "/api"
    }

    private suspend fun cdnBase(): String {
        return (FirebaseDomainHelper.getDomain("timstreams_cdn") ?: fallbackCdnBase).ensureSuffix("/")
    }

    private fun String.ensureSuffix(suffix: String): String =
        if (endsWith(suffix)) this else this + suffix

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TimStream(
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("vip") val vip: Boolean? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TimEvent(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("genre") val genre: Int? = null,
        @JsonProperty("time") val time: String? = null,
        @JsonProperty("isevent") val isevent: Boolean? = null,
        @JsonProperty("vip") val vip: Boolean? = null,
        @JsonProperty("featured") val featured: Boolean? = null,
        @JsonProperty("streams") val streams: List<TimStream>? = null,
        @JsonProperty("date") val date: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TimChannel(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("genre") val genre: Int? = null,
        @JsonProperty("vip") val vip: Boolean? = null,
        @JsonProperty("streams") val streams: List<TimStream>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LiveUpcomingResponse(
        @JsonProperty("events") val events: List<TimEvent>? = null,
        @JsonProperty("genres") val genres: Map<String, String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChannelsResponse(
        @JsonProperty("channels") val channels: List<TimChannel>? = null,
        @JsonProperty("genres") val genres: Map<String, String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LoadData(
        val title: String,
        val streams: List<TimStream>,
        val posterUrl: String? = null,
        val isUpcoming: Boolean = false
    )

    override val mainPage = mainPageOf(
        "live-upcoming" to "All"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        try {
            coroutineScope {
                val liveDeferred = async { fetchLiveUpcoming() }
                val channelsDeferred = async { fetchChannels() }

                val liveData = liveDeferred.await()
                if (liveData != null) {
                    val (live, upcoming) = liveData.partition { e ->
                        try {
                            val eventTime = java.time.LocalDateTime.parse(e.time ?: "")
                                .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                            eventTime <= System.currentTimeMillis()
                        } catch (ex: Exception) { true }
                    }
                    if (live.isNotEmpty()) {
                        val items = live.mapNotNull { it.toSearchResponse() }
                        lists.add(HomePageList("Live Now", items, isHorizontalImages = true))
                    }
                    if (upcoming.isNotEmpty()) {
                        val items = upcoming.mapNotNull { it.toUpcomingSearchResponse() }
                        lists.add(HomePageList("Upcoming Events", items, isHorizontalImages = true))
                    }
                }

                val channelsData = channelsDeferred.await()
                if (channelsData != null && channelsData.isNotEmpty()) {
                    val items = channelsData.mapNotNull { it.toSearchResponse() }
                    lists.add(HomePageList("Live TV Channels", items, isHorizontalImages = true))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED: ${e.message}")
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    private suspend fun fetchLiveUpcoming(): List<TimEvent>? {
        return try {
            val res = app.get("${apiUrl()}/live-upcoming", timeout = 30_000L, referer = "$mainUrl/")
            parseJson<LiveUpcomingResponse>(res.text).events
        } catch (e: Exception) {
            Log.e(TAG, "fetchLiveUpcoming FAILED: ${e.message}")
            null
        }
    }

    private suspend fun fetchChannels(): List<TimChannel>? {
        return try {
            val res = app.get("${apiUrl()}/channels", timeout = 30_000L, referer = "$mainUrl/")
            parseJson<ChannelsResponse>(res.text).channels
        } catch (e: Exception) {
            Log.e(TAG, "fetchChannels FAILED: ${e.message}")
            null
        }
    }

    private fun TimEvent.toSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val streams = streams ?: return null
        if (streams.isEmpty()) return null
        val loadData = LoadData(title = title, streams = streams, posterUrl = logo)
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = logo
        }
    }

    private fun TimEvent.toUpcomingSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val streams = streams ?: return null
        if (streams.isEmpty()) return null
        val displayTitle = "$title [Upcoming: ${time ?: date ?: "TBD"}]"
        val loadData = LoadData(title = title, streams = streams, posterUrl = logo, isUpcoming = true)
        return newLiveSearchResponse(displayTitle, loadData.toJson(), TvType.Live) {
            this.posterUrl = logo
        }
    }

    private fun TimChannel.toSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val streams = streams ?: return null
        if (streams.isEmpty()) return null
        val loadData = LoadData(title = title, streams = streams, posterUrl = logo)
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = logo
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResponse>()

        try {
            val eventsRes = app.get("${apiUrl()}/live-upcoming", timeout = 30_000L, referer = "$mainUrl/")
            val eventsParsed = parseJson<LiveUpcomingResponse>(eventsRes.text)
            eventsParsed.events?.forEach { e ->
                if (e.name?.contains(query, ignoreCase = true) == true) {
                    e.toSearchResponse()?.let { results.add(it) }
                }
            }

            val channelsRes = app.get("${apiUrl()}/channels", timeout = 30_000L, referer = "$mainUrl/")
            val channelsParsed = parseJson<ChannelsResponse>(channelsRes.text)
            channelsParsed.channels?.forEach { c ->
                if (c.name?.contains(query, ignoreCase = true) == true) {
                    c.toSearchResponse()?.let { results.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED: ${e.message}")
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val loadData = parseJson<LoadData>(url)
            newLiveStreamLoadResponse(loadData.title, url, this.name) {
                this.posterUrl = loadData.posterUrl
                this.plot = "${loadData.streams.size} stream sources available"
                this.dataUrl = loadData.toJson()
            }
        } catch (e: Exception) {
            Log.e(TAG, "load FAILED: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val loadData = try {
            parseJson<LoadData>(data)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: failed to parse LoadData: ${e.message}")
            return false
        }

        if (loadData.streams.isEmpty()) {
            Log.e(TAG, "loadLinks: no streams in LoadData")
            return false
        }

        var found = false
        for (stream in loadData.streams) {
            val streamName = stream.name
            val streamUrl = stream.url

            try {
                when {
                    streamUrl.contains("icelanders.st") -> {
                        try {
                            val resolver = WebViewResolver(
                                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                                script = """document.querySelector('video,[role="button"],.vjs-big-play-button,button,.play-button')?.click();""",
                                useOkhttp = false,
                                timeout = 30_000L
                            )
                            val resolvedUrl = app.get(streamUrl, referer = "$mainUrl/", interceptor = resolver).url

                            if (resolvedUrl.contains(".m3u8", ignoreCase = true) || resolvedUrl.contains(".mp4", ignoreCase = true)) {
                                val embedHost = try {
                                    val uri = android.net.Uri.parse(streamUrl)
                                    "${uri.scheme}://${uri.host}"
                                } catch (e: Exception) { null }

                                val cookieStr = if (embedHost != null) {
                                    try { android.webkit.CookieManager.getInstance().getCookie(embedHost) ?: "" } catch (e: Exception) { "" }
                                } else { "" }

                                val cdnHost = try {
                                    val uri = android.net.Uri.parse(resolvedUrl)
                                    "${uri.scheme}://${uri.host}"
                                } catch (e: Exception) { null }

                                val cdnCookies = if (cdnHost != null) {
                                    try { android.webkit.CookieManager.getInstance().getCookie(cdnHost) ?: "" } catch (e: Exception) { "" }
                                } else { "" }

                                val allCookies = listOf(cookieStr, cdnCookies).filter { it.isNotBlank() }.joinToString("; ")

                                val headers = mutableMapOf(
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                                    "Referer" to streamUrl
                                )
                                if (allCookies.isNotBlank()) headers["Cookie"] = allCookies

                                val isM3u8 = resolvedUrl.contains(".m3u8", ignoreCase = true)
                                callback.invoke(
                                    newExtractorLink(
                                        source = "$name - $streamName",
                                        name = "$name - $streamName",
                                        url = resolvedUrl,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.headers = headers
                                    }
                                )
                                found = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadLinks: '$streamName' icelanders.st failed: ${e.message}")
                        }
                    }

                    streamUrl.contains("ritzembeds.pages.dev") || streamUrl.contains("vileembeds.pages.dev") -> {
                        try {
                            val resolver = WebViewResolver(
                                interceptUrl = Regex("""(?i)\.m3u8(?:\?|$)"""),
                                additionalUrls = listOf(Regex("""(?i)\.m3u8(?:\?|$)""")),
                                script = """document.querySelector('.vjs-big-play-button,.play-button,button,[role=button]')?.click();""",
                                useOkhttp = false,
                                timeout = 30_000L
                            )
                            val resolvedUrl = app.get(streamUrl, referer = "$mainUrl/", interceptor = resolver).url

                            if (resolvedUrl.contains(".m3u8", ignoreCase = true)) {
                                try {
                                    android.webkit.CookieManager.getInstance().flush()
                                } catch (e: Exception) { }

                                val cdnHost = try {
                                    val uri = android.net.Uri.parse(resolvedUrl)
                                    "${uri.scheme}://${uri.host}"
                                } catch (e: Exception) { null }

                                val cookieStr = if (cdnHost != null) {
                                    try {
                                        android.webkit.CookieManager.getInstance().getCookie(cdnHost) ?: ""
                                    } catch (e: Exception) { "" }
                                } else { "" }

                                val ritzCookies = try {
                                    android.webkit.CookieManager.getInstance().getCookie("https://ritzembeds.pages.dev") ?: ""
                                } catch (e: Exception) { "" }

                                val allCookies = listOf(cookieStr, ritzCookies)
                                    .filter { it.isNotBlank() }
                                    .joinToString("; ")

                                val headers = mutableMapOf(
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                                    "Referer" to streamUrl,
                                    "Origin" to "https://ritzembeds.pages.dev"
                                )
                                if (allCookies.isNotBlank()) {
                                    headers["Cookie"] = allCookies
                                }

                                callback.invoke(
                                    newExtractorLink(
                                        source = "$name - $streamName",
                                        name = "$name - $streamName",
                                        url = resolvedUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.headers = headers
                                    }
                                )
                                found = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadLinks: '$streamName' WebViewResolver failed: ${e.message}")
                        }
                    }

                    streamUrl.contains("luluvdo.com") || streamUrl.contains("luluvid.com") -> {
                        val realUrl = streamUrl.replace("luluvid.com", "luluvdo.com")
                        val loaded = loadExtractor(realUrl, "$mainUrl/", subtitleCallback, callback)
                        if (loaded) found = true
                    }

                    streamUrl.contains("player.vimeo.com") -> {
                        val loaded = loadExtractor(streamUrl, "$mainUrl/", subtitleCallback, callback)
                        if (loaded) found = true
                    }

                    streamUrl.contains("upn.one") -> {
                        try {
                            val resolver = WebViewResolver(
                                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                                script = """document.querySelector('video,[role="button"],.vds-play-button,button')?.click();""",
                                useOkhttp = false,
                                timeout = 30_000L
                            )
                            val resolvedUrl = app.get(streamUrl, referer = "$mainUrl/", interceptor = resolver).url

                            if (resolvedUrl.contains(".m3u8", ignoreCase = true) || resolvedUrl.contains(".mp4", ignoreCase = true)) {
                                val upnCookies = try {
                                    android.webkit.CookieManager.getInstance().getCookie("https://timstreams.upn.one") ?: ""
                                } catch (e: Exception) { "" }

                                val headers = mutableMapOf(
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                                    "Referer" to "https://timstreams.upn.one/"
                                )
                                if (upnCookies.isNotBlank()) {
                                    headers["Cookie"] = upnCookies
                                }

                                val isM3u8 = resolvedUrl.contains(".m3u8", ignoreCase = true)
                                callback.invoke(
                                    newExtractorLink(
                                        source = "$name - $streamName",
                                        name = "$name - $streamName",
                                        url = resolvedUrl,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.headers = headers
                                    }
                                )
                                found = true
                            } else {
                                val loaded = loadExtractor(streamUrl, "$mainUrl/", subtitleCallback, callback)
                                if (loaded) found = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadLinks: '$streamName' upn.one failed: ${e.message}")
                            val loaded = loadExtractor(streamUrl, "$mainUrl/", subtitleCallback, callback)
                            if (loaded) found = true
                        }
                    }

                    streamUrl.contains(".m3u8") -> {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - $streamName",
                                name = "$name - $streamName",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        found = true
                    }

                    streamUrl.contains(".mp4") -> {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - $streamName",
                                name = "$name - $streamName",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        found = true
                    }

                    else -> {
                        val loaded = loadExtractor(streamUrl, "$mainUrl/", subtitleCallback, callback)
                        if (loaded) found = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks: FAILED for stream '$streamName': ${e.message}")
            }
        }

        return found
    }
}
