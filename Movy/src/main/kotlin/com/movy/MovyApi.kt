package com.movy

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.delay

object MovyApi {

    private const val TAG = "Movy"

    const val MAIN_URL = "https://www.movy.sx"
    const val REFERER = "https://www.movy.sx/"
    private const val TMDB_BASE = "https://db.wecollege.net/3"
    private const val STREAM_BASE = "https://api.wecollege.net"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"

    val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Referer" to REFERER,
        "Origin" to MAIN_URL,
    )

    /** playback requests also need the site referer, the cdn fronts check it */
    val playHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to REFERER,
        "Origin" to MAIN_URL,
    )

    class City(val slug: String, val display: String, val langNote: String? = null)

    val cities = listOf(
        City("miami", "Miami"),
        City("boise", "Boise"),
        City("vegas", "Vegas"),
        City("reno", "Reno"),
        City("oakland", "Oakland"),
        City("seattle", "Seattle"),
        City("phoenix", "Phoenix"),
        City("atlanta", "Atlanta"),
        City("portland", "Portland"),
        City("dallas", "Dallas"),
        City("tampa", "Tampa"),
        City("orlando", "Orlando"),
        City("austin", "Austin"),
        City("delhi", "Delhi"),
        City("munich", "Munich", "German"),
        City("berlin", "Berlin", "German"),
        City("paris", "Paris", "French"),
        City("cancun", "Cancun", "Spanish"),
    )

    fun poster(path: String?, width: String = "w500"): String? =
        path?.takeIf { it.isNotBlank() }?.let { "$IMAGE_BASE/$width$it" }

    private suspend fun getBody(url: String, timeout: Long = 20_000L): Pair<Int, String?> {
        return try {
            val res = app.get(url, headers = baseHeaders, timeout = timeout)
            res.code to res.text
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            -1 to null
        }
    }

    suspend fun tmdbPage(path: String, page: Int): MovyTmdbPage? {
        val sep = if (path.contains('?')) "&" else "?"
        val (code, body) = getBody("$TMDB_BASE/$path${sep}page=$page&language=en&include_adult=false")
        if (code != 200 || body.isNullOrBlank()) return null
        return try {
            parseJson<MovyTmdbPage>(body)
        } catch (e: Exception) {
            Log.e(TAG, "tmdb page parse: ${e.message}")
            null
        }
    }

    suspend fun searchMulti(query: String, page: Int): MovyTmdbPage? {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val (code, body) =
            getBody("$TMDB_BASE/search/multi?query=$encoded&page=$page&language=en&include_adult=false")
        if (code != 200 || body.isNullOrBlank()) return null
        return try {
            parseJson<MovyTmdbPage>(body)
        } catch (e: Exception) {
            Log.e(TAG, "search parse: ${e.message}")
            null
        }
    }

    suspend fun movieDetails(id: Int): MovyMovieDetails? {
        val (code, body) = getBody(
            "$TMDB_BASE/movie/$id?language=en&append_to_response=external_ids,translations"
        )
        if (code != 200 || body.isNullOrBlank()) return null
        return try {
            parseJson<MovyMovieDetails>(body)
        } catch (e: Exception) {
            Log.e(TAG, "movie parse: ${e.message}")
            null
        }
    }

    suspend fun tvDetails(id: Int): MovyTvDetails? {
        val (code, body) = getBody(
            "$TMDB_BASE/tv/$id?language=en&append_to_response=external_ids,translations"
        )
        if (code != 200 || body.isNullOrBlank()) return null
        return try {
            parseJson<MovyTvDetails>(body)
        } catch (e: Exception) {
            Log.e(TAG, "tv parse: ${e.message}")
            null
        }
    }

    suspend fun seasonDetails(id: Int, season: Int): MovySeasonDetails? {
        val (code, body) = getBody("$TMDB_BASE/tv/$id/season/$season?language=en")
        if (code != 200 || body.isNullOrBlank()) return null
        return try {
            parseJson<MovySeasonDetails>(body)
        } catch (e: Exception) {
            Log.e(TAG, "season parse: ${e.message}")
            null
        }
    }

    /**
     * Seeds are handed out per media id and live for roughly 30 seconds. The
     * api fleet behind cloudflare does not always agree about which seeds are
     * current, so a 401 usually just means the request landed on another
     * node: retry with the same seed first, only grab a new one when it keeps
     * failing. The seed endpoint also rate limits hard, so fetches are paced
     * to at most one per second and back off on 429.
     */
    class SeedPool(private val mediaId: String) {
        @Volatile private var seed: String? = null
        private var expiresAt = 0L
        private var lastFetchAt = 0L
        private val mutex = kotlinx.coroutines.sync.Mutex()

        fun drop() {
            seed = null
        }

        suspend fun current(): String? {
            mutex.lock()
            try {
                val now = System.currentTimeMillis()
                if (seed != null && now < expiresAt) return seed
                return fetchPaced()
            } finally {
                mutex.unlock()
            }
        }

        private suspend fun fetchPaced(): String? {
            var fourTwenties = 0
            while (true) {
                val now = System.currentTimeMillis()
                val wait = lastFetchAt + 1000L - now
                if (wait > 0) delay(wait)
                val res = try {
                    app.get(
                        "$STREAM_BASE/seed?mediaId=$mediaId",
                        headers = baseHeaders,
                        timeout = 15_000L
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "seed fetch: ${e.message}")
                    return null
                }
                lastFetchAt = System.currentTimeMillis()
                when {
                    res.code == 200 -> {
                        val reply = try {
                            parseJson<MovySeedReply>(res.text)
                        } catch (_: Exception) {
                            null
                        }
                        val fresh = reply?.seed?.takeIf { it.isNotBlank() } ?: return null
                        seed = fresh
                        expiresAt = lastFetchAt - 5000L + (reply.ttlMs ?: 30000L)
                        return fresh
                    }
                    res.code == 429 && fourTwenties < 1 -> {
                        fourTwenties++
                        delay(2500)
                    }
                    else -> {
                        Log.e(TAG, "seed http ${res.code}")
                        return null
                    }
                }
            }
        }
    }

    class SourcesRequest(
        val mediaType: String,
        val tmdbId: String,
        val imdbId: String,
        val title: String,
        val year: String,
        val season: Int,
        val episode: Int,
        val germanTitle: String? = null,
    )

    /**
     * The site double encodes the title (their http client encodes an already
     * encoded value), the extractors on the other side expect that leftover
     * percent escaping, so the wire has to match it exactly.
     */
    private fun titleOnWire(title: String): String {
        val once = java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        return once.replace("%", "%25")
    }

    private fun buildUrl(city: City, request: SourcesRequest, seed: String): String {
        val parts = StringBuilder()
        parts.append("title=").append(titleOnWire(request.title))
        parts.append("&mediaType=").append(request.mediaType)
        parts.append("&year=").append(request.year)
        parts.append("&episodeId=").append(request.episode)
        parts.append("&seasonId=").append(request.season)
        parts.append("&tmdbId=").append(request.tmdbId)
        if (request.imdbId.isNotBlank()) {
            parts.append("&imdbId=").append(request.imdbId)
        }
        if (city.slug == "munich") {
            parts.append("&language=german")
        }
        if (city.slug == "berlin" && !request.germanTitle.isNullOrBlank()) {
            parts.append("&altTitle=").append(titleOnWire(request.germanTitle))
        }
        parts.append("&enc=2&seed=").append(java.net.URLEncoder.encode(seed, "UTF-8"))
        return "$STREAM_BASE/${city.slug}/sources?$parts"
    }

    /**
     * One city attempt loop. A 401 is usually a wrong node, so the same seed
     * gets two quick retries before a new one is pulled; a 429 asks for a
     * cool down; one transport retry keeps slow extractors in play. Anything
     * else means this city has nothing for the title right now.
     */
    suspend fun citySources(
        city: City,
        request: SourcesRequest,
        pool: SeedPool,
    ): MovySourcesReply? {
        var transportRetries = 1
        var staleSeedStrikes = 0
        var coolDowns = 0
        for (attempt in 0 until 5) {
            val seed = pool.current() ?: return null
            val url = buildUrl(city, request, seed)
            val code: Int
            val body: String?
            try {
                val res = app.get(url, headers = baseHeaders, timeout = 30_000L)
                code = res.code
                body = if (code == 200) res.text else null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (transportRetries > 0) {
                    transportRetries--
                    delay(500)
                    continue
                }
                return null
            }
            when {
                code == 200 && body != null -> {
                    val plain = MovyCrypto.decrypt(body, seed, request.tmdbId)
                    if (plain == null) {
                        // marker mismatch, the node scrambled with another seed
                        staleSeedStrikes++
                        if (staleSeedStrikes >= 2) {
                            pool.drop()
                            staleSeedStrikes = 0
                        }
                        delay(250)
                        continue
                    }
                    return try {
                        parseJson<MovySourcesReply>(plain)
                    } catch (e: Exception) {
                        Log.e(TAG, "sources parse ${city.slug}: ${e.message}")
                        null
                    }
                }
                code == 401 -> {
                    staleSeedStrikes++
                    if (staleSeedStrikes >= 3) {
                        pool.drop()
                        staleSeedStrikes = 0
                    }
                    delay(250)
                }
                code == 429 -> {
                    if (coolDowns >= 1) return null
                    coolDowns++
                    delay(2500)
                    pool.drop()
                }
                else -> return null
            }
        }
        return null
    }
}
