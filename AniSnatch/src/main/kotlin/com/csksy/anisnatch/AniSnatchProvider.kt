package com.csksy.anisnatch

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class AniSnatch : MainAPI() {

    override var mainUrl = "https://anisnatch.to"
    override var name = "AniSnatch"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // validating every link takes a while when half the servers are down
    override val loadLinksTimeoutMs: Long? = 3 * 60_000L

    private val referer = "https://anisnatch.to/"

    // zh, fil and pt-BR fall back to bengali server side, so they stay out
    private val subtitleLanguages = linkedMapOf(
        "hi" to "Hindi",
        "es" to "Spanish",
        "ar" to "Arabic",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "tr" to "Turkish",
        "id" to "Indonesian",
        "vi" to "Vietnamese",
        "th" to "Thai",
        "pl" to "Polish",
        "nl" to "Dutch",
        "bn" to "Bengali",
        "ja" to "Japanese",
        "ko" to "Korean"
    )

    override val mainPage = mainPageOf(
        "sort=popularity" to "Trending",
        "sort=recently" to "Recently Added",
        "sort=score" to "Top Rated",
        "type=1&sort=popularity" to "Popular Movies",
        "status=2" to "Currently Airing",
        "language=2" to "Dubbed"
    )

    private fun tvTypeOf(type: String?): TvType = when (type?.lowercase()) {
        "movie" -> TvType.AnimeMovie
        "ova", "ona" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun searchItems(arr: JSONArray?): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        arr ?: return out
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val id = a.optInt("id", 0)
            if (id <= 0) continue
            val title = a.optString("title_en").ifBlank { a.optString("title") }
            if (title.isBlank()) continue
            val type = a.optString("type")
            out.add(
                newAnimeSearchResponse(title, id.toString(), tvTypeOf(type)) {
                    this.posterUrl = a.optString("picture").takeIf { it.isNotBlank() }
                    this.year = if (a.optInt("airedint", 0) > 0) a.optInt("airedint") / 10000 else null
                }
            )
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val query = AniSnatchCrypto.encodeFilterQuery(request.data)
        val items = searchItems(AniSnatchApi.filter(query, page))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> =
        searchItems(AniSnatchApi.search(query, 1))

    private fun episodeData(id: Int, ep: Int, dub: Boolean): String = "$id|$ep|${if (dub) 1 else 0}"

    private class EpisodeData(val id: Int, val ep: Int, val dub: Boolean)

    private fun parseEpisodeData(raw: String): EpisodeData? {
        val parts = raw.split("|")
        if (parts.size < 3) return null
        val id = parts[0].toIntOrNull() ?: return null
        val ep = parts[1].toIntOrNull() ?: return null
        return EpisodeData(id, ep, parts[2] == "1")
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.toIntOrNull() ?: return null
        val meta = AniSnatchApi.anime(id) ?: return null

        val title = meta.optString("title_en").ifBlank { meta.optString("title") }
        if (title.isBlank()) return null
        val info = meta.optJSONObject("info") ?: JSONObject()
        val type = info.optString("type")
        val tvType = tvTypeOf(type)

        val episodes = AniSnatchApi.episodes(id)
        val isSingle = type.equals("Movie", true) || episodes.size <= 1

        val subEps = ArrayList<Episode>()
        val dubEps = ArrayList<Episode>()
        if (episodes.isEmpty()) {
            subEps.add(newEpisode(episodeData(id, 1, false)) {
                this.episode = 1; this.name = "Movie"
            })
            if (meta.optInt("dub", 0) == 1) {
                dubEps.add(newEpisode(episodeData(id, 1, true)) {
                    this.episode = 1; this.name = "Movie"
                })
            }
        } else for (e in episodes) {
            val epTitle = e.title?.takeIf { it.isNotBlank() }
                ?.replace(Regex("\\s*·\\s*\\d+P$"), "")
                ?: "Episode ${e.number}"
            if (e.hasSub || !e.hasDub) {
                subEps.add(newEpisode(episodeData(id, e.number, false)) {
                    this.episode = e.number
                    this.name = if (isSingle) title else epTitle
                    this.posterUrl = e.image
                    this.description = buildString {
                        if (e.filler) append("Filler episode")
                        e.airDate?.let { d -> if (isNotEmpty()) append(" | "); append(d) }
                    }.takeIf { it.isNotBlank() }
                })
            }
            if (e.hasDub) {
                dubEps.add(newEpisode(episodeData(id, e.number, true)) {
                    this.episode = e.number
                    this.name = if (isSingle) title else epTitle
                    this.posterUrl = e.image
                })
            }
        }

        val scoreRaw = info.optDouble("score", 0.0)
        val airedint = info.optInt("airedint", 0)
        val genres = ArrayList<String>()
        info.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) genres.add(arr.optString(i))
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = meta.optString("picture").takeIf { it.isNotBlank() }
            this.backgroundPosterUrl = meta.optString("banner").takeIf { it.isNotBlank() }
            this.plot = info.optString("synopsis").takeIf { it.isNotBlank() }
            this.tags = genres
            this.year = if (airedint > 0) airedint / 10000 else null
            if (scoreRaw > 0) this.score = Score.from10(scoreRaw.toFloat())
            addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    private fun labelFor(entry: AniSnatchApi.ServerEntry, category: String): String {
        val notes = entry.notes.joinToString(" ").lowercase()
        return when {
            notes.contains("hindi") -> "${entry.title} (Hindi Dub)"
            category == "sub" -> "${entry.title} [Hard]"
            else -> entry.title
        }
    }

    private fun mp4Quality(label: String?): Int? =
        label?.replace("p", "")?.trim()?.toIntOrNull()

    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http")) return ref
        val schemeIdx = base.indexOf("://")
        if (schemeIdx < 0) return ref
        val host = base.substring(schemeIdx + 3).substringBefore("/")
        val origin = base.substring(0, schemeIdx + 3) + host
        return if (ref.startsWith("/")) origin + ref
        else base.substringBeforeLast("/") + "/" + ref
    }

    // a chunk of the site's backends hand out playlists whose segments live on
    // an ad cdn with expiring signatures - they die mid stream, so never emit them
    private fun isGarbageHost(url: String): Boolean {
        val host = try {
            java.net.URI(url).host ?: return true
        } catch (e: Exception) {
            return true
        }
        return host.endsWith("tiktokcdn.com") || host.endsWith("tiktokv.us")
    }

    private class Variant(val quality: Int?, val url: String)

    private fun parseMaster(body: String, masterUrl: String): List<Variant> {
        val lines = body.lines()
        val out = ArrayList<Variant>()
        var pendingHeight: Int? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                pendingHeight = res?.groupValues?.get(2)?.toIntOrNull()
            } else if (line.isNotEmpty() && !line.startsWith("#") && pendingHeight != null) {
                out.add(Variant(pendingHeight, resolveUrl(masterUrl, line)))
                pendingHeight = null
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                // media playlist without stream-inf entries
                out.add(Variant(null, resolveUrl(masterUrl, line)))
            }
        }
        return out
    }

    private fun firstSegment(body: String, playlistUrl: String): String? {
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isNotEmpty() && !line.startsWith("#")) {
                return resolveUrl(playlistUrl, line)
            }
        }
        return null
    }

    // only links whose media actually serves get emitted, dead playlists are dropped
    private fun validateHls(
        masterUrl: String,
        playHeaders: Map<String, String>
    ): List<Variant>? {
        val master = AniSnatchApi.fetchText(masterUrl, playHeaders) ?: return null
        if (!master.contains("#EXTM3U")) return null

        val variants = parseMaster(master, masterUrl)
        val probeTarget = variants.maxByOrNull { it.quality ?: 0 }?.url ?: masterUrl
        val playlist = AniSnatchApi.fetchText(probeTarget, playHeaders) ?: return null
        if (!playlist.contains("#EXTM3U")) return null

        val seg = firstSegment(playlist, probeTarget) ?: return null
        if (isGarbageHost(seg)) return null
        if (AniSnatchApi.probeRange(seg, playHeaders) !in 200..399) return null

        return if (variants.isEmpty()) listOf(Variant(null, masterUrl)) else variants
    }

    private suspend fun emitHls(
        label: String,
        masterUrl: String,
        playHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val variants = validateHls(masterUrl, playHeaders) ?: return false
        for (v in variants) {
            val quality = v.quality ?: Qualities.Unknown.value
            callback.invoke(
                newExtractorLink(
                    "AniSnatch",
                    if (v.quality != null) "$label ${v.quality}p" else label,
                    v.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = playHeaders
                    this.quality = quality
                }
            )
        }
        return true
    }

    private fun buildDashMpd(rawJson: String): Pair<String, Int>? {
        return try {
            val obj = JSONObject(rawJson)
            val videos = obj.optJSONArray("videos") ?: return null
            if (videos.length() == 0) return null
            val audios = obj.optJSONArray("audios") ?: JSONArray()
            val duration = obj.optDouble("duration", 0.0)

            fun esc(s: String) = s.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")

            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
            sb.append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" ")
            sb.append("mediaPresentationDuration=\"PT").append(duration).append("S\" ")
            sb.append("minBufferTime=\"PT1.5S\"><Period id=\"0\" start=\"PT0S\">")
            sb.append("<AdaptationSet mimeType=\"video/mp4\" segmentAlignment=\"true\">")
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val seg = v.optJSONObject("segment_base") ?: continue
                sb.append("<Representation id=\"").append(v.optInt("height")).append("p_")
                sb.append(v.optInt("bandwidth")).append("\" codecs=\"").append(v.optString("codecs"))
                sb.append("\" bandwidth=\"").append(v.optInt("bandwidth")).append("\" width=\"")
                sb.append(v.optInt("width")).append("\" height=\"").append(v.optInt("height"))
                sb.append("\" frameRate=\"").append(v.optDouble("frame_rate", 0.0)).append("\">")
                sb.append("<BaseURL>").append(esc(v.optString("url"))).append("</BaseURL>")
                sb.append("<SegmentBase indexRange=\"").append(seg.optString("index_range"))
                sb.append("\"><Initialization range=\"").append(seg.optString("range"))
                sb.append("\"/></SegmentBase></Representation>")
            }
            sb.append("</AdaptationSet>")
            sb.append("<AdaptationSet mimeType=\"audio/mp4\" lang=\"und\" segmentAlignment=\"true\">")
            for (i in 0 until audios.length()) {
                val a = audios.optJSONObject(i) ?: continue
                val seg = a.optJSONObject("segment_base") ?: continue
                sb.append("<Representation id=\"audio_").append(a.optInt("bandwidth"))
                sb.append("\" codecs=\"").append(a.optString("codecs")).append("\" bandwidth=\"")
                sb.append(a.optInt("bandwidth")).append("\">")
                sb.append("<BaseURL>").append(esc(a.optString("url"))).append("</BaseURL>")
                sb.append("<SegmentBase indexRange=\"").append(seg.optString("index_range"))
                sb.append("\"><Initialization range=\"").append(seg.optString("range"))
                sb.append("\"/></SegmentBase></Representation>")
            }
            sb.append("</AdaptationSet></Period></MPD>")

            val mpd = Base64.encodeToString(sb.toString().toByteArray(), Base64.NO_WRAP)
            "data:application/dash+xml;base64,$mpd" to audios.length()
        } catch (e: Exception) {
            Log.d("AniSnatch", "dash build failed: ${e.message}")
            null
        }
    }

    private suspend fun emitSubtitles(
        subtitles: List<Pair<String, String>>,
        subHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val seen = HashSet<String>()
        for ((label, file) in subtitles) {
            if (!seen.add(file)) continue
            subtitleCallback.invoke(newSubtitleFile(label, file) { this.headers = subHeaders })
        }

        // swapping the suffix machine translates the default track into any language
        val raw = subtitles.firstOrNull { it.second.contains("translate-raw.vtt") }?.second
            ?: return
        val existing = subtitles.joinToString(" ").lowercase()
        for ((code, lang) in subtitleLanguages) {
            if (existing.contains(lang.lowercase())) continue
            val url = raw.replace("translate-raw.vtt", "translate-$code.vtt")
            if (seen.add(url)) {
                subtitleCallback.invoke(newSubtitleFile(lang, url) { this.headers = subHeaders })
            }
        }
    }

    private val deadUntil = ConcurrentHashMap<String, Long>()

    private suspend fun emitServer(
        entry: AniSnatchApi.ServerEntry,
        category: String,
        isDubTab: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mark = deadUntil[entry.source]
        if (mark != null && System.currentTimeMillis() < mark) return false

        val page = AniSnatchApi.videoPage(entry.source)
        if (page == null || (page.sources.isEmpty() && page.dashRaw == null)) {
            deadUntil[entry.source] = System.currentTimeMillis() + 3 * 60_000L
            return false
        }

        val playHeaders = mapOf(
            "User-Agent" to AniSnatchApi.USER_AGENT,
            "Referer" to referer
        )
        // subtitle tracks are per episode, hand them over even when the video is dead
        emitSubtitles(page.subtitles, playHeaders, subtitleCallback)

        val label = labelFor(entry, category)
        var found = false
        var validated = false

        if (page.dashRaw != null) {
            val built = buildDashMpd(page.dashRaw)
            if (built != null) {
                val (mpdUri, audioCount) = built
                val multiAudio = audioCount > 1
                val firstVideo = Regex("\"url\"\\s*:\\s*\"([^\"]*)\"")
                    .find(page.dashRaw)?.groupValues?.get(1)
                val dashAlive = firstVideo == null ||
                    (AniSnatchApi.probeRange(firstVideo, playHeaders) in 200..399)
                validated = validated || dashAlive
                if (dashAlive && (!isDubTab || multiAudio)) {
                    val name = if (multiAudio) "$label (Multi Audio)" else label
                    callback.invoke(
                        newExtractorLink("AniSnatch", name, mpdUri, type = ExtractorLinkType.DASH) {
                            this.headers = playHeaders
                        }
                    )
                    found = true
                }
            }
        }

        for (src in page.sources) {
            when {
                src.type.equals("mp4", true) -> {
                    if (AniSnatchApi.probeRange(src.url, playHeaders) in 200..399) {
                        validated = true
                        callback.invoke(
                            newExtractorLink(
                                "AniSnatch",
                                listOfNotNull(label, src.label).joinToString(" "),
                                src.url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.headers = playHeaders
                                this.quality = mp4Quality(src.label) ?: Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                }
                else -> {
                    if (emitHls(label, src.url, playHeaders, callback)) {
                        found = true
                        validated = true
                    }
                }
            }
        }

        if (!validated) {
            deadUntil[entry.source] = System.currentTimeMillis() + 3 * 60_000L
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = try {
            parseEpisodeData(data) ?: return false
        } catch (e: Exception) {
            return false
        }

        val servers = try {
            AniSnatchApi.servers(ep.id, ep.ep)
        } catch (e: Exception) {
            Log.e("AniSnatch", "server list failed: ${e.message}")
            return false
        }

        val entries = ArrayList<Pair<AniSnatchApi.ServerEntry, String>>()
        if (ep.dub) {
            servers["dub"]?.forEach { entries.add(it to "dub") }
            // the multi-audio dash source sits in the sub category on the site
            servers["sub"].orEmpty()
                .filter { it.server == "allmanga-ak" }
                .forEach { entries.add(it to "sub") }
        } else {
            servers["soft-sub"]?.forEach { entries.add(it to "soft-sub") }
            servers["sub"]?.forEach { entries.add(it to "sub") }
        }

        var found = false
        // small parallel batches keep cloudflare from rate-challenging the app
        coroutineScope {
            entries.chunked(5).forEach { batch ->
                batch.map { (entry, category) ->
                    async(Dispatchers.IO) {
                        try {
                            if (emitServer(entry, category, ep.dub, subtitleCallback, callback)) {
                                found = true
                            }
                        } catch (e: Exception) {
                            Log.d("AniSnatch", "${entry.title} failed: ${e.message}")
                        }
                    }
                }.awaitAll()
            }
        }
        return found
    }
}
