package com.laddu100.bingr

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder

// =========================================================================
// Bingr API client
// =========================================================================
// All endpoints are at https://api.bingr.one/api and require NO authentication.
// Verified working endpoints (tested with curl before implementation):
//   GET  /trending/{type}?page={n}
//   GET  /search?q={query}&type={type}
//   GET  /details/{type}/{id}
//   POST /stream  {srv, t, id, query}
//   GET  /languages/{type}/{id}?title={t}&year={y}
//   GET  /subtitles/vdrk/{type}/{id}?season={s}&ep={e}
//   GET  /episodes/{id}/{season}
//   GET  /anime/trending?page={n}
//   GET  /anime/search?q={q}&page={n}
//   GET  /anime/{id}

object BingrApi {
    private const val TAG = "Bingr_API"
    private const val BASE = "https://api.bingr.one/api"
    private const val HIANIME_BASE = "https://hianime.filmu.in"

    // The 8 Server 1 sources. Tried in parallel in loadLinks. Only ~3 will return
    // sources for any given title (varies by title and geo), but trying all 8 ensures
    // we never miss an available source.
    val SERVER_IDS = listOf("s1", "s2", "s3", "s4", "s5", "s10", "s11", "s12")
    val SERVER_NAMES = mapOf(
        "s1" to "Miller", "s2" to "Mann", "s3" to "Edmunds", "s4" to "Luna",
        "s5" to "Aditya", "s10" to "Elysium", "s11" to "Sirius", "s12" to "Quasar"
    )

    // ---- Trending / Discover ----

    suspend fun trending(type: String, page: Int = 1): BingrSearchResponse {
        return try {
            val json = app.get("$BASE/trending/$type?page=$page", timeout = 15_000L).text
            parseJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "trending($type): ${e.message}")
            BingrSearchResponse()
        }
    }

    suspend fun discover(path: String): BingrSearchResponse {
        return try {
            val json = app.get("$BASE$path", timeout = 15_000L).text
            parseJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "discover($path): ${e.message}")
            BingrSearchResponse()
        }
    }

    // ---- Search ----

    suspend fun search(query: String, type: String = "multi"): BingrSearchResponse {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val json = app.get("$BASE/search?q=$encoded&type=$type", timeout = 15_000L).text
            parseJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
            BingrSearchResponse()
        }
    }

    // ---- Details ----

    suspend fun getDetails(type: String, id: Long): BingrDetail? {
        return try {
            val json = app.get("$BASE/details/$type/$id", timeout = 15_000L).text
            parseJson<BingrDetail>(json)
        } catch (e: Exception) {
            Log.e(TAG, "getDetails($type/$id): ${e.message}")
            null
        }
    }

    suspend fun getEpisodes(tmdbId: Long, season: Int): List<BingrEpisode> {
        return try {
            val json = app.get("$BASE/episodes/$tmdbId/$season", timeout = 15_000L).text
            parseJson<BingrEpisodesResponse>(json).episodes
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodes: ${e.message}")
            emptyList()
        }
    }

    // ---- Streaming ----

    // POST /stream — the core endpoint. Tries one server and returns sources if available.
    // Called in parallel for all 8 servers from loadLinks.
    suspend fun getStream(
        srv: String,
        type: String,
        id: Long,
        title: String,
        year: String? = null,
        season: Int? = null,
        episode: Int? = null
    ): BingrStreamResponse? {
        return try {
            val query = buildString {
                append("{\"title\":")
                append(jsonEncode(title))
                if (year != null) {
                    append(",\"year\":")
                    append(jsonEncode(year))
                }
                if (type == "tv" && season != null && episode != null) {
                    append(",\"season\":$season")
                    append(",\"episode\":$episode")
                }
                if (type == "anime" && episode != null) {
                    append(",\"episode\":$episode")
                }
                append("}")
            }
            val body = """{"srv":"$srv","t":"$type","id":"$id","query":$query}"""
            val response = app.post("$BASE/stream", json = body, headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            ), timeout = 20_000L)
            if (!response.isSuccessful) return null
            parseJson<BingrStreamResponse>(response.text)
        } catch (e: Exception) {
            Log.e(TAG, "getStream($srv,$type,$id): ${e.message}")
            null
        }
    }

    // Multi-audio MP4 sources from /languages endpoint.
    suspend fun getLanguages(type: String, id: Long, title: String, year: String? = null, season: Int? = null, episode: Int? = null): List<BingrLanguageSource> {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val url = buildString {
                append("$BASE/languages/$type/$id?title=$encodedTitle")
                if (year != null) append("&year=$year")
                if (type == "tv" && season != null) append("&season=$season")
                if (type == "tv" && episode != null) append("&episode=$episode")
            }
            val json = app.get(url, timeout = 15_000L).text
            parseJson<BingrLanguagesResponse>(json).sources
        } catch (e: Exception) {
            Log.e(TAG, "getLanguages: ${e.message}")
            emptyList()
        }
    }

    // VTT subtitles from /subtitles/vdrk endpoint.
    suspend fun getSubtitles(type: String, id: Long, season: Int? = null, episode: Int? = null): List<BingrSubtitle> {
        return try {
            val url = if (type == "tv" && season != null && episode != null) {
                "$BASE/subtitles/vdrk/$type/$id?season=$season&ep=$episode"
            } else {
                "$BASE/subtitles/vdrk/$type/$id"
            }
            val json = app.get(url, timeout = 15_000L).text
            parseJson<List<BingrSubtitle>>(json)
        } catch (e: Exception) {
            Log.e(TAG, "getSubtitles: ${e.message}")
            emptyList()
        }
    }

    // ---- Anime ----

    suspend fun animeTrending(page: Int = 1): BingrSearchResponse {
        return try {
            val json = app.get("$BASE/anime/trending?page=$page", timeout = 15_000L).text
            parseJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "animeTrending: ${e.message}")
            BingrSearchResponse()
        }
    }

    suspend fun animeSearch(query: String, page: Int = 1): BingrSearchResponse {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val json = app.get("$BASE/anime/search?q=$encoded&page=$page", timeout = 15_000L).text
            parseJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "animeSearch: ${e.message}")
            BingrSearchResponse()
        }
    }

    suspend fun animeDiscover(params: String): BingrSearchResponse {
        return try {
            val json = app.get("$BASE/anime/discover?$params", timeout = 15_000L).text
            parseJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "animeDiscover: ${e.message}")
            BingrSearchResponse()
        }
    }

    suspend fun getAnimeDetails(anilistId: Long): BingrAnimeDetail? {
        return try {
            val json = app.get("$BASE/anime/$anilistId", timeout = 15_000L).text
            parseJson<BingrAnimeDetail>(json)
        } catch (e: Exception) {
            Log.e(TAG, "getAnimeDetails: ${e.message}")
            null
        }
    }

    // ---- Hianime.filmu.in (AnimeSalt + Hikari) ----

    @Volatile
    private var cachedToken: String? = null
    @Volatile
    private var tokenExpiry: Long = 0L

    // JWT token valid 2.5 hours. Cache and reuse to avoid redundant /token calls.
    private suspend fun getHianimeToken(): String? {
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (cached != null && now < tokenExpiry) return cached
        return try {
            val response = app.post("$HIANIME_BASE/token", timeout = 10_000L)
            if (!response.isSuccessful) return null
            val tokenResp = parseJson<HianimeTokenResponse>(response.text)
            val token = tokenResp.token
            if (token != null) {
                cachedToken = token
                tokenExpiry = now + (2.5 * 60 * 60 * 1000).toLong()
                token
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "getHianimeToken: ${e.message}")
            null
        }
    }

    // AnimeSalt: returns MULTI-audio HLS streams.
    suspend fun getAnimeSaltStreams(title: String, episode: Int): BingrStreamResponse? {
        return try {
            val token = getHianimeToken() ?: return null
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val url = "$HIANIME_BASE/animesalt/streams?title=$encodedTitle&ep=$episode&season=1"
            val response = app.get(url, headers = mapOf("x-api-key" to token), timeout = 20_000L)
            if (!response.isSuccessful) return null
            // Convert hianime response format to BingrStreamResponse format
            val hianime = parseJson<HianimeStreamResponse>(response.text)
            val sources = hianime.streams.map { stream ->
                val streamUrl = stream.proxyUrl ?: stream.url
                val resolvedUrl = if (streamUrl.startsWith("/")) "$HIANIME_BASE$streamUrl" else streamUrl
                val apiUrl = if (token.isNotEmpty()) {
                    if (resolvedUrl.contains("?")) "$resolvedUrl&apiKey=$token" else "$resolvedUrl?apiKey=$token"
                } else resolvedUrl
                BingrSource(
                    url = apiUrl,
                    quality = if (stream.quality != null) "${stream.quality}p" else "HD",
                    language = stream.dubType ?: "MULTI",
                    type = if (stream.type == "video" || apiUrl.contains(".mp4")) "video/mp4" else "application/x-mpegurl",
                    label = "AnimeSalt | ${stream.dubType ?: "MULTI"} — ${stream.quality ?: "HD"}p",
                    name = "AnimeSalt | ${stream.dubType ?: "MULTI"}",
                    isMP4 = stream.type == "video" || apiUrl.contains(".mp4")
                )
            }
            val subs = hianime.streams.firstOrNull()?.subtitles?.map { sub ->
                val subUrl = if (sub.url.startsWith("/")) "$HIANIME_BASE${sub.url}" else sub.url
                BingrSubtitle(lang = sub.lang, label = sub.label, url = subUrl)
            } ?: emptyList()
            BingrStreamResponse(scraperName = "AnimeSalt", sources = sources, subtitles = subs)
        } catch (e: Exception) {
            Log.e(TAG, "getAnimeSaltStreams: ${e.message}")
            null
        }
    }

    // Hikari: returns sub or dub streams via megaplay endpoint.
    suspend fun getHikariStreams(malId: Long, episode: Int, audioMode: String): BingrStreamResponse? {
        return try {
            val token = getHianimeToken() ?: return null
            val url = "$HIANIME_BASE/hianime/megaplay?malId=$malId&ep=$episode&type=$audioMode"
            val response = app.get(url, headers = mapOf("x-api-key" to token), timeout = 20_000L)
            if (!response.isSuccessful) return null
            val hianime = parseJson<HianimeStreamResponse>(response.text)
            val modeLabel = if (audioMode == "dub") "Dub" else "Sub"
            val sources = hianime.streams.map { stream ->
                val streamUrl = stream.proxyUrl ?: stream.url
                val resolvedUrl = if (streamUrl.startsWith("/")) "$HIANIME_BASE$streamUrl" else streamUrl
                val apiUrl = if (token.isNotEmpty()) {
                    if (resolvedUrl.contains("?")) "$resolvedUrl&apiKey=$token" else "$resolvedUrl?apiKey=$token"
                } else resolvedUrl
                val serverName = stream.server?.replace(Regex("^HiAnime\\s*\\["), "")?.replace(Regex("\\]$"), "") ?: "MegaPlay"
                BingrSource(
                    url = apiUrl,
                    quality = stream.quality ?: "HD",
                    language = modeLabel,
                    type = when {
                        stream.type == "dash" || apiUrl.contains(".mpd") -> "application/dash+xml"
                        stream.type == "video" || apiUrl.contains(".mp4") -> "video/mp4"
                        else -> "application/x-mpegurl"
                    },
                    label = "Hikari $modeLabel | $serverName — ${stream.quality ?: "HD"}",
                    name = "Hikari $modeLabel | $serverName",
                    isMP4 = stream.type == "video" || apiUrl.contains(".mp4")
                )
            }
            val subs = hianime.streams.firstOrNull()?.subtitles?.map { sub ->
                val subUrl = if (sub.url.startsWith("/")) "$HIANIME_BASE${sub.url}" else sub.url
                BingrSubtitle(lang = sub.lang, label = sub.label, url = subUrl)
            } ?: emptyList()
            BingrStreamResponse(scraperName = "Hikari $modeLabel", sources = sources, subtitles = subs)
        } catch (e: Exception) {
            Log.e(TAG, "getHikariStreams: ${e.message}")
            null
        }
    }

    // Helper: JSON-encode a string value (with quotes and escaping).
    private fun jsonEncode(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 32) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
