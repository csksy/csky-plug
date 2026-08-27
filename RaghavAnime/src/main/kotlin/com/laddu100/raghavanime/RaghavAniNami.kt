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
        @JsonProperty("referer") val referer: String? = null
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


        var found = false
        val seenUrls = mutableSetOf<String>()

        for (epId in epIds) {
            val parts = epId.split("/")
            if (parts.size < 5 || parts[0] != "watch") {
                Log.d("RaghavAnime", "[AniNami] skip malformed epId: ${epId.take(60)}")
                continue
            }
            val provider = parts[1]
            val anilistId = parts[2]
            val audioType = parts[3]
            val slug = parts.drop(4).joinToString("/")
            Log.d("RaghavAnime", "[AniNami] epId provider=$provider anilistId=$anilistId audio=$audioType")
            if (provider.isEmpty() || slug.isEmpty()) continue

            val watchUrl = "$mainUrl/api/watch/$provider/$anilistId/$audioType/$slug"
            Log.d("RaghavAnime", "[AniNami] fetching streams: $watchUrl")
            val streamsText = try {
                app.get(watchUrl, headers = apiHeaders).text
            } catch (e: Exception) {
                Log.e("RaghavAnime", "[AniNami] watch fetch failed provider=$provider: ${e.message}")
                continue
            }
            val streams = try {
                parseJson<StreamResponse>(streamsText).results?.streams
            } catch (e: Exception) {
                Log.e("RaghavAnime", "[AniNami] streams parse failed provider=$provider: ${e.message}")
                continue
            }
            if (streams == null) {
                Log.d("RaghavAnime", "[AniNami] no streams for provider=$provider (body length=${streamsText.length})")
                continue
            }
            Log.d("RaghavAnime", "[AniNami] provider=$provider streams=${streams.size}")


            for (stream in streams) {
                val streamUrl = stream.url ?: continue
                if (streamUrl.isBlank() || !seenUrls.add(streamUrl)) continue
                val referer = stream.referer?.takeIf { it.isNotBlank() } ?: "$mainUrl/"
                val qualityLabel = stream.quality?.takeIf { it.isNotBlank() } ?: "Auto"
                val label = "AniNami $qualityLabel"

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
                            loadExtractor(streamUrl, referer, subtitleCallback, callback)
                            found = true
                        } catch (e: Exception) {
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
        }

        Log.d("RaghavAnime", "[AniNami] loadLinks done found=$found uniqueUrls=${seenUrls.size}")
        return found
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
