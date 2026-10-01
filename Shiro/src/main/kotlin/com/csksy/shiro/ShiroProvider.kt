package com.csksy.shiro

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

class Shiro : MainAPI() {
    override var mainUrl = "https://shiro.so"
    override var name = "Shiro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "popular" to "All Time Popular",
        "rated" to "Top Rated",
        "airing" to "Currently Airing",
        "new" to "Recently Released"
    )

    private fun tvTypeOf(format: String): TvType = when (format) {
        "MOVIE" -> TvType.AnimeMovie
        "TV", "TV_SHORT" -> TvType.Anime
        else -> TvType.OVA
    }

    private fun ShiroApi.Media.toSearchResponse(): SearchResponse? {
        if (title.isBlank()) return null
        return newAnimeSearchResponse(title, "$id", tvTypeOf(format)) {
            posterUrl = this@toSearchResponse.poster
            this.score = this@toSearchResponse.score?.let { Score.from10(it.toFloat()) }
            this.year = this@toSearchResponse.year
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (media, hasNext) = when (request.data) {
            "trending" -> ShiroApi.list("TRENDING_DESC", page)
            "popular" -> ShiroApi.list("POPULARITY_DESC", page)
            "rated" -> ShiroApi.list("SCORE_DESC", page)
            "airing" -> ShiroApi.list("POPULARITY_DESC", page, statuses = listOf("RELEASING"))
            else -> ShiroApi.list("START_DATE_DESC", page)
        }
        val items = media.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val (media, _) = ShiroApi.search(query, 1)
        return media.mapNotNull { it.toSearchResponse() }
    }

    // data carries the ids, the episode number and which versions the
    // player asked for so the dub tab never mixes with the sub tab
    private fun episodeData(id: Int, malId: Int?, ep: Int, variant: String): String =
        "$id|${malId ?: 0}|$ep|$variant"

    private class EpisodeRef(val id: Int, val malId: Int?, val ep: Int, val variant: String)

    private fun parseData(data: String): EpisodeRef? {
        val parts = data.split("|")
        if (parts.size < 4) return null
        val id = parts[0].toIntOrNull() ?: return null
        val ep = parts[2].toIntOrNull() ?: return null
        val mal = parts[1].toIntOrNull()?.takeIf { it > 0 }
        return EpisodeRef(id, mal, ep, parts[3])
    }

    // mirrors the episode counting the site uses: the planned count caps
    // everything, airing progress pushes it up when the plan is unknown and
    // the real titles from anilist map onto their episode numbers
    private fun episodeCount(m: ShiroApi.Media): Int {
        val planned = m.episodes?.takeIf { it > 0 }
        val fromAiring = (m.nextEpisode?.minus(1) ?: 0).coerceAtLeast(0)
        val finished = if (m.status == "FINISHED") planned ?: 0 else 0
        val fromStreams = if (m.streamTitles.isNotEmpty()) m.streamTitles.keys.maxOrNull() ?: 0 else 0
        val computed = maxOf(fromAiring, finished, fromStreams).coerceAtLeast(0)
        return planned?.let { minOf(computed, it) } ?: computed
    }

    private fun cleanEpisodeTitle(raw: String): String =
        raw.replace(Regex("""^Episode\s*\d+\s*[-–:]\s*""", RegexOption.IGNORE_CASE), "").trim()
            .takeIf { it.isNotBlank() } ?: raw

    override suspend fun load(url: String): LoadResponse? {
        val id = url.toIntOrNull() ?: return null
        val m = ShiroApi.detail(id) ?: return null
        if (m.title.isBlank()) return null

        val isMovie = m.format == "MOVIE" || (m.episodes == 1 && m.streamTitles.isEmpty() && m.format != "TV")
        val count = episodeCount(m)

        // one cheap api ping tells whether a dub track exists at all, the
        // site itself only learns this once the player is open, movies emit
        // every version from one entry so they can skip the check
        val hasDub = if (isMovie) {
            false
        } else {
            ShiroApi.streams(id, m.idMal, 1)?.dub?.isNotEmpty() == true
        }

        return if (isMovie) {
            newAnimeLoadResponse(m.title, url, TvType.AnimeMovie) {
                posterUrl = m.poster
                backgroundPosterUrl = m.banner
                plot = m.description
                tags = m.genres
                year = m.year
                m.score?.let { score = Score.from10(it.toFloat()) }
                m.duration?.let { duration = it }
                addEpisodes(DubStatus.Subbed, listOf(newEpisode(episodeData(id, m.idMal, 1, "all")) {
                    name = m.title
                    episode = 1
                }))
                m.trailerUrl?.let { addTrailer(it) }
            }
        } else {
            val total = if (count > 0) count else 1
            val subEps = (1..total).map { n ->
                val stream = m.streamTitles[n]
                newEpisode(episodeData(id, m.idMal, n, "sub")) {
                    episode = n
                    name = stream?.let { cleanEpisodeTitle(it.first) } ?: "Episode $n"
                    stream?.second?.takeIf { it.isNotBlank() }?.let { posterUrl = it }
                }
            }
            val dubEps = if (hasDub) (1..total).map { n ->
                val stream = m.streamTitles[n]
                newEpisode(episodeData(id, m.idMal, n, "dub")) {
                    episode = n
                    name = stream?.let { cleanEpisodeTitle(it.first) } ?: "Episode $n"
                    stream?.second?.takeIf { it.isNotBlank() }?.let { posterUrl = it }
                }
            } else emptyList()
            newAnimeLoadResponse(m.title, url, tvTypeOf(m.format)) {
                posterUrl = m.poster
                backgroundPosterUrl = m.banner
                plot = m.description
                tags = m.genres
                year = m.year
                m.score?.let { score = Score.from10(it.toFloat()) }
                m.duration?.let { duration = it }
                showStatus = when (m.status) {
                    "RELEASING" -> ShowStatus.Ongoing
                    "FINISHED" -> ShowStatus.Completed
                    else -> null
                }
                addEpisodes(DubStatus.Subbed, subEps)
                if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
                m.trailerUrl?.let { addTrailer(it) }
            }
        }
    }

    private fun qualityOf(height: Int): Int = when {
        height >= 2160 -> Qualities.P2160.value
        height >= 1440 -> Qualities.P1440.value
        height >= 1080 -> Qualities.P1080.value
        height >= 720 -> Qualities.P720.value
        height >= 480 -> Qualities.P480.value
        height >= 360 -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun labelFor(source: ShiroApi.EpisodeSource, height: Int?, variant: String): String {
        val quality = height?.let { "${it}p" } ?: ""
        val tag = when (variant) {
            "sub" -> " (Sub)"
            "dub" -> " (Dub)"
            "hsub" -> " (Hardsub)"
            else -> ""
        }
        return listOf(source.label, quality, tag).filter { it.isNotBlank() }.joinToString(" ")
    }

    private suspend fun emitSource(
        source: ShiroApi.EpisodeSource,
        variant: String,
        cookie: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val absolute = if (source.url.startsWith("http")) source.url else "https://shiro.so" + source.url
        val headers = ShiroApi.streamHeaders(cookie)
        val ok = if (source.isHls) {
            val master = ShiroApi.fetchMaster(absolute, cookie)
            val text = if (master != null && master.isSuccessful) {
                try {
                    master.text
                } catch (e: Exception) {
                    null
                }
            } else null
            val variants = text?.let { ShiroApi.parseMaster(it, absolute) }.orEmpty()
            if (variants.isEmpty()) {
                Log.d("Shiro", "${source.label} has no playable variants")
                false
            } else {
                for (v in variants) {
                    callback.invoke(
                        ExtractorLink(
                            source = "Shiro",
                            name = labelFor(source, v.height, variant),
                            url = v.url,
                            referer = "https://shiro.so/",
                            quality = qualityOf(v.height),
                            type = ExtractorLinkType.M3U8,
                            headers = headers
                        )
                    )
                }
                true
            }
        } else if (ShiroApi.probe(absolute, cookie)) {
            callback.invoke(
                ExtractorLink(
                    source = "Shiro",
                    name = labelFor(source, null, variant),
                    url = absolute,
                    referer = "https://shiro.so/",
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO,
                    headers = headers
                )
            )
            true
        } else {
            Log.d("Shiro", "${source.label} is unreachable")
            false
        }
        return ok
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = parseData(data) ?: return false
        val cookie = ShiroApi.streamsCookie() ?: return false
        val streams = ShiroApi.streams(ref.id, ref.malId, ref.ep) ?: return false

        val wanted = when (ref.variant) {
            "sub" -> buildList {
                streams.sub.forEach { add(it to "sub") }
                streams.hsub.forEach { add(it to "hsub") }
            }
            "dub" -> streams.dub.map { it to "dub" }
            else -> buildList {
                streams.sub.forEach { add(it to "sub") }
                streams.hsub.forEach { add(it to "hsub") }
                streams.dub.forEach { add(it to "dub") }
            }
        }
        if (wanted.isEmpty()) return false

        val emitted = coroutineScope {
            wanted.chunked(5).map { batch ->
                batch.map { (source, variant) ->
                    async(Dispatchers.IO) {
                        try {
                            emitSource(source, variant, cookie, callback)
                        } catch (e: Exception) {
                            Log.d("Shiro", "source ${source.label} failed: ${e.message}")
                            false
                        }
                    }
                }
            }.flatten().awaitAll().any { it }
        }

        emitSubtitles(wanted, cookie, subtitleCallback)
        return emitted
    }

    // sub and dub tracks are timed against different audio so they must
    // stay inside their own tab, movies play every version from one entry
    // and tag the dub timed ones so the two english picks stay tellable
    // apart, some providers hand out ass files that no android player
    // renders, and the same label can point at a broken file on one
    // provider and a good one on another, so every candidate is probed and
    // each label survives with the first real webvtt behind it
    private suspend fun emitSubtitles(
        wanted: List<Pair<ShiroApi.EpisodeSource, String>>,
        cookie: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val byLabel = LinkedHashMap<String, MutableList<String>>()
        val mixesVersions = wanted.map { it.second }.toSet().size > 1
        for ((source, variant) in wanted) {
            for (track in source.tracks) {
                val label = if (variant == "dub" && mixesVersions) "${track.label} (Dub)" else track.label
                byLabel.getOrPut(label) { ArrayList() }.add(track.src)
            }
        }
        if (byLabel.isEmpty()) return

        val candidates = byLabel.values.flatten().distinct()
        val isVtt = ConcurrentHashMap<String, Boolean>()
        coroutineScope {
            candidates.chunked(6).map { batch ->
                batch.map { src ->
                    async(Dispatchers.IO) {
                        isVtt[src] = ShiroApi.fetchTextHead("https://shiro.so$src", cookie)
                            ?.startsWith("WEBVTT") == true
                    }
                }
            }.flatten().awaitAll()
        }

        for ((label, srcs) in byLabel) {
            val good = srcs.firstOrNull { isVtt[it] == true } ?: continue
            subtitleCallback.invoke(newSubtitleFile(label, "https://shiro.so$good") {
                this.headers = ShiroApi.streamHeaders(cookie)
            })
        }
    }
}
