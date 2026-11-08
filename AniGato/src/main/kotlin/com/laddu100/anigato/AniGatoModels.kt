package com.laddu100.anigato

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Titles(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
    val userPreferred: String? = null
) {
    fun display(): String? = english?.takeIf { it.isNotBlank() }
        ?: romaji?.takeIf { it.isNotBlank() }
        ?: userPreferred?.takeIf { it.isNotBlank() }
        ?: native?.takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CoverImage(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null
) {
    fun best(): String? = extraLarge?.takeIf { it.isNotBlank() }
        ?: large?.takeIf { it.isNotBlank() }
        ?: medium?.takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
    val userPreferred: String? = null
) {
    fun display(): String? = english?.takeIf { it.isNotBlank() }
        ?: romaji?.takeIf { it.isNotBlank() }
        ?: userPreferred?.takeIf { it.isNotBlank() }
        ?: native?.takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeMedia(
    val id: Int? = null,
    val idMal: Int? = null,
    val title: MediaTitle? = null,
    val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val genres: List<String>? = null,
    val averageScore: Double? = null,
    val popularity: Int? = null,
    val trending: Int? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val startDate: FuzzyDate? = null,
    val studios: StudioNodes? = null,
    val trailer: Trailer? = null,
    val nextAiringEpisode: NextAiringEpisode? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FuzzyDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StudioNodes(val nodes: List<StudioNode>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StudioNode(val name: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Trailer(val id: String? = null, val site: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NextAiringEpisode(val episode: Int? = null, val airingAt: Long? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FeedResponse(
    val media: List<AnimeMedia>? = null,
    val hasNextPage: Boolean? = null,
    val total: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogSearchResponse(
    val media: List<AnimeMedia>? = null,
    val hasNextPage: Boolean? = null,
    val degraded: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UnifiedSearchItem(
    val id: String? = null,
    val kind: String? = null,
    val tmdbId: Int? = null,
    val title: String? = null,
    val poster: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val episodes: Int? = null,
    val format: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UnifiedSearchResponse(val results: List<UnifiedSearchItem>? = null, val degraded: Boolean? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeEntry(
    val id: String? = null,
    val number: Int? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val isFiller: Boolean? = null,
    val description: String? = null,
    val airDate: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodesResponse(val success: Boolean? = null, val episodes: List<EpisodeEntry>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubtitleTrack(
    val url: String? = null,
    val lang: String? = null,
    val label: String? = null,
    val default: Boolean? = null,
    val machine: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamEntry(
    val id: String? = null,
    val serverName: String? = null,
    val name: String? = null,
    val type: String? = null,
    val quality: String? = null,
    val url: String? = null,
    val embedUrl: String? = null,
    val isM3U8: Boolean? = null,
    val format: String? = null,
    val subtitles: List<SubtitleTrack>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WatchResponse(
    val success: Boolean? = null,
    val streams: List<StreamEntry>? = null,
    val partial: Boolean? = null,
    val primaryPending: Boolean? = null,
    val mainPending: Boolean? = null,
    val loadingMore: Boolean? = null,
    val dubUnavailable: Boolean? = null,
    val notReleased: Boolean? = null,
    val sourceDown: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemaFeedItem(
    val id: String? = null,
    val type: String? = null,
    val tmdbId: Int? = null,
    val title: Titles? = null,
    val coverImage: CoverImage? = null,
    val poster: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val episodes: Int? = null,
    val kind: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemaFeedResponse(val media: List<CinemaFeedItem>? = null, val hasNextPage: Boolean? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemaSearchResponse(val results: List<CinemaFeedItem>? = null, val media: List<CinemaFeedItem>? = null) {
    fun items(): List<CinemaFeedItem> = results ?: media ?: emptyList()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemaMeta(
    val id: String? = null,
    val type: String? = null,
    val tmdbId: Int? = null,
    val title: Titles? = null,
    val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val logoImage: String? = null,
    val description: String? = null,
    val format: String? = null,
    val status: String? = null,
    val duration: Int? = null,
    val genres: List<String>? = null,
    val averageScore: Double? = null,
    val seasonYear: Int? = null,
    val releaseInfo: String? = null,
    val imdbId: String? = null,
    val seasonList: List<Int>? = null,
    val seasons: Int? = null,
    val episodes: Int? = null,
    val trailer: Trailer? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemaEpisodeEntry(
    val id: String? = null,
    val number: Int? = null,
    val season: Int? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val description: String? = null,
    val airDate: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemaEpisodesResponse(val episodes: List<CinemaEpisodeEntry>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemaStreamResponse(
    val success: Boolean? = null,
    val streams: List<StreamEntry>? = null,
    val dmca: Boolean? = null,
    val sourceDown: Boolean? = null,
    val unavailable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemaSubtitlesResponse(val subtitles: List<SubtitleTrack>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniGatoEpisodeData(
    val anilistId: Int? = null,
    val ep: Int? = null,
    val audio: String? = null,
    val title: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniGatoCinemaData(
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val type: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null
)
