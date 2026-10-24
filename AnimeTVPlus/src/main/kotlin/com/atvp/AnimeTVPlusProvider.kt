package com.atvp

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * animetvplus.xyz - one entry per anime with every server the site runs in a
 * single link list (no sub/dub split, matching the site's own layout), real
 * episode titles from the site's catalog, and each server's full subtitle set.
 */
class AnimeTVPlusProvider : MainAPI() {

    override var mainUrl = AtvpApi.SITE
    override var name = "AnimeTVPlus"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = listOf(
        MainPageData("Latest Uploads", "latest"),
        MainPageData("Popular", "popular"),
        MainPageData("Top Rated", "top-rated"),
        MainPageData("New Releases", "new-releases"),
        MainPageData("This Season", "season-current"),
        MainPageData("Action", "genre-action"),
        MainPageData("Adventure", "genre-adventure"),
        MainPageData("Comedy", "genre-comedy"),
        MainPageData("Drama", "genre-drama"),
        MainPageData("Fantasy", "genre-fantasy"),
        MainPageData("Mystery", "genre-mystery"),
        MainPageData("Romance", "genre-romance"),
        MainPageData("Sci-Fi", "genre-sci-fi"),
        MainPageData("Sports", "genre-sports"),
        MainPageData("Supernatural", "genre-supernatural")
    )

    // ------------------------------------------------------------------ catalog mapping

    /**
     * Same displayability rule the site's client applies: canonical root entries
     * plus movies/ovas/onas/specials.
     */
    private fun displayable(item: JSONObject): Boolean {
        val mal = item.optString("mal_id")
        val canonical = item.optString("canonical_mal_id")
        if (canonical.isBlank() || canonical == mal) return true
        val format = item.optString("format").uppercase()
        return format == "MOVIE" || format == "OVA" || format == "ONA" || format == "SPECIAL"
    }

    private fun titleOf(item: JSONObject): String? {
        return item.optString("english_title").ifBlank { item.optString("canonical_title") }
            .ifBlank { item.optString("romaji_title") }
            .ifBlank { item.optString("title") }.takeIf { it.isNotBlank() }
    }

    private fun posterOf(item: JSONObject): String? {
        return item.optString("cr_poster").ifBlank { item.optString("cover_image") }
            .ifBlank { item.optString("thumbnail_1080_url") }
            .ifBlank { item.optString("poster") }
            .ifBlank { item.optString("banner_image") }.takeIf { it.startsWith("http") }
    }

    private fun toSearchResponse(item: JSONObject): SearchResponse? {
        if (!displayable(item)) return null
        val mal = item.optString("mal_id").takeIf { it.isNotBlank() } ?: return null
        val title = titleOf(item) ?: return null
        val format = item.optString("format").uppercase()
        val type = when {
            format == "MOVIE" -> TvType.AnimeMovie
            format == "OVA" || format == "ONA" || format == "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        val scoreRaw = item.optInt("average_score", 0)
        return newAnimeSearchResponse(title, mal, type) {
            posterUrl = posterOf(item)
            val y = item.optInt("season_year", 0)
            if (y > 0) year = y
            if (scoreRaw > 0) score = Score.from10(scoreRaw / 10.0)
        }
    }

    // ------------------------------------------------------------------ home + search

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when {
            request.data == "latest" -> {
                if (page > 1) emptyList() else AtvpApi.latestUploads(limit = 60).mapNotNull { u ->
                    val mal = u.optString("mal_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val title = u.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    newAnimeSearchResponse(title, mal, TvType.Anime) {
                        posterUrl = u.optString("poster").ifBlank { u.optString("hero_banner") }
                            .takeIf { it.startsWith("http") }
                    }
                }
            }
            request.data == "season-current" -> {
                val now = Calendar.getInstance()
                val year = now.get(Calendar.YEAR)
                val month = now.get(Calendar.MONTH)
                val season = when (month) {
                    Calendar.JANUARY, Calendar.FEBRUARY, Calendar.MARCH -> "winter"
                    Calendar.APRIL, Calendar.MAY, Calendar.JUNE -> "spring"
                    Calendar.JULY, Calendar.AUGUST, Calendar.SEPTEMBER -> "summer"
                    else -> "fall"
                }
                AtvpApi.row("season/$year/$season", page).mapNotNull(::toSearchResponse)
            }
            request.data.startsWith("genre-") ->
                AtvpApi.row("genre/${request.data.removePrefix("genre-")}", page)
                    .mapNotNull(::toSearchResponse)
            else -> AtvpApi.row(request.data, page).mapNotNull(::toSearchResponse)
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && request.data != "latest")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return AtvpApi.search(query, page = 1).mapNotNull(::toSearchResponse)
    }

    // ------------------------------------------------------------------ details + episodes

    /** Watch targets are (owner_mal, absolute stream_ep) exactly like the site's links. */
    private fun episodeData(mal: String, streamEp: Int): String = "$mal|$streamEp"

    private fun newEpisodeEntry(
        ownerMal: String,
        raw: JSONObject,
        seasonNumber: Int?,
        fallbackNumber: Int
    ): Episode? {
        val streamEp = raw.optInt("stream_ep", raw.optInt("ep", raw.optInt("episode_number", 0)))
        if (streamEp < 0) return null
        val display = if (streamEp > 0) streamEp else fallbackNumber
        val name = raw.optString("title").takeIf { it.isNotBlank() && it != "Episode $display" }
        return newEpisode(episodeData(ownerMal, if (streamEp > 0) streamEp else 0)) {
            this.name = name ?: "Episode $display"
            this.episode = display
            if (seasonNumber != null) this.season = seasonNumber
            val thumb = raw.optString("thumbnail").takeIf { it.startsWith("http") }
            if (thumb != null) posterUrl = thumb
        }
    }

    private fun seasonsOf(card: JSONObject?): List<JSONObject> {
        if (card == null) return emptyList()
        val arr = card.optJSONArray("seasons") ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            if (s.optString("owner_mal").isBlank()) continue
            if (s.optInt("episode_count", 0) <= 0) continue
            out.add(s)
        }
        out.sortBy { it.optInt("season_number", 0) }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val mal = url.substringAfterLast('|').ifBlank { url }
        val item = AtvpApi.byId(mal)
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Title not found")
        val card = runCatching { AtvpApi.crCard(mal) }.getOrNull()
        val title = titleOf(item) ?: "Anime $mal"
        val format = item.optString("format").uppercase()
        val isMovie = format == "MOVIE"
        val type = when {
            isMovie -> TvType.AnimeMovie
            format == "OVA" || format == "ONA" || format == "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        val scoreRaw = item.optInt("average_score", 0)
        val poster = posterOf(item)
        val background = item.optString("detail_banner").ifBlank { item.optString("cr_hero") }
            .ifBlank { item.optString("banner_image") }.takeIf { it.startsWith("http") }
        val plot = card?.optString("synopsis")?.takeIf { it.isNotBlank() }
            ?.replace("<br\\s*/?>".toRegex(RegexOption.IGNORE_CASE), "\n")
            ?.replace(Regex("<[^>]+>"), "")
        val year = item.optInt("season_year", 0).takeIf { it > 0 }
        val tags = runCatching {
            JSONArray(item.optString("genres_json")).let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }
        }.getOrNull()
        val showStatus = when (item.optString("status").uppercase()) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        // ---- episode list with real names -------------------------------------
        val seasons = seasonsOf(card)
        val episodes = mutableListOf<Episode>()

        if (seasons.isNotEmpty()) {
            // the card already carries the primary season's episode list
            val selected = card?.optJSONObject("selected_season")
            for (s in seasons) {
                val ownerMal = s.optString("owner_mal")
                val seasonNumber = s.optInt("season_number", 1)
                var list: List<JSONObject> = emptyList()
                if (selected != null && selected.optInt("season_number", -1) == seasonNumber) {
                    val arr = selected.optJSONArray("episodes")
                    if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list = list + it }
                }
                if (list.isEmpty()) {
                    list = runCatching { AtvpApi.allEpisodes(mal, s.optString("cr_season_id")) }
                        .getOrDefault(emptyList())
                }
                // seasons the catalog has no episode metadata for are skipped the
                // same way the site's client hides an empty season view
                for (raw in list) {
                    newEpisodeEntry(ownerMal, raw, seasonNumber, raw.optInt("ep", 0))?.let(episodes::add)
                }
            }
        }

        if (episodes.isEmpty()) {
            val flat = runCatching { AtvpApi.allEpisodes(mal) }.getOrDefault(emptyList())
            for (raw in flat) {
                newEpisodeEntry(mal, raw, null, raw.optInt("ep", 0))?.let(episodes::add)
            }
            // the catalog count can exceed the metadata list; the stream still has them
            val listed = episodes.size
            val total = item.optInt("m3u8_episode_count", 0).takeIf { it > listed }
                ?: item.optInt("current_episodes", 0).takeIf { it > listed }
            if (total != null && total > listed) {
                for (n in listed + 1..total) {
                    episodes.add(
                        newEpisode(episodeData(mal, n)) {
                            this.name = "Episode $n"
                            this.episode = n
                        }
                    )
                }
            }
        }

        // ---- response ---------------------------------------------------------
        return if (isMovie || episodes.isEmpty()) {
            val data = episodes.firstOrNull()?.data ?: episodeData(mal, 1)
            newMovieLoadResponse(title, mal, type, data) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                if (scoreRaw > 0) score = Score.from10(scoreRaw / 10.0)
                tags?.let { this.tags = it }
            }
        } else {
            newTvSeriesLoadResponse(title, mal, type, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.showStatus = showStatus
                if (scoreRaw > 0) score = Score.from10(scoreRaw / 10.0)
                tags?.let { this.tags = it }
            }
        }
    }

    // ------------------------------------------------------------------ links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sep = data.indexOf('|')
        if (sep <= 0) return false
        val mal = data.substring(0, sep)
        val episode = data.substring(sep + 1).toIntOrNull() ?: 1

        var found = false
        coroutineScope {
            val jobs = listOf(
                async(Dispatchers.IO) {
                    runCatching { AtvpSources.multiAudio(mal, episode, subtitleCallback, callback) }.getOrDefault(false)
                },
                async(Dispatchers.IO) {
                    runCatching { AtvpSources.dub(mal, episode, subtitleCallback, callback) }.getOrDefault(false)
                },
                async(Dispatchers.IO) {
                    runCatching { AtvpSources.hardsub(mal, episode, callback) }.getOrDefault(false)
                },
                async(Dispatchers.IO) {
                    runCatching { AtvpSources.kamuri(mal, episode, subtitleCallback, callback) }.getOrDefault(false)
                },
                async(Dispatchers.IO) {
                    runCatching { AtvpSources.vidmoly(mal, episode, callback) }.getOrDefault(false)
                },
                async(Dispatchers.IO) {
                    runCatching { AtvpSources.vidFlow(mal, episode, callback) }.getOrDefault(false)
                }
            )
            jobs.forEach { job -> found = job.await() || found }
        }
        return found
    }
}
