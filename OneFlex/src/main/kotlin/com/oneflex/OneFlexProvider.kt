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
        "trending/all/week" to "Trending Now",
        "movie/popular" to "Popular Movies",
        "movie/top_rated" to "Top Rated Movies",
        "tv/popular" to "Popular TV Shows",
        "tv/top_rated" to "Top Rated TV Shows",
        "discover/movie?with_genres=28&sort_by=popularity.desc" to "Action & Adventure Movies",
        "discover/movie?with_genres=35&sort_by=popularity.desc" to "Comedy Movies",
        "discover/movie?with_genres=18&sort_by=popularity.desc" to "Drama Movies",
        "discover/movie?with_genres=878&sort_by=popularity.desc" to "Sci-Fi Movies",
        "discover/movie?with_genres=27&sort_by=popularity.desc" to "Horror Movies",
        "discover/tv?with_genres=35&sort_by=popularity.desc" to "Comedy TV Shows",
        "discover/tv?with_genres=18&sort_by=popularity.desc" to "Drama TV Shows",
        "discover/tv?with_genres=80&sort_by=popularity.desc" to "Crime TV Shows",
        "discover/tv?with_genres=10765&sort_by=popularity.desc" to "Sci-Fi & Fantasy TV Shows"
    )

    companion object {
        // themoviedb.org is unreachable on several indian isps, the site itself
        // runs everything through its own proxy domain so the plugin does too,
        // the proxy only answers requests carrying the site referer
        const val DB_PROXY = "https://db.1flex.org"
        const val IMAGE_PROXY = "https://wsrv.nl/?url="
        const val TMDB_IMAGE = "https://image.tmdb.org/t/p"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        val BASE_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://www.1flex.org/"
        )

        // posters go through the image cache the site preconnects to, direct
        // image.tmdb.org is blocked wherever themoviedb.org is blocked
        fun imageUrl(path: JsonNode?, size: String = "w500"): String? {
            val clean = path?.asText()?.takeIf { it.isNotBlank() } ?: return null
            return IMAGE_PROXY + URLEncoder.encode("$TMDB_IMAGE/$size$clean", "UTF-8")
        }

        fun hexBytes(s: String): ByteArray = ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }

    private val mapper = ObjectMapper()

    private suspend fun dbGet(path: String, page: Int? = null): JsonNode? {
        return try {
            val join = if (path.contains('?')) '&' else '?'
            val url = if (page != null) "$DB_PROXY/$path${join}page=$page" else "$DB_PROXY/$path"
            mapper.readTree(app.get(url, headers = BASE_HEADERS).text)
        } catch (e: Exception) {
            Log.e(TAG, "db request failed for $path: ${e.message}")
            null
        }
    }

    private fun JsonNode?.toList(): List<JsonNode> =
        if (this != null && isArray) this.map { it } else emptyList()

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
        val json = dbGet(request.data, page)
            ?: throw ErrorLoadingException("1flex database unreachable")
        // trending lists tag every entry with media_type, the movie and discover
        // lists do not so the type comes from the path segments instead
        val segments = request.data.substringBefore('?').split('/')
        val defaultType = when (segments.first()) {
            "movie", "tv" -> segments.first()
            "discover" -> segments.getOrNull(1)?.takeIf { it == "movie" || it == "tv" }
            else -> null
        }
        val items = json.get("results").toList().mapNotNull { it.toSearchResponse(defaultType) }
        val hasNext = (json.get("page")?.asInt() ?: 1) < (json.get("total_pages")?.asInt() ?: 1)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = dbGet("search/multi?query=$encoded") ?: return emptyList()
        return json.get("results").toList().mapNotNull { it.toSearchResponse() }
    }

    private fun buildActors(cast: JsonNode?): List<ActorData> =
        cast.toList().take(10).mapNotNull { entry ->
            val name = entry.get("name")?.asText() ?: return@mapNotNull null
            ActorData(Actor(name, imageUrl(entry.get("profile_path"))))
        }

    private fun buildRecommendations(similar: JsonNode?, type: String): List<SearchResponse> =
        similar?.get("results").toList().take(12).mapNotNull { it.toSearchResponse(type) }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|")
        if (parts.size != 2) return null
        val type = parts[0]
        val id = parts[1]

        if (type == "movie") {
            val json = dbGet("movie/$id?append_to_response=credits,similar") ?: return null
            val title = json.get("title")?.asText() ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = imageUrl(json.get("poster_path"))
                this.backgroundPosterUrl = imageUrl(json.get("backdrop_path"), "w780")
                this.year = json.get("release_date")?.asText()?.take(4)?.toIntOrNull()
                this.plot = json.get("overview")?.asText()
                this.duration = json.get("runtime")?.asInt()
                this.score = json.get("vote_average")?.asDouble()?.let { Score.from10(it.toString()) }
                this.tags = json.get("genres").toList().mapNotNull { it.get("name")?.asText() }
                this.actors = buildActors(json.get("credits")?.get("cast"))
                this.recommendations = buildRecommendations(json.get("similar"), "movie")
            }
        }

        if (type == "tv") {
            val json = dbGet("tv/$id?append_to_response=aggregate_credits,similar") ?: return null
            val title = json.get("name")?.asText() ?: return null

            // seasons load in parallel, long shows would crawl one by one
            val episodes = coroutineScope {
                json.get("seasons").toList()
                    .mapNotNull { season -> season.get("season_number")?.asInt() }
                    .map { seasonNumber ->
                        async {
                            val seasonJson = dbGet("tv/$id/season/$seasonNumber")
                                ?: return@async emptyList<Episode>()
                            seasonJson.get("episodes").toList().mapNotNull { ep ->
                                val epNumber = ep.get("episode_number")?.asInt() ?: return@mapNotNull null
                                newEpisode("tv|$id|$seasonNumber|$epNumber") {
                                    this.name = ep.get("name")?.asText()?.takeIf { it.isNotBlank() }
                                        ?: "Episode $epNumber"
                                    this.season = seasonNumber
                                    this.episode = epNumber
                                    this.posterUrl = imageUrl(ep.get("still_path"))
                                    this.description = ep.get("overview")?.asText()
                                    this.score = ep.get("vote_average")?.asDouble()
                                        ?.let { Score.from10(it.toString()) }
                                }
                            }
                        }
                    }
                    .awaitAll()
                    .flatten()
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = imageUrl(json.get("poster_path"))
                this.backgroundPosterUrl = imageUrl(json.get("backdrop_path"), "w780")
                this.year = json.get("first_air_date")?.asText()?.take(4)?.toIntOrNull()
                this.plot = json.get("overview")?.asText()
                this.score = json.get("vote_average")?.asDouble()?.let { Score.from10(it.toString()) }
                this.tags = json.get("genres").toList().mapNotNull { it.get("name")?.asText() }
                this.actors = buildActors(json.get("aggregate_credits")?.get("cast"))
                this.recommendations = buildRecommendations(json.get("similar"), "tv")
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

    // the embed players run their own token or crypto chain in the page, so they
    // resolve through a webview while the two open json apis go straight over http
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
            "Server 7 (Viduki Multi Language)",
            "https://www.viduki.net/2/movie/{id}?color=FF0000",
            "https://www.viduki.net/2/tv/{id}/{s}/{e}?color=FF0000"
        ),
        Embed(
            "Server 8 (Viduki Premium Embeds)",
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
        return "$scheme://$host"
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
                timeout = 45_000L
            )
            val resolved = app.get(pageUrl, referer = "$mainUrl/", interceptor = resolver).url
            if (!resolved.contains(".m3u8", ignoreCase = true) &&
                !resolved.contains(".mp4", ignoreCase = true)
            ) return false
            val linkType = if (resolved.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
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

        // webview embeds have no surface while casting
        if (!isCasting) {
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
        }

        jobs.awaitAll().any { it }
    }
}
