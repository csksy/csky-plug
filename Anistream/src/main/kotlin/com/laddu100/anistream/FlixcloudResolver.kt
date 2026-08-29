package com.laddu100.anistream

import android.util.Base64
import com.lagradost.cloudstream3.app
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * FlixCloud (zen provider) resolver.
 *
 * Chain (all verified live):
 *  1. anistream.one/api/flixcloud?slug=&anilistId=&episode= → playerUrl
 *  2. GET playerUrl (Referer anistream.one) — SvelteKit page carrying:
 *     obfuscation_seed, w_payload (randomized WASM), obfuscated_crypto_data,
 *     keyFrag2 + token fields (names derived from the seed)
 *  3. GET flixcloud.cc/api/m3u8/{token} — MUST reuse the same HTTP connection
 *     as step 2 (token is bound to the TLS session; the shared OkHttp client
 *     pools connections so sequential calls reuse it, mirroring the browser)
 *  4. MiniWasm decrypt → PBKDF2 seed → AES-CBC → plain m3u8 URL
 *
 * Also extracts the per-release subtitle list (srt/ass) and the release title.
 */
object FlixcloudResolver {

    private val HEADERS_SITE = mapOf(
        "User-Agent" to AnistreamApi.USER_AGENT,
        "Referer" to "${AnistreamApi.MAIN_URL}/",
    )
    private val HEADERS_PLAYER = mapOf(
        "User-Agent" to AnistreamApi.USER_AGENT,
        "Referer" to "https://flixcloud.cc/",
    )

    data class Result(
        val m3u8: String,
        val subtitles: List<FlixSubtitle>,
        val releaseTitle: String?
    )

    /** Resolve a flixcloud embed URL to a playable m3u8 + subtitle list. */
    suspend fun resolve(embedUrl: String): Result? {
        return try {
            val page = app.get(embedUrl, headers = HEADERS_SITE).text
            val region = page.substringAfter("node_ids", "")
            if (region.isBlank()) return null

            val seed = Regex("""obfuscation_seed:"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null
            val wasmB64 = Regex("""w_payload:"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null

            // SHA-256 chains → field names
            val e = shaChain(seed)
            val s2 = shaChain(e)
            val keyField = "kf_${e.substring(8, 16)}"
            val ivField = "ivf_${e.substring(16, 24)}"
            val containerField = "cd_${e.substring(24, 32)}"
            val arrayField = "ad_${e.substring(32, 40)}"
            val objectField = "od_${e.substring(40, 48)}"
            val tokenField = "${e.substring(48, 64)}_${e.substring(56, 64)}"
            val keyFrag2Field = "${s2.substring(0, 16)}_${s2.substring(16, 24)}"

            // obfuscated_crypto_data → {container:{array:[{object:{kf:…,ivf:…}}]}}
            val obfStr = extractJsObject(region, "obfuscated_crypto_data:") ?: return null
            val kf = Regex(""""?$keyField"?\s*:\s*"([^"]+)"""").find(obfStr)?.groupValues?.get(1) ?: return null
            val ivf = Regex(""""?$ivField"?\s*:\s*"([^"]+)"""").find(obfStr)?.groupValues?.get(1) ?: return null
            val token = Regex(""""?$tokenField"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null
            val keyFrag2 = Regex(""""?$keyFrag2Field"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null

            // 3) token API — same pooled connection
            val path = Regex("""/e/[^?#]+""").find(embedUrl)?.value ?: return null
            val tokenResp = app.get(
                "https://flixcloud.cc/api/m3u8/$token",
                headers = HEADERS_SITE + mapOf("Referer" to embedUrl)
            ).text
            val vidField = shaHex(token + "vid").substring(0, 10)
            val keyField2 = shaHex(token + "key").substring(0, 10)
            val encVideo = Regex(""""$vidField"\s*:\s*"([^"]+)"""").find(tokenResp)?.groupValues?.get(1) ?: return null
            val encKey = Regex(""""$keyField2"\s*:\s*"([^"]+)"""").find(tokenResp)?.groupValues?.get(1) ?: return null

            // 4) WASM decrypt of (frag1, keyFrag2, encKey)
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

            // 5) AES key: PBKDF2(keySeed, salt=seed, 1000, 32) XOR seed chars → SHA-256
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

            // 6) AES-CBC decrypt encVideo
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(b64d(ivf)))
            val urlBytes = cipher.doFinal(b64d(encVideo))
            val url = String(urlBytes, Charsets.UTF_8).trim()
            if (!url.startsWith("http")) return null

            // subtitles
            val subs = extractSubtitles(region)
            val title = Regex("""<title>([^<]*)</title>""").find(page)?.groupValues?.get(1)
            Result(url, subs, title)
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------- helpers

    /** e = sha256(e + i) for i in 0..2 (hex string chain, mirrors hn()). */
    private fun shaChain(seed: String): String {
        var e = seed
        for (i in 0 until 3) e = shaHex(e + i)
        return e
    }

    private fun shaHex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    /** Brace-matched extraction of a JS object literal following `marker`. */
    private fun extractJsObject(s: String, marker: String): String? {
        val i = s.indexOf(marker)
        if (i < 0) return null
        val start = s.indexOf('{', i)
        if (start < 0) return null
        var depth = 0
        var j = start
        var inStr = false
        var esc = false
        while (j < s.length) {
            val c = s[j]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(start, j + 1)
                    }
                }
            }
            j++
        }
        return null
    }

    /** Parse subtitles:[{url:…,language:…,format:…}] from the page payload. */
    private fun extractSubtitles(region: String): List<FlixSubtitle> {
        val out = mutableListOf<FlixSubtitle>()
        val m = Regex("""subtitles:\[([^\]]*)\]""").find(region) ?: return out
        val body = m.groupValues[1]
        if (body.isBlank()) return out
        val objRe = Regex("""\{([^{}]*)\}""")
        for (om in objRe.findAll(body)) {
            val o = om.groupValues[1]
            val url = Regex("""url:"([^"]+)"""").find(o)?.groupValues?.get(1) ?: continue
            val lang = Regex("""language:"([^"]+)"""").find(o)?.groupValues?.get(1)
            val fmt = Regex("""format:"([^"]+)"""").find(o)?.groupValues?.get(1)
            out.add(FlixSubtitle(url = url, language = lang, format = fmt))
        }
        return out
    }
}
