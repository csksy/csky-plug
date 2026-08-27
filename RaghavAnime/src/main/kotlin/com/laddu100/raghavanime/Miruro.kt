package com.laddu100.raghavanime

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
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.Score
import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

class Miruro : MainAPI() {

    companion object {
        // Set by RaghavAnimePlugin.load(context) - needed for WebView-based Cloudflare bypass
        var context: Context? = null

        // Episode list cache: anilistId -> (subEps, dubEps, timestamp)
        // Avoids re-triggering Cloudflare warm-up when switching between episodes
        private data class CachedEps(
            val sub: List<Episode>,
            val dub: List<Episode>,
            val timestamp: Long
        )
        private val epsCache = ConcurrentHashMap<Int, CachedEps>()
        private val EPS_CACHE_TTL = 300_000L // 5 minutes
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

    // Full provider list - tried in order for episodes & sources.
    // Expanded to include all known Miruro providers for maximum source coverage.
    private val providerOrder = listOf(
        "kiwi", "pewe", "bonk", "bee", "ally", "hop",
        "moo", "nun", "bun", "twin", "cog",
        "mega", "nova", "wave", "zen", "flux"
    )

    private val providerDisplayNames = mapOf(
        "kiwi" to "AnimePahe",
        "pewe" to "AniDB",
        "bonk" to "AnimeDao",
        "bee"  to "AniKoto",
        "ally" to "AllManga",
        "hop"  to "KickAssAnime",
        "moo"  to "AnimeGG",
        "nun"  to "Nun",
        "bun"  to "Bun",
        "twin" to "Twin",
        "cog"  to "Cog",
        "mega" to "MegaAnime",
        "nova" to "Nova",
        "wave" to "Wave",
        "zen"  to "Zen",
        "flux" to "Flux"
    )

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending",
        "POPULAR" to "Popular",
        "RECENT" to "Recently Updated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("RaghavAnime", "[Miruro] getMainPage: page=$page name='${request.name}'")
        mainUrl = FirebaseDomainHelper.getDomain("miruro") ?: mainUrl
        MiruroCloudflare.setWorkingDomain(mainUrl)
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
        Log.d("RaghavAnime", "[Miruro] getMainPage: parsed ${mediaList.size} media items for '${request.name}'")

        val home = mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            newAnimeSearchResponse(title, "$mainUrl/info/$id/${toSlug(title)}", TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
            }
        }
        Log.d("RaghavAnime", "[Miruro] getMainPage: '${request.name}' -> ${home.size} items")
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("RaghavAnime", "[Miruro] search: query='$query'")
        mainUrl = FirebaseDomainHelper.getDomain("miruro") ?: mainUrl
        MiruroCloudflare.setWorkingDomain(mainUrl)
        val variables = mapOf<String, Any?>("search" to query, "page" to 1, "perPage" to 20)
        val responseText = anilistQuery(SEARCH_QUERY, variables)
        val response = parseJson<AniListResponse>(responseText)
        val mediaList = response.data?.Page?.media ?: emptyList()
        Log.d("RaghavAnime", "[Miruro] search: parsed ${mediaList.size} results for '$query'")

        return mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            newAnimeSearchResponse(title, "$mainUrl/info/$id/${toSlug(title)}", TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("RaghavAnime", "[Miruro] load: url=$url")
        mainUrl = FirebaseDomainHelper.getDomain("miruro") ?: mainUrl
        MiruroCloudflare.setWorkingDomain(mainUrl)
        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        if (anilistId == null) {
            Log.e("RaghavAnime", "[Miruro] load: no anilist id found in url: $url")
            return null
        }

        val infoText = anilistQuery(INFO_QUERY, mapOf("id" to anilistId))
        val infoResponse = parseJson<AniListResponse>(infoText)
        val media = infoResponse.data?.Media
        if (media == null) {
            Log.e("RaghavAnime", "[Miruro] load: AniList returned no Media for id=$anilistId")
            return null
        }

        val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
        val bannerUrl = media.bannerImage
        val plot = media.description?.replace(Regex("<[^>]*>"), "")
        val year = media.seasonYear
        val tags = media.genres ?: emptyList()
        val animeScore = media.averageScore

        val tvType = when (media.format) {
            "MOVIE" -> TvType.Anime
            "OVA", "ONA" -> TvType.OVA
            else -> TvType.Anime
        }
        val showStatus = when (media.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val cached = epsCache[anilistId]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < EPS_CACHE_TTL) {
            Log.d("RaghavAnime", "[Miruro] load: epsCache hit for anilistId=$anilistId")
            return newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                if (animeScore != null) this.score = Score.from10((animeScore / 10).toString())
                this.showStatus = showStatus
                addAniListId(anilistId)
                if (cached.sub.isNotEmpty()) addEpisodes(DubStatus.Subbed, cached.sub)
                if (cached.dub.isNotEmpty()) addEpisodes(DubStatus.Dubbed, cached.dub)
            }
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        try {
            val episodesJson = miruroPipeRequest("episodes", mapOf("anilistId" to anilistId))
            val episodesData = parseJson<MiruroEpisodesResponse>(episodesJson)
            val providers = episodesData.providers ?: emptyMap()
            Log.d("RaghavAnime", "[Miruro] load: episodes pipe returned ${providers.size} providers for anilistId=$anilistId")

            var bestSubProvider: String? = null
            var bestSubCount = 0
            for (provName in providerOrder) {
                val prov = providers[provName] ?: continue
                val subCount = prov.episodes?.sub?.size ?: 0
                val ssubCount = prov.episodes?.ssub?.size ?: 0
                val count = maxOf(subCount, ssubCount)
                if (count > bestSubCount) { bestSubCount = count; bestSubProvider = provName }
            }
            // Also check providers not in our predefined order
            if (bestSubProvider == null || bestSubCount == 0) {
                for ((provName, prov) in providers) {
                    val subCount = prov.episodes?.sub?.size ?: 0
                    val ssubCount = prov.episodes?.ssub?.size ?: 0
                    val count = maxOf(subCount, ssubCount)
                    if (count > bestSubCount) { bestSubCount = count; bestSubProvider = provName }
                }
            }
            Log.d("RaghavAnime", "[Miruro] load: best sub provider=$bestSubProvider ($bestSubCount eps)")
            if (bestSubProvider != null) {
                val epList = providers[bestSubProvider]!!.episodes!!.let { it.sub ?: it.ssub } ?: emptyList()
                Log.d("RaghavAnime", "[Miruro] load: building sub episodes from '$bestSubProvider' (${epList.size} eps)")
                epList.forEach { ep ->
                    val epNum = ep.number ?: return@forEach

                    val parts = mutableListOf("sub", anilistId.toString())
                    for (provName in providerOrder) {
                        val provEps = providers[provName]?.episodes ?: continue
                        val subMatch = provEps.sub?.firstOrNull { it.number == epNum }
                        val ssubMatch = provEps.ssub?.firstOrNull { it.number == epNum }
                        if (subMatch?.id != null) {
                            parts.add("$provName:${subMatch.id}:sub")
                        } else if (ssubMatch?.id != null) {
                            parts.add("$provName:${ssubMatch.id}:ssub")
                        }
                    }
                    // Also check providers not in predefined order
                    for ((provName, prov) in providers) {
                        if (provName in providerOrder) continue
                        val provEps = prov.episodes ?: continue
                        val subMatch = provEps.sub?.firstOrNull { it.number == epNum }
                        val ssubMatch = provEps.ssub?.firstOrNull { it.number == epNum }
                        if (subMatch?.id != null) {
                            parts.add("$provName:${subMatch.id}:sub")
                        } else if (ssubMatch?.id != null) {
                            parts.add("$provName:${ssubMatch.id}:ssub")
                        }
                    }
                    subEpisodes.add(newEpisode(parts.joinToString("|")) {
                        this.name = ep.title ?: "Episode $epNum"
                        this.episode = epNum
                        this.description = ep.description
                        this.posterUrl = ep.image
                    })
                }
            }

            var bestDubProvider: String? = null
            var bestDubCount = 0
            for (provName in providerOrder) {
                val count = providers[provName]?.episodes?.dub?.size ?: 0
                if (count > bestDubCount) { bestDubCount = count; bestDubProvider = provName }
            }
            // Also check providers not in our predefined order
            if (bestDubProvider == null || bestDubCount == 0) {
                for ((provName, prov) in providers) {
                    val count = prov.episodes?.dub?.size ?: 0
                    if (count > bestDubCount) { bestDubCount = count; bestDubProvider = provName }
                }
            }
            Log.d("RaghavAnime", "[Miruro] load: best dub provider=$bestDubProvider ($bestDubCount eps)")
            if (bestDubProvider != null) {
                val dubList = providers[bestDubProvider]!!.episodes!!.dub!!
                Log.d("RaghavAnime", "[Miruro] load: building dub episodes from '$bestDubProvider' (${dubList.size} eps)")
                dubList.forEach { ep ->
                    val epNum = ep.number ?: return@forEach

                    val parts = mutableListOf("dub", anilistId.toString())
                    for (provName in providerOrder) {
                        val provEps = providers[provName]?.episodes ?: continue

                        val dubMatch = provEps.dub?.firstOrNull { it.number == epNum }
                        if (dubMatch?.id != null) {
                            parts.add("$provName:${dubMatch.id}:dub")
                        }
                    }
                    // Also check providers not in predefined order
                    for ((provName, prov) in providers) {
                        if (provName in providerOrder) continue
                        val provEps = prov.episodes ?: continue
                        val dubMatch = provEps.dub?.firstOrNull { it.number == epNum }
                        if (dubMatch?.id != null) {
                            parts.add("$provName:${dubMatch.id}:dub")
                        }
                    }

                    if (parts.size > 2) {
                        dubEpisodes.add(newEpisode(parts.joinToString("|")) {
                            this.name = ep.title ?: "Episode $epNum"
                            this.episode = epNum
                            this.description = ep.description
                            this.posterUrl = ep.image
                        })
                    }
                }
            }

            if (subEpisodes.isNotEmpty() || dubEpisodes.isNotEmpty()) {
                epsCache[anilistId] = CachedEps(
                    sub = subEpisodes.toList(),
                    dub = dubEpisodes.toList(),
                    timestamp = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Miruro] load: episodes fetch failed for anilistId=$anilistId: ${e.message}")
            epsCache.remove(anilistId)
            e.message
        }

        // Fallback: when the pipe is unavailable (Cloudflare blocking it), resolve
        // episodes directly through MegaPlay's anilist endpoint:
        //   https://megaplay.buzz/stream/ani/{anilistId}/{epNum}/{sub|dub}
        // This needs no Cloudflare bypass at all and always carries sub+dub.
        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
            val epCount = media.episodes
                ?: media.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }
                ?: 1
            Log.d("RaghavAnime", "[Miruro] load: no episodes from pipe, using MegaPlay direct fallback ($epCount eps) for anilistId=$anilistId")
            for (num in 1..epCount) {
                subEpisodes.add(newEpisode("sub|$anilistId|megaplay-direct:$num:sub") {
                    this.name = "Episode $num"
                    this.episode = num
                })
                dubEpisodes.add(newEpisode("dub|$anilistId|megaplay-direct:$num:dub") {
                    this.name = "Episode $num"
                    this.episode = num
                })
            }
        }

        Log.d("RaghavAnime", "[Miruro] load: '$title' (anilistId=$anilistId) -> ${subEpisodes.size} sub / ${dubEpisodes.size} dub episodes")
        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = bannerUrl
            this.year = year
            this.plot = plot
            this.tags = tags
            if (animeScore != null) this.score = Score.from10((animeScore / 10).toString())
            this.showStatus = showStatus
            addAniListId(anilistId)
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
        Log.d("RaghavAnime", "[Miruro] loadLinks: data=${data.take(80)}")

        val parts = data.split("|")
        if (parts.size < 3) {
            Log.e("RaghavAnime", "[Miruro] loadLinks: malformed data (${parts.size} parts), aborting")
            return false
        }

        val dubOrSub = parts[0]
        val anilistId = parts[1].toIntOrNull()
        val providerEntries = parts.drop(2)
        Log.d("RaghavAnime", "[Miruro] loadLinks: $dubOrSub anilistId=$anilistId, ${providerEntries.size} provider entries")

        // Thread-safe set for dedup across parallel providers
        val seenUrls = ConcurrentHashMap.newKeySet<String>()

        // Parse all entries first, then resolve in parallel
        val tasks = providerEntries.mapNotNull { entry ->
            val colonParts = entry.split(":")
            if (colonParts.size < 3) {
                // Backward compat: old format "provider:episodeId" without category
                if (colonParts.size == 2) {
                    Triple(colonParts[0], colonParts[1], dubOrSub)
                } else {
                    Log.e("RaghavAnime", "[Miruro] loadLinks: skipping malformed provider entry: ${entry.take(60)}")
                    null
                }
            } else {
                val provider = colonParts[0]
                val category = colonParts.last()
                val episodeId = colonParts.drop(1).dropLast(1).joinToString(":")
                if (provider.isEmpty() || episodeId.isEmpty() || category.isEmpty()) {
                    Log.e("RaghavAnime", "[Miruro] loadLinks: skipping entry with empty fields: ${entry.take(60)}")
                    null
                } else {
                    Triple(provider, episodeId, category)
                }
            }
        }

        // Resolve all providers in parallel - much faster than sequential
        val results = coroutineScope {
            tasks.map { (provider, episodeId, category) ->
                async {
                    processProvider(provider, episodeId, category, anilistId, seenUrls, subtitleCallback, callback)
                }
            }.awaitAll()
        }
        val foundAnySources = results.any { it != null }
        Log.d("RaghavAnime", "[Miruro] loadLinks: done, foundAnySources=$foundAnySources")
        return foundAnySources
    }

    private suspend fun processProvider(
        provider: String,
        episodeId: String,
        category: String,
        anilistId: Int?,
        seenUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean? {
        val displayName = providerDisplayNames[provider] ?: provider
        Log.d("RaghavAnime", "[Miruro] provider '$displayName': requesting sources (episodeId=$episodeId, category=$category)")

        // Direct MegaPlay fallback entry (built when the CF pipe is unavailable):
        // resolve straight through megaplay.buzz's anilist endpoint, no pipe needed.
        if (provider.equals("megaplay-direct", ignoreCase = true)) {
            if (anilistId == null) return null
            val cat = if (category.equals("dub", true)) "dub" else "sub"
            val streamUrl = "https://megaplay.buzz/stream/ani/$anilistId/$episodeId/$cat"
            Log.d("RaghavAnime", "[Miruro] provider '$displayName': resolving MegaPlay direct $streamUrl")
            return try {
                val found = java.util.concurrent.atomic.AtomicBoolean(false)
                MiruroMegaPlay().getUrl(streamUrl, null, subtitleCallback) { link ->
                    found.set(true)
                    callback(link)
                }
                if (found.get()) true else null
            } catch (e: Exception) {
                Log.e("RaghavAnime", "[Miruro] provider '$displayName': MegaPlay direct failed: ${e.message}")
                null
            }
        }

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
            val streams = sourcesData.streams
            if (streams == null) {
                Log.e("RaghavAnime", "[Miruro] provider '$displayName': no streams in sources response (json len=${sourcesJson.length})")
                return null
            }
            Log.d("RaghavAnime", "[Miruro] provider '$displayName': ${streams.size} streams, ${sourcesData.subtitles?.size ?: 0} subtitles")

            var found = false

            for (stream in streams.filter { it.type == "hls" && !it.url.isNullOrEmpty() }) {
                val m3u8Url = stream.url ?: continue
                if (!seenUrls.add(m3u8Url)) {
                    Log.d("RaghavAnime", "[Miruro] provider '$displayName': duplicate hls url, skipping")
                    continue
                }

                val referer = stream.referer ?: "${MiruroCloudflare.getWorkingDomain()}/"
                val quality = qualityFromString(stream.quality)
                val qualityLabel = stream.quality ?: "Auto"
                Log.d("RaghavAnime", "[Miruro] provider '$displayName': hls link '$qualityLabel' -> ${m3u8Url.take(80)}")
                val fansubLabel = if (!stream.fansub.isNullOrEmpty()) " [${stream.fansub}]" else ""
                val userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

                callback.invoke(
                    newExtractorLink(
                        source = "Miruro",
                        name = "$displayName$fansubLabel - $qualityLabel",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to userAgent
                        )
                    }
                )
                found = true
            }

            for (stream in streams.filter { it.type == "mp4" && !it.url.isNullOrEmpty() }) {
                val mp4Url = stream.url ?: continue
                if (!seenUrls.add(mp4Url)) {
                    Log.d("RaghavAnime", "[Miruro] provider '$displayName': duplicate mp4 url, skipping")
                    continue
                }

                val referer = stream.referer ?: "${MiruroCloudflare.getWorkingDomain()}/"
                val qualityLabel = stream.quality ?: "SD"
                Log.d("RaghavAnime", "[Miruro] provider '$displayName': mp4 link '$qualityLabel' -> ${mp4Url.take(80)}")

                callback.invoke(
                    newExtractorLink(
                        source = "Miruro",
                        name = "$displayName (MP4) - $qualityLabel",
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityFromString(stream.quality)
                        this.headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                        )
                    }
                )
                found = true
            }

            for (stream in streams.filter { it.type == "embed" && !it.url.isNullOrEmpty() }) {
                val embedUrl = stream.url ?: continue
                if (!seenUrls.add(embedUrl)) {
                    Log.d("RaghavAnime", "[Miruro] provider '$displayName': duplicate embed url, skipping")
                    continue
                }

                val referer = stream.referer ?: "${MiruroCloudflare.getWorkingDomain()}/"
                try {
                    if (embedUrl.contains("megaplay.buzz") || embedUrl.contains("megaplay")) {
                        Log.d("RaghavAnime", "[Miruro] provider '$displayName': resolving megaplay embed ${embedUrl.take(80)}")
                        MiruroMegaPlay().getUrl(embedUrl, referer, subtitleCallback, callback)
                        found = true
                    } else if (embedUrl.contains("vidwish.live") || embedUrl.contains("vidwish")) {
                        Log.d("RaghavAnime", "[Miruro] provider '$displayName': resolving vidwish embed ${embedUrl.take(80)}")
                        MiruroVidWish().getUrl(embedUrl, referer, subtitleCallback, callback)
                        found = true
                    } else {
                        try {
                            // fast paths for the dood-style hosts generic extractors
                            // need 20-45s on (direct m3u8 scan + jsunpacker)
                            val fastM3u8 = fastEmbedM3u8(embedUrl, referer)
                            if (fastM3u8 != null) {
                                Log.d("RaghavAnime", "[Miruro] provider '$displayName': fast embed m3u8 ${fastM3u8.take(80)}")
                                callback.invoke(
                                    newExtractorLink(
                                        source = "Miruro",
                                        name = displayName,
                                        url = fastM3u8,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.headers = mapOf(
                                            "Referer" to embedUrl,
                                            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                                        )
                                    }
                                )
                                found = true
                            } else {
                                Log.d("RaghavAnime", "[Miruro] provider '$displayName': loadExtractor for ${embedUrl.take(80)}")
                                loadExtractor(embedUrl, referer, subtitleCallback, callback)
                                found = true
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e("RaghavAnime", "[Miruro] provider '$displayName': loadExtractor failed for ${embedUrl.take(80)}: ${e.message}")
                            val host = try { java.net.URL(embedUrl).host } catch (_: Exception) { "" }
                            if (host.isNotEmpty()) {
                                Log.d("RaghavAnime", "[Miruro] provider '$displayName': trying WebView fallback for host=$host")
                                MiruroWebView(host, "https://$host").getUrl(embedUrl, referer, subtitleCallback, callback)
                                found = true
                            } else {
                                Log.e("RaghavAnime", "[Miruro] provider '$displayName': skipped embed, cannot parse host: ${embedUrl.take(80)}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("RaghavAnime", "[Miruro] provider '$displayName': embed failed: ${e.message}")
                    e.message
                }
            }

            sourcesData.subtitles?.forEach { sub ->
                if (!sub.url.isNullOrEmpty()) {
                    Log.d("RaghavAnime", "[Miruro] provider '$displayName': subtitle '${sub.lang ?: "English"}'")
                    subtitleCallback.invoke(SubtitleFile(sub.lang ?: "English", sub.url))
                }
            }

            Log.d("RaghavAnime", "[Miruro] provider '$displayName': done, found=$found")
            return if (found) true else null
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Miruro] provider '$displayName': sources request failed: ${e.message}")
            return null
        }
    }

    private fun toSlug(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    private val fastEmbedM3u8Pattern = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")

    /**
     * Fast m3u8 extraction for the dood-style hosts that generic extractors
     * are extremely slow on (20-45s). Same approach AniDao/Anikai use:
     * raw m3u8 scan with a jsunpacker fallback for packed players.
     */
    private suspend fun fastEmbedM3u8(embedUrl: String, referer: String): String? {
        val handled = embedUrl.contains("vivibebe.site") || embedUrl.contains("bibiemb.xyz") ||
            embedUrl.contains("otakuhg.site") || embedUrl.contains("otakuvid.online")
        if (!handled) return null
        return try {
            val html = app.get(embedUrl, headers = mapOf("Referer" to referer)).text
            fastEmbedM3u8Pattern.find(html)?.value
                ?: JsPacker.parseAndUnpack(html)?.let { fastEmbedM3u8Pattern.find(it)?.value }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.d("RaghavAnime", "[Miruro] fast embed scan failed for ${embedUrl.take(60)}: ${e.message}")
            null
        }
    }
}
