package com.laddu100

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListResponse(@JsonProperty("data") val data: AniListData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListData(
    @JsonProperty("Page") val page: AniListPage? = null,
    @JsonProperty("Media") val media: AniListMedia? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListPage(@JsonProperty("media") val media: List<AniListMedia>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("idMal") val idMal: Int? = null,
    @JsonProperty("title") val title: AniListTitle? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("nextAiringEpisode") val nextAiringEpisode: AniListNextAiring? = null,
    @JsonProperty("streamingEpisodes") val streamingEpisodes: List<AniListStreamingEpisode>? = null
) {
    val displayTitle: String get() = title?.english ?: title?.romaji ?: title?.native ?: ""
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListTitle(
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("native") val native: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListCoverImage(
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("extraLarge") val extraLarge: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListNextAiring(
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("airingAt") val airingAt: Long? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListStreamingEpisode(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroInfoResponse(
    @JsonProperty("media") val media: AniListMedia? = null,
    @JsonProperty("tmdb") val tmdb: MiruroTmdb? = null,
    @JsonProperty("tvdb") val tvdb: MiruroTvdb? = null,
    @JsonProperty("mappings") val mappings: MiruroMappings? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroTmdb(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("movie") val movie: MiruroTmdbMovie? = null,
    @JsonProperty("episodes") val episodes: List<MiruroTmdbEpisode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroTmdbMovie(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroTmdbEpisode(
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("airDate") val airDate: String? = null,
    @JsonProperty("duration") val duration: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroTvdb(
    @JsonProperty("episodes") val episodes: List<MiruroTvdbEpisode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroTvdbEpisode(
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("aired") val aired: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroMappings(
    @JsonProperty("episodeOffset") val episodeOffset: Int? = null,
    @JsonProperty("tmdbOffset") val tmdbOffset: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisodesResponse(
    @JsonProperty("providers") val providers: Map<String, MiruroProviderEpisodes>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroProviderEpisodes(
    @JsonProperty("episodes") val episodes: MiruroEpisodeCategories? = null
)

// the api's category names are misleading: "sub" entries are played back as
// hardsub encodes, "ssub" entries are clean video plus external subtitle
// tracks, "dub" is self explanatory. the episode lists themselves only ever
// carry sub and dub, ssub exists purely as a sources-query category
@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisodeCategories(
    @JsonProperty("sub") val sub: List<MiruroEpisode>? = null,
    @JsonProperty("ssub") val ssub: List<MiruroEpisode>? = null,
    @JsonProperty("dub") val dub: List<MiruroEpisode>? = null
)

// providers send both "filler" and "isFiller" spellings, and declaring two
// kotlin properties for them makes jackson's bean introspection blow up on
// the generated isFiller() accessor, so one field with an alias is used
@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisode(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("filler") @JsonAlias("isFiller") val filler: Boolean? = null,
    @JsonProperty("uncensored") val uncensored: Boolean? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("airDate") val airDate: String? = null
)

// shape mirrors what the site itself reads from the config pipe endpoint;
// it drives provider visibility, category resolution and embed detection
@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroConfigWrapper(
    @JsonProperty("providers") val providers: Map<String, MiruroProviderConfig>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroProviderConfig(
    @JsonProperty("capabilities") val capabilities: MiruroCapabilities? = null,
    @JsonProperty("parent") val parent: String? = null,
    @JsonProperty("relationship") val relationship: String? = null,
    @JsonProperty("visible") val visible: Boolean? = null,
    @JsonProperty("player") val player: String? = null,
    @JsonProperty("cors") val cors: Boolean? = null,
    @JsonProperty("fallback") val fallback: Int? = null,
    @JsonProperty("variantOrder") val variantOrder: List<String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroCapabilities(
    @JsonProperty("sub") val sub: Boolean? = null,
    @JsonProperty("ssub") val ssub: Boolean? = null,
    @JsonProperty("download") val download: Boolean? = null,
    @JsonProperty("skip_times") val skipTimes: Boolean? = null,
    @JsonProperty("thumbnails") val thumbnails: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroSourcesResponse(
    @JsonProperty("streams") val streams: List<MiruroStream>? = null,
    @JsonProperty("subtitles") val subtitles: List<MiruroSubtitle>? = null,
    @JsonProperty("captions") val captions: List<MiruroSubtitle>? = null,
    @JsonProperty("download") val download: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroStream(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("resolution") val resolution: MiruroResolution? = null,
    @JsonProperty("fansub") val fansub: String? = null,
    @JsonProperty("referer") val referer: String? = null
) {
    val streamUrl: String? get() = url?.takeIf { it.isNotBlank() }

    val isHls: Boolean get() = type == "hls" || type == "application/x-mpegurl"

    val isMp4: Boolean get() = type == "mp4"

    val isEmbed: Boolean get() = type == "embed" || type == "iframe"

    val qualityLabel: String
        get() = quality?.takeIf { it.isNotBlank() }
            ?: label?.takeIf { it.isNotBlank() }
            ?: resolution?.height?.toString()?.plus("p")
            ?: "Auto"

    val height: Int? get() = resolution?.height
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroResolution(
    @JsonProperty("width") val width: Int? = null,
    @JsonProperty("height") val height: Int? = null
)

// providers disagree on field names: bonk sends file/label/kind/language while
// the rest send url/name/lang, so both spellings are accepted
@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroSubtitle(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("language") val language: String? = null
) {
    val subtitleUrl: String? get() = (url ?: file)?.takeIf { it.isNotBlank() }

    val subtitleLabel: String
        get() = (label ?: name ?: lang ?: language)?.trim().takeIf { !it.isNullOrEmpty() } ?: "English"
}
