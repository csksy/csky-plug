package com.netnaija

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest

// wefeed platform (same as moviebox, different deployment).
// any api response carries an x-user jwt used as bearer auth; until one shows
// up a timestamp+md5 client token is sent instead. the play api also rejects
// requests without X-Source: webNetnaijaSite, and mp4 urls want the site referer.
// subject/search rejects anonymous tokens, the site only gets results through
// the server rendered search page - so the plugin reads that page too.
class NetNaija : MainAPI() {
    override var mainUrl = "https://netnaija.film"
    override var name = "NetNaija"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val apiUrl = "https://h5-api.aoneroom.com"
    private val bff = "$apiUrl/wefeed-h5api-bff"

    private val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "X-Request-Lang" to "en",
        "X-Client-Info" to """{"timezone":"Asia/Kolkata"}"""
    )

    private val playHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "User-Agent" to ua
    )

    // browse channels used by the site's movies / tv-series / animated-series pages
    private val moviesChannel = 1
    private val seriesChannel = 2
    private val animeChannel = 1006

    // ranking menu ids from the site's ranking-list page
    private val trendingAnimeRanking = "62133389738001440"
    private val top100AnimeRanking = "1513079728666723416"

    private val mapper: ObjectMapper = jacksonObjectMapper()

    private var jwtToken: String? = null

    /** Generate X-Client-Token: {timestamp},{md5(reversed_timestamp)}. */
    private fun generateXClientToken(): String {
        val ts = System.currentTimeMillis() / 1000
        val reversed = ts.toString().reversed()
        val md5 = MessageDigest.getInstance("MD5").digest(reversed.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$ts,$md5"
    }

    /** Extract JWT from x-user response header (JSON: {"token":"eyJ..."}). */
    private fun extractTokenFromResponse(response: com.lagradost.nicehttp.NiceResponse): String? {
        try {
            val xUser = response.headers?.get("x-user") ?: return null
            if (xUser.isBlank()) return null
            val token = mapper.readTree(xUser)["token"]?.asText()
            if (!token.isNullOrBlank()) {
                jwtToken = token
                return token
            }
        } catch (e: Exception) {
        }
        return null
    }

    /** Ensure we have a JWT token; fetch one if needed. */
    private suspend fun ensureToken(): String {
        jwtToken?.let { return it }
        return try {
            val headers = baseHeaders.toMutableMap()
            headers["X-Client-Token"] = generateXClientToken()
            val response = app.get("$bff/home", headers = headers)
            extractTokenFromResponse(response) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** Build auth headers with BOTH Cookie and Bearer (play needs Cookie, lists need Bearer). */
    private suspend fun authHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val token = ensureToken()
        val headers = baseHeaders.toMutableMap()
        if (token.isNotEmpty()) {
            headers["Cookie"] = "token=$token"
            headers["Authorization"] = "Bearer $token"
        } else {
            headers["X-Client-Token"] = generateXClientToken()
        }
        headers.putAll(extra)
        return headers
    }

    /**
     * The site homepage rows come from one /home call that returns every
     * curated section at once. Rows are requested in parallel by the app so
     * the response is cached behind a mutex and shared across them.
     */
    private val homeMutex = Mutex()
    private val homeCacheTtl = 10 * 60_000L

    @Volatile
    private var homeSectionsCache: Map<String, List<NetNaijaSubject>> = emptyMap()

    @Volatile
    private var homeCacheTime = 0L

    private suspend fun homeSections(): Map<String, List<NetNaijaSubject>> {
        if (homeSectionsCache.isNotEmpty() && System.currentTimeMillis() - homeCacheTime < homeCacheTtl) {
            return homeSectionsCache
        }
        return homeMutex.withLock {
            if (homeSectionsCache.isNotEmpty() && System.currentTimeMillis() - homeCacheTime < homeCacheTtl) {
                return@withLock homeSectionsCache
            }
            val sections = try {
                val response = app.get("$bff/home", headers = authHeaders())
                extractTokenFromResponse(response)
                parseJson<NetNaijaHomeResponse>(response.text).data?.operatingList.orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            val byTitle = LinkedHashMap<String, List<NetNaijaSubject>>()
            sections.forEach { section ->
                val subjects = section.subjects.orEmpty()
                    .filter { !it.title.isNullOrBlank() && !it.detailPath.isNullOrBlank() }
                if (subjects.isNotEmpty()) {
                    byTitle[cleanSectionTitle(section.title ?: return@forEach)] = subjects
                }
            }
            homeSectionsCache = byTitle
            homeCacheTime = System.currentTimeMillis()
            byTitle
        }
    }

    /** Strip emoji and decoration the site mixes into section titles. */
    private fun cleanSectionTitle(title: String): String =
        title.replace(Regex("[^\\p{L}\\p{N} &+\\[\\]().'-]"), "").trim()

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "home:Popular Series" to "Popular Series",
        "home:Popular Movie" to "Popular Movies",
        "home:Nollywood Movie" to "Nollywood Movies",
        "home:Adult Animation" to "Adult Animation",
        "home:Epic Fantasy" to "Epic Fantasy",
        "home:Sitcom" to "Sitcom",
        "home:Teen Romance" to "Teen Romance",
        "home:Superhero Series" to "Superhero Series",
        "home:BL Story" to "BL Story",
        "home:Bet+" to "Bet+",
        "home:Action&Thriller" to "Action & Thriller",
        "home:Gangster" to "Gangster",
        "home:Teen Fantasy" to "Teen Fantasy",
        "home:K-Drama" to "K-Drama",
        "home:C-Drama" to "C-Drama",
        "home:Anime[English Dubbed]" to "Anime (English Dubbed)",
        "home:Action Movies" to "Action Movies",
        "home:Horror Movies" to "Horror Movies",
        "home:Must-watch Black Shows" to "Must-Watch Black Shows",
        "home:Romance" to "Romance Movies",
        "filter:$moviesChannel:Latest" to "Latest Movies",
        "filter:$seriesChannel:Latest" to "Latest Series",
        "filter:$animeChannel:Hottest" to "Anime",
        "filter:$animeChannel:Latest" to "Latest Anime",
        "rank:$trendingAnimeRanking" to "Trending Anime",
        "rank:$top100AnimeRanking" to "Top 100 Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("netnaija") ?: mainUrl
        val key = request.data
        return try {
            when {
                key == "trending" -> {
                    val response = app.get("$bff/subject/trending?page=$page&perPage=24", headers = authHeaders())
                    extractTokenFromResponse(response)
                    val data = parseJson<NetNaijaTrendingResponse>(response.text).data
                    val items = data?.subjectList.orEmpty().mapNotNull { it.toSearchResponse() }
                    newHomePageResponse(request.name, items, hasNext = data?.pager?.hasMore == true)
                }

                key.startsWith("filter:") -> {
                    val parts = key.split(":")
                    val channelId = parts.getOrNull(1)?.toIntOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
                    val sort = parts.getOrNull(2) ?: "Hottest"
                    val body = """{"page":$page,"perPage":24,"channelId":$channelId,"sort":"$sort"}"""
                    val response = app.post(
                        "$bff/subject/filter",
                        headers = authHeaders(mapOf("Content-Type" to "application/json")),
                        requestBody = body.toRequestBody("application/json".toMediaType())
                    )
                    extractTokenFromResponse(response)
                    val data = parseJson<NetNaijaListResponse>(response.text).data
                    val items = data?.items.orEmpty().mapNotNull { it.toSearchResponse() }
                    newHomePageResponse(request.name, items, hasNext = data?.pager?.hasMore == true)
                }

                key.startsWith("rank:") -> {
                    val listId = key.removePrefix("rank:")
                    val response = app.get("$bff/ranking-list/content?id=$listId&page=$page&perPage=24", headers = authHeaders())
                    extractTokenFromResponse(response)
                    val data = parseJson<NetNaijaRankingResponse>(response.text).data
                    val items = data?.subjectList.orEmpty().mapNotNull { it.toSearchResponse() }
                    newHomePageResponse(request.name, items, hasNext = data?.pager?.hasMore == true)
                }

                key.startsWith("home:") -> {
                    val title = cleanSectionTitle(key.removePrefix("home:"))
                    val items = homeSections()[title].orEmpty().mapNotNull { it.toSearchResponse() }
                    newHomePageResponse(request.name, items, hasNext = false)
                }

                else -> newHomePageResponse(request.name, emptyList(), hasNext = false)
            }
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("netnaija") ?: mainUrl
        if (query.isBlank()) return emptyList()
        return try {
            val html = app.get(
                "$mainUrl/search-result?keyword=${URLEncoder.encode(query, "UTF-8")}",
                headers = mapOf("User-Agent" to ua)
            ).text
            parseSearchPage(html)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private val nuxtDataRegex = Regex(
        """<script[^>]*id="__NUXT_DATA__"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun parseSearchPage(html: String): List<SearchResponse> {
        val match = nuxtDataRegex.find(html) ?: return emptyList()
        val root = try {
            mapper.readTree(match.groupValues[1])
        } catch (e: Exception) {
            return emptyList()
        }
        if (root !is ArrayNode) return emptyList()
        for (i in 0 until root.size()) {
            val node = root.get(i)
            if (node is ObjectNode && node.has("items") && node.has("pager") && node.get("items").isNumber) {
                val resolved = resolveNuxtNode(root, node.get("items").asInt(), 0) as? List<*> ?: continue
                return resolved.mapNotNull { entry ->
                    try {
                        mapper.convertValue(entry, NetNaijaSubject::class.java).toSearchResponse()
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        return emptyList()
    }

    /**
     * Nuxt flattens the rendered page state into a single array where every
     * object and array holds indexes into that array instead of values. Each
     * index resolves one level, primitives are the leaves.
     */
    private fun resolveNuxtNode(arr: ArrayNode, idx: Int, depth: Int): Any? {
        if (idx < 0 || idx >= arr.size() || depth > 8) return null
        return when (val node = arr.get(idx)) {
            is ObjectNode -> {
                val out = LinkedHashMap<String, Any?>()
                val fields = node.fields()
                while (fields.hasNext()) {
                    val field = fields.next()
                    out[field.key] = resolveNuxtValue(arr, field.value, depth)
                }
                out
            }

            is ArrayNode -> {
                val out = ArrayList<Any?>(node.size())
                for (i in 0 until node.size()) {
                    out.add(resolveNuxtValue(arr, node.get(i), depth))
                }
                out
            }

            else -> plainNuxtValue(node)
        }
    }

    private fun resolveNuxtValue(arr: ArrayNode, value: JsonNode, depth: Int): Any? =
        if (value.isNumber) resolveNuxtNode(arr, value.asInt(), depth + 1) else plainNuxtValue(value)

    private fun plainNuxtValue(node: JsonNode): Any? = when {
        node.isTextual -> node.asText()
        node.isBoolean -> node.asBoolean()
        node.isInt || node.isLong -> node.asLong()
        node.isNumber -> node.asDouble()
        else -> null
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("netnaija") ?: mainUrl
        val detailPath = url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() }
            ?: return null

        return try {
            val response = app.get("$bff/detail?detailPath=$detailPath", headers = authHeaders())
            extractTokenFromResponse(response)
            val data = parseJson<NetNaijaDetailResponse>(response.text).data ?: return null
            val subject = data.subject ?: return null
            val title = subject.title ?: return null

            val poster = subject.cover?.url
            val plot = subject.description?.takeIf { it.isNotBlank() }
            val genres = subject.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val year = subject.releaseDate?.substringBefore("-")?.toIntOrNull()
            val score = subject.imdbRatingValue?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Score.from10(it) }.getOrNull() }
            val runtime = subject.duration?.takeIf { it > 0 }?.let { it / 60 }
            val cast = data.stars.orEmpty().mapNotNull { staff ->
                staff.name?.let { ActorData(Actor(it, staff.avatarUrl)) }
            }.take(15)
            val trailer = subject.trailer?.videoAddress?.url?.takeIf { it.isNotBlank() }
            val recommendations = fetchRecommendations(subject.subjectId, detailPath)

            val tvType = when (subject.subjectType) {
                1 -> TvType.Movie
                2 -> TvType.TvSeries
                else -> TvType.Movie
            }

            val seasons = data.resource?.seasons ?: emptyList()
            val dubs = subject.dubs.orEmpty().ifEmpty {
                listOf(
                    NetNaijaDub(
                        subjectId = subject.subjectId ?: "",
                        detailPath = detailPath,
                        lanName = "Original Audio",
                        lanCode = "en",
                        type = 0,
                        original = true
                    )
                )
            }

            if (tvType == TvType.Movie || seasons.isEmpty()) {
                // Movie - store all dub subjectIds so loadLinks can fetch each audio
                val movieData = NetNaijaEpisodeData(dubs = dubs, season = 0, episode = 0).toJson()
                return newMovieLoadResponse(title, url, tvType, movieData) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    this.score = score
                    this.duration = runtime
                    this.actors = cast
                    this.recommendations = recommendations
                    if (trailer != null) addTrailer(trailer)
                }
            } else {
                val episodes = mutableListOf<Episode>()
                seasons.forEach { season ->
                    val seasonNum = season.se ?: return@forEach
                    val maxEp = season.maxEp ?: 0
                    for (ep in 1..maxEp) {
                        val epData = NetNaijaEpisodeData(dubs = dubs, season = seasonNum, episode = ep).toJson()
                        episodes.add(newEpisode(epData) {
                            this.season = seasonNum
                            this.episode = ep
                            this.name = "Episode $ep"
                        })
                    }
                }
                return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    this.score = score
                    this.duration = runtime
                    this.actors = cast
                    this.recommendations = recommendations
                    if (trailer != null) addTrailer(trailer)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchRecommendations(subjectId: String?, detailPath: String): List<SearchResponse> {
        if (subjectId.isNullOrBlank()) return emptyList()
        return try {
            val response = app.get(
                "$bff/subject/detail-rec?subjectId=$subjectId&detailPath=$detailPath",
                headers = authHeaders()
            )
            val items = parseJson<NetNaijaListResponse>(response.text).data?.items.orEmpty()
            items.filter { it.detailPath != detailPath }.mapNotNull { it.toSearchResponse() }.take(12)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            parseJson<NetNaijaEpisodeData>(data)
        } catch (e: Exception) {
            return false
        }

        val dubs = epData.dubs
        var found = false

        // soft subtitles only hang off the original audio stream, remember it
        // and pull them once at the end
        var captionStream: Triple<String, String, String>? = null

        // Each dub (audio language) is a separate source, labeled with language name.
        // This follows the same pattern as MovieBox (phisher98).
        // CloudStream's audioTracks field uses SingleSampleMediaSource which only
        // works with single-file audio URLs (like YouTube), NOT DASH manifests.
        // So we must use separate sources for each audio language.
        dubs.forEach { dub ->
            val dubSubjectId = dub.subjectId ?: return@forEach
            val dubDetailPath = dub.detailPath ?: return@forEach
            val label = sourceLabel(dub)
            if (label == null) return@forEach

            val playReferer = "$mainUrl/videoPlayPage/$dubDetailPath"
            val headers = authHeaders(mapOf(
                "X-Source" to "webNetnaijaSite",
                "Referer" to playReferer
            ))

            val playUrl = "$bff/subject/play?subjectId=$dubSubjectId" +
                "&se=${epData.season}&ep=${epData.episode}&detailPath=$dubDetailPath"

            val playData = try {
                val resp = app.get(playUrl, headers = headers)
                extractTokenFromResponse(resp)
                parseJson<NetNaijaPlayResponse>(resp.text).data
            } catch (e: Exception) {
                return@forEach
            } ?: return@forEach

            // MP4 streams
            val mp4Streams = playData.streams ?: emptyList()
            mp4Streams.forEach { stream ->
                val url = stream.url ?: return@forEach
                val resolution = stream.resolutions ?: return@forEach
                if (stream.vipLocked == true) return@forEach

                val qualityInt = resolution.toIntOrNull()
                val qualityLabel = when (qualityInt) {
                    360 -> Qualities.P360.value
                    480 -> Qualities.P480.value
                    720 -> Qualities.P720.value
                    1080 -> Qualities.P1080.value
                    else -> Qualities.Unknown.value
                }
                val sizeBytes = stream.size?.toLongOrNull() ?: 0
                val sizeLabel = if (sizeBytes > 0) " (${sizeBytes / (1024 * 1024)}MB)" else ""

                callback.invoke(
                    newExtractorLink(
                        "NetNaija $label",
                        "$label ${resolution}p${sizeLabel}",
                        url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = qualityLabel
                        this.headers = playHeaders
                    }
                )
                found = true
            }

            if ((dub.original == true || captionStream == null) && mp4Streams.isNotEmpty()) {
                mp4Streams.first().id?.let { streamId ->
                    captionStream = Triple(streamId, dubSubjectId, dubDetailPath)
                }
            }

            // DASH stream
            playData.dash?.forEach { dashStream ->
                val url = dashStream.url ?: return@forEach
                callback.invoke(
                    newExtractorLink(
                        "NetNaija $label",
                        "$label DASH (Adaptive)",
                        url,
                        type = ExtractorLinkType.DASH
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = playHeaders
                    }
                )
                found = true
            }

            // HLS stream
            playData.hls?.forEach { hlsStream ->
                val url = hlsStream.url ?: return@forEach
                val resolution = hlsStream.resolutions ?: "0"
                try {
                    M3u8Helper.generateM3u8(
                        "NetNaija $label HLS ${resolution}p",
                        url,
                        "$mainUrl/",
                        headers = playHeaders
                    ).forEach(callback)
                    found = true
                } catch (e: Exception) {
                    callback.invoke(
                        newExtractorLink(
                            "NetNaija $label",
                            "$label HLS ${resolution}p",
                            url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = playHeaders
                        }
                    )
                    found = true
                }
            }
        }

        captionStream?.let { (streamId, subjectId, detailPath) ->
            fetchSubtitles(streamId, subjectId, detailPath, subtitleCallback)
        }

        return found
    }

    /** "Original Audio" -> Original, "English dub" -> English Dub, "Arabic sub" -> Arabic Hardsub. */
    private fun sourceLabel(dub: NetNaijaDub): String? {
        val lanName = dub.lanName ?: return null
        if (dub.original == true) return "Original"
        val base = lanName.substringBefore(" dub").substringBefore(" sub").trim()
        if (base.isEmpty()) return null
        val pretty = when (base.lowercase()) {
            "ptbr" -> "PT-BR"
            "esla" -> "ES-LA"
            else -> base.replaceFirstChar { it.uppercase() }
        }
        return if (dub.type == 1) "$pretty Hardsub" else "$pretty Dub"
    }

    /** Fetch and emit all available subtitles. */
    private suspend fun fetchSubtitles(
        streamId: String,
        subjectId: String,
        detailPath: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val captionUrl = "$bff/subject/caption" +
                "?format=MP4&id=$streamId&subjectId=$subjectId&detailPath=$detailPath"
            val response = app.get(captionUrl, headers = authHeaders(mapOf("X-Source" to "webNetnaijaSite")))
            val data = parseJson<NetNaijaCaptionResponse>(response.text).data
            data?.captions?.forEach { caption ->
                val url = caption.url ?: return@forEach
                val lang = caption.lanName ?: caption.lan ?: "Unknown"
                subtitleCallback.invoke(newSubtitleFile(lang, url))
            }
        } catch (e: Exception) {
        }
    }

    private fun NetNaijaSubject.toSearchResponse(): SearchResponse? {
        val title = this.title ?: return null
        val path = detailPath ?: return null
        val poster = cover?.url
        val isMovie = subjectType == 1
        return if (isMovie) {
            newMovieSearchResponse(title, "$mainUrl/movieDetail/$path", TvType.Movie) {
                this.posterUrl = poster
                this.year = releaseDate?.substringBefore("-")?.toIntOrNull()
            }
        } else {
            newTvSeriesSearchResponse(title, "$mainUrl/movieDetail/$path", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = releaseDate?.substringBefore("-")?.toIntOrNull()
            }
        }
    }
}

data class NetNaijaDub(
    @JsonProperty("subjectId") val subjectId: String? = null,
    @JsonProperty("detailPath") val detailPath: String? = null,
    @JsonProperty("lanName") val lanName: String? = null,
    @JsonProperty("lanCode") val lanCode: String? = null,
    @JsonProperty("type") val type: Int? = null,
    @JsonProperty("original") val original: Boolean? = null
)

data class NetNaijaEpisodeData(
    val dubs: List<NetNaijaDub>,
    val season: Int,
    val episode: Int
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaCover(
    @JsonProperty("url") val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaSubject(
    @JsonProperty("subjectId") val subjectId: String? = null,
    @JsonProperty("subjectType") val subjectType: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("cover") val cover: NetNaijaCover? = null,
    @JsonProperty("countryName") val countryName: String? = null,
    @JsonProperty("dubs") val dubs: List<NetNaijaDub>? = null,
    @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null,
    @JsonProperty("trailer") val trailer: NetNaijaTrailer? = null,
    @JsonProperty("detailPath") val detailPath: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaTrailer(
    @JsonProperty("videoAddress") val videoAddress: NetNaijaTrailerVideo? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaTrailerVideo(
    @JsonProperty("url") val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaStaff(
    @JsonProperty("staffId") val staffId: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("avatarUrl") val avatarUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaPager(
    @JsonProperty("hasMore") val hasMore: Boolean? = null,
    @JsonProperty("page") val page: String? = null,
    @JsonProperty("perPage") val perPage: Int? = null,
    @JsonProperty("totalCount") val totalCount: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaTrendingData(
    @JsonProperty("subjectList") val subjectList: List<NetNaijaSubject>? = null,
    @JsonProperty("pager") val pager: NetNaijaPager? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaTrendingResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaTrendingData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaHomeSection(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("subjects") val subjects: List<NetNaijaSubject>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaHomeData(
    @JsonProperty("operatingList") val operatingList: List<NetNaijaHomeSection>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaHomeResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaHomeData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaListData(
    @JsonProperty("items") val items: List<NetNaijaSubject>? = null,
    @JsonProperty("pager") val pager: NetNaijaPager? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaListResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaListData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaRankingData(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("subjectList") val subjectList: List<NetNaijaSubject>? = null,
    @JsonProperty("pager") val pager: NetNaijaPager? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaRankingResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaRankingData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaResolution(
    @JsonProperty("resolution") val resolution: Int? = null,
    @JsonProperty("epNum") val epNum: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaSeason(
    @JsonProperty("se") val se: Int? = null,
    @JsonProperty("maxEp") val maxEp: Int? = null,
    @JsonProperty("resolutions") val resolutions: List<NetNaijaResolution>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaResource(
    @JsonProperty("seasons") val seasons: List<NetNaijaSeason>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaDetailData(
    @JsonProperty("subject") val subject: NetNaijaSubject? = null,
    @JsonProperty("resource") val resource: NetNaijaResource? = null,
    @JsonProperty("stars") val stars: List<NetNaijaStaff>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaDetailResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaDetailData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaStream(
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("resolutions") val resolutions: String? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("codecName") val codecName: String? = null,
    @JsonProperty("vipLocked") val vipLocked: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaPlayData(
    @JsonProperty("streams") val streams: List<NetNaijaStream>? = null,
    @JsonProperty("hls") val hls: List<NetNaijaStream>? = null,
    @JsonProperty("dash") val dash: List<NetNaijaStream>? = null,
    @JsonProperty("limited") val limited: Boolean? = null,
    @JsonProperty("hasResource") val hasResource: Boolean? = null,
    @JsonProperty("vipLocked") val vipLocked: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaPlayResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaPlayData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaCaption(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("lan") val lan: String? = null,
    @JsonProperty("lanName") val lanName: String? = null,
    @JsonProperty("url") val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaCaptionData(
    @JsonProperty("captions") val captions: List<NetNaijaCaption>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaCaptionResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaCaptionData? = null
)
