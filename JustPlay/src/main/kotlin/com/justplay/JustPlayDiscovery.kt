package com.justplay

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayPersonData(
    val person: Int? = null
)

internal object PlayTmdb {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private const val KEY_ALT = "98ae14df2b8d8f8f8136499daf79f0e0"

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val query = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        return try {
            JSONObject(app.get("$BASE$path?api_key=$KEY&$query", timeout = 12000L).text)
        } catch (e: Exception) {
            try {
                JSONObject(app.get("$BASE$path?api_key=$KEY_ALT&$query", timeout = 12000L).text)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun imageUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/original$path"
    }

    fun toSearchResponse(api: MainAPI, item: JSONObject, fallbackType: String): SearchResponse? {
        val id = item.optInt("id", 0)
        if (id == 0) return null
        val mediaType = item.optString("media_type").ifBlank { fallbackType }
        if (mediaType != "movie" && mediaType != "tv") return null
        val title = item.optString("title").ifBlank { item.optString("name") }
        if (title.isBlank()) return null
        val poster = imageUrl(item.optString("poster_path"))
        val year = item.optString("release_date").ifBlank { item.optString("first_air_date") }.take(4).toIntOrNull()
        val type = if (mediaType == "movie") TvType.Movie else TvType.TvSeries
        return api.newMovieSearchResponse(title, PlayTmdbData(id, mediaType).toJson(), type) {
            this.posterUrl = poster
            this.year = year
            score = Score.from10(item.optDouble("vote_average", 0.0))
        }
    }
}

object PlayHistory {
    private const val KEY_WATCHED = "JUSTPLAY_WATCHED"
    private const val KEY_PEOPLE = "JUSTPLAY_PEOPLE"
    private const val MAX_WATCHED = 50
    private const val MAX_PEOPLE = 400
    private val lock = Any()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WatchEntry(
        val id: Int? = null,
        val type: String? = null,
        val genres: List<Int> = emptyList(),
        val directors: List<String> = emptyList(),
        val ts: Long = 0L
    )

    fun read(): List<WatchEntry> {
        val raw = try {
            CloudStreamApp.getKey<String>(KEY_WATCHED)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return try {
            parseJson<Array<WatchEntry>>(raw).toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun record(detail: JSONObject, type: String) {
        val id = detail.optInt("id", 0)
        if (id == 0) return
        val genres = ArrayList<Int>(4)
        detail.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                val gid = arr.optJSONObject(i)?.optInt("id", 0) ?: 0
                if (gid != 0) genres.add(gid)
            }
        }
        val directors = ArrayList<String>(2)
        detail.optJSONObject("credits")?.optJSONArray("crew")?.let { arr ->
            for (i in 0 until arr.length()) {
                if (directors.size >= 2) break
                val c = arr.optJSONObject(i) ?: continue
                if (c.optString("job") == "Director") {
                    c.optString("name").takeIf { it.isNotBlank() }?.let(directors::add)
                }
            }
        }
        val entry = WatchEntry(id, type, genres, directors, System.currentTimeMillis())
        val people = synchronized(lock) {
            val kept = read().filterNot { it.id == entry.id && it.type == entry.type }
            val updated = (kept + entry).takeLast(MAX_WATCHED)
            try {
                CloudStreamApp.setKey(KEY_WATCHED, updated.toJson())
            } catch (_: Exception) {
            }
            readPeople()
        }
        // the lookup needs the network, it runs after the entry is stored so a
        // failure here never blocks recording
        directors.forEach { name -> resolvePerson(name, people) }
    }

    fun topGenres(entries: List<WatchEntry>, count: Int): List<Int> =
        entries.flatMap { it.genres }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(count)

    fun topDirector(entries: List<WatchEntry>): Pair<String, Int>? =
        entries.flatMap { it.directors }
            .groupingBy { it }
            .eachCount()
            .entries
            .maxByOrNull { it.value }
            ?.let { it.key to it.value }

    fun personIdOf(name: String): Int? = readPeople()[name]

    private fun readPeople(): LinkedHashMap<String, Int> {
        val raw = try {
            CloudStreamApp.getKey<String>(KEY_PEOPLE)
        } catch (_: Exception) {
            null
        } ?: return LinkedHashMap()
        return try {
            parseJson<LinkedHashMap<String, Int>>(raw)
        } catch (_: Exception) {
            LinkedHashMap()
        }
    }

    private suspend fun resolvePerson(name: String, cached: MutableMap<String, Int>) {
        if (cached.containsKey(name)) return
        val found = PlayTmdb.get("/search/person", mapOf("query" to name, "page" to "1"))
            ?.optJSONArray("results")
            ?.optJSONObject(0)
            ?.optInt("id", 0)
            ?: 0
        if (found == 0) return
        synchronized(lock) {
            val people = readPeople()
            people[name] = found
            // insertion order is kept so the oldest lookups drop first
            while (people.size > MAX_PEOPLE) {
                people.keys.firstOrNull()?.let { people.remove(it) }
            }
            try {
                CloudStreamApp.setKey(KEY_PEOPLE, people.toJson())
            } catch (_: Exception) {
            }
        }
    }
}

internal object PlayDiscovery {

    const val ROW_SURPRISE = "surprise_me"
    const val ROW_RECOMMENDED = "recommended"
    const val ROW_ACTORS = "popular_actors"
    const val ROW_DIRECTOR = "director_row"
    const val ROW_GENRE = "mixed_genre"

    fun genreData(genreId: Int): String = "$ROW_GENRE&with_genres=$genreId"

    val genreRows = listOf(
        28 to "Action",
        35 to "Comedy",
        18 to "Drama",
        53 to "Thriller",
        12 to "Adventure",
        80 to "Crime",
        16 to "Animation",
        27 to "Horror",
        10749 to "Romance",
        878 to "Sci-Fi",
        14 to "Fantasy",
        9648 to "Mystery",
        99 to "Documentary",
        10751 to "Family"
    )

    data class LanguageRow(val code: String, val label: String, val minVotes: Int)

    val languageRows = listOf(
        LanguageRow("hi", "Hindi Movies", 20),
        LanguageRow("ta", "Tamil Movies", 10),
        LanguageRow("te", "Telugu Movies", 10),
        LanguageRow("ml", "Malayalam Movies", 10),
        LanguageRow("kn", "Kannada Movies", 10),
        LanguageRow("pa", "Punjabi Movies", 5),
        LanguageRow("bn", "Bengali Movies", 5),
        LanguageRow("mr", "Marathi Movies", 5),
        LanguageRow("en", "English Movies", 300),
        LanguageRow("ja", "Japanese Movies", 50),
        LanguageRow("ko", "Korean Movies", 20)
    )

    fun languageData(row: LanguageRow): String =
        "/discover/movie&with_original_language=${row.code}" +
            "&sort_by=popularity.desc&vote_count.gte=${row.minVotes}"

    fun directorRow(): MainPageData? {
        val entries = PlayHistory.read()
        val top = PlayHistory.topDirector(entries) ?: return null
        val personId = PlayHistory.personIdOf(top.first) ?: return null
        return MainPageData("More From ${top.first}", "$ROW_DIRECTOR&person=$personId")
    }

    fun recommendedRowName(): String =
        if (PlayHistory.read().isEmpty()) "Fresh Picks" else "Recommended for You"

    // the seen set only lives for this session, after a few thousand picks it
    // starts over so the set never grows without bound
    private val shownUrls = ConcurrentHashMap.newKeySet<String>()
    private const val SHOWN_CAP = 3000
    private const val SURPRISE_BATCH = 20

    private fun emptyRow(name: String): HomePageResponse =
        newHomePageResponse(name, emptyList<SearchResponse>(), hasNext = false)

    private fun interleave(first: List<SearchResponse>, second: List<SearchResponse>): List<SearchResponse> {
        val out = ArrayList<SearchResponse>(first.size + second.size)
        var i = 0
        var j = 0
        while (i < first.size || j < second.size) {
            if (i < first.size) out.add(first[i++])
            if (j < second.size) out.add(second[j++])
        }
        return out
    }

    private suspend fun discoverPage(
        api: MainAPI,
        path: String,
        extra: Map<String, String>,
        page: Int,
        fallbackType: String
    ): Pair<List<SearchResponse>, Boolean> {
        val params = extra + mapOf(
            "language" to "en-US",
            "page" to "$page",
            "include_adult" to "false"
        )
        val json = PlayTmdb.get(path, params) ?: return emptyList<SearchResponse>() to false
        val arr = json.optJSONArray("results") ?: return emptyList<SearchResponse>() to false
        val out = ArrayList<SearchResponse>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            PlayTmdb.toSearchResponse(api, item, fallbackType)?.let(out::add)
        }
        val hasNext = json.optInt("page", 1) < json.optInt("total_pages", 1)
        return out to hasNext
    }

    // movie and tv calls run side by side, it halves the wait on every mixed row
    private suspend fun mixedDiscover(
        api: MainAPI,
        moviePath: String,
        movieExtra: Map<String, String>,
        tvPath: String,
        tvExtra: Map<String, String>,
        page: Int
    ): Pair<List<SearchResponse>, Boolean> = coroutineScope {
        val movies = async { discoverPage(api, moviePath, movieExtra, page, "movie") }
        val shows = async { discoverPage(api, tvPath, tvExtra, page, "tv") }
        val movieSide = movies.await()
        val tvSide = shows.await()
        interleave(movieSide.first, tvSide.first) to (movieSide.second || tvSide.second)
    }

    suspend fun genrePage(api: MainAPI, page: Int, data: String, name: String): HomePageResponse {
        if (!data.startsWith(ROW_GENRE)) return emptyRow(name)
        val genreId = data.substringAfter("with_genres=", "").toIntOrNull()
        if (genreId == null || genreId == 0) return emptyRow(name)
        val (list, hasNext) = mixedDiscover(
            api,
            "/discover/movie",
            mapOf(
                "with_genres" to "$genreId",
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "100"
            ),
            "/discover/tv",
            mapOf(
                "with_genres" to "$genreId",
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "30"
            ),
            page
        )
        return newHomePageResponse(name, list, hasNext = hasNext)
    }

    suspend fun directorPage(api: MainAPI, page: Int, data: String, name: String): HomePageResponse {
        val personId = data.substringAfter("person=", "").toIntOrNull()
        if (personId == null || personId == 0) return emptyRow(name)
        val (list, hasNext) = mixedDiscover(
            api,
            "/discover/movie",
            mapOf(
                "with_crew" to "$personId",
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "50"
            ),
            "/discover/tv",
            mapOf(
                "with_crew" to "$personId",
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "10"
            ),
            page
        )
        return newHomePageResponse(name, list, hasNext = hasNext)
    }

    suspend fun actorsPage(api: MainAPI, page: Int, name: String): HomePageResponse {
        val json = PlayTmdb.get("/person/popular", mapOf("page" to "$page", "language" to "en-US"))
        val arr = json?.optJSONArray("results")
        val out = ArrayList<SearchResponse>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val person = arr.optJSONObject(i) ?: continue
                val id = person.optInt("id", 0)
                val personName = person.optString("name")
                val profile = person.optString("profile_path")
                if (id == 0 || personName.isBlank() || profile.isBlank()) continue
                out.add(
                    api.newMovieSearchResponse(personName, PlayPersonData(id).toJson(), TvType.Movie) {
                        this.posterUrl = PlayTmdb.imageUrl(profile)
                    }
                )
            }
        }
        // deep pages of the popular list get obscure fast, 400 keeps the row useful
        val total = minOf(json?.optInt("total_pages", 1) ?: 1, 400)
        val hasNext = (json?.optInt("page", 1) ?: 1) < total
        return newHomePageResponse(name, out, hasNext = hasNext)
    }

    private suspend fun topRatedBatch(
        api: MainAPI,
        path: String,
        maxPage: Int,
        fallbackType: String
    ): List<SearchResponse> {
        val json = PlayTmdb.get(
            path,
            mapOf("page" to "${Random.nextInt(1, maxPage + 1)}", "language" to "en-US")
        ) ?: return emptyList()
        val arr = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<SearchResponse>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("poster_path").isBlank()) continue
            PlayTmdb.toSearchResponse(api, item, fallbackType)?.let(out::add)
        }
        return out
    }

    suspend fun surprisePage(api: MainAPI, name: String): HomePageResponse {
        if (shownUrls.size > SHOWN_CAP) shownUrls.clear()
        val out = ArrayList<SearchResponse>(SURPRISE_BATCH + 4)
        var attempts = 0
        while (out.size < SURPRISE_BATCH && attempts < 3) {
            attempts++
            val batch = coroutineScope {
                val movies = async { topRatedBatch(api, "/movie/top_rated", 500, "movie") }
                val shows = async { topRatedBatch(api, "/tv/top_rated", 120, "tv") }
                (movies.await() + shows.await()).shuffled()
            }
            for (card in batch) {
                if (out.size >= SURPRISE_BATCH + 4) break
                if (shownUrls.add(card.url)) out.add(card)
            }
        }
        return newHomePageResponse(name, out, hasNext = true)
    }

    private fun tmdbIdOf(url: String): Int? = try {
        parseJson<PlayTmdbData>(url).id
    } catch (_: Exception) {
        null
    }

    private suspend fun freshPage(api: MainAPI, page: Int, name: String): HomePageResponse {
        val (list, hasNext) = mixedDiscover(
            api,
            "/movie/now_playing", emptyMap(),
            "/tv/on_the_air", emptyMap(),
            page
        )
        return newHomePageResponse(name, list, hasNext = hasNext)
    }

    suspend fun recommendedPage(api: MainAPI, page: Int, name: String): HomePageResponse {
        val entries = PlayHistory.read()
        if (entries.isEmpty()) return freshPage(api, page, name)
        val genres = PlayHistory.topGenres(entries, 2)
        if (genres.isEmpty()) return freshPage(api, page, name)
        val watched = entries.mapNotNull { it.id }.toHashSet()
        val genreParam = genres.joinToString(",")
        val (mixed, more) = mixedDiscover(
            api,
            "/discover/movie",
            mapOf(
                "with_genres" to genreParam,
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "300"
            ),
            "/discover/tv",
            mapOf(
                "with_genres" to genreParam,
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "100"
            ),
            page
        )
        val unseen = mixed.filterNot { watched.contains(tmdbIdOf(it.url)) }
        // a page is almost never only titles the user opened, but if it is the
        // unfiltered mix keeps the row moving instead of showing an empty shelf
        val list = if (unseen.isEmpty() && mixed.isNotEmpty()) mixed else unseen
        return newHomePageResponse(name, list, hasNext = more && page < 40)
    }

    private fun shorten(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val head = text.substring(0, limit)
        val stop = maxOf(head.lastIndexOf('.'), head.lastIndexOf('!'), head.lastIndexOf('?'))
        return if (stop >= limit / 2) head.substring(0, stop + 1).trim() else head.trim() + "..."
    }

    suspend fun personLoad(api: MainAPI, personId: Int, url: String): LoadResponse? {
        val json = PlayTmdb.get(
            "/person/$personId",
            mapOf("append_to_response" to "combined_credits", "language" to "en-US")
        ) ?: return null
        val name = json.optString("name")
        if (name.isBlank()) return null

        val works = ArrayList<SearchResponse>()
        val seenWorks = HashSet<Int>()
        json.optJSONObject("combined_credits")?.optJSONArray("cast")?.let { arr ->
            val credits = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let(credits::add)
            }
            credits.sortWith(
                compareByDescending<JSONObject> { it.optDouble("popularity", 0.0) }
                    .thenByDescending { it.optInt("vote_count", 0) }
            )
            for (credit in credits) {
                if (works.size >= 24) break
                val workId = credit.optInt("id", 0)
                if (workId == 0 || !seenWorks.add(workId)) continue
                if (credit.optString("poster_path").isBlank()) continue
                val fallback = credit.optString("media_type").ifBlank { "movie" }
                PlayTmdb.toSearchResponse(api, credit, fallback)?.let(works::add)
            }
        }

        val bio = json.optString("biography").trim()
        val plot = if (bio.isNotBlank()) {
            shorten(bio, 900)
        } else if (works.isNotEmpty()) {
            "Known for ${works.take(3).joinToString(", ") { it.name }}."
        } else {
            ""
        }
        val tags = ArrayList<String>(4)
        json.optString("known_for_department").takeIf { it.isNotBlank() }?.let(tags::add)
        works.take(3).forEach { tags.add(it.name) }

        return api.newMovieLoadResponse(name, url, TvType.Movie, PlayPersonData(personId).toJson()) {
            this.posterUrl = PlayTmdb.imageUrl(json.optString("profile_path"))
            this.plot = plot
            this.tags = tags
            this.recommendations = works
        }
    }
}
