package com.laddu100

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
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.Score
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

class MiruroProvider : MainAPI() {

    override var mainUrl = MiruroApi.DEFAULT_DOMAIN
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private data class EpisodeEntry(
        val provider: String,
        val episodeId: String,
        val category: String
    )

    private data class EpisodeMeta(
        val title: String?,
        val description: String?,
        val image: String?,
        val airDate: String?,
        val duration: Int?
    )

    companion object {
        private const val TAG = "Miruro"

        // the order the site's own provider enum uses; anything new the api
        // starts returning still works, it just sorts after the known ones
        private val PROVIDER_ORDER = listOf(
            "ally", "bee", "bonk", "bun", "cog", "dune", "moo", "twin",
            "hop", "kiwi", "pewe", "kuz", "nun", "telli"
        )

        private const val CONFIG_TTL = 10 * 60_000L

        @Volatile
        private var providerConfig: Map<String, MiruroProviderConfig>? = null

        @Volatile
        private var configLoadedAt = 0L

        private val uselessTitle = Regex("""^Episode \d+(\.\d+)?$""")
    }

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending",
        "POPULAR" to "Popular",
        "RECENT" to "Recently Updated",
    )

    private val trendingQuery = """
        query (${'$'}page: Int, ${'$'}perPage: Int) {
            Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, sort: TRENDING_DESC) {
                    id
                    title { romaji english native }
                    coverImage { large extraLarge }
                    format
                    seasonYear
                    averageScore
                }
            }
        }
    """.trimIndent()

    private val popularQuery = """
        query (${'$'}page: Int, ${'$'}perPage: Int) {
            Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, sort: POPULARITY_DESC) {
                    id
                    title { romaji english native }
                    coverImage { large extraLarge }
                    format
                    seasonYear
                    averageScore
                }
            }
        }
    """.trimIndent()

    private val recentQuery = """
        query (${'$'}page: Int, ${'$'}perPage: Int) {
            Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, sort: START_DATE_DESC, status: RELEASING) {
                    id
                    title { romaji english native }
                    coverImage { large extraLarge }
                    format
                    seasonYear
                    averageScore
                }
            }
        }
    """.trimIndent()

    private val searchQuery = """
        query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
            Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                    id
                    title { romaji english native }
                    coverImage { large extraLarge }
                    format
                    seasonYear
                    averageScore
                }
            }
        }
    """.trimIndent()

    private val infoQuery = """
        query (${'$'}id: Int) {
            Media(id: ${'$'}id, type: ANIME) {
                id
                idMal
                title { romaji english native }
                description(asHtml: false)
                coverImage { large extraLarge }
                bannerImage
                format
                seasonYear
                episodes
                status
                averageScore
                genres
                streamingEpisodes { title thumbnail }
            }
        }
    """.trimIndent()

    private suspend fun syncDomain() {
        val fresh = FirebaseDomainHelper.getDomain("miruro")
        if (fresh != null) {
            MiruroApi.remoteDomain = fresh
            mainUrl = fresh
        }
    }

    private fun searchResponse(media: AniListMedia): SearchResponse? {
        val id = media.id ?: return null
        val title = media.displayTitle.takeIf { it.isNotBlank() } ?: return null
        val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
        return newAnimeSearchResponse(title, "$mainUrl/info/$id", TvType.Anime) {
            this.posterUrl = poster
            this.year = media.seasonYear
            addDubStatus(dubExist = true, subExist = true)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncDomain()
        val query = when (request.data) {
            "POPULAR" -> popularQuery
            "RECENT" -> recentQuery
            else -> trendingQuery
        }
        val response = parseJson<AniListResponse>(
            AniListApi.query(query, mapOf("page" to page, "perPage" to 24))
        )
        val home = response.data?.page?.media.orEmpty().mapNotNull { searchResponse(it) }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        syncDomain()
        val response = parseJson<AniListResponse>(
            AniListApi.query(
                searchQuery,
                mapOf("search" to query, "page" to 1, "perPage" to 30)
            )
        )
        return response.data?.page?.media.orEmpty().mapNotNull { searchResponse(it) }
    }

    private suspend fun loadProviderConfig(): Map<String, MiruroProviderConfig> {
        val cached = providerConfig
        if (cached != null && System.currentTimeMillis() - configLoadedAt < CONFIG_TTL) {
            return cached
        }
        return try {
            val raw = MiruroApi.pipeRequest("config", emptyMap(), live = false)
            val direct = runCatching {
                parseJson<Map<String, MiruroProviderConfig>>(raw)
            }.getOrDefault(emptyMap())
            val config = (if (direct.isNotEmpty()) direct else {
                runCatching { parseJson<MiruroConfigWrapper>(raw).providers }
                    .getOrNull() ?: emptyMap()
            }).mapKeys { it.key.lowercase() }
            providerConfig = config
            configLoadedAt = System.currentTimeMillis()
            config
        } catch (e: Exception) {
            Log.d(TAG, "config pipe failed, falling back to episodes keys: ${e.message}")
            cached ?: emptyMap()
        }
    }

    // real episode names come from miruro's own info endpoint which carries
    // tmdb and tvdb metadata; anilist's streamingEpisodes act as a second
    // layer, and anything still nameless gets "EP N - AnimeTitle"
    private fun buildEpisodeMetadata(
        info: MiruroInfoResponse?,
        fallback: AniListMedia?
    ): MutableMap<Int, EpisodeMeta> {
        val meta = mutableMapOf<Int, EpisodeMeta>()

        fallback?.streamingEpisodes.orEmpty().forEach { entry ->
            val title = entry.title ?: return@forEach
            val number = Regex("""Episode (\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach
            meta.putIfAbsent(number, EpisodeMeta(title, null, entry.thumbnail, null, null))
        }

        info?.tvdb?.episodes.orEmpty().forEach { entry ->
            val number = entry.number ?: return@forEach
            val current = meta[number]
            meta[number] = EpisodeMeta(
                entry.name ?: current?.title,
                entry.overview ?: current?.description,
                entry.image ?: current?.image,
                entry.aired ?: current?.airDate,
                entry.runtime?.let { it * 60 } ?: current?.duration
            )
        }

        info?.tmdb?.episodes.orEmpty().forEach { entry ->
            val number = entry.number ?: return@forEach
            val current = meta[number]
            meta[number] = EpisodeMeta(
                entry.title?.takeIf { it.isNotBlank() } ?: current?.title,
                entry.description ?: current?.description,
                entry.image ?: current?.image,
                entry.airDate ?: current?.airDate,
                entry.duration ?: current?.duration
            )
        }

        val offset = info?.mappings?.episodeOffset ?: info?.mappings?.tmdbOffset ?: 0
        if (offset != 0) {
            val shifted = mutableMapOf<Int, EpisodeMeta>()
            meta.forEach { (number, entry) ->
                val adjusted = number - offset
                if (adjusted > 0) shifted[adjusted] = entry
            }
            return shifted
        }
        return meta
    }

    private fun episodeTitle(
        number: Int,
        providerTitle: String?,
        meta: EpisodeMeta?,
        animeTitle: String
    ): String {
        val fromMeta = meta?.title?.takeIf { it.isNotBlank() && !uselessTitle.matches(it) }
        val fromProvider = providerTitle?.takeIf { it.isNotBlank() && !uselessTitle.matches(it) }
        return fromMeta ?: fromProvider ?: "EP $number - $animeTitle"
    }

    private fun sortProviders(providers: Set<String>): List<String> {
        val known = PROVIDER_ORDER.filter { it in providers }
        val rest = providers.filter { it !in PROVIDER_ORDER }.sorted()
        return known + rest
    }

    override suspend fun load(url: String): LoadResponse? {
        syncDomain()
        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null

        var media: AniListMedia? = null
        var info: MiruroInfoResponse? = null
        try {
            info = MiruroApi.pipeJson<MiruroInfoResponse>("info/$anilistId", emptyMap())
            media = info?.media
        } catch (e: Exception) {
            Log.d(TAG, "info pipe failed for $anilistId, using anilist directly: ${e.message}")
        }

        if (media == null) {
            media = try {
                parseJson<AniListResponse>(
                    AniListApi.query(infoQuery, mapOf("id" to anilistId))
                ).data?.media
            } catch (e: Exception) {
                Log.d(TAG, "anilist info failed for $anilistId: ${e.message}")
                null
            } ?: return null
        }

        val animeTitle = media.displayTitle.takeIf { it.isNotBlank() } ?: "Unknown"
        val movieMeta = info?.tmdb?.takeIf { it.type == "MOVIE" }?.movie

        val tvType = when {
            movieMeta != null -> TvType.AnimeMovie
            media.format == "MOVIE" -> TvType.AnimeMovie
            media.format == "OVA" || media.format == "ONA" || media.format == "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        val showStatus = when (media.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val config = loadProviderConfig()
        val episodeMeta = buildEpisodeMetadata(info, media)

        // movie entries on miruro are a single episode numbered 1, so the
        // metadata layer only carries one entry for them
        if (movieMeta != null) {
            episodeMeta[1] = EpisodeMeta(
                movieMeta.title ?: movieMeta.originalTitle,
                movieMeta.overview,
                null,
                movieMeta.releaseDate,
                media.duration
            )
        }

        val subByNumber = sortedMapOf<Int, MutableList<EpisodeEntry>>()
        val dubByNumber = sortedMapOf<Int, MutableList<EpisodeEntry>>()
        val metaByNumber = mutableMapOf<Int, MiruroEpisode>()

        fun mergeMeta(number: Int, episode: MiruroEpisode) {
            val current = metaByNumber[number] ?: run {
                metaByNumber[number] = episode
                return
            }
            val betterTitle = current.title.isNullOrBlank() && !episode.title.isNullOrBlank()
            val betterImage = current.image == null && current.thumbnail == null &&
                (episode.image != null || episode.thumbnail != null)
            if (betterTitle || betterImage) {
                metaByNumber[number] = MiruroEpisode(
                    id = episode.id ?: current.id,
                    number = number,
                    title = episode.title ?: current.title,
                    filler = episode.filler ?: current.filler,
                    uncensored = episode.uncensored ?: current.uncensored,
                    image = episode.image ?: current.image,
                    thumbnail = episode.thumbnail ?: current.thumbnail,
                    description = episode.description ?: current.description,
                    duration = episode.duration ?: current.duration,
                    airDate = episode.airDate ?: current.airDate
                )
            }
        }

        var providersPayload: Map<String, MiruroProviderEpisodes> = emptyMap()
        try {
            providersPayload = MiruroApi.pipeJson<MiruroEpisodesResponse>(
                "episodes",
                mapOf("anilistId" to anilistId)
            ).providers?.mapKeys { it.key.lowercase() } ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "episodes fetch failed for $anilistId: ${e.message}")
        }

        // a provider that mirrors its parent (same ids, different host) shows
        // nothing under its own name, so the parent's list gets reused
        fun episodesFor(provider: String): MiruroEpisodeCategories? {
            providersPayload[provider]?.episodes?.let { direct ->
                val hasSub = !direct.sub.isNullOrEmpty() || !direct.ssub.isNullOrEmpty()
                if (hasSub || !direct.dub.isNullOrEmpty()) return direct
            }
            val parent = config[provider]?.parent?.lowercase() ?: return null
            if (parent == provider) return null
            return providersPayload[parent]?.episodes
        }

        // sub and ssub are separate watch options on the site and each needs
        // its own sources query at link time
        fun variantsFor(provider: String, listedUnder: String): List<String> {
            val caps = config[provider]?.capabilities
            if (caps == null) {
                return when (listedUnder) {
                    "ssub" -> listOf("ssub")
                    else -> listOf("sub")
                }
            }
            val variants = config[provider]?.variantOrder.orEmpty()
                .filter { it == "sub" || it == "ssub" }
                .filter { if (it == "sub") caps.sub == true else caps.ssub == true }
                .toMutableList()
            if (caps.ssub == true && "ssub" !in variants) variants.add("ssub")
            if (caps.sub == true && "sub" !in variants) variants.add("sub")
            return variants
        }

        // the site filters the episodes payload against its config map, so an
        // unknown provider key in the payload is ignored when config is live
        val orderedProviders = sortProviders(
            providersPayload.keys.filter { config.isEmpty() || it in config }.toSet()
        )

        for (provider in orderedProviders) {
            if (config[provider]?.visible == false) continue
            val categories = episodesFor(provider) ?: continue

            val listedUnder = when {
                !categories.sub.isNullOrEmpty() -> "sub"
                !categories.ssub.isNullOrEmpty() -> "ssub"
                else -> "sub"
            }

            val variants = variantsFor(provider, listedUnder)
            val subEpisodes = categories.sub?.takeIf { it.isNotEmpty() }
                ?: categories.ssub
                ?: emptyList()

            subEpisodes.forEach { episode ->
                val number = episode.number ?: return@forEach
                val id = episode.id ?: return@forEach
                if (variants.isNotEmpty()) {
                    subByNumber.getOrPut(number) { mutableListOf() }.apply {
                        variants.forEach { variant -> add(EpisodeEntry(provider, id, variant)) }
                    }
                }
                mergeMeta(number, episode)
            }

            categories.dub.orEmpty().forEach { episode ->
                val number = episode.number ?: return@forEach
                val id = episode.id ?: return@forEach
                dubByNumber.getOrPut(number) { mutableListOf() }
                    .add(EpisodeEntry(provider, id, "dub"))
                mergeMeta(number, episode)
            }
        }

        fun buildEpisodes(byNumber: Map<Int, List<EpisodeEntry>>): List<Episode> {
            return byNumber.map { (number, entries) ->
                val providerEpisode = metaByNumber[number]
                val meta = episodeMeta[number]
                val entryData = buildString {
                    append(anilistId)
                    entries.forEach { entry ->
                        append('|').append(entry.provider)
                            .append(':').append(entry.episodeId)
                            .append(':').append(entry.category)
                    }
                }
                newEpisode(entryData) {
                    this.name = episodeTitle(number, providerEpisode?.title, meta, animeTitle)
                    this.episode = number
                    this.description = meta?.description ?: providerEpisode?.description
                    this.posterUrl = meta?.image
                        ?: providerEpisode?.image
                        ?: providerEpisode?.thumbnail
                }
            }
        }

        val subEpisodes = buildEpisodes(subByNumber)
        val dubEpisodes = buildEpisodes(dubByNumber)

        Log.d(TAG, "load $animeTitle: ${subEpisodes.size} sub / ${dubEpisodes.size} dub episodes")

        return newAnimeLoadResponse(animeTitle, url, tvType) {
            this.posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            this.backgroundPosterUrl = media.bannerImage
            this.year = media.seasonYear
            this.plot = media.description?.replace(Regex("<[^>]*>"), "")
            this.tags = media.genres.orEmpty()
            media.averageScore?.let { score = Score.from10((it / 10).toString()) }
            this.showStatus = showStatus
            addAniListId(anilistId)
            media.idMal?.let { addMalId(it) }
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
        if (parts.size < 2) return false

        val anilistId = parts[0].toIntOrNull()
        val tasks = parts.drop(1).mapNotNull { entry ->
            val pieces = entry.split(":")
            if (pieces.size < 3) return@mapNotNull null
            val provider = pieces.first()
            val category = pieces.last()
            val episodeId = pieces.drop(1).dropLast(1).joinToString(":")
            if (provider.isEmpty() || episodeId.isEmpty()) null
            else Triple(provider, episodeId, category)
        }
        if (tasks.isEmpty()) return false

        val seenUrls = ConcurrentHashMap.newKeySet<String>()
        val seenSubtitles = ConcurrentHashMap.newKeySet<String>()

        val results = coroutineScope {
            tasks.map { (provider, episodeId, category) ->
                async {
                    loadProviderLinks(
                        provider, episodeId, category, anilistId,
                        seenUrls, seenSubtitles, subtitleCallback, callback
                    )
                }
            }.awaitAll()
        }
        return results.any { it }
    }

    private fun variantLabel(category: String): String = when (category) {
        "ssub" -> "Sub s-sub"
        "dub" -> "Dub"
        else -> "Sub h-sub"
    }

    private fun qualityNumber(stream: MiruroStream): Int {
        val raw = stream.quality
        if (raw != null) {
            when {
                raw.contains("2160") || raw.contains("4K", true) -> return 2160
                raw.contains("1080") -> return 1080
                raw.contains("720") -> return 720
                raw.contains("480") -> return 480
                raw.contains("360") -> return 360
            }
        }
        return stream.height ?: -1
    }

    private suspend fun loadProviderLinks(
        provider: String,
        episodeId: String,
        category: String,
        anilistId: Int?,
        seenUrls: MutableSet<String>,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val displayName = provider.replaceFirstChar { it.uppercase() }
        val label = variantLabel(category)
        var found = false

        val response = try {
            val query = buildMap {
                put("episodeId", episodeId)
                put("provider", provider)
                put("category", category)
                anilistId?.let { put("anilistId", it) }
            }
            MiruroApi.pipeJson<MiruroSourcesResponse>("sources", query)
        } catch (e: Exception) {
            Log.e(TAG, "$displayName ($category) sources failed: ${e.message}")
            return false
        }

        val streams = response.streams.orEmpty().filter { !it.streamUrl.isNullOrBlank() }
        val referer = streams.firstNotNullOfOrNull { s ->
            s.referer?.takeIf { r -> r.isNotBlank() }
        } ?: "${MiruroApi.workingDomain()}/"

        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to MiruroApi.USER_AGENT
        )

        fun emitSubtitleTracks() {
            (response.subtitles.orEmpty() + response.captions.orEmpty()).forEach { subtitle ->
                val subUrl = subtitle.subtitleUrl ?: return@forEach
                if (!seenSubtitles.add(subUrl)) return@forEach
                // routing through miruro's own proxy is what the site player
                // does; it keeps referer-locked cdns working without headers
                val proxied = MiruroProxy.subtitleUrl(subUrl, referer)
                subtitleCallback.invoke(SubtitleFile(subtitle.subtitleLabel, proxied))
            }
        }

        for (stream in streams) {
            val streamUrl = stream.streamUrl ?: continue
            if (!seenUrls.add(streamUrl)) continue

            val quality = stream.qualityLabel
            val qualityInt = qualityNumber(stream).takeIf { it > 0 } ?: -1
            val fansubTag = stream.fansub?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""

            when {
                stream.isHls -> {
                    callback.invoke(
                        newExtractorLink(
                            source = "Miruro",
                            name = "$displayName $label$fansubTag $quality",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = qualityInt
                            this.headers = headers
                        }
                    )
                    found = true
                }

                stream.isMp4 -> {
                    callback.invoke(
                        newExtractorLink(
                            source = "Miruro",
                            name = "$displayName $label$fansubTag $quality",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = qualityInt
                            this.headers = headers
                        }
                    )
                    found = true
                }

                stream.isEmbed -> {
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
                                } catch (e: Exception) {
                                    Log.d(TAG, "loadExtractor miss for $streamUrl: ${e.message}")
                                }
                                if (!loaded) {
                                    MiruroWebViewExtractor.forUrl(streamUrl)
                                        .getUrl(streamUrl, referer, subtitleCallback, embedCallback)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "$displayName embed failed: ${e.message}")
                    }
                    embedLinks.forEach { link ->
                        // extractor names carry no provider identity, so the
                        // miruro source and variant get prefixed onto them
                        val renamed = if (
                            link.name.contains(displayName, ignoreCase = true) ||
                            link.name.contains(label, ignoreCase = true)
                        ) {
                            link.name
                        } else {
                            "$displayName $label ${link.name}"
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
                    if (streamUrl.contains(".m3u8")) {
                        callback.invoke(
                            newExtractorLink(
                                source = "Miruro",
                                name = "$displayName $label$fansubTag $quality",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = qualityInt
                                this.headers = headers
                            }
                        )
                        found = true
                    }
                }
            }
        }

        // direct download links (ally/kiwi/moo style providers); pahe.win pages
        // are site-downloader specific and not playable, so they get skipped
        response.download?.takeIf { it.isNotBlank() && !it.contains("pahe.win") }?.let { downloadUrl ->
            if (seenUrls.add(downloadUrl)) {
                callback.invoke(
                    newExtractorLink(
                        source = "Miruro",
                        name = "$displayName $label DL",
                        url = downloadUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = -1
                        this.headers = headers
                    }
                )
                found = true
            }
        }

        // softsub tracks arrive alongside any category and are harmless for
        // hardsub/dub playback, so they always get emitted
        emitSubtitleTracks()

        return found
    }
}
