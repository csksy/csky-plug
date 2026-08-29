package com.laddu100.anistream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/* ------------------------------------------------------------------ *
 *  GraphQL catalog / search models  (graphql.animex.one)
 * ------------------------------------------------------------------ */

@JsonIgnoreProperties(ignoreUnknown = true)
data class GqlEnvelope<T>(
    @JsonProperty("data") val data: T? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchData(
    @JsonProperty("searchAnime") val searchAnime: AnimeConnection? = null,
    @JsonProperty("catalogAnime") val catalogAnime: AnimeConnection? = null,
    @JsonProperty("anime") val anime: AnimeNode? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeConnection(
    @JsonProperty("items") val items: List<AnimeNode> = emptyList(),
    @JsonProperty("totalCount") val totalCount: Int? = null,
    @JsonProperty("hasNextPage") val hasNextPage: Boolean? = null,
    @JsonProperty("limit") val limit: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeNode(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("malId") val malId: Int? = null,
    @JsonProperty("anilistId") val anilistId: Int? = null,
    @JsonProperty("titleRomaji") val titleRomaji: String? = null,
    @JsonProperty("titleEnglish") val titleEnglish: String? = null,
    @JsonProperty("titles") val titles: Map<String, String?>? = null,
    @JsonProperty("synonyms") val synonyms: List<String>? = null,
    @JsonProperty("coverImage") val coverImage: CoverImage? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("episodeCount") val episodeCount: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("season") val season: String? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("meanScore") val meanScore: Int? = null,
    @JsonProperty("popularity") val popularity: Int? = null,
    @JsonProperty("favourites") val favourites: Int? = null,
    @JsonProperty("trending") val trending: Int? = null,
    @JsonProperty("isAdult") val isAdult: Boolean? = null,
    @JsonProperty("countryOfOrigin") val countryOfOrigin: String? = null,
    @JsonProperty("nextAiringAt") val nextAiringAt: Long? = null,
    @JsonProperty("nextAiringEpisode") val nextAiringEpisode: Int? = null,
    @JsonProperty("trailerId") val trailerId: String? = null,
    @JsonProperty("subCount") val subCount: Int? = null,
    @JsonProperty("dubCount") val dubCount: Int? = null,
    @JsonProperty("studios") val studios: List<String>? = null,
    @JsonProperty("tags") val tags: List<String>? = null,
    @JsonProperty("recommendations") val recommendations: List<LinkedNode>? = null,
    @JsonProperty("relations") val relations: List<LinkedNode>? = null,
    @JsonProperty("seasons") val seasons: List<LinkedNode>? = null
) {
    val displayTitle: String
        get() = titleEnglish?.takeIf { it.isNotBlank() }
            ?: titleRomaji?.takeIf { it.isNotBlank() }
            ?: titles?.get("en")?.takeIf { it.isNotBlank() }
            ?: id.orEmpty()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CoverImage(
    @JsonProperty("extraLarge") val extraLarge: String? = null,
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("medium") val medium: String? = null
) {
    val best: String? get() = extraLarge ?: large ?: medium
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LinkedNode(
    @JsonProperty("animeId") val animeId: String? = null,
    @JsonProperty("anilistId") val anilistId: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("episodeCount") val episodeCount: Int? = null,
    @JsonProperty("type") val type: String? = null
)

/* ------------------------------------------------------------------ *
 *  REST: recent  (graphql.animex.one/api/recent)
 * ------------------------------------------------------------------ */

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecentEnvelope(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("hasNextPage") val hasNextPage: Boolean? = null,
    @JsonProperty("results") val results: List<RecentItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecentItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("anilistId") val anilistId: Int? = null,
    @JsonProperty("malId") val malId: Int? = null,
    @JsonProperty("coverImage") val coverImage: CoverImage? = null,
    @JsonProperty("titleRomaji") val titleRomaji: String? = null,
    @JsonProperty("titleEnglish") val titleEnglish: String? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("airingAt") val airingAt: Long? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("meanScore") val meanScore: Int? = null,
    @JsonProperty("isSub") val isSub: Boolean? = null,
    @JsonProperty("isDub") val isDub: Boolean? = null
)

/* ------------------------------------------------------------------ *
 *  REST: episodes  (api.anistream.one/rest/api/episodes)
 * ------------------------------------------------------------------ */

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeItem(
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("titles") val titles: Map<String, String?>? = null,
    @JsonProperty("img") val img: String? = null,
    @JsonProperty("isFiller") val isFiller: Boolean? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("rating") val rating: String? = null,
    @JsonProperty("length") val length: Int? = null,
    @JsonProperty("airDateUtc") val airDateUtc: String? = null,
    @JsonProperty("hasDub") val hasDub: Boolean? = null,
    @JsonProperty("hasSub") val hasSub: Boolean? = null
) {
    val epTitle: String? get() = titles?.get("en")?.takeIf { it.isNotBlank() }
}

/* ------------------------------------------------------------------ *
 *  REST: servers  (api.anistream.one/rest/api/servers)
 * ------------------------------------------------------------------ */

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServersEnvelope(
    @JsonProperty("subProviders") val subProviders: List<ProviderInfo> = emptyList(),
    @JsonProperty("dubProviders") val dubProviders: List<ProviderInfo> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProviderInfo(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("default") val default: Boolean? = null,
    @JsonProperty("tip") val tip: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("url") val url: String? = null
) {
    /** "hard" | "soft" | "other" — mirrors the site's own ur() helper. */
    val subKind: String
        get() {
            val t = tip?.lowercase().orEmpty()
            return when {
                t.contains("hard sub") || t.contains("hardsub") -> "hard"
                t.contains("soft sub") || t.contains("softsub") -> "soft"
                else -> "other"
            }
        }
}

/* ------------------------------------------------------------------ *
 *  REST: sources  (api.anistream.one/rest/api/sources)
 * ------------------------------------------------------------------ */

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourcesEnvelope(
    @JsonProperty("sources") val sources: List<SourceFile> = emptyList(),
    @JsonProperty("tracks") val tracks: List<TrackFile>? = null,
    @JsonProperty("audio") val audio: Any? = null,
    @JsonProperty("headers") val headers: Map<String, String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourceFile(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("isM3U8") val isM3U8: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrackFile(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("default") val default: Boolean? = null
) {
    val bestUrl: String? get() = url ?: file
}

/* ------------------------------------------------------------------ *
 *  MegaPlay (minky / yuki backend)
 * ------------------------------------------------------------------ */

@JsonIgnoreProperties(ignoreUnknown = true)
data class MegaplayResponse(
    @JsonProperty("sources") val sources: MegaplaySources? = null,
    @JsonProperty("tracks") val tracks: List<TrackFile> = emptyList(),
    @JsonProperty("intro") val intro: SkipTime? = null,
    @JsonProperty("outro") val outro: SkipTime? = null,
    @JsonProperty("server") val server: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MegaplaySources(
    @JsonProperty("file") val file: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SkipTime(
    @JsonProperty("start") val start: Double? = null,
    @JsonProperty("end") val end: Double? = null
)

/* ------------------------------------------------------------------ *
 *  VidHawk (hawk)
 * ------------------------------------------------------------------ */

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkRace(
    @JsonProperty("winner") val winner: String? = null,
    @JsonProperty("ticket") val ticket: String? = null,
    @JsonProperty("servers") val servers: List<VidhawkServer> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkServer(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("ticket") val ticket: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkPlay(
    @JsonProperty("anilistId") val anilistId: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("defaultAudio") val defaultAudio: String? = null,
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("serverLabel") val serverLabel: String? = null,
    @JsonProperty("tracks") val tracks: List<VidhawkTrack> = emptyList(),
    @JsonProperty("intro") val intro: SkipTime? = null,
    @JsonProperty("outro") val outro: SkipTime? = null,
    @JsonProperty("captions") val captions: Map<String, List<VidhawkCaption>>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkTrack(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("src") val src: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkCaption(
    @JsonProperty("src") val src: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("default") val default: Boolean? = null
)

/* ------------------------------------------------------------------ *
 *  FlixCloud (zen)
 * ------------------------------------------------------------------ */

@JsonIgnoreProperties(ignoreUnknown = true)
data class FlixcloudLookup(
    @JsonProperty("playerUrl") val playerUrl: String? = null,
    @JsonProperty("accessId") val accessId: String? = null,
    @JsonProperty("error") val error: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FlixSubtitle(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("format") val format: String? = null
) {
    val bestUrl: String? get() = url ?: file
}

/* ------------------------------------------------------------------ *
 *  Episode link payload (CloudStream episode -> loadLinks)
 * ------------------------------------------------------------------ */

data class LinkData(
    val slug: String,
    val anilistId: Int?,
    val malId: Int?,
    val ep: Int,
    val variant: String,        // "sub" | "dub"
    val title: String? = null   // anime display title (used for zen watch-slug)
)
