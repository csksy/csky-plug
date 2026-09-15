package com.anikoto

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MegaPlay (megaplay.buzz + vidwish/vidtube clones) — fully rewritten 2026-09-15.
 *
 * VERIFIED LIVE against megaplay.buzz:
 *  - /stream/getSourcesNew?id=<dataId>&type=<sub|dub> answers
 *    {"tracks":[..],"t":1,"intro":{..},"outro":{..},"server":4,"enc":"<base64url>"}
 *  - "enc" = AES-CBC(key seed "i?LMTAx0Q6,:}50U" zero-padded to 32B, IV "W0;27ToaUpl_P%'c")
 *    of {"file":"https://fetch.nexabloom.top/anime/<id1>/<id2>/master.m3u8"}
 *  - !s! query param selects the CDN and MUST NOT be forwarded:
 *      default/bcdn -> fetch.nexabloom.top (variant playlists hold ABSOLUTE segment
 *                      urls on clean Cloudflare CDNs: qx-01.quavex.top,
 *                      tx-01.tyrionx.top, cdn-XXX.streamzone1.site ... plain MPEG-TS,
 *                      no byte prefix, only need Referer)
 *      tcdn         -> megap.shiora.site (segments are absolute tiktokcdn.com urls
 *                      with a 252-byte PNG header prepended -> UNPLAYABLE in
 *                      ExoPlayer + tiktokcdn is ISP-blocked in India)
 *  - ONLY the master.m3u8 request requires an HMAC token (openresty 403 otherwise):
 *        payload = "<unix-expires>|<id1>/<id2>"   (ids lowercased from the path)
 *        token   = b64url(payload) + "." + b64url(HMAC-SHA256("MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s", payload))
 *        url     = url + (already has query ? "&" : "?") + "token=" + token
 *    The real web player signs with a 90s expiry, but the server only checks
 *    expires > now, so we sign a long-lived (7 day) token. Variant playlists and
 *    segments are served WITHOUT any token (Referer only).
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

    // --- HMAC url token (openresty on the CDN requires it for master.m3u8) ---
    private const val TOKEN_KEY = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"
    private const val TOKEN_LIFETIME_SECONDS = 7L * 24L * 60L * 60L // 7 days (server only checks expires > now)
    private val hexIdsRegex = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""", RegexOption.IGNORE_CASE)

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Signs a megaplay CDN url with a long-lived HMAC token (verified live). */
    fun signUrl(url: String, lifetimeSeconds: Long = TOKEN_LIFETIME_SECONDS): String {
        val m = hexIdsRegex.find(url) ?: return url
        val expires = System.currentTimeMillis() / 1000L + lifetimeSeconds
        val payload = "$expires|${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TOKEN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val token = "${b64url(payload.toByteArray(Charsets.UTF_8))}.${b64url(signature)}"
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}token=$token"
    }

    class MegaPlayStream(val m3u8: String, val subtitles: List<Pair<String, String>>)

    fun audioTypeFromUrl(url: String): String? =
        Regex("""/(dub|sub)(?:[/?#]|$)""").find(url)?.groupValues?.get(1)

    /**
     * Resolves a megaplay embed page to the playable master m3u8 (default CDN).
     * The ?s= param is deliberately NOT forwarded — s=tcdn selects a CDN whose
     * segments are tiktokcdn urls with a 252-byte junk prefix (unplayable).
     */
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
                // NOTE: no &s= param — always the default (nexabloom) CDN.
                val url = "$base/stream/$endpoint?id=$streamId&type=$audioType"
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

    private data class VariantEntry(val url: String, val quality: Int?)

    /**
     * Splits the (signed) master playlist into its video variants.
     * Skips trick-play (i-frame) entries naturally: their URI is an inline
     * attribute of #EXT-X-I-FRAME-STREAM-INF, never the line after
     * #EXT-X-STREAM-INF.
     */
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

    /**
     * Emits quality-labelled, HMAC-signed links for every variant of the master
     * playlist (plus the signed master itself as a fallback). Variant playlists
     * and segments only need the Referer header.
     */
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

        val signedMaster = signUrl(m3u8)
        val masterText = try {
            app.get(signedMaster, headers = playHeaders, timeout = 15_000L).text
        } catch (e: Exception) {
            Log.d(TAG, "[MegaPlay] signed master fetch failed: ${e.message}")
            null
        }

        var found = false
        val variants = masterText?.let { parseVariants(m3u8, it) } ?: emptyList()
        if (variants.isNotEmpty()) {
            for (v in variants) {
                val qualitySuffix = v.quality?.let { "${it}p" } ?: ""
                callback.invoke(
                    newExtractorLink(
                        source,
                        if (qualitySuffix.isEmpty()) label else "$label $qualitySuffix",
                        signUrl(v.url),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        v.quality?.let { quality = it }
                        this.headers = playHeaders
                    }
                )
                found = true
            }
        } else {
            // master could not be fetched/parsed — hand out the signed url as-is
            callback.invoke(
                newExtractorLink(source, label, signedMaster, type = ExtractorLinkType.M3U8) {
                    this.referer = referer
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
