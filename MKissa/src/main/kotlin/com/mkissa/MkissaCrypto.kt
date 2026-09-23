package com.mkissa

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object MkissaCrypto {

    const val BUILD_ID = "175"
    private const val EPOCH_PERIOD = 604800000L
    private const val TS_PERIOD = 300000L
    private const val BOOT_PREFIX = "X8S061oCq:"
    private const val FRAG_MUL = 93
    private const val FRAG_ADD = 29

    private val HM = listOf(
        "jfTMXeWz1KY=",
        "sSiL9IX1c1k=",
        "jjOcW/T0DYU=",
        "vRF6mguezW8="
    )

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    internal fun g1Mask(buildId: String): ByteArray {
        val frags = HM.map { Base64.decode(it, Base64.DEFAULT) }
        val s = buildId.toByteArray(Charsets.ISO_8859_1)
        val at = ByteArray(32)
        for (c in 0 until 32) {
            val ch = if (s.isNotEmpty()) s[c % s.size].toInt() and 0xFF else 0
            at[c] = (ch xor (((c * 34) + 47) and 0xFF)).toByte()
        }
        val mask = ByteArray(32)
        for (u in 0 until 4) {
            for (g in 0 until 8) {
                mask[u * 8 + g] = (frags[u][g].toInt() and 0xFF xor (at[u * 8 + g].toInt() and 0xFF)
                        xor (((u * FRAG_MUL) + (g * FRAG_ADD)) and 0xFF)).toByte()
            }
        }
        return mask
    }

    private val mask: ByteArray by lazy { g1Mask(BUILD_ID) }

    fun currentEpoch(): Long = System.currentTimeMillis() / EPOCH_PERIOD

    fun xAaBoot(epoch: Long, lane: String): String {
        val f = hmacSha256(mask, (BOOT_PREFIX + BUILD_ID).toByteArray(Charsets.UTF_8))
        val preimage = listOf(BUILD_ID, "mkissa", "mkissa.to", epoch.toString(), lane)
            .joinToString("|")
        return hmacSha256(f, preimage.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun deriveKey(partB: String): ByteArray {
        val partb = Base64.decode(partB, Base64.DEFAULT)
        if (partb.size < 32) throw IllegalArgumentException("partB too short")
        val key = ByteArray(32)
        for (i in 0 until 32) {
            key[i] = (partb[i] xor mask[i % 32])
        }
        return key
    }

    fun buildAaReq(key: ByteArray, queryHash: String, lane: String, epoch: Long): String {
        val ts = (System.currentTimeMillis() / TS_PERIOD) * TS_PERIOD
        val payload = buildString {
            append("{\"v\":1,\"ts\":")
            append(ts)
            append(",\"epoch\":")
            append(epoch)
            append(",\"buildId\":\"")
            append(BUILD_ID)
            append("\",\"qh\":\"")
            append(queryHash)
            append("\",\"k\":\"")
            append(lane)
            append("\"}")
        }
        val ivSeed = "$epoch:$BUILD_ID:$queryHash:$ts:$lane"
        val iv = sha256(ivSeed.toByteArray(Charsets.UTF_8)).copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val raw = ByteArray(1 + 12 + ct.size)
        raw[0] = 1
        System.arraycopy(iv, 0, raw, 1, 12)
        System.arraycopy(ct, 0, raw, 13, ct.size)
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    fun decryptPayload(data: String, key: ByteArray): String {
        val raw = Base64.decode(data, Base64.DEFAULT)
        if (raw.isEmpty() || raw[0].toInt() != 1) throw IllegalArgumentException("unsupported payload version")
        val iv = raw.copyOfRange(1, 13)
        val ct = raw.copyOfRange(13, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val pt = cipher.doFinal(ct)
        return String(pt, Charsets.UTF_8)
    }

    fun hexToUrl(hex: String): String {
        val out = StringBuilder()
        var i = 0
        while (i + 1 < hex.length) {
            val b = hex.substring(i, i + 2).toInt(16) xor 56
            out.append(b.toChar())
            i += 2
        }
        return out.toString()
    }

    fun sha256Hex(text: String): String =
        sha256(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
