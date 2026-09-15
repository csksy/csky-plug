package com.laddu100.reanime

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object FlixResolver {

    private const val TAG = "ReAnime"

    data class FlixSubtitle(
        val url: String,
        val language: String?,
        val format: String?
    )

    data class Result(
        val url: String,
        val subtitles: List<FlixSubtitle>,
        val isProxy: Boolean,
        val quality: Int? = null
    )

    private val embedHeaders = mapOf(
        "User-Agent" to ReAnimeApi.DESKTOP_UA,
        "Referer" to "${ReAnimeApi.MAIN_URL}/"
    )

    private val cdnHeaders = mapOf(
        "User-Agent" to ReAnimeApi.DESKTOP_UA,
        "Referer" to "${ReAnimeApi.FLIX_BASE}/"
    )

    suspend fun resolve(embedUrl: String, preferEnglish: Boolean): Result? {
        val page = try {
            app.get(embedUrl, headers = embedHeaders).text
        } catch (e: Exception) {
            Log.d(TAG, "embed fetch failed: ${e.message}")
            return null
        }
        val dataObj = extractDataObject(page) ?: return null
        val subs = parseSubtitles(dataObj)

        resolveViaService(dataObj)?.let {
            FlixStreamState.register(it, FlixStreamState.PlaylistContext(null, preferEnglish))
            return Result(it, subs, true)
        }
        return resolveLocally(page, dataObj, preferEnglish)?.let { Result(it.first, subs, false, it.second) }
    }

    fun decryptPlaylist(body: String, pk: ByteArray): String? {
        return try {
            val trimmed = body.trim()
            if (trimmed.startsWith("#EXTM3U")) return trimmed
            val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
            val raw = Base64.decode(padded, Base64.DEFAULT)
            val out = ByteArray(raw.size)
            for (i in raw.indices) out[i] = (raw[i].toInt() xor pk[i % pk.size].toInt()).toByte()
            val text = String(out, Charsets.UTF_8)
            if (text.startsWith("#EXTM3U")) text else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractDataObject(page: String): JSONObject? {
        return try {
            val start = page.indexOf("data:").let { i ->
                if (i == -1) return null else page.indexOf('{', i)
            }
            val end = matchBraces(page, start)
            if (end <= start) return null
            val raw = page.substring(start, end)
            val quoted = Regex("([{,]\\s*)([A-Za-z0-9_]+)(\\s*:)").replace(raw) { m ->
                "${m.groupValues[1]}\"${m.groupValues[2]}\"${m.groupValues[3]}"
            }
            JSONObject(quoted)
        } catch (e: Exception) {
            Log.d(TAG, "data blob parse failed: ${e.message}")
            null
        }
    }

    private fun matchBraces(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        return -1
    }

    private fun parseSubtitles(data: JSONObject): List<FlixSubtitle> {
        val out = mutableListOf<FlixSubtitle>()
        val arr = data.optJSONArray("subtitles") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url", "")
            if (!url.startsWith("http")) continue
            val language = o.optString("language").takeIf { it.isNotBlank() }
            val format = o.optString("format").takeIf { it.isNotBlank() }
            out.add(FlixSubtitle(url, language, format))
        }
        return out
    }

    // primary path: the decryption service handles the wasm handshake and
    // serves decrypted playlists through its proxy endpoint
    private suspend fun resolveViaService(dataObj: JSONObject): String? {
        return try {
            val tokenBody = JSONObject().put("data", dataObj).toString()
            val tokenRes = app.post(
                "${ReAnimeApi.DEC_SERVICE}/dec-flixcloud?type=token",
                json = tokenBody,
                headers = mapOf("User-Agent" to ReAnimeApi.DESKTOP_UA),
                timeout = 15000L
            )
            if (!tokenRes.isSuccessful) return null
            val result = JSONObject(tokenRes.text).optJSONObject("result") ?: return null
            val token = result.optString("token")
            val context = result.optJSONObject("context") ?: return null
            if (token.isBlank()) return null

            val m3u8Res = app.get("${ReAnimeApi.FLIX_BASE}/api/m3u8/$token", headers = cdnHeaders)
            if (!m3u8Res.isSuccessful) return null
            val streamResponse = JSONObject(m3u8Res.text)

            val streamBody = JSONObject()
                .put("context", context)
                .put("stream_response", streamResponse)
            val streamRes = app.post(
                "${ReAnimeApi.DEC_SERVICE}/dec-flixcloud?type=stream",
                json = JSONObject().put("data", streamBody).toString(),
                headers = mapOf("User-Agent" to ReAnimeApi.DESKTOP_UA),
                timeout = 15000L
            )
            if (!streamRes.isSuccessful) return null
            val sResult = JSONObject(streamRes.text).optJSONObject("result") ?: return null
            val streamUrl = sResult.optString("stream")
            val wPayload = sResult.optJSONObject("context")?.optString("w_payload")
                ?: sResult.optString("w_payload")
            if (!streamUrl.startsWith("http") || wPayload.isNullOrBlank()) return null

            "${ReAnimeApi.DEC_SERVICE}/parse-flixcloud?url=" +
                URLEncoder.encode(streamUrl, "UTF-8") +
                "&w_payload=" + URLEncoder.encode(wPayload, "UTF-8")
        } catch (e: Exception) {
            Log.d(TAG, "service resolve failed: ${e.message}")
            null
        }
    }

    // fallback: run the wasm key derivation locally and let the video
    // interceptor decrypt playlists in flight
    private suspend fun resolveLocally(
        page: String,
        dataObj: JSONObject,
        preferEnglish: Boolean
    ): Triple<String, Int?, Unit>? {
        return try {
            val nodeIdx = page.indexOf("node_ids")
            if (nodeIdx == -1) return null
            val region = page.substring(nodeIdx)
            val seed = Regex("""obfuscation_seed:"([^"]+)"""").find(region)?.groupValues?.get(1)
                ?: dataObj.optString("obfuscation_seed")
            val wasmB64 = dataObj.optString("w_payload")
            if (seed.isBlank() || wasmB64.isBlank()) return null

            val e = shaChain(seed)
            val s2 = shaChain(e)
            val kf = Regex(""""?kf_${e.substring(8, 16)}"?\s*:\s*"([^"]+)"""")
                .find(region)?.groupValues?.get(1) ?: return null
            val ivf = Regex(""""?ivf_${e.substring(16, 24)}"?\s*:\s*"([^"]+)"""")
                .find(region)?.groupValues?.get(1) ?: return null
            val token = Regex(""""?${e.substring(48, 64)}_${e.substring(56, 64)}"?\s*:\s*"([^"]+)"""")
                .find(region)?.groupValues?.get(1) ?: return null
            val keyFrag2 = Regex(""""?${s2.substring(0, 16)}_${s2.substring(16, 24)}"?\s*:\s*"([^"]+)"""")
                .find(region)?.groupValues?.get(1) ?: return null

            val tokenResp = app.get("${ReAnimeApi.FLIX_BASE}/api/m3u8/$token", headers = cdnHeaders)
            if (!tokenResp.isSuccessful) return null
            val tokenJson = JSONObject(tokenResp.text)
            val encVideo = tokenJson.optString(shaHex(token + "vid").substring(0, 10))
            val encKey = tokenJson.optString(shaHex(token + "key").substring(0, 10))
            if (encVideo.isBlank() || encKey.isBlank()) return null

            val wasm = MiniWasm(Base64.decode(wasmB64, Base64.DEFAULT))
            val frag1 = b64d(kf)
            val frag2 = b64d(keyFrag2)
            val encKeyBytes = b64d(encKey)
            val k = frag1.size
            if (frag2.size != k || encKeyBytes.size != k) return null
            val base = 1000
            wasm.writeMemory(base, frag1)
            wasm.writeMemory(base + k, frag2)
            wasm.writeMemory(base + 2 * k, encKeyBytes)
            val seedInt = seed.take(8).toLongOrNull(16)?.toInt() ?: return null
            wasm.call("_s", seedInt)
            wasm.call("_r", base, base + k, base + 2 * k, base + 3 * k, k)
            val keySeed = wasm.readMemory(base + 3 * k, k)
            if (keySeed.all { it == 0.toByte() }) return null

            val seedBytes = seed.toByteArray(Charsets.UTF_8)
            val pbkdf2 = pbkdf2Sha256(keySeed, seedBytes, 1000, 32)
            val xored = ByteArray(32)
            for (i in 0 until 32) xored[i] = (pbkdf2[i].toInt() xor seedBytes[i % seedBytes.size].toInt()).toByte()
            val aesKey = MessageDigest.getInstance("SHA-256").digest(xored)

            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(b64d(ivf)))
            val padded = cipher.doFinal(b64d(encVideo))
            val pad = padded.last().toInt() and 0xFF
            val cut = if (pad in 1..16 && padded.size > pad) padded.size - pad else padded.size
            val url = String(padded.copyOfRange(0, cut), Charsets.UTF_8).trim()
            if (!url.startsWith("http")) return null

            val pk = derivePk(wasm.dataBytes) ?: return null
            val masterRaw = app.get(url, headers = cdnHeaders).text
            val master = decryptPlaylist(masterRaw, pk) ?: return null
            val height = Regex("""RESOLUTION=\d+x(\d+)""").findAll(master)
                .mapNotNull { m -> m.groupValues[1].toIntOrNull() }
                .maxOrNull()

            // renditions sit in a different directory than the master, so every
            // playlist url gets registered up front for the interceptor
            val ctx = FlixStreamState.PlaylistContext(pk, preferEnglish)
            FlixStreamState.register(url, ctx)
            FlixStreamState.register(url.substringBeforeLast("/") + "/", ctx)
            for (rendition in playlistUrls(master, url)) {
                FlixStreamState.register(rendition, ctx)
                FlixStreamState.register(rendition.substringBeforeLast("/") + "/", ctx)
            }
            Triple(url, height, Unit)
        } catch (e: Exception) {
            Log.d(TAG, "local resolve failed: ${e.message}")
            null
        }
    }

    private fun playlistUrls(master: String, masterUrl: String): List<String> {
        val out = mutableSetOf<String>()
        for (m in Regex("""URI="([^"]+)"""").findAll(master)) {
            val uri = m.groupValues[1]
            if (uri.startsWith("http") || uri.endsWith(".m3u8")) {
                out.add(resolveUrl(uri, masterUrl))
            }
        }
        for (line in master.split("\n")) {
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("#") && t.endsWith(".m3u8")) {
                out.add(resolveUrl(t, masterUrl))
            }
        }
        return out.toList()
    }

    private fun resolveUrl(link: String, base: String): String {
        if (link.startsWith("http")) return link
        val sb = StringBuilder(base)
        while (sb.isNotEmpty() && sb.last() != '/') sb.setLength(sb.length - 1)
        val dir = sb.toString()
        return when {
            link.startsWith("/") -> {
                val schemeEnd = dir.indexOf("://") + 3
                val host = dir.substring(schemeEnd).trimEnd('/')
                dir.substring(0, schemeEnd) + host + link
            }
            else -> normalizePath(dir + link)
        }
    }

    private fun normalizePath(url: String): String {
        val marker = "://"
        val schemeEnd = url.indexOf(marker) + 3
        val pathStart = url.indexOf('/', schemeEnd)
        if (pathStart == -1) return url
        val head = url.substring(0, pathStart)
        val parts = mutableListOf<String>()
        for (seg in url.substring(pathStart + 1).split('/')) {
            when (seg) {
                "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return head + "/" + parts.joinToString("/")
    }

    private fun derivePk(data: ByteArray): ByteArray? {
        if (data.size < 64) return null
        val out = ByteArray(32)
        for (i in 0 until 32) out[i] = (data[i].toInt() xor data[i + 32].toInt()).toByte()
        return out
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val blocks = (keyLen + 31) / 32
        val out = ByteArray(blocks * 32)
        val index = ByteArray(4)
        for (i in 1..blocks) {
            index[0] = (i ushr 24).toByte()
            index[1] = ((i ushr 16) and 0xFF).toByte()
            index[2] = ((i ushr 8) and 0xFF).toByte()
            index[3] = (i and 0xFF).toByte()
            mac.reset()
            var u = mac.doFinal(salt + index)
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * 32, 32)
        }
        return out.copyOf(keyLen)
    }

    private fun shaChain(seed: String): String {
        var e = seed
        for (i in 0 until 3) e = shaHex(e + i)
        return e
    }

    private fun shaHex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
}
