package com.csksy.streamcorner

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class StreamCorner : MainAPI() {
    override var mainUrl = "https://streamcorner.st"
    override var name = "StreamCorner"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        private data class Feed(
            val id: String,
            val body: String,
            val title: String,
            val keyField: String = "stream_id",
            val branded: String? = null,
            val variant: Boolean = false
        )

        private val FEEDS = listOf(
            Feed("beta", "beta", "Live Sports"),
            Feed("alpha", "alpha", "Soccer"),
            Feed("mlb", "mlb", "MLB"),
            Feed("nfl", "nfl", "NFL"),
            Feed("nba", "nba", "NBA", keyField = "game_id"),
            Feed("001", "001", "Sports TV"),
            Feed("003", "003", "More Sports"),
            Feed("channels", "channels", "24/7 Channels"),
            Feed("extra003", "extra003", "HD Channels"),
            Feed("extra004", "extra004", "Extra Streams"),
            Feed("admin", "admin", "24/7 Shows"),
            Feed("slingtv_channels", "slingtv_channels", "SlingTV", keyField = "channel_id",
                branded = "sling", variant = true),
            Feed("slingtv_sports", "slingtv_sports", "SlingTV Sports",
                branded = "sling", variant = false),
            Feed("peacock_channels", "peacock_channels", "Peacock",
                branded = "peacock", variant = true),
            Feed("peacock_schedule", "peacock_schedule", "Peacock Sports",
                branded = "peacock", variant = false),
            Feed("paramount_schedule", "paramount_schedule", "Paramount+",
                branded = "paramount", variant = false)
        )

        private fun feedById(id: String): Feed? = FEEDS.firstOrNull { it.id == id }

        private const val TAG = "StreamCorner"
    }

    override val mainPage = mainPageOf(*FEEDS.map { it.id to it.title }.toTypedArray())

    private data class LoadData(val f: String, val i: String)

    private fun itemTitle(item: JSONObject, feed: Feed): String {
        val home = item.optString("home_team", "")
        val away = item.optString("away_team", "")
        if (home.isNotBlank() && away.isNotBlank() && home != "Home Team") {
            return "$away vs $home"
        }
        return item.optString("event_name", "")
            .ifBlank { item.optString("channel_name", "") }
            .ifBlank { item.optString("name", "") }
            .ifBlank { item.optString("title", "") }
            .ifBlank { "Stream" }
    }

    private fun itemPoster(item: JSONObject): String? {
        val poster = item.optString("poster", "")
        if (poster.isNotBlank()) return poster
        val images = item.optJSONArray("images") ?: return null
        for (i in 0 until images.length()) {
            val img = images.optJSONObject(i) ?: continue
            val url = img.optString("url", "")
            if (url.isNotBlank()) return url
        }
        return item.optString("image", "").ifBlank { null }
    }

    private fun itemTimeText(item: JSONObject): String {
        val formats = listOf(
            "display_start_time", "start_timestamp", "timestamp",
            "start_time", "game_timestamp", "time_et", "time_utc"
        )
        for (key in formats) {
            when (val v = item.opt(key) ?: continue) {
                is Number -> {
                    val ms = if (v.toLong() < 1_000_000_000_000L) v.toLong() * 1000 else v.toLong()
                    return formatTime(ms)
                }
                is String -> if (v.isNotBlank() && v.any { it.isDigit() }) {
                    parseTimeText(v)?.let { return it }
                }
            }
        }
        return ""
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("EEE, MMM d hh:mm a", Locale.US).format(java.util.Date(ms))

    private fun parseTimeText(v: String): String? {
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm", "MM/dd/yy hh:mm a", "MM/dd/YYYY hh:mm a"
        )
        for (f in formats) {
            try {
                val fmt = SimpleDateFormat(f, Locale.US)
                if (f.startsWith("MM/dd")) fmt.timeZone = TimeZone.getTimeZone("America/New_York")
                val d = fmt.parse(v) ?: continue
                return formatTime(d.time)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun itemPlot(item: JSONObject, feed: Feed): String {
        val parts = mutableListOf<String>()
        item.optString("league", "").ifBlank { item.optString("category", "") }
            .takeIf { it.isNotBlank() }?.let { parts.add(it) }
        item.optString("description", "").takeIf { it.isNotBlank() }?.let { parts.add(it) }
        item.optString("channel_name", "").takeIf { it.isNotBlank() }?.let { parts.add(it) }
        itemTimeText(item).takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.joinToString("\n")
    }

    private suspend fun feedItems(feed: Feed): JSONArray {
        val data = if (feed.branded != null) {
            CornerApi.brandedList(feed.id, feed.branded, feed.variant)
        } else {
            CornerApi.providerList(feed.id, feed.body)
        } ?: return JSONArray()
        return when {
            // sling sports: sports.tabs[].tiles
            feed.id == "slingtv_sports" -> {
                val out = JSONArray()
                val tabs = (data as? JSONObject)?.optJSONObject("sports")?.optJSONArray("tabs")
                if (tabs != null) {
                    for (i in 0 until tabs.length()) {
                        val tiles = tabs.optJSONObject(i)?.optJSONArray("tiles") ?: continue
                        for (j in 0 until tiles.length()) out.put(tiles.get(j))
                    }
                }
                out
            }
            // peacock schedule: one or more rails each holding events
            feed.id == "peacock_schedule" -> {
                val out = JSONArray()
                when (data) {
                    is JSONObject -> data.optJSONArray("events")?.let { e ->
                        for (j in 0 until e.length()) out.put(e.get(j))
                    }
                    is JSONArray -> for (i in 0 until data.length()) {
                        data.optJSONObject(i)?.optJSONArray("events")?.let { e ->
                            for (j in 0 until e.length()) out.put(e.get(j))
                        }
                    }
                }
                out
            }
            // paramount: category objects each holding events
            feed.id == "paramount_schedule" -> {
                val out = JSONArray()
                val cats = CornerApi.flatten(data)
                for (i in 0 until cats.length()) {
                    val events = cats.optJSONObject(i)?.optJSONArray("events") ?: continue
                    for (j in 0 until events.length()) out.put(events.get(j))
                }
                out
            }
            else -> CornerApi.flatten(data)
        }
    }

    private fun toSearchResponse(item: JSONObject, feed: Feed): SearchResponse? {
        val id = item.optString(feed.keyField, "").ifBlank {
            item.optString("stream_id", "")
        }
        if (id.isBlank()) return null
        val title = itemTitle(item, feed)
        val url = LoadData(feed.id, id).toJson()
        return newLiveSearchResponse(title, url, TvType.Live) {
            this.posterUrl = itemPoster(item)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val feed = feedById(request.data)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        return try {
            val items = feedItems(feed)
            val list = (0 until items.length())
                .mapNotNull { idx -> items.optJSONObject(idx)?.let { toSearchResponse(it, feed) } }
            newHomePageResponse(request.name, list, hasNext = false)
        } catch (e: Exception) {
            Log.d(TAG, "feed ${feed.id} failed: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val results = kotlinx.coroutines.coroutineScope {
            FEEDS.filter {
                it.id !in listOf("slingtv_sports", "peacock_schedule", "paramount_schedule")
            }.map { feed ->
                async {
                    try {
                        val items = feedItems(feed)
                        (0 until items.length()).mapNotNull { idx ->
                            items.optJSONObject(idx)?.let { item ->
                                val title = itemTitle(item, feed).lowercase()
                                if (title.contains(q)) toSearchResponse(item, feed) else null
                            }
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        // the same fixture can sit in several feeds, keep the first copy
        val seen = mutableSetOf<String>()
        return results.filter { r -> seen.add(r.url) }
    }

    private suspend fun fetchDetail(feed: Feed, id: String): JSONObject? {
        val data = if (feed.branded != null) {
            CornerApi.brandedList("${feed.id}&id=${java.net.URLEncoder.encode(id, "UTF-8")}",
                feed.branded, feed.variant)
        } else {
            CornerApi.providerList(feed.id, feed.body, id)
        }
        return data as? JSONObject
    }

    override suspend fun load(url: String): LoadResponse {
        val d = parseJson<LoadData>(url)
        val feed = feedById(d.f) ?: throw ErrorLoadingException("unknown feed")
        val detail = fetchDetail(feed, d.i)
        val title = detail?.let { itemTitle(it, feed) } ?: d.i
        val poster = detail?.let { itemPoster(it) }
        val plot = buildString {
            detail?.let { itemPlot(it, feed).takeIf { p -> p.isNotBlank() }?.let { append(it) } }
            append("\nLive streams from StreamCorner")
        }
        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun collectStreamObjects(detail: JSONObject?): List<JSONObject> {
        if (detail == null) return emptyList()
        val out = mutableListOf<JSONObject>()
        for (key in listOf("streams", "vods")) {
            val arr = detail.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(it) }
            }
        }
        // branded channels carry the embed directly on the detail object
        if (out.isEmpty() && (detail.optString("embed_url").isNotBlank() ||
                detail.optString("stream_url").isNotBlank())
        ) {
            out.add(detail)
        }
        return out
    }

    private suspend fun emitDirect(
        streamUrl: String,
        keys: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val source = label.ifBlank { "Stream" }
        return try {
            when {
                streamUrl.contains(".mpd") -> {
                    val kidHex: String
                    val keyHex: String
                    if (keys.contains(":")) {
                        val parts = keys.split(":")
                        kidHex = parts[0]
                        keyHex = parts[1]
                    } else {
                        kidHex = ""
                        keyHex = ""
                    }
                    if (kidHex.length == 32 && keyHex.length == 32) {
                        callback.invoke(
                            newDrmExtractorLink(
                                name, source, streamUrl, INFER_TYPE, CLEARKEY_UUID
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.kid = CornerApi.hexToBase64(kidHex)
                                this.key = CornerApi.hexToBase64(keyHex)
                            }
                        )
                    } else {
                        callback.invoke(
                            newExtractorLink(name, source, streamUrl, INFER_TYPE) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                    true
                }
                streamUrl.contains(".m3u8") -> {
                    M3u8Helper.generateM3u8(
                        source = "$name - $source",
                        streamUrl = streamUrl,
                        referer = mainUrl,
                        headers = mapOf("User-Agent" to CornerApi.userAgent())
                    ).forEach(callback)
                    true
                }
                streamUrl.startsWith("http") -> {
                    callback.invoke(
                        newExtractorLink(name, source, streamUrl, INFER_TYPE) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.d(TAG, "direct link failed for $source: ${e.message}")
            false
        }
    }

    private suspend fun resolveWithWebView(
        embedUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                additionalUrls = listOf(
                    Regex("""\.mpd"""), Regex("""\.flv"""), Regex("""\.mp4""")
                ),
                script = """
                    try{var d=document.getElementById('dontfoid');if(d)d.remove()}catch(e){}
                    try{var b=document.querySelector('button,[class*=play],.vjs-big-play-button,.jw-icon-display,[class*=Play]');if(b)b.click()}catch(e){}
                """.trimIndent(),
                useOkhttp = false,
                timeout = 25_000L
            )
            val resolved = app.get(embedUrl, referer = "$mainUrl/", interceptor = resolver).url
            when {
                resolved.contains(".m3u8") -> {
                    M3u8Helper.generateM3u8(
                        source = "$name - $label",
                        streamUrl = resolved,
                        referer = mainUrl,
                        headers = mapOf("User-Agent" to CornerApi.userAgent())
                    ).forEach(callback)
                    true
                }
                resolved.contains(".mpd") || resolved.contains(".flv") ||
                    resolved.contains(".mp4") -> {
                    callback.invoke(
                        newExtractorLink(name, label, resolved, INFER_TYPE) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.d(TAG, "webview resolve failed for $label: ${e.message}")
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val d = parseJson<LoadData>(data)
        val feed = feedById(d.f) ?: return false
        val detail = fetchDetail(feed, d.i)
        val streams = collectStreamObjects(detail)
        if (streams.isEmpty()) return false

        var emitted = false
        for (stream in streams) {
            val label = stream.optString("source_name", "")
                .ifBlank { stream.optString("name", "") }
                .ifBlank { stream.optString("channel_name", "") }
                .ifBlank { stream.optString("title", "") }
                .ifBlank { "Stream" }
            val embedUrl = stream.optString("embed_url", "")
            val directUrl = stream.optString("stream_url", "")
                .ifBlank { stream.optString("playback_url", "") }

            if (directUrl.isNotBlank() && !directUrl.startsWith("http")) continue

            // the dominant embed form packs the real url in a p= parameter
            val pParam = Regex("[?&]p=([A-Za-z2-7]{40,})").find(embedUrl)?.groupValues?.get(1)
            if (pParam != null) {
                val decoded = CornerCrypto.decryptEmbedParam(pParam)
                val streamUrl = decoded?.optString("stream_url", "").orEmpty()
                if (decoded != null && streamUrl.isNotBlank()) {
                    val keys = decoded.optString("stream_keys", "")
                        .ifBlank { decoded.optString("keys", "") }
                    if (emitDirect(streamUrl, keys, decoded.optString("name", label), callback)) {
                        emitted = true
                        continue
                    }
                }
            }
            if (directUrl.isNotBlank()) {
                if (emitDirect(directUrl, "", label, callback)) {
                    emitted = true
                    continue
                }
            }
            if (embedUrl.isNotBlank()) {
                if (resolveWithWebView(embedUrl, label, callback)) {
                    emitted = true
                }
            }
        }
        return emitted
    }
}
