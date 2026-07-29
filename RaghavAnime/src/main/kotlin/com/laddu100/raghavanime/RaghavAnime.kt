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
import kotlinx.coroutines.withTimeout
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
        "TOP_RATED" to "Top Rated Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
        return try {
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
    }

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null

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

        Log.d("RaghavAnime", "loadLinks START: aniId=$aniId title='$title' jpTitle='$jpTitle' ep=$episode isDub=$isDub")

        runAllAsync(
            {
                Log.d("RaghavAnime", "[Miruro] START aniId=$aniId ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val miruro = Miruro()
                    Log.d("RaghavAnime", "[Miruro] mainUrl=${miruro.mainUrl}")
                    val loadResult = miruro.load("${miruro.mainUrl}/info/$aniId") as? com.lagradost.cloudstream3.AnimeLoadResponse
                    if (loadResult == null) {
                        Log.d("RaghavAnime", "[Miruro] load returned null for aniId=$aniId")
                    } else {
                        Log.d("RaghavAnime", "[Miruro] load success, epCount=${loadResult.episodes?.values?.sumOf { it.size }}")
                        val epList = if (isDub) loadResult.episodes?.get(DubStatus.Dubbed) else loadResult.episodes?.get(DubStatus.Subbed)
                        Log.d("RaghavAnime", "[Miruro] epList size=${epList?.size} for ${if (isDub) "dub" else "sub"}")
                        val matchedEp = epList?.find { it.episode == episode }
                        if (matchedEp != null) {
                            Log.d("RaghavAnime", "[Miruro] found ep $episode, loading links")
                            miruro.loadLinks(matchedEp.data, false, subtitleCallback, callback)
                        } else {
                            Log.d("RaghavAnime", "[Miruro] ep $episode not found in list")
                        }
                    }
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[Miruro] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[Miruro] END")
            },
            {
                Log.d("RaghavAnime", "[AniSuge] START ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val aniSuge = AniSugeProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    Log.d("RaghavAnime", "[AniSuge] searchTitles=$searchTitles")
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { aniSuge.search(it) },
                        doLoad = { aniSuge.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniSuge"
                    )
                    if (epData != null) {
                        Log.d("RaghavAnime", "[AniSuge] found epData, loading links")
                        aniSuge.loadLinks(epData, false, subtitleCallback, callback)
                    } else {
                        Log.d("RaghavAnime", "[AniSuge] no epData found")
                    }
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[AniSuge] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[AniSuge] END")
            },
            {
                Log.d("RaghavAnime", "[AniWaves] START ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val aniWaves = AniWaves()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val aniWavesTargets = listOfNotNull(title, jpTitle).map { cleanTitle(it) }
                    Log.d("RaghavAnime", "[AniWaves] searchTitles=$searchTitles targets=$aniWavesTargets")
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        Log.d("RaghavAnime", "[AniWaves] searching for '$t'")
                        val searchResults = try { aniWaves.search(t) } catch (e: Throwable) {
                            Log.d("RaghavAnime", "[AniWaves] search failed for '$t': ${e.message}")
                            continue
                        }
                        Log.d("RaghavAnime", "[AniWaves] search returned ${searchResults.size} results")
                        val candidates = searchResults.mapNotNull { r ->
                            val c = cleanTitle(r.name)
                            val score = when {
                                aniWavesTargets.contains(c) -> 2
                                else -> 0
                            }
                            if (score > 0) {
                                Log.d("RaghavAnime", "[AniWaves] candidate: '${r.name}' score=$score")
                                Pair(score, r)
                            } else null
                        }.sortedByDescending { it.first }
                        for ((_, result) in candidates) {
                            try {
                                Log.d("RaghavAnime", "[AniWaves] loading '${result.name}' url=${result.url}")
                                val loadResult = aniWaves.load(result.url) as? com.lagradost.cloudstream3.AnimeLoadResponse ?: continue
                                val ep = loadResult.episodes?.get(DubStatus.Subbed)?.find { it.episode == episode } ?: continue
                                val parts = ep.data.split("|").toMutableList()
                                parts[0] = if (isDub) "dub" else "sub"
                                matchedData = parts.joinToString("|")
                                Log.d("RaghavAnime", "[AniWaves] matched ep $episode, data=$matchedData")
                                break
                            } catch (e: Throwable) {
                                Log.d("RaghavAnime", "[AniWaves] load failed for '${result.name}': ${e.message}")
                                continue
                            }
                        }
                        if (matchedData != null) break
                    }
                    if (matchedData != null) {
                        Log.d("RaghavAnime", "[AniWaves] loading links for matchedData")
                        aniWaves.loadLinks(matchedData, false, subtitleCallback, callback)
                    } else {
                        Log.d("RaghavAnime", "[AniWaves] no match found")
                    }
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[AniWaves] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[AniWaves] END")
            },
            {
                Log.d("RaghavAnime", "[Anikai] START ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val anikai = Anikai()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    Log.d("RaghavAnime", "[Anikai] searchTitles=$searchTitles")
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anikai.search(it) },
                        doLoad = { anikai.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Anikai"
                    )
                    if (epData != null) {
                        Log.d("RaghavAnime", "[Anikai] found epData, loading links")
                        anikai.loadLinks(epData, false, subtitleCallback, callback)
                    } else {
                        Log.d("RaghavAnime", "[Anikai] no epData found")
                    }
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[Anikai] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[Anikai] END")
            },
            {
                Log.d("RaghavAnime", "[AniDb] START ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val aniDb = AniDb()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    Log.d("RaghavAnime", "[AniDb] searchTitles=$searchTitles")
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { q -> aniDb.search(q, 1).items },
                        doLoad = { aniDb.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniDb"
                    )
                    if (epData != null) {
                        Log.d("RaghavAnime", "[AniDb] found epData, loading links")
                        aniDb.loadLinks(epData, false, subtitleCallback, callback)
                    } else {
                        Log.d("RaghavAnime", "[AniDb] no epData found")
                    }
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[AniDb] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[AniDb] END")
            },
            {
                Log.d("RaghavAnime", "[AniKage] START aniId=$aniId ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val anikage = RaghavAniKage()
                    anikage.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, subtitleCallback, callback)
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[AniKage] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[AniKage] END")
            },
            {
                Log.d("RaghavAnime", "[Anineko] START ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val anineko = Anineko()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    Log.d("RaghavAnime", "[Anineko] searchTitles=$searchTitles")
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anineko.search(it) },
                        doLoad = { anineko.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Anineko"
                    )
                    if (epData != null) {
                        Log.d("RaghavAnime", "[Anineko] found epData, loading links")
                        anineko.loadLinks(epData, false, subtitleCallback, callback)
                    } else {
                        Log.d("RaghavAnime", "[Anineko] no epData found")
                    }
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[Anineko] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[Anineko] END")
            },
            {
                Log.d("RaghavAnime", "[Animetsu] SKIPPED - site is dead")
            },
            {
                Log.d("RaghavAnime", "[2DHive] START ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val twoDHive = RaghavTwoDHive()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    Log.d("RaghavAnime", "[2DHive] searchTitles=$searchTitles")
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { twoDHive.search(it) },
                        doLoad = { twoDHive.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "2DHive"
                    )
                    if (epData != null) {
                        Log.d("RaghavAnime", "[2DHive] found epData, loading links")
                        twoDHive.loadLinks(epData, false, subtitleCallback, callback)
                    } else {
                        Log.d("RaghavAnime", "[2DHive] no epData found")
                    }
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[2DHive] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[2DHive] END")
            },
            {
                Log.d("RaghavAnime", "[AniKoto] START ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val anikoto = RaghavAnikoto()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    Log.d("RaghavAnime", "[AniKoto] searchTitles=$searchTitles")
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anikoto.search(it) },
                        doLoad = { anikoto.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniKoto"
                    )
                    if (epData != null) {
                        Log.d("RaghavAnime", "[AniKoto] found epData, loading links")
                        anikoto.loadLinks(epData, false, subtitleCallback, callback)
                    } else {
                        Log.d("RaghavAnime", "[AniKoto] no epData found")
                    }
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[AniKoto] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[AniKoto] END")
            },
            {
                Log.d("RaghavAnime", "[Enma] START aniId=$aniId ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val enma = RaghavEnma()
                    enma.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, subtitleCallback, callback)
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[Enma] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[Enma] END")
            },
            {
                Log.d("RaghavAnime", "[Animo] START ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val animo = RaghavAnimo()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    Log.d("RaghavAnime", "[Animo] searchTitles=$searchTitles")
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { animo.search(it) },
                        doLoad = { animo.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Animo"
                    )
                    if (epData != null) {
                        Log.d("RaghavAnime", "[Animo] found epData, loading links")
                        animo.loadLinks(epData, false, subtitleCallback, callback)
                    } else {
                        Log.d("RaghavAnime", "[Animo] no epData found")
                    }
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[Animo] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[Animo] END")
            },
            {
                Log.d("RaghavAnime", "[Anidap] START ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val anidap = RaghavAnidap()
                    anidap.loadLinksByAnilistId(aniId, episode, isDub, subtitleCallback, callback)
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[Anidap] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[Anidap] END")
            },
            {
                Log.d("RaghavAnime", "[Senshi] START ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val senshi = RaghavSenshi()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    Log.d("RaghavAnime", "[Senshi] searchTitles=$searchTitles")
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { senshi.search(it) },
                        doLoad = { senshi.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Senshi"
                    )
                    if (epData != null) {
                        Log.d("RaghavAnime", "[Senshi] found epData, loading links")
                        senshi.loadLinks(epData, false, subtitleCallback, callback)
                    } else {
                        Log.d("RaghavAnime", "[Senshi] no epData found")
                    }
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[Senshi] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[Senshi] END")
            },
            {
                Log.d("RaghavAnime", "[AniNami] START aniId=$aniId ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val aniNami = RaghavAniNami()
                    Log.d("RaghavAnime", "[AniNami] mainUrl=${aniNami.mainUrl}")
                    val loadResult = aniNami.load("${aniNami.mainUrl}/anime/$aniId") as? com.lagradost.cloudstream3.AnimeLoadResponse
                    if (loadResult == null) {
                        Log.d("RaghavAnime", "[AniNami] load returned null for aniId=$aniId")
                    } else {
                        Log.d("RaghavAnime", "[AniNami] load success, epCount=${loadResult.episodes?.values?.sumOf { it.size }}")
                        val epList = if (isDub) loadResult.episodes?.get(DubStatus.Dubbed) else loadResult.episodes?.get(DubStatus.Subbed)
                        Log.d("RaghavAnime", "[AniNami] epList size=${epList?.size} for ${if (isDub) "dub" else "sub"}")
                        val matchedEp = epList?.find { it.episode == episode }
                        if (matchedEp != null) {
                            Log.d("RaghavAnime", "[AniNami] found ep $episode, loading links")
                            aniNami.loadLinks(matchedEp.data, false, subtitleCallback, callback)
                        } else {
                            Log.d("RaghavAnime", "[AniNami] ep $episode not found in list")
                        }
                    }
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[AniNami] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[AniNami] END")
            },
            {
                Log.d("RaghavAnime", "[AniDao] START ep=$episode isDub=$isDub")
                try { withTimeout(30_000L) {
                    val aniDao = RaghavAniDao()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    Log.d("RaghavAnime", "[AniDao] searchTitles=$searchTitles")
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { aniDao.search(it) },
                        doLoad = { aniDao.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniDao"
                    )
                    if (epData != null) {
                        Log.d("RaghavAnime", "[AniDao] found epData, loading links")
                        aniDao.loadLinks(epData, false, subtitleCallback, callback)
                    } else {
                        Log.d("RaghavAnime", "[AniDao] no epData found")
                    }
                } } catch (e: Throwable) {
                    Log.d("RaghavAnime", "[AniDao] FAILED: ${e.message}")
                }
                Log.d("RaghavAnime", "[AniDao] END")
            },
        )

        Log.d("RaghavAnime", "loadLinks END: aniId=$aniId ep=$episode")
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

        Log.d("RaghavAnime", "[$sourceTag] findEpisodeData: ep=$episode isDub=$isDub year=$year targets=$cleanedTargets")

        val targetSeasonNum = targetTitles.firstNotNullOfOrNull { extractSeasonNumber(it) }
        Log.d("RaghavAnime", "[$sourceTag] targetSeasonNum=$targetSeasonNum")

        data class Candidate(val combinedScore: Int, val titleScore: Int, val result: SearchResponse)

        val allCandidates = mutableListOf<Candidate>()
        var totalSearchResults = 0
        for (t in searchTitles) {
            Log.d("RaghavAnime", "[$sourceTag] searching for '$t'")
            val searchResults = try { doSearch(t) } catch (e: Throwable) {
                Log.d("RaghavAnime", "[$sourceTag] search failed for '$t': ${e.message}")
                continue
            }
            totalSearchResults += searchResults.size
            Log.d("RaghavAnime", "[$sourceTag] search returned ${searchResults.size} results for '$t'")
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
                Log.d("RaghavAnime", "[$sourceTag] candidate: '${r.name}' cleaned='$c' titleScore=$titleScore yearScore=$yearScore seasonScore=$seasonScore combined=$combinedScore")
                allCandidates.add(Candidate(combinedScore, titleScore, r))
            }
        }

        Log.d("RaghavAnime", "[$sourceTag] totalSearchResults=$totalSearchResults allCandidates=${allCandidates.size}")

        if (allCandidates.isEmpty()) {
            Log.d("RaghavAnime", "[$sourceTag] no candidates found")
            return null
        }

        allCandidates.sortByDescending { it.combinedScore }
        Log.d("RaghavAnime", "[$sourceTag] sorted candidates: ${allCandidates.map { "${it.result.name}(score=${it.combinedScore},title=${it.titleScore})" }}")

        for (cand in allCandidates) {
            if (cand.titleScore < 2) {
                Log.d("RaghavAnime", "[$sourceTag] skipping titleScore<2 candidate: '${cand.result.name}'")
                break
            }
            try {
                Log.d("RaghavAnime", "[$sourceTag] loading '${cand.result.name}' url=${cand.result.url}")
                val loadResult = doLoad(cand.result.url) ?: continue
                Log.d("RaghavAnime", "[$sourceTag] load success, epCount=${loadResult.episodes?.values?.sumOf { it.size }}")
                val ep = loadResult.episodes?.get(epKey)?.find { it.episode == episode }
                if (ep != null) {
                    Log.d("RaghavAnime", "[$sourceTag] FOUND ep $episode data=${ep.data}")
                    return ep.data
                } else {
                    Log.d("RaghavAnime", "[$sourceTag] ep $episode not found in ${epKey} list")
                }
            } catch (e: Throwable) {
                Log.d("RaghavAnime", "[$sourceTag] load failed for '${cand.result.name}': ${e.message}")
            }
        }

        Log.d("RaghavAnime", "[$sourceTag] no matching episode found after all candidates")
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
