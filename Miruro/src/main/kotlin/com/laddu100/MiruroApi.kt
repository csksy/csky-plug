package com.laddu100

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisodeCounts(
    val raw: Int? = null,
    val sub: Int? = null,
    val dub: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroExternalIds(
    val anilist: List<String>? = null,
    val mal: List<String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroAnime(
    val id: String = "",
    val title: MiruroTitle? = null,
    val format: String? = null,
    val status: String? = null,
    val season_year: Int? = null,
    val average_score: Int? = null,
    val episode_counts: MiruroEpisodeCounts? = null,
    val episode_duration_minutes: Int? = null,
    val description: String? = null,
    val cover_url: String? = null,
    val banner_url: String? = null,
    val genres: List<String>? = null,
    val external_ids: MiruroExternalIds? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroListPage(
    val data: List<MiruroAnime> = emptyList(),
    val next_cursor: String? = null,
    val has_more: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisode(
    val episode_number: Int? = null,
    val title: String? = null,
    val synopsis: String? = null,
    val thumbnail_url: String? = null,
    val aired_season_number: Int? = null,
    val kind: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisodesPage(
    val data: List<MiruroEpisode> = emptyList(),
    val has_more: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroResolution(val width: Int? = null, val height: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlayStream(
    val url: String? = null,
    val format: String? = null,
    val quality: String? = null,
    val resolution: MiruroResolution? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEmbed(val url: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlayServer(
    val server: String = "",
    val headers: Map<String, String>? = null,
    val streams: List<MiruroPlayStream> = emptyList(),
    val embed: MiruroEmbed? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlaySubtitle(
    val file: String? = null,
    val language: String? = null,
    val label: String? = null,
    val format: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlayProvider(
    val provider: String = "",
    val subtitles: List<MiruroPlaySubtitle> = emptyList(),
    val servers: List<MiruroPlayServer> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlayTrack(
    val track: String = "",
    val providers: List<MiruroPlayProvider> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlay(
    val episode_number: Int? = null,
    val tracks: List<MiruroPlayTrack> = emptyList()
)

object MiruroApi {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // the backend only accepts the page sizes its own web client sends,
    // anything else is answered with a plain 400
    private const val RAIL_LIMIT = 12
    private const val SEARCH_LIMIT = 15
    private const val EPISODES_LIMIT = 10000

    private val mapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // catalog responses arrive as binary octet-stream: xor with this key
    // then gunzip, the web client decodes the exact same way
    private val catalogKey = "miruro/catalog".toByteArray(Charsets.US_ASCII)

    private val fallbackDomains = listOf(
        "https://www.miruro.to",
        "https://www.miruro.bz",
        "https://www.miruro.tv"
    )

    private val killers = ConcurrentHashMap<String, CloudflareKiller>()

    @Volatile
    private var domainCache: Pair<Long, List<String>>? = null

    private suspend fun domains(): List<String> {
        val now = System.currentTimeMillis()
        domainCache?.let { (at, list) ->
            if (now - at < 300_000L) return list
        }
        val override = try {
            FirebaseDomainHelper.getDomain("miruro")
        } catch (e: Exception) {
            null
        }
        val list = mutableListOf<String>()
        override?.removeSuffix("/")
            ?.takeIf { it.startsWith("http") }
            ?.let { list.add(it) }
        for (domain in fallbackDomains) {
            if (domain !in list) list.add(domain)
        }
        domainCache = now to list
        return list
    }

    private fun killerFor(domain: String): CloudflareKiller =
        killers.getOrPut(domain) { CloudflareKiller() }

    private fun headersFor(domain: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Referer" to "$domain/",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private fun decodeBytes(raw: ByteArray): String {
        val head = raw.firstOrNull() ?: return ""
        if (head == '{'.code.toByte() || head == '['.code.toByte()) {
            return String(raw, Charsets.UTF_8)
        }
        val plain = ByteArray(raw.size) {
            (raw[it].toInt() xor catalogKey[it % catalogKey.size].toInt()).toByte()
        }
        if (plain.size > 2 && plain[0] == 0x1f.toByte() && plain[1] == 0x8b.toByte()) {
            return GZIPInputStream(ByteArrayInputStream(plain)).use { bytes ->
                bytes.readBytes().toString(Charsets.UTF_8)
            }
        }
        return plain.toString(Charsets.UTF_8)
    }

    private suspend fun fetch(path: String): String {
        var lastError: Exception? = null
        for (domain in domains()) {
            try {
                val res = app.get(
                    domain + path,
                    headers = headersFor(domain),
                    interceptor = killerFor(domain)
                )
                if (res.code == 200) {
                    val raw = res.body?.bytes() ?: throw Exception("empty body from $domain")
                    return decodeBytes(raw)
                }
                lastError = Exception("http ${res.code} from $domain")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        Log.d("Miruro", "catalog request failed for $path: ${lastError?.message}")
        throw lastError ?: Exception("miruro is unreachable")
    }

    suspend fun trending(): MiruroListPage =
        mapper.readValue(fetch("/api/v1/anime/trending?limit=$RAIL_LIMIT&status=RELEASING"), MiruroListPage::class.java)

    suspend fun popular(cursor: String?): MiruroListPage =
        list("/api/v1/anime?sort=-popularity&limit=$RAIL_LIMIT", cursor)

    suspend fun recent(cursor: String?): MiruroListPage =
        list("/api/v1/anime?sort=-updated_at&limit=$RAIL_LIMIT", cursor)

    suspend fun movies(cursor: String?): MiruroListPage =
        list("/api/v1/anime?format=MOVIE&sort=-score&limit=$RAIL_LIMIT", cursor)

    suspend fun topRated(cursor: String?): MiruroListPage =
        list("/api/v1/anime?sort=-score&limit=$RAIL_LIMIT", cursor)

    suspend fun search(query: String): MiruroListPage =
        list("/api/v1/anime?q=$query&limit=$SEARCH_LIMIT", null)

    private suspend fun list(path: String, cursor: String?): MiruroListPage {
        val sep = if ("?" in path) "&" else "?"
        val full = if (cursor != null) "$path${sep}cursor=$cursor" else path
        return mapper.readValue(fetch(full), MiruroListPage::class.java)
    }

    suspend fun detail(id: String): MiruroAnime =
        mapper.readValue(fetch("/api/v1/anime/$id"), MiruroAnime::class.java)

    suspend fun episodes(id: String): List<MiruroEpisode> =
        mapper.readValue(
            fetch("/api/v1/anime/$id/episodes?limit=$EPISODES_LIMIT"),
            MiruroEpisodesPage::class.java
        ).data

    suspend fun play(id: String, episode: Int): MiruroPlay =
        mapper.readValue(
            fetch("/api/v1/anime/$id/episodes/$episode/play"),
            MiruroPlay::class.java
        )
}
