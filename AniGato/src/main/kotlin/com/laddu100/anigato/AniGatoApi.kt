package com.laddu100.anigato

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder

object AniGatoApi {

    const val MAIN_URL = "https://anigato.lol"
    private const val TAG = "AniGato"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // the stream endpoints answer 403 "A browser is required" unless the
    // request carries at least one browser header, Referer covers everything
    val browserHeaders: Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$MAIN_URL/"
    )

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("/") -> MAIN_URL + url
        else -> "$MAIN_URL/$url"
    }

    // the api answers with occasional transient failures under load, one short
    // retry keeps a single hiccup from turning into an empty page
    private suspend fun getJson(url: String, timeout: Long = 20L): String? {
        for (attempt in 0..1) {
            try {
                val res = app.get(url, headers = browserHeaders, timeout = timeout)
                if (res.isSuccessful) return res.text
                if (res.code in 500..599 && attempt == 0) {
                    kotlinx.coroutines.delay(400L)
                    continue
                }
                return null
            } catch (e: Exception) {
                if (attempt == 1) {
                    Log.d(TAG, "GET $url: ${e.message}")
                    return null
                }
                kotlinx.coroutines.delay(400L)
            }
        }
        return null
    }

    // the site retries an empty watch answer three times when the upstream is
    // still indexing, 700 and 1800 ms apart, this mirrors that behaviour
    suspend fun watch(
        anilistId: Int,
        audio: String,
        episode: Int,
        title: String
    ): WatchResponse? {
        val url = "$MAIN_URL/api/watch/$anilistId/$audio/$episode?title=${urlEncode(title)}"
        val delays = longArrayOf(700L, 1800L)
        var last: WatchResponse? = null
        for (attempt in 0..delays.size) {
            val res = try {
                app.get(url, headers = browserHeaders, timeout = 25L)
            } catch (e: Exception) {
                Log.d(TAG, "watch attempt ${attempt + 1}: ${e.message}")
                null
            }
            if (res != null) {
                if (res.code == 429) {
                    // a rate limited answer is final, the retry-after header is
                    // respected but capped so a stuck episode cannot hang the app
                    val wait = res.headers["Retry-After"]?.toLongOrNull()?.times(1000L) ?: 60_000L
                    if (wait <= 10_000L) kotlinx.coroutines.delay(wait) else return last
                    continue
                }
                if (res.isSuccessful) {
                    val parsed = try {
                        parseJson<WatchResponse>(res.text)
                    } catch (e: Exception) {
                        Log.d(TAG, "watch parse: ${e.message}")
                        null
                    }
                    if (parsed != null) {
                        last = parsed
                        val streams = parsed.streams.orEmpty()
                        val pending = parsed.partial == true || parsed.loadingMore == true ||
                            parsed.mainPending == true || parsed.primaryPending == true
                        if (streams.isNotEmpty() || parsed.dubUnavailable == true ||
                            parsed.notReleased == true || !pending
                        ) {
                            return parsed
                        }
                    }
                }
            }
            if (attempt < delays.size) kotlinx.coroutines.delay(delays[attempt])
        }
        return last
    }

    suspend fun feed(kind: String, page: Int, perPage: Int = 30): FeedResponse? {
        val body = getJson("$MAIN_URL/api/catalog/feed?kind=$kind&page=$page&perPage=$perPage") ?: return null
        return try {
            parseJson<FeedResponse>(body)
        } catch (e: Exception) {
            Log.d(TAG, "feed parse: ${e.message}")
            null
        }
    }

    // the anilist backed search degrades to an empty answer for a few seconds
    // when its upstream is busy, a short retry window recovers almost all of
    // those, the site itself just shows nothing for the same window
    suspend fun searchAnime(query: String, page: Int = 1, perPage: Int = 30): CatalogSearchResponse? {
        val url = "$MAIN_URL/api/catalog/search?q=${urlEncode(query)}&page=$page&perPage=$perPage" +
            "&sort=POPULARITY_DESC"
        val delays = longArrayOf(800L, 1500L)
        var last: CatalogSearchResponse? = null
        for (attempt in 0..delays.size) {
            val body = getJson(url) ?: return last
            val parsed = try {
                parseJson<CatalogSearchResponse>(body)
            } catch (e: Exception) {
                Log.d(TAG, "anime search parse: ${e.message}")
                return last
            }
            last = parsed
            val empty = parsed.media.orEmpty().isEmpty()
            if (!(empty && parsed.degraded == true)) return parsed
            if (attempt < delays.size) kotlinx.coroutines.delay(delays[attempt])
        }
        return last
    }

    suspend fun animeDetail(anilistId: Int): AnimeMedia? {
        val body = getJson("$MAIN_URL/api/catalog/anime/$anilistId") ?: return null
        return try {
            parseJson<AnimeMedia>(body)
        } catch (e: Exception) {
            Log.d(TAG, "detail parse: ${e.message}")
            null
        }
    }

    suspend fun episodes(anilistId: Int, title: String): List<EpisodeEntry> {
        val body = getJson("$MAIN_URL/api/episodes/$anilistId?title=${urlEncode(title)}") ?: return emptyList()
        return try {
            parseJson<EpisodesResponse>(body).episodes.orEmpty()
        } catch (e: Exception) {
            Log.d(TAG, "episodes parse: ${e.message}")
            emptyList()
        }
    }

    suspend fun cinemaFeed(type: String, kind: String, page: Int): CinemaFeedResponse? {
        val body = getJson("$MAIN_URL/api/cinema/feed?type=$type&kind=$kind&page=$page&perPage=24") ?: return null
        return try {
            parseJson<CinemaFeedResponse>(body)
        } catch (e: Exception) {
            Log.d(TAG, "cinema feed parse: ${e.message}")
            null
        }
    }

    suspend fun cinemaSearch(query: String, type: String): List<CinemaFeedItem> {
        val body = getJson(
            "$MAIN_URL/api/cinema/search?q=${urlEncode(query)}&type=$type&page=1&perPage=24"
        ) ?: return emptyList()
        return try {
            parseJson<CinemaSearchResponse>(body).items()
        } catch (e: Exception) {
            Log.d(TAG, "cinema search parse: ${e.message}")
            emptyList()
        }
    }

    suspend fun cinemaMeta(type: String, tmdbId: Int): CinemaMeta? {
        val body = getJson("$MAIN_URL/api/cinema/meta/$type/tmdb-$tmdbId") ?: return null
        return try {
            parseJson<CinemaMeta>(body)
        } catch (e: Exception) {
            Log.d(TAG, "cinema meta parse: ${e.message}")
            null
        }
    }

    suspend fun cinemaEpisodes(tmdbId: Int, season: Int): List<CinemaEpisodeEntry> {
        val body = getJson("$MAIN_URL/api/cinema/episodes/tmdb-$tmdbId?season=$season") ?: return emptyList()
        return try {
            parseJson<CinemaEpisodesResponse>(body).episodes.orEmpty()
        } catch (e: Exception) {
            Log.d(TAG, "cinema episodes parse: ${e.message}")
            emptyList()
        }
    }

    suspend fun cinemaStream(
        tmdbId: Int,
        type: String,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        audio: String
    ): CinemaStreamResponse? {
        var url = "$MAIN_URL/api/cinema/stream/tmdb-$tmdbId?type=$type"
        imdbId?.let { url += "&imdb=${urlEncode(it)}" }
        season?.let { url += "&s=$it" }
        episode?.let { url += "&e=$it" }
        url += "&audio=$audio"
        // the cinema stream endpoint shares the watch retry pattern: an empty
        // answer may just mean the upstream is still resolving
        val delays = longArrayOf(700L, 1800L)
        var last: CinemaStreamResponse? = null
        for (attempt in 0..delays.size) {
            val body = getJson(url)
            if (body != null) {
                val parsed = try {
                    parseJson<CinemaStreamResponse>(body)
                } catch (e: Exception) {
                    null
                }
                if (parsed != null) {
                    last = parsed
                    if (parsed.streams.orEmpty().isNotEmpty() || parsed.dmca == true ||
                        parsed.unavailable == true
                    ) {
                        return parsed
                    }
                }
            }
            if (attempt < delays.size) kotlinx.coroutines.delay(delays[attempt])
        }
        return last
    }

    suspend fun cinemaSubtitles(
        tmdbId: Int,
        type: String,
        imdbId: String?,
        season: Int?,
        episode: Int?
    ): List<SubtitleTrack> {
        var url = "$MAIN_URL/api/cinema/subtitles/tmdb-$tmdbId?type=$type"
        imdbId?.let { url += "&imdb=${urlEncode(it)}" }
        season?.let { url += "&s=$it" }
        episode?.let { url += "&e=$it" }
        val body = getJson(url) ?: return emptyList()
        return try {
            parseJson<CinemaSubtitlesResponse>(body).subtitles.orEmpty()
        } catch (e: Exception) {
            Log.d(TAG, "cinema subs parse: ${e.message}")
            emptyList()
        }
    }

    // the self hosted servers answer with a SELFP1 blob only the site player
    // can decode, every other master is a plain playlist any player accepts
    fun isSelfHostedEncrypted(url: String): Boolean {
        if (!url.contains("anigato.lol")) return false
        return Regex("""^https?://anigato\.lol/(?:m3/)?\d+/\d+/(?:sub|dub)/master\.m3u8""")
            .containsMatchIn(url)
    }

    data class ValidatedStream(
        val url: String,
        val isDash: Boolean,
        val maxHeight: Int?
    )

    // a stream only counts when its manifest body is a real playlist, the
    // server list rotates and half of the entries can be dead at any moment
    suspend fun validateStream(url: String): ValidatedStream? {
        return try {
            val res = app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$MAIN_URL/"),
                timeout = 15L
            )
            if (!res.isSuccessful) return null
            val text = res.body.string()
            when {
                text.startsWith("#EXTM3U") -> {
                    val height = Regex("""RESOLUTION=\d+x(\d+)""")
                        .findAll(text)
                        .mapNotNull { it.groupValues[1].toIntOrNull() }
                        .maxOrNull()
                    ValidatedStream(url, isDash = false, maxHeight = height)
                }
                text.startsWith("<") && text.contains("<MPD") ->
                    ValidatedStream(url, isDash = true, maxHeight = null)
                else -> null
            }
        } catch (e: Exception) {
            Log.d(TAG, "validate: ${e.message}")
            null
        }
    }

}
