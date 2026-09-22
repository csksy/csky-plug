package com.gotaku

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class GoTaku : MainAPI() {
    override var mainUrl = "https://gotaku.to"
    override var name = "GoTaku"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries,
        TvType.OVA
    )

    companion object {
        private const val TAG = "GoTaku"
    }

    override val mainPage = mainPageOf(
        "trending_day" to "Trending Today",
        "trending_week" to "Trending This Week",
        "latest" to "Recently Updated",
        "track:sub" to "Latest Sub",
        "track:dub" to "Latest Dub",
        "format:MOVIE" to "Anime Movies",
        "format:ONA" to "ONA"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val params = mutableMapOf(
            "sort" to "latest",
            "limit" to "28",
            "page" to page.toString()
        )
        when {
            request.data == "trending_day" || request.data == "trending_week" -> params["sort"] = request.data
            request.data.startsWith("track:") -> params["track"] = request.data.removePrefix("track:")
            request.data.startsWith("format:") -> params["format"] = request.data.removePrefix("format:")
        }
        val (titles, hasMore) = GoTakuApi.fetchTitles(params)
        val items = titles.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = hasMore)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val (titles, _) = GoTakuApi.fetchTitles(mapOf("q" to query, "limit" to "28"))
        return titles.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val titleId = url.substringAfter("title/").substringBefore("?").takeIf { it.isNotBlank() } ?: return null
        val detail = GoTakuApi.fetchTitleDetail(titleId) ?: return null
        val title = detail.name ?: return null
        val episodes = GoTakuApi.fetchEpisodes(titleId)

        val poster = detail.poster_url
        val backdrop = detail.backdrop_url
        val plot = detail.synopsis?.takeIf { it.isNotBlank() }
        val year = detail.year
        val tags = detail.genres.orEmpty().mapNotNull { it.name }
        val duration = detail.duration_minutes?.takeIf { it > 0 }
        val rating = detail.age_rating

        val isMovie = detail.format == "MOVIE"
        val tvType = when (detail.format) {
            "MOVIE" -> if (detail.is_adult == true) TvType.Movie else TvType.AnimeMovie
            "OVA" -> TvType.OVA
            "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        return if (isMovie || episodes.size <= 1) {
            val episode = episodes.firstOrNull()
            val data = GoTakuEpisodeData(titleId, episode?.id ?: "", episode?.name)
            newMovieLoadResponse(title, url, tvType, data.toJson()) {
                this.posterUrl = poster ?: backdrop
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.tags = tags
                this.year = year
                this.duration = duration
                this.contentRating = rating
            }
        } else {
            val episodeList = episodes.mapNotNull { entry ->
                val id = entry.id ?: return@mapNotNull null
                val data = GoTakuEpisodeData(titleId, id, entry.name)
                newEpisode(data.toJson()) {
                    this.name = entry.name?.takeIf { it.isNotBlank() && it != entry.label }
                        ?: "Episode ${entry.number ?: entry.label}"
                    this.episode = entry.number
                    this.posterUrl = entry.thumbnail_url
                    this.description = buildString {
                        entry.is_filler?.let { if (it) append("Filler episode") }
                        entry.aired_at?.let {
                            if (isNotEmpty()) append(" | ")
                            append("Aired ${it.substringBefore("T")}")
                        }
                    }.takeIf { it.isNotBlank() }
                }
            }
            newTvSeriesLoadResponse(title, url, tvType, episodeList) {
                this.posterUrl = poster ?: backdrop
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.tags = tags
                this.year = year
                this.duration = duration
                this.contentRating = rating
            }
        }
    }

    private data class TrackLinks(
        val label: String,
        val embedUrl: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            parseJson<GoTakuEpisodeData>(data)
        } catch (e: Exception) {
            Log.d(TAG, "episode data parse failed: ${e.message}")
            return false
        }
        if (epData.episodeId.isBlank()) return false

        // availability comes from the episode itself so freshly added dubs show
        // up without reloading the info page, the raw embed probe covers the
        // case where that lookup fails
        val info = GoTakuApi.fetchEpisodeInfo(epData.episodeId)
        val tracks = mutableListOf<TrackLinks>()
        for ((type, label) in listOf("soft_sub" to "Sub", "hard_sub" to "Sub Hardsub", "dub" to "Dub")) {
            if (info != null && info.trackAvailable(type) != true) continue
            GoTakuApi.fetchEmbed(epData.episodeId, type)?.let { tracks.add(TrackLinks(label, it)) }
        }
        if (tracks.isEmpty()) return false

        var found = false
        for (track in tracks) {
            val stream = resolveStream(track.embedUrl) ?: continue
            val qualities = parseQualities(stream.masterPlaylist)
            if (qualities.isEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = track.label,
                        url = "${stream.proxyUrl}/m/0/master.m3u8",
                        type = ExtractorLinkType.M3U8
                    )
                )
                found = true
            } else {
                qualities.forEach { (label, quality, index) ->
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "${track.label} $label",
                            url = "${stream.proxyUrl}/m/$index/master.m3u8",
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = quality
                        }
                    )
                }
                found = true
            }
        }
        return found
    }

    class ResolvedStream(
        val proxyUrl: String,
        val masterPlaylist: String
    )

    private suspend fun resolveStream(embedUrl: String): ResolvedStream? {
        return try {
            val html = com.lagradost.cloudstream3.app.get(
                GoTakuApi.SITE + embedUrl,
                headers = GoTakuApi.browserHeaders + mapOf("Referer" to "${GoTakuApi.SITE}/")
            ).text

            val base = Regex("""data-manifest-base="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
            val stamp = Regex("""data-manifest-stamp="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null

            // the cdn turns away the first manifest now and then, a second
            // manifest fetch with the same stamp gets a working token
            var master: String? = null
            var manifest: GoTakuApi.ManifestInfo? = null
            for (attempt in 0 until 3) {
                manifest = GoTakuApi.fetchManifest(base, stamp) ?: continue
                val key = GoTakuCrypto.playlistKey(manifest.keySeed, manifest.token)
                val masterBytes = try {
                    val response = com.lagradost.cloudstream3.app.get(
                        manifest.source,
                        headers = GoTakuApi.browserHeaders + mapOf(
                            "Referer" to "${GoTakuApi.SITE}/",
                            "Origin" to GoTakuApi.SITE
                        )
                    )
                    if (response.isSuccessful) response.body.bytes() else null
                } catch (e: Exception) {
                    Log.d(TAG, "master attempt ${attempt + 1} failed: ${e.message}")
                    null
                } ?: continue

                master = GoTakuCrypto.decryptPlaylist(key, masterBytes)
                if (master != null && master.startsWith("#EXTM3U")) break
                master = null
            }
            if (master == null || manifest == null) return null

            val proxyBase = GoTakuProxy.register(
                manifestBase = base,
                stamp = manifest.stamp,
                token = manifest.token,
                expiresAt = manifest.expiresAt,
                keySeed = manifest.keySeed,
                segmentBytes = manifest.segmentBytes,
                masterUrl = manifest.source
            ) ?: return null

            ResolvedStream(proxyBase, master)
        } catch (e: Exception) {
            Log.d(TAG, "stream resolve failed: ${e.message}")
            null
        }
    }

    // every quality pins one variant of the master, the proxy serves a single
    // level per link so the label always matches what plays
    private fun parseQualities(master: String): List<Triple<String, Int, Int>> {
        val lines = master.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<Triple<String, Int, Int>>()
        var index = -1
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(lines[i])?.groupValues?.get(2)?.toIntOrNull()
            val height = res ?: continue
            index++
            val label = when {
                height >= 2160 -> "2160p"
                height >= 1080 -> "1080p"
                height >= 720 -> "720p"
                height >= 480 -> "480p"
                height >= 360 -> "360p"
                else -> "${height}p"
            }
            val quality = when {
                height >= 2160 -> Qualities.P2160.value
                height >= 1080 -> Qualities.P1080.value
                height >= 720 -> Qualities.P720.value
                height >= 480 -> Qualities.P480.value
                else -> Qualities.P360.value
            }
            out.add(Triple(label, quality, index))
        }
        return out
    }

    private fun GoTakuApi.TitleEntry.toSearchResponse(): SearchResponse? {
        val name = this.name ?: return null
        val id = this.id ?: return null
        val isMovie = this.format == "MOVIE"
        val poster = this.poster_url ?: this.backdrop_url
        return if (isMovie) {
            newMovieSearchResponse(name, "$mainUrl/title/$id", TvType.AnimeMovie) {
                this.posterUrl = poster
                this.year = this@toSearchResponse.year
            }
        } else {
            newTvSeriesSearchResponse(name, "$mainUrl/title/$id", TvType.Anime) {
                this.posterUrl = poster
                this.year = this@toSearchResponse.year
            }
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoTakuEpisodeData(
    val titleId: String,
    val episodeId: String,
    val episodeName: String? = null
)
