package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import com.raghav.donation.DonationManager

class DamiTVProvider : MainAPI() {

    override var mainUrl = "https://damitv.st"
    override var name = "DamiTV"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    private val customDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == "1.1.1.1") {
                return listOf(InetAddress.getByName("1.1.1.1"))
            }
            dnsCache[hostname]?.let { return it }
            val resolved = resolveDnsDoH(hostname)
            if (resolved.isNotEmpty()) {
                dnsCache[hostname] = resolved
                return resolved
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val dnsClient by lazy {
        app.baseClient.newBuilder()
            .dns(customDns)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun resolveDnsDoH(hostname: String): List<InetAddress> {
        try {
            val request = Request.Builder()
                .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                .header("Accept", "application/dns-json")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            dohClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseText = response.body?.string() ?: ""
                    val ipRegex = """\"data\"\s*:\s*\"([0-9.]+)\"""".toRegex()
                    val ips = ipRegex.findAll(responseText).map { it.groupValues[1] }.toList()
                    if (ips.isNotEmpty()) {
                        return ips.map { InetAddress.getByName(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    private suspend fun apiGet(url: String, headers: Map<String, String>): String {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        dnsClient.newCall(reqBuilder.build()).execute().use { response ->
            return response.body?.string() ?: ""
        }
    }

    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to CHROME_UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

    // the segment cdn serves a static noise image to anything that does not
    // look like the site's own browser player, so the player requests must
    // mirror the browser exactly: no referer, a null origin and fetch metadata
    private val streamHeaders: Map<String, String> = mapOf(
        "User-Agent" to CHROME_UA,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "null",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty"
    )

    data class DamiTvChannel(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    data class DamiTeam(
        @JsonProperty("name") val name: String?,
        @JsonProperty("badge") val badge: String?
    )

    data class DamiTeams(
        @JsonProperty("home") val home: DamiTeam?,
        @JsonProperty("away") val away: DamiTeam?
    )

    data class DamiMatch(
        @JsonProperty("id") val id: String,
        @JsonProperty("league") val league: String?,
        @JsonProperty("title") val title: String,
        @JsonProperty("category") val category: String?,
        @JsonProperty("date") val date: Long?,
        @JsonProperty("popular") val popular: Boolean?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("ppvPoster") val ppvPoster: String? = null,
        @JsonProperty("status") val status: String?,
        @JsonProperty("viewers") val viewers: Int?,
        @JsonProperty("teams") val teams: DamiTeams? = null,
        @JsonProperty("tvChannels") val tvChannels: List<DamiTvChannel>? = null,
        @JsonProperty("ppvId") val ppvId: String? = null,
        @JsonProperty("always_live") val alwaysLive: Boolean? = null
    )

    data class TvListItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("country") val country: String? = null
    )

    data class TvListResponse(
        @JsonProperty("channels") val channels: List<TvListItem> = emptyList()
    )

    data class MsrcSource(
        @JsonProperty("url") val url: String,
        @JsonProperty("label") val label: String?
    )

    data class ExtractUrlResponse(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("hlsUrl") val hlsUrl: String? = null,
        @JsonProperty("embedUrl") val embedUrl: String? = null,
        @JsonProperty("error") val error: String? = null
    )

    data class HlsTokenResponse(
        @JsonProperty("tk") val tk: String,
        @JsonProperty("e") val e: Long
    )

    data class EventLoadData(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val category: String?,
        val status: String? = null,
        val date: Long? = null,
        val league: String? = null,
        val teams: DamiTeams? = null,
        val ppvId: String? = null,
        val tvChannels: List<DamiTvChannel>? = null
    )

    data class ChannelLoadData(
        val channelId: String? = null,
        val channelName: String? = null,
        val channelPoster: String? = null,
        val channelCountry: String? = null
    )

    data class StreamLoadData(
        val title: String,
        val streams: List<StreamInfo>
    )

    data class StreamInfo(
        val name: String,
        val url: String
    )

    // the site's manifest endpoints answer only with a site referer while the
    // segment cdn behind them rejects any referer, and the app player sends
    // one header set to every host. a loopback server splits the two apart:
    // the player asks the proxy for the manifest, the proxy adds the referer,
    // and segments go straight from the player to the cdn with clean
    // browser-style headers
    private object ManifestProxy {
        @Volatile private var server: ServerSocket? = null
        @Volatile private var fetchClient: OkHttpClient? = null
        @Volatile private var tk: String = ""
        @Volatile private var tkExpires: Long = 0

        fun ensure(client: OkHttpClient): Boolean {
            fetchClient = client
            if (server != null && server?.isClosed == false) return true
            return try {
                val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
                server = socket
                thread(isDaemon = true, name = "damitv-manifest-proxy") { acceptLoop(socket) }
                true
            } catch (_: Exception) {
                server = null
                false
            }
        }

        fun isRunning(): Boolean = server != null && server?.isClosed == false

        fun port(): Int = server?.localPort ?: -1

        fun proxyUrl(upstream: String): String {
            val encoded = android.util.Base64.encodeToString(
                upstream.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return "http://127.0.0.1:${port()}/x/$encoded"
        }

        fun channelUrl(channelId: String): String {
            return "http://127.0.0.1:${port()}/c/$channelId"
        }

        private fun acceptLoop(socket: ServerSocket) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { serve(client) }
            }
        }

        private fun serve(client: Socket) {
            try {
                client.soTimeout = 25000
                client.use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                    val requestLine = reader.readLine() ?: return
                    val parts = requestLine.split(" ")
                    if (parts.size < 2 || parts[0] != "GET") {
                        respond(socket, 405, "method not allowed".toByteArray())
                        return
                    }
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val route = parts[1].substringBefore('?')
                    val upstream = upstreamFor(route) ?: run {
                        respond(socket, 404, "not found".toByteArray())
                        return
                    }
                    val allowRetry = route.startsWith("/c/")
                    var body = fetch(upstream)
                    if (body == null && allowRetry) {
                        refreshToken()
                        body = fetch("$PLAY_BASE/c/${route.removePrefix("/c/")}.m3u8?tk=$tk&e=$tkExpires")
                    }
                    if (body == null) {
                        respond(socket, 502, "upstream failed".toByteArray())
                        return
                    }
                    respond(socket, 200, body)
                }
            } catch (_: Exception) {}
        }

        private fun upstreamFor(route: String): String? {
            return when {
                route.startsWith("/c/") -> {
                    val channelId = route.removePrefix("/c/").substringBefore('/')
                    if (!channelId.matches(Regex("[A-Za-z0-9_-]{3,64}"))) null
                    else "$PLAY_BASE/c/$channelId.m3u8?tk=${token()}&e=$tkExpires"
                }
                route.startsWith("/x/") -> {
                    val encoded = route.removePrefix("/x/").substringBefore('/')
                    val upstream = try {
                        String(
                            android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE),
                            Charsets.UTF_8
                        )
                    } catch (_: Exception) {
                        null
                    }
                    if (upstream != null && isAllowedHost(upstream)) upstream else null
                }
                else -> null
            }
        }

        // only the site family may be fetched through the proxy, the loopback
        // port must never become an open relay for other apps on the device
        private fun isAllowedHost(upstream: String): Boolean {
            val host = try {
                URI(upstream).host ?: ""
            } catch (_: Exception) {
                ""
            }
            return host == "damitv.st" || host.endsWith(".damitv.st")
        }

        private fun token(): String {
            val now = System.currentTimeMillis() / 1000
            if (tk.isBlank() || tkExpires - now < 300) refreshToken()
            return tk
        }

        private fun refreshToken() {
            try {
                val request = Request.Builder()
                    .url("$PLAY_BASE/papi/hls-token")
                    .header("User-Agent", CHROME_UA)
                    .header("Accept", "*/*")
                    .header("Referer", "$SITE_REFERER")
                    .build()
                fetchClient?.newCall(request)?.execute()?.use { response ->
                    val text = response.body?.string() ?: ""
                    val parsed = parseJson<HlsTokenResponse>(text)
                    if (parsed.tk.isNotBlank()) {
                        tk = parsed.tk
                        tkExpires = parsed.e
                    }
                }
            } catch (_: Exception) {}
        }

        private fun fetch(upstream: String): ByteArray? {
            return try {
                val request = Request.Builder()
                    .url(upstream)
                    .header("User-Agent", CHROME_UA)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", SITE_REFERER)
                    .build()
                fetchClient?.newCall(request)?.execute()?.use { response ->
                    if (response.isSuccessful) response.body?.bytes() else null
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun respond(socket: Socket, code: Int, body: ByteArray) {
            val reason = when (code) {
                200 -> "OK"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                else -> "Bad Gateway"
            }
            val header = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().use { out ->
                out.write(header.toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            }
        }
    }

    private fun formatMatchDate(timestamp: Long?): String {
        if (timestamp == null) return "soon"
        return try {
            val date = java.util.Date(timestamp)
            val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getDefault()
            sdf.format(date)
        } catch (_: Exception) {
            "soon"
        }
    }

    // a fresh signed url right before playback, one retry absorbs the
    // transient failures damitv is known for
    private suspend fun fetchExtractUrl(id: String): ExtractUrlResponse? {
        repeat(2) {
            try {
                val text = apiGet("$mainUrl/papi/extract-url/${java.net.URLEncoder.encode(id, "UTF-8")}", apiHeaders)
                val resp = parseJson<ExtractUrlResponse>(text)
                if (resp.success && !resp.hlsUrl.isNullOrBlank()) return resp
            } catch (_: Exception) {}
        }
        return null
    }

    private fun isSiteUrl(url: String): Boolean {
        val host = try {
            URI(url).host ?: ""
        } catch (_: Exception) {
            ""
        }
        return host == "damitv.st" || host.endsWith(".damitv.st")
    }

    private fun absoluteImageUrl(poster: String?): String {
        if (poster.isNullOrBlank()) return ""
        return if (poster.startsWith("http")) poster else "$mainUrl$poster"
    }

    private fun matchPoster(match: DamiMatch): String {
        return absoluteImageUrl(
            match.poster ?: match.ppvPoster ?: match.teams?.home?.badge ?: match.teams?.away?.badge
        )
    }

    private fun matchToSearchResponse(match: DamiMatch): SearchResponse {
        val posterUrl = matchPoster(match)
        val loadData = EventLoadData(
            title = match.title,
            url = match.id,
            posterUrl = posterUrl,
            category = match.category,
            status = match.status,
            date = match.date,
            league = match.league,
            teams = match.teams,
            ppvId = match.ppvId,
            tvChannels = match.tvChannels
        )
        return newLiveSearchResponse(match.title, loadData.toJson(), TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    private fun matchToUpcomingSearchResponse(match: DamiMatch): SearchResponse {
        val dateStr = formatMatchDate(match.date)
        val title = "${match.title} [Upcoming - Starts: $dateStr]"
        val posterUrl = matchPoster(match)
        val loadData = EventLoadData(
            title = match.title,
            url = match.id,
            posterUrl = posterUrl,
            category = match.category,
            status = match.status,
            date = match.date,
            league = match.league,
            teams = match.teams,
            ppvId = match.ppvId,
            tvChannels = match.tvChannels
        )
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    private fun channelToSearchResponse(item: TvListItem): SearchResponse {
        val loadData = ChannelLoadData(
            channelId = item.id,
            channelName = item.name,
            channelPoster = absoluteImageUrl(item.image),
            channelCountry = item.country
        )
        return newLiveSearchResponse(item.name, loadData.toJson(), TvType.Live) {
            this.posterUrl = loadData.channelPoster
        }
    }

    private fun isChannelCategory(category: String?): Boolean {
        val cat = category?.lowercase() ?: return false
        return cat == "24/7-streams" || cat == "live-tv" || cat == "channels" || cat.contains("stream")
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        DonationManager.checkAndShow()
        val lists = mutableListOf<HomePageList>()

        try {
            val tvText = apiGet("$mainUrl/papi/tv-list", apiHeaders)
            val tvList = parseJson<TvListResponse>(tvText)
            if (tvList.channels.isNotEmpty()) {
                val items = tvList.channels.map { channelToSearchResponse(it) }
                lists.add(HomePageList("Live Sports Channels", items, isHorizontalImages = true))
            }
        } catch (_: Exception) {}

        try {
            val allText = apiGet("$mainUrl/papi/matches/all", apiHeaders)
            val allMatches = parseJson<List<DamiMatch>>(allText)

            val popular = try {
                val popularText = apiGet("$mainUrl/papi/matches/all/popular", apiHeaders)
                parseJson<List<DamiMatch>>(popularText).ifEmpty {
                    allMatches.filter { it.popular == true }
                }
            } catch (_: Exception) {
                allMatches.filter { it.popular == true }
            }
            if (popular.isNotEmpty()) {
                lists.add(HomePageList("Popular Now", popular.map { matchToSearchResponse(it) }, isHorizontalImages = true))
            }

            val liveMatches = allMatches.filter { it.status?.lowercase() == "live" && !isChannelCategory(it.category) }
            if (liveMatches.isNotEmpty()) {
                lists.add(HomePageList("Live Sports Events", liveMatches.map { matchToSearchResponse(it) }, isHorizontalImages = true))
            }

            val upcomingMatches = allMatches.filter { it.status?.lowercase() == "upcoming" && !isChannelCategory(it.category) }
            if (upcomingMatches.isNotEmpty()) {
                lists.add(HomePageList("Upcoming Matches (Live soon)", upcomingMatches.map { matchToUpcomingSearchResponse(it) }, isHorizontalImages = true))
            }

            val channels = allMatches.filter { isChannelCategory(it.category) }
            if (channels.isNotEmpty()) {
                lists.add(HomePageList("24/7 Channels & Live TV", channels.map { matchToSearchResponse(it) }, isHorizontalImages = true))
            }
        } catch (_: Exception) {}

        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val text = apiGet("$mainUrl/papi/matches/all", apiHeaders)
            val allMatches = parseJson<List<DamiMatch>>(text)
            results.addAll(allMatches.filter { match ->
                match.title.contains(query, ignoreCase = true) ||
                (match.league?.contains(query, ignoreCase = true) ?: false) ||
                (match.category?.contains(query, ignoreCase = true) ?: false) ||
                (match.teams?.home?.name?.contains(query, ignoreCase = true) ?: false) ||
                (match.teams?.away?.name?.contains(query, ignoreCase = true) ?: false)
            }.map { match ->
                if (match.status == "upcoming") {
                    matchToUpcomingSearchResponse(match)
                } else {
                    matchToSearchResponse(match)
                }
            })
        } catch (_: Exception) {}

        try {
            val tvText = apiGet("$mainUrl/papi/tv-list", apiHeaders)
            val tvList = parseJson<TvListResponse>(tvText)
            results.addAll(tvList.channels.filter { ch ->
                ch.name.contains(query, ignoreCase = true) ||
                (ch.country?.contains(query, ignoreCase = true) ?: false)
            }.map { channelToSearchResponse(it) })
        } catch (_: Exception) {}
        return results
    }

    // the msrc answer is a player page path whose url query param carries the
    // real playlist path, for example /c/8afdcdd622.m3u8
    private fun playlistPathFromPlayerUrl(playerUrl: String): String? {
        return try {
            val query = playerUrl.substringAfter('?', "")
            for (pair in query.split('&')) {
                val kv = pair.split('=', limit = 2)
                if (kv.size == 2 && kv[0] == "url") {
                    val path = java.net.URLDecoder.decode(kv[1], "UTF-8")
                    if (path.startsWith("/")) return path
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun buildMatchStreams(event: EventLoadData): List<StreamInfo> {
        val streams = mutableListOf<StreamInfo>()
        val seenPaths = mutableSetOf<String>()
        val isUpcoming = event.status?.lowercase() == "upcoming"
        val dateStr = formatMatchDate(event.date)

        if (!event.ppvId.isNullOrBlank()) {
            val mainName = if (isUpcoming) "Main Stream (Live soon: $dateStr)" else "Main Stream"
            streams.add(StreamInfo(name = mainName, url = "ppv://${event.ppvId}"))
        }

        try {
            val stamp = System.currentTimeMillis()
            val text = apiGet("$mainUrl/papi/msrc-for?id=${java.net.URLEncoder.encode(event.url, "UTF-8")}&t=$stamp", apiHeaders)
            val sources = parseJson<List<MsrcSource>>(text)
            sources.forEach { src ->
                val path = playlistPathFromPlayerUrl(src.url) ?: return@forEach
                if (seenPaths.add(path)) {
                    streams.add(StreamInfo(name = src.label ?: "Server ${seenPaths.size}", url = "chan://$path"))
                }
            }
        } catch (_: Exception) {}

        // channel entries without an id have no playable path on the site
        event.tvChannels?.forEach { ch ->
            val id = ch.id
            if (id.isNullOrBlank() || ch.name.isNullOrBlank()) return@forEach
            val path = "/c/$id.m3u8"
            if (seenPaths.add(path)) {
                val chName = if (isUpcoming) "${ch.name} (Upcoming)" else ch.name
                streams.add(StreamInfo(name = chName, url = "chan://$path"))
            }
        }

        if (streams.isEmpty()) {
            val mainName = if (isUpcoming) "Main Stream (Live soon: $dateStr)" else "Main Stream"
            streams.add(StreamInfo(name = mainName, url = "ppv://${event.url}"))
        }
        return streams
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val channelData = parseJson<ChannelLoadData>(url)
            if (!channelData.channelId.isNullOrBlank()) {
                val title = channelData.channelName ?: channelData.channelId
                val streams = listOf(StreamInfo(name = "Live", url = "chan:///c/${channelData.channelId}.m3u8"))
                val streamData = StreamLoadData(title, streams)
                val plot = buildString {
                    append("Live sports channel")
                    channelData.channelCountry?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
                }
                return newLiveStreamLoadResponse(title, url, streamData.toJson()) {
                    this.posterUrl = channelData.channelPoster
                    this.plot = plot
                }
            }
        } catch (_: Exception) {}

        val eventData = parseJson<EventLoadData>(url)
        val title = eventData.title
        val isUpcoming = eventData.status?.lowercase() == "upcoming"
        val dateStr = formatMatchDate(eventData.date)

        val streams = buildMatchStreams(eventData)
        val streamData = StreamLoadData(title, streams)

        val plot = buildString {
            eventData.league?.takeIf { it.isNotBlank() }?.let { append("League: $it\n") }
            eventData.teams?.let { t ->
                t.home?.name?.takeIf { it.isNotBlank() }?.let { append("Home: $it\n") }
                t.away?.name?.takeIf { it.isNotBlank() }?.let { append("Away: $it\n") }
            }
            eventData.status?.takeIf { it.isNotBlank() }?.let {
                append("Status: ${it.replaceFirstChar { c -> c.uppercase() }}\n")
            }
            if (eventData.date != null) append("Starts: $dateStr")
        }.trim()

        return newLiveStreamLoadResponse(title, url, streamData.toJson()) {
            this.posterUrl = eventData.posterUrl
            this.plot = plot
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
        } catch (_: Exception) {
            return false
        }

        if (streamData.streams.isEmpty()) return false

        val proxyReady = ManifestProxy.ensure(dnsClient)
        var token: HlsTokenResponse? = null
        var foundAny = false

        streamData.streams.forEach { stream ->
            try {
                val streamUrl = when {
                    stream.url.startsWith("ppv://") -> {
                        val targetId = stream.url.removePrefix("ppv://")
                        val response = fetchExtractUrl(targetId)
                        val hlsUrl = response?.hlsUrl
                        if (hlsUrl.isNullOrBlank()) return@forEach
                        if (proxyReady && isSiteUrl(hlsUrl)) {
                            ManifestProxy.proxyUrl(hlsUrl)
                        } else {
                            hlsUrl
                        }
                    }
                    stream.url.startsWith("chan://") -> {
                        val channelId = stream.url.removePrefix("chan://")
                            .removePrefix("/c/")
                            .substringBefore('/')
                            .removeSuffix(".m3u8")
                        if (proxyReady) {
                            ManifestProxy.channelUrl(channelId)
                        } else {
                            if (token == null) token = fetchHlsToken()
                            val tk = token ?: return@forEach
                            "$PLAY_BASE/c/$channelId.m3u8?tk=${tk.tk}&e=${tk.e}"
                        }
                    }
                    else -> return@forEach
                }
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = stream.name,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = streamHeaders
                    }
                )
                foundAny = true
            } catch (_: Exception) {}
        }

        return foundAny
    }

    private suspend fun fetchHlsToken(): HlsTokenResponse? {
        repeat(2) {
            try {
                val text = apiGet("$PLAY_BASE/papi/hls-token", apiHeaders)
                val resp = parseJson<HlsTokenResponse>(text)
                if (resp.tk.isNotBlank()) return resp
            } catch (_: Exception) {}
        }
        return null
    }

    // fallback for the rare case the loopback server could not start: the
    // interceptor keeps the site referer on manifest hosts and strips it from
    // the segment cdn, at the cost of forcing the okhttp player stack
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        if (ManifestProxy.isRunning()) return null
        return Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val builder = request.newBuilder()
            builder.header("User-Agent", CHROME_UA)
            builder.header("Accept", "*/*")
            builder.header("Accept-Language", "en-US,en;q=0.9")
            builder.removeHeader("Referer")
            builder.removeHeader("Origin")
            builder.removeHeader("Sec-Fetch-Site")
            builder.removeHeader("Sec-Fetch-Mode")
            builder.removeHeader("Sec-Fetch-Dest")
            if (host == "damitv.st" || host.endsWith(".damitv.st") ||
                host == "ondemand.st" || host.endsWith(".ondemand.st")
            ) {
                builder.header("Referer", SITE_REFERER)
            } else {
                builder.header("Origin", "null")
                builder.header("Sec-Fetch-Site", "cross-site")
                builder.header("Sec-Fetch-Mode", "cors")
                builder.header("Sec-Fetch-Dest", "empty")
            }
            chain.proceed(builder.build())
        }
    }

    companion object {
        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36"

        // the player host and its token endpoint kept their own domain while
        // the main site rotated through renames, so they must not follow mainUrl
        private const val PLAY_BASE = "https://play.damitv.st"

        private const val SITE_REFERER = "https://damitv.st/"
    }
}
