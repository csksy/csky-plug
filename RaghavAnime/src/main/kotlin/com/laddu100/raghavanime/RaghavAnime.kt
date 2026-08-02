package com.laddu100.raghavanime
import com.lagradost.api.Log

import com.lagradost.cloudstream3.CommonActivity.activity
import android.content.Context
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.CheckBox

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
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending Now",
        "POPULAR" to "Popular This Season",
        "RECENT" to "Recently Updated",
        "ACTION" to "Action & Adventure",
        "FANTASY" to "Fantasy & Magic",
        "SCIFI" to "Sci-Fi & Mecha",
        "ROMANCE" to "Romance & Drama",
        "COMEDY" to "Comedy & Slice of Life",
        "MOVIES" to "Top Rated Movies",
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
            "ACTION" -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
                variables["genreIn"] = listOf("Action", "Adventure")
            }
            "FANTASY" -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
                variables["genreIn"] = listOf("Fantasy")
            }
            "SCIFI" -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
                variables["genreIn"] = listOf("Sci-Fi")
            }
            "ROMANCE" -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
                variables["genreIn"] = listOf("Romance", "Drama")
            }
            "COMEDY" -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
                variables["genreIn"] = listOf("Comedy")
            }
            "MOVIES" -> {
                variables["sort"] = listOf("SCORE_DESC")
                variables["format"] = "MOVIE"
            }
            "TOP_RATED" -> {
                variables["sort"] = listOf("SCORE_DESC")
                variables["format"] = "TV"
            }
            else -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
            }
        }

        // Robust error handling — NEVER throw, always return something.
        // AniList rate-limits at 90 req/min; transient failures must not
        // cause an empty home screen. Cache successful results for fallback.
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
        return try {
            val variables = mapOf<String, Any?>("search" to query, "page" to 1, "perPage" to 20)
            val responseText = anilistQuery(SEARCH_QUERY, variables)
            val response = parseJson<AniListResponse>(responseText)
            val mediaList = response.data?.Page?.media ?: emptyList()

            val results = mediaList.mapNotNull { media ->
                val id = media.id ?: return@mapNotNull null
                val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
                val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                newAnimeSearchResponse(title, "$mainUrl/info/$id", TvType.Anime) {
                    this.posterUrl = posterUrl
                    addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
                }
            }
            Log.d("RaghavAnime", "[Search] END: ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Search] FAILED: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        Log.d("RaghavAnime", "[Load] START: anilistId=$anilistId url=$url")

        // Fetch anime details from AniList
        val media = try {
            val infoText = anilistQuery(INFO_QUERY, mapOf("id" to anilistId))
            val infoResponse = parseJson<AniListResponse>(infoText)
            infoResponse.data?.Media
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Load] FAILED: AniList query failed for id=$anilistId: ${e.message}")
            return null
        } ?: run {
            Log.e("RaghavAnime", "[Load] FAILED: AniList returned null Media for id=$anilistId")
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

        // Fetch episode metadata from ani.zip (for episode titles, descriptions, thumbnails)
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

        // For ongoing series, cap at nextAiringEpisode - 1 (aired episodes only)
        media.nextAiringEpisode?.episode?.let { nextEp ->
            if (totalEps >= nextEp) {
                totalEps = nextEp - 1
            }
        }

        if (media.format == "MOVIE" && totalEps == 0) totalEps = 1
        if (totalEps == 0) totalEps = 1 // Always at least 1 episode

        Log.d("RaghavAnime", "[Load] totalEps=$totalEps (anilist=${media.episodes}, anizip_numeric=$anizipNumericCount, nextAiring=${media.nextAiringEpisode?.episode})")

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

        Log.d("RaghavAnime", "[Load] episodes built: ${subEpisodes.size} sub + ${dubEpisodes.size} dub")

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

        runAllAsync(
            {
                // 1. Miruro
                var miruroLinks = 0
                val miruroCallback: (ExtractorLink) -> Unit = { link ->
                    miruroLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Miruro] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val miruro = Miruro()
                    val loadResult = miruro.load("${miruro.mainUrl}/info/$aniId") as? com.lagradost.cloudstream3.AnimeLoadResponse
                    val epList = if (isDub) loadResult?.episodes?.get(DubStatus.Dubbed) else loadResult?.episodes?.get(DubStatus.Subbed)
                    val matchedEp = epList?.find { it.episode == episode }
                    if (matchedEp == null) {
                        Log.d("RaghavAnime", "[Miruro] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Miruro] Found episode data: ${matchedEp.data.take(80)}")
                        miruro.loadLinks(matchedEp.data, false, subtitleCallback, miruroCallback)
                    }
                    Log.d("RaghavAnime", "[Miruro] END: $miruroLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[Miruro] FAILED: ${e.message}") }
            },
            {
                // 2. AniSuge
                var aniSugeLinks = 0
                val aniSugeCallback: (ExtractorLink) -> Unit = { link ->
                    aniSugeLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[AniSuge] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val aniSuge = AniSugeProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { aniSuge.search(it) },
                        doLoad = { aniSuge.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[AniSuge] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[AniSuge] Found episode data: ${epData.take(80)}")
                        aniSuge.loadLinks(epData, false, subtitleCallback, aniSugeCallback)
                    }
                    Log.d("RaghavAnime", "[AniSuge] END: $aniSugeLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[AniSuge] FAILED: ${e.message}") }
            },
            {
                // 3. AniWaves
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
                    // AniWaves encodes sub/dub in the data string — always load from Subbed list and flip the prefix
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        val searchResults = try { aniWaves.search(t) } catch (_: Throwable) { continue }
                        // Only consider results with at least a partial title match (score >= 1)
                        val candidates = searchResults.mapNotNull { r ->
                            val c = cleanTitle(r.name)
                            val score = when {
                                aniWavesTargets.contains(c) -> 2
                                aniWavesTargets.any { tgt -> tgt.contains(c) || c.contains(tgt) } -> 1
                                else -> 0
                            }
                            if (score > 0) Pair(score, r) else null
                        }.sortedByDescending { it.first }
                        for ((_, result) in candidates) {
                            try {
                                val loadResult = aniWaves.load(result.url) as? com.lagradost.cloudstream3.AnimeLoadResponse ?: continue
                                val ep = loadResult.episodes?.get(DubStatus.Subbed)?.find { it.episode == episode } ?: continue
                                val parts = ep.data.split("|").toMutableList()
                                parts[0] = if (isDub) "dub" else "sub"
                                matchedData = parts.joinToString("|")
                                break
                            } catch (_: Throwable) { continue }
                        }
                        if (matchedData != null) break
                    }
                    if (matchedData == null) {
                        Log.d("RaghavAnime", "[AniWaves] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[AniWaves] Found episode data: ${matchedData.take(80)}")
                        aniWaves.loadLinks(matchedData, false, subtitleCallback, aniWavesCallback)
                    }
                    Log.d("RaghavAnime", "[AniWaves] END: $aniWavesLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[AniWaves] FAILED: ${e.message}") }
            },
            {
                // 4. Anikai
                var anikaiLinks = 0
                val anikaiCallback: (ExtractorLink) -> Unit = { link ->
                    anikaiLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Anikai] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val anikai = Anikai()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { anikai.search(it) },
                        doLoad = { anikai.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[Anikai] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Anikai] Found episode data: ${epData.take(80)}")
                        anikai.loadLinks(epData, false, subtitleCallback, anikaiCallback)
                    }
                    Log.d("RaghavAnime", "[Anikai] END: $anikaiLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[Anikai] FAILED: ${e.message}") }
            },
            {
                // 5. AniDb
                var aniDbLinks = 0
                val aniDbCallback: (ExtractorLink) -> Unit = { link ->
                    aniDbLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[AniDb] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val aniDb = AniDb()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { q -> aniDb.search(q, 1).items },
                        doLoad = { aniDb.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[AniDb] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[AniDb] Found episode data: ${epData.take(80)}")
                        aniDb.loadLinks(epData, false, subtitleCallback, aniDbCallback)
                    }
                    Log.d("RaghavAnime", "[AniDb] END: $aniDbLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[AniDb] FAILED: ${e.message}") }
            },
            {
                // 6. Anikage
                var anikageLinks = 0
                val anikageCallback: (ExtractorLink) -> Unit = { link ->
                    anikageLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Anikage] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val anikage = AnikageProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { anikage.search(it) },
                        doLoad = { anikage.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[Anikage] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Anikage] Found episode data: ${epData.take(80)}")
                        anikage.loadLinks(epData, false, subtitleCallback, anikageCallback)
                    }
                    Log.d("RaghavAnime", "[Anikage] END: $anikageLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[Anikage] FAILED: ${e.message}") }
            },
            {
                // 7. Anineko
                var aninekoLinks = 0
                val aninekoCallback: (ExtractorLink) -> Unit = { link ->
                    aninekoLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Anineko] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val anineko = Anineko()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { anineko.search(it) },
                        doLoad = { anineko.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[Anineko] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Anineko] Found episode data: ${epData.take(80)}")
                        anineko.loadLinks(epData, false, subtitleCallback, aninekoCallback)
                    }
                    Log.d("RaghavAnime", "[Anineko] END: $aninekoLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[Anineko] FAILED: ${e.message}") }
            },
            {
                // 8. Animetsu
                var animetsuLinks = 0
                val animetsuCallback: (ExtractorLink) -> Unit = { link ->
                    animetsuLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Animetsu] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val animetsu = AnimetsuProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { animetsu.search(it) },
                        doLoad = { animetsu.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[Animetsu] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Animetsu] Found episode data: ${epData.take(80)}")
                        animetsu.loadLinks(epData, false, subtitleCallback, animetsuCallback)
                    }
                    Log.d("RaghavAnime", "[Animetsu] END: $animetsuLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[Animetsu] FAILED: ${e.message}") }
            },
            {
                // 9. 2DHive
                var twoDHiveLinks = 0
                val twoDHiveCallback: (ExtractorLink) -> Unit = { link ->
                    twoDHiveLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[2DHive] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val twoDHive = RaghavTwoDHive()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { twoDHive.search(it) },
                        doLoad = { twoDHive.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[2DHive] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[2DHive] Found episode data: ${epData.take(80)}")
                        twoDHive.loadLinks(epData, false, subtitleCallback, twoDHiveCallback)
                    }
                    Log.d("RaghavAnime", "[2DHive] END: $twoDHiveLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[2DHive] FAILED: ${e.message}") }
            },
            {
                // 10. AniKoto
                var anikotoLinks = 0
                val anikotoCallback: (ExtractorLink) -> Unit = { link ->
                    anikotoLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[AniKoto] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val anikoto = RaghavAnikoto()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { anikoto.search(it) },
                        doLoad = { anikoto.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[AniKoto] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[AniKoto] Found episode data: ${epData.take(80)}")
                        anikoto.loadLinks(epData, false, subtitleCallback, anikotoCallback)
                    }
                    Log.d("RaghavAnime", "[AniKoto] END: $anikotoLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[AniKoto] FAILED: ${e.message}") }
            },
            {
                // 11. Enma
                var enmaLinks = 0
                val enmaCallback: (ExtractorLink) -> Unit = { link ->
                    enmaLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Enma] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val enma = RaghavEnma()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { enma.search(it) },
                        doLoad = { enma.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[Enma] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Enma] Found episode data: ${epData.take(80)}")
                        enma.loadLinks(epData, false, subtitleCallback, enmaCallback)
                    }
                    Log.d("RaghavAnime", "[Enma] END: $enmaLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[Enma] FAILED: ${e.message}") }
            },
            {
                // 12. Animo (4animo.xyz)
                var animoLinks = 0
                val animoCallback: (ExtractorLink) -> Unit = { link ->
                    animoLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Animo] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val animo = RaghavAnimo()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { animo.search(it) },
                        doLoad = { animo.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[Animo] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Animo] Found episode data: ${epData.take(80)}")
                        // Inject isDub flag into AnimoEpData so loadLinks knows which type to fetch
                        val animoEpData = parseJson<com.laddu100.raghavanime.RaghavAnimo.AnimoEpData>(epData).copy(isDub = isDub)
                        Log.d("RaghavAnime", "[Animo] Injected isDub=$isDub into episode data")
                        animo.loadLinks(animoEpData.toJson(), false, subtitleCallback, animoCallback)
                    }
                    Log.d("RaghavAnime", "[Animo] END: $animoLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[Animo] FAILED: ${e.message}") }
            },
            {
                // 13. Anidap (anidap.se) — sub/dub with per-anime providers
                var anidapLinks = 0
                val anidapCallback: (ExtractorLink) -> Unit = { link ->
                    anidapLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Anidap] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val anidap = RaghavAnidap()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { anidap.search(it) },
                        doLoad = { anidap.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[Anidap] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Anidap] Found episode data: ${epData.take(80)}")
                        anidap.loadLinks(epData, false, subtitleCallback, anidapCallback)
                    }
                    Log.d("RaghavAnime", "[Anidap] END: $anidapLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[Anidap] FAILED: ${e.message}") }
            },
            {
                // 14. Senshi (senshi.live) — mostly sub, some dub
                var senshiLinks = 0
                val senshiCallback: (ExtractorLink) -> Unit = { link ->
                    senshiLinks++
                    callback.invoke(link)
                }
                try {
                    Log.d("RaghavAnime", "[Senshi] START: $title ep$episode ${if (isDub) "dub" else "sub"}")
                    val senshi = RaghavSenshi()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { senshi.search(it) },
                        doLoad = { senshi.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData == null) {
                        Log.d("RaghavAnime", "[Senshi] No episode data found")
                    } else {
                        Log.d("RaghavAnime", "[Senshi] Found episode data: ${epData.take(80)}")
                        senshi.loadLinks(epData, false, subtitleCallback, senshiCallback)
                    }
                    Log.d("RaghavAnime", "[Senshi] END: $senshiLinks links found")
                } catch (e: Throwable) { Log.e("RaghavAnime", "[Senshi] FAILED: ${e.message}") }
            }
        )

        return true
    }

    private fun cleanTitle(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun matchSearchResult(searchResults: List<SearchResponse>, targetTitles: List<String>): SearchResponse? {
        val cleanedTargets = targetTitles.map { cleanTitle(it) }
        // Pass 1: exact match
        for (res in searchResults) {
            val cleanedRes = cleanTitle(res.name)
            if (cleanedTargets.contains(cleanedRes)) {
                return res
            }
        }
        // Pass 2: substring match
        for (res in searchResults) {
            val cleanedRes = cleanTitle(res.name)
            if (cleanedTargets.any { target -> target.contains(cleanedRes) || cleanedRes.contains(target) }) {
                return res
            }
        }
        // No match — return null so the caller truly skips this result
        return null
    }

    /**
     * Exhaustively search all results from all titles for an episode match.
     * Pass 1: try exact title matches only (score 2) across all search titles.
     * Pass 2: if no exact match found, try partial matches (score 1).
     * This prevents wrong-season anime from loading when a partial title match
     * returns a different season (e.g. "Bleach" matching "Bleach TYBW").
     */
    private suspend fun findEpisodeData(
        searchTitles: List<String>,
        targetTitles: List<String>,
        episode: Int,
        isDub: Boolean,
        doSearch: suspend (String) -> List<SearchResponse>,
        doLoad: suspend (String) -> com.lagradost.cloudstream3.AnimeLoadResponse?,
        dubKey: com.lagradost.cloudstream3.DubStatus = com.lagradost.cloudstream3.DubStatus.Dubbed,
        subKey: com.lagradost.cloudstream3.DubStatus = com.lagradost.cloudstream3.DubStatus.Subbed
    ): String? {
        val cleanedTargets = targetTitles.map { cleanTitle(it) }
        val epKey = if (isDub) dubKey else subKey

        val allCandidates = mutableListOf<Pair<Int, SearchResponse>>()
        for (t in searchTitles) {
            val searchResults = try { doSearch(t) } catch (_: Throwable) { continue }
            for (r in searchResults) {
                val c = cleanTitle(r.name)
                val score = when {
                    cleanedTargets.contains(c) -> 2
                    cleanedTargets.any { tgt -> tgt.contains(c) || c.contains(tgt) } -> 1
                    else -> 0
                }
                if (score > 0) allCandidates.add(Pair(score, r))
            }
        }

        allCandidates.sortByDescending { it.first }

        for ((score, result) in allCandidates) {
            if (score < 2) continue
            try {
                val loadResult = doLoad(result.url) ?: continue
                val ep = loadResult.episodes?.get(epKey)?.find { it.episode == episode }
                if (ep != null) return ep.data
            } catch (_: Throwable) { continue }
        }

        for ((score, result) in allCandidates) {
            if (score < 1) continue
            try {
                val loadResult = doLoad(result.url) ?: continue
                val ep = loadResult.episodes?.get(epKey)?.find { it.episode == episode }
                if (ep != null) return ep.data
            } catch (_: Throwable) { continue }
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
        // Cache for home page data — used as fallback when AniList fails
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
