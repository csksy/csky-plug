package com.csksy.animex

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

object AnimeXApi {

    private const val TAG = "AnimeX"
    const val SITE = "https://animex.one"
    private const val GRAPHQL = "https://graphql.animex.one/graphql"
    private const val RECENT = "https://graphql.animex.one/api/recent"
    private const val PP = "https://pp.animex.one/rest/api"
    private const val PLYR = "https://plyr.animex.one"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val BASE_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    private const val CATALOG_QUERY = """
query CatalogAnime(${'$'}filter: AnimeCatalogFilterInput, ${'$'}sort: [AnimeSortInput!], ${'$'}limit: Int, ${'$'}offset: Int) {
  catalogAnime(filter: ${'$'}filter, sort: ${'$'}sort, limit: ${'$'}limit, offset: ${'$'}offset) {
    items {
      id anilistId malId titleRomaji titleEnglish coverImage bannerImage
      description status format averageScore seasonYear season genres
      subCount dubCount episodeCount
    }
    totalCount hasNextPage
  }
}
"""

    private const val ANIME_QUERY = """
query {
  anime(id: "${'$'}id") {
    id anilistId malId titleRomaji titleEnglish coverImage bannerImage
    description status format averageScore seasonYear season genres
    subCount dubCount episodeCount
  }
}
"""

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    // a raw T::class.java read loses generic arguments to erasure and hands
    // back LinkedHashMaps where the caller expects typed models, the typed
    // reference keeps them
    private inline fun <reified T> parse(text: String): T? =
        try {
            mapper.readValue(text, object : TypeReference<T>() {})
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${e.message}")
            null
        }

    // a single timeout used to kill the whole title load, the same call a
    // moment later answers fine, so the transport hiccup is retried before
    // the load is allowed to fail
    private suspend fun postGraphQL(query: String, variables: Map<String, Any?>): String? {
        val body = mapper.writeValueAsString(mapOf("query" to query, "variables" to variables))
        var lastReason: String? = null
        repeat(3) { attempt ->
            try {
                val resp = app.post(
                    GRAPHQL,
                    headers = BASE_HEADERS + mapOf(
                        "Content-Type" to "application/json",
                        "Origin" to SITE,
                        "Referer" to "$SITE/"
                    ),
                    requestBody = body.toRequestBody("application/json".toMediaType())
                )
                if (resp.isSuccessful) return resp.text
                lastReason = "status ${resp.code}"
            } catch (e: Exception) {
                lastReason = e.message
            }
            if (attempt < 2) delay(1500L)
        }
        Log.e(TAG, "graphql failed: $lastReason")
        return null
    }

    private suspend fun catalog(
        filter: Map<String, Any?>,
        sortField: String,
        offset: Int,
        limit: Int = 30
    ): Pair<List<CatalogItem>, Boolean> {
        val body = postGraphQL(
            CATALOG_QUERY,
            mapOf(
                "filter" to filter,
                "sort" to listOf(mapOf("field" to sortField, "direction" to "DESC")),
                "limit" to limit,
                "offset" to offset
            )
        ) ?: return emptyList<CatalogItem>() to false
        val page = parse<CatalogEnvelope>(body)?.data?.catalogAnime ?: return emptyList<CatalogItem>() to false
        return page.items.filter { it.id != null } to (page.hasNextPage == true)
    }

    suspend fun trending(page: Int): Pair<List<CatalogItem>, Boolean> =
        catalog(emptyMap(), "TRENDING", (page - 1) * 30)

    suspend fun popular(page: Int): Pair<List<CatalogItem>, Boolean> =
        catalog(emptyMap(), "POPULARITY", (page - 1) * 30)

    suspend fun movies(page: Int): Pair<List<CatalogItem>, Boolean> =
        catalog(mapOf("formatIn" to listOf("MOVIE")), "POPULARITY", (page - 1) * 30)

    suspend fun airing(page: Int): Pair<List<CatalogItem>, Boolean> =
        catalog(mapOf("statusIn" to listOf("RELEASING")), "POPULARITY", (page - 1) * 30)

    suspend fun search(query: String): List<CatalogItem> =
        catalog(mapOf("query" to query), "POPULARITY", 0).first

    suspend fun recent(page: Int): Pair<List<RecentItem>, Boolean> {
        val body = try {
            val resp = app.get("$RECENT?page=$page", headers = BASE_HEADERS)
            if (resp.isSuccessful) resp.text else null
        } catch (e: Exception) {
            Log.e(TAG, "recent failed: ${e.message}")
            null
        } ?: return emptyList<RecentItem>() to false
        val parsed = parse<RecentEnvelope>(body) ?: return emptyList<RecentItem>() to false
        return parsed.results.filter { it.id != null } to (parsed.hasNextPage == true)
    }

    suspend fun animeDetail(slug: String): CatalogItem? {
        val body = postGraphQL(
            ANIME_QUERY.replace("\$id", slug),
            emptyMap()
        ) ?: return null
        return parse<AnimeEnvelope>(body)?.data?.anime
    }

    // a slug pasted from the site's own url (displaySlug-anilistId) does not match
    // the internal id, the rendered page carries the real one
    suspend fun resolveSlug(urlSlug: String): String? {
        val body = try {
            val resp = app.get("$SITE/anime/$urlSlug", headers = browserHeaders)
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        } ?: return null
        return Regex("""slug:"([a-z0-9-]+)"""").find(body)?.groupValues?.get(1)
    }

    suspend fun episodes(slug: String): List<EpisodeList> {
        val body = try {
            val resp = app.get("$PP/episodes?id=${urlEncode(slug)}", headers = BASE_HEADERS)
            if (resp.isSuccessful) resp.text else null
        } catch (e: Exception) {
            Log.e(TAG, "episodes failed: ${e.message}")
            null
        } ?: return emptyList()
        return parse<List<EpisodeList>>(body).orEmpty()
    }

    suspend fun servers(slug: String, epNum: Int): ServerList {
        val body = try {
            val resp = app.get(
                "$PP/servers?id=${urlEncode(slug)}&epNum=$epNum",
                headers = BASE_HEADERS
            )
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        } ?: return ServerList()
        return parse<ServerList>(body) ?: ServerList()
    }

    // the site's own player discovers its self hosted episode through this
    // lookup before any provider is asked, the episode id it answers with is
    // what the site's own loader opens. a burst of calls occasionally draws a
    // null episode, the same call a moment later answers fine
    suspend fun anmxLookup(anilistId: Int, epNum: Int): AnmxLookup? {
        var lastReason: String? = null
        repeat(3) { attempt ->
            val body = try {
                val resp = app.get(
                    "$PLYR/api/anmx/$anilistId/$epNum",
                    headers = BASE_HEADERS + mapOf(
                        "Accept" to "*/*",
                        "Origin" to "https://plyr.animex.one",
                        "Referer" to "https://plyr.animex.one/"
                    )
                )
                if (resp.isSuccessful) resp.text else null
            } catch (e: Exception) {
                lastReason = e.message
                null
            }
            if (body != null) {
                val parsed = parse<AnmxLookup>(body)
                if (parsed?.episode?.episodeId != null) return parsed
                lastReason = "episode null"
            }
            if (attempt < 2) delay(1000L)
        }
        Log.d(TAG, "anmx lookup unavailable: $lastReason")
        return null
    }

    fun parseAnmxManifest(body: String): AnmxManifest? = parse<AnmxManifest>(body)

    suspend fun sources(slug: String, epNum: Int, type: String, providerId: String): SourceSet? {
        val body = try {
            val resp = app.get(
                "$PP/sources?id=${urlEncode(slug)}&epNum=$epNum&type=$type&providerId=${urlEncode(providerId)}",
                headers = BASE_HEADERS
            )
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        } ?: return null
        val parsed = parse<SourceSet>(body) ?: return null
        if (parsed.sources.isEmpty()) return null
        return parsed
    }

    // the flixcloud entries ride the watch page stream as a deferred sveltekit
    // block keyed by anilist id and episode, any slug is accepted
    suspend fun zenAccessIds(anilistId: Int, epNum: Int): List<ZenEntry> {
        val body = try {
            val resp = app.get(
                "$SITE/watch/x-$anilistId-episode-$epNum",
                headers = browserHeaders
            )
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return Regex("""access_id:"([a-z0-9]+)",imdb:\w+,audio:"([a-z]+)"""")
            .findAll(body)
            .map { ZenEntry(it.groupValues[1], it.groupValues[2]) }
            .distinctBy { it.accessId }
            .toList()
    }

    // the site's own download sheet is keyed by mal id and offers anidl and
    // kiwi mirror entries per quality and audio side
    suspend fun episodeLinks(malId: Int, epNum: Int): List<DownloadEntry> {
        val body = try {
            val resp = app.get(
                "$SITE/api/episode-links/$malId/$epNum",
                headers = BASE_HEADERS + mapOf("Referer" to "$SITE/")
            )
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return parse<EpisodeLinksEnvelope>(body)?.downloads.orEmpty()
    }

    // resolves a sheet entry to its kwik file page, anidl rows answer with a
    // null direct url and only carry their redirect page
    suspend fun resolveEpisodeLink(malId: Int, epNum: Int, downloadId: String): ResolvedLink? {
        return try {
            val resp = app.post(
                "$SITE/api/episode-links/$malId/$epNum/resolve",
                headers = BASE_HEADERS + mapOf(
                    "Content-Type" to "application/json",
                    "Origin" to SITE,
                    "Referer" to "$SITE/"
                ),
                requestBody = mapper.writeValueAsString(mapOf("downloadId" to downloadId))
                    .toRequestBody("application/json".toMediaType())
            )
            if (resp.isSuccessful) parse<ResolvedLink>(resp.text) else null
        } catch (_: Exception) {
            null
        }
    }

    data class ZenEntry(val accessId: String, val audio: String)
}
