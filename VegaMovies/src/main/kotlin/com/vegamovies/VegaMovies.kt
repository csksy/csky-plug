package com.vegamovies

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

@JsonIgnoreProperties(ignoreUnknown = true)
data class VegaLinkData(
    val groups: List<VegaGroup> = emptyList(),
    val season: Int? = null,
    val episode: Int? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VegaGroup(
        val label: String = "",
        val urls: List<String> = emptyList()
    )
}

class VegaMovies : MainAPI() {
    override var mainUrl = "https://new1.vegamovies.lease"
    override var name = "VegaMovies"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"

    // rows load one by one so low end devices don't stall on the request burst
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 120L

    private val pageRows by lazy { VegaSites.rows() }

    override val mainPage: List<MainPageData>
        get() = pageRows.map { MainPageData(it.title, "${it.site.id}:${it.path}") }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (siteId, path) = request.data.split(":", limit = 2).let {
            it[0] to it.getOrElse(1) { "" }
        }
        val site = VegaSites.ALL.firstOrNull { it.id == siteId } ?: return emptyPage(request.name)
        val cards = VegaSites.catalogPage(this, site, path, page)
            ?: return emptyPage(request.name)
        return newHomePageResponse(request.name, cards, hasNext = cards.isNotEmpty())
    }

    private fun emptyPage(name: String): HomePageResponse =
        newHomePageResponse(name, emptyList<SearchResponse>(), hasNext = false)

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return null
        val api = this
        val merged = coroutineScope {
            VegaSites.ALL.map { site ->
                async(Dispatchers.IO) { VegaSites.tsSearch(api, site, query, 1) }
            }.flatMap { it.await() }
        }
        return merged.distinctBy { it.url }.ifEmpty { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        val site = VegaSites.siteOf(url) ?: return null
        val res = VegaNet.retry(attempts = 2) {
            try {
                val r = com.lagradost.cloudstream3.app.get(
                    url,
                    headers = VegaNet.browserHeaders("${site.base}/"),
                    timeout = 25L
                )
                if (r.code == 200) r.text else null
            } catch (_: Exception) {
                null
            }
        } ?: return null
        val detail = VegaSites.parseDetail(res, url) ?: return null

        val score = detail.imdbScore?.let { Score.from10(it) }

        if (detail.isSeries && (detail.seasons.isNotEmpty() || looksSeries(url, detail.title))) {
            val episodes = buildEpisodes(detail)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(detail.title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = detail.poster
                    this.year = detail.year
                    this.plot = detail.plot
                    this.score = score
                    this.tags = detail.infoTags
                }
            }
            if (detail.movieGroups.isEmpty()) return null
        }

        val groups = detail.movieGroups.map { g ->
            VegaLinkData.VegaGroup(g.label, g.links.map { it.second })
        }
        if (groups.isEmpty()) return null
        return newMovieLoadResponse(detail.title, url, TvType.Movie, VegaLinkData(groups).toJson()) {
            this.posterUrl = detail.poster
            this.year = detail.year
            this.plot = detail.plot
            this.score = score
            this.tags = detail.infoTags
        }
    }

    private fun looksSeries(url: String, title: String): Boolean {
        return url.contains("season", true) || VegaSites.looksLikeSeries(title)
    }

    // episode lists only exist behind the nexdrive hop, one probe per season learns them
    private suspend fun buildEpisodes(detail: VegaSites.Detail): List<com.lagradost.cloudstream3.Episode> {
        val seasons = detail.seasons.ifEmpty {
            listOf(
                VegaSites.SeasonBlock(detail.title, 1, detail.movieGroups.map {
                    VegaSites.QualityGroup(it.label, it.links.toMutableList())
                }.toMutableList())
            )
        }
        return coroutineScope {
            val gate = Semaphore(3)
            seasons.map { season ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val groups = season.groups.map { g ->
                            VegaLinkData.VegaGroup(g.label, g.links.map { it.second })
                        }
                        if (groups.isEmpty()) return@withPermit emptyList()

                        val probeUrl = groups.asSequence()
                            .flatMap { it.urls.asSequence() }
                            .firstOrNull()
                        val probe = probeUrl?.let {
                            withTimeoutOrNull(12_000L) { VegaSources.fetchDrivePage(it) }
                        }
                        val numbers = probe?.episodes?.keys?.sorted()

                        if (numbers.isNullOrEmpty()) {
                            val data = VegaLinkData(groups, season.number, null)
                            listOf(
                                newEpisode(data.toJson()) {
                                    this.season = season.number
                                    this.episode = 1
                                    this.name = "Season ${season.number} Pack"
                                }
                            )
                        } else {
                            numbers.map { n ->
                                val data = VegaLinkData(groups, season.number, n)
                                newEpisode(data.toJson()) {
                                    this.season = season.number
                                    this.episode = n
                                    this.name = "Episode $n"
                                }
                            }
                        }
                    }
                }
            }.flatMap { it.await() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = try {
            parseJson<VegaLinkData>(data)
        } catch (_: Exception) {
            return false
        }
        VegaSources.resetSession()
        val groups = parsed.groups
        if (groups.isEmpty()) return false

        coroutineScope {
            val gate = Semaphore(3)
            groups.forEach { group ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val quality = VegaNet.qualityOf(group.label)
                        for (url in group.urls) {
                            try {
                                val page = VegaSources.fetchDrivePage(url) ?: continue
                                val targets = if (parsed.episode != null) {
                                    page.episodes[parsed.episode]
                                        ?: page.servers.takeIf { page.episodes.isEmpty() }
                                } else {
                                    page.servers
                                } ?: continue
                                targets.forEach { (label, href) ->
                                    val streams = VegaSources.resolveAll(label, href)
                                    VegaSources.emit(name, quality, streams, callback)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        }
        return true
    }
}
