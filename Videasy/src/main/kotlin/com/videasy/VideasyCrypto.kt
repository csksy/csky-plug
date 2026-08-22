package com.videasy

import android.util.Base64

object VideasyCrypto {
    private val f = intArrayOf(
        1116352408, -1899447441, -1244634727, -373958238, 961987163, 1508970993,
        -1841140236, -1423663819, -720594547, 310598401, 607225278, 1426881987,
        1925078388, -2162078106, -2614888103, -3248222580
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

    private fun I(len: Int): Boolean = (len * (len + 1) and 1) == 1
    private fun b(e: Int): Boolean = (e * (e + 1) and 1) == 0

    private fun imul(a: Int, b: Int): Int = (a.toLong() * b.toLong()).toInt()

    private fun fnvHash(seed: String): Int {
        var hash = 2166136261
        for (i in seed.indices) {
            hash = imul(hash xor seed[i].code, 16777619)
        }
        return w(hash)
    }

    private class CipherState(
        val S: MutableMap<Int, Int> = mutableMapOf(),
        var acc: Int = 0
    )

    private fun buildCipher(seed: String, mediaId: Int): CipherState {
        if (I(seed.length)) {
            val S = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + S[i] + seed[i % seed.length].code) and 255
                val tmp = S[i]; S[i] = S[j]; S[j] = tmp
            }
            var acc = 1732584193
            for (i in seed.indices) {
                acc = v(acc xor imul(seed[i].code, f[15 and i]), 5)
            }
            acc = w(acc)
            val Smap = mutableMapOf<Int, Int>()
            for (i in 0 until 256) Smap[i] = S[i]
            return CipherState(Smap, acc)
        } else {
            val a = w(fnvHash(seed) xor w(mediaId xor 2654435769))
            val S = mutableMapOf<Int, Int>()
            var aa = a
            for (i in 0 until 8) {
                if (b(i)) {
                    val tIdx = aa % 61
                    aa = v(aa + 2654435769, 7 + (7 and i))
                    S[tIdx] = aa xor w(aa)
                    aa = w(aa + (S[tIdx] ?: 0))
                } else {
                    S[i] = f[15 and i]
                }
            }
            return CipherState(S, w(2779096485 xor aa))
        }
    }

    private fun prga(state: CipherState, t: Int): Int {
        val S = state.S
        val acc = state.acc
        val n = acc % 61
        val nInS = S.containsKey(n)
        val d = S[n] ?: 0
        val xorVal = imul(2654435769, t + 1)
        val aVal = d xor xorVal

        val l = if (nInS) {
            (acc xor aVal) or (acc and aVal)
        } else {
            acc xor aVal
        }

        val newAcc = w(v(l + acc, 31 and n) xor v(acc, 31 and imul(n, 7)) + 2654435769)
        S[n] = newAcc
        state.acc = newAcc
        return newAcc
    }

    fun decrypt(encrypted: String, seed: String, mediaId: Int): String {
        val trimmed = encrypted.trim()
        val padded = trimmed.replace('-', '+').replace('_', '/')
        val padLen = (4 - padded.length % 4) % 4
        val paddedStr = padded + "=".repeat(padLen)
        val data = Base64.decode(paddedStr, Base64.NO_WRAP)

        val state = buildCipher(seed, mediaId)
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
                throw Exception("decrypt failed: bad seed at $j")
            }
        }

        return String(result, h.size, result.size - h.size, Charsets.UTF_8)
    }
}
