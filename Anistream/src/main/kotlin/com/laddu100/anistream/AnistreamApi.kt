package com.laddu100.anistream

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

/**
 * HTTP layer for anistream.one:
 *  - GraphQL catalog (graphql.animex.one)
 *  - REST recent/episodes/servers/sources (api.anistream.one)
 *  - flixcloud lookup (anistream.one/api/flixcloud)
 *
 * flixcloud.cc resolution lives in FlixcloudResolver (it needs a dedicated
 * sequential-connection flow); everything here is stateless GET/POST.
 */
object AnistreamApi {

    const val MAIN_URL = "https://anistream.one"
    const val GRAPHQL_URL = "https://graphql.animex.one/graphql"
    const val RECENT_URL = "https://graphql.animex.one/api/recent"
    const val REST_BASE = "https://api.anistream.one/rest/api"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val mapper = ObjectMapper()

    val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$MAIN_URL/",
        "Origin" to MAIN_URL,
    )

    // ---------------------------------------------------------------- GraphQL

    suspend fun graphqlPost(query: String, variables: Map<String, Any?>): SearchData? {
        return try {
            val body = mapOf("query" to query, "variables" to variables)
            val text = app.post(
                GRAPHQL_URL,
                json = body,
                headers = baseHeaders + mapOf("Content-Type" to "application/json")
            ).text
            mapper.readValue<GqlEnvelope<SearchData>>(text).data
        } catch (e: Exception) {
            null
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
        return try {
            app.get("$RECENT_URL?page=$page", headers = baseHeaders)
                .parsedSafe<RecentEnvelope>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun episodes(slug: String): List<EpisodeItem> {
        return try {
            app.get("$REST_BASE/episodes?id=$slug", headers = baseHeaders)
                .parsedSafe<List<EpisodeItem>>() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun servers(slug: String, epNum: Int): ServersEnvelope? {
        return try {
            app.get("$REST_BASE/servers?id=$slug&epNum=$epNum", headers = baseHeaders)
                .parsedSafe<ServersEnvelope>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun sources(slug: String, epNum: Int, type: String, providerId: String): SourcesEnvelope? {
        return try {
            app.get(
                "$REST_BASE/sources?id=$slug&epNum=$epNum&type=$type&providerId=$providerId",
                headers = baseHeaders
            ).parsedSafe<SourcesEnvelope>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun flixcloudLookup(watchSlug: String, anilistId: Int, episode: Int): FlixcloudLookup? {
        return try {
            app.get(
                "$MAIN_URL/api/flixcloud?slug=$watchSlug&anilistId=$anilistId&episode=$episode",
                headers = baseHeaders
            ).parsedSafe<FlixcloudLookup>()
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------- utilities

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
