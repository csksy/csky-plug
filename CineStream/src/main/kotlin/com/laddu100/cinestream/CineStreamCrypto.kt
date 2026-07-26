package com.laddu100.cinestream

import android.util.Base64
import com.lagradost.api.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// =========================================================================
// CryptoJS-compatible AES decryption
// =========================================================================
// CineStream's API encrypts all responses with CryptoJS AES using the
// passphrase "Penguin". CryptoJS uses a passphrase-based key derivation
// scheme (OpenSSL's EVP_BytesToKey with MD5) that produces a 32-byte key
// and 16-byte IV from the passphrase + a random 8-byte salt.
//
// The encrypted format is:
//   "Salted__" (8 bytes) + salt (8 bytes) + ciphertext
//
// The key derivation:
//   D_0 = MD5(passphrase + salt)
//   D_1 = MD5(D_0 + passphrase + salt)
//   D_2 = MD5(D_1 + passphrase + salt)
//   key = D_0 + D_1 (32 bytes for AES-256)
//   iv  = D_2 (16 bytes)
//
// The output is base64url-encoded (using - and _ instead of + and /).

object CineStreamCrypto {
    private const val TAG = "CineStream_Crypto"
    private const val PASSPHRASE = "Penguin"

    // Decrypt a CryptoJS AES-encrypted base64url string.
    // Returns the decrypted plaintext, or null on failure.
    fun decrypt(encryptedBase64Url: String): String? {
        return try {
            // Convert base64url to standard base64
            val b64 = encryptedBase64Url
                .replace("-", "+")
                .replace("_", "/")
            // Add padding if needed
            val padded = when (b64.length % 4) {
                2 -> "$b64=="
                3 -> "$b64="
                else -> b64
            }
            val fullCipher = Base64.decode(padded, Base64.DEFAULT)

            // Check for "Salted__" prefix
            if (fullCipher.size < 16 || !String(fullCipher, 0, 8, Charsets.UTF_8).startsWith("Salted__")) {
                Log.e(TAG, "decrypt: missing Salted__ prefix")
                return null
            }

            val salt = fullCipher.copyOfRange(8, 16)
            val cipherText = fullCipher.copyOfRange(16, fullCipher.size)

            // EVP_BytesToKey with MD5 — derive 48 bytes (32 key + 16 IV)
            val passphraseBytes = PASSPHRASE.toByteArray(Charsets.UTF_8)
            val md = MessageDigest.getInstance("MD5")
            var keyMaterial = md.digest(passphraseBytes + salt)
            var combined = keyMaterial
            while (combined.size < 48) {
                keyMaterial = md.digest(keyMaterial + passphraseBytes + salt)
                combined += keyMaterial
            }
            val keyBytes = combined.copyOfRange(0, 32)
            val ivBytes = combined.copyOfRange(32, 48)

            // Decrypt with AES-256-CBC
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            val decrypted = cipher.doFinal(cipherText)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt error: ${e.message}")
            null
        }
    }
}
