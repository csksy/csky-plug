package com.laddu100.animex

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AnimeXProvider : MainAPI() {

    override var mainUrl = AnimeXApi.MAIN_URL
    override var name = "AnimeX"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "popular" to "Most Popular",
        "updated" to "Recently Updated",
        "movies" to "Popular Movies",
        "upcoming" to "Upcoming"
    )

    private data class EpisodeRef(
        val slug: String,
        val ep: Int,
        val lang: String,
        val anilistId: Int?,
        val urlSlug: String
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val (items, hasNext) = AnimeXApi.catalog(request.data, page)
        return newHomePageResponse(
            request.name,
            items.mapNotNull { it.toSearchResponse() },
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return AnimeXApi.search(query, 1).mapNotNull { it.toSearchResponse() }
    }

    private fun CatalogItem.toSearchResponse(): SearchResponse? {
        val slug = id ?: return null
        val displayTitle = displayTitle() ?: return null
        return newAnimeSearchResponse(displayTitle, "$mainUrl/anime/$slug") {
            this.posterUrl = coverImage?.best()
            this.year = seasonYear
            this.otherName = titleRomaji?.takeIf { it != displayTitle }
            addDubStatus(
                dubExist = (dubCount ?: 0) > 0,
                subExist = (subCount ?: 0) > 0
            )
            averageScore?.let { score = Score.from100(it.toDouble()) }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.removePrefix("$mainUrl/anime/").removePrefix("$mainUrl/").removePrefix("/")
        val anime = AnimeXApi.animeDetail(slug) ?: return null
        val title = anime.titleEnglish?.takeIf { it.isNotBlank() }
            ?: anime.titleRomaji?.takeIf { it.isNotBlank() } ?: return null
        val type = when (anime.format) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        val showStatus = when (anime.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val eps = AnimeXApi.episodes(slug)
        val urlSlug = buildUrlSlug(
            anime.titleEnglish ?: anime.titleRomaji ?: title,
            anime.anilistId ?: 0
        )
        val refBase = EpisodeRef(
            slug = slug,
            ep = 0,
            lang = "sub",
            anilistId = anime.anilistId,
            urlSlug = urlSlug
        )

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        for (ep in eps) {
            val num = ep.number ?: continue
            val hasSub = ep.hasSub ?: true
            val hasDub = ep.hasDub ?: false
            if (hasSub) subEps.add(ep.toEpisode(refBase, num, "sub"))
            if (hasDub) dubEps.add(ep.toEpisode(refBase, num, "dub"))
        }
        if (subEps.isEmpty() && dubEps.isEmpty()) {
            // Episode-list API failed: still split sub/dub from the GraphQL counters
            // so the Sub/Dub selector keeps working. Dub-only anime must not get
            // phantom sub entries, so guard the sub list on subCount (defaulting
            // to sub when both counters are missing/zero).
            val total = anime.episodeCount ?: 1
            val nums = (1..total).toList().ifEmpty { listOf(1) }
            val hasDubTrack = (anime.dubCount ?: 0) > 0
            val hasSubTrack = (anime.subCount ?: 0) > 0 || !hasDubTrack
            for (num in nums) {
                if (hasSubTrack) {
                    subEps.add(refBase.copy(ep = num, lang = "sub").toFallbackEpisode())
                }
            }
            if (hasDubTrack) {
                for (num in nums) {
                    dubEps.add(refBase.copy(ep = num, lang = "dub").toFallbackEpisode())
                }
            }
        } else {
            // Some anime list dub availability only on the anime-level counters; make sure
            // the Dub tab exists whenever the site reports any dub episodes.
            val siteHasDub = (anime.dubCount ?: 0) > 0
            if (siteHasDub && dubEps.isEmpty() && subEps.isNotEmpty()) {
                for (ep in subEps) {
                    val num = ep.episode ?: continue
                    dubEps.add(refBase.copy(ep = num, lang = "dub").toFallbackEpisode())
                }
            }
        }

        // CloudStream hides the SUB/DUB selector for movie types (isMovieType covers
        // AnimeMovie), so present sub+dub movies as a 1-episode series to keep the
        // dropdown - same trick AniKuro/Anistream use. Sub-only movies stay movies.
        val finalType = if (type == TvType.AnimeMovie && dubEps.isNotEmpty()) TvType.Anime else type

        return newAnimeLoadResponse(title, "$mainUrl/anime/$slug", finalType) {
            this.engName = anime.titleEnglish
            this.japName = anime.titleRomaji
            this.posterUrl = anime.coverImage?.best()
            this.backgroundPosterUrl = anime.bannerImage
            this.year = anime.seasonYear
            this.showStatus = showStatus
            this.plot = anime.description?.let(::stripHtml)
            this.tags = anime.genres ?: emptyList()
            this.duration = anime.duration
            this.score = anime.averageScore?.let { Score.from100(it.toDouble()) }
            addAniListId(anime.anilistId ?: 0)
            addMalId(anime.malId ?: 0)
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    private fun EpisodeEntry.toEpisode(base: EpisodeRef, num: Int, lang: String): Episode {
        val ref = base.copy(ep = num, lang = lang)
        val epTitle = titles?.display()?.takeIf { it.isNotBlank() }
        return newEpisode(ref.toJson()) {
            this.name = if (isFiller == true && epTitle != null) {
                "$epTitle (Filler)"
            } else epTitle
            this.episode = num
            this.description = description?.takeIf { it.isNotBlank() }?.let(::stripHtml)
            this.posterUrl = img?.takeIf { it.startsWith("http") }
            parseAirDate(airDateUtc)?.let { addDate(it) }
        }
    }

    private fun EpisodeRef.toFallbackEpisode(): Episode =
        newEpisode(toJson()) {
            this.episode = ep
        }

    private fun buildUrlSlug(title: String, anilistId: Int): String {
        val slug = title.lowercase().trim()
            .replace(Regex("[^a-z0-9]+"), "-")
            .replace(Regex("^-+|-+$"), "")
        return "$slug-$anilistId"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = try {
            parseJson<EpisodeRef>(data)
        } catch (e: Exception) {
            null
        } ?: return false
        if (ref.ep <= 0) return false
        val wantDub = ref.lang == "dub"
        val type = if (wantDub) "dub" else "sub"

        val any = java.util.concurrent.atomic.AtomicBoolean(false)
        val seenLinks = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        val seenSubs = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

        suspend fun emitLink(
            label: String,
            url: String,
            headers: Map<String, String>?,
            quality: Int?,
            linkType: ExtractorLinkType = ExtractorLinkType.M3U8
        ) {
            if (!url.startsWith("http")) return
            if (!seenLinks.add(url)) return
            any.set(true)
            callback.invoke(
                newExtractorLink(label, label, url, type = linkType) {
                    quality?.let { this.quality = it }
                    headers?.let { this.headers = it }
                }
            )
        }

        coroutineScope {
            val anmxJob = async {
                val servers = AnimeXApi.servers(ref.slug, ref.ep) ?: return@async
                val providers = (if (wantDub) servers.dubProviders else servers.subProviders)
                    ?.filter { !it.id.isNullOrBlank() }
                    ?: return@async
                providers.map { provider ->
                    async {
                        val providerId = provider.id!!
                        val resp = AnimeXApi.sources(ref.slug, ref.ep, type, providerId)
                            ?: return@async
                        val src = resp.sources?.firstOrNull { !it.url.isNullOrBlank() }
                            ?: return@async
                        val rawUrl = src.url!!.let { u ->
                            var v = u.trim()
                            while (v.contains(":///")) v = v.replace(":///", "://")
                            v
                        }
                        if (!rawUrl.startsWith("http")) return@async

                        val finalUrl = UwuProxy.transformProviderUrl(rawUrl, providerId, resp.headers)

                        val hardsub = provider.isHardsub
                        val baseName = buildString {
                            append(providerId.replaceFirstChar { it.uppercase() })
                            if (hardsub) {
                                if (wantDub) append(" \u2022 Hardsub Dub")
                                else append(" \u2022 Hardsub")
                            } else if (wantDub) {
                                append(" \u2022 Dub")
                            }
                        }

                        val qualities = UwuProxy.expandQualities(finalUrl)
                        for (q in qualities) {
                            val qualLabel = when {
                                q.height != null -> "${q.height}p"
                                q.label != null -> q.label
                                else -> "Auto"
                            }
                            emitLink(
                                "AnimeX \u2022 $baseName \u2022 $qualLabel",
                                q.url,
                                null,
                                q.height
                            )
                        }

                        for (track in resp.tracks.orEmpty()) {
                            if (!track.isCaptions) continue
                            val trackUrl = track.url?.trim() ?: continue
                            if (!trackUrl.startsWith("http")) continue
                            val subUrl = UwuProxy.transformSubtitleUrl(trackUrl, providerId, resp.headers)
                            if (!subUrl.startsWith("http")) continue
                            if (!seenSubs.add(subUrl)) continue
                            val label = track.label?.takeIf { it.isNotBlank() }
                                ?: track.lang?.takeIf { it.isNotBlank() } ?: "English"
                            subtitleCallback(newSubtitleFile(label, subUrl) {})
                        }
                    }
                }.forEach { it.join() }
            }

            val zenJob = async {
                if (wantDub) return@async
                val zen = AnimeXApi.zenEmbed(ref.urlSlug, ref.ep) ?: return@async
                val res = FlixResolver.resolve(zen.playerUrl, "${AnimeXApi.MAIN_URL}/") ?: return@async
                val proxyMaster = FlixProxy.registerMaster(res.m3u8, res.masterContent, res.pkKey)
                    ?: return@async
                val hasEnglishAudio = res.masterContent.contains("""LANGUAGE="en"""") ||
                    res.masterContent.contains("NAME=\"English\"")
                val hasOtherAudio = Regex("""TYPE=AUDIO[^\n]*LANGUAGE="(?!en)[^"]*"""")
                    .containsMatchIn(res.masterContent) ||
                    (res.masterContent.contains("TYPE=AUDIO") && !hasEnglishAudio)
                val lang = when {
                    hasEnglishAudio && !hasOtherAudio -> "dub"
                    else -> "sub"
                }
                if (lang != "sub") return@async
                val url = "$proxyMaster?lang=sub"
                if (seenLinks.add(url)) {
                    any.set(true)
                    val height = Regex("""RESOLUTION=\d+x(\d+)""")
                        .findAll(res.masterContent)
                        .mapNotNull { it.groupValues[1].toIntOrNull() }
                        .maxOrNull()
                    callback.invoke(
                        newExtractorLink(
                            "AnimeX \u2022 Zen (Flix) \u2022 ${height?.let { "${it}p" } ?: "Auto"}",
                            "AnimeX \u2022 Zen (Flix)",
                            url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            height?.let { this.quality = it }
                            this.headers = mapOf("Referer" to "${AnimeXApi.FLIX_EMBED_BASE}/")
                        }
                    )
                }
                for (sub in res.subtitles) {
                    if (!seenSubs.add(sub.url)) continue
                    val name = sub.language ?: "English"
                    val withExt = sub.format?.let { "$name ($it)" } ?: name
                    subtitleCallback(newSubtitleFile(withExt, sub.url) {})
                }
            }

            val kotoJob = async {
                val anilistId = ref.anilistId ?: return@async
                val koto = AnimeXApi.kotoStream(anilistId, ref.ep, type) ?: return@async
                val m3u8 = koto.m3u8
                val qualities = UwuProxy.expandQualities(
                    m3u8,
                    mapOf(
                        "User-Agent" to AnimeXApi.USER_AGENT,
                        "Referer" to "${AnimeXApi.MEGAPLAY}/"
                    )
                )
                val kotoName = if (wantDub) "Koto \u2022 Dub" else "Koto"
                for (q in qualities) {
                    val qualLabel = when {
                        q.height != null -> "${q.height}p"
                        q.label != null -> q.label
                        else -> "Auto"
                    }
                    emitLink(
                        "AnimeX \u2022 $kotoName \u2022 $qualLabel",
                        q.url,
                        mapOf("Referer" to "${AnimeXApi.MEGAPLAY}/"),
                        q.height
                    )
                }
                if (qualities.isEmpty()) {
                    emitLink(
                        "AnimeX \u2022 $kotoName",
                        m3u8,
                        mapOf("Referer" to "${AnimeXApi.MEGAPLAY}/"),
                        null
                    )
                }
                for ((label, trackUrl) in koto.tracks) {
                    val fixed = AnimeXApi.fixUrl(trackUrl)
                    if (!fixed.startsWith("http")) continue
                    val proxied = if (fixed.contains("nexabloom")) {
                        UwuProxy.proxyUrl(fixed, "${AnimeXApi.MEGAPLAY}/")
                    } else fixed
                    if (!seenSubs.add(proxied)) continue
                    subtitleCallback(newSubtitleFile(label, proxied) {})
                }
            }

            anmxJob.join()
            zenJob.join()
            kotoJob.join()
        }

        return any.get()
    }

    private fun parseAirDate(raw: String?): java.util.Date? {
        if (raw.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(raw.take(19))
        } catch (e: Exception) {
            null
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()
}
