package com.just4anime.plugin

import android.net.Uri
import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin client for just4anime.online (Next.js frontend API)
 * and api.just4anime.online (Anify-style meta/source API).
 *
 * All stream/subtitle URLs returned by the sources endpoint are already
 * routed through the site's CORS proxy (cors.just4anime.online), which is
 * what keeps referer-locked upstream CDNs playable.
 */
object Just4AnimeApi {

    const val MAIN_URL = "https://just4anime.online"
    const val API_URL = "https://api.just4anime.online"
    const val PROVIDER_NAME = "just4anime"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val TAG = "Just4AnimeApi"
    private val gson = Gson()

    private val baseHeaders: Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$MAIN_URL/",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    /** Availability discovery is expensive server-side; cache it per show for 10 minutes. */
    private data class CachedServers(val servers: List<J4AServer>, val at: Long)

    @Volatile
    private var availabilityCache = ConcurrentHashMap<String, CachedServers>()

    private suspend inline fun <reified T> fetch(url: String, timeout: Long = 30): T? =
        withContext(Dispatchers.IO) {
            try {
                val response = app.get(url, headers = baseHeaders, timeout = timeout)
                val body = response.text
                if (body.isBlank()) return@withContext null
                gson.fromJson(body, T::class.java)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "GET failed: $url -> ${e.message}")
                null
            }
        }

    /** advanced-search with AniList-style sort; empty query browses the catalog. */
    suspend fun search(query: String, page: Int, sort: String, perPage: Int = 30): J4ASearchData? {
        val url = buildString {
            append(MAIN_URL)
            append("/api/advanced-search?query=")
            append(Uri.encode(query))
            append("&page=").append(page)
            append("&perPage=").append(perPage)
            append("&sort=")
            append(Uri.encode("[\"$sort\"]"))
        }
        return fetch<J4ASearchResponse>(url)?.data
    }

    /** Full show meta + real TMDB episode titles for an AniList id. */
    suspend fun episodes(anilistId: String): J4AEpisodesData? {
        val url = "$MAIN_URL/api/episodes/${Uri.encode(anilistId)}"
        return fetch<J4AEpisodesResponse>(url)?.data
    }

    /**
     * Which providers carry this show. May legitimately return fewer providers
     * than the site UI lists (discovery is time-boxed server-side); the plugin
     * blind-probes the remaining ones, so nothing is lost.
     */
    suspend fun availability(anilistId: String): List<J4AServer> {
        val cached = availabilityCache[anilistId]
        if (cached != null && System.currentTimeMillis() - cached.at < 10 * 60_000L) {
            return cached.servers
        }
        val url = "$API_URL/api/v1/meta/availability/${Uri.encode(anilistId)}/servers"
        val servers = try {
            fetch<J4AAvailabilityResponse>(url, timeout = 30)?.data?.servers ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "availability failed: ${e.message}")
            emptyList()
        }
        availabilityCache[anilistId] = CachedServers(servers, System.currentTimeMillis())
        return servers
    }

    /**
     * Direct stream sources for one provider + episode + audio type.
     * providerAnimeId is optional - the backend resolves the provider's own
     * anime id itself when omitted (slower, but works for providers that
     * availability discovery missed).
     */
    suspend fun sources(
        anilistId: String,
        provider: String,
        num: Int,
        type: String,
        providerAnimeId: String? = null
    ): J4ASourcesData? {
        val url = buildString {
            append(API_URL)
            append("/api/v1/meta/sources/")
            append(Uri.encode(anilistId))
            append("?provider=").append(Uri.encode(provider))
            append("&num=").append(num)
            append("&type=").append(Uri.encode(type))
            if (!providerAnimeId.isNullOrBlank()) {
                append("&providerAnimeId=").append(Uri.encode(providerAnimeId))
            }
        }
        return fetch<J4ASourcesResponse>(url, timeout = 30)?.data
    }
}
