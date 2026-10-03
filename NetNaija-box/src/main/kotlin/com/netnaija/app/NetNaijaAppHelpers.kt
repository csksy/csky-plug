package com.netnaija.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// first match wins so the ladder is ordered from the highest label down
fun getHighestQuality(input: String): Int? {
    val qualities = listOf(
        "2160" to Qualities.P2160.value,
        "1440" to Qualities.P1440.value,
        "1080" to Qualities.P1080.value,
        "720" to Qualities.P720.value,
        "480" to Qualities.P480.value,
        "360" to Qualities.P360.value,
        "240" to Qualities.P240.value,
    )
    for ((label, mapped) in qualities) {
        if (input.contains(label, ignoreCase = true)) return mapped
    }
    return null
}

private fun cleanTitle(s: String): String {
    return Regex("\\s+").replace(Regex("[^a-z0-9 ]").replace(s.lowercase(Locale.ROOT), " "), " ").trim()
}

// match when three quarters of the smaller token set is shared, tolerates
// release tag suffixes
private fun tokenEquals(a: String, b: String): Boolean {
    val sa = Regex("\\s+").split(a).filter { it.isNotBlank() }.toSet()
    val sb = Regex("\\s+").split(b).filter { it.isNotBlank() }.toSet()
    if (sa.isEmpty() || sb.isEmpty()) return false
    val inter = sa.intersect(sb).size
    return inter >= max(1, (min(sa.size, sb.size) * 3) / 4)
}

// strip bracketed extras and dub words so "Movie (2024) Hindi dub" resolves
// to the plain "movie" entry
private fun normalize(s: String): String {
    val lowered = Regex("(?i)\\b(dub|dubbed|hd|4k|hindi|tamil|telugu|dual audio)\\b")
        .replace(Regex("\\(.*?\\)").replace(Regex("\\[.*?]").replace(s, " "), " "), " ")
        .trim()
        .lowercase(Locale.ROOT)
    return Regex("\\s+").replace(Regex("\\p{Punct}").replace(lowered.replace(":", " "), " "), " ")
}

private suspend fun searchAndPickDoSearch(endpoint: String, extraParams: String = ""): JSONArray? {
    return try {
        val url = buildString {
            append("https://api.themoviedb.org/3/")
            append(endpoint)
            append("?api_key=")
            append("1865f43a0549ca50d341dd9ab8b29f49")
            append(extraParams)
            append("&include_adult=false&page=1")
        }
        val text = app.get(url).text
        JSONObject(text).optJSONArray("results")
    } catch (e: Exception) {
        null
    }
}

private suspend fun searchAndPick(
    normTitle: String,
    year: Int?,
    imdbRatingValue: Double?,
    isIndian: Boolean,
    expectedType: TvType
): Pair<Int?, String?> {
    val extraYearParam = if (year != null) "&year=$year" else ""
    val extraTvYearParam = if (year != null) "&first_air_date_year=$year" else ""
    val searchQueues: List<Pair<String, JSONArray?>> = if (expectedType != TvType.TvSeries) {
        listOf(
            "movie" to searchAndPickDoSearch("search/movie", "&query=$normTitle$extraYearParam"),
            "multi" to searchAndPickDoSearch("search/multi", "&query=$normTitle$extraYearParam"),
            "tv" to searchAndPickDoSearch("search/tv", "&query=$normTitle$extraTvYearParam"),
        )
    } else {
        listOf(
            "tv" to searchAndPickDoSearch("search/tv", "&query=$normTitle$extraTvYearParam"),
            "multi" to searchAndPickDoSearch("search/multi", "&query=$normTitle$extraYearParam"),
            "movie" to searchAndPickDoSearch("search/movie", "&query=$normTitle$extraYearParam"),
        )
    }

    var bestId: Int? = null
    var bestIsTv = false
    var bestScore = -1.0
    for ((kind, results) in searchQueues) {
        if (results == null) continue
        for (i in 0 until results.length()) {
            try {
                val obj = results.getJSONObject(i)
                val mediaType = when {
                    kind == "multi" -> obj.optString("media_type", "")
                    kind == "tv" -> "tv"
                    else -> "movie"
                }
                val id = obj.optInt("id", -1)
                if (id == -1) continue
                val names = listOf(
                    obj.optString("title"),
                    obj.optString("name"),
                    obj.optString("original_title"),
                    obj.optString("original_name")
                ).filter { it.isNotBlank() }
                val dateStr = if (mediaType == "tv") obj.optString("first_air_date", "") else obj.optString("release_date", "")
                val foundYear = dateStr.take(4).toIntOrNull()
                val vote = obj.optDouble("vote_average", Double.NaN)
                val lang = obj.optString("original_language", "")
                val cleanNorm = cleanTitle(normTitle)
                var score = 0.0
                for (n in names) {
                    val cleanN = cleanTitle(n)
                    if (tokenEquals(cleanN, cleanNorm)) {
                        score = 50.0
                        break
                    } else if (cleanN.contains(cleanNorm) || cleanNorm.contains(cleanN)) {
                        score = max(score, 20.0)
                    }
                }
                if (isIndian) {
                    if (lang in listOf("hi", "ta", "te", "ml", "kn", "pa", "bn", "mr", "gu")) score += 30.0
                    else if (lang == "en") score -= 20.0
                }
                if (year != null && foundYear != null) {
                    val diff = abs(foundYear - year)
                    when {
                        diff == 0 -> score += 40.0
                        diff == 1 -> score += 20.0
                        diff >= 4 -> score -= 40.0
                        diff >= 2 -> score -= 15.0
                    }
                }
                if (expectedType == TvType.TvSeries && mediaType == "tv") score += 15.0
                if (expectedType == TvType.Movie && mediaType == "movie") score += 15.0
                if (imdbRatingValue != null && !vote.isNaN()) {
                    val diff = abs(vote - imdbRatingValue)
                    if (diff <= 0.5) score += 10.0
                    else if (diff <= 1.0) score += 5.0
                }
                if (obj.has("popularity")) score += min(obj.optDouble("popularity", 0.0) / 100.0, 5.0)
                if (score > bestScore) {
                    bestScore = score
                    bestId = id
                    bestIsTv = mediaType == "tv"
                }
            } catch (e: JSONException) {
                continue
            }
        }
    }
    if (bestId == null || bestScore < 40.0) return null to null
    val detailKind = if (bestIsTv) "tv" else "movie"
    val detailUrl = "https://api.themoviedb.org/3/$detailKind/$bestId?api_key=1865f43a0549ca50d341dd9ab8b29f49&append_to_response=external_ids"
    return try {
        val detail = app.get(detailUrl)
        val imdbId = JSONObject(detail.text).optJSONObject("external_ids")?.optString("imdb_id")
        bestId to imdbId
    } catch (e: Exception) {
        null to null
    }
}

// resolves the site title to tmdb/imdb ids for artwork, cast and ratings
suspend fun identifyID(
    title: String,
    year: Int? = null,
    imdbRatingValue: Double? = null,
    isIndian: Boolean = false,
    expectedType: TvType = TvType.Movie
): Pair<Int?, String?> {
    val normTitle = normalize(title)
    val res = searchAndPick(normTitle, year, imdbRatingValue, isIndian, expectedType)
    return if (res.first != null) res else null to null
}

suspend fun fetchMetaData(imdbId: String?, type: TvType): JsonNode? {
    if (imdbId.isNullOrBlank()) return null
    val metaType = if (type == TvType.TvSeries) "series" else "movie"
    val url = "https://v3-cinemeta.strem.io/meta/$metaType/$imdbId.json"
    return try {
        val resp = app.get(url)
        jacksonObjectMapper().readTree(resp.text).get("meta")
    } catch (e: Exception) {
        null
    }
}

private fun logoPath(o: JSONObject): String = o.optString("file_path")
private fun logoIsSvg(o: JSONObject): Boolean = logoPath(o).endsWith(".svg", ignoreCase = true)
private fun logoUrlOf(o: JSONObject): String = "https://image.tmdb.org/t/p/w500" + logoPath(o)
private fun logoVoted(o: JSONObject): Boolean =
    o.optDouble("vote_average", 0.0) > 0.0 && o.optInt("vote_count", 0) > 0

private fun logoBetter(a: JSONObject?, b: JSONObject): Boolean {
    if (a == null) return true
    val aAvg = a.optDouble("vote_average", 0.0)
    val aCnt = a.optInt("vote_count", 0)
    val bAvg = b.optDouble("vote_average", 0.0)
    val bCnt = b.optInt("vote_count", 0)
    if (bAvg > aAvg) return true
    return bAvg == aAvg && bCnt > aCnt
}

// app language first, then any language svg, then the highest voted artwork
suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {
    if (tmdbId == null) return null
    val url = if (type == TvType.Movie) {
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    } else {
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"
    }
    val json = try {
        JSONObject(app.get(url).text)
    } catch (e: Exception) {
        null
    } ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null
    val lang = appLangCode?.trim()?.lowercase(Locale.ROOT)
    var svgFallback: JSONObject? = null
    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = logoPath(logo)
        if (p.isBlank()) continue
        val l = logo.optString("iso_639_1").trim().lowercase(Locale.ROOT)
        if (l != lang) continue
        if (!logoIsSvg(logo)) return logoUrlOf(logo)
        if (svgFallback == null) svgFallback = logo
    }
    if (svgFallback != null) return logoUrlOf(svgFallback)
    var best: JSONObject? = null
    var bestSvg: JSONObject? = null
    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!logoVoted(logo)) continue
        if (logoIsSvg(logo)) {
            if (logoBetter(bestSvg, logo)) bestSvg = logo
        } else if (logoBetter(best, logo)) {
            best = logo
        }
    }
    if (best != null) return logoUrlOf(best)
    if (bestSvg != null) return logoUrlOf(bestSvg)
    return null
}

data class SportPlaySource(
    val title: String? = null,
    val path: String? = null
)

data class SportClip(
    val title: String? = null,
    val path: String? = null,
    val duration: Long? = null,
    val coverUrl: String? = null
)

data class SportVideoData(
    val title: String,
    val url: String,
    val poster: String? = null
)

data class SportMatchData(
    val id: String,
    val title: String,
    val league: String? = null,
    val status: String? = null,
    val playPath: String? = null,
    val playSource: List<SportPlaySource>? = null,
    val highlights: List<SportClip>? = null,
    val replay: List<SportClip>? = null,
    val poster: String? = null
)
