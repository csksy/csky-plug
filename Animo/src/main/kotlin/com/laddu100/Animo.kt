package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import java.net.URLEncoder

class Animo : MainAPI() {
    override var mainUrl = "https://4animo.xyz"
    override var name = "Animo"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "https://api.kryzox.xyz"
    private val cdnUrl = "https://cdn.4animo.xyz"

    // Use desktop UA — the site works better with it (confirmed via HAR + curl testing)
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
    private val apiHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        Pair("trending", "Trending"),
        Pair("recently-updated", "Recently Updated"),
        Pair("recently-added", "Recently Added"),
        Pair("top", "Top Rated"),
        Pair("movie", "Movies"),
        Pair("tv", "TV Series"),
        Pair("ova", "OVA"),
        Pair("ona", "ONA"),
        Pair("special", "Specials"),
        Pair("completed", "Completed")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("animo") ?: mainUrl
        val url = "$apiUrl/anime/${request.data}?page=$page&limit=20"
        return try {
            val items = parseAnimeList(app.get(url, headers = apiHeaders).text)
            val home = items.mapNotNull { it.toSearchResponse() }
            newHomePageResponse(request.name, home, hasNext = home.size == 20)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun parseAnimeList(text: String): List<AnimeSearchItem> {
        return try {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) {
                parseJson<List<AnimeSearchItem>>(text)
            } else {
                parseJson<SearchResponseData>(text).data ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("animo") ?: mainUrl
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$apiUrl/anime/search?keyword=$encoded&page=1&limit=20"
            val resp = parseJson<SearchResponseData>(app.get(url, headers = apiHeaders).text)
            resp.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("animo") ?: mainUrl
        val animeId = url.substringAfterLast("/").toIntOrNull() ?: return null

        val anime = try {
            parseJson<AnimeDetails>(app.get("$apiUrl/anime/$animeId", headers = apiHeaders).text)
        } catch (e: Exception) {
            return null
        }
        val title = anime.titles?.english ?: anime.titles?.romaji ?: return null

        val episodes = try {
            parseJson<EpisodesResponse>(app.get("$apiUrl/anime/$animeId/episodes", headers = apiHeaders).text).data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()

        episodes.forEach { ep ->
            val num = ep.number ?: return@forEach
            val epId = ep.id ?: return@forEach
            val epName = ep.titles?.en ?: ep.titles?.romaji ?: "Episode $num"
            // ani field format: "154587/1" (anilistId/episodeNum) — used for hd-1/hd-2 embeds
            val ani = ep.ani ?: ""
            if (ep.sub == true) {
                subEps.add(newEpisode(EpisodeData(animeId, epId, ep.embed_id, num, ani, "sub").toJson()) {
                    this.episode = num
                    this.name = epName
                    this.posterUrl = ep.thumbnail
                })
            }
            if (ep.dub == true) {
                dubEps.add(newEpisode(EpisodeData(animeId, epId, ep.embed_id, num, ani, "dub").toJson()) {
                    this.episode = num
                    this.name = epName
                    this.posterUrl = ep.thumbnail
                })
            }
        }

        val tvType = when (anime.type?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        val year = anime.air?.start?.substringBefore("-")?.toIntOrNull()
        val finalType = if (tvType == TvType.AnimeMovie && dubEps.isNotEmpty()) TvType.Anime else tvType

        return newAnimeLoadResponse(title, url, finalType) {
            this.posterUrl = anime.images?.poster
            this.plot = anime.synopsis
            this.year = year
            this.tags = anime.genres
            if (anime.score != null) this.score = Score.from10(anime.score.toString())
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    /**
     * DIRECT HTTP APPROACH — no WebView needed!
     *
     * Confirmed via curl + Python requests testing:
     * 1. Embed page is NOT behind CF challenge — accessible via direct HTTP
     * 2. getSources API token is embedded in the embed page HTML (var sourcesUrl)
     * 3. The /p?t= m3u8 endpoint requires HTTP keep-alive (same TCP connection)
     *    as the getSources call — CloudStream's app.get uses OkHttp which
     *    maintains connection pools, so keep-alive works automatically
     * 4. No cf_clearance cookie needed for any endpoint
     *
     * Embed URL formats (confirmed working via testing):
     * - a-1/{episodeId}/{type}?k=1&autoPlay=1&skipIntro=1&skipOutro=1
     * - s-1/{embedId}/{type}?k=1&autoPlay=1&skipIntro=1&skipOutro=1
     * - hd-1/ani/{aniField}/{type}?k=1&autoPlay=1&skipIntro=1&skipOutro=1
     * - hd-2/ani/{aniField}/{type}?k=1&autoPlay=1&skipIntro=1&skipOutro=1
     *
     * The ani field from API is like "154587/1" (anilistId/episodeNum)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            parseJson<EpisodeData>(data)
        } catch (e: Exception) {
            Log.e("Animo", "loadLinks: failed to parse data: ${e.message}")
            return false
        }

        Log.i("Animo", "========== loadLinks START ==========")
        Log.i("Animo", "episodeId=${epData.episodeId} embedId=${epData.embedId} animeId=${epData.animeId} epNum=${epData.episodeNum} ani='${epData.ani}' streamType=${epData.streamType}")

        val type = epData.streamType
        val queryParams = "?k=1&autoPlay=1&skipIntro=1&skipOutro=1"

        // Build embed URL candidates — episode NUMBER not ID for a-1
        val embedFormats = mutableListOf<Pair<String, String>>()
        embedFormats.add(Pair("a-1", "$cdnUrl/embed/a-1/${epData.episodeId}/$type$queryParams"))
        embedFormats.add(Pair("s-1", "$cdnUrl/embed/s-1/${epData.embedId ?: epData.episodeId}/$type$queryParams"))
        // hd-1/hd-2 use the ani field (format: "anilistId/episodeNum")
        if (epData.ani.isNotEmpty()) {
            embedFormats.add(Pair("hd-1", "$cdnUrl/embed/hd-1/ani/${epData.ani}/$type$queryParams"))
            embedFormats.add(Pair("hd-2", "$cdnUrl/embed/hd-2/ani/${epData.ani}/$type$queryParams"))
        }

        var found = false
        var subtitlesAdded = false

        for ((labelKey, embedUrl) in embedFormats) {
            Log.i("Animo", "[$labelKey] Trying: $embedUrl")
            try {
                // Step 1: Fetch embed page to get the sourcesUrl token
                val embedResp = app.get(embedUrl, headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.7"
                ), timeout = 15_000L)
                Log.i("Animo", "[$labelKey] Embed HTTP ${embedResp.code}")
                if (embedResp.code != 200) continue

                val embedHtml = embedResp.text
                // Extract token from: var sourcesUrl = '/stream/getSources?t=TOKEN';
                val tokenMatch = Regex("getSources\\?t=([A-Za-z0-9_.-]+)").find(embedHtml)
                if (tokenMatch == null) {
                    Log.e("Animo", "[$labelKey] No sourcesUrl token in embed HTML")
                    continue
                }
                val token = tokenMatch.groupValues[1]
                Log.i("Animo", "[$labelKey] Token: ${token.take(30)}...")

                // Step 2: Call getSources API — MUST use same app.get (OkHttp keep-alive)
                val sourcesUrl = "$cdnUrl/stream/getSources?t=$token"
                val sourcesResp = app.get(sourcesUrl, headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "*/*",
                    "Referer" to embedUrl,
                    "Sec-Fetch-Site" to "same-origin",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Dest" to "empty"
                ), timeout = 15_000L)
                Log.i("Animo", "[$labelKey] getSources HTTP ${sourcesResp.code}")
                if (sourcesResp.code != 200) {
                    Log.e("Animo", "[$labelKey] getSources failed: ${sourcesResp.code}")
                    continue
                }

                val sourcesText = sourcesResp.text
                if (sourcesText.contains("invalid token") || sourcesText.contains("error")) {
                    Log.e("Animo", "[$labelKey] getSources error: ${sourcesText.take(100)}")
                    continue
                }

                val sources = parseJson<GetSourcesResponse>(sourcesText)
                Log.i("Animo", "[$labelKey] Sources: ${sources.sources?.size ?: 0}, Tracks: ${sources.tracks?.size ?: 0}")

                // Step 3: Fetch master m3u8 — MUST use same app.get (OkHttp keep-alive)
                val masterFile = sources.sources?.firstOrNull()?.file
                if (masterFile == null) {
                    Log.e("Animo", "[$labelKey] No source file in getSources response")
                    continue
                }
                val masterUrl = if (masterFile.startsWith("http")) masterFile else "$cdnUrl/${masterFile.removePrefix("/")}"
                Log.i("Animo", "[$labelKey] Fetching master m3u8: ${masterUrl.take(80)}...")

                val masterResp = app.get(masterUrl, headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "*/*",
                    "Referer" to embedUrl,
                    "Sec-Fetch-Site" to "same-origin",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Dest" to "empty"
                ), timeout = 15_000L)
                Log.i("Animo", "[$labelKey] Master m3u8 HTTP ${masterResp.code} (${masterResp.text.length} bytes)")
                if (masterResp.code != 200) {
                    Log.e("Animo", "[$labelKey] Master fetch failed: ${masterResp.code}")
                    continue
                }

                val masterContent = masterResp.text
                if (!masterContent.trim().startsWith("#EXTM3U")) {
                    Log.e("Animo", "[$labelKey] Master not M3U8: ${masterContent.take(100)}")
                    continue
                }

                // Step 4: Pass master m3u8 URL directly as a single source.
                // The master playlist contains quality variants — the player
                // (ExoPlayer) will parse them and offer quality selection in UI.
                // Do NOT split into separate sources.
                val playHeaders = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "*/*",
                    "Referer" to embedUrl,
                    "Origin" to cdnUrl
                )

                val label = "$name $labelKey ($type)"
                Log.i("Animo", "[$labelKey] Adding source: $label")
                callback.invoke(
                    newExtractorLink(label, label, masterUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = embedUrl
                        this.headers = playHeaders
                    }
                )
                found = true

                // Step 5: Add subtitle tracks (only from first successful source
                // to avoid duplicates)
                if (!subtitlesAdded && sources.tracks?.isNotEmpty() == true) {
                    sources.tracks.forEach { t ->
                        val subFile = t.file ?: return@forEach
                        val subUrl = if (subFile.startsWith("http")) subFile else "$cdnUrl/${subFile.removePrefix("/")}"
                        val subLabel = t.label ?: "Subtitles"
                        Log.i("Animo", "[$labelKey] Subtitle: $subLabel")
                        subtitleCallback.invoke(newSubtitleFile(subLabel, subUrl) {
                            this.headers = playHeaders
                        })
                    }
                    subtitlesAdded = true
                }

                // Do NOT break — continue trying all 4 sources
            } catch (e: Exception) {
                Log.e("Animo", "[$labelKey] Exception: ${e.message}")
            }
        }

        Log.i("Animo", "========== loadLinks END (found=$found) ==========")
        return found
    }

    private fun AnimeSearchItem.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val title = titles?.english ?: titles?.romaji ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
            this.posterUrl = images?.poster
            addDubStatus(dubExist = (dub_count ?: 0) > 0, subExist = (sub_count ?: 0) > 0)
        }
    }

    data class EpisodeData(
        val animeId: Int,
        val episodeId: Int,
        val embedId: String?,
        val episodeNum: Int,
        val ani: String,  // e.g. "154587/1" — used for hd-1/hd-2 embeds
        val streamType: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeSearchItem(
        val id: Int? = null,
        val slug: String? = null,
        val titles: Titles? = null,
        val images: Images? = null,
        val type: String? = null,
        val status: String? = null,
        val episodes_count: Int? = null,
        val sub_count: Int? = null,
        val dub_count: Int? = null,
        val score: Double? = null,
        val season_year: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResponseData(
        val success: Boolean? = null,
        val data: List<AnimeSearchItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Images(
        val poster: String? = null,
        val banner: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Titles(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDetails(
        val id: Int? = null,
        val slug: String? = null,
        val titles: Titles? = null,
        val synopsis: String? = null,
        val images: Images? = null,
        val type: String? = null,
        val status: String? = null,
        val score: Double? = null,
        val rating: String? = null,
        val air: Air? = null,
        val genres: List<String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Air(
        val start: String? = null,
        val end: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResponse(
        val anime_id: Int? = null,
        val total: Int? = null,
        val sub_count: String? = null,
        val dub_count: String? = null,
        val data: List<EpisodeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        val id: Int? = null,
        val number: Int? = null,
        val titles: EpisodeTitles? = null,
        val filler: Boolean? = null,
        val rating: String? = null,
        val thumbnail: String? = null,
        val sub: Boolean? = null,
        val dub: Boolean? = null,
        @JsonProperty("embed_id") val embed_id: String? = null,
        val ani: String? = null,  // e.g. "154587/1" — anilistId/episodeNum
        val mal: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeTitles(
        val en: String? = null,
        val ja: String? = null,
        val romaji: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GetSourcesResponse(
        val sources: List<MegaSource>? = null,
        val tracks: List<MegaTrack>? = null,
        val encrypted: Boolean? = null,
        val server: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaSource(
        val file: String? = null,
        val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaTrack(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null,
        val default: Boolean? = null
    )
}
