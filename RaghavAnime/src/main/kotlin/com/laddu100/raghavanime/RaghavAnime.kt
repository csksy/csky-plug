package com.laddu100.raghavanime

import com.lagradost.cloudstream3.CommonActivity.activity
import android.content.Context
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.CheckBox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class RaghavAnime : MainAPI() {
    override var mainUrl = "https://graphql.anilist.co"
    override var name = "RaghavAnime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending Now",
        "POPULAR" to "Popular This Season",
        "RECENT" to "Recently Updated",
        "TOP_RATED" to "Top Rated Series",
        "RECOMMEND" to "Recommended For You"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // RECOMMEND section — uses watch history-based recommendations
        if (request.data == "RECOMMEND") {
            if (!RaghavAnimeFeatures.isEnabled("recommendations")) {
                return newHomePageResponse(request.name, emptyList())
            }
            return try {
                val list = RaghavAnimeFeatures.getRecommendationsList()
                val home = list.map { item ->
                    newAnimeSearchResponse(item.title, item.url, TvType.Anime) {
                        this.posterUrl = item.posterUrl
                    }
                }
                newHomePageResponse(request.name, home)
            } catch (e: Exception) {
                Log.e("RaghavAnime", "[Recommendations] FAILED: ${e.message}")
                newHomePageResponse(request.name, emptyList())
            }
        }

        val query = HOMEPAGE_QUERY
        val variables = mutableMapOf<String, Any?>("page" to page, "perPage" to 20)

        when (request.data) {
            "TRENDING" -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
            }
            "POPULAR" -> {
                variables["sort"] = listOf("POPULARITY_DESC")
            }
            "RECENT" -> {
                variables["sort"] = listOf("START_DATE_DESC")
                variables["status"] = "RELEASING"
            }
            "TOP_RATED" -> {
                variables["sort"] = listOf("SCORE_DESC")
                variables["format"] = "TV"
            }
            else -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
            }
        }

        val home = try {
            val responseText = anilistQuery(query, variables)
            val response = parseJson<AniListResponse>(responseText)
            val mediaList = response.data?.Page?.media ?: emptyList()

            if (mediaList.isNotEmpty()) {
                homePageCache[request.data] = mediaList
            }

            mediaList.mapNotNull { media ->
                val id = media.id ?: return@mapNotNull null
                val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
                val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                newAnimeSearchResponse(title, "$mainUrl/info/$id", TvType.Anime) {
                    this.posterUrl = posterUrl
                    addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
                }
            }
        } catch (e: Exception) {
            val cached = homePageCache[request.data]
            if (cached != null) {
                cached.mapNotNull { media ->
                    val id = media.id ?: return@mapNotNull null
                    val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
                    val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                    newAnimeSearchResponse(title, "$mainUrl/info/$id", TvType.Anime) {
                        this.posterUrl = posterUrl
                        addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
                    }
                }
            } else {
                emptyList()
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("RaghavAnime", "[Search] START: query='$query'")
        val results = try {
            val variables = mapOf<String, Any?>("search" to query, "page" to 1, "perPage" to 20)
            val responseText = anilistQuery(SEARCH_QUERY, variables)
            val response = parseJson<AniListResponse>(responseText)
            val mediaList = response.data?.Page?.media ?: emptyList()

            mediaList.mapNotNull { media ->
                val id = media.id ?: return@mapNotNull null
                val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
                val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                newAnimeSearchResponse(title, "$mainUrl/info/$id", TvType.Anime) {
                    this.posterUrl = posterUrl
                    addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        Log.d("RaghavAnime", "[Search] END: ${results.size} results")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        Log.d("RaghavAnime", "[Load] START: anilistId=$anilistId")

        val media = try {
            val infoText = anilistQuery(INFO_QUERY, mapOf("id" to anilistId))
            val infoResponse = parseJson<AniListResponse>(infoText)
            infoResponse.data?.Media
        } catch (e: Exception) {
            return null
        } ?: run {
            return null
        }

        val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
        val jpTitle = media.title?.romaji
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

        val syncMetaData = try {
            app.get("https://api.ani.zip/mappings?anilist_id=$anilistId").text
        } catch (_: Exception) { null }
        val animeMetaData = syncMetaData?.let { parseAnimeData(it) }

        val anizipNumericCount = animeMetaData?.episodes?.keys
            ?.filterNotNull()
            ?.filter { it.toIntOrNull() != null }
            ?.size ?: 0

        var totalEps = media.episodes
            ?: anizipNumericCount
            ?: 0

        media.nextAiringEpisode?.episode?.let { nextEp ->
            if (totalEps >= nextEp) {
                totalEps = nextEp - 1
            }
        }

        if (media.format == "MOVIE" && totalEps == 0) totalEps = 1
        if (totalEps == 0) totalEps = 1

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for (i in 1..totalEps) {
            val epData = animeMetaData?.episodes?.get(i.toString())
            val epTitle = epData?.title?.get("en") ?: epData?.title?.get("ja") ?: epData?.title?.get("x-jat") ?: "Episode $i"
            val epDesc = epData?.overview ?: "No summary available"
            val epPoster = epData?.image ?: posterUrl

            val subLinkData = LinkData(animeId = anilistId, title = title, jpTitle = jpTitle, episode = i, isDub = false, year = year).toJson()
            val dubLinkData = LinkData(animeId = anilistId, title = title, jpTitle = jpTitle, episode = i, isDub = true, year = year).toJson()

            subEpisodes.add(newEpisode(subLinkData) {
                this.episode = i
                this.name = epTitle
                this.description = epDesc
                this.posterUrl = epPoster
            })
            dubEpisodes.add(newEpisode(dubLinkData) {
                this.episode = i
                this.name = epTitle
                this.description = epDesc
                this.posterUrl = epPoster
            })
        }

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
        val linkData = parseJson<LinkData>(data)
        val aniId = linkData.animeId
        val title = linkData.title
        val jpTitle = linkData.jpTitle
        val episode = linkData.episode
        val isDub = linkData.isDub

        // Record watch time (24min per episode by default — triggers recommendations cache invalidation)
        if (RaghavAnimeFeatures.isEnabled("watch_time")) {
            try { RaghavAnimeFeatures.recordWatchTime(aniId, title, null, 24 * 60 * 1000L) } catch (_: Exception) {}
        }

        runAllAsync(
            {
                var miruroLinks = 0
                val miruroCallback: (ExtractorLink) -> Unit = { link ->
                    miruroLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Miruro] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val miruro = Miruro()
                    val loadResult = miruro.load("${miruro.mainUrl}/info/$aniId") as? com.lagradost.cloudstream3.AnimeLoadResponse
                    if (loadResult == null) {
                        Log.d("RaghavAnime", "[Miruro] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Miruro] Found episode data")
                        val epList = if (isDub) loadResult.episodes?.get(DubStatus.Dubbed) else loadResult.episodes?.get(DubStatus.Subbed)
                        val matchedEp = epList?.find { it.episode == episode }
                        if (matchedEp != null) {
                            miruro.loadLinks(matchedEp.data, false, subtitleCallback, miruroCallback)
                        } else {
                        }
                    }
                    Log.d("RaghavAnime", "[Miruro] END: $miruroLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[Miruro] FAILED: ${e.message}")
                }
            },
            {
                var aniSugeLinks = 0
                val aniSugeCallback: (ExtractorLink) -> Unit = { link ->
                    aniSugeLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[AniSuge] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val aniSuge = AniSugeProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { aniSuge.search(it) },
                        doLoad = { aniSuge.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniSuge"
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[AniSuge] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[AniSuge] Found episode data")
                        aniSuge.loadLinks(epData, false, subtitleCallback, aniSugeCallback)
                    }
                    Log.d("RaghavAnime", "[AniSuge] END: $aniSugeLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[AniSuge] FAILED: ${e.message}")
                }
            },
            {
                var aniWavesLinks = 0
                val aniWavesCallback: (ExtractorLink) -> Unit = { link ->
                    aniWavesLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[AniWaves] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val aniWaves = AniWaves()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val aniWavesTargets = listOfNotNull(title, jpTitle).map { cleanTitle(it) }
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        val searchResults = try { aniWaves.search(t) } catch (e: Throwable) {
                            Log.e("RaghavAnime", "[AniWaves] search failed for '$t': ${e.message}")
                            continue
                        }
                        val candidates = searchResults.mapNotNull { r ->
                            val c = cleanTitle(r.name)
                            val score = when {
                                aniWavesTargets.contains(c) -> 2
                                else -> 0
                            }
                            if (score > 0) {
                                Pair(score, r)
                            } else null
                        }.sortedByDescending { it.first }
                        for ((_, result) in candidates) {
                            try {
                                val loadResult = aniWaves.load(result.url) as? com.lagradost.cloudstream3.AnimeLoadResponse ?: continue
                                val ep = loadResult.episodes?.get(DubStatus.Subbed)?.find { it.episode == episode } ?: continue
                                val parts = ep.data.split("|").toMutableList()
                                parts[0] = if (isDub) "dub" else "sub"
                                matchedData = parts.joinToString("|")
                                break
                            } catch (e: Throwable) {
                                Log.e("RaghavAnime", "[AniWaves] load failed for '${result.name}': ${e.message}")
                                continue
                            }
                        }
                        if (matchedData != null) break
                    }
                    if (matchedData == null) {
                        Log.d("RaghavAnime", "[AniWaves] No matched data")
                    } else {
                        Log.d("RaghavAnime", "[AniWaves] Found matched data")
                        aniWaves.loadLinks(matchedData, false, subtitleCallback, aniWavesCallback)
                    }
                    Log.d("RaghavAnime", "[AniWaves] END: $aniWavesLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[AniWaves] FAILED: ${e.message}")
                }
            },
            {
                var anikaiLinks = 0
                val anikaiCallback: (ExtractorLink) -> Unit = { link ->
                    anikaiLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Anikai] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val anikai = Anikai()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anikai.search(it) },
                        doLoad = { anikai.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Anikai"
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[Anikai] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Anikai] Found episode data")
                        anikai.loadLinks(epData, false, subtitleCallback, anikaiCallback)
                    }
                    Log.d("RaghavAnime", "[Anikai] END: $anikaiLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[Anikai] FAILED: ${e.message}")
                }
            },
            {
                var aniDbLinks = 0
                val aniDbCallback: (ExtractorLink) -> Unit = { link ->
                    aniDbLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[AniDb] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val aniDb = AniDb()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { q -> aniDb.search(q, 1).items },
                        doLoad = { aniDb.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniDb"
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[AniDb] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[AniDb] Found episode data")
                        aniDb.loadLinks(epData, false, subtitleCallback, aniDbCallback)
                    }
                    Log.d("RaghavAnime", "[AniDb] END: $aniDbLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[AniDb] FAILED: ${e.message}")
                }
            },
            {
                var aniKageLinks = 0
                val aniKageCallback: (ExtractorLink) -> Unit = { link ->
                    aniKageLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[AniKage] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val anikage = RaghavAniKage()
                    anikage.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, subtitleCallback, aniKageCallback)
                    Log.d("RaghavAnime", "[AniKage] END: $aniKageLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[AniKage] FAILED: ${e.message}")
                }
            },
            {
                var aninekoLinks = 0
                val aninekoCallback: (ExtractorLink) -> Unit = { link ->
                    aninekoLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Anineko] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val anineko = Anineko()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anineko.search(it) },
                        doLoad = { anineko.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Anineko"
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[Anineko] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Anineko] Found episode data")
                        anineko.loadLinks(epData, false, subtitleCallback, aninekoCallback)
                    }
                    Log.d("RaghavAnime", "[Anineko] END: $aninekoLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[Anineko] FAILED: ${e.message}")
                }
            },
            {
            },
            {
                var twoDHiveLinks = 0
                val twoDHiveCallback: (ExtractorLink) -> Unit = { link ->
                    twoDHiveLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[2DHive] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val twoDHive = RaghavTwoDHive()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { twoDHive.search(it) },
                        doLoad = { twoDHive.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "2DHive"
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[2DHive] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[2DHive] Found episode data")
                        twoDHive.loadLinks(epData, false, subtitleCallback, twoDHiveCallback)
                    }
                    Log.d("RaghavAnime", "[2DHive] END: $twoDHiveLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[2DHive] FAILED: ${e.message}")
                }
            },
            {
                var aniKotoLinks = 0
                val aniKotoCallback: (ExtractorLink) -> Unit = { link ->
                    aniKotoLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[AniKoto] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val anikoto = RaghavAnikoto()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anikoto.search(it) },
                        doLoad = { anikoto.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniKoto"
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[AniKoto] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[AniKoto] Found episode data")
                        anikoto.loadLinks(epData, false, subtitleCallback, aniKotoCallback)
                    }
                    Log.d("RaghavAnime", "[AniKoto] END: $aniKotoLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[AniKoto] FAILED: ${e.message}")
                }
            },
            {
                var enmaLinks = 0
                val enmaCallback: (ExtractorLink) -> Unit = { link ->
                    enmaLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Enma] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val enma = RaghavEnma()
                    enma.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, subtitleCallback, enmaCallback)
                    Log.d("RaghavAnime", "[Enma] END: $enmaLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[Enma] FAILED: ${e.message}")
                }
            },
            {
                var animoLinks = 0
                val animoCallback: (ExtractorLink) -> Unit = { link ->
                    animoLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Animo] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val animo = RaghavAnimo()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { animo.search(it) },
                        doLoad = { animo.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Animo"
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[Animo] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Animo] Found episode data")
                        animo.loadLinks(epData, false, subtitleCallback, animoCallback)
                    }
                    Log.d("RaghavAnime", "[Animo] END: $animoLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[Animo] FAILED: ${e.message}")
                }
            },
            {
                var anidapLinks = 0
                val anidapCallback: (ExtractorLink) -> Unit = { link ->
                    anidapLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Anidap] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val anidap = RaghavAnidap()
                    anidap.loadLinksByAnilistId(aniId, episode, isDub, subtitleCallback, anidapCallback)
                    Log.d("RaghavAnime", "[Anidap] END: $anidapLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[Anidap] FAILED: ${e.message}")
                }
            },
            {
                var senshiLinks = 0
                val senshiCallback: (ExtractorLink) -> Unit = { link ->
                    senshiLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Senshi] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val senshi = RaghavSenshi()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { senshi.search(it) },
                        doLoad = { senshi.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Senshi"
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[Senshi] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Senshi] Found episode data")
                        senshi.loadLinks(epData, false, subtitleCallback, senshiCallback)
                    }
                    Log.d("RaghavAnime", "[Senshi] END: $senshiLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[Senshi] FAILED: ${e.message}")
                }
            },
            {
                var aniNamiLinks = 0
                val aniNamiCallback: (ExtractorLink) -> Unit = { link ->
                    aniNamiLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[AniNami] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val aniNami = RaghavAniNami()
                    val loadResult = aniNami.load("${aniNami.mainUrl}/anime/$aniId") as? com.lagradost.cloudstream3.AnimeLoadResponse
                    if (loadResult == null) {
                        Log.d("RaghavAnime", "[AniNami] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[AniNami] Found episode data")
                        val epList = if (isDub) loadResult.episodes?.get(DubStatus.Dubbed) else loadResult.episodes?.get(DubStatus.Subbed)
                        val matchedEp = epList?.find { it.episode == episode }
                        if (matchedEp != null) {
                            aniNami.loadLinks(matchedEp.data, false, subtitleCallback, aniNamiCallback)
                        } else {
                        }
                    }
                    Log.d("RaghavAnime", "[AniNami] END: $aniNamiLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[AniNami] FAILED: ${e.message}")
                }
            },
            {
                var aniDaoLinks = 0
                val aniDaoCallback: (ExtractorLink) -> Unit = { link ->
                    aniDaoLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[AniDao] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val aniDao = RaghavAniDao()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { aniDao.search(it) },
                        doLoad = { aniDao.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniDao"
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[AniDao] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[AniDao] Found episode data")
                        aniDao.loadLinks(epData, false, subtitleCallback, aniDaoCallback)
                    }
                    Log.d("RaghavAnime", "[AniDao] END: $aniDaoLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[AniDao] FAILED: ${e.message}")
                }
            },
            {
                var aniChanLinks = 0
                val aniChanCallback: (ExtractorLink) -> Unit = { link ->
                    aniChanLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[AniChan] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val anichan = RaghavAniChan()
                    anichan.loadLinksByAnilistId(aniId, episode, isDub, subtitleCallback, aniChanCallback)
                    Log.d("RaghavAnime", "[AniChan] END: $aniChanLinks links found")
                } catch (e: Throwable) {
                    Log.e("RaghavAnime", "[AniChan] FAILED: ${e.message}")
                }
            },
        )

        return true
    }

    private fun cleanTitle(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun romanToInt(s: String): Int? {
        val upper = s.uppercase()
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10)
        if (upper.any { it !in map }) return null
        var result = 0
        var prev = 0
        for (c in upper.reversed()) {
            val curr = map[c] ?: return null
            if (curr < prev) result -= curr else result += curr
            prev = curr
        }
        return if (result in 1..20) result else null
    }

    private fun extractSeasonNumber(title: String): Int? {
        val lower = title.lowercase()
        Regex("""(\d+)(?:st|nd|rd|th)\s*season""").find(lower)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("""season\s*(\d+)""").find(lower)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("""\bs(\d+)\b""").find(lower)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("""part\s*(\d+)""").find(lower)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("""cour\s*(\d+)""").find(lower)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("""\s+([ivx]+)\s*$""").find(lower)?.let {
            return romanToInt(it.groupValues[1])
        }
        return null
    }

    private fun extractYear(title: String): Int? {
        return Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun findEpisodeData(
        searchTitles: List<String>,
        targetTitles: List<String>,
        episode: Int,
        isDub: Boolean,
        year: Int?,
        doSearch: suspend (String) -> List<SearchResponse>,
        doLoad: suspend (String) -> com.lagradost.cloudstream3.AnimeLoadResponse?,
        dubKey: com.lagradost.cloudstream3.DubStatus = com.lagradost.cloudstream3.DubStatus.Dubbed,
        subKey: com.lagradost.cloudstream3.DubStatus = com.lagradost.cloudstream3.DubStatus.Subbed,
        sourceTag: String = "Source"
    ): String? {
        val cleanedTargets = targetTitles.map { cleanTitle(it) }
        val epKey = if (isDub) dubKey else subKey


        val targetSeasonNum = targetTitles.firstNotNullOfOrNull { extractSeasonNumber(it) }

        data class Candidate(val combinedScore: Int, val titleScore: Int, val result: SearchResponse)

        val allCandidates = mutableListOf<Candidate>()
        var totalSearchResults = 0
        for (t in searchTitles) {
            val searchResults = try { doSearch(t) } catch (e: Throwable) {
                Log.e("RaghavAnime", "[$sourceTag] search failed for '$t': ${e.message}")
                continue
            }
            totalSearchResults += searchResults.size
            for (r in searchResults) {
                val c = cleanTitle(r.name)
                val titleScore = when {
                    cleanedTargets.contains(c) -> 2
                    cleanedTargets.any { tgt -> tgt.contains(c) || c.contains(tgt) } -> 1
                    else -> 0
                }
                if (titleScore == 0) continue

                val candSeasonNum = extractSeasonNumber(r.name)
                val candYear = extractYear(r.name)
                val yearScore = if (year != null && (candYear == year || r.name.contains(year.toString()))) 1 else 0
                val seasonScore = if (targetSeasonNum != null && candSeasonNum == targetSeasonNum) 1 else 0

                val combinedScore = titleScore * 10 + yearScore * 5 + seasonScore * 3
                allCandidates.add(Candidate(combinedScore, titleScore, r))
            }
        }


        if (allCandidates.isEmpty()) {
            return null
        }

        allCandidates.sortByDescending { it.combinedScore }

        for (cand in allCandidates) {
            if (cand.titleScore < 2) {
                break
            }
            try {
                val loadResult = doLoad(cand.result.url) ?: continue
                val ep = loadResult.episodes?.get(epKey)?.find { it.episode == episode }
                if (ep != null) {
                    return ep.data
                } else {
                }
            } catch (e: Throwable) {
                Log.e("RaghavAnime", "[$sourceTag] load failed for '${cand.result.name}': ${e.message}")
            }
        }

        return null
    }

    data class LinkData(
        val animeId: Int,
        val title: String,
        val jpTitle: String?,
        val episode: Int,
        val isDub: Boolean,
        val year: Int?
    )

    companion object {
        var hasShownThisSession = false
        private val homePageCache = mutableMapOf<String, List<AniListMedia>>()
    }
}

val HOMEPAGE_QUERY = """
    query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: [MediaSort], ${'$'}genreIn: [String], ${'$'}format: MediaFormat, ${'$'}status: MediaStatus) {
        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            media(type: ANIME, sort: ${'$'}sort, genre_in: ${'$'}genreIn, format: ${'$'}format, status: ${'$'}status) {
                id
                title { romaji english native }
                coverImage { large extraLarge }
                format
                episodes
                status
                seasonYear
                averageScore
                genres
                nextAiringEpisode { episode }
            }
        }
    }
""".trimIndent()
