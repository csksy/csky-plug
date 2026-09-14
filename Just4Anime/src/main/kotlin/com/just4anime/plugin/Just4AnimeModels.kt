package com.just4anime.plugin

import com.google.gson.annotations.SerializedName

/* ---------------- search (just4anime.online/api/advanced-search) ---------------- */

data class J4ASearchResponse(
    val success: Boolean = false,
    val data: J4ASearchData? = null
)

data class J4ASearchData(
    val results: List<J4ASearchResult> = emptyList(),
    val currentPage: Int? = null,
    val hasNextPage: Boolean? = null,
    val totalPages: Int? = null,
    val totalResults: Int? = null
)

data class J4ASearchResult(
    val id: String? = null,
    val malId: Int? = null,
    val title: J4ATitle? = null,
    val status: String? = null,
    val image: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val rating: String? = null,
    val releaseDate: String? = null,
    val totalEpisodes: Int? = null,
    val currentEpisode: Int? = null,
    val currentEpisodeCount: Int? = null,
    val type: String? = null,
    val popularity: Int? = null
)

data class J4ATitle(
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
    val userPreferred: String? = null
)

/* ---------------- episodes (just4anime.online/api/episodes/{id}) ---------------- */

data class J4AEpisodesResponse(
    val success: Boolean = false,
    val data: J4AEpisodesData? = null
)

data class J4AEpisodesData(
    val id: String? = null,
    val malId: Int? = null,
    val tmdbId: Int? = null,
    val tmdbType: String? = null,
    val title: String? = null,
    val titleRomaji: String? = null,
    val titleJa: String? = null,
    val format: String? = null,
    val year: Int? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val studios: List<String>? = null,
    val totalEpisodes: Int? = null,
    val anilistEpisodeCount: Int? = null,
    val currentEpisode: Int? = null,
    val status: String? = null,
    val images: List<J4AImage>? = null,
    val episodes: List<J4AEpisode> = emptyList()
)

data class J4AImage(
    val coverType: String? = null,
    val url: String? = null,
    val source: String? = null
)

data class J4AEpisode(
    val id: String? = null,
    val number: Int? = null,
    val title: String? = null,
    val titleJa: String? = null,
    val description: String? = null,
    val image: String? = null,
    val airDate: String? = null,
    val duration: Int? = null,
    val isFiller: Boolean? = null,
    val rating: String? = null,
    val hasAired: Boolean? = null,
    val season: Int? = null
)

/* ---------------- availability (api.just4anime.online/api/v1/meta) ---------------- */

data class J4AAvailabilityResponse(
    val success: Boolean = false,
    val data: J4AAvailabilityData? = null
)

data class J4AAvailabilityData(
    val animeId: String? = null,
    val malId: Int? = null,
    val servers: List<J4AServer> = emptyList()
)

data class J4AServer(
    val code: String = "",
    val displayName: String? = null,
    val animeId: String? = null,
    val cached: Boolean? = null,
    val hasEpisode: Boolean? = null,
    val totalEpisodes: Int? = null,
    val episodeId: String? = null,
    val types: List<String> = emptyList()
)

/* ---------------- sources (api.just4anime.online/api/v1/meta/sources) ---------------- */

data class J4ASourcesResponse(
    val success: Boolean = false,
    val data: J4ASourcesData? = null
)

data class J4ASourcesData(
    val episode: J4ASourceEpisode? = null,
    val isDub: Boolean? = null,
    val type: String? = null,
    val sources: List<J4AStream> = emptyList(),
    val subtitles: List<J4ASubtitle> = emptyList(),
    val iframe: Any? = null,
    val outro: J4AOutro? = null,
    val timestamp: String? = null
)

data class J4ASourceEpisode(
    val number: Int? = null,
    val id: String? = null,
    val title: String? = null
)

data class J4AOutro(
    val start: Int? = null,
    val end: Int? = null
)

data class J4AStream(
    val url: String? = null,
    val quality: String? = null,
    val isM3U8: Boolean? = null,
    val isDub: Boolean? = null,
    val proxied: Boolean? = null,
    val headers: Map<String, String>? = null,
    val subtitles: List<J4ASubtitle>? = null
)

data class J4ASubtitle(
    val url: String? = null,
    val lang: String? = null,
    val language: String? = null,
    val format: String? = null,
    val headers: Map<String, String>? = null,
    val origin: String? = null,
    val proxied: Boolean? = null
)
