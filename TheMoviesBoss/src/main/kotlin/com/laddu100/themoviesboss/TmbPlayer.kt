package com.laddu100.themoviesboss

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmbSource(val file: String? = null, val label: String? = null, val type: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmbSourcesResponse(
    val status: String? = null,
    val message: String? = null,
    val title: String? = null,
    val sources: List<TmbSource> = emptyList()
)

object TmbPlayer {
    private const val TAG = "TheMoviesBoss"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val KF = "\uFF9F" // halfwidth semi-voiced sound mark
    private const val KD = "\u0414" // cyrillic de
    private const val KE = "\u03B5" // greek epsilon
    private const val KT = "\u0398" // greek theta
    private const val KM = "\uFF70" // halfwidth prolonged sound mark

    // atom values hold after the obfuscated setup finishes, the prolonged mark starts as 2 and gets +1
    private val atoms = mapOf(
        "($KF$KT$KF)" to 1,
        "($KF$KM$KF)" to 4,
        "($KF$KD$KF)" to 1,
        "(o^_^o)" to 3,
        "(c^_^o)" to 0
    )
    private val bsMarker = "($KF$KD$KF)[$KE$KF]"
    private val quoteMarker = "($KF$KD$KF)[$KF" + "o" + "$KF]"

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Config(val pd: String, val ps: String, val kaken: String, val qsx: String, val apx: String)

    suspend fun loadSources(embedUrl: String): List<TmbSource> {
        return try {
            val page = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to "https://tmbplayer.site/"
                )
            ).text
            val cfg = parseConfig(page) ?: return emptyList()
            val base = Base64.decode(cfg.apx, Base64.DEFAULT).decodeToString().removeSuffix("api-config/")
            val res = app.post(
                "${base}api/?p=" + URLEncoder.encode(cfg.ps, "UTF-8"),
                headers = mapOf(
                    "User-Agent" to UA,
                    "Content-Type" to "text/plain",
                    "Referer" to embedUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                requestBody = cfg.kaken.toRequestBody("text/plain".toMediaTypeOrNull())
            ).text
            val plain = decrypt(res, cfg.pd) ?: return emptyList()
            val parsed = parseJson<TmbSourcesResponse>(plain)
            if (parsed.status != "ok") emptyList() else parsed.sources
        } catch (e: Exception) {
            Log.e(TAG, "tmbplayer: ${e.message}")
            emptyList()
        }
    }

    private fun parseConfig(html: String): Config? {
        readPlain(html)?.let { return it }
        return decodeObfuscated(html)?.let { readPlain(it) }
    }

    private fun readPlain(source: String): Config? {
        fun value(key: String): String? =
            Regex("(?:window\\.)?$key=\"([^\"]+)\"").find(source)?.groupValues?.get(1)

        val pd = value("pd") ?: return null
        val ps = value("ps") ?: return null
        val kaken = value("kaken") ?: return null
        val qsx = value("qsx") ?: return null
        val apx = value("apx") ?: return null
        return Config(pd, ps, kaken, qsx, apx)
    }

    // the page sometimes ships the config behind an aaencode kaomoji script
    private fun decodeObfuscated(html: String): String? {
        return try {
            val anchor = html.lastIndexOf("['_']")
            if (anchor < 0) return null
            val after = html.substring(anchor + 5)
            val open = after.indexOf('(')
            if (open < 0) return null
            val body = after.substring(open + 1)
            val first = body.indexOf(quoteMarker)
            if (first < 0) return null
            val last = body.lastIndexOf(quoteMarker)
            if (last <= first) return null
            val region = body.substring(first + quoteMarker.length, last)
            region.split(bsMarker)
                .mapNotNull { seqToPayload(it) }
                .joinToString("")
                .let { unpackPacker(it) }
        } catch (e: Exception) {
            Log.e(TAG, "aaencode: ${e.message}")
            null
        }
    }

    private fun seqToPayload(seq: String): String? {
        val trimmed = seq.trim()
        if (trimmed.isEmpty() || trimmed == "+") return null
        val digits = splitTop(trimmed).map { evalTerm(it) }
        return String(Character.toChars(Integer.parseInt(digits.joinToString(""), 8)))
    }

    private fun splitTop(s: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var cur = StringBuilder()
        for (ch in s) {
            when (ch) {
                '(' -> { depth++; cur.append(ch) }
                ')' -> { depth--; cur.append(ch) }
                '+' -> if (depth == 0) { parts.add(cur.toString()); cur = StringBuilder() } else cur.append(ch)
                else -> cur.append(ch)
            }
        }
        parts.add(cur.toString())
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun evalTerm(term: String): Int {
        var t = term.trim()
        if (atoms.containsKey(t)) return atoms.getValue(t)
        if (t.startsWith("(") && t.endsWith(")") && wrapsWhole(t)) {
            return evalTerm(t.substring(1, t.length - 1))
        }
        var acc = 0
        var pending = "+"
        var value = StringBuilder()
        var depth = 0
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            when {
                ch == '(' -> { depth++; value.append(ch) }
                ch == ')' -> { depth--; value.append(ch) }
                (ch == '+' || ch == '-') && depth == 0 -> {
                    acc = combine(acc, pending, value.toString())
                    value = StringBuilder()
                    pending = ch.toString()
                }
                else -> value.append(ch)
            }
            i++
        }
        return combine(acc, pending, value.toString())
    }

    private fun combine(acc: Int, op: String, rawValue: String): Int {
        val value = rawValue.trim()
        if (value.isEmpty()) return acc
        val n = evalTerm(value)
        return if (op == "-") acc - n else acc + n
    }

    private fun wrapsWhole(t: String): Boolean {
        var depth = 0
        for (i in t.indices) {
            when (t[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0 && i < t.length - 1) return false }
            }
        }
        return depth == 0
    }

    private fun unpackPacker(payload: String): String {
        val regex = Regex("}\\('(.*)',(\\d+),(\\d+),'(.*?)'\\.split\\('\\|'\\)", RegexOption.DOT_MATCHES_ALL)
        val m = regex.find(payload) ?: return payload
        val packed = m.groupValues[1].replace("\\\\", "\\").replace("\\'", "'")
        val base = m.groupValues[2].toInt()
        val count = m.groupValues[3].toInt()
        val keys = m.groupValues[4].split("|")
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+_"
        fun encode(value: Int): String {
            var n = value
            var out = ""
            do {
                out = alphabet[n % base] + out
                n /= base
            } while (n > 0)
            return out
        }
        var result = packed
        for (i in count - 1 downTo 0) {
            val key = keys.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: continue
            result = Regex("\\b" + encode(i) + "\\b").replace(result, key)
        }
        return result
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val blocks = (length + 31) / 32
        val derived = ByteArray(blocks * 32)
        var offset = 0
        for (block in 1..blocks) {
            val saltBlock = salt + byteArrayOf(0, 0, 0, block.toByte())
            var u = hmacSha256(password, saltBlock)
            val chunk = u.copyOf()
            for (round in 1 until iterations) {
                u = hmacSha256(password, u)
                for (j in chunk.indices) chunk[j] = (chunk[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(chunk, 0, derived, offset, 32)
            offset += 32
        }
        return derived.copyOf(length)
    }

    // response = base64(salt + aes-256-cbc payload), key material derived from the page timestamp
    private fun decrypt(body: String, password: String): String? {
        return try {
            val raw = Base64.decode(body, Base64.DEFAULT)
            if (raw.size <= 16) return null
            val salt = raw.copyOfRange(0, 16)
            val cipherText = raw.copyOfRange(16, raw.size)
            val derived = pbkdf2Sha256(password.toByteArray(), salt, 10000, 48)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(derived, 0, 32, "AES"),
                IvParameterSpec(derived, 32, 16)
            )
            String(cipher.doFinal(cipherText))
        } catch (e: Exception) {
            Log.e(TAG, "decrypt: ${e.message}")
            null
        }
    }
}
