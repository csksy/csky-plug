package com.laddu100

import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class Miruro : MainAPI() {

    override var mainUrl = "https://www.miruro.to"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "popular" to "Popular",
        "recent" to "Recently Updated",
        "movies" to "Top Movies",
        "top" to "Top Rated"
    )

    // the catalog pages with cursors, cloudstream asks for pages in order so
    // the cursor of page n only unlocks after page n - 1 was fetched
    private data class PageCursor(val page: Int, val next: String?)

    private val cursors = ConcurrentHashMap<String, PageCursor>()

    private val providerNames = mapOf(
        "animepahe" to "AnimePahe",
        "animegg" to "AnimeGG",
        "aniwaves" to "AniWaves",
        "anikoto" to "AniKoto",
        "kickassanime" to "KickAssAnime",
        "icarus" to "Icarus",
        "gogoanime" to "GogoAnime",
        "hianime" to "HiAnime",
        "megaplay" to "MegaPlay",
        "vidwish" to "VidWish"
    )

    private fun providerLabel(key: String): String =
        providerNames[key] ?: key.replaceFirstChar { it.uppercase() }

    private fun encodeParam(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun MiruroAnime.tvType(): TvType = when (format) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA", "ONA" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun MiruroAnime.displayTitle(): String? =
        title?.english?.takeIf { it.isNotBlank() }
            ?: title?.romaji?.takeIf { it.isNotBlank() }

    private fun MiruroAnime.toSearch(): SearchResponse? {
        val name = displayTitle() ?: return null
        if (id.isBlank()) return null
        return newAnimeSearchResponse(name, "$mainUrl/watch/$id", tvType()) {
            posterUrl = cover_url
            year = season_year
            val counts = episode_counts
            addDubStatus(
                dubExist = (counts?.dub ?: 0) > 0,
                subExist = (counts?.sub ?: counts?.raw ?: 0) > 0
            )
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        if (page <= 1) cursors.remove(request.data)
        val previous = cursors[request.data]
        if (page > 1 && (previous == null || previous.page != page - 1 || previous.next == null)) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val cursor = if (page > 1) previous?.next else null
        val result = try {
            when (request.data) {
                "trending" -> MiruroApi.trending()
                "popular" -> MiruroApi.popular(cursor)
                "recent" -> MiruroApi.recent(cursor)
                "movies" -> MiruroApi.movies(cursor)
                else -> MiruroApi.topRated(cursor)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ErrorLoadingException("Could not load the homepage: ${e.message}")
        }
        if (result.next_cursor != null) {
            cursors[request.data] = PageCursor(page, result.next_cursor)
        } else {
            cursors.remove(request.data)
        }
        val cards = result.data.mapNotNull { it.toSearch() }
        return newHomePageResponse(request.name, cards, hasNext = result.has_more)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = encodeParam(query.trim())
        if (encoded.isEmpty()) return emptyList()
        val result = try {
            MiruroApi.search(encoded)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("Miruro", "search failed: ${e.message}")
            return emptyList()
        }
        return result.data.mapNotNull { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""/watch/([A-Za-z0-9_-]{32})""").find(url)?.groupValues?.get(1)
            ?: return null
        val anime = try {
            MiruroApi.detail(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ErrorLoadingException("Could not load this title: ${e.message}")
        }
        val title = anime.displayTitle() ?: "Unknown"
        val counts = anime.episode_counts
        val subLimit = counts?.sub ?: 0
        val dubLimit = counts?.dub ?: 0

        val episodes = try {
            MiruroApi.episodes(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("Miruro", "episodes unavailable for $id: ${e.message}")
            emptyList()
        }
        if (episodes.isEmpty()) {
            throw ErrorLoadingException("No episodes found for this title")
        }

        val subEpisodes = episodes.mapNotNull { ep ->
            val num = ep.episode_number ?: return@mapNotNull null
            val include = when {
                ep.kind == "special" -> true
                subLimit <= 0 -> true
                else -> num in 1..subLimit
            }
            if (include) ep.toEpisode(id, "sub") else null
        }
        val dubEpisodes = episodes.mapNotNull { ep ->
            val num = ep.episode_number ?: return@mapNotNull null
            if (ep.kind != "special" && dubLimit > 0 && num in 1..dubLimit) {
                ep.toEpisode(id, "dub")
            } else {
                null
            }
        }

        val plot = anime.description?.replace(Regex("<[^>]*>"), "")
        return newAnimeLoadResponse(title, url, anime.tvType()) {
            posterUrl = anime.cover_url
            backgroundPosterUrl = anime.banner_url
            year = anime.season_year
            this.plot = plot
            tags = anime.genres.orEmpty()
            duration = anime.episode_duration_minutes
            if (anime.average_score != null) {
                score = Score.from10((anime.average_score / 10.0).toString())
            }
            showStatus = when (anime.status) {
                "RELEASING" -> ShowStatus.Ongoing
                "FINISHED" -> ShowStatus.Completed
                else -> null
            }
            anime.external_ids?.anilist?.firstOrNull()?.toIntOrNull()?.let { addAniListId(it) }
            anime.external_ids?.mal?.firstOrNull()?.toIntOrNull()?.let { addMalId(it) }
            addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private fun MiruroEpisode.toEpisode(animeId: String, track: String): Episode {
        val num = episode_number ?: 0
        val special = kind == "special"
        return newEpisode("$animeId|$num|$track") {
            name = title?.takeIf { it.isNotBlank() } ?: "Episode $num"
            this.episode = num
            season = if (special) 0 else aired_season_number ?: 1
            posterUrl = thumbnail_url
            description = synopsis
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size != 3) return false
        val animeId = parts[0]
        val episodeNumber = parts[1].toIntOrNull() ?: return false
        val wantedTrack = parts[2]

        val play = try {
            MiruroApi.play(animeId, episodeNumber)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ErrorLoadingException("Could not load streams: ${e.message}")
        }

        val primary = if (wantedTrack == "dub") listOf("dub") else listOf("sub", "ssub")
        val secondary = if (wantedTrack == "dub") listOf("sub", "ssub", "raw") else listOf("raw", "dub")

        val seen = ConcurrentHashMap.newKeySet<String>()
        val embeds = mutableListOf<Pair<String, String>>()
        var emitted = false

        for (trackKey in primary) {
            if (emitTrack(play, trackKey, seen, embeds, subtitleCallback, callback)) emitted = true
        }
        if (!emitted) {
            for (trackKey in secondary) {
                if (emitTrack(play, trackKey, seen, embeds, subtitleCallback, callback)) emitted = true
            }
        }
        if (!emitted && embeds.isNotEmpty()) {
            emitted = resolveEmbeds(embeds, subtitleCallback, callback)
        }
        return emitted
    }

    private suspend fun emitTrack(
        play: MiruroPlay,
        trackKey: String,
        seen: MutableSet<String>,
        embeds: MutableList<Pair<String, String>>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val track = play.tracks.firstOrNull { it.track == trackKey } ?: return false
        val trackLabel = when (trackKey) {
            "dub" -> "DUB"
            "raw" -> "RAW"
            else -> "SUB"
        }
        var emitted = false
        for (provider in track.providers) {
            val providerName = providerLabel(provider.provider)
            for (server in provider.servers) {
                val headers = mutableMapOf("User-Agent" to MiruroApi.USER_AGENT)
                server.headers?.let { headers.putAll(it) }
                for (stream in server.streams) {
                    val link = stream.url ?: continue
                    if (link.isBlank() || !seen.add(link)) continue
                    val label = buildString {
                        append(trackLabel)
                        append(' ')
                        append(providerName)
                        if (!server.server.equals(providerName, ignoreCase = true)) {
                            append(' ')
                            append(server.server)
                        }
                        stream.quality?.takeIf { it.isNotBlank() }?.let {
                            append(' ')
                            append(it)
                        }
                    }
                    callback(
                        newExtractorLink(
                            source = "Miruro",
                            name = label,
                            url = link,
                            type = if (stream.format == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            quality = qualityOf(stream)
                            this.headers = headers
                        }
                    )
                    emitted = true
                }
                val embedUrl = server.embed?.url?.takeIf { it.isNotBlank() }
                if (embedUrl != null && embeds.size < 6) {
                    embeds.add(embedUrl to (server.headers?.get("Referer") ?: "$mainUrl/"))
                }
            }
            for (subtitle in provider.subtitles) {
                val url = subtitle.file ?: continue
                if (url.isNotBlank()) {
                    subtitleCallback(
                        newSubtitleFile(subtitle.label ?: subtitle.language ?: "English", url) { }
                    )
                }
            }
        }
        return emitted
    }

    private fun qualityOf(stream: MiruroPlayStream): Int {
        val label = stream.quality?.lowercase()
        if (label != null) {
            when {
                label.contains("2160") || label.contains("4k") -> return 2160
                label.contains("1080") -> return 1080
                label.contains("720") || label == "hd" -> return 720
                label.contains("480") || label == "hq" -> return 480
                label.contains("360") || label == "sd" -> return 360
            }
        }
        return stream.resolution?.height ?: -1
    }

    private suspend fun resolveEmbeds(
        embeds: List<Pair<String, String>>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anyLink = false
        val tracking: (ExtractorLink) -> Unit = { link ->
            anyLink = true
            callback(link)
        }
        for ((url, referer) in embeds) {
            try {
                when {
                    url.contains("megaplay") -> {
                        MiruroMegaPlay().getUrl(url, referer, subtitleCallback, tracking)
                    }
                    url.contains("vidwish") -> {
                        MiruroVidWish().getUrl(url, referer, subtitleCallback, tracking)
                    }
                    else -> {
                        try {
                            loadExtractor(url, referer, subtitleCallback, tracking)
                        } catch (e: Exception) {
                            val host = try {
                                java.net.URI(url).host ?: ""
                            } catch (e: Exception) {
                                ""
                            }
                            if (host.isNotEmpty()) {
                                MiruroWebView(host, "https://$host").getUrl(url, referer, subtitleCallback, tracking)
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("Miruro", "embed $url failed: ${e.message}")
            }
        }
        return anyLink
    }
}
