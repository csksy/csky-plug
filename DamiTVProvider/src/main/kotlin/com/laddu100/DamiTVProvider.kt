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
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import com.raghav.donation.DonationManager

class DamiTVProvider : MainAPI() {

    override var mainUrl = "https://damitv.st"
    override var name = "DamiTV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    // the player host and its token endpoint kept their own domain while the
    // main site rotated through renames, so they must not follow mainUrl
    private val playBase = "https://play.damitv.st"

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

    private var isUrlLoaded = false

    data class FirebaseConfig(
        @JsonProperty("dami") val dami: String? = null,
        @JsonProperty("dami_url") val dami_url: String? = null,
        @JsonProperty("damiUrl") val damiUrl: String? = null,
        @JsonProperty("damitv_url") val damitvUrl: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json").text
            val json = parseJson<FirebaseConfig>(response)
            val url = json.dami ?: json.dami_url ?: json.damiUrl ?: json.damitvUrl
            if (!url.isNullOrBlank()) {
                mainUrl = url.removeSuffix("/")
                isUrlLoaded = true
            }
        } catch (_: Exception) {}
    }

    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

    // the stream cdn rejects playlist requests without a referer from the
    // site family, the player accepts either the main domain or its mirror
    private val playHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "*/*"
        )

    data class DamiTvChannel(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String
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
    // transient cloudflare hiccups damitv is known for
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

    private suspend fun fetchHlsToken(): HlsTokenResponse? {
        repeat(2) {
            try {
                val text = apiGet("$playBase/papi/hls-token", apiHeaders)
                val resp = parseJson<HlsTokenResponse>(text)
                if (resp.tk.isNotBlank()) return resp
            } catch (_: Exception) {}
        }
        return null
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
        loadFirebaseUrl()
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

            // the popular flag moved off matches/all onto its own endpoint,
            // the old field stays as a fallback for older cached payloads
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
        loadFirebaseUrl()
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

        event.tvChannels?.forEach { ch ->
            val path = "/c/${ch.id}.m3u8"
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
        loadFirebaseUrl()

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

    // the site ships embed urls as base64 of the reversed string
    private fun decodeEmbedUrl(embed: String?): String? {
        if (embed.isNullOrBlank()) return null
        if (embed.startsWith("http")) return embed
        if (!embed.startsWith("enc:")) return null
        return try {
            val decoded = String(Base64.getDecoder().decode(embed.removePrefix("enc:")), Charsets.UTF_8)
            val candidate = decoded.reversed()
            if (candidate.startsWith("http")) candidate else null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadFirebaseUrl()
        val streamData = try {
            parseJson<StreamLoadData>(data)
        } catch (_: Exception) {
            return false
        }

        if (streamData.streams.isEmpty()) return false

        var foundAny = false
        var token: HlsTokenResponse? = null

        streamData.streams.forEach { stream ->
            try {
                if (stream.url.startsWith("ppv://")) {
                    val targetId = stream.url.removePrefix("ppv://")
                    val response = fetchExtractUrl(targetId)
                    if (response != null) {
                        val hlsUrl = response.hlsUrl!!
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = stream.name,
                                url = hlsUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = playHeaders
                            }
                        )
                        foundAny = true

                        val embed = decodeEmbedUrl(response.embedUrl)
                        if (embed != null) {
                            try {
                                loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)
                            } catch (_: Exception) {}
                        }
                    }
                } else if (stream.url.startsWith("chan://")) {
                    val path = stream.url.removePrefix("chan://")
                    if (token == null) {
                        token = fetchHlsToken()
                    }
                    val tk = token
                    if (tk != null) {
                        val streamUrl = "$playBase$path?tk=${tk.tk}&e=${tk.e}"
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = stream.name,
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = playHeaders
                            }
                        )
                        foundAny = true
                    }
                }
            } catch (_: Exception) {}
        }

        return foundAny
    }

    // the site's playlist hosts require the referer while their segment cdn
    // rejects it, so the player datasource gets a per-request interceptor
    // that keeps the referer on the manifest hosts and strips it elsewhere
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        val manifestHost = try {
            Regex("""https?://([^/]+)""").find(extractorLink.url)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
        return Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val builder = request.newBuilder()
            if (host == manifestHost || host == "damitv.st" || host.endsWith(".damitv.st") ||
                host == "ondemand.st" || host.endsWith(".ondemand.st")
            ) {
                builder.header("Referer", "$mainUrl/")
                builder.header("User-Agent", DESKTOP_UA)
            } else {
                builder.removeHeader("Referer")
                builder.removeHeader("Origin")
            }
            chain.proceed(builder.build())
        }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }
}
