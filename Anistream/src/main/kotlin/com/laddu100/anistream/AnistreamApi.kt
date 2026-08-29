package com.laddu100.anistream

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * HTTP layer for anistream.one (v2 — resilient):
 *  - All calls go through AnistreamHttp (CloudflareKiller retry, DoH DNS
 *    fallback for ISP blocks, cookie jar mirroring the site's
 *    `credentials: 'include'`, 429 retry, descriptive errors).
 *  - Failures now THROW AnistreamHttp.AnistreamException with the real reason
 *    (v1 swallowed every error into null/empty which made the plugin fail
 *    silently with zero feedback).
 *
 * Endpoints (verified against the live site bundle):
 *  - GraphQL catalog  POST https://graphql.animex.one/graphql
 *  - Recent           GET  https://graphql.animex.one/api/recent
 *  - REST episodes/servers/sources  https://api.anistream.one/rest/api/*
 *  - FlixCloud lookup GET  https://anistream.one/api/flixcloud
 */
object AnistreamApi {

    const val MAIN_URL = "https://anistream.one"
    const val GRAPHQL_URL = "https://graphql.animex.one/graphql"
    const val RECENT_URL = "https://graphql.animex.one/api/recent"
    const val REST_BASE = "https://api.anistream.one/rest/api"

    private val mapper = ObjectMapper()

    // ---------------------------------------------------------------- GraphQL

    suspend fun graphqlPost(query: String, variables: Map<String, Any?>): SearchData? {
        val text = AnistreamHttp.postJson(
            GRAPHQL_URL,
            mapOf("query" to query, "variables" to variables)
        )
        return parseOrThrow(text, GRAPHQL_URL) { raw: String ->
            val env = mapper.readValue<GqlEnvelope<SearchData>>(raw)
            if (env.data == null) {
                // GraphQL-level error: surface the message instead of an empty page
                val err = Regex(""""message"\s*:\s*"([^"]+)"""")
                    .find(raw)?.groupValues?.get(1) ?: "unknown GraphQL error"
                throw AnistreamHttp.AnistreamException("Anistream GraphQL error: $err")
            }
            env.data
        }
    }

    /** Field selection shared by all catalog calls (coverImage is a JSON scalar — no subfields). */
    private const val CATALOG_FIELDS = """
        items {
            id
            anilistId
            malId
            titleRomaji
            titleEnglish
            coverImage
            bannerImage
            format
            status
            season
            seasonYear
            averageScore
            isAdult
            nextAiringAt
            nextAiringEpisode
            episodeCount
            genres
            subCount
            dubCount
        }
        totalCount
        hasNextPage
        limit
    """

    suspend fun searchAnime(query: String, offset: Int = 0, limit: Int = 30): List<AnimeNode> {
        val data = graphqlPost(
            """query(${'$'}q: String!, ${'$'}limit: Int, ${'$'}offset: Int, ${'$'}includeAdult: Boolean) {
                searchAnime(query: ${'$'}q, limit: ${'$'}limit, offset: ${'$'}offset, includeAdult: ${'$'}includeAdult) { $CATALOG_FIELDS }
            }""",
            mapOf("q" to query, "limit" to limit, "offset" to offset, "includeAdult" to false)
        )
        return data?.searchAnime?.items.orEmpty()
    }

    suspend fun catalogAnime(
        filter: String,
        sort: String,
        offset: Int = 0,
        limit: Int = 30
    ): List<AnimeNode> {
        val data = graphqlPost(
            """query(${'$'}limit: Int, ${'$'}offset: Int) {
                catalogAnime(filter: $filter, sort: [$sort], limit: ${'$'}limit, offset: ${'$'}offset) { $CATALOG_FIELDS }
            }""",
            mapOf("limit" to limit, "offset" to offset)
        )
        return data?.catalogAnime?.items.orEmpty()
    }

    suspend fun animeDetail(slugOrId: String): AnimeNode? {
        val data = graphqlPost(
            """query(${'$'}id: String) {
                anime(id: ${'$'}id) {
                    id
                    malId
                    anilistId
                    titleRomaji
                    titleEnglish
                    titles
                    synonyms
                    coverImage
                    bannerImage
                    description
                    episodeCount
                    status
                    duration
                    genres
                    format
                    seasonYear
                    season
                    averageScore
                    meanScore
                    popularity
                    favourites
                    isAdult
                    nextAiringAt
                    nextAiringEpisode
                    trailerId
                    subCount
                    dubCount
                    studios
                    tags
                }
            }""",
            mapOf("id" to slugOrId)
        )
        return data?.anime
    }

    // ------------------------------------------------------------------ REST

    suspend fun recent(page: Int): RecentEnvelope? {
        val text = AnistreamHttp.get("$RECENT_URL?page=$page", referer = "$MAIN_URL/")
        return parseOrThrow(text, "$RECENT_URL?page=$page") { it ->
            mapper.readValue<RecentEnvelope>(it)
        }
    }

    suspend fun episodes(slug: String): List<EpisodeItem> {
        val url = "$REST_BASE/episodes?id=$slug"
        val text = AnistreamHttp.get(url, referer = "$MAIN_URL/")
        return parseOrThrow(text, url) { it ->
            mapper.readValue<List<EpisodeItem>>(it)
        }
    }

    suspend fun servers(slug: String, epNum: Int): ServersEnvelope? {
        val url = "$REST_BASE/servers?id=$slug&epNum=$epNum"
        val text = AnistreamHttp.get(url, referer = "$MAIN_URL/")
        return parseOrThrow(text, url) { it ->
            mapper.readValue<ServersEnvelope>(it)
        }
    }

    suspend fun sources(slug: String, epNum: Int, type: String, providerId: String): SourcesEnvelope? {
        val url = "$REST_BASE/sources?id=$slug&epNum=$epNum&type=$type&providerId=$providerId"
        val text = AnistreamHttp.get(url, referer = "$MAIN_URL/")
        return parseOrThrow(text, url) { it ->
            mapper.readValue<SourcesEnvelope>(it)
        }
    }

    suspend fun flixcloudLookup(watchSlug: String, anilistId: Int, episode: Int): FlixcloudLookup? {
        val url = "$MAIN_URL/api/flixcloud?slug=$watchSlug&anilistId=$anilistId&episode=$episode"
        val text = AnistreamHttp.get(url, referer = "$MAIN_URL/")
        return parseOrThrow(text, url) { it ->
            mapper.readValue<FlixcloudLookup>(it)
        }
    }

    // -------------------------------------------------------------- utilities

    private inline fun <T> parseOrThrow(
        text: String,
        url: String,
        block: (String) -> T
    ): T {
        return try {
            block(text)
        } catch (e: AnistreamHttp.AnistreamException) {
            throw e
        } catch (e: Exception) {
            throw AnistreamHttp.AnistreamException(
                "Anistream returned an unexpected response from $url " +
                    "(${e.javaClass.simpleName}: ${e.message?.take(80)}; body: " +
                    "${text.replace("\n", " ").take(100)})"
            )
        }
    }

    /** Build the watch-page slug used by the flixcloud lookup: {title}-{anilistId}-episode-{n} */
    fun watchSlug(title: String?, anilistId: Int?, episode: Int): String {
        val t = (title ?: "").lowercase()
            .replace("&", " and ")
            .map { c -> if (c.isLetterOrDigit()) c else '-' }
            .joinToString("")
            .split('-').filter { it.isNotBlank() }.joinToString("-")
        return "${t}-${anilistId ?: 0}-episode-$episode"
    }
}
