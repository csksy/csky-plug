package com.laddu100.bingr

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// =========================================================================
// BingrAnimeProvider — Anime (separate provider, AniList IDs)
// =========================================================================
// Uses /anime/trending and /anime/search for catalog.
// load() fetches /anime/{id} for metadata (AniList + TMDB cross-ref).
// loadLinks() fetches from 3 anime-specific sources:
//   1. AnimeSalt (MULTI-audio HLS via hianime.filmu.in/animesalt/streams)
//   2. Hikari Sub (subbed streams via hianime.filmu.in/hianime/megaplay?type=sub)
//   3. Hikari Dub (dubbed streams via hianime.filmu.in/hianime/megaplay?type=dub)
// Plus the 8 general Server 1 sources (some anime have TMDB IDs and work there too).
// All anime sources require a JWT token from hianime.filmu.in/token (cached 2.5h).

class BingrAnimeProvider : MainAPI() {
    override var mainUrl = "https://bingr.one"
    override var name = "Bingr Anime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val TAG = "Bingr_Anime"

    // Data URL format: bingrani://{anilistId}/{malId}/{title}
    // malId is needed for Hikari (it uses MAL IDs, not AniList IDs).

    override val mainPage = mainPageOf(
        "trending" to "Trending Anime",
        "action" to "Action Anime",
        "adventure" to "Adventure Anime",
        "comedy" to "Comedy Anime",
        "drama" to "Drama Anime",
        "fantasy" to "Fantasy Anime",
        "scifi" to "Sci-Fi Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "getMainPage: ${request.name} page=$page")
        return try {
            val params = when (request.data) {
                "trending" -> "page=$page"
                "action" -> "genre=Action&sort=POPULARITY_DESC&page=$page"
                "adventure" -> "genre=Adventure&sort=POPULARITY_DESC&page=$page"
                "comedy" -> "genre=Comedy&sort=POPULARITY_DESC&page=$page"
                "drama" -> "genre=Drama&sort=POPULARITY_DESC&page=$page"
                "fantasy" -> "genre=Fantasy&sort=POPULARITY_DESC&page=$page"
                "scifi" -> "genre=Sci-Fi&sort=POPULARITY_DESC&page=$page"
                else -> "page=$page"
            }
            val resp = if (request.data == "trending") {
                BingrApi.animeTrending(page)
            } else {
                BingrApi.animeDiscover(params)
            }
            val items = resp.results.mapNotNull { item ->
                val title = item.title
                if (title.isBlank()) return@mapNotNull null
                val id = item.id
                val malId = item.idMal ?: id
                newAnimeSearchResponse(title, "bingrani://$id/$malId/$title", TvType.Anime) {
                    this.posterUrl = item.poster
                    this.year = item.year?.toIntOrNull()
                }
            }
            Log.d(TAG, "getMainPage: ${request.name} got ${items.size} items")
            newHomePageResponse(request.name, items, hasNext = resp.page < resp.total_pages)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search: '$query'")
        if (query.isBlank()) return emptyList()
        return try {
            val resp = BingrApi.animeSearch(query)
            val results = resp.results.mapNotNull { item ->
                val title = item.title
                if (title.isBlank()) return@mapNotNull null
                val id = item.id
                val malId = item.idMal ?: id
                newAnimeSearchResponse(title, "bingrani://$id/$malId/$title", TvType.Anime) {
                    this.posterUrl = item.poster
                    this.year = item.year?.toIntOrNull()
                }
            }
            Log.d(TAG, "search: got ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load: url=$url")
        val payload = url.substringAfter("bingrani://")
        val parts = payload.split("/")
        if (parts.size < 3) return null
        val anilistId = parts[0].toLongOrNull() ?: return null
        val malId = parts[1].toLongOrNull()
        val title = parts.subList(2, parts.size).joinToString("/")
        Log.d(TAG, "load: anilistId=$anilistId malId=$malId title='$title'")

        return try {
            val detail = BingrApi.getAnimeDetails(anilistId) ?: return null
            val epCount = detail.episodes ?: 12
            val tvType = if (detail.format == "MOVIE") TvType.AnimeMovie else TvType.Anime

            // Generate episodes 1..epCount
            val episodes = (1..epCount).map { epNum ->
                newEpisode("bingrani://$anilistId/$malId/$epNum/${detail.title}") {
                    this.episode = epNum
                    this.name = "Episode $epNum"
                }
            }

            val recommendations = detail.similars.take(15).mapNotNull { rec ->
                val recTitle = rec.title
                if (recTitle.isBlank()) return@mapNotNull null
                val recId = rec.id
                val recMalId = rec.idMal ?: recId
                newAnimeSearchResponse(recTitle, "bingrani://$recId/$recMalId/$recTitle", TvType.Anime) {
                    this.posterUrl = rec.poster
                    this.year = rec.year?.toIntOrNull()
                }
            }

            newAnimeLoadResponse(detail.title, url, tvType) {
                this.posterUrl = detail.poster
                this.backgroundPosterUrl = detail.backdrop
                this.plot = detail.overview
                this.year = detail.year
                this.tags = detail.genres
                this.actors = detail.cast.take(15).map { ActorData(Actor(it.name, it.photo)) }
                this.score = detail.rating?.let { Score.from10(it) }
                this.duration = detail.runtime?.toInt()
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
                // If Hikari Dub is available, add dub episodes too
                if (malId != null) {
                    addEpisodes(DubStatus.Dubbed, episodes)
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
        Log.d(TAG, "loadLinks: data=$data")
        return try {
            val payload = data.substringAfter("bingrani://")
            val parts = payload.split("/")
            if (parts.size < 4) return false
            val anilistId = parts[0].toLongOrNull() ?: return false
            val malId = parts[1].toLongOrNull()
            val episode = parts[2].toIntOrNull() ?: return false
            val title = parts.subList(3, parts.size).joinToString("/")
            Log.d(TAG, "loadLinks: anilistId=$anilistId malId=$malId ep=$episode title='$title'")

            val found = java.util.concurrent.atomic.AtomicBoolean(false)

            // Run all 3 anime sources + 8 general servers in parallel
            coroutineScope {
                // 1. AnimeSalt (MULTI-audio)
                val animeSaltJob = async {
                    try {
                        val resp = BingrApi.getAnimeSaltStreams(title, episode)
                        if (resp != null && resp.sources.isNotEmpty()) {
                            Log.d(TAG, "loadLinks: AnimeSalt returned ${resp.sources.size} sources")
                            for (source in resp.sources) {
                                val label = if (source.label.isNotBlank()) source.label else "AnimeSalt — ${source.quality}"
                                val linkName = "Bingr Anime | $label"
                                emitSource(linkName, source, callback)
                                found.set(true)
                            }
                            for (sub in resp.subtitles) {
                                try { subtitleCallback.invoke(SubtitleFile(sub.lang, sub.url)) } catch (e: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadLinks: AnimeSalt error: ${e.message}")
                    }
                }

                // 2. Hikari Sub
                val hikariSubJob = async {
                    try {
                        val resp = BingrApi.getHikariStreams(malId ?: anilistId, episode, "sub")
                        if (resp != null && resp.sources.isNotEmpty()) {
                            Log.d(TAG, "loadLinks: Hikari Sub returned ${resp.sources.size} sources")
                            for (source in resp.sources) {
                                val label = if (source.label.isNotBlank()) source.label else "Hikari Sub — ${source.quality}"
                                val linkName = "Bingr Anime | $label"
                                emitSource(linkName, source, callback)
                                found.set(true)
                            }
                            for (sub in resp.subtitles) {
                                try { subtitleCallback.invoke(SubtitleFile(sub.lang, sub.url)) } catch (e: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadLinks: Hikari Sub error: ${e.message}")
                    }
                }

                // 3. Hikari Dub
                val hikariDubJob = async {
                    try {
                        val resp = BingrApi.getHikariStreams(malId ?: anilistId, episode, "dub")
                        if (resp != null && resp.sources.isNotEmpty()) {
                            Log.d(TAG, "loadLinks: Hikari Dub returned ${resp.sources.size} sources")
                            for (source in resp.sources) {
                                val label = if (source.label.isNotBlank()) source.label else "Hikari Dub — ${source.quality}"
                                val linkName = "Bingr Anime | $label"
                                emitSource(linkName, source, callback)
                                found.set(true)
                            }
                            for (sub in resp.subtitles) {
                                try { subtitleCallback.invoke(SubtitleFile(sub.lang, sub.url)) } catch (e: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadLinks: Hikari Dub error: ${e.message}")
                    }
                }

                // 4. Also try the 8 general Server 1 sources (some anime have TMDB IDs)
                val generalJobs = BingrApi.SERVER_IDS.map { srv ->
                    async {
                        try {
                            val streamResp = BingrApi.getStream(srv, "anime", anilistId, title, null, null, episode)
                            if (streamResp != null && streamResp.sources.isNotEmpty()) {
                                val serverName = BingrApi.SERVER_NAMES[srv] ?: srv
                                Log.d(TAG, "loadLinks: $serverName ($srv) returned ${streamResp.sources.size} anime sources")
                                for (source in streamResp.sources) {
                                    val label = if (source.label.isNotBlank()) source.label else "${serverName} — ${source.quality}"
                                    val linkName = "Bingr Anime | $serverName — ${source.language} — $label"
                                    emitSource(linkName, source, callback)
                                    found.set(true)
                                }
                                for (sub in streamResp.subtitles) {
                                    try { subtitleCallback.invoke(SubtitleFile(sub.lang, sub.url)) } catch (e: Exception) {}
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadLinks: $srv anime error: ${e.message}")
                        }
                    }
                }

                awaitAll(animeSaltJob, hikariSubJob, hikariDubJob, *generalJobs.toTypedArray())
            }

            Log.d(TAG, "loadLinks: done, found=${found.get()}")
            found.get()
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: ${e.message}")
            false
        }
    }

    private suspend fun emitSource(
        linkName: String,
        source: BingrSource,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = when {
            source.isMP4 -> ExtractorLinkType.VIDEO
            source.type.contains("mp4") -> ExtractorLinkType.VIDEO
            source.type.contains("mpegurl") -> ExtractorLinkType.M3U8
            source.type.contains("dash") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.M3U8
        }
        val quality = parseQuality(source.quality)
        val link = newExtractorLink("Bingr Anime", linkName, source.url, type) {
            this.quality = quality
            if (source.headers.isNotEmpty()) {
                this.headers = source.headers
                source.headers["Referer"]?.let { this.referer = it }
            }
        }
        callback.invoke(link)
    }

    private fun parseQuality(q: String?): Int {
        if (q == null) return Qualities.Unknown.value
        return when {
            q.contains("2160") || q.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            q.contains("1080") -> Qualities.P1080.value
            q.contains("720") -> Qualities.P720.value
            q.contains("480") -> Qualities.P480.value
            q.contains("360") -> Qualities.P360.value
            q.contains("hd", ignoreCase = true) -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }
    }
}
