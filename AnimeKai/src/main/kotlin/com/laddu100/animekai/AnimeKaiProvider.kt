package com.laddu100.animekai

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeKaiProvider : MainAPI() {
    override var mainUrl = "https://animekai.ro"
    override var name = "AnimeKai"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val ajaxHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/plain, */*"
    )

    override val mainPage = mainPageOf(
        "sort=latest-updated" to "Recently Updated",
        "sort=latest-added" to "Newly Added",
        "sort=most-viewed" to "Most Viewed",
        "sort=score" to "Top Rated",
        "term_type%5B%5D=Movie&sort=most-viewed" to "Movies",
        "status%5B%5D=currently-airing&sort=latest-updated" to "Currently Airing"
    )

    private fun tvTypeFromLabel(label: String?): TvType = when (label?.trim()?.uppercase()) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA", "ONA", "SPECIAL", "TV_SPECIAL", "TV SPECIAL", "MUSIC" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst(".poster")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst(".title")?.text()?.trim() ?: return null
        // catalog links live under /anime but playback lives under /watch
        val watchUrl = href.replace("$mainUrl/anime/", "$mainUrl/watch/")
        val subCount = selectFirst(".info .sub")?.text()?.trim()?.toIntOrNull()
        val dubCount = selectFirst(".info .dub")?.text()?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, watchUrl, tvTypeFromLabel(selectFirst(".type")?.text())) {
            posterUrl = selectFirst(".poster img")?.attr("src") ?: ""
            addDubStatus(
                dubExist = dubCount != null && dubCount > 0,
                subExist = subCount != null && subCount > 0,
                dubEpisodes = dubCount,
                subEpisodes = subCount
            )
        }
    }

    private fun parseListing(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        return doc.select(".aitem").mapNotNull { it.toSearchResult() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/filter?${request.data}&page=$page"
        val home = try {
            parseListing(app.get(url, referer = "$mainUrl/home").text)
        } catch (_: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filter?keyword=${URLEncoder.encode(query, "UTF-8")}"
        return try {
            parseListing(app.get(url, referer = "$mainUrl/home").text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun metaText(doc: org.jsoup.nodes.Document, label: String): String? {
        val row = doc.selectFirst(".detail div:containsOwn($label:)") ?: return null
        return row.selectFirst("span")?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            app.get(url, referer = "$mainUrl/home").document
        } catch (_: Exception) {
            return null
        }

        val titleEl = doc.selectFirst("h1.title .d-title") ?: return null
        val title = titleEl.text().trim()
        if (title.isEmpty()) return null
        val jpTitle = titleEl.attr("data-jp").takeIf { it.isNotBlank() }

        val posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst(".desc .content")?.text()?.trim()
        val background = doc.selectFirst(".player-bg")?.attr("data-art")?.takeIf { it.isNotBlank() }

        val year = metaText(doc, "Premiered")?.let { Regex("""(19\d{2}|20\d{2})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val tags = doc.select(".detail div:containsOwn(Genres:) span a").map { it.text().trim() }.filter { it.isNotEmpty() }
        val tvType = tvTypeFromLabel(metaText(doc, "Type"))
        val showStatus = when (metaText(doc, "Status")?.lowercase()) {
            "currently airing" -> ShowStatus.Ongoing
            "finished airing" -> ShowStatus.Completed
            else -> null
        }
        val score10 = metaText(doc, "MAL")?.toDoubleOrNull()

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        val seenEp = mutableSetOf<Int>()

        for (ep in doc.select(".eplist a[data-id]")) {
            val epNum = ep.attr("data-num").toIntOrNull() ?: continue
            if (!seenEp.add(epNum)) continue

            val epUrl = ep.attr("href").takeIf { it.isNotBlank() } ?: continue
            val hasSub = ep.attr("data-sub") == "1"
            val hasDub = ep.attr("data-dub") == "1"
            if (!hasSub && !hasDub) continue

            // the range list repeats episodes across pages, only the entry for
            // the current range carries the title so fall back to the number
            val epName = ep.parent()?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }

            if (hasSub) {
                subEpisodes.add(newEpisode("$epUrl|sub") {
                    this.episode = epNum
                    this.name = epName
                })
            }
            if (hasDub) {
                dubEpisodes.add(newEpisode("$epUrl|dub") {
                    this.episode = epNum
                    this.name = epName
                })
            }
        }

        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) return null

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = background
            this.year = year
            this.plot = plot
            this.tags = tags
            this.showStatus = showStatus
            if (jpTitle != null) this.japName = jpTitle
            score10?.let { this.score = Score.from10(it.toString()) }
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private data class ServerEntry(val linkId: String, val name: String, val type: String)

    private data class ServerResponse(
        val status: Any? = null,
        val result: ServerResult? = null
    )

    private data class ServerResult(
        val url: String? = null,
        val type: String? = null
    )

    private fun serversFor(doc: org.jsoup.nodes.Document, dubOrSub: String): List<ServerEntry> {
        val wanted = if (dubOrSub == "dub") listOf("dub") else listOf("sub", "hsub")
        val out = mutableListOf<ServerEntry>()
        for (section in doc.select(".server-items.type")) {
            val sectionType = section.attr("data-type") ?: continue
            if (sectionType !in wanted) continue
            for (li in section.select("li[data-link-id]")) {
                val linkId = li.attr("data-link-id").takeIf { it.isNotBlank() } ?: continue
                out.add(ServerEntry(linkId, li.text().trim(), sectionType))
            }
        }
        return out
    }

    private suspend fun embedUrlFor(linkId: String, referer: String): String? {
        return try {
            app.get(
                "$mainUrl/ajax/server?get=$linkId",
                referer = referer,
                headers = ajaxHeaders
            ).parsed<ServerResponse>().result?.url?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val parts = data.split("|")
        if (parts.size < 2) return@coroutineScope false
        val epUrl = fixUrl(parts[0])
        val dubOrSub = parts[1]

        val doc = try {
            app.get(epUrl, referer = "$mainUrl/home").document
        } catch (_: Exception) {
            return@coroutineScope false
        }

        val servers = serversFor(doc, dubOrSub)
        if (servers.isEmpty()) return@coroutineScope false

        // the same embed can sit behind two server buttons, fetch once
        val embeds = mutableListOf<Pair<String, String>>()
        val seenEmbeds = mutableSetOf<String>()
        val deferredEmbeds = servers.map { server ->
            async {
                val embed = embedUrlFor(server.linkId, epUrl) ?: return@async
                val name = server.name.ifBlank { "Server" }
                synchronized(seenEmbeds) {
                    if (seenEmbeds.add(embed)) {
                        embeds.add(embed to name)
                    }
                }
            }
        }
        deferredEmbeds.awaitAll()
        if (embeds.isEmpty()) return@coroutineScope false

        var found = false
        val seenStreamUrls = mutableSetOf<String>()
        embeds.map { (embed, serverName) ->
            async {
                try {
                    when {
                        embed.contains("megavid") -> {
                            MegaVidResolver.resolveAll(embed, serverName, name, subtitleCallback) { link ->
                                synchronized(seenStreamUrls) {
                                    if (seenStreamUrls.add(link.url)) {
                                        found = true
                                    }
                                }
                                callback(link)
                            }
                        }
                        embed.contains("megaplay") -> {
                            val stream = MegaPlayResolver.resolveStream(embed, epUrl) ?: return@async
                            MegaPlayResolver.emitLinks(
                                name,
                                "$serverName (MegaPlay)",
                                stream.m3u8,
                                embed,
                                stream.subtitles,
                                subtitleCallback
                            ) { link ->
                                synchronized(seenStreamUrls) {
                                    if (seenStreamUrls.add(link.url)) {
                                        found = true
                                    }
                                }
                                callback(link)
                            }
                        }
                        else -> loadExtractor(embed, epUrl, subtitleCallback) { link ->
                            synchronized(seenStreamUrls) {
                                if (seenStreamUrls.add(link.url)) {
                                    found = true
                                }
                            }
                            callback(link)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }.awaitAll()

        found
    }
}
