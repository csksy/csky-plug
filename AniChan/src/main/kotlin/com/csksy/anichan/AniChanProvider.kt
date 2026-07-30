package com.csksy.anichan

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup

class AniChanProvider : MainAPI() {
    override var mainUrl = "https://anichan.net"
    override var name = "AniChan"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val browserUA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "season" to "Airing This Season",
        "popular" to "All-Time Popular"
    )

    private val anilistUrl = "https://graphql.anilist.co"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val sort = when (request.data) {
                "trending" -> "TRENDING_DESC"
                "season" -> "POPULARITY_DESC"
                "popular" -> "POPULARITY_DESC"
                else -> "TRENDING_DESC"
            }

            val (season, seasonYear) = if (request.data == "season") {
                val cal = java.util.Calendar.getInstance()
                val s = when (cal.get(java.util.Calendar.MONTH)) {
                    in 0..2 -> "WINTER"
                    in 3..5 -> "SPRING"
                    in 6..8 -> "SUMMER"
                    in 9..11 -> "FALL"
                    else -> "WINTER"
                }
                s to cal.get(java.util.Calendar.YEAR)
            } else null to null

            val query = if (season != null) {
                "{ Page(page: 1, perPage: 20) { media(type: ANIME, sort: [$sort], season: $season, seasonYear: $seasonYear) { id title { english romaji } coverImage { large extraLarge } format episodes seasonYear } } }"
            } else {
                "{ Page(page: 1, perPage: 20) { media(type: ANIME, sort: [$sort]) { id title { english romaji } coverImage { large extraLarge } format episodes seasonYear } } }"
            }

            val response = app.post(anilistUrl, json = mapOf("query" to query), timeout = 15_000L).text
            val data = parseJson<AniListPage>(response)
            val media = data.data?.page?.media ?: emptyList()

            val items = media.mapNotNull { m ->
                val title = m.title?.english ?: m.title?.romaji ?: return@mapNotNull null
                val id = m.id ?: return@mapNotNull null
                val poster = m.coverImage?.extraLarge ?: m.coverImage?.large ?: ""
                newAnimeSearchResponse(title, id.toString(), TvType.Anime) {
                    this.posterUrl = poster
                    this.year = m.seasonYear
                }
            }

            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            Log.e("AniChan", "getMainPage: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val response = app.get(
                "$mainUrl/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}",
                headers = mapOf("User-Agent" to browserUA),
                timeout = 15_000L
            ).text
            val data = parseJson<AniChanSearchResponse>(response)
            data.results.mapNotNull { item ->
                val title = item.title ?: item.titleRomaji ?: return@mapNotNull null
                val id = item.id ?: return@mapNotNull null
                val tvType = if (item.format == "MOVIE") TvType.AnimeMovie else TvType.Anime
                newAnimeSearchResponse(title, id.toString(), tvType) {
                    this.posterUrl = item.poster
                    this.year = item.startDate?.year
                }
            }
        } catch (e: Exception) {
            Log.e("AniChan", "search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = url.toIntOrNull() ?: return null

        val animeHtml = app.get("$mainUrl/anime/$anilistId", headers = mapOf("User-Agent" to browserUA), timeout = 30_000L).text
        val doc = Jsoup.parse(animeHtml)

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" —")
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val genres = doc.select(".info-row:contains(Genres) a, .genres a").map { it.text().trim() }.filter { it.isNotBlank() }
        val year = doc.selectFirst(".info-row:contains(Year) b")?.text()?.toIntOrNull()
            ?: doc.selectFirst(".info-row:contains(Aired) b")?.text()?.substringBefore("-")?.trim()?.toIntOrNull()

        val epData = try {
            val resp = app.get(
                "$mainUrl/api/watch/episodes?anilistId=$anilistId",
                headers = mapOf("User-Agent" to browserUA, "Referer" to "$mainUrl/anime/$anilistId"),
                timeout = 15_000L
            ).text
            parseJson<EpisodesResponse>(resp)
        } catch (e: Exception) {
            Log.e("AniChan", "load: episodes fetch failed: ${e.message}")
            return null
        }

        val epCount = epData.episodes ?: 0
        val dubAvailable = epData.dubAvailable == true
        val isMovie = epCount <= 1

        val titleMap = extractEpisodeTitles(animeHtml)

        if (isMovie) {
            val movieData = EpisodeData(anilistId, 1).toJson()
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, movieData) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        }

        val subEpisodes = (1..epCount).map { epNum ->
            val epTitle = titleMap[epNum] ?: "Episode $epNum"
            newEpisode(EpisodeData(anilistId, epNum).toJson()) {
                this.episode = epNum
                this.name = epTitle
            }
        }

        val dubEpisodes = if (dubAvailable) {
            (1..epCount).map { epNum ->
                val epTitle = titleMap[epNum] ?: "Episode $epNum"
                newEpisode(EpisodeData(anilistId, epNum).toJson()) {
                    this.episode = epNum
                    this.name = epTitle
                }
            }
        } else emptyList()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val epData = parseJson<EpisodeData>(data)
            val anilistId = epData.anilistId
            val episode = epData.episode

            var foundLinks = false

            for (category in listOf("sub", "dub")) {
                try {
                    val resp = app.get(
                        "$mainUrl/api/watch/servers?anilistId=$anilistId&ep=$episode&category=$category",
                        headers = mapOf("User-Agent" to browserUA, "Referer" to "$mainUrl/anime/$anilistId"),
                        timeout = 15_000L
                    ).text
                    val servers = parseJson<ServersResponse>(resp).servers ?: emptyList()

                    for (server in servers) {
                        val stream = server.stream ?: continue
                        val fullStream = if (stream.startsWith("/")) "$mainUrl$stream" else stream
                        val label = server.label ?: server.name ?: "AniChan"
                        val subType = server.subType ?: "soft"
                        val isHardsub = subType == "hard"

                        val displayLabel = when {
                            isHardsub && category == "sub" -> "$label (Hardsub)"
                            category == "dub" -> "$label (Dub)"
                            else -> label
                        }

                        callback.invoke(newExtractorLink(
                            "AniChan",
                            displayLabel,
                            fullStream,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = mapOf(
                                "User-Agent" to browserUA,
                                "Referer" to "$mainUrl/anime/$anilistId"
                            )
                        })
                        foundLinks = true

                        server.subtitles?.forEach { sub ->
                            val subUrl = sub.url ?: return@forEach
                            val fullSubUrl = if (subUrl.startsWith("/")) "$mainUrl$subUrl" else subUrl
                            val lang = sub.lang ?: "English"
                            subtitleCallback.invoke(SubtitleFile(lang, fullSubUrl))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AniChan", "loadLinks $category: ${e.message}")
                }
            }

            foundLinks
        } catch (e: Exception) {
            Log.e("AniChan", "loadLinks: ${e.message}")
            false
        }
    }

    private fun extractEpisodeTitles(html: String): Map<Int, String> {
        val titles = mutableMapOf<Int, String>()
        val regex = Regex("""\"(\d+)\":\"([^\"]{3,120})\"""")
        regex.findAll(html).forEach { match ->
            val epNum = match.groupValues[1].toIntOrNull() ?: return@forEach
            val title = match.groupValues[2]
                .replace("\\u00e2\\u0080\\u0099", "'")
                .replace("\\u00e2\\u0080\\u009c", "\"")
                .replace("\\u00e2\\u0080\\u009d", "\"")
                .replace("\\/", "/")
            if (title.isNotBlank() && !title.matches(Regex("\\d+"))) {
                titles[epNum] = title
            }
        }
        return titles
    }

    data class EpisodeData(val anilistId: Int, val episode: Int)
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniChanSearchResponse(
    @JsonProperty("results") val results: List<SearchResult> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("titleRomaji") val titleRomaji: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("startDate") val startDate: StartDate? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StartDate(
    @JsonProperty("year") val year: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodesResponse(
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("dubAvailable") val dubAvailable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServersResponse(
    @JsonProperty("servers") val servers: List<Server>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Server(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("host") val host: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("stream") val stream: String? = null,
    @JsonProperty("subType") val subType: String? = null,
    @JsonProperty("subtitles") val subtitles: List<Subtitle>? = null,
    @JsonProperty("audios") val audios: List<Audio>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Subtitle(
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("default") val default: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Audio(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("lang") val lang: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListPage(
    @JsonProperty("data") val data: AniListPageData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListPageData(
    @JsonProperty("Page") val page: AniListPageMedia? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListPageMedia(
    @JsonProperty("media") val media: List<AniListMedia>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: AniListTitle? = null,
    @JsonProperty("coverImage") val coverImage: AniListCover? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListTitle(
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("romaji") val romaji: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListCover(
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("extraLarge") val extraLarge: String? = null
)
