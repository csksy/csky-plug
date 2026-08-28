package com.laddu100

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

class Miruro : MainAPI() {

    companion object {
        var context: Context? = null

        private data class CachedEps(
            val sub: List<Episode>,
            val dub: List<Episode>,
            val timestamp: Long
        )
        private val epsCache = ConcurrentHashMap<Int, CachedEps>()
        private val EPS_CACHE_TTL = 300_000L
    }

    override var mainUrl = MIRURO_DEFAULT_DOMAIN
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // the order the site itself lists sources in; anything new the api returns
    // is appended after these so unknown providers still work
    private val providerOrder = listOf(
        "kiwi", "pewe", "bonk", "bee", "ally", "moo",
        "hop", "nun", "bun", "twin", "cog", "telli"
    )

    private fun providerDisplayName(provider: String): String =
        provider.replaceFirstChar { it.uppercase() }

    private fun sortProviders(providers: Map<String, MiruroProvider>): List<String> {
        val known = providerOrder.filter { providers.containsKey(it) }
        val rest = providers.keys.filter { it !in providerOrder }.sorted()
        return known + rest
    }

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending",
        "POPULAR" to "Popular",
        "RECENT" to "Recently Updated",
    )

    private suspend fun syncDomain() {
        val fresh = FirebaseDomainHelper.getDomain("miruro")
        if (fresh != null) {
            mainUrl = fresh
            MiruroCloudflare.setWorkingDomain(fresh)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncDomain()
        val query = when (request.data) {
            "TRENDING" -> TRENDING_QUERY
            "POPULAR" -> POPULAR_QUERY
            "RECENT" -> RECENT_QUERY
            else -> TRENDING_QUERY
        }
        val variables = mapOf("page" to page, "perPage" to 20)
        val responseText = anilistQuery(query, variables)
        val response = parseJson<AniListResponse>(responseText)
        val mediaList = response.data?.Page?.media ?: emptyList()

        val home = mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            newAnimeSearchResponse(title, "$mainUrl/info/$id", TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(dubExist = true, subExist = true)
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        syncDomain()
        val variables = mapOf<String, Any?>("search" to query, "page" to 1, "perPage" to 24)
        val responseText = anilistQuery(SEARCH_QUERY, variables)
        val response = parseJson<AniListResponse>(responseText)
        val mediaList = response.data?.Page?.media ?: emptyList()

        return mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            newAnimeSearchResponse(title, "$mainUrl/info/$id", TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(dubExist = true, subExist = true)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        syncDomain()
        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        val infoText = anilistQuery(INFO_QUERY, mapOf("id" to anilistId))
        val infoResponse = parseJson<AniListResponse>(infoText)
        val media = infoResponse.data?.Media ?: return null

        val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
        val bannerUrl = media.bannerImage
        val plot = media.description?.replace(Regex("<[^>]*>"), "")
        val year = media.seasonYear
        val tags = media.genres ?: emptyList()
        val animeScore = media.averageScore

        val tvType = when (media.format) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        val showStatus = when (media.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val cached = epsCache[anilistId]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < EPS_CACHE_TTL) {
            return buildLoadResponse(
                url, title, posterUrl, bannerUrl, plot, year, tags, animeScore,
                tvType, showStatus, anilistId, media.idMal, cached.sub, cached.dub
            )
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        try {
            val episodesJson = miruroPipeRequest("episodes", mapOf("anilistId" to anilistId))
            val episodesData = parseJson<MiruroEpisodesResponse>(episodesJson)
            val providers = episodesData.providers ?: emptyMap()

            // anilist episode lists come newest-first with the number inside the
            // title ("Episode 12 - Name"), so index them by parsed number
            val anilistEpMeta = mutableMapOf<Int, Pair<String, String?>>()
            media.streamingEpisodes?.forEach { se ->
                val t = se.title ?: return@forEach
                val num = Regex("""Episode (\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach
                anilistEpMeta.putIfAbsent(num, t to se.thumbnail)
            }

            val orderedProviders = sortProviders(providers)

            // sub tab covers both sub (hardsub) and ssub (softsub) entries
            data class SubEntry(val provider: String, val encodedId: String, val category: String)

            val subByNumber = sortedMapOf<Int, MutableList<SubEntry>>()
            val epMetaByNumber = mutableMapOf<Int, MiruroEpisode>()
            val dubByNumber = sortedMapOf<Int, MutableList<SubEntry>>()

            // keep the first entry per number but swap it out when a later one
            // carries a title or image the first lacked
            fun mergeMeta(num: Int, ep: MiruroEpisode) {
                val current = epMetaByNumber[num]
                if (current == null) {
                    epMetaByNumber[num] = ep
                    return
                }
                val betterTitle = current.title.isNullOrBlank() && !ep.title.isNullOrBlank()
                val betterImage = (current.image == null && current.thumbnail == null) &&
                    (ep.image != null || ep.thumbnail != null)
                if (betterTitle || betterImage) {
                    epMetaByNumber[num] = MiruroEpisode(
                        id = ep.id ?: current.id,
                        number = num,
                        title = ep.title ?: current.title,
                        isFiller = ep.isFiller ?: current.isFiller,
                        filler = ep.filler ?: current.filler,
                        image = ep.image ?: current.image,
                        thumbnail = ep.thumbnail ?: current.thumbnail,
                        description = ep.description ?: current.description,
                        duration = ep.duration ?: current.duration,
                        airDate = ep.airDate ?: current.airDate
                    )
                }
            }

            for (provName in orderedProviders) {
                val prov = providers[provName] ?: continue
                val eps = prov.episodes ?: continue

                fun collect(target: MutableMap<Int, MutableList<SubEntry>>, category: String) {
                    (if (category == "dub") eps.dub else if (category == "ssub") eps.ssub else eps.sub)
                        ?.forEach { ep ->
                            val num = ep.number ?: return@forEach
                            val id = ep.id ?: return@forEach
                            target.getOrPut(num) { mutableListOf() }
                                .add(SubEntry(provName, id, category))
                            mergeMeta(num, ep)
                        }
                }

                collect(subByNumber, "sub")
                collect(subByNumber, "ssub")
                collect(dubByNumber, "dub")
            }

            fun titleFor(num: Int): String {
                val providerTitle = epMetaByNumber[num]?.title?.takeIf { it.isNotBlank() }
                if (providerTitle != null) return providerTitle
                anilistEpMeta[num]?.let { return it.first }
                return "Episode $num"
            }

            fun thumbnailFor(num: Int): String? {
                val providerImage = epMetaByNumber[num]?.let { it.image ?: it.thumbnail }
                return providerImage ?: anilistEpMeta[num]?.second
            }

            for ((num, entries) in subByNumber) {
                val parts = mutableListOf("sub", anilistId.toString())
                entries.forEach { e -> parts.add("${e.provider}:${e.encodedId}:${e.category}") }
                subEpisodes.add(newEpisode(parts.joinToString("|")) {
                    this.name = titleFor(num)
                    this.episode = num
                    this.description = epMetaByNumber[num]?.description
                    this.posterUrl = thumbnailFor(num)
                })
            }

            for ((num, entries) in dubByNumber) {
                val parts = mutableListOf("dub", anilistId.toString())
                entries.forEach { e -> parts.add("${e.provider}:${e.encodedId}:dub") }
                dubEpisodes.add(newEpisode(parts.joinToString("|")) {
                    this.name = titleFor(num)
                    this.episode = num
                    this.description = epMetaByNumber[num]?.description
                    this.posterUrl = thumbnailFor(num)
                })
            }

            if (subEpisodes.isNotEmpty() || dubEpisodes.isNotEmpty()) {
                epsCache[anilistId] = CachedEps(
                    sub = subEpisodes.toList(),
                    dub = dubEpisodes.toList(),
                    timestamp = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e("Miruro", "episodes fetch failed for $anilistId: ${e.message}")
            epsCache.remove(anilistId)
        }

        Log.d(
            "Miruro",
            "load $title: ${subEpisodes.size} sub / ${dubEpisodes.size} dub episodes"
        )

        return buildLoadResponse(
            url, title, posterUrl, bannerUrl, plot, year, tags, animeScore,
            tvType, showStatus, anilistId, media.idMal, subEpisodes, dubEpisodes
        )
    }

    private suspend fun buildLoadResponse(
        url: String,
        title: String,
        posterUrl: String?,
        bannerUrl: String?,
        plot: String?,
        year: Int?,
        tags: List<String>,
        animeScore: Int?,
        tvType: TvType,
        showStatus: ShowStatus?,
        anilistId: Int,
        malId: Int?,
        subEpisodes: List<Episode>,
        dubEpisodes: List<Episode>
    ): LoadResponse {
        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = bannerUrl
            this.year = year
            this.plot = plot
            this.tags = tags
            if (animeScore != null) this.score = Score.from10((animeScore / 10).toString())
            this.showStatus = showStatus
            addAniListId(anilistId)
            malId?.let { addMalId(it) }
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
        val parts = data.split("|")
        if (parts.size < 3) return false

        val dubOrSub = parts[0]
        val anilistId = parts[1].toIntOrNull()
        val providerEntries = parts.drop(2)

        val seenUrls = ConcurrentHashMap.newKeySet<String>()
        val seenSubs = ConcurrentHashMap.newKeySet<String>()

        val tasks = providerEntries.mapNotNull { entry ->
            val colonParts = entry.split(":")
            when {
                colonParts.size >= 3 -> {
                    val provider = colonParts[0]
                    val category = colonParts.last()
                    val episodeId = colonParts.drop(1).dropLast(1).joinToString(":")
                    if (provider.isEmpty() || episodeId.isEmpty() || category.isEmpty()) null
                    else Triple(provider, episodeId, category)
                }
                colonParts.size == 2 -> Triple(colonParts[0], colonParts[1], dubOrSub)
                else -> null
            }
        }

        val results = coroutineScope {
            tasks.map { (provider, episodeId, category) ->
                async {
                    processProvider(
                        provider, episodeId, category, anilistId,
                        seenUrls, seenSubs, subtitleCallback, callback
                    )
                }
            }.awaitAll()
        }
        return results.any { it }
    }

    private fun categoryLabel(category: String): String = when (category) {
        "ssub" -> "Softsub"
        "dub" -> "Dub"
        else -> "Hardsub"
    }

    private suspend fun processProvider(
        provider: String,
        episodeId: String,
        category: String,
        anilistId: Int?,
        seenUrls: MutableSet<String>,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val providerName = providerDisplayName(provider)
        val variant = categoryLabel(category)

        try {
            val queryMap = mutableMapOf<String, Any>(
                "episodeId" to episodeId,
                "provider" to provider,
                "category" to category
            )
            if (anilistId != null) {
                queryMap["anilistId"] = anilistId
            }

            val sourcesJson = miruroPipeRequest("sources", queryMap)
            val sourcesData = parseJson<MiruroSourcesResponse>(sourcesJson)
            val streams = sourcesData.streams ?: return false

            var found = false

            fun emitSubtitles() {
                for (sub in sourcesData.subtitles.orEmpty() + sourcesData.captions.orEmpty()) {
                    val subUrl = sub.subtitleUrl ?: continue
                    if (subUrl.isBlank()) continue
                    if (!seenSubs.add(subUrl)) continue
                    subtitleCallback.invoke(SubtitleFile(sub.subtitleLabel, subUrl))
                }
            }

            fun linkBase(stream: MiruroStream): String {
                val fansubTag = stream.fansub?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
                return "$providerName $variant$fansubTag"
            }

            fun qualityOf(stream: MiruroStream): String {
                stream.quality?.let { return it }
                stream.label?.let { return it }
                stream.resolution?.height?.let { return "${it}p" }
                return "Auto"
            }

            for (stream in streams) {
                val streamUrl = stream.url ?: continue
                if (streamUrl.isBlank()) continue
                if (!seenUrls.add(streamUrl)) continue

                val referer = stream.referer ?: "${MiruroCloudflare.getWorkingDomain()}/"
                val qualityLabel = qualityOf(stream)

                when (stream.type?.lowercase()) {
                    "hls" -> {
                        callback.invoke(
                            newExtractorLink(
                                source = "Miruro",
                                name = "${linkBase(stream)} $qualityLabel",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = qualityFromString(stream.quality)
                                    .takeIf { it > 0 } ?: stream.resolution?.height ?: -1
                                this.headers = mapOf(
                                    "Referer" to referer,
                                    "User-Agent" to CF_USER_AGENT
                                )
                            }
                        )
                        found = true
                    }
                    "mp4" -> {
                        callback.invoke(
                            newExtractorLink(
                                source = "Miruro",
                                name = "${linkBase(stream)} MP4 $qualityLabel",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = qualityFromString(stream.quality)
                                    .takeIf { it > 0 } ?: stream.resolution?.height ?: -1
                                this.headers = mapOf(
                                    "Referer" to referer,
                                    "User-Agent" to CF_USER_AGENT
                                )
                            }
                        )
                        found = true
                    }
                    "embed" -> {
                        // collect extractor output first so the links can be re-emitted
                        // with the miruro provider name in front
                        val embedBase = "$providerName $variant"
                        val embedLinks = mutableListOf<ExtractorLink>()
                        val embedCallback: (ExtractorLink) -> Unit = { link -> embedLinks.add(link) }
                        try {
                            when {
                                streamUrl.contains("megaplay") ->
                                    MiruroMegaPlay().getUrl(streamUrl, referer, subtitleCallback, embedCallback)
                                streamUrl.contains("vidwish") ->
                                    MiruroVidWish().getUrl(streamUrl, referer, subtitleCallback, embedCallback)
                                else -> {
                                    var loaded = false
                                    try {
                                        loaded = loadExtractor(streamUrl, referer, subtitleCallback, embedCallback)
                                    } catch (_: Exception) {}
                                    if (!loaded) {
                                        val host = try {
                                            java.net.URL(streamUrl).host
                                        } catch (_: Exception) { "" }
                                        if (host.isNotEmpty()) {
                                            MiruroWebView(host, "https://$host")
                                                .getUrl(streamUrl, referer, subtitleCallback, embedCallback)
                                            loaded = embedLinks.isNotEmpty()
                                        }
                                    }
                                    found = found || loaded || embedLinks.isNotEmpty()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Miruro", "$providerName embed failed: ${e.message}")
                        }
                        for (link in embedLinks) {
                            val renamed = if (link.name.contains(embedBase, ignoreCase = true)) {
                                link.name
                            } else {
                                "$embedBase ${link.name}"
                            }
                            callback.invoke(
                                newExtractorLink(
                                    source = "Miruro",
                                    name = renamed,
                                    url = link.url,
                                    type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = link.quality
                                    this.headers = link.headers
                                    this.referer = link.referer
                                    this.extractorData = link.extractorData
                                }
                            )
                            found = true
                        }
                    }
                    else -> {
                        // untyped urls are almost always playable hls
                        if (streamUrl.contains(".m3u8")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "Miruro",
                                    name = "${linkBase(stream)} $qualityLabel",
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.quality = qualityFromString(stream.quality)
                                        .takeIf { it > 0 } ?: stream.resolution?.height ?: -1
                                    this.headers = mapOf(
                                        "Referer" to referer,
                                        "User-Agent" to CF_USER_AGENT
                                    )
                                }
                            )
                            found = true
                        }
                    }
                }
            }

            // softsub tracks arrive separately from the video, emit them regardless
            // of category since hardsub/dub responses normally carry none
            emitSubtitles()

            return found
        } catch (e: Exception) {
            Log.e("Miruro", "$providerName ($category) failed: ${e.message}")
            return false
        }
    }
}
