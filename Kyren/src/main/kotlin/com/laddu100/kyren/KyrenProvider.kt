package com.laddu100.kyren

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import java.net.URLEncoder

class KyrenProvider : MainAPI() {
    override var mainUrl = "https://kyren.moe"
    override var name = "Kyren"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "en"

    override val mainPage = mainPageOf(
        "$mainUrl/api/anime/latest" to "Latest Releases"
    )

    @Volatile
    private var isUrlLoaded = false

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get(
                "https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json",
                timeout = 10_000L
            ).text
            val config = parseJson<FirebaseConfig>(response)
            val url = config.kyren_url ?: config.kyren
            if (!url.isNullOrBlank()) {
                mainUrl = url.removeSuffix("/")
            }
            isUrlLoaded = true
        } catch (e: Exception) {
            isUrlLoaded = true
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FirebaseConfig(
        @JsonProperty("kyren_url") val kyren_url: String? = null,
        @JsonProperty("kyren") val kyren: String? = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        loadFirebaseUrl()
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val res = app.get(url, timeout = 30_000L).text
        val parsed = parseJson<LatestResponse>(res)
        val items = parsed.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        return newHomePageResponse(request.name, items, hasNext = parsed.meta?.nextPage != null)
    }

    private fun AnimeData.toSearchResponse(): SearchResponse? {
        val title = this.title?.english ?: this.title?.romaji ?: return null
        val poster = this.coverImage?.extraLarge ?: this.coverImage?.large
        val id = this.id ?: return null
        val href = "$mainUrl/api/anime/info/$id"
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        loadFirebaseUrl()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val res = app.get("$mainUrl/api/anime/search?q=$encoded", timeout = 30_000L).text
        val parsed = parseJson<SearchApiResponse>(res)
        return parsed.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        val title = this.title ?: return null
        val id = this.id ?: return null
        val href = "$mainUrl/api/anime/info/$id"
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = this@toSearchResponse.image
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        loadFirebaseUrl()
        val id = url.substringAfterLast("/")

        val infoRes = app.get(url, timeout = 30_000L).text
        val info = parseJson<AnimeInfo>(infoRes)

        val title = info.titleEnglish ?: info.titleRomaji ?: info.title ?: return null
        val poster = info.image
        val plot = info.description
        val genres = info.genres
        val year = info.year

        val epRes = app.get("$mainUrl/api/anime/episodes/$id", timeout = 30_000L).text
        val epParsed = parseJson<EpisodesResponse>(epRes)
        val eps = epParsed.data ?: emptyList()

        val totalEps = info.episodes ?: eps.size
        val isMovie = info.type?.equals("MOVIE", ignoreCase = true) == true && totalEps <= 1

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for (ep in eps) {
            val epNum = ep.number ?: continue
            val epTitle = ep.title ?: "Episode $epNum"
            val dataStr = "$id|$epNum|$title|$year|$totalEps"

            subEpisodes.add(newEpisode("$dataStr|sub") {
                this.episode = epNum
                this.name = epTitle
                this.posterUrl = ep.thumbnail
            })

            dubEpisodes.add(newEpisode("$dataStr|dub") {
                this.episode = epNum
                this.name = epTitle
                this.posterUrl = ep.thumbnail
            })
        }

        val tvType = if (isMovie && dubEpisodes.isEmpty()) TvType.AnimeMovie else TvType.Anime

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadFirebaseUrl()
        val parts = data.split("|")
        if (parts.size < 6) return false

        val id = parts[0]
        val epNum = parts[1]
        val title = parts[2]
        val year = parts[3]
        val totalEps = parts[4]
        val lang = parts[5]

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val streamUrl = "$mainUrl/api/stream/$id/$epNum?lang=$lang&title=$encodedTitle&server=megaplay&year=$year&episodes=$totalEps"

        val res = app.get(streamUrl, timeout = 30_000L).text
        val parsed = parseJson<StreamResponse>(res)

        if (parsed.ok != true || parsed.sources.isNullOrEmpty()) {
            Log.d("Kyren", "stream api failed: ${parsed.error}")
            return false
        }

        val embedUrl = parsed.sources.firstOrNull()?.url ?: return false
        return resolveMegaPlay(embedUrl, subtitleCallback, callback)
    }

    private suspend fun resolveMegaPlay(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val html = app.get(embedUrl, referer = "$mainUrl/", timeout = 30_000L).text
            val dataId = Regex("data-id=\"(\\d+)\"").find(html)?.groupValues?.get(1) ?: return false

            val sourcesUrl = "https://megaplay.buzz/stream/getSourcesNew?id=$dataId"
            val headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to embedUrl
            )
            val sourcesRes = app.get(sourcesUrl, headers = headers, timeout = 30_000L).text
            val sourcesParsed = parseJson<MegaPlaySources>(sourcesRes)

            val m3u8 = sourcesParsed.sources?.file ?: return false

            val link = newExtractorLink(
                source = "Kyren",
                name = "Kyren",
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "https://megaplay.buzz/"
                this.headers = mapOf("Referer" to "https://megaplay.buzz/")
            }
            callback.invoke(link)

            sourcesParsed.tracks?.forEach { track ->
                if (track.kind == "captions" && track.file != null) {
                    val subLang = track.label?.take(2) ?: "en"
                    val sub = newSubtitleFile(subLang, track.file) {
                        this.headers = mapOf("Referer" to "https://megaplay.buzz/")
                    }
                    subtitleCallback.invoke(sub)
                }
            }
            return true
        } catch (e: Exception) {
            Log.e("Kyren", "megaplay resolve failed: ${e.message}")
            return false
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LatestResponse(
        @JsonProperty("data") val data: List<AnimeData>?,
        @JsonProperty("meta") val meta: Meta?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Meta(@JsonProperty("nextPage") val nextPage: Boolean?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeData(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: TitleObj?,
        @JsonProperty("coverImage") val coverImage: CoverImage?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TitleObj(
        @JsonProperty("english") val english: String?,
        @JsonProperty("romaji") val romaji: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CoverImage(
        @JsonProperty("extraLarge") val extraLarge: String?,
        @JsonProperty("large") val large: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchApiResponse(@JsonProperty("items") val items: List<SearchItem>?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchItem(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("image") val image: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeInfo(
        @JsonProperty("title") val title: String?,
        @JsonProperty("titleEnglish") val titleEnglish: String?,
        @JsonProperty("titleRomaji") val titleRomaji: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("genres") val genres: List<String>?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("episodes") val episodes: Int?,
        @JsonProperty("year") val year: Int?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResponse(@JsonProperty("data") val data: List<EpData>?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpData(
        @JsonProperty("number") val number: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("thumbnail") val thumbnail: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamResponse(
        @JsonProperty("ok") val ok: Boolean?,
        @JsonProperty("sources") val sources: List<StreamSource>?,
        @JsonProperty("error") val error: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamSource(
        @JsonProperty("url") val url: String?,
        @JsonProperty("language") val language: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaPlaySources(
        @JsonProperty("sources") val sources: MegaPlayFile?,
        @JsonProperty("tracks") val tracks: List<MegaPlayTrack>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaPlayFile(@JsonProperty("file") val file: String?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaPlayTrack(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )
}
