
package com.laddu100.anidb

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AniDbProvider : MainAPI() {
    override var mainUrl = "https://anidb.app"
    override var name = "AniDB"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )
    override val hasQuickSearch = false

    @Volatile
    private var isUrlLoaded = false

    data class FirebaseConfig(
        @JsonProperty("anidb_url") val anidbUrl: String? = null,
        @JsonProperty("anidb") val anidb: String? = null,
        @JsonProperty("anidb_app") val anidbApp: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get(
                "https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json",
                timeout = 10_000L
            ).text
            val config = parseJson<FirebaseConfig>(response)
            val url = config.anidbUrl ?: config.anidb ?: config.anidbApp
            if (!url.isNullOrBlank()) {
                mainUrl = url.removeSuffix("/")
            }
            isUrlLoaded = true
        } catch (e: Exception) {
            Log.e("AniDB", "Firebase load failed: ${e.message}")
            isUrlLoaded = true
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href").ifBlank { return null }
        val img = this.selectFirst("img") ?: return null
        val title = img.attr("alt").ifBlank { "Unknown" }
        val poster = img.attr("src").ifBlank { null }
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        loadFirebaseUrl()
        val doc = app.get(mainUrl + "/home", timeout = 30_000L).document
        val sections = doc.select("section")
        val rows = mutableListOf<HomePageList>()
        
        for (sec in sections) {
            val title = sec.selectFirst("h2")?.text()?.trim() ?: continue
            val cards = sec.select("a[href*=/anime/]").mapNotNull { it.toSearchResult() }
            if (cards.isNotEmpty()) {
                rows.add(HomePageList(title, cards, isHorizontalImages = true))
            }
        }
        
        return HomePageResponse(rows)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        loadFirebaseUrl()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/browse?q=$encoded", timeout = 30_000L).document
        return doc.select("a[href*=/anime/]").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        loadFirebaseUrl()
        val doc = app.get(url, timeout = 30_000L).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("img[alt=$title]")?.attr("src")
        
        val synopsisH2 = doc.selectFirst("h2:contains(Synopsis)")
        val plot = synopsisH2?.parent()?.selectFirst("p")?.text()
        
        val metadata = mutableMapOf<String, String>()
        doc.select("dt").forEach { dt ->
            val key = dt.text().trim()
            val dd = dt.nextElementSibling()
            if (dd != null && dd.tagName() == "dd") {
                metadata[key] = dd.text().trim()
            }
        }
        
        val animeType = metadata["Type"] ?: "TV"
        val isMovie = animeType.contains("Movie", true) || animeType.contains("Special", true)
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime
        
        val genres = doc.select("a[href*=/genres/]").map { it.text().trim() }.distinct()
        val score = metadata["Score"]?.toFloatOrNull()?.let { Score.from10(it) }
        
        val animeId = url.substringAfterLast("-").trim()
        
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        
        try {
            val epRes = app.get("$mainUrl/api/frontend/anime/$animeId/episodes", timeout = 30_000L).text
            val epData = parseJson<EpisodesResponse>(epRes)
            val episodes = epData.episodes ?: emptyList()
            
            var hasSub = false
            var hasDub = false
            if (episodes.isNotEmpty()) {
                val firstEp = episodes.first()
                val langRes = app.get("$mainUrl/api/frontend/episode/${firstEp.id}/languages", timeout = 30_000L).text
                val langData = parseJson<LanguagesResponse>(langRes)
                langData.languages?.forEach { lang ->
                    if (lang.code == "jpn") hasSub = true
                    if (lang.code == "eng") hasDub = true
                }
            }
            
            episodes.forEach { ep ->
                val epTitle = if (ep.number2 != null && ep.number2 != ep.number) {
                    "Episode ${ep.number}-${ep.number2}"
                } else {
                    "Episode ${ep.number}"
                }
                
                subEpisodes.add(newEpisode("${ep.id}|sub") {
                    this.name = epTitle
                    this.episode = ep.number
                })
                
                if (hasDub) {
                    dubEpisodes.add(newEpisode("${ep.id}|dub") {
                        this.name = epTitle
                        this.episode = ep.number
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("AniDB", "Failed to load episodes: ${e.message}")
        }
        
        val finalType = if (isMovie && dubEpisodes.isNotEmpty()) TvType.Anime else tvType
        
        return newAnimeLoadResponse(title, url, finalType) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.score = score
            this.backgroundPosterUrl = poster
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    data class EpisodeData(
        @JsonProperty("id") val id: Int,
        @JsonProperty("number") val number: Int,
        @JsonProperty("number2") val number2: Int?,
        @JsonProperty("filler") val filler: Boolean?
    )

    data class EpisodesResponse(
        @JsonProperty("episodes") val episodes: List<EpisodeData>?
    )

    data class Language(
        @JsonProperty("code") val code: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("embed_url") val embedUrl: String
    )

    data class LanguagesResponse(
        @JsonProperty("languages") val languages: List<Language>?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadFirebaseUrl()
        val parts = data.split("|")
        if (parts.size < 2) return false
        
        val epId = parts[0]
        val requestedType = parts[1] 
        
        try {
            val langRes = app.get("$mainUrl/api/frontend/episode/$epId/languages", timeout = 30_000L).text
            val langData = parseJson<LanguagesResponse>(langRes)
            val languages = langData.languages ?: return false
            
            val targetLang = if (requestedType == "dub") "eng" else "jpn"
            val lang = languages.find { it.code == targetLang } ?: languages.firstOrNull() ?: return false
            
            val embedUrl = lang.embedUrl
            val embedDoc = app.get(embedUrl, timeout = 30_000L).document
            
            val scripts = embedDoc.select("script")
            var m3u8Url: String? = null
            for (script in scripts) {
                val scriptData = script.data()
                val regex = Regex("""file:\s*'([^']+\.m3u8)'""")
                val match = regex.find(scriptData)
                if (match != null) {
                    m3u8Url = match.groupValues[1]
                    break
                }
            }
            
            if (m3u8Url != null) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name - ${lang.name}",
                        url = m3u8Url,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
                return true
            }
        } catch (e: Exception) {
            Log.e("AniDB", "Failed to load links: ${e.message}")
        }
        
        return false
    }
}
