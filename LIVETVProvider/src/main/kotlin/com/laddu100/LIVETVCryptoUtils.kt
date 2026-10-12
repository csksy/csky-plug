package com.laddu100

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object LIVETVCryptoUtils {

    private fun hexBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private data class KeyInfo(val key: ByteArray, val iv: ByteArray)

    private val KEYS by lazy {
        listOf(
            KeyInfo(
                hexBytes("3368487a78594167534749382f68616d"),
                hexBytes("557143766b766a656345497a38343256")
            ),
            KeyInfo(
                hexBytes("4d7165594743543441594b6f484b7254"),
                hexBytes("6f484b725451755078387a6c386f4a2b")
            )
        )
    }

    private fun tryDecrypt(cipherBytes: ByteArray, keyInfo: KeyInfo): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyInfo.key, "AES"),
                IvParameterSpec(keyInfo.iv)
            )
            val plain = cipher.doFinal(cipherBytes)
            val text = String(plain, Charsets.UTF_8)
            if (!text.startsWith("{") && !text.startsWith("[") && !text.contains("http", ignoreCase = true)) {
                null
            } else {
                text
            }
        } catch (_: Exception) {
            null
        }
    }

    fun decryptLIVETV(body: String?): String? {
        return try {
            val raw = body?.trim().orEmpty()
            if (raw.isEmpty()) return null

            val cleanBase64 = raw
                .replace("\n", "")
                .replace("\r", "")
                .replace(" ", "")
                .replace("\t", "")

            val cipherBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            for (keyInfo in KEYS) {
                val result = tryDecrypt(cipherBytes, keyInfo)
                if (result != null) return result
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
