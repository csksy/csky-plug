package com.laddu100.animex

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object AnimeXApi {

    const val TAG = "AnimeX"
    const val MAIN_URL = "https://animex.one"
    const val GRAPHQL_URL = "https://graphql.animex.one/graphql"
    const val PROVIDER_API = "https://pp.animex.one"
    const val MEGAPLAY = "https://megaplay.buzz"
    const val FLIX_EMBED_BASE = "https://flixcloud.cc"

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val BROWSER_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Origin" to MAIN_URL,
        "Referer" to "$MAIN_URL/"
    )

    // Cloudflare on pp.animex.one rejects requests without an Origin header (403),
    // which silently killed episodes/servers/sources -> no sub/dub episode split.
    private val PROVIDER_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Origin" to "https://animex.one",
        "Referer" to "https://plyr.animex.one/"
    )

    /**
     * GET on pp.animex.one with a Referer fallback: the WAF sometimes rejects
     * the player referer, so retry with the main site's referer (both carry
     * the required Origin header) before giving up.
     */
    private suspend fun providerGet(path: String): String? {
        val attempts = listOf(PROVIDER_HEADERS, BROWSER_HEADERS)
        for (headers in attempts) {
            val body = try {
                val resp = app.get("$PROVIDER_API$path", headers = headers)
                if (resp.isSuccessful) resp.text else continue
            } catch (e: Exception) {
                Log.d(TAG, "providerGet $path failed: ${e.message?.take(80)}")
                continue
            }
            return body
        }
        return null
    }

    private inline fun <reified T> parse(text: String): T? =
        try {
            mapper.readValue(text, T::class.java)
        } catch (e: Exception) {
            Log.d(TAG, "parse failed: ${e.message?.take(80)}")
            null
        }

    private suspend fun gql(query: String, variables: String? = null): JsonNodeLike? {
        val body = buildString {
            append("{\"query\":")
            append(mapper.writeValueAsString(query))
            if (variables != null) {
                append(",\"variables\":")
                append(variables)
            }
            append("}")
        }
        return try {
            val resp = app.post(
                GRAPHQL_URL,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "Referer" to "$MAIN_URL/"
                ),
                requestBody = body.toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            )
            if (!resp.isSuccessful) return null
            parse<GraphQlEnvelope>(resp.text)?.data
        } catch (e: Exception) {
            Log.d(TAG, "gql failed: ${e.message?.take(80)}")
            null
        }
    }

    private val CATALOG_FIELDS = """
        items {
          id anilistId malId titleRomaji titleEnglish coverImage bannerImage description
          format status episodeCount seasonYear season subCount dubCount averageScore
          popularity genres
        }
        totalCount currentPage totalPages hasNextPage
    """.trimIndent()

    suspend fun search(query: String, page: Int, limit: Int = 30): List<CatalogItem> {
        val offset = (page - 1) * limit
        val variables = """{"query":${mapper.writeValueAsString(query)},"limit":$limit,"offset":$offset}"""
        val gqlQuery = "query(\$query: String, \$limit: Int, \$offset: Int) { searchAnime(query: \$query, limit: \$limit, offset: \$offset) { $CATALOG_FIELDS } }"
        val data = gql(gqlQuery, variables) ?: return emptyList()
        return data.searchAnime?.items ?: emptyList()
    }

    suspend fun catalog(
        section: String,
        page: Int,
        limit: Int = 30
    ): Pair<List<CatalogItem>, Boolean> {
        val offset = (page - 1) * limit
        val filter = when (section) {
            "movies" -> ""","filter":{"formatIn":["MOVIE"]}"""
            "upcoming" -> ""","filter":{"statusIn":["NOT_YET_RELEASED"]}"""
            else -> ""
        }
        val sort = when (section) {
            "trending" -> "[{\"field\":\"TRENDING\",\"direction\":\"DESC\"}]"
            "updated" -> "[{\"field\":\"UPDATED_AT\",\"direction\":\"DESC\"}]"
            else -> "[{\"field\":\"POPULARITY\",\"direction\":\"DESC\"}]"
        }
        val variables = """{"sort":$sort,"limit":$limit,"offset":$offset$filter}"""
        val gqlQuery = "query(\$filter: AnimeCatalogFilterInput, \$sort: [AnimeSortInput!], \$limit: Int, \$offset: Int) { catalogAnime(filter: \$filter, sort: \$sort, limit: \$limit, offset: \$offset) { $CATALOG_FIELDS } }"
        val data = gql(gqlQuery, variables) ?: return Pair(emptyList(), false)
        val conn = data.catalogAnime ?: return Pair(emptyList(), false)
        val items = conn.items ?: emptyList()
        val hasNext = conn.hasNextPage ?: ((conn.currentPage ?: 1) < (conn.totalPages ?: 1))
        return items to hasNext
    }

    suspend fun animeDetail(slug: String): AnimeDetail? {
        val variables = """{"id":${mapper.writeValueAsString(slug)}}"""
        val query = """
            query(${'$'}id: String) { anime(id: ${'$'}id) {
              id anilistId malId titleRomaji titleEnglish coverImage bannerImage description
              episodeCount status format seasonYear season averageScore popularity genres
              studios source subCount dubCount duration countryOfOrigin
            } }
        """.trimIndent()
        return gql(query, variables)?.anime
    }

    suspend fun episodes(slug: String): List<EpisodeEntry> {
        val body = providerGet("/rest/api/episodes?id=${urlEncode(slug)}") ?: return emptyList()
        return parse<List<EpisodeEntry>>(body) ?: emptyList()
    }

    suspend fun servers(slug: String, epNum: Int): ServersResponse? {
        val body = providerGet("/rest/api/servers?id=${urlEncode(slug)}&epNum=$epNum") ?: return null
        return parse<ServersResponse>(body)
    }

    suspend fun sources(
        slug: String,
        epNum: Int,
        type: String,
        providerId: String
    ): SourcesResponse? {
        val body = providerGet(
            "/rest/api/sources?id=${urlEncode(slug)}&epNum=$epNum" +
                "&type=$type&providerId=${urlEncode(providerId)}"
        ) ?: return null
        return parse<SourcesResponse>(body)
    }

    suspend fun fetchText(
        url: String,
        headers: Map<String, String> = mapOf("User-Agent" to USER_AGENT)
    ): String? = try {
        val resp = app.get(url, headers = headers)
        if (resp.isSuccessful) resp.text else null
    } catch (e: Exception) {
        null
    }

    data class ZenEmbed(
        val accessId: String,
        val playerUrl: String,
        val audio: String?
    )

    suspend fun zenEmbed(urlSlug: String, epNum: Int): ZenEmbed? {
        val body = fetchText(
            "$MAIN_URL/watch/${urlEncode(urlSlug)}-episode-$epNum",
            mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$MAIN_URL/",
                "Accept" to "text/html"
            )
        ) ?: return null
        val playerUrl = Regex("""player_url:"(https://flixcloud\.cc/e/[^"]+)"""")
            .find(body)?.groupValues?.get(1) ?: return null
        val accessId = Regex("""access_id:"([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: playerUrl.substringAfter("/e/").substringBefore("?")
        val audio = Regex(""""audio":"([^"]*)"\s*,\s*anilist_id""").find(body)?.groupValues?.get(1)
        return ZenEmbed(accessId, playerUrl, audio)
    }

    data class KotoStream(
        val m3u8: String,
        val tracks: List<Pair<String, String>>
    )

    suspend fun kotoStream(anilistId: Int, epNum: Int, type: String): KotoStream? {
        val embedUrl = "$MEGAPLAY/stream/ani/$anilistId/$epNum/$type"
        val page = try {
            val resp = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$MAIN_URL/",
                    "Accept" to "text/html"
                )
            )
            if (resp.isSuccessful) resp.text else return null
        } catch (e: Exception) {
            Log.d(TAG, "koto embed failed: ${e.message?.take(80)}")
            return null
        }
        if (page.contains("error-code") || page.contains("deleted by the owner")) return null
        val idCandidates = listOfNotNull(
            Regex("""data-id="(\d+)"""").find(page)?.groupValues?.get(1),
            Regex("""data-realid="(\d+)"""").find(page)?.groupValues?.get(1)
        ).distinct()
        if (idCandidates.isEmpty()) return null
        val altDomain = Regex("""data-domain="([^"]+)"""").find(page)?.groupValues?.get(1)
        val hosts = listOf("megaplay.buzz", altDomain).filterNotNull().distinct()

        for (host in hosts) {
            for (streamId in idCandidates) {
                val api = "https://$host/stream/getSourcesNew?id=$streamId&type=$type"
                val body = try {
                    val resp = app.get(
                        api,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "application/json",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to embedUrl
                        )
                    )
                    if (resp.isSuccessful) resp.text else continue
                } catch (e: Exception) {
                    continue
                }
                val parsed = parse<MegaPlaySources>(body) ?: continue
                val file = parsed.sources?.file?.takeIf { it.startsWith("http") } ?: continue
                val tracks = (parsed.tracks ?: emptyList())
                    .filter { it.kind == "captions" || it.kind == "subtitles" }
                    .filter { !it.file.isNullOrBlank() }
                    .mapNotNull { t ->
                        val url = fixUrl(t.file!!)
                        if (url.startsWith("http")) (t.label ?: "English") to url else null
                    }
                return KotoStream(file, tracks)
            }
        }
        return null
    }

    fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    fun fixUrl(url: String): String {
        var u = url
        while (u.contains(":///")) u = u.replace(":///", "://")
        return u
    }

    fun siteUrlSlug(item: CatalogItem): String {
        val title = item.displayTitle() ?: item.id ?: ""
        val slug = title.lowercase().trim()
            .replace(Regex("[^a-z0-9]+"), "-")
            .replace(Regex("^-+|-+$"), "")
        val id = item.anilistId ?: 0
        return "$slug-$id"
    }
}
