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

// tmbplayer serves two link families: /hls/ playlists for streaming and /stream-vid/
// progressive files listed on its download page, both unlocked by the same encrypted
// api whose key material ships inside the page
object TmbPlayer {
    private const val TAG = "TheMoviesBoss"

    const val PLAYER_URL = "https://tmbplayer.site/"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmbSource(val file: String? = null, val label: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmbResult(
        val status: String? = null,
        val title: String? = null,
        val download_url: String? = null,
        val sources: List<TmbSource> = emptyList()
    )

    private data class Config(val pd: String, val ps: String, val kaken: String, val apx: String)

    // watch embed, gives the hls playlist plus the download page url
    suspend fun resolve(embedUrl: String): TmbResult? = resolveFrom(embedUrl, PLAYER_URL)

    // the download page carries a second config whose api returns progressive stream urls
    suspend fun resolveDownloads(downloadUrl: String, referer: String): TmbResult? =
        resolveFrom(downloadUrl, referer)

    private suspend fun resolveFrom(url: String, referer: String): TmbResult? {
        return try {
            val page = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to referer
                )
            ).text
            val config = readConfig(page) ?: return null
            apiCall(config, url)
        } catch (e: Exception) {
            Log.e(TAG, "tmbplayer page: ${e.message}")
            null
        }
    }

    private suspend fun apiCall(config: Config, referer: String): TmbResult? {
        return try {
            val base = Base64.decode(config.apx, Base64.DEFAULT)
                .decodeToString()
                .removeSuffix("api-config/")
            val body = app.post(
                "${base}api/?p=" + URLEncoder.encode(config.ps, "UTF-8"),
                headers = mapOf(
                    "User-Agent" to UA,
                    "Content-Type" to "text/plain",
                    "Referer" to referer,
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                requestBody = config.kaken.toRequestBody("text/plain".toMediaTypeOrNull())
            ).text
            val plain = decrypt(body, config.pd) ?: return null
            parseJson<TmbResult>(plain).takeIf { it.status == "ok" }
        } catch (e: Exception) {
            Log.e(TAG, "tmbplayer api: ${e.message}")
            null
        }
    }

    private fun readConfig(html: String): Config? {
        fun value(key: String): String? =
            Regex("(?:window\\.)?$key=\"([^\"]+)\"").find(html)?.groupValues?.get(1)

        val pd = value("pd") ?: return null
        val ps = value("ps") ?: return null
        val kaken = value("kaken") ?: return null
        val apx = value("apx") ?: return null
        return Config(pd, ps, kaken, apx)
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
