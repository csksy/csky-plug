package com.csksy.xanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Xanime : MainAPI() {

    override var mainUrl = "https://xanime.me"
    override var name = "Xanime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "sort=field_update" to "Latest Updates",
        "sort=field_date_create" to "Recently Added",
        "sort=field_score" to "Top Rated",
        "type=Movie" to "Movies",
        "type=OVA" to "OVA",
        "type=ONA" to "ONA",
        "status=currently_airing" to "Currently Airing",
        "audio=dub" to "Dubbed",
        "genre=action" to "Action",
        "genre=comedy" to "Comedy",
        "genre=romance" to "Romance",
        "genre=fantasy" to "Fantasy",
        "genre=isekai" to "Isekai",
        "genre=slice_of_life" to "Slice of Life",
        "genre=supernatural" to "Supernatural"
    )

    private fun tvTypeOf(types: List<String>): TvType = when {
        types.any { it.equals("Movie", true) } -> TvType.AnimeMovie
        types.any {
            it.equals("OVA", true) || it.equals("ONA", true) || it.equals("Special", true)
        } -> TvType.OVA
        else -> TvType.Anime
    }

    private fun toSearch(entry: XanimeApi.TitleEntry): SearchResponse? {
        return newAnimeSearchResponse(entry.title, entry.aniId, tvTypeOf(entry.types)) {
            this.posterUrl = entry.poster
            this.year = entry.year
            val hasSub = entry.audio.isEmpty() ||
                entry.audio.any { it == "sub" || it == "raw" }
            addDubStatus(
                dubExist = entry.audio.any { it == "dub" },
                subExist = hasSub
            )
        }
    }

    private fun parseFilters(data: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in data.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) out[pair.substring(0, idx)] = pair.substring(idx + 1)
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val filters = parseFilters(request.data)
        val items = XanimeApi.browse(
            sortby = filters["sort"],
            genre = filters["genre"],
            type = filters["type"],
            status = filters["status"],
            audio = filters["audio"],
            page = page
        ).mapNotNull { toSearch(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> =
        XanimeApi.search(query).mapNotNull { toSearch(it) }

    // the audio tab the episode was opened from rides along in the id so
    // loadLinks only hands out streams matching that tab
    private fun episodeData(epId: String, audio: String): String = "$epId|$audio"

    private fun parseEpisodeData(raw: String): Pair<String, String>? {
        val parts = raw.split("|")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return parts[0] to parts[1]
    }

    override suspend fun load(url: String): LoadResponse? {
        // cloudstream hands over the id as a full mainUrl link, only the tail
        // segment is the ani id
        val aniId = url.substringBefore('?').substringAfterLast('/')
            .takeIf { it.isNotBlank() } ?: return null
        val detail = XanimeApi.detail(aniId) ?: return null

        val episodes = XanimeApi.episodes(aniId)
        val siteType = tvTypeOf(detail.types)

        val subEps = ArrayList<Episode>()
        val dubEps = ArrayList<Episode>()
        for (ep in episodes) {
            val hasSub = ep.audio.any { it == "sub" }
            val hasDub = ep.audio.any { it == "dub" }
            val hasRaw = ep.audio.any { it == "raw" }
            val number = if (ep.index > 0) ep.index else subEps.size + dubEps.size + 1
            val single = episodes.size == 1
            if (hasSub || (!hasDub && hasRaw)) {
                val audio = if (hasSub) "sub" else "raw"
                subEps.add(newEpisode(episodeData(ep.epId, audio)) {
                    this.episode = number
                    this.name = when {
                        single && audio == "sub" -> "Sub"
                        single -> "Raw"
                        audio == "sub" -> ep.title
                        else -> "${ep.title} (Raw)"
                    }
                })
            }
            if (hasDub) {
                dubEps.add(newEpisode(episodeData(ep.epId, "dub")) {
                    this.episode = number
                    this.name = if (single) "Dub" else ep.title
                })
            }
        }

        val tags = ArrayList<String>()
        detail.genres.forEach { tags.add(it.replace('_', ' ').replaceFirstChar { c -> c.uppercase() }) }

        // the dub and sub chips only render on series pages, a movie carrying
        // both tracks is presented as a single episode series so the switcher
        // stays reachable, single audio movies keep the real movie layout
        val tvType = if (siteType == TvType.AnimeMovie && dubEps.isNotEmpty()) {
            TvType.Anime
        } else {
            siteType
        }

        return newAnimeLoadResponse(detail.title, aniId, tvType) {
            this.posterUrl = detail.poster
            this.backgroundPosterUrl = detail.background
            this.plot = detail.description
            this.tags = tags
            this.year = detail.year
            this.showStatus = when (detail.status) {
                "currently_airing" -> ShowStatus.Ongoing
                "finished_airing" -> ShowStatus.Completed
                else -> null
            }
            detail.score?.let { this.score = Score.from10(it / 10f) }
            addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    private fun audioLabel(audio: String): String = when (audio) {
        "sub" -> "SUB"
        "dub" -> "DUB"
        else -> "RAW"
    }

    private fun serverName(raw: String): String =
        if (raw.all { it.isDigit() }) "Server $raw" else raw

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // cloudstream can glue mainUrl in front of non http ids, drop it so the
        // epId|audio pair is found
        val clean = data.removePrefix("$mainUrl/").removePrefix("/")
        val (epId, audio) = parseEpisodeData(clean) ?: return false
        if (epId.isBlank()) return false
        val sources = XanimeApi.sources(epId).filter { it.type == audio }
        if (sources.isEmpty()) return false

        val playHeaders = mapOf("User-Agent" to XanimeApi.USER_AGENT)
        val sameNameCount = HashMap<String, Int>()
        var anyEmitted = false

        for (source in sources) {
            // the tokens are minted per api call so links are always fresh, a
            // quick master probe keeps dead cdns out of the list
            val master = XanimeApi.fetchText(source.path) ?: continue
            if (!master.contains("#EXTM3U")) continue

            val count = sameNameCount.merge(source.name, 1, Int::plus) ?: 1
            val base = serverName(source.name)
            val nameLabel = if (count > 1) "$base $count" else base
            val label = "$nameLabel (${audioLabel(audio)})"

            callback.invoke(
                newExtractorLink(
                    name,
                    label,
                    source.path,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = playHeaders
                    // master playlist carries every resolution, the player
                    // quality picker exposes them as tracks
                    this.quality = Qualities.Unknown.value
                }
            )
            anyEmitted = true

            if (source.tracks.isNotEmpty() && XanimeApi.tracksAreReal(source)) {
                val seen = HashSet<String>()
                for (track in source.tracks) {
                    if (!seen.add(track.url)) continue
                    subtitleCallback.invoke(newSubtitleFile(track.label, track.url))
                }
            }
        }
        return anyEmitted
    }
}
