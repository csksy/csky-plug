package com.laddu100.animotvslash

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
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class AnimoTvSlashProvider : MainAPI() {
    override var mainUrl = "https://animotvslash.org"
    override var name = "AnimoTvSlash"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val fallbackApiBase = "https://animotvslash.ru"
    private val anilistGraphql = "https://graphql.anilist.co"
    private val aniZipApi = "https://api.ani.zip"
    private val TAG = "AnimoTvSlash"

    private suspend fun apiBase(): String {
        val domain = FirebaseDomainHelper.getDomain("animotvslash_api")
        return (domain ?: fallbackApiBase).removeSuffix("/")
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamInfo(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("fansub") val fansub: String? = null,
        @JsonProperty("audio") val audio: String? = null,
        @JsonProperty("kwikEmbed") val kwikEmbed: String? = null,
        @JsonProperty("kwikDownload") val kwikDownload: String? = null,
        @JsonProperty("downloadSize") val downloadSize: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WatchResponse(
        @JsonProperty("streams") val streams: List<StreamInfo>? = null,
        @JsonProperty("animeSession") val animeSession: String? = null,
        @JsonProperty("episodeSession") val episodeSession: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BeepQuality(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("referer") val referer: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BeepResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("server") val server: String? = null,
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("qualities") val qualities: List<BeepQuality>? = null,
        @JsonProperty("embeds") val embeds: List<BeepEmbed>? = null,
        @JsonProperty("subtitleUrl") val subtitleUrl: String? = null,
        @JsonProperty("subtitleFormat") val subtitleFormat: String? = null,
        @JsonProperty("subtitles") val subtitles: List<SubtitleTrack>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BeepEmbed(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubtitleTrack(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("default") val default: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class M3u8ProxyResponse(
        @JsonProperty("m3u8") val m3u8: String? = null,
        @JsonProperty("referer") val referer: String? = null,
        @JsonProperty("proxyUrl") val proxyUrl: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipMapping(
        @JsonProperty("themoviedb_id") val themoviedbId: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("mal_id") val malId: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipEpisode(
        @JsonProperty("title") val title: Map<String, String>? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("airDate") val airDate: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipResponse(
        @JsonProperty("mappings") val mappings: AniZipMapping? = null,
        @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null,
        @JsonProperty("episodeCount") val episodeCount: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LoadData(
        val anilistId: Int,
        val title: String,
        val posterUrl: String? = null,
        val isMovie: Boolean = false,
        val episodeCount: Int = 0,
        val dubAvailable: Boolean = false
    )

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending Now",
        "POPULAR" to "Popular",
        "LATEST" to "Latest Released",
        "UPCOMING" to "Upcoming",
        "TOP_RATED" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sort = when (request.data) {
            "TRENDING" -> "TRENDING_DESC"
            "POPULAR" -> "POPULARITY_DESC"
            "LATEST" -> "ID_DESC"
            "UPCOMING" -> "START_DATE_DESC"
            "TOP_RATED" -> "SCORE_DESC"
            else -> "TRENDING_DESC"
        }

        val query = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: [MediaSort]) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    media(type: ANIME, sort: ${'$'}sort, isAdult: false) {
                        id
                        title { english romaji }
                        coverImage { extraLarge large }
                        format
                        episodes
                        status
                        seasonYear
                        averageScore
                    }
                }
            }
        """.trimIndent()

        val items = try {
            val responseText = anilistQuery(query, mapOf("page" to page, "perPage" to 30, "sort" to listOf(sort)))
            val response = parseJson<AniListResponse>(responseText)
            val mediaList = response.data?.Page?.media ?: emptyList()
            mediaList.mapNotNull { media ->
                val id = media.id ?: return@mapNotNull null
                val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
                val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
                val loadData = LoadData(
                    anilistId = id,
                    title = title,
                    posterUrl = poster,
                    isMovie = media.format == "MOVIE",
                    episodeCount = media.episodes ?: 1
                )
                newAnimeSearchResponse(title, loadData.toJson(), if (media.format == "MOVIE") TvType.AnimeMovie else TvType.Anime) {
                    this.posterUrl = poster
                    addDubStatus(dubExist = true, subExist = true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
            emptyList()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val gqlQuery = """
            query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    media(type: ANIME, search: ${'$'}search, sort: SEARCH_MATCH, isAdult: false) {
                        id
                        title { english romaji }
                        coverImage { extraLarge large }
                        format
                        episodes
                    }
                }
            }
        """.trimIndent()

        return try {
            val responseText = anilistQuery(gqlQuery, mapOf("search" to query, "page" to 1, "perPage" to 30))
            val response = parseJson<AniListResponse>(responseText)
            val mediaList = response.data?.Page?.media ?: emptyList()
            mediaList.mapNotNull { media ->
                val id = media.id ?: return@mapNotNull null
                val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
                val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
                val loadData = LoadData(
                    anilistId = id,
                    title = title,
                    posterUrl = poster,
                    isMovie = media.format == "MOVIE",
                    episodeCount = media.episodes ?: 1
                )
                newAnimeSearchResponse(title, loadData.toJson(), if (media.format == "MOVIE") TvType.AnimeMovie else TvType.Anime) {
                    this.posterUrl = poster
                    addDubStatus(dubExist = true, subExist = true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = try {
            parseJson<LoadData>(url)
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            return null
        }

        val query = """
            query (${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    id
                    title { english romaji native }
                    description(asHtml: false)
                    coverImage { extraLarge large }
                    bannerImage
                    format
                    episodes
                    status
                    seasonYear
                    averageScore
                    genres
                    nextAiringEpisode { episode }
                    duration
                }
            }
        """.trimIndent()

        return try {
            val responseText = anilistQuery(query, mapOf("id" to loadData.anilistId))
            val response = parseJson<AniListResponse>(responseText)
            val media = response.data?.Media ?: return null

            val title = media.title?.english ?: media.title?.romaji ?: loadData.title
            val poster = media.coverImage?.extraLarge ?: media.coverImage?.large ?: loadData.posterUrl
            val plot = media.description?.replace(Regex("<[^>]*>"), "")
            val year = media.seasonYear
            val genres = media.genres ?: emptyList()

            val aniZipData = fetchAniZip(loadData.anilistId)
            val episodeCount = if (media.status == "FINISHED") {
                media.episodes ?: aniZipData?.episodeCount ?: 1
            } else {
                media.nextAiringEpisode?.episode?.let { it - 1 } ?: media.episodes ?: aniZipData?.episodeCount ?: 1
            }
            val dubAvailable = checkDubAvailable(loadData.anilistId, episodeCount)

            if (loadData.isMovie || media.format == "MOVIE") {
                val movieLoadData = LoadData(
                    anilistId = loadData.anilistId,
                    title = title,
                    posterUrl = poster,
                    isMovie = true,
                    episodeCount = 1,
                    dubAvailable = dubAvailable
                )
                newMovieLoadResponse(title, url, TvType.AnimeMovie, movieLoadData.toJson()) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = media.bannerImage
                    this.plot = plot
                    this.year = year
                    this.tags = genres
                }
            } else {
                val episodes = mutableListOf<Episode>()
                for (epNum in 1..episodeCount) {
                    val epData = EpisodeData(
                        anilistId = loadData.anilistId,
                        episode = epNum,
                        title = title,
                        posterUrl = poster,
                        dubAvailable = dubAvailable
                    )
                    val epTitle = aniZipData?.episodes?.get(epNum.toString())?.title?.get("en")
                        ?: aniZipData?.episodes?.get(epNum.toString())?.title?.get("ja")
                        ?: "Episode $epNum"
                    val epOverview = aniZipData?.episodes?.get(epNum.toString())?.overview
                    val epThumb = aniZipData?.episodes?.get(epNum.toString())?.image

                    episodes.add(newEpisode(epData.toJson()) {
                        this.name = epTitle
                        this.episode = epNum
                        this.season = 1
                        this.posterUrl = epThumb ?: poster
                        this.description = epOverview
                    })
                }

                val seriesLoadData = LoadData(
                    anilistId = loadData.anilistId,
                    title = title,
                    posterUrl = poster,
                    isMovie = false,
                    episodeCount = episodeCount,
                    dubAvailable = dubAvailable
                )

                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = media.bannerImage
                    this.plot = plot
                    this.year = year
                    this.tags = genres
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isMovie = data.contains("\"isMovie\":true")
        val anilistId = try {
            if (isMovie) {
                parseJson<LoadData>(data).anilistId
            } else {
                parseJson<EpisodeData>(data).anilistId
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: ${e.message}")
            return false
        }

        val episode = if (isMovie) 1 else try { parseJson<EpisodeData>(data).episode } catch (_: Exception) { 1 }
        val base = apiBase()
        var found = false

        for (lang in listOf("sub", "dub")) {
            try {
                val watchUrl = "$base/api/stream/watch?anilistId=$anilistId&episode=$episode&lang=$lang"
                val watchRes = app.get(watchUrl, referer = "$base/")
                val watch = parseJson<WatchResponse>(watchRes.text)
                val streams = watch.streams ?: emptyList()

                if (streams.isEmpty()) continue

                val animeSession = watch.animeSession

                for (stream in streams) {
                    val kwikUrl = stream.kwikEmbed ?: continue
                    if (kwikUrl.isBlank()) continue

                    val quality = when (stream.quality?.lowercase()) {
                        "4k", "2160p" -> Qualities.P2160.value
                        "1080p", "fhd" -> Qualities.P1080.value
                        "720p", "hd" -> Qualities.P720.value
                        "480p", "sd" -> Qualities.P480.value
                        "360p" -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }

                    val audioLang = stream.audio ?: if (lang == "dub") "eng" else "jpn"
                    val labelLang = if (audioLang == "eng" || lang == "dub") "Dub" else "Sub"
                    val labelFansub = stream.fansub ?: ""
                    val labelQuality = stream.quality ?: "Unknown"
                    val linkName = "${labelFansub} $labelQuality $labelLang".trim()

                    try {
                        val proxyApiUrl = "$base/api/stream/m3u8?url=${java.net.URLEncoder.encode(kwikUrl, "UTF-8")}"
                        val proxyRes = app.get(proxyApiUrl, referer = "$base/")
                        val proxyData = parseJson<M3u8ProxyResponse>(proxyRes.text)
                        val m3u8Url = proxyData.m3u8
                        val streamReferer = proxyData.referer ?: kwikUrl

                        if (m3u8Url != null && m3u8Url.isNotBlank()) {
                            val localUrl = KwikProxyServer.getProxiedM3u8Url(m3u8Url, streamReferer)
                            val finalUrl = localUrl ?: m3u8Url
                            val finalHeaders = if (localUrl != null) {
                                mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                            } else {
                                mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                                    "Referer" to streamReferer
                                )
                            }
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = linkName,
                                    url = finalUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.quality = quality
                                    this.headers = finalHeaders
                                }
                            )
                            found = true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadLinks: m3u8 proxy failed for $kwikUrl: ${e.message}")
                    }

                    try {
                        val loaded = loadExtractor(kwikUrl, "$base/", subtitleCallback, callback)
                        if (loaded) found = true
                    } catch (e: Exception) {
                        Log.e(TAG, "loadLinks: loadExtractor failed for $kwikUrl: ${e.message}")
                    }
                }

                if (animeSession != null) {
                    try {
                        val beepUrl = "$base/api/stream/sources/beep?anilistId=$anilistId&episode=$episode&lang=$lang&session=$animeSession"
                        val beepRes = app.get(beepUrl, referer = "$base/")
                        val beep = parseJson<BeepResponse>(beepRes.text)

                        beep.qualities?.forEach { q ->
                            val qUrl = q.url ?: return@forEach
                            if (qUrl.isBlank()) return@forEach
                            val qQuality = when (q.quality?.lowercase()) {
                                "1080p" -> Qualities.P1080.value
                                "720p" -> Qualities.P720.value
                                "480p" -> Qualities.P480.value
                                "360p" -> Qualities.P360.value
                                else -> Qualities.Unknown.value
                            }
                            val beepLabel = "Beep ${q.quality ?: "Unknown"} ${if (lang == "dub") "Dub" else "Sub"}"
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = beepLabel,
                                    url = qUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = qQuality
                                    this.headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                                        "Referer" to "$base/"
                                    )
                                }
                            )
                            found = true
                        }

                        beep.embeds?.forEach { embed ->
                            val embedUrl = embed.url ?: return@forEach
                            if (embedUrl.isBlank()) return@forEach
                            try {
                                val loaded = loadExtractor(embedUrl, "$base/", subtitleCallback, callback)
                                if (loaded) found = true
                            } catch (e: Exception) {
                                Log.e(TAG, "loadLinks: embed ${embed.name} failed: ${e.message}")
                            }
                        }

                        beep.subtitles?.forEach { sub ->
                            val subUrl = sub.url ?: return@forEach
                            if (subUrl.isBlank()) return@forEach
                            val subLabel = sub.label ?: sub.lang ?: "English"
                            subtitleCallback.invoke(
                                SubtitleFile(
                                    lang = subLabel,
                                    url = if (subUrl.startsWith("http")) subUrl else "$base$subUrl"
                                )
                            )
                        }

                        beep.subtitleUrl?.takeIf { it.isNotBlank() }?.let { subUrl ->
                            val fullUrl = if (subUrl.startsWith("http")) subUrl else "$base$subUrl"
                            subtitleCallback.invoke(SubtitleFile("English", fullUrl))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadLinks: Beep source failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks: $lang failed: ${e.message}")
            }
        }

        try {
            val aniZip = fetchAniZip(anilistId)
            aniZip?.episodes?.get(episode.toString())?.let { epInfo ->
                epInfo.title?.keys?.forEach { langCode ->
                    val epTitle = epInfo.title[langCode] ?: return@forEach
                }
            }
        } catch (_: Exception) {}

        return found
    }

    private suspend fun checkDubAvailable(anilistId: Int, episodeCount: Int): Boolean {
        return try {
            val base = apiBase()
            val testEp = if (episodeCount > 0) 1 else 1
            val res = app.get("$base/api/stream/watch?anilistId=$anilistId&episode=$testEp&lang=dub", referer = "$base/")
            val watch = parseJson<WatchResponse>(res.text)
            val streams = watch.streams ?: emptyList()
            streams.isNotEmpty()
        } catch (_: Exception) { false }
    }

    private suspend fun fetchAniZip(anilistId: Int): AniZipResponse? {
        return try {
            val res = app.get("$aniZipApi/mappings?anilist_id=$anilistId", referer = "$mainUrl/")
            parseJson<AniZipResponse>(res.text)
        } catch (e: Exception) {
            Log.e(TAG, "fetchAniZip: ${e.message}")
            null
        }
    }

    private suspend fun anilistQuery(query: String, variables: Map<String, Any?>): String {
        val requestData = mapOf("query" to query, "variables" to variables).toJson()
            .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        return app.post(
            anilistGraphql,
            headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
            requestBody = requestData
        ).text
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeData(
        val anilistId: Int,
        val episode: Int,
        val title: String,
        val posterUrl: String? = null,
        val dubAvailable: Boolean = false
    )
}
