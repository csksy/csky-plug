package com.videasy

import android.util.Base64

object VideasyCrypto {
    private val f = intArrayOf(
        1116352408, -1899447441, -1244634727, -373958238, 961987163, 1508970993,
        -1841140236, -1423663819, -720594547, 310598401, 607225278, 1426881987,
        1925078388, 2132889190, 1680079193, 1046744716
    )
    private val h = byteArrayOf(109, 118, 109, 49)

    private fun w(e: Int): Int {
        var x = e
        x = x xor (x ushr 16)
        x = (x.toLong() * 2246822507L).toInt()
        x = x xor (x ushr 13)
        x = (x.toLong() * 3266489909L).toInt()
        x = x xor (x ushr 16)
        return x
    }

    private fun v(e: Int, t: Int): Int {
        val shift = t and 31
        return if (shift == 0) e else (e ushr shift) or (e shl (32 - shift))
    }

    private fun b(e: Int): Boolean = (e * (e + 1) and 1) == 0

    private fun imul(a: Int, b: Int): Int = (a.toLong() * b.toLong()).toInt()

    private fun fnvHash(key: String): Int {
        var hash = -2128831035
        for (i in key.indices) {
            hash = imul(hash xor key[i].code, 16777619)
        }
        return w(hash)
    }

    private class CipherState(
        val S: MutableMap<Int, Int> = mutableMapOf(),
        var acc: Int = 0
    )

    private fun buildCipher(key: String, mediaId: Int): CipherState {
        val a = w(fnvHash(key) xor w(mediaId xor -1639481527))
        val S = mutableMapOf<Int, Int>()
        var aa = a
        for (i in 0 until 8) {
            if (b(i)) {
                val tIdx = aa % 61
                aa = v(aa + -1639481527, 7 + (7 and i))
                S[tIdx] = aa xor w(aa)
                aa = w(aa + (S[tIdx] ?: 0))
            } else {
                S[i] = f[15 and i]
            }
        }
        return CipherState(S, w(-1515870811 xor aa))
    }

    private fun prga(state: CipherState, t: Int): Int {
        val S = state.S
        val acc = state.acc
        val n = acc % 61
        val nInS = S.containsKey(n)
        val d = S[n] ?: 0
        val xorVal = imul(-1639481527, t + 1)
        val aVal = d xor xorVal

        val l = if (nInS) {
            (acc xor aVal) or (acc and aVal)
        } else {
            acc xor aVal
        }

        val newAcc = w(v(l + acc, 31 and n) xor v(acc, 31 and imul(n, 7)) + -1639481527)
        S[n] = newAcc
        state.acc = newAcc
        return newAcc
    }

    fun decrypt(encrypted: String, key: String, seed: String, mediaId: Int): String {
        val trimmed = encrypted.trim()
        val padded = trimmed.replace('-', '+').replace('_', '/')
        val padLen = (4 - padded.length % 4) % 4
        val paddedStr = padded + "=".repeat(padLen)
        val data = Base64.decode(paddedStr, Base64.NO_WRAP)

        val state = buildCipher(key, mediaId)
        val keystream = ByteArray(data.size)
        var o = 0
        var i = 0
        while (i < data.size) {
            val t = prga(state, o++)
            keystream[i] = (t and 0xFF).toByte()
            i++
            if (i < data.size) { keystream[i] = ((t ushr 8) and 0xFF).toByte(); i++ }
            if (i < data.size) { keystream[i] = ((t ushr 16) and 0xFF).toByte(); i++ }
            if (i < data.size) { keystream[i] = ((t ushr 24) and 0xFF).toByte(); i++ }
        }

        val result = ByteArray(data.size)
        for (j in data.indices) {
            result[j] = (data[j].toInt() xor keystream[j].toInt()).toByte()
        }

        for (j in h.indices) {
            if (result[j] != h[j]) {
                throw Exception("decrypt failed at $j: ${result[j]} != ${h[j]}")
            }
        }

        return String(result, h.size, result.size - h.size, Charsets.UTF_8)
    }
}
