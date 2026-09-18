package com.radiostorm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

@JsonIgnoreProperties(ignoreUnknown = true)
data class RadioStation(
    val name: String = "",
    val url_resolved: String? = null,
    val url: String? = null,
    val homepage: String? = null,
    val favicon: String? = null,
    val tags: String? = null,
    val country: String? = null,
    val language: String? = null,
    val votes: Int = 0,
    val codec: String? = null,
    val bitrate: Int = 0,
)

class RadioStorm : MainAPI() {
    override var mainUrl = "https://de1.api.radio-browser.info"
    override var name = "RadioStorm"
    override val supportedTypes = setOf(TvType.Music)
    override var lang = "en"
    override val hasMainPage = true

    private val mapper = ObjectMapper()

    // the community api runs on volunteer mirrors, they rotate availability
    private val mirrors = listOf(
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
    )

    private val pageSize = 40

    override val mainPage = mainPageOf(
        Pair("trending", "Trending Worldwide"),
        Pair("popular", "Most Voted"),
        Pair("country:india", "India"),
        Pair("country:united states", "United States"),
        Pair("country:united kingdom", "United Kingdom"),
        Pair("country:japan", "Japan"),
        Pair("country:germany", "Germany"),
        Pair("country:france", "France"),
        Pair("country:brazil", "Brazil"),
        Pair("country:united arab emirates", "UAE"),
        Pair("genre:bollywood", "Bollywood"),
        Pair("genre:pop", "Pop"),
        Pair("genre:rock", "Rock"),
        Pair("genre:hip hop", "Hip Hop"),
        Pair("genre:classical", "Classical"),
        Pair("genre:electronic", "Electronic"),
        Pair("genre:jazz", "Jazz"),
        Pair("genre:news", "News & Talk"),
        Pair("genre:lofi", "Lo-Fi"),
        Pair("language:hindi", "Hindi"),
        Pair("language:english", "English"),
        Pair("language:tamil", "Tamil"),
        Pair("language:telugu", "Telugu"),
        Pair("language:malayalam", "Malayalam"),
        Pair("language:punjabi", "Punjabi"),
        Pair("language:marathi", "Marathi"),
        Pair("language:bengali", "Bengali"),
        Pair("language:arabic", "Arabic"),
        Pair("language:spanish", "Spanish"),
    )

    private fun buildQuery(data: String): String {
        val parts = data.split(":", limit = 2)
        val kind = parts[0]
        val value = parts.getOrElse(1) { "" }.replace(" ", "%20")
        return when (kind) {
            "trending" -> "order=clickcount&reverse=true"
            "popular" -> "order=votes&reverse=true"
            "country" -> "country=$value&order=clickcount&reverse=true"
            "genre" -> "tagList=$value&order=clickcount&reverse=true"
            else -> "language=$value&order=clickcount&reverse=true"
        }
    }

    private suspend fun fetchStations(query: String, offset: Int): List<RadioStation> {
        for (base in mirrors) {
            val body = runCatching {
                app.get("$base/json/stations/search?limit=$pageSize&offset=$offset&hidebroken=true&$query").text
            }.getOrNull() ?: continue
            val stations = runCatching {
                mapper.readValue(body, object : TypeReference<List<RadioStation>>() {})
            }.getOrNull() ?: continue
            return stations
        }
        return emptyList()
    }

    // everything the detail page needs travels inside the data url so load
    // never needs a second api roundtrip that could miss
    private fun packStation(station: RadioStation): String? {
        val stream = station.url_resolved ?: station.url ?: return null
        val fields = listOf(
            "station",
            station.name,
            stream,
            station.codec.orEmpty(),
            station.bitrate.toString(),
            station.country.orEmpty(),
            station.language.orEmpty(),
            station.homepage.orEmpty(),
            station.favicon.orEmpty(),
            station.tags.orEmpty(),
        )
        return fields.joinToString("|") { java.net.URLEncoder.encode(it, "UTF-8") }
    }

    private fun unpackStation(data: String): RadioStation? {
        val parts = data.split("|")
        if (parts.size < 10 || parts[0] != "station") return null
        fun field(i: Int) = runCatching {
            java.net.URLDecoder.decode(parts[i], "UTF-8")
        }.getOrDefault("")
        return RadioStation(
            name = field(1),
            url_resolved = field(2),
            codec = field(3).ifBlank { null },
            bitrate = field(4).toIntOrNull() ?: 0,
            country = field(5).ifBlank { null },
            language = field(6).ifBlank { null },
            homepage = field(7).ifBlank { null },
            favicon = field(8).ifBlank { null },
            tags = field(9).ifBlank { null },
        )
    }

    private fun toSearchResponse(station: RadioStation): MovieSearchResponse? {
        val packed = packStation(station) ?: return null
        val stationName = station.name.ifBlank { return null }
        return newMovieSearchResponse(
            stationName,
            packed,
            TvType.Music,
        ) {
            this.posterUrl = station.favicon?.takeIf { it.startsWith("http") }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val stations = fetchStations(buildQuery(request.data), (page - 1) * pageSize)
        val items = stations.mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(request.name, items, stations.size >= pageSize)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return fetchStations("name=$encoded", 0).mapNotNull { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val known = unpackStation(url)
        val stream = known?.url_resolved ?: url.takeIf { it.startsWith("http") } ?: return null
        val title = known?.name?.ifBlank { null } ?: stream.substringAfterLast("/").ifBlank { "Radio Station" }
        val info = listOfNotNull(
            known?.codec?.uppercase(),
            known?.bitrate?.takeIf { it > 0 }?.let { "$it kbps" },
            known?.country,
            known?.language?.takeIf { it.isNotBlank() },
        ).joinToString(" - ")

        return newMovieLoadResponse(
            title,
            url,
            TvType.Music,
            url,
        ) {
            this.plot = buildString {
                append("Live radio station.")
                known?.tags?.takeIf { it.isNotBlank() }?.let { append(" $it".replace(",", ", ")) }
                if (info.isNotBlank()) append("\n$info")
            }
            this.tags = known?.tags.orEmpty().split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(6)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val stream = unpackStation(data)?.url_resolved
            ?: data.takeIf { it.startsWith("http") }
            ?: return false
        callback(
            newExtractorLink(name, name, stream, type = ExtractorLinkType.VIDEO) {
                this.referer = ""
            }
        )
        return true
    }
}
