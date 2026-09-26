package com.laddu100.animekai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class MegaVidSource(
    val provider: String,
    val url: String,
    val isHls: Boolean,
    val subtitles: List<Pair<String, String>>
)

// megavid fronts several upstream cdns behind one /source endpoint, the
// active one is picked with the axsv query param and every one of them can
// fail independently so they are probed before a link goes out
object MegaVidResolver {
    private val mapper = ObjectMapper()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private data class Provider(val id: String, val hardSub: Boolean)

    private fun sourceUrl(embedUrl: String, provider: String?): String {
        val base = embedUrl.trimEnd('/') + "/source"
        return if (provider == null) base else "$base?axsv=$provider"
    }

    private fun parseTracks(root: JsonNode): List<Pair<String, String>> {
        val subs = mutableListOf<Pair<String, String>>()
        val tracks = root.get("tracks") ?: return subs
        if (!tracks.isArray) return subs
        for (element in tracks) {
            val kind = element.get("kind")?.asText() ?: continue
            if (kind != "captions" && kind != "subtitles") continue
            val file = element.get("file")?.asText() ?: continue
            if (file.isBlank()) continue
            subs.add((element.get("label")?.asText() ?: "English") to file)
        }
        return subs
    }

    // the endpoint answers countdown while a copy is still encoding, the web
    // player just retries so we do the same instead of failing the source
    private suspend fun fetchSourceJson(embedUrl: String, provider: String?): JsonNode? {
        repeat(3) {
            val node = try {
                mapper.readTree(
                    app.get(
                        sourceUrl(embedUrl, provider),
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "application/json",
                            "Referer" to "https://animekai.ro/"
                        ),
                        timeout = 15_000L
                    ).text
                )
            } catch (_: Exception) {
                return@repeat
            }
            val status = node.get("status")?.asText()
            if (status == "ok" && node.get("source") != null) return node
            if (status != "countdown") return null
        }
        return null
    }

    private fun parseProviders(root: JsonNode): List<Provider> {
        val out = mutableListOf<Provider>()
        val providers = root.get("providers") ?: return out
        if (!providers.isArray) return out
        for (element in providers) {
            val id = element.get("id")?.asText() ?: continue
            if (id.isBlank()) continue
            out.add(Provider(id, element.get("hard")?.asBoolean() ?: false))
        }
        return out
    }

    // a playlist that comes back as html means the cdn is challenging this
    // network, without the check the player would get an unplayable link
    private suspend fun playlistQualities(url: String): List<Pair<String, Int>> {
        val body = try {
            app.get(
                MegaPlayResolver.signUrl(url),
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://megavid.buzz/"),
                timeout = 15_000L
            ).text
        } catch (_: Exception) {
            return emptyList()
        }
        if (!body.contains("#EXTM3U")) return emptyList()

        val base = url.substringBefore('?').let { it.substringBeforeLast('/') + "/" }
        val out = mutableListOf<Pair<String, Int>>()
        val lines = body.lines()
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
                        out.add(absolute to (quality ?: 0))
                    }
                    i = j
                }
            }
            i++
        }
        // a media playlist has no stream-inf lines, play it as a single link
        if (out.isEmpty()) out.add(url to 0)
        return out
    }

    private suspend fun emit(
        source: String,
        label: String,
        result: MegaVidSource,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://megavid.buzz/")

        if (!result.isHls) {
            callback.invoke(
                newExtractorLink(source, label, result.url, type = ExtractorLinkType.VIDEO) {
                    this.referer = "https://megavid.buzz/"
                    this.headers = playHeaders
                }
            )
            return true
        }

        val qualities = playlistQualities(result.url)
        if (qualities.isEmpty()) return false

        for ((variant, quality) in qualities) {
            val suffix = if (quality > 0) "${quality}p" else ""
            callback.invoke(
                newExtractorLink(
                    source,
                    if (suffix.isEmpty()) label else "$label $suffix",
                    MegaPlayResolver.signUrl(variant),
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://megavid.buzz/"
                    if (quality > 0) this.quality = quality
                    this.headers = playHeaders
                }
            )
        }
        return true
    }

    private fun buildResult(root: JsonNode, providerId: String): MegaVidSource? {
        val url = root.get("source")?.asText() ?: return null
        if (url.isBlank()) return null
        val type = root.get("type")?.asText() ?: "hls"
        val name = root.get("provider")?.asText()?.takeIf { it.isNotBlank() } ?: providerId
        return MegaVidSource(name, url, type == "hls", parseTracks(root))
    }

    // resolves the default source plus every alternate provider the endpoint
    // advertises, all in parallel since each is an independent cdn
    suspend fun resolveAll(
        embedUrl: String,
        serverName: String,
        sourceTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val defaultRoot = fetchSourceJson(embedUrl, null) ?: return@coroutineScope false
        val providers = parseProviders(defaultRoot)
        val default = buildResult(defaultRoot, "default")

        val results = mutableListOf<MegaVidSource>()
        default?.let { results.add(it) }

        providers.map { provider ->
            async {
                val root = fetchSourceJson(embedUrl, provider.id) ?: return@async
                val built = buildResult(root, provider.id) ?: return@async
                synchronized(results) { results.add(built) }
            }
        }.awaitAll()

        var found = false
        val seen = mutableSetOf<String>()
        val subtitleUrls = mutableSetOf<String>()
        val subtitleFiles = mutableListOf<Pair<String, String>>()
        for (result in results.sortedBy { it.provider }) {
            for ((subLabel, subFile) in result.subtitles) {
                if (subtitleUrls.add(subFile)) {
                    subtitleFiles.add(subLabel to subFile)
                }
            }
        }
        for (result in results.sortedBy { it.provider }) {
            if (!seen.add(result.url)) continue
            val hardTag = providers.firstOrNull { it.id == result.provider }?.hardSub == true
            val label = if (hardTag) {
                "$serverName (${result.provider.replaceFirstChar { it.uppercase() }}, Hardsub)"
            } else {
                "$serverName (${result.provider.replaceFirstChar { it.uppercase() }})"
            }
            try {
                if (emit(sourceTag, label, result, callback)) {
                    found = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }

        // every provider carries the same subtitle set most of the time, the
        // url set above keeps the player from listing them five times over
        if (found) {
            for ((subLabel, subFile) in subtitleFiles) {
                subtitleCallback.invoke(
                    newSubtitleFile(subLabel, subFile) {
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "https://megavid.buzz/"
                        )
                    }
                )
            }
        }
        found
    }
}
