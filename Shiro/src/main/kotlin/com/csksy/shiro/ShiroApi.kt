package com.csksy.shiro

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

object ShiroApi {
    private const val TAG = "Shiro"

    private const val SITE = "https://shiro.so"
    private const val ANILIST = "https://graphql.anilist.co"

    private val GRAPH_FIELDS = """
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
    """

    private val SEARCH_QUERY = """
        query (${'$'}search: String!, ${'$'}page: Int, ${'$'}perPage: Int) {
            Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage }
                media(type: ANIME, isAdult: false, search: ${'$'}search, sort: SEARCH_MATCH) { %s }
            }
        }
    """.trimIndent()

    private val LIST_QUERY = """
        query (${'$'}page: Int, ${'$'}sort: [MediaSort!]!, ${'$'}seasonYear: Int, ${'$'}statuses: [MediaStatus!]) {
            Page(page: ${'$'}page, perPage: 24) {
                pageInfo { hasNextPage }
                media(type: ANIME, isAdult: false, sort: ${'$'}sort, seasonYear: ${'$'}seasonYear, status_in: ${'$'}statuses) { %s }
            }
        }
    """.trimIndent()

    private val DETAIL_QUERY = """
        query (${'$'}id: Int) {
            Media(id: ${'$'}id, type: ANIME, isAdult: false) {
                %s
                duration
                source
                studios(isMain: true) { nodes { name } }
                trailer { id site }
                streamingEpisodes { title thumbnail url }
            }
        }
    """.trimIndent()

    private lateinit var prefs: SharedPreferences
    private val cookieMutex = Mutex()

    @Volatile
    private var watchCookie: String? = null

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("shiro", Context.MODE_PRIVATE)
            watchCookie = prefs.getString("watch_cookie", null)
        }
    }

    class Media(
        val id: Int,
        val idMal: Int?,
        val title: String,
        val poster: String,
        val banner: String?,
        val format: String,
        val status: String,
        val episodes: Int?,
        val year: Int?,
        val genres: List<String>,
        val score: Double?,
        val description: String?,
        val nextEpisode: Int?,
        val duration: Int?,
        val studio: String?,
        val trailerUrl: String?,
        val streamTitles: Map<Int, Pair<String, String>>
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
            streamTitles = streamTitles
        )
    }

    private suspend fun graphql(query: String, variables: JSONObject): JSONObject? {
        val res = try {
            app.post(
                ANILIST,
                json = JSONObject().put("query", query).put("variables", variables).toString(),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                timeout = 20L
            )
        } catch (e: Exception) {
            Log.d(TAG, "anilist request failed: ${e.message}")
            return null
        }
        if (!res.isSuccessful) return null
        return try {
            JSONObject(res.text).optJSONObject("data")
        } catch (e: Exception) {
            Log.d(TAG, "anilist parse failed: ${e.message}")
            null
        }
    }

    suspend fun search(query: String, page: Int): Pair<List<Media>, Boolean> {
        val data = graphql(
            String.format(SEARCH_QUERY, GRAPH_FIELDS),
            JSONObject().put("search", query).put("page", page).put("perPage", 30)
        ) ?: return Pair(emptyList(), false)
        val pageObj = data.optJSONObject("Page") ?: return Pair(emptyList(), false)
        val media = pageObj.optJSONArray("media") ?: return Pair(emptyList(), false)
        val out = ArrayList<Media>()
        for (i in 0 until media.length()) {
            media.optJSONObject(i)?.let { out.add(mediaFromJson(it)) }
        }
        val hasNext = pageObj.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false
        return Pair(out, hasNext)
    }

    suspend fun list(
        sort: String,
        page: Int,
        year: Int? = null,
        statuses: List<String>? = null
    ): Pair<List<Media>, Boolean> {
        val vars = JSONObject().put("page", page).put("sort", JSONArray().put(sort))
        year?.let { vars.put("seasonYear", it) }
        statuses?.let { list -> vars.put("statuses", JSONArray(list)) }
        val data = graphql(String.format(LIST_QUERY, GRAPH_FIELDS), vars) ?: return Pair(emptyList(), false)
        val pageObj = data.optJSONObject("Page") ?: return Pair(emptyList(), false)
        val media = pageObj.optJSONArray("media") ?: return Pair(emptyList(), false)
        val out = ArrayList<Media>()
        for (i in 0 until media.length()) {
            media.optJSONObject(i)?.let { out.add(mediaFromJson(it)) }
        }
        val hasNext = pageObj.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false
        return Pair(out, hasNext)
    }

    suspend fun detail(anilistId: Int): Media? {
        val data = graphql(
            String.format(DETAIL_QUERY, GRAPH_FIELDS),
            JSONObject().put("id", anilistId)
        ) ?: return null
        return data.optJSONObject("Media")?.let { mediaFromJson(it) }
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

    // the watch cookie is handed out by any /anime/{slug}/{n} page, a made
    // up slug works too, it stays valid for around a year and is never
    // rotated so one fetch per install is usually enough
    private suspend fun fetchCookie(): String? {
        val res = try {
            app.get("$SITE/anime/one-piece/1", timeout = 15L)
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

    suspend fun streamsCookie(): String? = cookie()

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

    // the endpoint answers 429 with a Retry-After while it is still pulling
    // providers and a dead cookie answers 403, both are recoverable
    suspend fun streams(anilistId: Int, malId: Int?, episode: Int): EpisodeStreams? {
        for (attempt in 0 until 3) {
            val cookie = cookie() ?: return null
            val body = JSONObject().put("anilistId", anilistId).put("episode", episode)
            malId?.let { body.put("malId", it) }
            val res = try {
                app.post(
                    "$SITE/api/episode",
                    json = body.toString(),
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Origin" to SITE,
                        "Referer" to "$SITE/",
                        "Cookie" to cookie
                    ),
                    timeout = 30L
                )
            } catch (e: Exception) {
                Log.d(TAG, "episode request failed: ${e.message}")
                return null
            }
            when {
                res.code == 403 -> {
                    clearCookie()
                    if (attempt == 2) return null
                }
                res.code == 429 -> {
                    val wait = (res.headers["retry-after"]?.trim()?.toLongOrNull() ?: 5L)
                        .coerceIn(2L, 10L)
                    kotlinx.coroutines.delay(wait * 1000)
                }
                res.isSuccessful -> {
                    val parsed = try {
                        parseStreams(JSONObject(res.text))
                    } catch (e: Exception) {
                        Log.d(TAG, "episode parse failed: ${e.message}")
                        null
                    }
                    if (parsed != null) return parsed
                    if (attempt == 2) return null
                    kotlinx.coroutines.delay(1500L)
                }
                else -> return null
            }
        }
        return null
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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
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
