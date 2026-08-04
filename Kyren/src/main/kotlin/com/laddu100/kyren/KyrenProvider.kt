package com.laddu100.kyren

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
import java.net.URLEncoder

class KyrenProvider : MainAPI() {
    override var mainUrl = "https://kyren.moe"
    override var name = "Kyren"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "en"

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json",
        "Referer" to "https://kyren.moe/"
    )

    override val mainPage = mainPageOf(
        "POPULARITY_DESC" to "Popular",
        "TRENDING_DESC" to "Trending",
        "SCORE_DESC" to "Top Rated",
        "START_DATE_DESC" to "Latest"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiSearchResponse(
        @JsonProperty("items") val items: List<AnimeItem>? = null,
        @JsonProperty("total") val total: Int? = null,
        @JsonProperty("hasNextPage") val hasNextPage: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("titleEnglish") val titleEnglish: String? = null,
        @JsonProperty("titleRomaji") val titleRomaji: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("seasonYear") val seasonYear: Int? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("subAvailable") val subAvailable: Boolean? = null,
        @JsonProperty("dubAvailable") val dubAvailable: Boolean? = null,
        @JsonProperty("nextAiringEpisode") val nextAiringEpisode: NextAiring? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NextAiring(@JsonProperty("episode") val episode: Int? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeList(
        @JsonProperty("data") val data: List<EpisodeInfo>? = null,
        @JsonProperty("total") val total: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeInfo(
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("titleJp") val titleJp: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("aired") val aired: String? = null,
        @JsonProperty("filler") val filler: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("sources") val sources: List<StreamSource>? = null,
        @JsonProperty("subtitles") val subtitles: List<SubtitleInfo>? = null,
        @JsonProperty("error") val error: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamSource(
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("isDub") val isDub: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubtitleInfo(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("label") val label: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LoadData(
        val id: Int,
        val title: String,
        val episodeCount: Int,
        val posterUrl: String? = null,
        val isMovie: Boolean = false
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeData(
        val id: Int,
        val episode: Int,
        val title: String,
        val posterUrl: String? = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sort = request.data
        val items = try {
            val url = "$mainUrl/api/anime/search?q=&page=$page&perPage=30&sort=$sort"
            val res = app.get(url, headers = apiHeaders)
            val parsed = parseJson<ApiSearchResponse>(res.text)
            parsed.items ?: emptyList()
        } catch (e: Exception) {
            Log.e("Kyren", "getMainPage: ${e.message}")
            emptyList()
        }

        val searchItems = items.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, searchItems)
    }

    private fun AnimeItem.toSearchResponse(): SearchResponse? {
        val animeId = id ?: return null
        val animeTitle = titleEnglish ?: title ?: titleRomaji ?: return null
        val loadData = LoadData(
            id = animeId,
            title = animeTitle,
            episodeCount = episodes ?: 1,
            posterUrl = image,
            isMovie = format == "MOVIE"
        )
        return newAnimeSearchResponse(animeTitle, loadData.toJson(), if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = image
            addDubStatus(dubExist = dubAvailable == true, subExist = subAvailable != false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/api/anime/search?q=$encoded&page=1&perPage=30"
            val res = app.get(url, headers = apiHeaders)
            val parsed = parseJson<ApiSearchResponse>(res.text)
            parsed.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            Log.e("Kyren", "search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = try {
            parseJson<LoadData>(url)
        } catch (e: Exception) {
            Log.e("Kyren", "load parse: ${e.message}")
            return null
        }

        return try {
            val infoUrl = "$mainUrl/api/anime/info/${loadData.id}"
            val infoRes = app.get(infoUrl, headers = apiHeaders)
            val anime = parseJson<AnimeItem>(infoRes.text)

            val title = anime.titleEnglish ?: anime.title ?: loadData.title
            val poster = anime.image ?: loadData.posterUrl
            val plot = anime.synopsis?.replace(Regex("<[^>]*>"), "")
            val year = anime.seasonYear
            val genres = anime.genres ?: emptyList()

            val episodeCount = if (anime.status == "FINISHED") {
                anime.episodes ?: loadData.episodeCount
            } else {
                anime.nextAiringEpisode?.episode?.let { it - 1 } ?: anime.episodes ?: loadData.episodeCount
            }

            val episodesData = try {
                val epUrl = "$mainUrl/api/anime/episodes/${loadData.id}"
                val epRes = app.get(epUrl, headers = apiHeaders)
                parseJson<EpisodeList>(epRes.text)
            } catch (e: Exception) {
                Log.e("Kyren", "load episodes: ${e.message}")
                null
            }

            val epList = episodesData?.data ?: emptyList()

            if (loadData.isMovie || anime.format == "MOVIE") {
                val movieData = LoadData(loadData.id, title, 1, poster, true)
                newMovieLoadResponse(title, url, TvType.AnimeMovie, movieData.toJson()) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = anime.bannerImage
                    this.plot = plot
                    this.year = year
                    this.tags = genres
                }
            } else {
                val episodes = mutableListOf<Episode>()

                if (epList.isNotEmpty()) {
                    for (ep in epList) {
                        val epNum = ep.number ?: continue
                        val epData = EpisodeData(loadData.id, epNum, title, ep.thumbnail ?: poster).toJson()
                        episodes.add(newEpisode(epData) {
                            this.name = ep.title ?: "Episode $epNum"
                            this.episode = epNum
                            this.season = 1
                            this.posterUrl = ep.thumbnail ?: poster
                            this.description = if (ep.filler == true) "Filler episode" else null
                        })
                    }
                } else {
                    for (epNum in 1..episodeCount) {
                        val epData = EpisodeData(loadData.id, epNum, title, poster).toJson()
                        episodes.add(newEpisode(epData) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                            this.season = 1
                            this.posterUrl = poster
                        })
                    }
                }

                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = anime.bannerImage
                    this.plot = plot
                    this.year = year
                    this.tags = genres
                }
            }
        } catch (e: Exception) {
            Log.e("Kyren", "load: ${e.message}")
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
        val animeId = try {
            if (isMovie) parseJson<LoadData>(data).id
            else parseJson<EpisodeData>(data).id
        } catch (e: Exception) {
            Log.e("Kyren", "loadLinks parse: ${e.message}")
            return false
        }

        val episode = if (isMovie) 1 else try { parseJson<EpisodeData>(data).episode } catch (_: Exception) { 1 }
        val title = if (isMovie) parseJson<LoadData>(data).title else parseJson<EpisodeData>(data).title

        var found = false

        for (lang in listOf("sub", "dub")) {
            for (server in listOf("megaplay", "megaplay-direct", "tryembed")) {
                try {
                    val encodedTitle = URLEncoder.encode(title, "UTF-8")
                    val streamUrl = "$mainUrl/api/stream/$animeId/$episode?lang=$lang&title=$encodedTitle&server=$server"

                    val res = app.get(streamUrl, headers = apiHeaders)
                    val parsed = parseJson<StreamResponse>(res.text)

                    if (parsed.ok != true) continue

                    val sources = parsed.sources ?: emptyList()
                    for (source in sources) {
                        val sourceUrl = source.url ?: continue
                        if (sourceUrl.isBlank()) continue

                        val langLabel = if (lang == "dub" || source.isDub == true) "Dub" else "Sub"
                        val providerName = source.provider ?: server
                        val quality = when (source.quality?.lowercase()) {
                            "1080p" -> Qualities.P1080.value
                            "720p" -> Qualities.P720.value
                            "480p" -> Qualities.P480.value
                            "360p" -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }

                        when (source.type) {
                            "hls" -> {
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "$providerName $langLabel",
                                        url = sourceUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = quality
                                        this.headers = mapOf(
                                            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                                            "Referer" to "$mainUrl/"
                                        )
                                    }
                                )
                                found = true
                            }
                            "embed" -> {
                                val loaded = loadExtractor(sourceUrl, "$mainUrl/", subtitleCallback, callback)
                                if (loaded) found = true
                            }
                            else -> {
                                val loaded = loadExtractor(sourceUrl, "$mainUrl/", subtitleCallback, callback)
                                if (loaded) found = true
                            }
                        }
                    }

                    parsed.subtitles?.forEach { sub ->
                        val subUrl = sub.url ?: return@forEach
                        if (subUrl.isBlank()) return@forEach
                        val subLabel = sub.label ?: sub.lang ?: "English"
                        subtitleCallback.invoke(SubtitleFile(subLabel, subUrl))
                    }
                } catch (e: Exception) {
                    Log.e("Kyren", "loadLinks: $lang/$server: ${e.message}")
                }
            }
        }

        return found
    }
}
