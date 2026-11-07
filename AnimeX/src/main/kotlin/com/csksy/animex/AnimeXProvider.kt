package com.csksy.animex

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
import com.lagradost.cloudstream3.app
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import com.raghav.donation.DonationManager

class AnimeXProvider : MainAPI() {

    override var mainUrl = AnimeXApi.SITE
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "recent" to "Recently Updated",
        "popular" to "All Time Popular",
        "airing" to "Airing",
        "movies" to "Movies"
    )

    private data class EpisodeRef(
        val slug: String,
        val anilistId: Int?,
        val malId: Int?,
        val ep: Int,
        val lang: String
    )

    companion object {
        // the anmx player falls back to these when the servers answer is thin,
        // new ids the site reports are used as-is and only need a display name
        private val KNOWN_ANMX_PROVIDERS = listOf(
            "uwu", "beep", "nero", "yuki", "zuna", "loli", "sora"
        )
        private val PROVIDER_NAMES = mapOf(
            "uwu" to "Uwu",
            "beep" to "Beep",
            "nero" to "Nero",
            "yuki" to "Yuki",
            "zuna" to "Zuna",
            "loli" to "Loli",
            "sora" to "Sora"
        )

        // used when a sources answer carries no headers block of its own
        private val FALLBACK_HEADERS = mapOf(
            "nero" to mapOf("Referer" to "https://animex.one/"),
            "yuki" to mapOf(
                "Origin" to "https://megaplay.buzz",
                "Referer" to "https://megaplay.buzz/"
            ),
            "zuna" to mapOf("Referer" to "https://zokoanime.video/"),
            "loli" to mapOf("Referer" to "https://play.echovideo.ru/"),
            "sora" to mapOf("Referer" to "https://kaa.lt/")
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        DonationManager.checkAndShow()
        val (items, hasNext) = when (request.data) {
            "trending" -> AnimeXApi.trending(page)
            "popular" -> AnimeXApi.popular(page)
            "airing" -> AnimeXApi.airing(page)
            "movies" -> AnimeXApi.movies(page)
            "recent" -> {
                val (list, more) = AnimeXApi.recent(page)
                list.mapNotNull { it.toCatalogItem() } to more
            }
            else -> return null
        }
        return newHomePageResponse(
            request.name,
            items.mapNotNull { it.toSearchResponse() },
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return AnimeXApi.search(query).mapNotNull { it.toSearchResponse() }
    }

    private fun RecentItem.toCatalogItem(): CatalogItem? {
        val id = id ?: return null
        return CatalogItem(
            id = id,
            anilistId = anilistId,
            titleRomaji = titleRomaji,
            titleEnglish = titleEnglish,
            poster = coverImage?.best(),
            format = format,
            genres = genres,
            averageScore = meanScore,
            subCount = if (isSub == true) 1 else 0,
            dubCount = if (isDub == true) 1 else 0
        )
    }

    private fun CatalogItem.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val title = titleEnglish?.takeIf { it.isNotBlank() } ?: titleRomaji?.takeIf { it.isNotBlank() }
        ?: return null
        val type = when (format) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        val dubExists = (dubCount ?: 0) > 0
        val subExists = (subCount ?: 0) > 0 || !dubExists
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", type) {
            this.posterUrl = bestPoster()?.takeIf { it.startsWith("http") }
                ?: bannerImage?.takeIf { it.startsWith("http") }
            this.year = seasonYear
            this.otherName = titleRomaji?.takeIf { it != title }
            addDubStatus(dubExist = dubExists, subExist = subExists)
            averageScore?.let { score = Score.from100(it.toDouble()) }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val urlSlug = url.removePrefix("$mainUrl/").removePrefix("/").removePrefix("anime/")
        var slug = urlSlug
        var anime = AnimeXApi.animeDetail(slug)
        if (anime?.id == null) {
            slug = AnimeXApi.resolveSlug(urlSlug) ?: return null
            anime = AnimeXApi.animeDetail(slug) ?: return null
        }
        val title = anime.titleEnglish?.takeIf { it.isNotBlank() }
            ?: anime.titleRomaji?.takeIf { it.isNotBlank() }
            ?: return null
        val eps = AnimeXApi.episodes(slug)

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        for (ep in eps) {
            val num = ep.number ?: continue
            val hasSub = ep.hasSub ?: true
            val hasDub = ep.hasDub ?: false
            if (hasSub) subEps.add(ep.toEpisode(slug, num, "sub", anime))
            if (hasDub) dubEps.add(ep.toEpisode(slug, num, "dub", anime))
        }
        if (subEps.isEmpty() && dubEps.isEmpty()) {
            eps.forEach { ep ->
                val num = ep.number ?: return@forEach
                subEps.add(ep.toEpisode(slug, num, "sub", anime))
            }
        }

        // the app hides the sub/dub switcher on movie types, so dual audio
        // movies are typed as regular anime to keep both sides reachable
        val type = when {
            anime.format == "MOVIE" && dubEps.isNotEmpty() -> TvType.Anime
            anime.format == "MOVIE" -> TvType.AnimeMovie
            anime.format == "OVA" || anime.format == "ONA" || anime.format == "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        val showStatus = when (anime.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        return newAnimeLoadResponse(title, "$mainUrl/anime/$slug", type) {
            this.engName = anime.titleEnglish
            this.japName = anime.titleRomaji
            this.posterUrl = anime.bestPoster()?.takeIf { it.startsWith("http") }
            this.backgroundPosterUrl = anime.bannerImage?.takeIf { it.startsWith("http") }
                ?: anime.backdropUrl?.takeIf { it.startsWith("http") }
            this.year = anime.seasonYear
            this.showStatus = showStatus
            this.plot = anime.description?.let(::stripHtml)
            this.tags = anime.genres.orEmpty()
            this.score = anime.averageScore?.let { Score.from100(it.toDouble()) }
            addAniListId(anime.anilistId ?: 0)
            addMalId(anime.malId ?: 0)
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    private fun EpisodeList.toEpisode(
        slug: String,
        num: Int,
        lang: String,
        anime: CatalogItem
    ): Episode {
        val ref = EpisodeRef(
            slug = slug,
            anilistId = anime.anilistId,
            malId = anime.malId,
            ep = num,
            lang = lang
        )
        return newEpisode(ref.toJson()) {
            this.name = titles?.best()?.takeIf { it.isNotBlank() }
            this.episode = num
            this.description = description?.takeIf { it.isNotBlank() }?.let(::stripHtml)
            this.posterUrl = img?.takeIf { it.startsWith("http") }
            parseAirDate(airDateUtc)?.let { addDate(it) }
        }
    }

    private fun parseAirDate(raw: String?): kotlin.time.Instant? {
        if (raw.isNullOrBlank()) return null
        return try {
            kotlin.time.Instant.parse(raw.take(20))
        } catch (_: Exception) {
            null
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = try {
            parseJson<EpisodeRef>(data)
        } catch (_: Exception) {
            null
        } ?: return false
        if (ref.ep < 0) return false
        val audio = if (ref.lang == "dub") "dub" else "sub"

        val servers = AnimeXApi.servers(ref.slug, ref.ep)
        val anmxProviders = (if (audio == "dub") servers.dubProviders else servers.subProviders)
            .mapNotNull { it.id }
            .distinct()
            .ifEmpty { KNOWN_ANMX_PROVIDERS }
        val providerTips = (if (audio == "dub") servers.dubProviders else servers.subProviders)
            .mapNotNull { provider -> provider.id?.let { it to (provider.tip ?: "") } }
            .toMap()

        val found = AtomicBoolean(false)

        coroutineScope {
            for (pid in anmxProviders) {
                launch {
                    val set = try {
                        AnimeXApi.sources(ref.slug, ref.ep, audio, pid)
                    } catch (_: Exception) {
                        null
                    } ?: return@launch
                    emitProviderSet(pid, providerTips[pid], set, audio, subtitleCallback, callback)
                    found.set(true)
                }
            }

            launch {
                if (emitAnmxOg(ref, audio, subtitleCallback, callback)) {
                    found.set(true)
                }
            }

            ref.anilistId?.let { anilistId ->
                launch {
                    val embed = "https://megaplay.buzz/stream/ani/$anilistId/${ref.ep}/$audio"
                    val stream = try {
                        MegaPlayResolver.resolveStream(embed, "$mainUrl/")
                    } catch (_: Exception) {
                        null
                    } ?: return@launch
                    val label = "KOTO ${audio.uppercase()}"
                    if (MegaPlayResolver.emitLinks(
                            name, label, stream.m3u8, "https://megaplay.buzz/",
                            stream.subtitles, subtitleCallback, callback
                        )
                    ) {
                        found.set(true)
                    }
                }

                launch {
                    val zoroStream = try {
                        VidCloudResolver.resolveStream(anilistId, ref.malId, ref.ep, audio)
                    } catch (_: Exception) {
                        null
                    } ?: return@launch
                    if (VidCloudResolver.emitLinks(
                            name, "Zoro ${audio.uppercase()}", zoroStream,
                            subtitleCallback, callback
                        )
                    ) {
                        found.set(true)
                    }
                }

                launch {
                    val zenEntries = try {
                        AnimeXApi.zenAccessIds(anilistId, ref.ep)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    for (entry in zenEntries) {
                        // dual audio files serve both tabs, single audio entries
                        // only line up with their own side
                        if (entry.audio != "dual" && entry.audio != audio) continue
                        val file = try {
                            FlixCdnResolver.resolve(entry.accessId)
                        } catch (_: Exception) {
                            null
                        } ?: continue
                        // the subtitle list is timed against the zen file itself,
                        // it rides along only when that file is offered
                        val subs = try {
                            FlixCdnResolver.subtitles(entry.accessId)
                        } catch (_: Exception) {
                            emptyList()
                        }
                        if (subs.isNotEmpty()) {
                            FlixCdnResolver.emitSubtitles(subs, subtitleCallback)
                        }
                        FlixCdnResolver.emit(file, callback)
                        found.set(true)
                    }
                }

                launch {
                    emitDownloadLinks(ref, audio, callback)
                }
            }
        }

        return found.get()
    }

    // the site's own self hosted server, named ANMX OG on both sides. its
    // manifest is minted through the site's player loader and every answer
    // is bound to one episode, so the handshake runs in a fresh player page
    // exactly like navigating to the episode on the site
    private suspend fun emitAnmxOg(
        ref: EpisodeRef,
        audio: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val anilistId = ref.anilistId ?: return false
        val lookup = try {
            AnimeXApi.anmxLookup(anilistId, ref.ep)
        } catch (_: Exception) {
            null
        } ?: return false
        val episodeId = lookup.episode?.episodeId ?: return false
        if (episodeId <= 0) return false
        val raw = AnmxWebView.openManifest(
            episodeId,
            "https://plyr.animex.one/e/${ref.slug}/${ref.ep}?lang=$audio"
        ) ?: return false
        val manifest = try {
            AnimeXApi.parseAnmxManifest(raw)
        } catch (_: Exception) {
            null
        } ?: return false

        // the self hosted cdn turns mobile user agents away with a plain
        // forbidden while every other server of the site accepts them, the
        // desktop ua the loader itself runs under is what it wants
        val playHeaders = mapOf(
            "User-Agent" to AnmxWebView.BRIDGE_UA,
            "Referer" to "https://plyr.animex.one/",
            "Origin" to "https://plyr.animex.one"
        )
        val side = audio.uppercase()
        var emitted = false
        for (source in manifest.source) {
            val src = fixUrl(source.src ?: continue)
            if (!src.startsWith("http")) continue
            // the loader labels each file with its audio side, a both file
            // carries every language as selectable renditions
            val label = source.label?.lowercase(Locale.US) ?: "both"
            if (label != "both" && label != audio) continue
            if (emitStreamLinks(src, "ANMX OG ($side)", playHeaders, callback)) {
                emitted = true
            }
        }
        // a title the self hosted cdn refuses answers with a plain forbidden
        // instead of a playlist, its dead row is dropped the same way the
        // sites own player would fail on it
        if (!emitted) return false

        for (track in manifest.tracks.orEmpty()) {
            val url = fixUrl(track.vttUrl?.takeIf { it.isNotBlank() } ?: track.url ?: continue)
            if (!url.startsWith("http")) continue
            if (url.contains("storyboard") || url.contains("/chapter")) continue
            val isAiDub = track.label?.contains("AI Dub", ignoreCase = true) == true
            val lang = if (isAiDub) "en (AI Dub)" else normalizeLang(track.label) ?: "en"
            subtitleCallback.invoke(
                newSubtitleFile(lang, url) {
                    this.headers = playHeaders
                }
            )
        }
        return true
    }

    // the site's own download sheet offers kiwi mirror rows per quality and
    // audio side, each row resolves to a kwik page that hides a direct file;
    // only the two best qualities of the active side ride along so the sheet
    // never slows the streaming links down
    private suspend fun emitDownloadLinks(
        ref: EpisodeRef,
        audio: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val malId = ref.malId ?: return
        val entries = try {
            AnimeXApi.episodeLinks(malId, ref.ep)
        } catch (_: Exception) {
            emptyList()
        }
        val qualityOf = { entry: DownloadEntry ->
            entry.quality?.takeWhile { it.isDigit() }?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        }
        val picked = entries
            .filter { entry ->
                val tracks = entry.audioTracks.orEmpty()
                entry.id != null && entry.source == "mapper" &&
                    (tracks.isEmpty() || tracks.contains(audio))
            }
            .sortedByDescending(qualityOf)
            .take(2)
        for (entry in picked) {
            val resolved = try {
                AnimeXApi.resolveEpisodeLink(malId, ref.ep, entry.id!!)
            } catch (_: Exception) {
                null
            } ?: continue
            val kwikUrl = resolved.directUrl ?: continue
            val (file, kwikPage) = try {
                KwikResolver.resolve(kwikUrl, resolved.downloadPage ?: "$mainUrl/")
            } catch (_: Exception) {
                null
            } ?: continue
            if (!file.startsWith("http")) continue
            val tracks = entry.audioTracks
            val side = when {
                tracks?.contains("dub") == true && tracks.contains("sub") != true -> "DUB"
                else -> audio.uppercase()
            }
            val quality = entry.quality?.takeWhile { it.isDigit() }?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            val group = entry.releaseGroup?.takeIf { it.isNotBlank() } ?: "Download"
            callback.invoke(
                newExtractorLink(
                    name,
                    "$group $side${entry.quality?.let { " $it" } ?: ""}",
                    file,
                    type = ExtractorLinkType.VIDEO
                ) {
                    quality?.let { this.quality = it }
                    this.headers = mapOf(
                        "User-Agent" to AnimeXApi.USER_AGENT,
                        "Referer" to kwikPage
                    )
                }
            )
        }
    }

    private suspend fun emitProviderSet(
        providerId: String,
        tip: String?,
        set: SourceSet,
        audio: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val providerHeaders = (set.headers?.takeIf { it.isNotEmpty() })
            ?: FALLBACK_HEADERS[providerId].orEmpty()
        val playHeaders = mapOf("User-Agent" to AnimeXApi.USER_AGENT) + providerHeaders
        val providerName = PROVIDER_NAMES[providerId] ?: providerId.uppercase()
        // the site splits its sub providers into softsub and hard sub groups,
        // the burn-in ones say so in their tip line
        val hardsub = tip?.contains("hard sub", ignoreCase = true) == true
        val baseLabel = "ANMX $providerName${if (hardsub) " (Hardsub)" else ""} ${audio.uppercase()}"

        for (stream in set.sources) {
            val url = stream.url ?: continue
            if (!url.startsWith("http")) continue
            emitStreamLinks(url, baseLabel, playHeaders, callback)
        }

        emitSubtitleTracks(set.tracks, playHeaders, subtitleCallback)
    }

    private suspend fun emitStreamLinks(
        url: String,
        label: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val target = fixUrl(url)
        if (!target.startsWith("http")) return false
        val masterText = try {
            app.get(target, headers = headers, timeout = 12_000L).text
        } catch (_: Exception) {
            null
        }

        // an answer that is not a playlist means the cdn refused the url, a
        // dead row is dropped instead of handed to the player to fail on
        if (masterText != null && !target.contains(".mp4") && !masterText.contains("#EXTM3U")) {
            return false
        }

        // a master that selects audio renditions must be handed to the player
        // untouched, a split variant would stream video without sound
        val hasAudioRenditions = masterText?.contains("#EXT-X-MEDIA:") == true
        val variants = if (hasAudioRenditions) {
            emptyList()
        } else {
            masterText?.let { parseVariants(target, it) }.orEmpty()
        }

        if (variants.isNotEmpty()) {
            for (variant in variants) {
                val suffix = variant.quality?.let { "${it}p" } ?: ""
                callback.invoke(
                    newExtractorLink(
                        name,
                        if (suffix.isEmpty()) label else "$label $suffix",
                        variant.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        variant.quality?.let { quality = it }
                        this.headers = headers
                    }
                )
            }
        } else {
            val type = if (target.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            callback.invoke(
                newExtractorLink(name, label, target, type = type) {
                    this.headers = headers
                }
            )
        }
        return true
    }

    private data class VariantEntry(val url: String, val quality: Int?)

    private fun parseVariants(masterUrl: String, masterText: String): List<VariantEntry> {
        val base = masterUrl.substringBefore('?').let { it.substringBeforeLast('/') + "/" }
        val out = mutableListOf<VariantEntry>()
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                val quality = res?.groupValues?.get(2)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    if (uri.isNotEmpty()) {
                        val absolute = if (uri.startsWith("http")) uri else base + uri
                        out.add(VariantEntry(absolute, quality))
                    }
                    i = j
                }
            }
            i++
        }
        return out
    }

    private suspend fun emitSubtitleTracks(
        tracks: List<SourceSet.Track>?,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (tracks.isNullOrEmpty()) return
        val seen = mutableSetOf<String>()
        for (track in tracks) {
            val url = fixUrl(track.url ?: continue)
            if (!url.startsWith("http")) continue
            if (url.contains("thumbnails") || url.contains("/preview/")) continue
            val lang = normalizeLang(track.lang) ?: normalizeLang(track.label) ?: continue
            val key = "$lang|$url"
            if (!seen.add(key)) continue
            subtitleCallback.invoke(
                newSubtitleFile(lang, url) {
                    this.headers = trackHeaders(url, headers)
                }
            )
        }
    }

    // the subtitle mirrors the providers hand out live on the megaplay family
    // and answer with a cloudflare page for any other referer, even the one
    // the sources response itself advertises, so those hosts are pinned to
    // the headers their cdn actually accepts
    private fun trackHeaders(url: String, playHeaders: Map<String, String>): Map<String, String> {
        val host = try {
            java.net.URI(url).host?.lowercase() ?: return playHeaders
        } catch (_: Exception) {
            return playHeaders
        }
        val megaplayCdn = host == "cdn.imgnex.top" ||
            host.endsWith(".mysubsplay.com") ||
            host.endsWith(".lostproject.club")
        if (!megaplayCdn) return playHeaders
        return mapOf(
            "User-Agent" to AnimeXApi.USER_AGENT,
            "Origin" to "https://megaplay.buzz",
            "Referer" to "https://megaplay.buzz/"
        )
    }

    // the sites data occasionally carries hosts with a triple slash or an
    // empty host, the player on the site never sees them because it routes
    // through its own asset proxy, so they are repaired here before use
    private fun fixUrl(raw: String): String {
        val trimmed = raw.trim()
        return trimmed.replace(Regex("^(https?):///+"), "$1://")
    }

    // "spanish (- spanish(latin america))" carries a region variant after the
    // base language, the code keeps both so variants stay distinguishable
    private fun normalizeLang(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.US) ?: return null
        if (value.isBlank()) return null
        val paren = value.indexOf('(')
        val base = if (paren > 0) value.substring(0, paren).trim() else value
        val variant = if (paren > 0) {
            value.substring(paren + 1).removeSuffix(")").trim()
        } else {
            null
        }
        val code = when (base) {
            "english", "eng" -> "en"
            "arabic", "ara" -> "ar"
            "french", "fre", "fra" -> "fr"
            "german", "ger", "deu" -> "de"
            "italian", "ita" -> "it"
            "spanish", "spa" -> "es"
            "portuguese", "por" -> "pt"
            "polish", "pol" -> "pl"
            "russian", "rus" -> "ru"
            "japanese", "jpn" -> "ja"
            "hindi", "hin" -> "hi"
            "tamil", "tam" -> "ta"
            "telugu", "tel" -> "te"
            "malayalam", "mal" -> "ml"
            "bengali", "ben" -> "bn"
            "korean", "kor" -> "ko"
            "chinese", "chi", "zho" -> "zh"
            "turkish", "tur" -> "tr"
            "indonesian", "ind" -> "id"
            "thai" -> "th"
            "vietnamese", "vie" -> "vi"
            "ukrainian", "ukr" -> "uk"
            "dutch", "dut", "nld" -> "nl"
            else -> base.takeIf { it.isNotBlank() }
        } ?: return null
        if (variant == null || variant == base) return code
        val region = variant.substringAfterLast('(').removeSuffix(")").trim()
            .takeIf { it.isNotBlank() } ?: variant
        return "$code (${
            region.replace("spanish", "").replace("portuguese", "").trim().replace(" ", "-")
                .takeIf { it.isNotBlank() } ?: variant
        })"
    }
}
