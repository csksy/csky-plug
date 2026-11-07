package com.csksy.animex

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogEnvelope(
    val data: CatalogData? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatalogData(
        val catalogAnime: CatalogAnime? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatalogAnime(
        val items: List<CatalogItem> = emptyList(),
        val totalCount: Int? = null,
        val hasNextPage: Boolean? = null
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeEnvelope(
    val data: AnimeData? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeData(
        val anime: CatalogItem? = null
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogItem(
    val id: String? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val titleRomaji: String? = null,
    val titleEnglish: String? = null,
    val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val status: String? = null,
    val format: String? = null,
    val averageScore: Int? = null,
    val seasonYear: Int? = null,
    val season: String? = null,
    val genres: List<String>? = null,
    val subCount: Int? = null,
    val dubCount: Int? = null,
    val episodeCount: Int? = null,
    val poster: String? = null
) {
    fun bestPoster(): String? = coverImage?.best() ?: poster
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CoverImage(
        val extraLarge: String? = null,
        val large: String? = null,
        val medium: String? = null
    ) {
        fun best(): String? = extraLarge ?: large ?: medium
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecentEnvelope(
    val page: Int? = null,
    val hasNextPage: Boolean? = null,
    val results: List<RecentItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecentItem(
    val id: String? = null,
    val anilistId: Int? = null,
    val titleRomaji: String? = null,
    val titleEnglish: String? = null,
    val coverImage: RecentCover? = null,
    val format: String? = null,
    val genres: List<String>? = null,
    val meanScore: Int? = null,
    val isSub: Boolean? = null,
    val isDub: Boolean? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecentCover(
        val extraLarge: String? = null,
        val large: String? = null,
    ) {
        fun best(): String? = extraLarge ?: large
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeList(
    val number: Int? = null,
    val titles: EpisodeTitles? = null,
    val img: String? = null,
    val isFiller: Boolean? = null,
    val description: String? = null,
    val length: Int? = null,
    val airDateUtc: String? = null,
    val hasDub: Boolean? = null,
    val hasSub: Boolean? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeTitles(
        val en: String? = null,
        val ja: String? = null,
        val xjat: String? = null
    ) {
        fun best(): String? = en?.takeIf { it.isNotBlank() } ?: xjat?.takeIf { it.isNotBlank() }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServerList(
    val subProviders: List<Provider> = emptyList(),
    val dubProviders: List<Provider> = emptyList()
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Provider(
        val id: String? = null,
        val default: Boolean? = null,
        val tip: String? = null
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourceSet(
    val sources: List<Stream> = emptyList(),
    val tracks: List<Track>? = null,
    val headers: Map<String, String>? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Stream(
        val url: String? = null,
        val quality: String? = null,
        val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Track(
        val id: String? = null,
        val url: String? = null,
        val lang: String? = null,
        val label: String? = null,
        val kind: String? = null,
        val default: Boolean? = null
    )
}
