package com.csksy.anisnatch

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/*
 * anisnatch.to encrypts its API traffic: page tokens derive a byte marker
 * and a substitution alphabet, payloads travel as shuffled shifted-hex chunks
 * and responses are marker-prefixed xor'd gzip. Mirrors ajax.min.js.
 */
internal object AniSnatchCrypto {

    private const val MARKER = "strSMCconvert"

    class SiteConfig(
        val mark: ByteArray,
        val alphabet: String,
        val serverTime: Long,
        val fetchedAt: Long
    ) {
        // 60s slack, same as the site's timeOffset
        fun urlTime(nowMs: Long): Long {
            val nowSec = nowMs / 1000
            return nowSec + (serverTime - fetchedAt + 60)
        }
    }

    class ShiftCipher(private val alphabet: String) {

        private val size = alphabet.length

        private fun round(input: String, encrypt: Boolean): String {
            val sb = StringBuilder(input.length)
            for (ch in input) {
                val pos = alphabet.indexOf(ch)
                if (pos >= 0) {
                    val w = if (encrypt) (pos + 5) % size else (pos - 5 + size) % size
                    sb.append(alphabet[w])
                } else {
                    sb.append(ch)
                }
            }
            sb.reverse()
            return sb.toString()
        }

        fun shift(input: String, rounds: Int, encrypt: Boolean): String {
            var s = input
            repeat(rounds) { s = round(s, encrypt) }
            return s
        }
    }

    fun parseConfig(html: String): SiteConfig? {
        return try {
            val meta = Regex("name=\"token\" content=\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1) ?: return null
            val cfg = Regex("configToken = '([^']+)'")
                .find(html)?.groupValues?.get(1) ?: return null
            val serverTime = Regex("serverTime = (\\d+)")
                .find(html)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val version = Regex("version = '([\\d.]+)'")
                .find(html)?.groupValues?.get(1) ?: return null

            val raw = Base64.decode(cfg, Base64.DEFAULT)
            val key = (meta + version + serverTime).toByteArray(Charsets.UTF_8)
            val plain = ByteArray(raw.size)
            for (i in raw.indices) {
                plain[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
            }

            val json = JSONObject(String(plain, Charsets.UTF_8))
            val markArr = json.getJSONArray("mark")
            val mark = ByteArray(markArr.length())
            for (i in mark.indices) mark[i] = markArr.getInt(i).toByte()
            SiteConfig(mark, json.getString("key"), serverTime, System.currentTimeMillis() / 1000)
        } catch (e: Exception) {
            null
        }
    }

    // marker + round count let the server brute force the shift depth back
    fun encrypt(cipher: ShiftCipher, text: String, rnd: Random): String {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val hex = StringBuilder(b64.length * 2)
        for (c in b64) hex.append(Integer.toHexString(c.code))

        val innerRounds = rnd.nextInt(10) + 1
        val shifted = cipher.shift(hex.toString(), innerRounds, true)
        val payload = shifted + MARKER + innerRounds
        return cipher.shift(payload, rnd.nextInt(10) + 1, true)
    }

    class BuiltRequest(val url: String, val body: String, val authenticator: String)

    fun buildRequest(
        config: SiteConfig,
        cipher: ShiftCipher,
        endpoint: String,
        payload: JSONObject,
        rnd: Random
    ): BuiltRequest {
        val now = config.urlTime(System.currentTimeMillis())
        val token = encrypt(cipher, (now + 500).toString(), rnd)
        val authenticator = token.sumOf { it.code }.toString()

        val enc = encrypt(cipher, payload.toString(), rnd)
        val chunks = enc.chunked(25)
        val order = (chunks.indices).shuffled(rnd)

        val body = JSONObject()
        body.put("data", JSONArray(order.map { chunks[it] }))
        body.put("key", JSONArray(order))
        body.put("token", token)
        body.put("authenticator", authenticator)

        return BuiltRequest(
            "https://anisnatch.to/$endpoint/$now",
            body.toString(),
            authenticator
        )
    }

    fun decodeResponse(bytes: ByteArray, config: SiteConfig, authenticator: String): String? {
        val mark = config.mark
        var start = -1
        outer@ for (i in 0..bytes.size - mark.size) {
            for (j in mark.indices) {
                if (bytes[i + j] != mark[j]) continue@outer
            }
            start = i + mark.size
            break
        }
        if (start < 0) return null

        val key = authenticator.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(bytes.size - start)
        for (i in xored.indices) {
            xored[i] = (bytes[start + i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return try {
            GZIPInputStream(ByteArrayInputStream(xored)).readBytes().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            String(xored, Charsets.UTF_8)
        }
    }

    fun encodeFilterQuery(filter: String): String {
        val b64 = Base64.encodeToString(filter.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return b64.replace("=", "").reversed()
    }
}
