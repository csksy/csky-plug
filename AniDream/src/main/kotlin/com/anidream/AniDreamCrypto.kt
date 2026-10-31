package com.anidream

import android.util.Base64
import com.lagradost.api.Log
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AniDreamCrypto {

    private const val HKDF_INFO = "anidream-stream-v1"
    private const val B64 = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    fun newKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    // the server wants the raw uncompressed point 0x04||X||Y in url safe base64
    fun publicKeyString(public: PublicKey): String {
        val w = (public as java.security.interfaces.ECPublicKey).w
        val raw = ByteArray(65)
        raw[0] = 0x04
        System.arraycopy(fixed32(w.affineX), 0, raw, 1, 32)
        System.arraycopy(fixed32(w.affineY), 0, raw, 33, 32)
        return Base64.encodeToString(raw, B64)
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        val out = ByteArray(32)
        if (bytes.size >= 32) {
            System.arraycopy(bytes, bytes.size - 32, out, 0, 32)
        } else {
            System.arraycopy(bytes, 0, out, 32 - bytes.size, bytes.size)
        }
        return out
    }

    fun open(
        private: PrivateKey,
        epk: String,
        iv: String,
        ct: String,
        aad: String
    ): String? {
        if (epk.isEmpty() || iv.isEmpty() || ct.isEmpty()) return null
        return try {
            val ephemeral = decodePublic(epk)
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(private)
            agreement.doPhase(ephemeral, true)
            val shared = agreement.generateSecret()

            val key = hkdfSha256(shared, ByteArray(32), HKDF_INFO.toByteArray(Charsets.UTF_8), 32)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, Base64.decode(iv, B64))
            )
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(Base64.decode(ct, B64)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d("AniDream", "sealed open failed: ${e.message}")
            null
        }
    }

    private fun decodePublic(b64: String): PublicKey {
        val raw = Base64.decode(b64, B64)
        if (raw.size != 65 || raw[0].toInt() != 0x04) {
            throw IllegalArgumentException("bad ephemeral key")
        }
        val x = fixed32(BigInteger(1, raw.copyOfRange(1, 33)))
        val y = fixed32(BigInteger(1, raw.copyOfRange(33, 65)))
        val params = ecParams()
        val spec = ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), params)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private var cachedParams: ECParameterSpec? = null

    private fun ecParams(): ECParameterSpec {
        cachedParams?.let { return it }
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val generated = generator.generateKeyPair().public as java.security.interfaces.ECPublicKey
        val params = generated.params
        cachedParams = params
        return params
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ArrayList<Byte>(length)
        var t = ByteArray(0)
        var counter = 1
        while (out.size < length) {
            t = mac.doFinal(t + info + byteArrayOf(counter.toByte()))
            for (b in t) out.add(b)
            counter++
        }
        return ByteArray(length) { i -> out[i] }
    }
}
