package com.csksy.streamcorner

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class StreamCornerProvider : MainAPI() {
    override var mainUrl = "https://streamcorner.st"
    override var name = "StreamCorner"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    // embed heavy events can carry a dozen players, the default window is too
    // tight for the fallback webview passes behind them
    override val loadLinksTimeoutMs = 240_000L

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        *(listOf("live" to "Live Now", "upcoming" to "Upcoming") +
            StreamCornerApi.FEEDS.map { (id, label) -> id to label }).toTypedArray()
    )

    data class EventLoadData(
        val feed: String,
        val id: String,
        val title: String,
        val posterUrl: String?,
        val category: String?,
        val league: String?,
        val homeTeam: String?,
        val awayTeam: String?,
        val description: String?,
        val startTime: Long?,
        val timeEt: String?
    )

    data class StreamEntry(
        val name: String,
        val kind: String,
        val url: String,
        val keys: String = ""
    )

    data class StreamLoadData(
        val title: String,
        val entries: List<StreamEntry>
    )

    private fun formatTime(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0) return "soon"
        return try {
            val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getDefault()
            sdf.format(java.util.Date(timestamp * 1000))
        } catch (e: Exception) {
            "soon"
        }
    }

    private fun ScEvent.toSearch(feed: String): SearchResponse? {
        val eventId = id ?: return null
        val title = name?.takeIf { it.isNotBlank() } ?: return null
        val start = startTime ?: timestamp
        val now = System.currentTimeMillis() / 1000
        val upcoming = start != null && start > now
        val display = if (upcoming) "$title [Starts: ${formatTime(start)}]" else title
        val poster = poster?.takeIf { it.isNotBlank() }
            ?: homeTeamLogo?.takeIf { it.isNotBlank() }
            ?: awayTeamLogo?.takeIf { it.isNotBlank() }
            ?: categoryLogo?.takeIf { it.isNotBlank() }
        val data = EventLoadData(
            feed = feed,
            id = eventId,
            title = title,
            posterUrl = poster,
            category = category,
            league = league,
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            description = description,
            startTime = start,
            timeEt = timeEt
        ).toJson()
        return newLiveSearchResponse(display, data, TvType.Live) {
            this.posterUrl = poster
        }
    }

    private fun <T> pageOf(
        rowName: String,
        events: List<T>,
        page: Int,
        mapEvent: (T) -> SearchResponse?
    ): HomePageResponse? {
        if (events.isEmpty()) return null
        val perPage = 30
        val from = (page - 1) * perPage
        if (from >= events.size) return null
        val slice = events.subList(from, minOf(from + perPage, events.size))
        val items = slice.mapNotNull(mapEvent)
        if (items.isEmpty()) return null
        return newHomePageResponse(rowName, items, hasNext = from + perPage < events.size)
    }

    private suspend fun allEvents(): List<Pair<String, ScEvent>> = coroutineScope {
        StreamCornerApi.FEEDS.keys.map { feed ->
            async {
                try {
                    StreamCornerApi.list(feed).map { feed to it }
                } catch (e: Exception) {
                    Log.d("StreamCorner", "$feed skipped: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val now = System.currentTimeMillis() / 1000
        if (request.data == "live" || request.data == "upcoming") {
            val events = allEvents()
            val live = events.filter { (_, ev) ->
                val start = ev.startTime ?: ev.timestamp
                start == null || start <= now
            }.sortedByDescending { (_, ev) -> ev.startTime ?: ev.timestamp ?: Long.MAX_VALUE }
            val upcoming = events.filter { (_, ev) ->
                val start = ev.startTime ?: ev.timestamp
                start != null && start > now
            }.sortedBy { (_, ev) -> ev.startTime ?: ev.timestamp ?: Long.MAX_VALUE }
            return if (request.data == "live") {
                pageOf(request.name, live, page) { (feed, ev) -> ev.toSearch(feed) }
            } else {
                pageOf(request.name, upcoming, page) { (feed, ev) -> ev.toSearch(feed) }
            }
        }

        val feed = request.data
        val events = try {
            StreamCornerApi.list(feed)
        } catch (e: Exception) {
            Log.d("StreamCorner", "${request.name} skipped: ${e.message}")
            return null
        }
        val sorted = events.sortedByDescending { it.startTime ?: it.timestamp ?: Long.MAX_VALUE }
        return pageOf(request.name, sorted, page) { it.toSearch(feed) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase()
        return allEvents().mapNotNull { (feed, ev) ->
            val hit = ev.name?.lowercase()?.contains(q) == true ||
                ev.category?.lowercase()?.contains(q) == true ||
                ev.league?.lowercase()?.contains(q) == true ||
                ev.homeTeam?.lowercase()?.contains(q) == true ||
                ev.awayTeam?.lowercase()?.contains(q) == true
            if (hit) ev.toSearch(feed) else null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val event = try {
            parseJson<EventLoadData>(url)
        } catch (e: Exception) {
            return null
        }

        val detail = try {
            StreamCornerApi.detail(event.feed, event.id)
        } catch (e: Exception) {
            throw ErrorLoadingException("StreamCorner could not be reached: ${e.message}")
        }

        val now = System.currentTimeMillis() / 1000
        val entries = mutableListOf<StreamEntry>()
        detail?.streams?.forEach { s ->
            val label = s.sourceName?.takeIf { it.isNotBlank() } ?: "Stream"
            val streamUrl = s.streamUrl.orEmpty()
            val embedUrl = s.embedUrl.orEmpty()
            val keys = s.streamKeys.orEmpty()
            when {
                streamUrl.contains(".mpd", true) && keys.isNotBlank() ->
                    entries.add(StreamEntry(label, "mpd", streamUrl, keys))
                streamUrl.contains(".mpd", true) ->
                    entries.add(StreamEntry(label, "dash", streamUrl))
                streamUrl.contains(".m3u8", true) ->
                    entries.add(StreamEntry(label, "m3u8", streamUrl))
                else -> {
                    // most entries only carry an embed page, a few fill both
                    // fields with different pages so both are worth a try
                    if (embedUrl.isNotBlank()) entries.add(StreamEntry(label, "embed", embedUrl))
                    if (streamUrl.isNotBlank() && streamUrl != embedUrl && streamUrl.startsWith("http")) {
                        entries.add(StreamEntry(label, "embed", streamUrl))
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            val start = event.startTime
            val placeholder = if (start != null && start > now) {
                "Live soon [Starts: ${formatTime(start)}]"
            } else {
                "No source is up yet, check back later"
            }
            entries.add(StreamEntry(placeholder, "none", ""))
        }

        val plot = buildString {
            event.league?.takeIf { it.isNotBlank() }?.let { append("League: $it\n") }
            event.homeTeam?.takeIf { it.isNotBlank() }?.let { append("Home: $it\n") }
            event.awayTeam?.takeIf { it.isNotBlank() }?.let { append("Away: $it\n") }
            event.category?.takeIf { it.isNotBlank() }?.let { append("Category: $it\n") }
            event.description?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
        }.trim()

        return newLiveStreamLoadResponse(event.title, url, this.name) {
            this.posterUrl = event.posterUrl
            this.plot = plot
            this.dataUrl = StreamLoadData(event.title, entries).toJson()
        }
    }

    private fun hexToBase64Url(hex: String): String? {
        val clean = hex.replace("-", "").trim()
        if (clean.length != 32 || !clean.matches(Regex("^[0-9a-fA-F]+$"))) return null
        val bytes = ByteArray(16) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // the dash feeds hand out a clearkey pair next to the manifest, the player
    // takes both as base64url and decrypts without a license server
    private suspend fun emitDrm(entry: StreamEntry, callback: (ExtractorLink) -> Unit): Boolean {
        val parts = entry.keys.split(":")
        if (parts.size != 2) return false
        val kid = hexToBase64Url(parts[0]) ?: return false
        val key = hexToBase64Url(parts[1]) ?: return false
        callback.invoke(
            newDrmExtractorLink(this.name, entry.name, entry.url, INFER_TYPE, CLEARKEY_UUID) {
                this.key = key
                this.kid = kid
                this.headers = mapOf("User-Agent" to userAgent)
            }
        )
        return true
    }

    private fun originOf(url: String): String? = try {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) {
        null
    }

    private suspend fun emitResolved(
        entry: StreamEntry,
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val type = when {
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
        val headers = buildMap {
            put("User-Agent", userAgent)
            originOf(entry.url)?.let { put("Referer", "$it/") }
            try {
                android.webkit.CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            } catch (e: Exception) {
            }
        }
        callback.invoke(
            newExtractorLink(this.name, entry.name, url, type) {
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
        return true
    }

    private fun isPlaylistUrl(url: String): Boolean =
        url.contains(".m3u8", true) || url.contains(".mpd", true) ||
            url.contains("playlist", true) || url.contains("manifest", true) ||
            url.contains("master.txt", true)

    private fun isRawStreamUrl(url: String): Boolean =
        url.endsWith(".ts") || url.contains(".ts?", true) || url.contains(".flv", true)

    // players start muted since a webview without a user gesture is only
    // allowed to autoplay silent video, the click wakes the ones that wait
    // for interaction
    private val NUDGE = """
        (function() {
            try {
                var v = document.querySelector('video');
                if (v) { v.muted = true; var p = v.play(); if (p && p.catch) p.catch(function() {}); }
                var c = document.querySelector('#player, .player, .jwplayer, .oplayer, .video-container, iframe');
                if (c) c.click();
            } catch (e) {}
        })();
    """

    private suspend fun resolveEmbed(embedUrl: String): String? {
        val ctx = StreamCornerApi.context() ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val captured = AtomicBoolean(false)
                val rawCandidate = AtomicReference<String>(null)
                var wv: WebView? = null

                fun finish(result: String?) {
                    if (captured.compareAndSet(false, true)) {
                        try {
                            wv?.destroy()
                        } catch (e: Exception) {
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                }

                cont.invokeOnCancellation {
                    if (captured.compareAndSet(false, true)) {
                        Handler(Looper.getMainLooper()).post {
                            try {
                                wv?.destroy()
                            } catch (e: Exception) {
                            }
                        }
                    }
                }

                try {
                    wv = WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString = userAgent
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                val url = request.url.toString()
                                if (!captured.get() && url.startsWith("http") && url != embedUrl) {
                                    if (isPlaylistUrl(url)) {
                                        Handler(Looper.getMainLooper()).post { finish(url) }
                                    } else if (rawCandidate.get() == null &&
                                        isRawStreamUrl(url) && !url.contains(".js")
                                    ) {
                                        rawCandidate.set(url)
                                    }
                                }
                                return null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript(NUDGE, null)
                                Handler(Looper.getMainLooper()).postDelayed({
                                    view?.evaluateJavascript(NUDGE, null)
                                }, 2500)
                            }
                        }
                        loadUrl(embedUrl, mapOf("Referer" to "https://streamcorner.st/"))
                    }
                    // a playlist answers fast, a bare mpegts stream only shows
                    // itself as one long download so the wait covers both
                    Handler(Looper.getMainLooper()).postDelayed({ finish(rawCandidate.get()) }, 22_000L)
                } catch (e: Exception) {
                    finish(null)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamData = try {
            parseJson<StreamLoadData>(data)
        } catch (e: Exception) {
            return false
        }

        var found = false
        val embeds = mutableListOf<StreamEntry>()
        for (entry in streamData.entries) {
            when (entry.kind) {
                "mpd" -> if (emitDrm(entry, callback)) found = true
                "dash" -> {
                    callback.invoke(
                        newExtractorLink(this.name, entry.name, entry.url, ExtractorLinkType.DASH) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to userAgent)
                        }
                    )
                    found = true
                }
                "m3u8" -> {
                    callback.invoke(
                        newExtractorLink(this.name, entry.name, entry.url, ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to "$mainUrl/"
                            )
                        }
                    )
                    found = true
                }
                "embed" -> embeds.add(entry)
            }
        }

        if (embeds.isNotEmpty()) {
            coroutineScope {
                embeds.chunked(3).forEach { batch ->
                    try {
                        val ok = batch.map { entry ->
                            async(Dispatchers.IO) {
                                val resolved = try {
                                    resolveEmbed(entry.url)
                                } catch (e: Exception) {
                                    null
                                }
                                if (resolved.isNullOrBlank()) {
                                    Log.d("StreamCorner", "${entry.name} had no playable link")
                                    false
                                } else {
                                    emitResolved(entry, resolved, callback)
                                }
                            }
                        }.awaitAll().any { it }
                        if (ok) found = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d("StreamCorner", "embed batch failed: ${e.message}")
                    }
                }
            }
        }

        return found
    }
}
