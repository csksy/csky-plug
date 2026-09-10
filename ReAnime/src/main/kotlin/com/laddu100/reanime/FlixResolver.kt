package com.laddu100.reanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object FlixResolver {

    private const val TAG = "ReAnime"

    data class FlixSubtitle(
        val url: String,
        val language: String?,
        val format: String?
    )

    data class Result(
        val m3u8: String,
        val pkKey: ByteArray,
        val masterContent: String,
        val subtitles: List<FlixSubtitle>,
        val releaseTitle: String?
    )

    suspend fun resolve(embedUrl: String, referer: String): Result? {
        return try {
            val pageHeaders = mapOf(
                "User-Agent" to ReAnimeApi.BROWSER_HEADERS["User-Agent"]!!,
                "Referer" to referer
            )
            val page = app.get(embedUrl, headers = pageHeaders).text
            val region = page.substringAfter("node_ids", "")
            if (region.isBlank()) return null

            val seed = Regex("""obfuscation_seed:"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null
            val wasmB64 = Regex("""w_payload:"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null

            val e = shaChain(seed)
            val s2 = shaChain(e)
            val kf = Regex(""""?kf_${e.substring(8, 16)}"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null
            val ivf = Regex(""""?ivf_${e.substring(16, 24)}"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null
            val token = Regex(""""?${e.substring(48, 64)}_${e.substring(56, 64)}"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null
            val keyFrag2 = Regex(""""?${s2.substring(0, 16)}_${s2.substring(16, 24)}"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null

            val tokenResp = app.get(
                "${ReAnimeApi.FLIX_EMBED_BASE}/api/m3u8/$token",
                headers = pageHeaders
            ).text
            val vidField = shaHex(token + "vid").substring(0, 10)
            val keyField2 = shaHex(token + "key").substring(0, 10)
            val encVideo = Regex(""""$vidField"\s*:\s*"([^"]+)"""").find(tokenResp)?.groupValues?.get(1) ?: return null
            val encKey = Regex(""""$keyField2"\s*:\s*"([^"]+)"""").find(tokenResp)?.groupValues?.get(1) ?: return null

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

            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(
                keySeed.map { it.toInt().toChar() }.toCharArray(),
                seed.toByteArray(Charsets.UTF_8),
                1000, 256
            )
            val pbkdf2 = factory.generateSecret(spec).encoded
            val xored = ByteArray(32)
            val seedBytes = seed.toByteArray(Charsets.UTF_8)
            for (i in 0 until 32) xored[i] = (pbkdf2[i].toInt() xor seedBytes[i % seedBytes.size].toInt()).toByte()
            val aesKey = MessageDigest.getInstance("SHA-256").digest(xored)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(b64d(ivf)))
            val urlBytes = cipher.doFinal(b64d(encVideo))
            val url = String(urlBytes, Charsets.UTF_8).trim()
            if (!url.startsWith("http")) return null

            val pk = derivePk(wasm.dataBytes) ?: return null

            val masterRaw = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to ReAnimeApi.BROWSER_HEADERS["User-Agent"]!!,
                    "Referer" to "${ReAnimeApi.FLIX_EMBED_BASE}/"
                )
            ).text.trim()
            val masterBody = decryptPlaylist(masterRaw, pk) ?: return null

            val subs = extractSubtitles(region)
            val title = Regex("""<title>([^<]*)</title>""").find(page)?.groupValues?.get(1)
            Result(url, pk, masterBody, subs, title)
        } catch (e: Exception) {
            android.util.Log.d(TAG, "flix resolve failed: ${e.message}")
            null
        }
    }

    fun decryptPlaylist(body: String, pk: ByteArray): String? {
        return try {
            val trimmed = body.trim()
            if (trimmed.startsWith("#EXTM3U")) return trimmed
            val padded = buildString {
                append(trimmed)
                append("=".repeat((4 - trimmed.length % 4) % 4))
            }
            val raw = Base64.decode(padded, Base64.DEFAULT)
            val out = ByteArray(raw.size)
            for (i in raw.indices) out[i] = (raw[i].toInt() xor pk[i % pk.size].toInt()).toByte()
            val text = String(out, Charsets.UTF_8)
            if (text.startsWith("#EXTM3U")) text else null
        } catch (e: Exception) {
            null
        }
    }

    private fun derivePk(data: ByteArray): ByteArray? {
        if (data.size < 64) return null
        val out = ByteArray(32)
        for (i in 0 until 32) out[i] = (data[i].toInt() xor data[i + 32].toInt()).toByte()
        return out
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

    private fun extractSubtitles(region: String): List<FlixSubtitle> {
        val out = mutableListOf<FlixSubtitle>()
        val m = Regex("""subtitles:\[([^\]]*)\]""").find(region) ?: return out
        val body = m.groupValues[1]
        if (body.isBlank()) return out
        val objRe = Regex("""\{([^{}]*)\}""")
        for (om in objRe.findAll(body)) {
            val o = om.groupValues[1]
            val url = Regex(""""?url"?\s*:\s*"([^"]+)"""").find(o)?.groupValues?.get(1) ?: continue
            if (!url.startsWith("http")) continue
            val lang = Regex(""""?language"?\s*:\s*"([^"]+)"""").find(o)?.groupValues?.get(1)
            val fmt = Regex(""""?format"?\s*:\s*"([^"]+)"""").find(o)?.groupValues?.get(1)
            out.add(FlixSubtitle(url = url, language = lang, format = fmt))
        }
        return out
    }
}
