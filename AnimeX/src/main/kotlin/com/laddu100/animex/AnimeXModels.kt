package com.laddu100.animex

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GraphQlEnvelope(
    @JsonProperty("data") val data: JsonNodeLike? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonNodeLike(
    @JsonProperty("searchAnime") val searchAnime: CatalogConnection? = null,
    @JsonProperty("catalogAnime") val catalogAnime: CatalogConnection? = null,
    @JsonProperty("anime") val anime: AnimeDetail? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogConnection(
    @JsonProperty("items") val items: List<CatalogItem>? = null,
    @JsonProperty("totalCount") val totalCount: Int? = null,
    @JsonProperty("totalPages") val totalPages: Int? = null,
    @JsonProperty("currentPage") val currentPage: Int? = null,
    @JsonProperty("hasNextPage") val hasNextPage: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("anilistId") val anilistId: Int? = null,
    @JsonProperty("malId") val malId: Int? = null,
    @JsonProperty("titleRomaji") val titleRomaji: String? = null,
    @JsonProperty("titleEnglish") val titleEnglish: String? = null,
    @JsonProperty("coverImage") val coverImage: CoverImage? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("episodeCount") val episodeCount: Int? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("season") val season: String? = null,
    @JsonProperty("subCount") val subCount: Int? = null,
    @JsonProperty("dubCount") val dubCount: Int? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("popularity") val popularity: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("studios") val studios: List<String>? = null,
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("trailerId") val trailerId: String? = null,
    @JsonProperty("nextAiringAt") val nextAiringAt: Long? = null,
    @JsonProperty("isAdult") val isAdult: Boolean? = null
) {
    fun displayTitle(): String? =
        titleEnglish?.takeIf { it.isNotBlank() } ?: titleRomaji?.takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CoverImage(
    @JsonProperty("extraLarge") val extraLarge: String? = null,
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("medium") val medium: String? = null,
    @JsonProperty("color") val color: String? = null
) {
    fun best(): String? =
        extraLarge?.takeIf { it.startsWith("http") }
            ?: large?.takeIf { it.startsWith("http") }
            ?: medium?.takeIf { it.startsWith("http") }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeDetail(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("anilistId") val anilistId: Int? = null,
    @JsonProperty("malId") val malId: Int? = null,
    @JsonProperty("titleRomaji") val titleRomaji: String? = null,
    @JsonProperty("titleEnglish") val titleEnglish: String? = null,
    @JsonProperty("coverImage") val coverImage: CoverImage? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("episodeCount") val episodeCount: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("season") val season: String? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("popularity") val popularity: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("studios") val studios: List<String>? = null,
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("subCount") val subCount: Int? = null,
    @JsonProperty("dubCount") val dubCount: Int? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("countryOfOrigin") val countryOfOrigin: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeEntry(
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("titles") val titles: EpisodeTitles? = null,
    @JsonProperty("img") val img: String? = null,
    @JsonProperty("isFiller") val isFiller: Boolean? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("rating") val rating: String? = null,
    @JsonProperty("length") val length: Int? = null,
    @JsonProperty("airDateUtc") val airDateUtc: String? = null,
    @JsonProperty("hasSub") val hasSub: Boolean? = null,
    @JsonProperty("hasDub") val hasDub: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeTitles(
    @JsonProperty("en") val en: String? = null,
    @JsonProperty("ja") val ja: String? = null,
    @JsonProperty("x-jat") val xJat: String? = null
) {
    fun display(): String? =
        en?.takeIf { it.isNotBlank() } ?: xJat?.takeIf { it.isNotBlank() }
            ?: ja?.takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServersResponse(
    @JsonProperty("subProviders") val subProviders: List<ProviderInfo>? = null,
    @JsonProperty("dubProviders") val dubProviders: List<ProviderInfo>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProviderInfo(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("default") val default: Boolean? = null,
    @JsonProperty("tip") val tip: String? = null
) {
    val isHardsub: Boolean
        get() = tip?.lowercase()?.contains("hard sub") == true
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourcesResponse(
    @JsonProperty("sources") val sources: List<SourceEntry>? = null,
    @JsonProperty("tracks") val tracks: List<TrackEntry>? = null,
    @JsonProperty("chapters") val chapters: List<ChapterEntry>? = null,
    @JsonProperty("headers") val headers: Map<String, String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourceEntry(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("type") val type: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrackEntry(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("default") val default: Boolean? = null
) {
    val isCaptions: Boolean
        get() = kind == "captions" || kind == "subtitles"
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChapterEntry(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("start") val start: Int? = null,
    @JsonProperty("end") val end: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MegaPlaySources(
    @JsonProperty("sources") val sources: MegaPlaySourceList? = null,
    @JsonProperty("tracks") val tracks: List<MegaPlayTrack>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MegaPlaySourceList(
    @JsonProperty("file") val file: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MegaPlayTrack(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("kind") val kind: String? = null
)
