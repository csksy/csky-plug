package com.laddu100.raghavanime
import com.lagradost.api.Log

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

class RaghavAniNami : MainAPI() {
    override var mainUrl = "https://www.aninami.site"
    override var name = "AniNami"
    override val hasMainPage = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiHeaders = mapOf(
        "Accept" to "application/json",
        "Referer" to "$mainUrl/"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("results") val results: EpisodesResultData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResultData(
        @JsonProperty("providers") val providers: Map<String, ProviderData>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ProviderData(
        @JsonProperty("episodes") val episodes: EpisodeCategories? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeCategories(
        @JsonProperty("sub") val sub: List<EpisodeItem>? = null,
        @JsonProperty("dub") val dub: List<EpisodeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("title") val title: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("results") val results: StreamResultData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamResultData(
        @JsonProperty("streams") val streams: List<Stream>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Stream(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("referer") val referer: String? = null,
        @JsonProperty("server") val server: String? = null
    )

    private data class AniNamiEntry(
        val provider: String,
        val anilistId: String,
        val audioType: String,
        val slug: String,
        val watchUrl: String
    )

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("aninami") ?: mainUrl
        Log.d("RaghavAnime", "[AniNami] load url=$url")
        val anilistId = Regex("""/anime/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        if (anilistId == null) {
            Log.d("RaghavAnime", "[AniNami] load: no anilist id in url")
            return null
        }

        val epsText = try {
            app.get("$mainUrl/api/episodes/$anilistId", headers = apiHeaders).textLarge
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniNami] episodes fetch anilistId=$anilistId failed: ${e.message}")
            return null
        }
        Log.d("RaghavAnime", "[AniNami] episodes anilistId=$anilistId ok, length=${epsText.length}")
        val providers = try {
            parseJson<EpisodesResponse>(epsText).results?.providers ?: emptyMap()
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniNami] episodes parse anilistId=$anilistId failed: ${e.message}")
            return null
        }
        Log.d("RaghavAnime", "[AniNami] anilistId=$anilistId providers=${providers.size}")


        val subIdsByNumber = sortedMapOf<Int, MutableList<String>>()
        val dubIdsByNumber = sortedMapOf<Int, MutableList<String>>()

        for ((provName, prov) in providers) {
            Log.d("RaghavAnime", "[AniNami] provider $provName: sub=${prov.episodes?.sub?.size ?: 0} dub=${prov.episodes?.dub?.size ?: 0}")
            try {
                prov.episodes?.sub?.forEach { ep ->
                    val num = ep.number ?: return@forEach
                    val id = ep.id ?: return@forEach
                    subIdsByNumber.getOrPut(num) { mutableListOf() }.add(id)
                }
                prov.episodes?.dub?.forEach { ep ->
                    val num = ep.number ?: return@forEach
                    val id = ep.id ?: return@forEach
                    dubIdsByNumber.getOrPut(num) { mutableListOf() }.add(id)
                }
            } catch (e: Throwable) { Log.e("RaghavAnime", "AniNami: ${e.message}") }
        }


        Log.d("RaghavAnime", "[AniNami] anilistId=$anilistId collected ${subIdsByNumber.size} sub / ${dubIdsByNumber.size} dub episode numbers")

        val subEpisodes = subIdsByNumber.map { (num, ids) ->
            newEpisode("sub|${ids.joinToString(";;")}") {
                this.episode = num
                this.name = "Episode $num"
            }
        }
        val dubEpisodes = dubIdsByNumber.map { (num, ids) ->
            newEpisode("dub|${ids.joinToString(";;")}") {
                this.episode = num
                this.name = "Episode $num"
            }
        }

        Log.d("RaghavAnime", "[AniNami] load anilistId=$anilistId done: subEps=${subEpisodes.size} dubEps=${dubEpisodes.size}")
        return newAnimeLoadResponse("AniNami", url, TvType.Anime) {
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("RaghavAnime", "[AniNami] loadLinks data=${data.take(80)}")
        val pipeIdx = data.indexOf("|")
        if (pipeIdx < 0) {
            Log.d("RaghavAnime", "[AniNami] loadLinks: no '|' in data")
            return false
        }
        val requestedAudio = data.substring(0, pipeIdx).substringAfterLast("/")
        val epIds = data.substring(pipeIdx + 1).split(";;").filter { it.isNotEmpty() }
        if (epIds.isEmpty()) {
            Log.d("RaghavAnime", "[AniNami] loadLinks: no episode ids for audio=$requestedAudio")
            return false
        }
        Log.d("RaghavAnime", "[AniNami] loadLinks audio=$requestedAudio epIds=${epIds.size}")


        val seenUrls = ConcurrentHashMap.newKeySet<String>()

        val entries = epIds.mapNotNull { epId ->
            val parts = epId.split("/")
            if (parts.size < 5 || parts[0] != "watch") {
                Log.d("RaghavAnime", "[AniNami] skip malformed epId: ${epId.take(60)}")
                return@mapNotNull null
            }
            val provider = parts[1]
            val anilistId = parts[2]
            val audioType = parts[3]
            val slug = parts.drop(4).joinToString("/")
            AniNamiEntry(
                provider = provider,
                anilistId = anilistId,
                audioType = audioType,
                slug = slug,
                watchUrl = "$mainUrl/api/watch/$provider/$anilistId/$audioType/$slug"
            )
        }

        val found = coroutineScope {
            entries.map { entry ->
                async { processEntry(entry, seenUrls, subtitleCallback, callback) }
            }.awaitAll().any { it }
        }

        Log.d("RaghavAnime", "[AniNami] loadLinks done found=$found uniqueUrls=${seenUrls.size}")
        return found
    }

    private suspend fun processEntry(
        entry: AniNamiEntry,
        seenUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val provider = entry.provider
        Log.d("RaghavAnime", "[AniNami] epId provider=$provider anilistId=${entry.anilistId} audio=${entry.audioType}")
        if (provider.isEmpty() || entry.slug.isEmpty()) return false

        val watchUrl = entry.watchUrl
        Log.d("RaghavAnime", "[AniNami] fetching streams: $watchUrl")
        val streamsText = try {
            app.get(watchUrl, headers = apiHeaders).text
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniNami] watch fetch failed provider=$provider: ${e.message}")
            return false
        }
        val streams = try {
            parseJson<StreamResponse>(streamsText).results?.streams
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniNami] streams parse failed provider=$provider: ${e.message}")
            return false
        }
        if (streams == null) {
            Log.d("RaghavAnime", "[AniNami] no streams for provider=$provider (body length=${streamsText.length})")
            return false
        }
        Log.d("RaghavAnime", "[AniNami] provider=$provider streams=${streams.size}")

        var found = false
        for (stream in streams) {
            val streamUrl = stream.url ?: continue
            if (streamUrl.isBlank() || !seenUrls.add(streamUrl)) continue
            val referer = stream.referer?.takeIf { it.isNotBlank() } ?: "$mainUrl/"
            val qualityLabel = stream.quality?.takeIf { it.isNotBlank() } ?: "Auto"
            val serverName = stream.server?.takeIf { it.isNotBlank() }
            val label = if (serverName != null) "AniNami $serverName $qualityLabel" else "AniNami $qualityLabel"

            when (stream.type?.lowercase()) {
                "hls" -> {
                    Log.d("RaghavAnime", "[AniNami] hls link $label: ${streamUrl.take(80)}")
                    callback.invoke(
                        newExtractorLink(label, label, streamUrl, ExtractorLinkType.M3U8) {
                            this.quality = parseQuality(stream.quality)
                            this.headers = mapOf("Referer" to referer)
                        }
                    )
                    found = true
                }
                "embed" -> {
                    try {
                        Log.d("RaghavAnime", "[AniNami] embed extractor: ${streamUrl.take(80)}")
                        // fast paths for the dood-style hosts the generic extractors
                        // need 20-45s on (direct m3u8 scan + jsunpacker, same as AniDao)
                        val fastResolved = resolveEmbedFast(streamUrl, referer, label, parseQuality(stream.quality), callback)
                        if (fastResolved) {
                            found = true
                        } else {
                            loadExtractor(streamUrl, referer, subtitleCallback, callback)
                            found = true
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e("RaghavAnime", "[AniNami] embed extractor failed: ${e.message}")
                    }
                }
                else -> {
                    try {
                        Log.d("RaghavAnime", "[AniNami] type=${stream.type} extractor: ${streamUrl.take(80)}")
                        loadExtractor(streamUrl, referer, subtitleCallback, callback)
                        found = true
                    } catch (e: Exception) {
                        Log.e("RaghavAnime", "[AniNami] extractor failed: ${e.message}")
                    }
                }
            }
        }
        return found
    }

    private val m3u8Pattern = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")

    /**
     * Fast embed resolution for hosts that generic extractors are extremely slow
     * on (otakuhg/otakuvid took 20-45s each in the wild). Same approach AniDao
     * uses: raw m3u8 scan, with a jsunpacker fallback for packed players.
     */
    private suspend fun resolveEmbedFast(
        embedUrl: String,
        referer: String,
        label: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val handled = embedUrl.contains("vivibebe.site") || embedUrl.contains("bibiemb.xyz") ||
                embedUrl.contains("otakuhg.site") || embedUrl.contains("otakuvid.online")
            if (!handled) return false

            Log.d("RaghavAnime", "[AniNami] fast embed path for ${embedUrl.take(60)}")
            val html = app.get(embedUrl, headers = mapOf("Referer" to referer)).text
            val m3u8 = m3u8Pattern.find(html)?.value
                ?: JsPacker.parseAndUnpack(html)?.let { m3u8Pattern.find(it)?.value }
            if (m3u8 != null) {
                Log.d("RaghavAnime", "[AniNami] fast embed path m3u8 found: ${m3u8.take(80)}")
                callback.invoke(
                    newExtractorLink(label, label, m3u8, ExtractorLinkType.M3U8) {
                        this.quality = quality
                        this.headers = mapOf("Referer" to embedUrl)
                    }
                )
                true
            } else {
                Log.d("RaghavAnime", "[AniNami] fast embed path found no m3u8 for ${embedUrl.take(60)}")
                false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.d("RaghavAnime", "[AniNami] fast embed path failed for ${embedUrl.take(60)}: ${e.message}")
            false
        }
    }

    private fun parseQuality(q: String?): Int {
        if (q.isNullOrBlank() || q == "auto" || q == "Hls") return Qualities.Unknown.value
        val h = Regex("(\\d{3,4})").find(q)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Qualities.Unknown.value
        return when {
            h >= 1080 -> Qualities.P1080.value
            h >= 720 -> Qualities.P720.value
            h >= 480 -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}
