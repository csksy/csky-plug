package com.csksy.anichan

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogEnvelope(
    @JsonProperty("results") val results: List<CatalogItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("titleRomaji") val titleRomaji: String? = null,
    @JsonProperty("titleNative") val titleNative: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("banner") val banner: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("score") val score: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("startDate") val startDate: StartDate? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("selfhost") val selfhost: Selfhost? = null,
    @JsonProperty("isAdult") val isAdult: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StartDate(
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("month") val month: Int? = null,
    @JsonProperty("day") val day: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Selfhost(
    @JsonProperty("cached_eps") val cachedEps: List<Int>? = null,
    @JsonProperty("cached_sub") val cachedSub: List<Int>? = null,
    @JsonProperty("cached_dub") val cachedDub: List<Int>? = null,
    @JsonProperty("count") val count: Int? = null,
    @JsonProperty("total_eps") val totalEps: Int? = null,
    @JsonProperty("ep_titles") val epTitles: Map<String, String>? = null,
    @JsonProperty("ep_meta") val epMeta: Map<String, EpMeta>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpMeta(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("airDate") val airDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("overview") val overview: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WatchInfo(
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("sources") val sources: List<WatchSource>? = null,
    @JsonProperty("dubAvailable") val dubAvailable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WatchSource(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("sub") val sub: Boolean? = null,
    @JsonProperty("dub") val dub: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServersEnvelope(
    @JsonProperty("servers") val servers: List<Server>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Server(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("host") val host: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("rank") val rank: Int? = null,
    @JsonProperty("stream") val stream: String? = null,
    @JsonProperty("embed") val embed: String? = null,
    @JsonProperty("subType") val subType: String? = null,
    @JsonProperty("subtitles") val subtitles: List<Subtitle>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Subtitle(
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("default") val default: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkRace(
    @JsonProperty("winner") val winner: String? = null,
    @JsonProperty("ticket") val ticket: String? = null,
    @JsonProperty("servers") val servers: List<VidhawkServer>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkServer(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("ticket") val ticket: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkPlay(
    @JsonProperty("defaultAudio") val defaultAudio: String? = null,
    @JsonProperty("tracks") val tracks: List<VidhawkTrack>? = null,
    @JsonProperty("captions") val captions: Map<String, List<VidhawkCaption>>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkTrack(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("src") val src: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkCaption(
    @JsonProperty("src") val src: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("lang") val lang: String? = null
)
