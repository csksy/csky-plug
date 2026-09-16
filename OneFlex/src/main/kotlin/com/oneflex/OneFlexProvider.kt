package com.oneflex

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class OneFlexProvider : MainAPI() {
    private val TAG = "OneFlex"

    override var mainUrl = "https://www.1flex.org"
    override var name = "1Flex"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "trending/all/day" to "Trending",
        "movie/popular" to "Popular Movies",
        "movie/top_rated" to "Top Rated Movies",
        "movie/now_playing" to "In Theaters",
        "movie/upcoming" to "Coming Soon",
        "tv/popular" to "Popular TV Shows",
        "tv/top_rated" to "Top Rated TV Shows",
        "tv/on_the_air" to "On Air"
    )

    companion object {
        // 1flex is a tmdb front end, the player bundles ship the key it calls with
        const val TMDB_KEY = "adc48d20c0956934fb224de5c40bb85d"
        const val TMDB_API = "https://api.themoviedb.org/3"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        val BASE_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*"
        )

        fun hexBytes(s: String): ByteArray = ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }

    private val mapper = ObjectMapper()

    private fun imageUrl(path: JsonNode?): String? =
        path?.asText()?.takeIf { it.isNotBlank() }?.let { "$IMAGE_BASE$it" }

    private suspend fun tmdb(path: String, extra: String = ""): JsonNode? {
        return try {
            mapper.readTree(app.get("$TMDB_API/$path?api_key=$TMDB_KEY$extra", headers = BASE_HEADERS).text)
        } catch (e: Exception) {
            Log.e(TAG, "tmdb request failed for $path: ${e.message}")
            null
        }
    }

    private fun JsonNode.toSearchResponse(defaultType: String? = null): SearchResponse? {
        val mediaType = get("media_type")?.asText() ?: defaultType ?: return null
        if (mediaType != "movie" && mediaType != "tv") return null
        val id = get("id")?.asInt() ?: return null
        val title = get(if (mediaType == "movie") "title" else "name")?.asText() ?: return null
        val year = get(if (mediaType == "movie") "release_date" else "first_air_date")
            ?.asText()?.take(4)?.toIntOrNull()
        val data = "$mediaType|$id"
        return if (mediaType == "movie") {
            newMovieSearchResponse(title, data) {
                this.posterUrl = imageUrl(get("poster_path"))
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, data) {
                this.posterUrl = imageUrl(get("poster_path"))
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = tmdb(request.data, "&page=$page")
            ?: throw ErrorLoadingException("tmdb unavailable")
        val defaultType = request.data.substringBefore('/')
        val items = json.get("results")?.mapNotNull { it.toSearchResponse(defaultType) }
            ?: emptyList()
        val hasNext = (json.get("page")?.asInt() ?: 1) < (json.get("total_pages")?.asInt() ?: 1)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = tmdb("search/multi", "&query=$encoded") ?: return emptyList()
        return json.get("results")?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|")
        if (parts.size != 2) return null
        val type = parts[0]
        val id = parts[1]

        if (type == "movie") {
            val json = tmdb("movie/$id") ?: return null
            val title = json.get("title")?.asText() ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = imageUrl(json.get("poster_path"))
                this.backgroundPosterUrl = imageUrl(json.get("backdrop_path"))
                this.year = json.get("release_date")?.asText()?.take(4)?.toIntOrNull()
                this.plot = json.get("overview")?.asText()
                this.duration = json.get("runtime")?.asInt()
                this.score = json.get("vote_average")?.asDouble()?.let { Score.from10(it.toString()) }
                this.tags = json.get("genres")?.mapNotNull { it.get("name")?.asText() }
            }
        }

        if (type == "tv") {
            val json = tmdb("tv/$id") ?: return null
            val title = json.get("name")?.asText() ?: return null
            val episodes = mutableListOf<Episode>()
            val seasons = json.get("seasons") ?: return null
            for (season in seasons) {
                val seasonNumber = season.get("season_number")?.asInt() ?: continue
                val seasonJson = tmdb("tv/$id/season/$seasonNumber") ?: continue
                val eps = seasonJson.get("episodes") ?: continue
                for (ep in eps) {
                    val epNumber = ep.get("episode_number")?.asInt() ?: continue
                    val epName = ep.get("name")?.asText()?.takeIf { it.isNotBlank() }
                    episodes.add(
                        newEpisode("tv|$id|$seasonNumber|$epNumber") {
                            this.name = epName ?: "Episode $epNumber"
                            this.season = seasonNumber
                            this.episode = epNumber
                            this.posterUrl = imageUrl(ep.get("still_path"))
                            this.description = ep.get("overview")?.asText()
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = imageUrl(json.get("poster_path"))
                this.backgroundPosterUrl = imageUrl(json.get("backdrop_path"))
                this.year = json.get("first_air_date")?.asText()?.take(4)?.toIntOrNull()
                this.plot = json.get("overview")?.asText()
                this.score = json.get("vote_average")?.asDouble()?.let { Score.from10(it.toString()) }
                this.tags = json.get("genres")?.mapNotNull { it.get("name")?.asText() }
                this.showStatus = getStatus(json.get("status")?.asText())
            }
        }

        return null
    }

    private fun getStatus(status: String?): ShowStatus? = when (status) {
        "Returning Series" -> ShowStatus.Ongoing
        "Ended", "Canceled" -> ShowStatus.Completed
        else -> null
    }

    // the embed players each run their own token or crypto chain in the page,
    // so they resolve through a webview while the two open json apis go straight over http
    private class Embed(val label: String, val movieUrl: String, val tvUrl: String)

    private val embeds = listOf(
        Embed(
            "Server 1 (Viduki)",
            "https://www.viduki.net/1/movie/{id}?color=FF0000",
            "https://www.viduki.net/1/tv/{id}/{s}/{e}?color=FF0000"
        ),
        Embed(
            "Server 2 (Vidy)",
            "https://vidy.st/movie/{id}?color=FF0000&overlay=true",
            "https://vidy.st/tv/{id}/{s}/{e}?color=FF0000&episodeSelector=false&nextEpisode=false&autoplayNextEpisode=false&overlay=true"
        ),
        Embed(
            "Server 3 (VidFast)",
            "https://vidfast.pro/movie/{id}?autoPlay=true&title=true&poster=true&theme=FF0000",
            "https://vidfast.pro/tv/{id}/{s}/{e}?autoPlay=true&title=true&poster=true&theme=FF0000&nextButton=false&autoNext=false"
        ),
        Embed(
            "Server 4 (VidLink)",
            "https://vidlink.pro/movie/{id}?primaryColor=FF0000&secondaryColor=a2a2a2&iconColor=eefdec&icons=default&player=jw&title=true&poster=true&autoplay=true&nextbutton=false",
            "https://vidlink.pro/tv/{id}/{s}/{e}?primaryColor=FF0000&secondaryColor=a2a2a2&iconColor=eefdec&icons=default&player=jw&title=true&poster=true&autoplay=true&nextbutton=false"
        ),
        Embed(
            "Multi Language (Viduki)",
            "https://www.viduki.net/2/movie/{id}?color=FF0000",
            "https://www.viduki.net/2/tv/{id}/{s}/{e}?color=FF0000"
        ),
        Embed(
            "Premium Embeds (Viduki)",
            "https://www.viduki.net/4/movie/{id}?color=FF0000",
            "https://www.viduki.net/4/tv/{id}/{s}/{e}?color=FF0000"
        )
    )

    // the players start on their own, this only pushes a paused video so the
    // manifest request lands inside the intercept window
    private val playNudge = """
        (function () {
            try {
                var v = document.querySelector('video');
                if (v) {
                    v.muted = true;
                    var p = v.play();
                    if (p && p.catch) p.catch(function () {});
                }
            } catch (e) {}
        })();
    """.trimIndent()

    private fun buildEmbedUrl(embed: Embed, type: String, id: String, season: Int?, episode: Int?): String {
        val template = if (type == "tv") embed.tvUrl else embed.movieUrl
        return template
            .replace("{id}", id)
            .replace("{s}", season?.toString() ?: "1")
            .replace("{e}", episode?.toString() ?: "1")
    }

    private fun embedOrigin(url: String): String {
        val scheme = url.substringBefore("://")
        val host = url.substringAfter("://").substringBefore("/")
        return "$scheme://$host/"
    }

    private suspend fun resolveEmbed(
        label: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:[?#]|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:[?#]|$)""")),
                script = playNudge,
                useOkhttp = false,
                timeout = 40_000L
            )
            val resolved = app.get(pageUrl, referer = "$mainUrl/", interceptor = resolver).url
            if (resolved.isBlank()) return false
            val isM3u8 = resolved.contains(".m3u8", ignoreCase = true)
            val isMp4 = resolved.contains(".mp4", ignoreCase = true)
            if (!isM3u8 && !isMp4) return false
            val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(label, label, resolved, type = linkType) {
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedOrigin(pageUrl)
                    )
                }
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "$label webview resolve failed: ${e.message}")
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val parts = data.split("|")
        if (parts.size < 2) return@coroutineScope false
        val type = parts[0]
        val id = parts[1]
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()
        if (type != "movie" && type != "tv") return@coroutineScope false

        val jobs = mutableListOf<Deferred<Boolean>>()

        jobs.add(async {
            try {
                VidRock.load(type, id, season, episode, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.w(TAG, "vidrock resolve failed: ${e.message}")
                false
            }
        })

        jobs.add(async {
            try {
                Vidzee.load(type, id, season, episode, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.w(TAG, "vidzee resolve failed: ${e.message}")
                false
            }
        })

        for (embed in embeds) {
            val pageUrl = buildEmbedUrl(embed, type, id, season, episode)
            jobs.add(async {
                try {
                    resolveEmbed(embed.label, pageUrl, callback)
                } catch (e: Exception) {
                    Log.w(TAG, "${embed.label} resolve failed: ${e.message}")
                    false
                }
            })
        }

        jobs.awaitAll().any { it }
    }
}
