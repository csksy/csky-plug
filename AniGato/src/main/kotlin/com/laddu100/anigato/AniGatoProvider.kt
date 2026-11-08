package com.laddu100.anigato

import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

class AniGatoProvider : MainAPI() {

    override var mainUrl = AniGatoApi.MAIN_URL
    override var name = "AniGato"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "popular" to "Most Popular",
        "top" to "Top Rated",
        "recent" to "Recently Added"
    )

    companion object {
        private const val TAG = "AniGato"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val feed = AniGatoApi.feed(request.data, page) ?: return null
        val items = feed.media.orEmpty().mapNotNull { it.toSearchResponse() }
        val hasNext = feed.hasNextPage == true || (items.isNotEmpty() && page < 400)
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val res = AniGatoApi.searchAnime(query) ?: return emptyList()
        return res.media.orEmpty().mapNotNull { it.toSearchResponse() }
    }

    private fun AnimeMedia.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val title = this.title?.display() ?: return null
        val tvType = when (format) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", tvType) {
            this.posterUrl = coverImage?.best()
            this.year = seasonYear
            this.otherName = this@toSearchResponse.title?.romaji
            averageScore?.let { score = Score.from100(it) }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = url.trimEnd('/').substringAfterLast("/").toIntOrNull() ?: return null
        val anime = AniGatoApi.animeDetail(anilistId) ?: return null
        val title = anime.title?.display() ?: return null

        val episodeList = AniGatoApi.episodes(anilistId, title)
        if (episodeList.isEmpty()) return null

        // a single dub probe on the first episode decides whether the dub list
        // exists, the site answers with streams even for sub only shows when it
        // intends its fallback of playing the sub with subtitles, only urls a
        // player can actually use count as an answer
        val dubAvailable = AniGatoApi.watch(anilistId, "dub", episodeList.first().number ?: 1, title)
            ?.streams.orEmpty().any { stream ->
                val streamUrl = stream.url?.takeIf { it.isNotBlank() } ?: return@any false
                streamUrl.startsWith("/") ||
                    (streamUrl.startsWith("http") && !AniGatoApi.isSelfHostedEncrypted(streamUrl))
            }

        // the app hides the sub dub switcher on movie types, so dual audio
        // movies are typed as regular anime to keep both reachable
        val tvType = when {
            anime.format == "MOVIE" && dubAvailable -> TvType.Anime
            anime.format == "MOVIE" -> TvType.AnimeMovie
            anime.format == "OVA" || anime.format == "ONA" || anime.format == "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        val showStatus = when (anime.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val subEpisodes = episodeList.map { it.toEpisode(anilistId, "sub", title) }
        val dubEpisodes = if (dubAvailable) {
            episodeList.map { it.toEpisode(anilistId, "dub", title) }
        } else {
            emptyList()
        }

        val response = newAnimeLoadResponse(title, "$mainUrl/anime/$anilistId", tvType) {
            this.engName = anime.title?.english
            this.japName = anime.title?.romaji ?: anime.title?.native
            this.posterUrl = anime.coverImage?.best()
            this.backgroundPosterUrl = anime.bannerImage
            this.plot = anime.description?.let(::stripHtml)
            this.tags = anime.genres.orEmpty()
            this.year = anime.seasonYear
            this.duration = anime.duration
            this.showStatus = showStatus
            anime.averageScore?.let { score = Score.from100(it) }
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
        anime.trailer?.let { trailer ->
            if (trailer.site == "youtube" && !trailer.id.isNullOrBlank()) {
                try {
                    response.addTrailer("https://www.youtube.com/watch?v=${trailer.id}")
                } catch (e: Exception) {
                    Log.d(TAG, "trailer: ${e.message}")
                }
            }
        }
        return response
    }

    // the index prefixes episode titles with the show name, the site strips
    // that prefix before showing the title, this mirrors the same rule
    private fun cleanEpisodeTitle(raw: String, showTitle: String): String {
        val cleaned = Regex("(?i)^${Regex.escape(showTitle)}\\s*[-\u2013\u2014:|]\\s*")
            .replace(raw, "").trim()
        return cleaned.ifBlank { raw }
    }

    private fun EpisodeEntry.toEpisode(anilistId: Int, audio: String, showTitle: String): Episode {
        val num = number ?: 1
        val data = AniGatoEpisodeData(
            anilistId = anilistId,
            ep = num,
            audio = audio,
            title = showTitle
        ).toJson()
        return newEpisode(data) {
            this.episode = num
            this.name = title?.takeIf { it.isNotBlank() }?.let { cleanEpisodeTitle(it, showTitle) }
            this.posterUrl = thumbnail?.takeIf { it.startsWith("http") }
            this.description = buildString {
                if (isFiller == true) append("Filler episode")
                airDate?.let {
                    if (isNotEmpty()) append(" | ")
                    append("Aired ${it.substringBefore("T").take(10)}")
                }
                description?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" | ")
                    append(stripHtml(it))
                }
            }.takeIf { it.isNotBlank() }
            parseAirDate(airDate)?.let { addDate(it) }
        }
    }

    private fun parseAirDate(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(raw.substringBefore("T").take(10))
        } catch (e: Exception) {
            null
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = try {
            parseJson<AniGatoEpisodeData>(data)
        } catch (e: Exception) {
            Log.d(TAG, "episode data parse: ${e.message}")
            return false
        }
        val anilistId = ref.anilistId ?: return false
        val episode = ref.ep ?: return false
        val audio = if (ref.audio == "dub") "dub" else "sub"
        val title = ref.title.orEmpty()

        val response = AniGatoApi.watch(anilistId, audio, episode, title) ?: return false
        val streams = response.streams.orEmpty()
        if (streams.isEmpty() || response.notReleased == true) return false

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubs = ConcurrentHashMap.newKeySet<String>()

        val results = coroutineScope {
            streams.map { stream ->
                async(Dispatchers.IO) {
                    val rawUrl = stream.url?.takeIf { it.isNotBlank() } ?: return@async false
                    if (AniGatoApi.isSelfHostedEncrypted(rawUrl) && rawUrl.startsWith("http")) return@async false
                    val url = AniGatoApi.absolute(rawUrl)

                    val validated = AniGatoApi.validateStream(url) ?: return@async false
                    if (!seenLinks.add(validated.url)) return@async false

                    val serverName = stream.serverName ?: stream.name ?: "Server"
                    val qualityLabel = stream.quality?.takeIf { it.isNotBlank() && it != "auto" }
                    val height = validated.maxHeight ?: qualityLabel?.let { qualityHeight(it) }
                    val label = buildString {
                        append(serverName)
                        if (height != null) append(" ${height}p")
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = label,
                            url = validated.url,
                            type = if (validated.isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                        ) {
                            height?.let { quality = it }
                            this.headers = mapOf(
                                "User-Agent" to AniGatoApi.browserHeaders["User-Agent"]!!,
                                "Referer" to "${AniGatoApi.MAIN_URL}/"
                            )
                        }
                    )

                    stream.subtitles.orEmpty().forEach { track ->
                        val subUrl = track.url?.takeIf { it.isNotBlank() } ?: return@forEach
                        val absolute = AniGatoApi.absolute(subUrl)
                        if (!seenSubs.add(absolute)) return@forEach
                        val subLabel = track.label?.takeIf { it.isNotBlank() }
                            ?: track.lang?.takeIf { it.isNotBlank() }
                            ?: "English"
                        try {
                            subtitleCallback.invoke(
                                newSubtitleFile(subLabel, absolute) {
                                    this.headers = mapOf("Referer" to "${AniGatoApi.MAIN_URL}/")
                                }
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "subtitle emit: ${e.message}")
                        }
                    }
                    true
                }
            }
        }.map { it.await() }

        return results.any { it }
    }

    // the api labels heights as 4K or plain numbers, the player wants pixels
    private fun qualityHeight(label: String): Int? = when {
        label.contains("4K", true) || label.contains("2160", true) -> 2160
        label.contains("1080", true) -> 1080
        label.contains("720", true) -> 720
        label.contains("480", true) -> 480
        label.contains("360", true) -> 360
        else -> null
    }
}
