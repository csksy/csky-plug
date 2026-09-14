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
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MegaPlay changed its sources API (megaplay.buzz and clones):
 *  - the client script lib/newclient.min.js rewrites /stream/getSources -> /stream/getSourcesNew
 *  - legacy getSources now answers {"tracks":[...]} with sources:null
 *  - getSourcesNew answers {"tracks":[...],"t":1,"server":4,"enc":"<base64url>"}
 *  - "enc" is AES-CBC(base64url) of a JSON payload {"file":"https://.../master.m3u8"}
 *  - key material lives in newclient.min.js (16-char seeds), pinned fallbacks kept below
 *  - the ?s=bcdn / ?s=tcdn query on the embed selects the CDN (imgnex vs nexabloom)
 *    and must be forwarded to getSourcesNew
 *
 * Verified live 2026-09-14 against megaplay.buzz/stream/s-2/{id}/{sub|dub}.
 */
object MegaPlayCipher {
    private const val TAG = "MegaPlayCipher"
    private const val FALLBACK_KEY_SEED = "i?LMTAx0Q6,:}50U"
    private const val FALLBACK_IV_SEED = "W0;27ToaUpl_P%'c"

    @Volatile
    private var cachedSeeds: Pair<String, String>? = null

    // newclient.min.js pins the two 16-char seeds side by side:
    // var P="<key>",w="<iv>",E=/\/segment\/...
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

    /**
     * Resolves a megaplay embed page to the playable master m3u8.
     * @param embedUrl e.g. https://megaplay.buzz/stream/s-2/599555/sub or ...?s=bcdn
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

        // the s= param selects the CDN and must be forwarded to getSourcesNew
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
                    return MegaPlayStream(streamUrl, parseSubtitleTracks(root, streamUrl))
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

    private fun parseSubtitleTracks(root: JsonNode, m3u8: String): List<Pair<String, String>> {
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

    /**
     * Emits quality variants from the master playlist, falling back to the raw
     * url when the playlist cannot be fetched or holds a single stream.
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
