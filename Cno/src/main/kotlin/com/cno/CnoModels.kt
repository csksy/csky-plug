package com.cno

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenSearchResponse(
    val page: Int? = null,
    val totalPages: Int? = null,
    val totalResults: Int? = null,
    val results: List<ScreenItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenItem(
    val type: String? = null,
    val id: Int? = null,
    val title: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val votes: Int? = null,
    val playable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenSite(
    val id: String? = null,
    val name: String? = null,
    val hero: List<ScreenItem> = emptyList(),
    val shelves: List<ScreenShelf> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenShelf(
    val id: String? = null,
    val title: String? = null,
    val kind: String? = null,
    val items: List<ScreenItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenSource(
    val id: String? = null,
    val kind: String? = null,
    val url: String? = null,
    val label: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenDetail(
    val type: String? = null,
    val id: Int? = null,
    val title: String? = null,
    val year: Int? = null,
    val date: String? = null,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val votes: Int? = null,
    val runtime: Int? = null,
    val certification: String? = null,
    val tagline: String? = null,
    val status: String? = null,
    val imdbId: String? = null,
    val countries: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val cast: List<ScreenPerson> = emptyList(),
    val directors: List<ScreenPerson> = emptyList(),
    val recommendations: List<ScreenItem> = emptyList(),
    val collection: ScreenCollection? = null,
    val sources: List<ScreenSource> = emptyList(),
    val seasonList: List<SeasonInfo> = emptyList(),
    val nextEpisode: ScreenNextEpisode? = null,
    val playable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenPerson(
    val id: Int? = null,
    val name: String? = null,
    val role: String? = null,
    val photo: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenCollection(
    val id: Int? = null,
    val name: String? = null,
    val backdrop: String? = null,
    val parts: List<ScreenItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenNextEpisode(
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val date: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonInfo(
    val number: Int? = null,
    val name: String? = null,
    val episodes: Int? = null,
    val year: Int? = null,
    val poster: String? = null,
    val playable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonResponse(
    val showId: Int? = null,
    val number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster: String? = null,
    val episodes: List<SeasonEpisode> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonEpisode(
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val overview: String? = null,
    val still: String? = null,
    val runtime: Int? = null,
    val date: String? = null,
    val rating: Double? = null,
    val playable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubsList(
    val files: List<SubFile> = emptyList(),
    val pending: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubFile(
    val id: String? = null,
    val lang: String? = null,
    val label: String? = null,
    val forced: Boolean? = null,
    val hi: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartnerTitlesResponse(
    val data: List<PartnerTitle> = emptyList(),
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val hasMore: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartnerTitle(
    val anilistId: Int? = null,
    val malId: Int? = null,
    val title: String? = null,
    val nativeTitle: String? = null,
    val format: String? = null,
    val status: String? = null,
    val year: Int? = null,
    val season: String? = null,
    val score: Double? = null,
    val rating: String? = null,
    val durationMinutes: Int? = null,
    val episodes: PartnerEpisodeCounts? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val adult: Boolean? = null,
    val synopsis: String? = null,
    val poster: String? = null,
    val banner: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartnerEpisodeCounts(
    val total: Int? = null,
    val sub: Int? = null,
    val dub: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartnerSeriesResponse(
    val data: PartnerSeries? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartnerSeries(
    val anilistId: Int? = null,
    val malId: Int? = null,
    val title: String? = null,
    val nativeTitle: String? = null,
    val format: String? = null,
    val status: String? = null,
    val year: Int? = null,
    val season: String? = null,
    val score: Double? = null,
    val rating: String? = null,
    val durationMinutes: Int? = null,
    val episodes: PartnerEpisodeCounts? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val adult: Boolean? = null,
    val synopsis: String? = null,
    val poster: String? = null,
    val banner: String? = null,
    val episodeList: List<PartnerEpisode> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartnerEpisode(
    val number: Int? = null,
    val title: String? = null,
    val runtimeSeconds: Int? = null,
    val aired: String? = null,
    val thumbnail: String? = null,
    val available: PartnerAvailability? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartnerAvailability(
    val sub: Boolean? = null,
    val dub: Boolean? = null,
    val subHard: Boolean? = null,
    val dubHard: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartnerRecentResponse(
    val data: List<PartnerRecentItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartnerRecentItem(
    val anilistId: Int? = null,
    val title: String? = null,
    val poster: String? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val sub: Boolean? = null,
    val dub: Boolean? = null,
    val adult: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BetaServerResponse(
    val available: Boolean? = null,
    val origin: String? = null,
    val path: String? = null,
    val episodes: List<Int> = emptyList(),
    val sub: Boolean? = null,
    val dub: Boolean? = null
)
