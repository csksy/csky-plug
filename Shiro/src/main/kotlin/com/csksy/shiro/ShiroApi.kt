package com.csksy.shiro

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object ShiroApi {
    private const val TAG = "Shiro"

    private const val SITE = "https://shiro.so"
    private const val ANILIST = "https://graphql.anilist.co"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // rows only need the poster and the labels under it, the html synopsis is
    // asked for on the detail query alone and cuts these answers to a tenth
    private const val LIST_FIELDS =
        "id title{romaji english native} coverImage{large extraLarge} format seasonYear averageScore"

    private val SEARCH_QUERY = """
        query (${'$'}search: String!, ${'$'}page: Int, ${'$'}perPage: Int) {
            Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage }
                media(type: ANIME, isAdult: false, search: ${'$'}search, sort: SEARCH_MATCH) { %s }
            }
        }
    """.trimIndent()

    private val LIST_QUERY = """
        query (${'$'}page: Int, ${'$'}sort: [MediaSort!]!, ${'$'}statuses: [MediaStatus!]) {
            Page(page: ${'$'}page, perPage: 24) {
                pageInfo { hasNextPage }
                media(type: ANIME, isAdult: false, sort: ${'$'}sort, status_in: ${'$'}statuses) { %s }
            }
        }
    """.trimIndent()

    private val DETAIL_QUERY = """
        query (${'$'}id: Int) {
            Media(id: ${'$'}id, type: ANIME, isAdult: false) {
                id
                idMal
                title { romaji english native }
                coverImage { large extraLarge }
                bannerImage
                format
                status
                episodes
                seasonYear
                genres
                averageScore
                description(asHtml: true)
                nextAiringEpisode { episode }
                duration
                studios(isMain: true) { nodes { name } }
                trailer { id site }
                streamingEpisodes { title thumbnail url }
                recommendations(sort: RATING_DESC, perPage: 10) {
                    nodes { mediaRecommendation { id title { romaji english native } coverImage { large extraLarge } format seasonYear } }
                }
            }
        }
    """.trimIndent()

    // the site holds anilist answers for five minutes, doing the same keeps
    // the home screen from reasking anilist on every refresh and pushes the
    // rate limit further away
    private const val LIST_TTL = 5 * 60 * 1000L

    private lateinit var prefs: SharedPreferences
    private val cookieMutex = Mutex()

    @Volatile
    private var watchCookie: String? = null

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("shiro", Context.MODE_PRIVATE)
            watchCookie = prefs.getString("watch_cookie", null)
        }
        appContext = context.applicationContext
    }

    class AnilistUnavailable(message: String) : Exception(message)
    class SiteUnavailable(message: String) : Exception(message)

    class Media(
        val id: Int,
        val title: String,
        val poster: String,
        val idMal: Int? = null,
        val banner: String? = null,
        val format: String = "",
        val status: String = "",
        val episodes: Int? = null,
        val year: Int? = null,
        val genres: List<String> = emptyList(),
        val score: Double? = null,
        val description: String? = null,
        val nextEpisode: Int? = null,
        val duration: Int? = null,
        val studio: String? = null,
        val trailerUrl: String? = null,
        val streamTitles: Map<Int, Pair<String, String>> = emptyMap(),
        val recommendations: List<Media> = emptyList()
    )

    private fun displayTitle(o: JSONObject): String {
        val t = o.optJSONObject("title")
        return t?.optString("english")?.takeIf { it.isNotBlank() }
            ?: t?.optString("romaji")?.takeIf { it.isNotBlank() }
            ?: t?.optString("native")?.orEmpty() ?: ""
    }

    private fun trailerUrl(o: JSONObject): String? {
        val t = o.optJSONObject("trailer") ?: return null
        return when (t.optString("site").lowercase()) {
            "youtube" -> "https://www.youtube.com/watch?v=${t.optString("id")}"
            "dailymotion" -> "https://www.dailymotion.com/video/${t.optString("id")}"
            else -> null
        }
    }

    private fun mediaFromJson(o: JSONObject): Media {
        val streamTitles = mutableMapOf<Int, Pair<String, String>>()
        val arr = o.optJSONArray("streamingEpisodes")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val title = e.optString("title").trim()
                if (title.isBlank()) continue
                val num = Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE)
                    .find(title)?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)
                streamTitles[num] = Pair(title, e.optString("thumbnail"))
            }
        }
        val recommendations = ArrayList<Media>()
        val nodes = o.optJSONObject("recommendations")?.optJSONArray("nodes")
        if (nodes != null) {
            for (i in 0 until nodes.length()) {
                val r = nodes.optJSONObject(i)?.optJSONObject("mediaRecommendation") ?: continue
                val title = displayTitle(r)
                if (title.isBlank()) continue
                val cover = r.optJSONObject("coverImage")
                recommendations.add(
                    Media(
                        id = r.optInt("id"),
                        title = title,
                        poster = cover?.optString("extraLarge")?.takeIf { it.isNotBlank() }
                            ?: cover?.optString("large").orEmpty(),
                        format = r.optString("format"),
                        year = if (r.has("seasonYear") && !r.isNull("seasonYear")) r.optInt("seasonYear") else null
                    )
                )
            }
        }
        return Media(
            id = o.optInt("id"),
            idMal = if (o.has("idMal") && !o.isNull("idMal")) o.optInt("idMal") else null,
            title = displayTitle(o),
            poster = o.optJSONObject("coverImage")?.optString("large")?.takeIf { it.isNotBlank() }
                ?: o.optJSONObject("coverImage")?.optString("extraLarge").orEmpty(),
            banner = o.optString("bannerImage").takeIf { it.isNotBlank() && it != "null" },
            format = o.optString("format"),
            status = o.optString("status"),
            episodes = if (o.has("episodes") && !o.isNull("episodes")) o.optInt("episodes") else null,
            year = if (o.has("seasonYear") && !o.isNull("seasonYear")) o.optInt("seasonYear") else null,
            genres = buildList {
                val g = o.optJSONArray("genres")
                if (g != null) for (i in 0 until g.length()) add(g.optString(i))
            },
            score = if (o.has("averageScore") && !o.isNull("averageScore")) o.optInt("averageScore") / 10.0 else null,
            description = o.optString("description").takeIf { it.isNotBlank() && it != "null" }
                ?.replace(Regex("<br\\s*/?>"), "\n")
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace("&quot;", "\"")
                ?.replace("&amp;", "&")
                ?.replace("&#039;", "'")
                ?.trim(),
            nextEpisode = o.optJSONObject("nextAiringEpisode")?.optInt("episode")?.takeIf { it > 0 },
            duration = if (o.has("duration") && !o.isNull("duration")) o.optInt("duration") else null,
            studio = o.optJSONArray("studios")?.optJSONObject(0)
                ?.optJSONArray("nodes")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() },
            trailerUrl = trailerUrl(o),
            streamTitles = streamTitles,
            recommendations = recommendations
        )
    }

    private fun retryAfterSeconds(res: NiceResponse): Long {
        val reset = res.headers["x-ratelimit-reset"]?.trim()?.toLongOrNull()
        if (reset != null) {
            val delta = reset - System.currentTimeMillis() / 1000
            if (delta > 0) return delta.coerceIn(2L, 12L)
        }
        return (res.headers["retry-after"]?.trim()?.toLongOrNull() ?: 6L).coerceIn(2L, 12L)
    }

    // anilist takes the query as a urlencoded form as well, some mobile
    // carriers run filtering proxies that shred json request bodies and
    // anilist answers those with a 400, form bodies pass through untouched
    private suspend fun graphql(query: String, variables: JSONObject): JSONObject {
        var reason = "AniList could not be reached. Check your connection and retry."
        for (attempt in 0 until 2) {
            val res = try {
                app.post(
                    ANILIST,
                    requestBody = FormBody.Builder()
                        .add("query", query)
                        .add("variables", variables.toString())
                        .build(),
                    headers = mapOf(
                        "Accept" to "application/json",
                        "User-Agent" to USER_AGENT
                    ),
                    timeout = 20L
                )
            } catch (e: Exception) {
                if (attempt == 0) {
                    delay(2000L)
                    continue
                }
                Log.d(TAG, "anilist request failed: ${e.message}")
                throw AnilistUnavailable(reason)
            }
            if (res.code == 429) {
                reason = "AniList is rate limiting this connection, wait a minute and retry."
                if (attempt == 0) {
                    delay(retryAfterSeconds(res) * 1000L)
                    continue
                }
                throw AnilistUnavailable(reason)
            }
            if (!res.isSuccessful) {
                // a one off 400 or 5xx happens on their edge, only the second
                // one in a row is treated as a real answer
                Log.d(TAG, "anilist answered ${res.code}: ${res.text.take(120)}")
                if (attempt == 0) {
                    delay(2000L)
                    continue
                }
                throw AnilistUnavailable("AniList answered with ${res.code}.")
            }
            val data = try {
                JSONObject(res.text).optJSONObject("data")
            } catch (e: Exception) {
                null
            }
            return data ?: throw AnilistUnavailable("AniList returned no data.")
        }
        throw AnilistUnavailable(reason)
    }

    private class PageCache(val expireAt: Long, val media: List<Media>, val hasNext: Boolean)

    private val pageCache = ConcurrentHashMap<String, PageCache>()

    private suspend fun pageOf(query: String, vars: JSONObject, cacheKey: String): PageCache {
        pageCache[cacheKey]?.takeIf { System.currentTimeMillis() < it.expireAt }?.let { return it }
        val data = graphql(query, vars)
        val pageObj = data.optJSONObject("Page") ?: throw AnilistUnavailable("AniList returned no data.")
        val media = pageObj.optJSONArray("media")
        val out = ArrayList<Media>()
        if (media != null) {
            for (i in 0 until media.length()) {
                media.optJSONObject(i)?.let { out.add(mediaFromJson(it)) }
            }
        }
        val hasNext = pageObj.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false
        val entry = PageCache(System.currentTimeMillis() + LIST_TTL, out, hasNext)
        pageCache[cacheKey] = entry
        return entry
    }

    suspend fun search(query: String, page: Int): Pair<List<Media>, Boolean> {
        val entry = pageOf(
            String.format(SEARCH_QUERY, LIST_FIELDS),
            JSONObject().put("search", query).put("page", page).put("perPage", 30),
            "search|$query|$page"
        )
        return Pair(entry.media, entry.hasNext)
    }

    suspend fun list(
        sort: String,
        page: Int,
        statuses: List<String>? = null
    ): Pair<List<Media>, Boolean> {
        val vars = JSONObject().put("page", page).put("sort", JSONArray().put(sort))
        statuses?.let { list -> vars.put("statuses", JSONArray(list)) }
        val entry = pageOf(
            String.format(LIST_QUERY, LIST_FIELDS),
            vars,
            "$sort|${statuses?.joinToString(",") ?: ""}|$page"
        )
        return Pair(entry.media, entry.hasNext)
    }

    private class DetailCache(val id: Int, val expireAt: Long, val media: Media)

    @Volatile
    private var detailCache: DetailCache? = null

    suspend fun detail(anilistId: Int): Media? {
        detailCache?.takeIf { it.id == anilistId && System.currentTimeMillis() < it.expireAt }
            ?.let { return it.media }
        var anilistFailure: AnilistUnavailable? = null
        var media = try {
            graphql(DETAIL_QUERY, JSONObject().put("id", anilistId)).optJSONObject("Media")
                ?.let { mediaFromJson(it) }
        } catch (e: AnilistUnavailable) {
            anilistFailure = e
            null
        }
        if (media == null) {
            // the site pages answer while anilist does not, an anime that is
            // on neither is not on shiro at all
            media = siteDetail(anilistId) ?: anilistFailure?.let { throw it } ?: return null
        }
        detailCache = DetailCache(anilistId, System.currentTimeMillis() + LIST_TTL, media)
        return media
    }

    // shiro's own schedule feed, unlike the rest of the catalog it never goes
    // through anilist so it keeps filling the home page while anilist is
    // limiting or unreachable
    @Volatile
    private var recentCache: Pair<Long, List<Media>>? = null

    suspend fun recentEpisodes(): List<Media> {
        recentCache?.takeIf { System.currentTimeMillis() < it.first }?.let { return it.second }
        val res = try {
            app.get(
                "$SITE/api/recent-episodes",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
                timeout = 15L
            )
        } catch (e: Exception) {
            Log.d(TAG, "recent episodes request failed: ${e.message}")
            throw SiteUnavailable("shiro.so could not be reached (${e.message}).")
        }
        if (!res.isSuccessful) throw SiteUnavailable("shiro.so answered with ${res.code}.")
        val schedules = try {
            JSONObject(res.text).getJSONArray("schedules")
        } catch (e: Exception) {
            Log.d(TAG, "recent episodes parse failed: ${e.message}")
            // a proxy or block page instead of the feed, swallowing this is
            // what left the home screen blank with nothing to read
            throw SiteUnavailable("shiro.so did not send its episode feed, the connection is probably filtered.")
        }
        val out = ArrayList<Media>()
        val seen = HashSet<Int>()
        val now = System.currentTimeMillis() / 1000
        for (i in 0 until schedules.length()) {
            if (out.size >= 16) break
            val s = schedules.optJSONObject(i) ?: continue
            if (s.optLong("airingAt", Long.MAX_VALUE) > now) continue
            val m = s.optJSONObject("media") ?: continue
            if (m.optBoolean("isAdult")) continue
            if (!seen.add(m.optInt("id"))) continue
            val title = displayTitle(m)
            if (title.isBlank()) continue
            val cover = m.optJSONObject("coverImage")
            val poster = cover?.optString("extraLarge")?.takeIf { it.isNotBlank() }
                ?: cover?.optString("large").orEmpty()
            if (poster.isBlank()) continue
            out.add(Media(id = m.optInt("id"), title = title, poster = poster, format = m.optString("format")))
        }
        recentCache = Pair(System.currentTimeMillis() + 60 * 1000L, out)
        return out
    }

    private val flightChunk =
        Regex("""self\.__next_f\.push\(\[1,\s*("(?:[^"\\]|\\.)*")\s*\]\)""")
    private val ldJsonScript =
        Regex("""<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val ogImage = Regex("""<meta[^>]+property="og:image"[^>]+content="([^"]*)"""")

    // next.js streams its page data as escaped js string pushes, the anime
    // object the player runs on sits inside them
    private fun unescapeJs(quoted: String): String {
        val s = quoted.removePrefix("\"").removeSuffix("\"")
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    'b' -> { out.append('\b'); i += 2 }
                    'u' -> {
                        val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            i += 6
                        } else {
                            out.append(s[i + 1]); i += 2
                        }
                    }
                    else -> { out.append(s[i + 1]); i += 2 }
                }
            } else {
                out.append(s[i]); i += 1
            }
        }
        return out.toString()
    }

    private fun balancedJson(s: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun flightAnime(blob: String): JSONObject? {
        val at = blob.indexOf("\"episodeCount\"")
        if (at < 0) return null
        var start = blob.lastIndexOf('{', at)
        while (start >= 0) {
            val candidate = balancedJson(blob, start)
            if (candidate == null) return null
            try {
                val o = JSONObject(candidate)
                if (o.has("episodeCount") && o.has("title")) return o
            } catch (e: Exception) {
                // a nested object, step out one brace and retry
            }
            start = blob.lastIndexOf('{', start - 1)
        }
        return null
    }

    private fun ldAnimeNode(html: String): JSONObject? {
        val ld = ldJsonScript.find(html)?.groupValues?.get(1) ?: return null
        val graph = try {
            JSONObject(ld).optJSONArray("@graph")
        } catch (e: Exception) {
            null
        } ?: return null
        for (i in 0 until graph.length()) {
            val n = graph.optJSONObject(i) ?: continue
            val type = n.optString("@type")
            if (type == "TVSeries" || type == "Movie") return n
        }
        return null
    }

    // the pages render the whole catalog entry the player runs on, including
    // the episode count the site itself lists by, so an anime stays openable
    // even while anilist refuses the connection
    suspend fun siteDetail(anilistId: Int): Media? {
        val res = try {
            app.get(
                "$SITE/anime/$anilistId",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html"),
                timeout = 20L
            )
        } catch (e: Exception) {
            Log.d(TAG, "site detail request failed: ${e.message}")
            throw SiteUnavailable("shiro.so could not be reached (${e.message}).")
        }
        if (res.code == 404) return null
        if (!res.isSuccessful) throw SiteUnavailable("shiro.so answered with ${res.code}.")

        val html = res.text
        val blob = StringBuilder()
        for (m in flightChunk.findAll(html)) {
            blob.append(unescapeJs(m.groupValues[1]))
        }
        val node = flightAnime(blob.toString())
        if (node != null) {
            val facts = HashMap<String, String>()
            val fa = node.optJSONArray("facts")
            if (fa != null) {
                for (i in 0 until fa.length()) {
                    val f = fa.optJSONObject(i) ?: continue
                    facts[f.optString("label")] = f.optString("value")
                }
            }
            return Media(
                id = anilistId,
                idMal = node.optInt("idMal", 0).takeIf { it > 0 },
                title = node.optString("title"),
                poster = node.optString("cover"),
                banner = node.optString("banner").takeIf { it.isNotBlank() },
                format = node.optString("format"),
                status = facts["Status"]?.uppercase().orEmpty(),
                episodes = node.optInt("episodeCount", 0).takeIf { it > 0 },
                year = if (node.has("seasonYear") && !node.isNull("seasonYear")) node.optInt("seasonYear") else null,
                genres = buildList {
                    val g = node.optJSONArray("genres")
                    if (g != null) for (i in 0 until g.length()) add(g.optString(i))
                },
                score = if (node.has("score") && !node.isNull("score")) node.optDouble("score") else null,
                duration = facts["Duration"]?.filter { it.isDigit() }?.toIntOrNull(),
                studio = facts["Studio"]?.takeIf { it.isNotBlank() },
                trailerUrl = node.optString("trailer")
                    .takeIf { it.isNotBlank() && it != "null" }?.replace("%09", ""),
                description = ldAnimeNode(html)?.optString("description")?.takeIf { it.isNotBlank() }
            )
        }

        // the flight layout is not set in stone, the ld+json every page also
        // carries covers the important fields
        val ld = ldAnimeNode(html) ?: return null
        val name = ld.optString("name").takeIf { it.isNotBlank() } ?: return null
        return Media(
            id = anilistId,
            title = name,
            poster = ld.optString("image"),
            banner = ogImage.find(html)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() && it != "$SITE/og-image.jpg" },
            format = if (ld.optInt("numberOfEpisodes", 0) == 1) "MOVIE" else "TV",
            episodes = ld.optInt("numberOfEpisodes", 0).takeIf { it > 0 } ?: 12,
            year = ld.optString("startDate").take(4).toIntOrNull(),
            genres = buildList {
                val g = ld.optJSONArray("genre")
                if (g != null) for (i in 0 until g.length()) add(g.optString(i))
            },
            description = ld.optString("description").takeIf { it.isNotBlank() }
        )
    }

    class EpisodeSource(
        val id: String,
        val label: String,
        val url: String,
        val isHls: Boolean,
        val tracks: List<SubtitleTrack>
    )

    class SubtitleTrack(val label: String, val src: String)

    class EpisodeStreams(
        val sub: List<EpisodeSource>,
        val hsub: List<EpisodeSource>,
        val dub: List<EpisodeSource>
    )

    // the stream urls only answer the cookie that asked for them, so the
    // cookie that minted the sources rides along for the player headers
    class StreamsAnswer(
        val streams: EpisodeStreams,
        val cookie: String
    )

    private fun emptyStreams() = EpisodeStreams(emptyList(), emptyList(), emptyList())

    // the watch cookie is handed out by any /anime/{slug}/{n} page, a made
    // up slug works too, it stays valid for around a year and is never
    // rotated so one fetch per install is usually enough
    private suspend fun fetchCookie(): String? {
        val res = try {
            app.get("$SITE/anime/one-piece/1", headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L)
        } catch (e: Exception) {
            Log.d(TAG, "cookie fetch failed: ${e.message}")
            return null
        }
        val set = res.headers.values("set-cookie")
        for (c in set) {
            if (c.startsWith("shiro_watch=")) {
                return c.substringBefore(";").trim()
            }
        }
        return null
    }

    private suspend fun cookie(): String? {
        watchCookie?.let { return it }
        return cookieMutex.withLock {
            watchCookie?.let { return it }
            val fresh = fetchCookie()
            if (fresh != null) {
                watchCookie = fresh
                prefs.edit().putString("watch_cookie", fresh).apply()
            }
            fresh
        }
    }

    private fun clearCookie() {
        watchCookie = null
        prefs.edit().remove("watch_cookie").apply()
    }

    private fun parseStreams(body: JSONObject): EpisodeStreams? {
        if (body.optString("status") != "ready") return null
        val variants = body.optJSONArray("variants") ?: return null
        val sub = ArrayList<EpisodeSource>()
        val hsub = ArrayList<EpisodeSource>()
        val dub = ArrayList<EpisodeSource>()
        for (i in 0 until variants.length()) {
            val v = variants.optJSONObject(i) ?: continue
            val bucket = when (v.optString("id")) {
                "sub" -> sub
                "hsub" -> hsub
                "dub" -> dub
                else -> null
            } ?: continue
            val sources = v.optJSONArray("sources") ?: continue
            for (j in 0 until sources.length()) {
                val s = sources.optJSONObject(j) ?: continue
                val url = s.optString("url")
                if (!url.startsWith("/stream/")) continue
                val tracks = ArrayList<SubtitleTrack>()
                val tarr = s.optJSONArray("tracks")
                if (tarr != null) {
                    for (k in 0 until tarr.length()) {
                        val t = tarr.optJSONObject(k) ?: continue
                        val src = t.optString("src")
                        if (!src.startsWith("/stream/")) continue
                        val label = t.optString("label").takeIf { it.isNotBlank() }
                            ?: t.optString("language").takeIf { it.isNotBlank() } ?: "Subtitle"
                        tracks.add(SubtitleTrack(label, src))
                    }
                }
                bucket.add(
                    EpisodeSource(
                        id = s.optString("id"),
                        label = s.optString("label"),
                        url = url,
                        isHls = s.optString("type").contains("mpegurl"),
                        tracks = tracks
                    )
                )
            }
        }
        return EpisodeStreams(sub, hsub, dub)
    }

    // some carriers shred json request bodies on their filtering proxies and
    // the episode endpoint answers those with a 400, the site parses the same
    // body sent as text/plain so that flavour is the working one there while
    // clean networks are free to keep the canonical json. whichever flavour
    // last got a 200 is remembered and tried first
    @Volatile
    private var plainBodyWorks: Boolean = false

    private fun rememberFlavor(asText: Boolean) {
        if (plainBodyWorks == asText) return
        plainBodyWorks = asText
        prefs.edit().putBoolean("plain_body", asText).apply()
    }

    private suspend fun postEpisode(payload: JSONObject, cookie: String, asText: Boolean): NiceResponse? {
        val type = if (asText) "text/plain" else "application/json"
        return try {
            app.post(
                "$SITE/api/episode",
                requestBody = payload.toString().toRequestBody(type.toMediaType()),
                headers = mapOf(
                    "Origin" to SITE,
                    "Referer" to "$SITE/",
                    "Cookie" to cookie,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*"
                ),
                timeout = 30L
            )
        } catch (e: Exception) {
            Log.d(TAG, "episode request failed: ${e.message}")
            null
        }
    }

    // cloudflare answers a bot check with its own html page instead of the
    // json the api speaks, that needs the webview solver not a cookie swap
    private fun challengeBlocked(res: NiceResponse): Boolean {
        if (res.headers["cf-mitigated"] != null) return true
        val body = try {
            res.text
        } catch (e: Exception) {
            return false
        }
        return body.contains("Just a moment") ||
            body.contains("cf-chl") ||
            body.contains("Attention Required") ||
            body.contains("sorry, you have been blocked")
    }

    private fun jsonOf(res: NiceResponse): JSONObject? = try {
        JSONObject(res.text)
    } catch (e: Exception) {
        null
    }

    // the episode endpoint hands out twenty answers per watch cookie and then
    // answers 429 for about a minute, a fresh cookie starts the count over so
    // the limit is rotated away instead of waited out
    suspend fun streams(anilistId: Int, malId: Int?, episode: Int): StreamsAnswer? {
        val payload = JSONObject().put("anilistId", anilistId).put("episode", episode)
        malId?.let { payload.put("malId", it) }

        var sawChallenge = false
        var sawBadAnswer = false
        var waitedOnce = false
        var rotatedJustNow = false

        for (round in 0 until 4) {
            val cookie = cookie() ?: break
            val flavors = if (plainBodyWorks) listOf(true, false) else listOf(false, true)
            for (asText in flavors) {
                val res = postEpisode(payload, cookie, asText)
                if (res == null) {
                    // the network refused the call outright, the webview gets
                    // a chance with its own browser stack later
                    sawBadAnswer = true
                    continue
                }
                when {
                    res.isSuccessful -> {
                        val body = jsonOf(res)
                        if (body != null && body.optString("status") == "ready") {
                            rememberFlavor(asText)
                            val parsed = parseStreams(body)
                            if (parsed != null) return StreamsAnswer(parsed, cookie)
                            sawBadAnswer = true
                        } else if (body != null && body.optString("status") == "unavailable") {
                            // the site itself says this episode has nothing,
                            // no amount of retrying changes that answer
                            rememberFlavor(asText)
                            return StreamsAnswer(emptyStreams(), cookie)
                        } else {
                            sawBadAnswer = true
                        }
                    }
                    res.code == 429 -> {
                        // a fresh cookie starts a clean answer budget, so the
                        // limit is rotated away instead of waited out, the
                        // retry-after only matters when even a fresh cookie
                        // lands on a hot edge for this ip
                        clearCookie()
                        if (rotatedJustNow && !waitedOnce) {
                            waitedOnce = true
                            val wait = (res.headers["retry-after"]?.trim()?.toLongOrNull() ?: 5L)
                                .coerceIn(3L, 15L)
                            delay(wait * 1000L)
                            if (cookie() == null) break
                        }
                        rotatedJustNow = true
                        break
                    }
                    res.code == 403 -> {
                        if (challengeBlocked(res)) {
                            sawChallenge = true
                        } else {
                            // the cookie went stale mid session, a fresh one
                            // is fetched and the call goes out again
                            clearCookie()
                        }
                        break
                    }
                    else -> {
                        if (challengeBlocked(res)) sawChallenge = true else sawBadAnswer = true
                    }
                }
            }
            if (sawChallenge) break
        }

        if (sawChallenge || sawBadAnswer) {
            webviewEpisode(payload)?.let { return it }
        }
        return null
    }

    // last resort when every direct call is refused: a throwaway webview
    // opens a watch page of the site, which plants a fresh watch cookie in
    // its own jar, and the episode call runs from inside that page with the
    // browser stack, the same path the site's own player takes
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun webviewEpisode(payload: JSONObject): StreamsAnswer? {
        val context = appContext ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                val asked = AtomicBoolean(false)
                var webView: WebView? = null
                val handler = Handler(Looper.getMainLooper())

                fun finish(answer: StreamsAnswer?) {
                    if (done.compareAndSet(false, true)) {
                        try {
                            webView?.stopLoading()
                        } catch (e: Exception) {
                            Log.d(TAG, "webview stop failed: ${e.message}")
                        }
                        try {
                            webView?.destroy()
                        } catch (e: Exception) {
                            Log.d(TAG, "webview destroy failed: ${e.message}")
                        }
                        cont.resume(answer)
                    }
                }

                fun harvestCookie(): String? {
                    return try {
                        val jar = CookieManager.getInstance().getCookie(SITE).orEmpty()
                        val match = Regex("shiro_watch=[^;]+").find(jar)?.value
                        if (match != null) {
                            watchCookie = match
                            prefs.edit().putString("watch_cookie", match).apply()
                        }
                        match
                    } catch (e: Exception) {
                        Log.d(TAG, "cookie harvest failed: ${e.message}")
                        null
                    }
                }

                fun askOnce(view: WebView) {
                    if (done.get() || !asked.compareAndSet(false, true)) return
                    val js = """
                        (function(){
                            window.__ep = null;
                            fetch('/api/episode', {
                                method: 'POST',
                                credentials: 'include',
                                headers: {'Content-Type': 'application/json', 'Accept': '*/*'},
                                body: ${payload}
                            }).then(function(r){
                                return r.text().then(function(t){
                                    window.__ep = JSON.stringify({code: r.status, body: t});
                                });
                            }).catch(function(e){
                                window.__ep = JSON.stringify({code: 0, body: String(e)});
                            });
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(js) {}
                    for (i in 1..30) {
                        handler.postDelayed({
                            if (done.get()) return@postDelayed
                            view.evaluateJavascript(
                                "(function(){ return window.__ep; })()"
                            ) { raw ->
                                if (done.get() || raw == null || raw == "null") return@evaluateJavascript
                                val unwrapped = try {
                                    JSONArray("[$raw]").optString(0)
                                } catch (e: Exception) {
                                    null
                                }
                                val answer = try {
                                    JSONObject(unwrapped ?: raw)
                                } catch (e: Exception) {
                                    null
                                } ?: return@evaluateJavascript
                                val cookie = harvestCookie()
                                if (answer.optInt("code") == 200 && cookie != null) {
                                    val body = try {
                                        JSONObject(answer.optString("body"))
                                    } catch (e: Exception) {
                                        null
                                    }
                                    if (body != null && body.optString("status") == "ready") {
                                        val parsed = parseStreams(body)
                                        finish(parsed?.let { StreamsAnswer(it, cookie) })
                                    } else if (body != null && body.optString("status") == "unavailable") {
                                        finish(StreamsAnswer(emptyStreams(), cookie))
                                    } else {
                                        finish(null)
                                    }
                                } else {
                                    finish(null)
                                }
                            }
                        }, i * 500L)
                    }
                }

                try {
                    CookieManager.getInstance().setAcceptCookie(true)
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = USER_AGENT
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // the cookie lands with the page response, the
                                // call goes out a beat later once it is set
                                handler.postDelayed({
                                    if (view != null) askOnce(view)
                                }, 600L)
                            }
                        }
                        loadUrl("$SITE/anime/one-piece/1")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "webview failed to start: ${e.message}")
                    finish(null)
                    return@suspendCancellableCoroutine
                }

                handler.postDelayed({ finish(null) }, 25000L)
            }
        }
    }

    // the dub tab needs one episode answer per anime, remembering it for
    // half a day keeps browsing from burning through the per cookie budget
    private val dubCache = ConcurrentHashMap<Int, Pair<Long, Boolean>>()

    suspend fun hasDub(anilistId: Int, malId: Int?): Boolean {
        val now = System.currentTimeMillis()
        dubCache[anilistId]?.let { (at, dub) ->
            if (now - at < 12 * 60 * 60 * 1000L) return dub
        }
        val dub = streams(anilistId, malId, 1)?.streams?.dub?.isNotEmpty() == true
        dubCache[anilistId] = Pair(now, dub)
        return dub
    }

    class HlsVariant(val url: String, val height: Int)

    // masters carry the playable qualities in RESOLUTION tags, the I-FRAME
    // entries inline their URI so the line after a stream-inf is only ever
    // the real variant
    fun parseMaster(masterText: String, masterUrl: String): List<HlsVariant> {
        val out = ArrayList<HlsVariant>()
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                val height = Regex("""RESOLUTION=\d+x(\d+)""").find(lines[i])
                    ?.groupValues?.get(1)?.toIntOrNull()
                val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (next.isNotEmpty() && !next.startsWith("#") && height != null) {
                    val url = when {
                        next.startsWith("http") -> next
                        next.startsWith("/") -> SITE + next
                        else -> masterUrl.substringBeforeLast("/") + "/" + next
                    }
                    out.add(HlsVariant(url, height))
                }
                i += 2
            } else {
                i += 1
            }
        }
        return out
    }

    fun streamHeaders(cookie: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$SITE/",
        "Cookie" to cookie
    )

    suspend fun fetchMaster(url: String, cookie: String): NiceResponse? {
        return try {
            app.get(url, headers = streamHeaders(cookie), timeout = 20L)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun probe(url: String, cookie: String): Boolean {
        return try {
            val res = app.get(
                url,
                headers = streamHeaders(cookie) + mapOf("Range" to "bytes=0-1023"),
                timeout = 15L
            )
            res.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // a 16 byte ranged read is enough to tell a webvtt apart from the ass
    // files some providers mix in
    suspend fun fetchTextHead(url: String, cookie: String): String? {
        return try {
            val res = app.get(
                url,
                headers = streamHeaders(cookie) + mapOf("Range" to "bytes=0-15"),
                timeout = 15L
            )
            if (res.isSuccessful) res.text else null
        } catch (e: Exception) {
            null
        }
    }
}
