package com.csksy.anisnatch

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup

class AniSnatchProvider : MainAPI() {
    override var mainUrl = "https://anisnatch.to"
    override var name = "AniSnatch"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val anilistUrl = "https://graphql.anilist.co"
    private val jikanUrl = "https://api.jikan.moe/v4"
    private val dlUrl = "https://dl.anisnatch.top"

    private val browserUA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "TRENDING_DESC" to "Trending Now",
        "POPULARITY_DESC" to "All-Time Popular",
        "SCORE_DESC" to "Top Rated",
        "START_DATE_DESC" to "Recently Added"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sort = request.data
        val query = """{ Page(page: 1, perPage: 20) { media(type: ANIME, sort: [$sort], format_in: [TV, MOVIE, ONA, OVA]) { id title { english romaji } coverImage { large extraLarge } format episodes seasonYear } } }"""
        val items = try {
            val response = app.post(anilistUrl, json = mapOf("query" to query)).text
            parseAniListSearch(response)
        } catch (e: Exception) {
            Log.e("AniSnatch", "getMainPage: ${e.message}")
            emptyList()
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.replace("\"", "\\\"")
        val graphql = """{ Page(page: 1, perPage: 30) { media(search: "$encoded", type: ANIME, format_in: [TV, MOVIE, ONA, OVA]) { id title { english romaji } coverImage { large extraLarge } format episodes seasonYear } } }"""
        return try {
            val response = app.post(anilistUrl, json = mapOf("query" to graphql)).text
            parseAniListSearch(response)
        } catch (e: Exception) {
            Log.e("AniSnatch", "search: ${e.message}")
            emptyList()
        }
    }

    private fun parseAniListSearch(json: String): List<SearchResponse> {
        val data = try {
            parseJson<AniListPageResponse>(json).data?.page?.media ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return data.mapNotNull { media ->
            val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            val id = media.id ?: return@mapNotNull null
            val poster = media.coverImage?.extraLarge ?: media.coverImage?.large ?: ""
            val tvType = if (media.formatStr == "MOVIE") TvType.AnimeMovie else TvType.Anime
            newAnimeSearchResponse(title, "$id", tvType) {
                this.posterUrl = poster
                this.year = media.seasonYear
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = url.toIntOrNull() ?: return null

        val metaQuery = """{ Media(id: $anilistId) { id idMal title { english romaji } coverImage { large extraLarge } bannerImage description genres episodes format seasonYear averageScore status nextAiringEpisode { episode } } }"""
        val meta: AniListMedia? = try {
            val resp = app.post(anilistUrl, json = mapOf("query" to metaQuery)).text
            parseJson<AniListMediaResponse>(resp).data?.media
        } catch (e: Exception) {
            Log.e("AniSnatch", "load: AniList fetch failed: ${e.message}")
            null
        }

        val title = meta?.title?.english ?: meta?.title?.romaji ?: return null
        val poster = meta?.coverImage?.extraLarge ?: meta?.coverImage?.large ?: ""
        val banner = meta?.bannerImage ?: ""
        val plot = meta?.description?.replace(Regex("<[^>]+>"), "")?.replace("\\n", "\n")
        val genres = meta?.genres ?: emptyList()
        val year = meta?.seasonYear
        val scoreVal = meta?.averageScore?.div(10.0)?.toFloat()

        // Determine episode count — AniList returns null for ongoing series
        var epCount = meta?.episodes ?: 0
        if (epCount == 0 && meta?.statusStr == "RELEASING") {
            epCount = (meta.nextAiringEpisode?.episode ?: 1) - 1
            if (epCount < 1) epCount = 1
        }
        if (epCount == 0) epCount = 1

        val titleMap = fetchJikanEpisodeTitles(meta?.idMal)
        val isMovie = meta?.formatStr == "MOVIE" || epCount <= 1

        if (isMovie) {
            val movieData = MovieData(anilistId, 1)
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, movieData.toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = plot
                this.tags = genres
                this.year = year
                if (scoreVal != null) this.score = Score.from10(scoreVal)
            }
        }

        // Build episodes — check both sub and dub by testing first episode
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for (epNum in 1..epCount) {
            val epTitle = titleMap[epNum] ?: "Episode $epNum"
            val epData = EpisodeData(anilistId, epNum).toJson()

            subEpisodes.add(newEpisode(epData) {
                this.episode = epNum
                this.name = epTitle
            })

            dubEpisodes.add(newEpisode(epData) {
                this.episode = epNum
                this.name = epTitle
            })
        }

        // Check if dub is available by testing the first episode page
        val hasDub = checkDubAvailability(anilistId)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = plot
            this.tags = genres
            this.year = year
            if (scoreVal != null) this.score = Score.from10(scoreVal)
            addEpisodes(DubStatus.Subbed, subEpisodes)
            if (hasDub) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private suspend fun checkDubAvailability(anilistId: Int): Boolean {
        return try {
            val html = fetchEpisodePage(anilistId, 1)
            html.contains("Dubbed Audio Releases")
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun fetchEpisodePage(anilistId: Int, episode: Int): String {
        val url = "$dlUrl/anime/$anilistId/$episode"
        return app.get(url, headers = mapOf("User-Agent" to browserUA), timeout = 30_000L).text
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parsed = parseJson<EpisodeData>(data)
            val html = fetchEpisodePage(parsed.anilistId, parsed.episode)
            val doc = Jsoup.parse(html)

            val sections = doc.select("div.section-title")
            for (section in sections) {
                val sectionText = section.text().lowercase()
                val isDub = sectionText.contains("dubbed") || sectionText.contains("hindi")
                if (!isDub && !sectionText.contains("subtitled")) continue

                val linkGrid = section.nextElementSibling()
                val links = linkGrid?.select("a.download-btn") ?: continue

                for (link in links) {
                    val href = link.attr("href")
                    if (href.isBlank()) continue
                    val fullUrl = if (href.startsWith("http")) href else "$dlUrl$href"
                    val label = link.selectFirst("span")?.text()?.trim() ?: continue

                    try {
                        loadExtractor(fullUrl, "$mainUrl/", subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.d("AniSnatch", "extractor failed for $label: ${e.message}")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e("AniSnatch", "loadLinks: ${e.message}")
            false
        }
    }

    private suspend fun fetchJikanEpisodeTitles(malId: Int?): Map<Int, String> {
        if (malId == null) return emptyMap()
        val titleMap = mutableMapOf<Int, String>()
        var page = 1
        var hasMore = true
        var safety = 0
        while (hasMore && safety < 50) {
            try {
                val resp = app.get("$jikanUrl/anime/$malId/episodes?page=$page", timeout = 15_000L).text
                val parsed = parseJson<JikanEpisodesResponse>(resp)
                parsed.data?.forEach { ep ->
                    val epNum = ep.malId ?: ep.sort ?: return@forEach
                    val epTitle = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                    titleMap[epNum] = epTitle
                }
                hasMore = (parsed.pagination?.hasNextPage ?: false) && !parsed.data.isNullOrEmpty()
                page++
                safety++
            } catch (e: Exception) {
                break
            }
        }
        return titleMap
    }

    data class EpisodeData(val anilistId: Int, val episode: Int)
    data class MovieData(val anilistId: Int, val episode: Int)
}

// ---------------- AniList data classes ----------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListPageResponse(
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
data class AniListMediaResponse(
    @JsonProperty("data") val data: AniListMediaData? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListMediaData(
    @JsonProperty("Media") val media: AniListMedia? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("idMal") val idMal: Int? = null,
    @JsonProperty("title") val title: AniListTitle? = null,
    @JsonProperty("coverImage") val coverImage: AniListCover? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("format") val formatStr: String? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("status") val statusStr: String? = null,
    @JsonProperty("nextAiringEpisode") val nextAiringEpisode: AniListAiring? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListAiring(
    @JsonProperty("episode") val episode: Int? = null
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

// ---------------- Jikan v4 data classes ----------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class JikanEpisodesResponse(
    @JsonProperty("data") val data: List<JikanEpisode>? = null,
    @JsonProperty("pagination") val pagination: JikanPagination? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class JikanEpisode(
    @JsonProperty("mal_id") val malId: Int? = null,
    @JsonProperty("sort") val sort: Int? = null,
    @JsonProperty("title") val title: String? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class JikanPagination(
    @JsonProperty("has_next_page") val hasNextPage: Boolean? = null
)
