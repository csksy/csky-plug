package com.laddu100

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MegaPlay (megaplay.buzz and clones) sources API changed:
 *  - the client script lib/newclient.min.js rewrites /stream/getSources -> /stream/getSourcesNew
 *  - legacy getSources now answers {"tracks":[...]} with sources:null
 *  - getSourcesNew answers {"tracks":[...],"t":1,"server":4,"enc":"<base64url>"}
 *  - "enc" is AES-CBC(base64url) of {"file":"https://.../master.m3u8"}
 *  - key material lives in newclient.min.js (16-char seeds), pinned fallbacks kept below
 *  - ?s=bcdn / ?s=tcdn on the embed selects the CDN and must be forwarded
 *
 * Verified live 2026-09-14 against megaplay.buzz.
 */
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
        } catch (_: Exception) {
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

object MegaPlayResolver {
    private const val TAG = "MegaPlayResolver"
    private val mapper = ObjectMapper()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    class MegaPlayStream(val m3u8: String, val subtitles: List<Pair<String, String>>)

    fun audioTypeFromUrl(url: String): String? =
        Regex("""/(dub|sub)(?:[/?#]|$)""").find(url)?.groupValues?.get(1)

    suspend fun resolveStream(embedUrl: String, referer: String?): MegaPlayStream? {
        val host = Regex("""https?://([^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: return null
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://$host/")
        )

        val pageHtml = try {
            app.get(embedUrl, headers = pageHeaders).text
        } catch (e: Exception) {
            Log.d(TAG, "[MegaPlay] embed page failed for $host: ${e.message}")
            return null
        }

        val streamId = Regex("""data-id=["'](\d+)""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""data-realid=["'](\d+)""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""/stream/s-\d+/(\d+)/""").find(embedUrl)?.groupValues?.get(1)
            ?: return null

        val audioType = audioTypeFromUrl(embedUrl)
            ?: Regex("""type\s*:\s*['"](dub|sub)['"]""").find(pageHtml)?.groupValues?.get(1)
            ?: "sub"

        val sParam = Regex("""[?&]s=([a-z0-9_-]+)""").find(embedUrl)?.groupValues?.get(1)
        val altHost = Regex("""data-domain=["']([^"']+)["']""").find(pageHtml)?.groupValues?.get(1)
        val hosts = listOfNotNull(host, altHost?.takeIf { it != host }).distinct()

        for (apiHost in hosts) {
            val base = "https://$apiHost"
            val ajaxHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to base,
                "Referer" to embedUrl
            )
            for (endpoint in listOf("getSourcesNew", "getSources")) {
                var url = "$base/stream/$endpoint?id=$streamId&type=$audioType"
                if (sParam != null) url += "&s=$sParam"
                val root = fetchJson(url, ajaxHeaders) ?: continue
                val streamUrl = extractStream(root, base)
                if (streamUrl != null) {
                    return MegaPlayStream(streamUrl, parseSubtitleTracks(root))
                }
            }
        }
        return null
    }

    private suspend fun extractStream(root: JsonNode, base: String): String? {
        val sources = root.get("sources")
        val plain = when {
            sources == null || sources.isNull -> null
            sources.isObject -> sources.get("file")?.asText()
            sources.isArray && sources.size() > 0 -> sources.get(0)?.get("file")?.asText()
            else -> null
        }
        if (!plain.isNullOrBlank()) return plain

        val enc = root.get("enc")?.takeIf { !it.isNull }?.asText() ?: return null
        return MegaPlayCipher.resolveEncStreamUrl(enc, base)
    }

    private fun parseSubtitleTracks(root: JsonNode): List<Pair<String, String>> {
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

    private suspend fun fetchJson(url: String, headers: Map<String, String>): JsonNode? {
        val text = try {
            app.get(url, headers = headers, timeout = 15_000L).text
        } catch (e: Exception) {
            Log.d(TAG, "[MegaPlay] sources request failed: ${e.message}")
            return null
        }
        return try {
            mapper.readTree(text)
        } catch (e: Exception) {
            Log.d(TAG, "[MegaPlay] sources json parse failed: ${e.message}")
            null
        }
    }

    suspend fun emitLinks(
        source: String,
        label: String,
        m3u8: String,
        referer: String,
        subtitles: List<Pair<String, String>>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer
        )
        var found = false
        val generated = try {
            M3u8Helper.generateM3u8(source, m3u8, referer, headers = playHeaders)
        } catch (e: Exception) {
            Log.d(TAG, "[MegaPlay] m3u8 expansion failed: ${e.message}")
            emptyList()
        }
        if (generated.isNotEmpty()) {
            generated.forEach(callback)
            found = true
        } else {
            callback.invoke(
                newExtractorLink(source, label, m3u8, type = ExtractorLinkType.M3U8) {
                    this.headers = playHeaders
                }
            )
            found = true
        }
        for ((subLabel, subUrl) in subtitles) {
            subtitleCallback.invoke(
                newSubtitleFile(subLabel, subUrl) {
                    this.headers = playHeaders
                }
            )
        }
        return found
    }
}

/**
 * AniSuge moved its per-episode sources to an external "mapper" API
 * (loaded via assets/js/mapper.js on the watch page):
 *
 *   https://mapper.nekostream.site/api/mal/{data-mal}/{data-slug}/{data-timestamp}
 *
 * Response: { "<provider>": { "sub": { "url": ..., "download": {quality: url} },
 *                             "dub":  { ... } }, "status": {...} }
 *
 * Provider display names follow the site's mapper.js mapping:
 * gogoanime -> Vidstream, anivibe -> vibe-Stream, animepahe -> Kiwi-Stream.
 *
 * Download urls (pahe.nekostream.site/{id}) lead to a workers.dev redirect
 * which 302s to a kwik.cx file page.
 *
 * Verified live 2026-09-14.
 */
object AniSugeMapper {
    private const val TAG = "AniSugeMapper"
    private val json = ObjectMapper()
    private const val MAPPER_API = "https://mapper.nekostream.site/api/mal/"

    class MapperEntry(val url: String?, val downloads: Map<String, String>)

    suspend fun fetchProviders(malId: String, slug: String, timestamp: String): Map<String, Pair<MapperEntry?, MapperEntry?>>? {
        val text = try {
            app.get(
                MAPPER_API + "$malId/$slug/$timestamp",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "Accept" to "application/json"
                ),
                timeout = 15_000L
            ).text
        } catch (e: Exception) {
            Log.d(TAG, "mapper fetch failed: ${e.message}")
            return null
        }
        val root = try {
            json.readTree(text)
        } catch (e: Exception) {
            Log.d(TAG, "mapper json parse failed: ${e.message}")
            return null
        }
        if (!root.isObject) return null

        val out = linkedMapOf<String, Pair<MapperEntry?, MapperEntry?>>()
        val fields = root.fields()
        while (fields.hasNext()) {
            val (key, value) = fields.next()
            if (!value.isObject) continue
            out[key] = parseEntry(value.get("sub")) to parseEntry(value.get("dub"))
        }
        return out
    }

    private fun parseEntry(node: JsonNode?): MapperEntry? {
        if (node == null || !node.isObject) return null
        val url = node.get("url")?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
        val downloads = linkedMapOf<String, String>()
        val dl = node.get("download")
        if (dl != null && dl.isObject) {
            val it = dl.fields()
            while (it.hasNext()) {
                val (q, u) = it.next()
                if (u.isTextual && u.asText().startsWith("http")) downloads[q] = u.asText()
            }
        }
        if (url == null && downloads.isEmpty()) return null
        return MapperEntry(url, downloads)
    }

    fun displayProviderName(key: String): String = when (key.lowercase()) {
        "gogoanime" -> "Vidstream"
        "anivibe" -> "vibe-Stream"
        "animepahe" -> "Kiwi-Stream"
        else -> key.replaceFirstChar { it.uppercase() }
    }
}

/**
 * Resolves pahe.nekostream.site/{id} download pages to the final kwik.cx file URL.
 * Page JS builds: const url = "https://<workers.dev>/" + id  -> 302 -> kwik.cx/f/{fileId}
 */
object PaheDownloadResolver {
    private const val TAG = "PaheDownloadResolver"
    private val workersUrlRegex = Regex("""const\s+url\s*=\s*"(https?://[^"]+)"""")
    private val anyHttpsRegex = Regex(""""(https?://[^"]*workers\.dev[^"]*)"""")

    suspend fun resolveKwikUrl(paheUrl: String): String? {
        val page = try {
            app.get(
                paheUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                ),
                timeout = 15_000L
            ).text
        } catch (e: Exception) {
            Log.d(TAG, "pahe page failed: ${e.message}")
            return null
        }

        val base = workersUrlRegex.find(page)?.groupValues?.get(1)
            ?: anyHttpsRegex.find(page)?.groupValues?.get(1)
            ?: return null

        val redirector = base.trimEnd('/') + "/" + paheUrl.trimEnd('/').substringAfterLast('/')
        return try {
            val res = app.get(redirector, allowRedirects = false)
            val loc = res.headers["location"]
            if (loc != null && loc.startsWith("http")) loc else null
        } catch (e: Exception) {
            Log.d(TAG, "workers redirect failed: ${e.message}")
            null
        }
    }
}

/**
 * kwik.cx file pages (kwik.cx/f/{id}) carry a p/a/c/k/e/d script that unpacks
 * to the playable source. Handles both the direct `source='...'` form and the
 * form-POST (action + _token -> 302 direct file) form.
 */
class KwikExtractor : ExtractorApi() {
    override val name = "Kwik"
    override val mainUrl = "https://kwik.cx"
    override val requiresReferer = true

    private val sourceRegex = Regex("""source\s*=\s*["']([^"']+)["']""")
    private val formActionRegex = Regex("""action="([^"]+)"""")
    private val formTokenRegex = Regex("""value="([^"]+)"""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        resolve(url, referer)?.let { (playUrl, pageUrl) ->
            callback.invoke(
                newExtractorLink(
                    name, name, playUrl,
                    if (playUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = pageUrl
                    this.headers = mapOf("Referer" to pageUrl)
                }
            )
        } ?: run {
            Log.e("Kwik", "extraction failed for $url")
        }
    }

    /** Returns (playableUrl, kwikPageUrl) or null. */
    suspend fun resolve(url: String, referer: String?): Pair<String, String>? {
        val page = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "Referer" to (referer ?: mainUrl)
                ),
                timeout = 20_000L
            )
        } catch (e: Exception) {
            Log.d("Kwik", "page fetch failed: ${e.message}")
            return null
        }
        val html = page.text

        // packed script -> unpack once, reuse for both source & form patterns
        val packedScript = try {
            Jsoup.parse(html).selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
        } catch (_: Exception) {
            null
        }
        val unpacked = packedScript?.let {
            try {
                getAndUnpack(it)
            } catch (e: Exception) {
                Log.d("Kwik", "unpack failed: ${e.message}")
                null
            }
        }

        // Form 1: unpacked script contains source='...'
        if (unpacked != null) {
            sourceRegex.find(unpacked)?.groupValues?.get(1)?.let { src ->
                if (src.startsWith("http")) return src to page.url
            }
        }

        // Form 2: unpacked script holds a form; POST action + _token -> 302 file
        if (unpacked != null) {
            val action = formActionRegex.find(unpacked)?.groupValues?.get(1)
            val token = formTokenRegex.find(unpacked)?.groupValues?.get(1)
            if (action != null && token != null) {
                return postForRedirect(action, token, page.url)
            }
        }

        // Form 3: raw html (no packer) — some mirrors inline the source directly
        sourceRegex.find(html)?.groupValues?.get(1)?.let { src ->
            if (src.startsWith("http") && (src.contains(".m3u8") || src.contains(".mp4"))) {
                return src to page.url
            }
        }
        return null
    }

    private suspend fun postForRedirect(action: String, token: String, pageUrl: String): Pair<String, String>? {
        var tries = 0
        var code = 419
        var location = ""
        while (code != 302 && tries < 10) {
            try {
                val res = app.post(
                    action,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                        "Referer" to pageUrl
                    ),
                    data = mapOf("_token" to token),
                    allowRedirects = false
                )
                code = res.code
                if (code == 302) location = res.headers["location"] ?: ""
            } catch (e: Exception) {
                Log.d("Kwik", "post attempt failed: ${e.message}")
            }
            tries++
        }
        return location.takeIf { it.startsWith("http") }?.let { it to pageUrl }
    }
}
