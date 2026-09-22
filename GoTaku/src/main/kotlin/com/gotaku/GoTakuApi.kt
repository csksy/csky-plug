package com.gotaku

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.delay

object GoTakuApi {

    private const val TAG = "GoTaku"
    const val SITE = "https://gotaku.to"
    private const val API = "$SITE/api/v1"

    // the video cdn rejects anything that does not look like a real browser
    val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "sec-ch-ua" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\""
    )

    private fun siteHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val headers = browserHeaders.toMutableMap()
        headers["Referer"] = "$SITE/"
        headers["Sec-Fetch-Site"] = "same-origin"
        headers["Accept"] = "application/json"
        headers.putAll(extra)
        return headers
    }

    // sealed endpoints answer with a 426 when the edge gets moody, one short
    // retry clears it
    private suspend fun getBody(url: String, headers: Map<String, String>, attempts: Int = 3): ByteArray? {
        repeat(attempts) { attempt ->
            try {
                val response = app.get(url, headers = headers)
                if (response.isSuccessful) {
                    return response.body.bytes()
                }
                if (response.code != 426) {
                    Log.d(TAG, "request $url answered ${response.code}")
                    return null
                }
            } catch (e: Exception) {
                Log.d(TAG, "request failed: ${e.message}")
            }
            delay(700L + attempt * 500L)
        }
        return null
    }

    suspend fun <T> fetchParsed(url: String, k: Boolean = false): T? {
        val fullUrl = if (k) "$url${if (url.contains('?')) '&' else '?'}k=1" else url
        val body = getBody(fullUrl, siteHeaders()) ?: return null
        val plain = GoTakuCrypto.unseal(body) ?: body
        return try {
            parseJson(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.d(TAG, "response parse failed for $url: ${e.message}")
            null
        }
    }

    class ManifestInfo(
        val title: String?,
        val source: String,
        val token: String,
        val expiresAt: Long,
        val keySeed: ByteArray,
        val stamp: String,
        val segmentBytes: Int
    )

    suspend fun fetchManifest(base: String, stamp: String): ManifestInfo? {
        val nonce = GoTakuCrypto.newNonce()
        val path = GoTakuCrypto.buildManifestPath(nonce, stamp)
        val body = getBody("$base/$path", siteHeaders(mapOf("Accept" to "text/plain"))) ?: return null
        val plain = GoTakuCrypto.openManifestResponse(nonce, String(body, Charsets.UTF_8)) ?: return null
        return try {
            val node = parseJson<ManifestResponse>(String(plain, Charsets.UTF_8))
            val source = node.source ?: return null
            val token = node.token ?: return null
            val seedText = node.keySeed ?: return null
            val seed = android.util.Base64.decode(
                seedText.replace('-', '+').replace('_', '/')
                    .let { it + "=".repeat((4 - it.length % 4) % 4) },
                android.util.Base64.DEFAULT
            )
            ManifestInfo(
                title = node.title,
                source = source,
                token = token,
                expiresAt = node.expiresAt,
                keySeed = seed,
                stamp = node.stamp ?: stamp,
                segmentBytes = node.obf?.segmentBytes ?: 0
            )
        } catch (e: Exception) {
            Log.d(TAG, "manifest parse failed: ${e.message}")
            null
        }
    }

    class ManifestResponse(
        val title: String? = null,
        val source: String? = null,
        val token: String? = null,
        val expiresAt: Long = 0,
        val keySeed: String? = null,
        val stamp: String? = null,
        val obf: Obf? = null
    ) {
        class Obf(val segmentBytes: Int = 0, val version: Int = 0)
    }

    class EmbedData(
        val url: String? = null,
        val skip: Map<String, List<Int>>? = null
    )

    class EmbedResponse(val data: EmbedData? = null)

    class EpisodeInfo(
        val id: String? = null,
        val number: Int? = null,
        val label: String? = null,
        val hard_sub: Boolean? = null,
        val soft_sub: Boolean? = null,
        val dub: Boolean? = null
    ) {
        fun trackAvailable(type: String): Boolean? = when (type) {
            "soft_sub" -> soft_sub
            "hard_sub" -> hard_sub
            "dub" -> dub
            else -> null
        }
    }

    class EpisodeInfoResponse(val data: EpisodeInfo? = null)

    class EpisodeEntry(
        val id: String? = null,
        val number: Int? = null,
        val label: String? = null,
        val name: String? = null,
        val thumbnail_url: String? = null,
        val aired_at: String? = null,
        val is_filler: Boolean? = null,
        val sub: Boolean? = null,
        val dub: Boolean? = null
    )

    class EpisodesResponse(val data: List<EpisodeEntry>? = null, val meta: Meta? = null) {
        class Meta(val next_airing: NextAiring? = null)
        class NextAiring(val number: Int? = null, val airs_at: String? = null)
    }

    class TitleEntry(
        val id: String? = null,
        val url: String? = null,
        val watch_url: String? = null,
        val name: String? = null,
        val poster_url: String? = null,
        val backdrop_url: String? = null,
        val synopsis: String? = null,
        val format: String? = null,
        val age_rating: String? = null,
        val year: Int? = null,
        val is_adult: Boolean? = null,
        val episodes: EpisodeCounts? = null,
        val status: String? = null,
        val season: String? = null,
        val duration_minutes: Int? = null,
        val genres: List<Genre>? = null
    ) {
        class EpisodeCounts(val latest_sub: Int? = null, val latest_dub: Int? = null, val total: Int? = null)
        class Genre(val id: Int? = null, val name: String? = null)
    }

    class TitlesResponse(
        val data: List<TitleEntry>? = null,
        val meta: Meta? = null
    ) {
        class Meta(val page: Int? = null, val limit: Int? = null, val has_more: Boolean? = null, val total: Int? = null)
    }

    class TitleDetailResponse(val data: TitleData? = null) {
        class TitleData(val title: TitleEntry? = null)
    }

    suspend fun fetchEmbed(episodeId: String, type: String): String? {
        val parsed: EmbedResponse? = fetchParsed("$API/episodes/$episodeId/embed?type=$type", k = true)
        return parsed?.data?.url?.takeIf { it.isNotBlank() }
    }

    suspend fun fetchEpisodeInfo(episodeId: String): EpisodeInfo? {
        val parsed: EpisodeInfoResponse? = fetchParsed("$API/episodes/$episodeId", k = true)
        return parsed?.data
    }

    suspend fun fetchEpisodes(titleId: String): List<EpisodeEntry> {
        val parsed: EpisodesResponse? = fetchParsed("$API/titles/$titleId/episodes", k = true)
        return parsed?.data.orEmpty()
    }

    suspend fun fetchTitleDetail(titleId: String): TitleEntry? {
        val parsed: TitleDetailResponse? = fetchParsed("$API/titles/$titleId")
        return parsed?.data?.title
    }

    suspend fun fetchTitles(params: Map<String, String>): Pair<List<TitleEntry>, Boolean> {
        val query = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val parsed: TitlesResponse? = fetchParsed("$API/titles?$query")
        return Pair(parsed?.data.orEmpty(), parsed?.meta?.has_more == true)
    }
}
