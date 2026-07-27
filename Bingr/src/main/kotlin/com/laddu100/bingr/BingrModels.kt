package com.laddu100.bingr

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// =========================================================================
// Search / Trending / Discover responses
// =========================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrSearchResponse(
    @JsonProperty("page") val page: Int = 0,
    @JsonProperty("total_pages") val total_pages: Int = 0,
    @JsonProperty("results") val results: List<BingrSearchResult> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrSearchResult(
    @JsonProperty("id") val id: Long,
    @JsonProperty("type") val type: String = "movie",
    @JsonProperty("title") val title: String,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("backdrop_original") val backdrop_original: String? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("overview") val overview: String? = null,
    // Anime-only fields
    @JsonProperty("idMal") val idMal: Long? = null,
    @JsonProperty("title_native") val title_native: String? = null
)

// =========================================================================
// Detail responses
// =========================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrDetail(
    @JsonProperty("id") val id: Long,
    @JsonProperty("type") val type: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("backdrop_original") val backdrop_original: String? = null,
    @JsonProperty("logo_backdrop") val logo_backdrop: String? = null,
    @JsonProperty("logo") val logo: String? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("runtime") val runtime: Long? = null,
    @JsonProperty("genres") val genres: List<String> = emptyList(),
    @JsonProperty("certification") val certification: String? = null,
    @JsonProperty("imdb_id") val imdb_id: String? = null,
    @JsonProperty("cast") val cast: List<BingrCast> = emptyList(),
    @JsonProperty("seasons") val seasons: List<BingrSeason> = emptyList(),
    @JsonProperty("similars") val similars: List<BingrSearchResult> = emptyList(),
    @JsonProperty("mature") val mature: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrCast(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("name") val name: String,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("photo") val photo: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrSeason(
    @JsonProperty("season") val season: Int,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("episodes") val episodes: Int = 0,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("air_date") val air_date: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrEpisodesResponse(
    @JsonProperty("episodes") val episodes: List<BingrEpisode> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrEpisode(
    @JsonProperty("episode") val episode: Int,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still") val still: String? = null,
    @JsonProperty("air_date") val air_date: String? = null,
    @JsonProperty("rating") val rating: Double? = null
)

// =========================================================================
// Stream responses
// =========================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrStreamResponse(
    @JsonProperty("scraperName") val scraperName: String = "",
    @JsonProperty("sources") val sources: List<BingrSource> = emptyList(),
    @JsonProperty("subtitles") val subtitles: List<BingrSubtitle> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrSource(
    @JsonProperty("url") val url: String,
    @JsonProperty("quality") val quality: String = "HD",
    @JsonProperty("language") val language: String = "Original",
    @JsonProperty("type") val type: String = "application/x-mpegurl",
    @JsonProperty("label") val label: String = "",
    @JsonProperty("name") val name: String = "",
    @JsonProperty("headers") val headers: Map<String, String> = emptyMap(),
    @JsonProperty("isMP4") val isMP4: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrSubtitle(
    @JsonProperty("lang") val lang: String = "und",
    @JsonProperty("label") val label: String = "",
    @JsonProperty("url") val url: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrLanguagesResponse(
    @JsonProperty("sources") val sources: List<BingrLanguageSource> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrLanguageSource(
    @JsonProperty("url") val url: String,
    @JsonProperty("quality") val quality: String = "HD",
    @JsonProperty("label") val label: String = "",
    @JsonProperty("language") val language: String = "Original"
)

// =========================================================================
// Anime-specific responses
// =========================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrAnimeDetail(
    @JsonProperty("id") val id: Long,
    @JsonProperty("idMal") val idMal: Long? = null,
    @JsonProperty("type") val type: String = "anime",
    @JsonProperty("title") val title: String,
    @JsonProperty("title_native") val title_native: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("runtime") val runtime: Long? = null,
    @JsonProperty("genres") val genres: List<String> = emptyList(),
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("studios") val studios: List<BingrStudio> = emptyList(),
    @JsonProperty("cast") val cast: List<BingrCast> = emptyList(),
    @JsonProperty("similars") val similars: List<BingrSearchResult> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BingrStudio(
    @JsonProperty("name") val name: String? = null
)

// Hianime.filmu.in token response
@JsonIgnoreProperties(ignoreUnknown = true)
data class HianimeTokenResponse(
    @JsonProperty("token") val token: String? = null
)

// AnimeSalt / Hikari stream response (from hianime.filmu.in)
@JsonIgnoreProperties(ignoreUnknown = true)
data class HianimeStreamResponse(
    @JsonProperty("streams") val streams: List<HianimeStream> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HianimeStream(
    @JsonProperty("url") val url: String,
    @JsonProperty("proxyUrl") val proxyUrl: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("dubType") val dubType: String? = null,
    @JsonProperty("subtitles") val subtitles: List<HianimeSub> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HianimeSub(
    @JsonProperty("lang") val lang: String = "und",
    @JsonProperty("label") val label: String = "",
    @JsonProperty("url") val url: String
)
