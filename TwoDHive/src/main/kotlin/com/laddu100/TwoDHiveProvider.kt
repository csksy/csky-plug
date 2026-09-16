package com.laddu100

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.newSubtitleFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import android.util.Base64
import com.lagradost.api.Log
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// megaplay encrypts the enc stream url, the AES seeds live in lib/newclient.min.js and move over time
object MegaPlayCipher {
    private const val TAG = "MegaPlayCipher"
    private const val FALLBACK_KEY_SEED = "i?LMTAx0Q6,:}50U"
    private const val FALLBACK_IV_SEED = "W0;27ToaUpl_P%'c"

    @Volatile
    private var cachedSeeds: Pair<String, String>? = null

    private val keyPairRegex = Regex("[A-Za-z]\\w*=\"([^\"]{16})\",[A-Za-z]\\w*=\"([^\"]{16})\"")
    private val fileRegex = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")

    private fun fallback() = Pair(FALLBACK_KEY_SEED, FALLBACK_IV_SEED)

    private suspend fun keySeedCandidates(baseUrl: String): List<Pair<String, String>> {
        cachedSeeds?.let { return listOf(it, fallback()) }
        val dynamic = try {
            val js = app.get("$baseUrl/lib/newclient.min.js", timeout = 10_000L).text
            keyPairRegex.find(js)?.groupValues?.let { g ->
                Pair(g[1], g[2]).also { cachedSeeds = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "key seed fetch failed: ${e.message}")
            null
        }
        return listOfNotNull(dynamic, fallback())
    }

    private fun decryptToken(enc: String, keySeed: String, ivSeed: String): String? {
        return try {
            var b64 = enc.replace('-', '+').replace('_', '/')
            while (b64.length % 4 != 0) b64 += "="
            val cipherBytes = Base64.decode(b64, Base64.DEFAULT)

            val seedBytes = keySeed.toByteArray(Charsets.UTF_8)
            val keyBytes = ByteArray(32)
            System.arraycopy(seedBytes, 0, keyBytes, 0, minOf(32, seedBytes.size))

            val ivBytes = ByteArray(16)
            val ivSeedBytes = ivSeed.toByteArray(Charsets.UTF_8)
            System.arraycopy(ivSeedBytes, 0, ivBytes, 0, minOf(16, ivSeedBytes.size))

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d(TAG, "token decrypt failed: ${e.message}")
            null
        }
    }

    suspend fun resolveEncStreamUrl(enc: String, baseUrl: String): String? {
        for ((keySeed, ivSeed) in keySeedCandidates(baseUrl)) {
            val plain = decryptToken(enc, keySeed, ivSeed) ?: continue
            fileRegex.find(plain)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }
}

class TwoDHiveProvider : MainAPI() {
    private val TAG = "TwoDHive"

    // the megaplay cdn (openresty) only serves master.m3u8 with a valid HMAC
    // url token; variants and segments need nothing beyond the referer
    private val cdnTokenKey = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"
    private val cdnHexIdsRegex = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""", RegexOption.IGNORE_CASE)

    override var mainUrl = "https://2dhive.com"
    override var name = "2Dhive"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "completed" to "Completed Classics",
        "top" to "Top Rated Anime"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val mapper = ObjectMapper()

    private suspend fun quickGet(url: String, referer: String? = null): String {
        val headers = mutableMapOf("User-Agent" to userAgent)
        headers["Referer"] = referer ?: "$mainUrl/"
        return app.get(url = url, headers = headers).text
    }

    private fun parseGrid(soup: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        soup.select("a[href*=\"/anime?anime=\"]").forEach { a ->
            val href = a.attr("href")
            val title = a.selectFirst("h3 span.truncate")?.text()?.trim()
                ?: a.selectFirst("h3")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: ""
            if (title.length < 2) return@forEach

            var posterUrl: String? = null
            val img = a.selectFirst("img")
            if (img != null) {
                val src = img.attr("src").takeIf { it.isNotBlank() }
                if (src != null && (src.contains("anilist") || src.contains("myanimelist") || src.contains("tmdb"))) {
                    posterUrl = src
                }
            }
            if (posterUrl == null) {
                var parent = a.parent()
                repeat(5) {
                    if (parent != null && posterUrl == null) {
                        val pImg = parent.selectFirst("img")
                        if (pImg != null) {
                            val src = pImg.attr("src").takeIf { it.isNotBlank() }
                            if (src != null && (src.contains("anilist") || src.contains("myanimelist") || src.contains("tmdb"))) {
                                posterUrl = src
                            }
                        }
                    }
                    parent = parent?.parent()
                }
            }

            results.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            })
        }
        return results.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("twodhive") ?: mainUrl
        val url = if (page > 1) {
            "$mainUrl/?list=${request.data}&page=$page"
        } else {
            "$mainUrl/?list=${request.data}"
        }
        val html = quickGet(url)
        val soup = Jsoup.parse(html)
        val items = parseGrid(soup)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("twodhive") ?: mainUrl
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = quickGet("$mainUrl/?q=$encodedQuery")
        val soup = Jsoup.parse(html)
        return parseGrid(soup)
    }

    private fun decodeAstro(node: JsonNode): JsonNode {
        if (node.isArray && node.size() == 2 && node.get(0).isNumber) {
            return decodeAstro(node.get(1))
        }
        if (node.isArray) {
            val arrayNode = mapper.createArrayNode()
            node.forEach { arrayNode.add(decodeAstro(it)) }
            return arrayNode
        }
        if (node.isObject) {
            val objectNode = mapper.createObjectNode()
            node.fields().forEach { (key, value) ->
                objectNode.set<JsonNode>(key, decodeAstro(value))
            }
            return objectNode
        }
        return node
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("twodhive") ?: mainUrl
        val malId = url.substringAfter("anime=").substringBefore("&").substringBefore("/").toIntOrNull()
        val html = quickGet(url)
        val soup = Jsoup.parse(html)

        val title = soup.selectFirst("h1")?.text()?.trim()
            ?: soup.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"

        var poster: String? = null
        soup.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.contains("anilist") && src.contains("cover")) {
                poster = src
                return@forEach
            }
        }
        if (poster == null) {
            poster = soup.selectFirst("meta[property=og:image]")?.attr("content")
        }

        var plot = ""
        val summaryLabel = soup.select("p").firstOrNull { it.text().trim() == "Synopsis" }
        if (summaryLabel != null) {
            val summaryP = summaryLabel.nextElementSibling()
            if (summaryP != null) {
                plot = summaryP.text().trim()
            }
        }
        if (plot.isBlank() && malId != null) {
            try {
                val apiResp = quickGet("$mainUrl/api/anime/summary?malId=$malId")
                val apiJson = mapper.readTree(apiResp)
                plot = apiJson.get("anime")?.get("synopsis")?.asText() ?: ""
            } catch (e: Exception) {
                Log.w(TAG, "synopsis fetch failed: ${e.message}")
            }
        }

        val genres = mutableListOf<String>()
        var year: Int? = null
        if (malId != null) {
            try {
                val apiResp = quickGet("$mainUrl/api/anime/summary?malId=$malId")
                val apiJson = mapper.readTree(apiResp)
                val genresNode = apiJson.get("anime")?.get("genres")
                if (genresNode != null && genresNode.isArray) {
                    genresNode.forEach { g -> genres.add(g.asText()) }
                }
                year = apiJson.get("anime")?.get("year")?.asInt()
            } catch (e: Exception) {
                Log.w(TAG, "metadata fetch failed: ${e.message}")
            }
        }
        if (year == null) {
            soup.select("div, span, p, small").forEach { el ->
                val text = el.text()
                val match = Regex("""\b(19\d\d|20\d\d)\b""").find(text)
                if (match != null && (text.contains("Premiered", true) || text.contains("Aired", true) || text.contains("Year", true))) {
                    year = match.groupValues[1].toIntOrNull()
                }
            }
        }

        var totalEpisodes = 1
        val titleMap = mutableMapOf<Int, Pair<String?, String?>>()

        val episodeBrowserIsland = soup.select("astro-island").firstOrNull {
            it.attr("component-url").contains("EpisodeBrowser", ignoreCase = true)
        }
        if (episodeBrowserIsland != null) {
            val propsStr = episodeBrowserIsland.attr("props").takeIf { it.isNotEmpty() }
            if (!propsStr.isNullOrEmpty()) {
                try {
                    val props = mapper.readTree(propsStr)
                    val decoded = decodeAstro(props)
                    totalEpisodes = decoded.get("totalEpisodes")?.asInt() ?: 1
                    val episodeMetaNode = decoded.get("episodeMeta")
                    if (episodeMetaNode != null && episodeMetaNode.isArray) {
                        episodeMetaNode.forEach { ep ->
                            val num = ep.get("number")?.asInt()
                            val epTitle = ep.get("title")?.asText()
                            val thumb = ep.get("thumbnail")?.asText()
                            if (num != null) {
                                titleMap[num] = Pair(epTitle, thumb)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "episode props parse failed: ${e.message}")
                }
            }
        }

        val maxFromLinks = soup.select("a[href*=\"/episode?\"]").mapNotNull { a ->
            Regex("""ep_num=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        val epCount = maxOf(totalEpisodes, maxFromLinks).coerceAtLeast(1)

        val episodes = (1..epCount).map { num ->
            val epUrl = "$mainUrl/episode?anime=${malId ?: ""}&ep_num=$num"
            val meta = titleMap[num]
            newEpisode(epUrl) {
                this.episode = num
                this.name = meta?.first?.takeIf { it.isNotBlank() } ?: "Episode $num"
                this.posterUrl = meta?.second
            }
        }

        val subEpisodes = episodes.map { ep ->
            newEpisode("${ep.data}|sub") {
                this.episode = ep.episode
                this.name = ep.name
                this.posterUrl = ep.posterUrl
            }
        }
        // only show the dub tab when megaplay actually carries a dub track
        val hasDub = malId != null && probeDub(malId)
        val dubEpisodes = if (hasDub) {
            episodes.map { ep ->
                newEpisode("${ep.data}|dub") {
                    this.episode = ep.episode
                    this.name = ep.name
                    this.posterUrl = ep.posterUrl
                }
            }
        } else emptyList()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private suspend fun probeDub(malId: Int): Boolean {
        return try {
            val html = app.get(
                "https://megaplay.buzz/stream/mal/$malId/1/dub",
                headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"),
                timeout = 15_000L
            ).text
            html.contains("data-id=") || html.contains("data-realid=")
        } catch (e: Exception) {
            Log.w(TAG, "dub probe failed: ${e.message}")
            false
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
        val epUrl = parts[0]
        val type = parts[1]

        val html = quickGet(epUrl)
        val soup = Jsoup.parse(html)

        // the island component was renamed from MultiServerPlayer to EpisodePlayer, match both
        val island = soup.select("astro-island").firstOrNull {
            val cu = it.attr("component-url")
            cu.contains("EpisodePlayer", ignoreCase = true) || cu.contains("MultiServerPlayer", ignoreCase = true)
        }
        val propsStr = island?.attr("props")?.takeIf { it.isNotEmpty() }
        val decoded = if (propsStr != null) decodeAstro(mapper.readTree(propsStr)) else null

        val malId = decoded?.get("animeIdOrName")?.let { node ->
            if (node.isNumber) node.asInt() else node.asText().toIntOrNull()
        } ?: epUrl.substringAfter("anime=").substringBefore("&").toIntOrNull()

        val epNum = decoded?.get("epNum")?.asInt()
            ?: epUrl.substringAfter("ep_num=").substringBefore("&").toIntOrNull()
            ?: 1

        if (malId == null) return@coroutineScope false

        val results = mutableListOf<Deferred<Boolean>>()

        results.add(async {
            try {
                resolveMegaPlay(malId, epNum, type, epUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.w(TAG, "megaplay resolve failed: ${e.message}")
                false
            }
        })

        results.add(async {
            try {
                resolveBabaStream(malId, epNum, type, epUrl, callback)
            } catch (e: Exception) {
                Log.w(TAG, "babastream resolve failed: ${e.message}")
                false
            }
        })

        results.awaitAll().any { it }
    }

    private suspend fun resolveMegaPlay(
        malId: Int, epNum: Int, type: String, epUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerUrl = "https://megaplay.buzz/stream/mal/$malId/$epNum/$type"
        val playerHtml = app.get(playerUrl, headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to epUrl
        ), timeout = 15_000L).text

        val playerId = Regex("""data-id=["'](\d+)""").find(playerHtml)?.groupValues?.get(1)
            ?: Regex("""data-realid=["'](\d+)""").find(playerHtml)?.groupValues?.get(1)
            ?: return false

        val ajaxHeaders = mapOf(
            "User-Agent" to userAgent,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to playerUrl,
        )

        // legacy getSources still returns encrypted payloads pinned to the dead imgnex host
        val sourcesJson = fetchJson(
            "https://megaplay.buzz/stream/getSourcesNew?id=$playerId&type=$type", ajaxHeaders
        ) ?: fetchJson(
            "https://megaplay.buzz/stream/getSources?id=$playerId&type=$type", ajaxHeaders
        ) ?: return false

        val resolved = extractMegaPlayStreamUrl(sourcesJson) ?: return false
        val cdnOrigin = cdnOriginFor(resolved)
        val m3u8Url = migrateLegacyUrl(resolved, cdnOrigin)

        val tracks = sourcesJson.get("tracks")
        if (tracks != null && tracks.isArray) {
            tracks.forEach { track ->
                val file = track.get("file")?.asText() ?: return@forEach
                val label = track.get("label")?.asText() ?: "English"
                subtitleCallback(newSubtitleFile(label, migrateLegacyUrl(file, cdnOrigin)) {
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "https://megaplay.buzz/"
                    )
                })
            }
        }

        val label = if (type == "dub") "MegaPlay Dub" else "MegaPlay Sub"
        val playHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "https://megaplay.buzz/"
        )

        val signedMaster = signCdnUrl(m3u8Url)
        val masterText = try {
            app.get(signedMaster, headers = playHeaders, timeout = 15_000L).text
        } catch (e: Exception) {
            Log.w(TAG, "master playlist fetch failed: ${e.message}")
            null
        }

        val variants = masterText?.let { parseVariants(m3u8Url, it) } ?: emptyList()
        if (variants.isNotEmpty()) {
            for (variant in variants) {
                val name = variant.quality?.let { "$label ${it}p" } ?: label
                callback(
                    newExtractorLink(name, name, signCdnUrl(variant.url), type = ExtractorLinkType.M3U8) {
                        this.headers = playHeaders
                        this.referer = "https://megaplay.buzz/"
                        variant.quality?.let { this.quality = it }
                    }
                )
            }
        } else {
            callback(
                newExtractorLink(label, label, signedMaster, type = ExtractorLinkType.M3U8) {
                    this.headers = playHeaders
                    this.referer = "https://megaplay.buzz/"
                }
            )
        }
        return true
    }

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    // payload "<unix-expires>|<id1>/<id2>" signed with the cdn key, the server
    // only checks that expires is in the future so a long lifetime is safe
    private fun signCdnUrl(url: String): String {
        val match = cdnHexIdsRegex.find(url) ?: return url
        val expires = System.currentTimeMillis() / 1000L + 7L * 24 * 60 * 60
        val payload = "$expires|${match.groupValues[1].lowercase()}/${match.groupValues[2].lowercase()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(cdnTokenKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val token = "${b64url(payload.toByteArray(Charsets.UTF_8))}.${b64url(signature)}"
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}token=$token"
    }

    private data class VariantEntry(val url: String, val quality: Int?)

    // trick-play i-frame entries are inline attributes and get skipped naturally
    private fun parseVariants(masterUrl: String, masterText: String): List<VariantEntry> {
        val base = masterUrl.substringBefore('?').let { it.substringBeforeLast('/') + "/" }
        val out = mutableListOf<VariantEntry>()
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim().startsWith("#EXT-X-STREAM-INF:")) {
                val quality = Regex("""RESOLUTION=(\d+)x(\d+)""").find(lines[i])?.groupValues?.get(2)?.toIntOrNull()
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

    private fun migrateLegacyUrl(url: String, cdnOrigin: String?): String {
        if (!url.contains("https://cdn.imgnex.top/anime")) return url
        return url.replace("https://cdn.imgnex.top/anime", cdnOrigin ?: "https://megap.norami.top")
    }

    private fun cdnOriginFor(streamUrl: String): String? {
        if (streamUrl.contains("cdn.imgnex.top")) return null
        return Regex("""https?://[^/]+""").find(streamUrl)?.value
    }

    private suspend fun fetchJson(url: String, headers: Map<String, String>): JsonNode? {
        val text = try {
            app.get(url, headers = headers, timeout = 15_000L).text
        } catch (e: Exception) {
            Log.e("MegaPlay", "sources request failed ($url): ${e.message}")
            return null
        }
        return try {
            mapper.readTree(text)
        } catch (e: Exception) {
            Log.e("MegaPlay", "sources JSON parse failed: ${e.message}")
            null
        }
    }

    private suspend fun extractMegaPlayStreamUrl(sourcesJson: JsonNode): String? {
        val sources = sourcesJson.get("sources")
        val legacy = when {
            sources == null -> null
            sources.isArray -> sources.get(0)?.get("file")?.asText()
            else -> sources.get("file")?.asText()
        }
        if (!legacy.isNullOrBlank()) return legacy
        val enc = sourcesJson.get("enc")?.asText() ?: return null
        return MegaPlayCipher.resolveEncStreamUrl(enc, "https://megaplay.buzz")
    }

    // the embed hides its stream behind an aes-gcm wrapped client api plus a
    // cap proof of work. this script scrapes the page config and runs the same
    // exchange the site player would, entirely inside the webview so the
    // cloudflare cookies stay valid, then requests the playlist so the url
    // resolver can catch it
    private val babaSolverScript = """
(function () {
    if (window.__babaWatch) return;
    window.__babaWatch = 1;

    var MAX_ATTEMPTS = 4;
    var RETRY_DELAY = 2500;
    var NONCE_LIMIT = 20000000;

    var WORKER_CODE = [
        "function toHex(buffer) {",
        "    var bytes = new Uint8Array(buffer);",
        "    var out = '';",
        "    for (var i = 0; i < bytes.length; i++) out += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);",
        "    return out;",
        "}",
        "async function solve(salt, target) {",
        "    var encoder = new TextEncoder();",
        "    var batch = 500;",
        "    var base = 0;",
        "    while (base < " + NONCE_LIMIT + ") {",
        "        var pending = [];",
        "        for (var i = 0; i < batch; i++) {",
        "            pending.push(crypto.subtle.digest('SHA-256', encoder.encode(salt + (base + i))));",
        "        }",
        "        var digests = await Promise.all(pending);",
        "        for (var j = 0; j < batch; j++) {",
        "            if (toHex(digests[j]).indexOf(target) === 0) return base + j;",
        "        }",
        "        base += batch;",
        "    }",
        "    return null;",
        "}",
        "self.onmessage = async function (event) {",
        "    for (var i = 0; i < event.data.length; i++) {",
        "        var job = event.data[i];",
        "        var nonce = await solve(job.salt, job.target);",
        "        self.postMessage({ id: job.id, nonce: nonce });",
        "    }",
        "};"
    ].join("\n");

    function parseConfig(html) {
        var pattern = /\b(?:var|let|const)\s+\w+\s*=\s*(\{[^;]+\})\s*;/g;
        var match;
        while ((match = pattern.exec(html)) !== null) {
            try {
                var config = JSON.parse(match[1]);
                if (typeof config.sid === "string" && typeof config.pk === "string") return config;
            } catch (e) {}
        }
        return null;
    }

    function parseCryptoOptions(html) {
        var name = (html.match(/importKey\([^,]+,[^,]+,\s*\{\s*name\s*:\s*["'](AES-[A-Z]+)["']/) || [])[1];
        var ivLength = parseInt((html.match(/getRandomValues\(new Uint8Array\((\d+)\)\)/) || [])[1], 10);
        var tagBits = parseInt((html.match(/tagLength\s*:\s*(\d+)/) || [])[1], 10) || 128;
        if (!name || !ivLength || ivLength < 1) return null;
        return { name: name, ivLength: ivLength, tagBits: tagBits };
    }

    function parseRoutes(html) {
        var routes = [];
        var pattern = /fetch\(\s*["']([^"']+)["']/g;
        var match;
        while ((match = pattern.exec(html)) !== null) routes.push(match[1]);
        function pick(test) {
            for (var i = 0; i < routes.length; i++) if (test(routes[i])) return routes[i];
            return null;
        }
        return {
            resolve: pick(function (r) { return /resolve/i.test(r); }),
            verify: pick(function (r) { return /verify/i.test(r); })
        };
    }

    function toBytes(base64) {
        var binary = atob(base64);
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return bytes;
    }

    function toBase64(bytes) {
        var binary = "";
        for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
        return btoa(binary);
    }

    function toHex(buffer) {
        var bytes = new Uint8Array(buffer);
        var out = "";
        for (var i = 0; i < bytes.length; i++) out += (bytes[i] < 16 ? "0" : "") + bytes[i].toString(16);
        return out;
    }

    // older cap deployments derive every salt and target from the session
    // token, this mirrors that derivation byte for byte
    function seededHex(value, length) {
        var state = 2166136261;
        for (var i = 0; i < value.length; i++) {
            state ^= value.charCodeAt(i);
            state += (state << 1) + (state << 4) + (state << 7) + (state << 8) + (state << 24);
        }
        state = state >>> 0;
        var output = "";
        while (output.length < length) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            state = state >>> 0;
            output += state.toString(16).padStart(8, "0");
        }
        return output.slice(0, length);
    }

    // main thread fallback used when the page forbids worker scripts
    async function findNonceInline(salt, target) {
        var encoder = new TextEncoder();
        var batch = 400;
        var base = 0;
        while (base < NONCE_LIMIT) {
            var pending = [];
            for (var i = 0; i < batch; i++) {
                pending.push(crypto.subtle.digest("SHA-256", encoder.encode(salt + (base + i))));
            }
            var digests = await Promise.all(pending);
            for (var j = 0; j < batch; j++) {
                if (toHex(digests[j]).indexOf(target) === 0) return base + j;
            }
            base += batch;
        }
        return null;
    }

    // the cap default is dozens of lightweight proofs, throw them at a pool
    // of digest workers like the site widget does
    function solveProofs(proofs) {
        var jobs = proofs.map(function (proof, id) {
            return { id: id, salt: proof.salt, target: proof.target };
        });
        return new Promise(function (resolve) {
            // null marks an unsolved job, using holes would break the
            // completeness checks below because indexOf skips them
            var results = jobs.map(function () { return null; });
            var failed = false;
            var settled = false;

            function finish() {
                if (settled) return;
                settled = true;
                workers.forEach(function (worker) { try { worker.terminate(); } catch (e) {} });
                if (failed || results.indexOf(null) !== -1) {
                    resolve(null);
                } else {
                    resolve(results);
                }
            }

            function remainingJobs() {
                return jobs.filter(function (job) { return results[job.id] === null; });
            }

            async function drainInline() {
                var pending = remainingJobs();
                for (var i = 0; i < pending.length; i++) {
                    results[pending[i].id] = await findNonceInline(pending[i].salt, pending[i].target);
                }
                finish();
            }

            var workers = [];
            try {
                var workerUrl = URL.createObjectURL(new Blob([WORKER_CODE], { type: "application/javascript" }));
                var count = Math.min(navigator.hardwareConcurrency || 2, 6, jobs.length);
                for (var w = 0; w < count; w++) {
                    var worker = new Worker(workerUrl);
                    worker.onmessage = function (event) {
                        var data = event.data;
                        if (data && data.id !== undefined && results[data.id] === null) {
                            if (data.nonce === null) { failed = true; }
                            results[data.id] = data.nonce === null ? -1 : data.nonce;
                            if (results.indexOf(null) === -1) finish();
                        }
                    };
                    worker.onerror = function () {
                        // a dead worker drops its queue, pick the rest up inline
                        workers.forEach(function (other) { try { other.terminate(); } catch (e) {} });
                        drainInline();
                    };
                    workers.push(worker);
                }
                for (var j = 0; j < jobs.length; j++) {
                    workers[j % workers.length].postMessage([jobs[j]]);
                }
            } catch (e) {
                drainInline();
            }
        });
    }

    async function solveChallenges(challenges) {
        var proofs = challenges.map(function (challenge) {
            if (!challenge || challenge.protocol !== "sha256-pow") return null;
            return { salt: challenge.payload.salt, target: challenge.payload.target };
        });
        if (proofs.indexOf(null) !== -1) throw new Error("unsupported challenge protocol");
        var nonces = await solveProofs(proofs);
        if (!nonces) throw new Error("proof of work failed");
        return nonces.map(function (nonce) { return { nonce: nonce }; });
    }

    async function solveSeededChallenge(token, challenge) {
        if (!token || !challenge || !challenge.c) throw new Error("invalid challenge");
        var proofs = [];
        for (var i = 1; i <= challenge.c; i++) {
            proofs.push({ salt: seededHex(token + i, challenge.s), target: seededHex(token + i + "d", challenge.d) });
        }
        var nonces = await solveProofs(proofs);
        if (!nonces) throw new Error("proof of work failed");
        return nonces;
    }

    async function openSession(html) {
        var config = parseConfig(html);
        var options = parseCryptoOptions(html);
        var routes = parseRoutes(html);
        if (!config || !options || !routes.resolve) throw new Error("client api not found in page");

        var key = await crypto.subtle.importKey(
            "raw", toBytes(config.pk), { name: options.name }, false, ["encrypt", "decrypt"]
        );

        async function request(route, body) {
            var iv = crypto.getRandomValues(new Uint8Array(options.ivLength));
            var sealed = await crypto.subtle.encrypt(
                { name: options.name, iv: iv, tagLength: options.tagBits },
                key,
                new TextEncoder().encode(JSON.stringify(body))
            );
            var payload = new Uint8Array(iv.length + sealed.byteLength);
            payload.set(iv, 0);
            payload.set(new Uint8Array(sealed), iv.length);
            var response = await fetch(route, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ s: config.sid, d: toBase64(payload) })
            });
            var json = await response.json();
            if (!json || !json.d) throw new Error("empty api response");
            var raw = toBytes(json.d);
            var plain = await crypto.subtle.decrypt(
                { name: options.name, iv: raw.slice(0, options.ivLength), tagLength: options.tagBits },
                key,
                raw.slice(options.ivLength)
            );
            return JSON.parse(new TextDecoder().decode(plain));
        }

        return { config: config, routes: routes, request: request };
    }

    async function loadPageHtml() {
        try {
            var response = await fetch(location.href, { credentials: "same-origin" });
            if (response.ok) {
                var text = await response.text();
                if (text.indexOf("sid") !== -1) return text;
            }
        } catch (e) {}
        return document.documentElement.outerHTML;
    }

    async function run() {
        var session = await openSession(await loadPageHtml());

        var resolved = await session.request(session.routes.resolve, { ts: Date.now() });
        if (resolved && resolved.t === "error" && /verify/i.test(resolved.m || "")) {
            if (!session.config.cap || !session.routes.verify) throw new Error("cap routes missing");
            var capBase = session.config.cap;
            if (capBase.charAt(capBase.length - 1) !== "/") capBase += "/";
            var challenge = await (await fetch(capBase + "challenge", { method: "POST" })).json();
            var solutions = Array.isArray(challenge.challenges)
                ? await solveChallenges(challenge.challenges)
                : await solveSeededChallenge(challenge.token, challenge.challenge);
            var redeem = await (await fetch(capBase + "redeem", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ token: challenge.token, solutions: solutions })
            })).json();
            if (!redeem || !redeem.success || !redeem.token) throw new Error("cap redeem failed");
            var verified = await session.request(session.routes.verify, {
                ts: Date.now(), token: redeem.token, mode: "invisible"
            });
            if (!verified || verified.t !== "ok") throw new Error("cap verify failed");
            resolved = await session.request(session.routes.resolve, { ts: Date.now() });
        }

        if (!resolved || !resolved.u) throw new Error((resolved && resolved.m) || "no stream url");
        if (/\.(m3u8|mp4)(\?|$)/i.test(resolved.u)) {
            // the host app watches for media requests leaving the webview
            fetch(resolved.u, { mode: "no-cors" }).catch(function () {});
        } else {
            location.href = resolved.u;
        }
    }

    var attempts = 0;
    function attempt() {
        attempts += 1;
        if (attempts > MAX_ATTEMPTS) return;
        run().catch(function () {
            setTimeout(attempt, RETRY_DELAY);
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", attempt);
    } else {
        attempt();
    }
})();
""".trimIndent()

    private suspend fun resolveBabaStream(
        malId: Int, epNum: Int, type: String, epUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = "https://babastream.top/embed/$malId/$epNum/$type"
        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                script = babaSolverScript,
                // the pow solve alone can take half a minute on slow hardware
                useOkhttp = false, timeout = 120_000L
            )
            val resolved = app.get(embedUrl, referer = epUrl, interceptor = resolver).url
            if (resolved.contains(".m3u8") || resolved.contains(".mp4")) {
                val linkType = if (resolved.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(
                    newExtractorLink("BabaStream", "BabaStream", resolved, type = linkType) {
                        this.headers = mapOf("User-Agent" to userAgent, "Referer" to "https://babastream.top/")
                    }
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("BabaStream", "WebView extraction failed: ${e.message}")
            false
        }
    }
}