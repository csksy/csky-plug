package com.csksy.streamcorner

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * streamcorner.st ships its provider data through a web worker whose request
 * and response bodies are encrypted. The worker source carries a small key
 * plus a large encoded blob; those decrypt into the session keys and the
 * cipher parameters. Keys rotate on every site deploy, so the worker source
 * is fetched live and parsed here instead of being hardcoded.
 */
internal object CornerCrypto {

    private val BOOT_TABLE = intArrayOf(
        185, 243, 94, 251, 153, 162, 126, 77, 155, 41, 182, 62, 200, 173, 252, 244
    )

    private val SALSA_CONST = intArrayOf(1634760805, 857760878, 2036477234, 1797285236)

    private val random = SecureRandom()

    private fun rotl8(v: Int, n: Int): Int = ((v shl n) or (v ushr (8 - n))) and 0xFF

    private fun rotr8(v: Int, n: Int): Int = ((v ushr n) or (v shl (8 - n))) and 0xFF

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    private fun b64url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // extracts the `dr` key and the `Ar` blob from the live worker source
    fun parseWorkerConstants(workerSrc: String): Pair<ByteArray, ByteArray> {
        val drMatch = Regex("dr=(\\[\"[A-Za-z0-9+/=]{16,32}\"\\])").find(workerSrc)
            ?: throw IllegalStateException("worker key not found")
        val dr = Base64.decode(
            JSONArray(drMatch.groupValues[1]).getString(0), Base64.DEFAULT
        )
        val arMatch = Regex("Ar=(\\[\"[^\"]+\"(?:,\"[^\"]+\")*\\])").find(workerSrc)
            ?: throw IllegalStateException("worker blob not found")
        val arParts = JSONArray(arMatch.groupValues[1])
        val chunks = (0 until arParts.length()).map { Base64.decode(arParts.getString(it), Base64.DEFAULT) }
        val ar = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (chunk in chunks) {
            chunk.copyInto(ar, pos)
            pos += chunk.size
        }
        return dr to ar
    }

    fun bootstrapDecrypt(ar: ByteArray, dr: ByteArray): ByteArray {
        val out = ByteArray(ar.size)
        var c = 117
        var y = 84 xor (dr[1].toInt() and 0xFF)
        for (i in ar.indices) {
            val ob = ar[i].toInt() and 0xFF
            val h = (dr[i and 15].toInt() and 0xFF) xor BOOT_TABLE[i and 15] xor y xor
                ((139 * i) and 0xFF) xor
                ((1293491885 ushr (8 * (3 and i))) and 0xFF) xor c
            out[i] = (ob xor h).toByte()
            y = ob
            c = rotl8((c xor ob xor (dr[(i + 7) and 15].toInt() and 0xFF)) and 0xFF, 1)
        }
        return out
    }

    private fun typedBytes(obj: JSONObject): ByteArray {
        val v = obj.getJSONArray("v")
        return ByteArray(v.length()) { v.getInt(it).toByte() }
    }

    // the six spec arrays feed a small stateful byte cipher; indexes 0, 1, 3
    // hold the api key, the mac key seed and the request nonce constant
    fun specDecrypt(spec: JSONArray): ByteArray {
        val init = spec.getInt(0)
        val key2 = spec.getInt(1)
        val data = spec.getJSONArray(2)
        val table = spec.getJSONArray(3)
        val pad = spec.getJSONArray(4)
        val perm = spec.getJSONArray(5)
        val out = ByteArray(data.length())
        var n = init
        for (o in 0 until data.length()) {
            val pi = perm.getInt(o)
            val mixed = rotl8(data.getInt(o), 7 and (n xor key2)) xor
                ((table.getInt(pi) + (n xor ((13 * o) and 0xFF))) and 0xFF) xor
                ((key2 + 17 * o) and 0xFF)
            val r = rotr8(mixed and 0xFF, 7 and (pad.getInt(o) xor n))
            out[o] = r.toByte()
            n = rotl8(n, 1) xor ((r + pad.getInt(o) + o) and 0xFF)
        }
        return out
    }

    private fun salsaBlock(key: ByteArray, nonce: ByteArray, counter: Long): ByteArray {
        val x = IntArray(16)
        val o = IntArray(16)
        o[0] = SALSA_CONST[0]; o[5] = SALSA_CONST[1]; o[10] = SALSA_CONST[2]; o[15] = SALSA_CONST[3]
        fun le32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
        fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))
        for (s in 0..3) {
            o[1 + s] = le32(key, 4 * s)
            o[11 + s] = le32(key, 16 + 4 * s)
        }
        o[6] = le32(nonce, 0)
        o[7] = le32(nonce, 4)
        o[8] = counter.toInt()
        o[9] = (counter ushr 32).toInt()
        System.arraycopy(o, 0, x, 0, 16)
        fun qr(a: Int, b: Int, c: Int, shift: Int) {
            x[a] = x[a] xor rotl((x[b] + x[c]), shift)
        }
        repeat(6) {
            qr(4, 0, 12, 7); qr(8, 4, 0, 9); qr(12, 8, 4, 13); qr(0, 12, 8, 18)
            qr(9, 5, 1, 7); qr(13, 9, 5, 9); qr(1, 13, 9, 13); qr(5, 1, 13, 18)
            qr(14, 10, 6, 7); qr(2, 14, 10, 9); qr(6, 2, 14, 13); qr(10, 6, 2, 18)
            qr(3, 15, 11, 7); qr(7, 3, 15, 9); qr(11, 7, 3, 13); qr(15, 11, 7, 18)
            qr(1, 0, 3, 7); qr(2, 1, 0, 9); qr(3, 2, 1, 13); qr(0, 3, 2, 18)
            qr(6, 5, 4, 7); qr(7, 6, 5, 9); qr(4, 7, 6, 13); qr(5, 4, 7, 18)
            qr(11, 10, 9, 7); qr(8, 11, 10, 9); qr(9, 8, 11, 13); qr(10, 9, 8, 18)
            qr(12, 15, 14, 7); qr(13, 12, 15, 9); qr(14, 13, 12, 13); qr(15, 14, 13, 18)
        }
        val out = ByteArray(64)
        for (s in 0..15) {
            val w = (x[s] + o[s])
            out[4 * s] = (w and 0xFF).toByte()
            out[4 * s + 1] = ((w ushr 8) and 0xFF).toByte()
            out[4 * s + 2] = ((w ushr 16) and 0xFF).toByte()
            out[4 * s + 3] = ((w ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun salsaXor(data: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var block = 0L
        var i = 0
        while (i < data.size) {
            val ks = salsaBlock(key, nonce, block)
            val len = minOf(64, data.size - i)
            for (s in 0 until len) out[i + s] = (data[i + s].toInt() xor ks[s].toInt()).toByte()
            i += 64
            block++
        }
        return out
    }

    class Keys(workerSrc: String) {
        private val keyN: ByteArray
        private val keyEr: ByteArray
        private val nonceCr: ByteArray
        private val saltReq: ByteArray
        private val saltReqMac: ByteArray
        private val saltRespMac: ByteArray
        private val saltResp: ByteArray

        init {
            val (dr, ar) = parseWorkerConstants(workerSrc)
            val spec = JSONObject(String(bootstrapDecrypt(ar, dr), Charsets.UTF_8))
            val specs = spec.getJSONArray("specs")
            keyN = specDecrypt(specs.getJSONArray(0))
            keyEr = specDecrypt(specs.getJSONArray(1))
            nonceCr = specDecrypt(specs.getJSONArray(3))
            val consts = spec.getJSONArray("consts")
            fun u8(i: Int): ByteArray = typedBytes(consts.getJSONObject(i))
            saltReq = u8(16)
            saltReqMac = u8(20)
            saltRespMac = u8(22)
            saltResp = u8(25)
        }

        private fun requestKey(): ByteArray = sha256(concat(keyN, keyEr, saltReq))

        fun buildRequest(provider: String): ByteArray {
            val key = requestKey()
            val nonce = ByteArray(8).also { random.nextBytes(it) }
            val bucket = System.currentTimeMillis() / 300000L
            val payload = JSONArray()
                .put(b64url(nonceCr))
                .put(provider.trim().lowercase())
                .put(bucket)
            val ct = salsaXor(payload.toString().toByteArray(Charsets.UTF_8), key, nonce)
            val mac = sha256(concat(key, nonce, ct, saltReqMac)).copyOf(12)
            return concat(byteArrayOf(10), nonce, mac, ct)
        }

        fun decryptResponse(resp: ByteArray, reqBody: ByteArray): String {
            if (resp.size <= 21 || resp[0].toInt() != 4) throw IllegalStateException("bad response format")
            if (reqBody.size <= 21 || reqBody[0].toInt() != 10) throw IllegalStateException("bad request echo")
            val reqNonce = reqBody.copyOfRange(1, 9)
            val reqMac = reqBody.copyOfRange(9, 21)
            val reqCt = reqBody.copyOfRange(21, reqBody.size)
            val reqKey = requestKey()
            val expectMac = sha256(concat(reqKey, reqNonce, reqCt, saltReqMac)).copyOf(12)
            if (!constantTimeEquals(reqMac, expectMac)) throw IllegalStateException("request mac mismatch")
            val plain = JSONArray(
                String(salsaXor(reqCt, reqKey, reqNonce), Charsets.UTF_8)
            )
            val bucket = plain.getLong(2)
            val nowBucket = System.currentTimeMillis() / 300000L
            if (kotlin.math.abs(nowBucket - bucket) > 1) throw IllegalStateException("stale request")
            val nonceConstBytes = plain.getString(0).toByteArray(Charsets.UTF_8)
            val providerBytes = plain.getString(1).toByteArray(Charsets.UTF_8)
            val bucketBytes = ByteArray(4)
            bucketBytes[0] = ((bucket ushr 24) and 0xFF).toByte()
            bucketBytes[1] = ((bucket ushr 16) and 0xFF).toByte()
            bucketBytes[2] = ((bucket ushr 8) and 0xFF).toByte()
            bucketBytes[3] = (bucket and 0xFF).toByte()
            val respKey = sha256(
                concat(keyN, keyEr, nonceConstBytes, reqNonce, providerBytes, bucketBytes, saltResp)
            )
            val nonce = resp.copyOfRange(1, 9)
            val mac = resp.copyOfRange(9, 21)
            val ct = resp.copyOfRange(21, resp.size)
            val expectRespMac = sha256(concat(respKey, nonce, ct, saltRespMac)).copyOf(12)
            if (!constantTimeEquals(mac, expectRespMac)) throw IllegalStateException("response mac mismatch")
            return String(salsaXor(ct, respKey, nonce), Charsets.UTF_8)
        }
    }

    /*
     * Embed urls of the form host/?p=<base32> carry the real stream address.
     * The layout has been stable: base32 decode, add 181 to every byte, then
     * xor with a fixed key. The result is JSON with the stream url and, for
     * encrypted dash channels, the clear key pair.
     */
    private val EMBED_KEY = "0000@streamcorner@0000".toByteArray(Charsets.UTF_8)

    private fun base32Decode(s: String): ByteArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (ch in s) {
            val v = when {
                ch in 'A'..'Z' -> ch - 'A'
                ch in 'a'..'z' -> ch - 'a'
                ch in '2'..'7' -> ch - '2' + 26
                else -> continue
            }
            acc = (acc shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.add(((acc shr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        if (out.isEmpty()) return null
        return out.toByteArray()
    }

    fun decryptEmbedParam(p: String): JSONObject? {
        return try {
            val payload = base32Decode(p) ?: return null
            val out = ByteArray(payload.size)
            for (i in payload.indices) {
                out[i] = (((payload[i].toInt() and 0xFF) + 181) and 0xFF xor
                    EMBED_KEY[i % EMBED_KEY.size].toInt()).toByte()
            }
            JSONObject(String(out, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }
}
