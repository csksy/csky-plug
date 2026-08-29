package com.laddu100

import android.util.Base64

// miruro fronts its streams with two rotating proxies. the target url (and an
// optional referer) travel base64url-encoded and xor'd with a key shipped in
// the site bundle, and the path suffix decides how the proxy serves the body:
// /pl.m3u8 rewrites an hls playlist so its segments stay proxied, /sub.vtt
// passes subtitle text through untouched. routing subtitles this way is what
// the site does, and it sidesteps referer-locked subtitle cdns entirely
object MiruroProxy {

    private val PROXY_A = "https://s1.watami.win/"
    private val PROXY_B = "https://s1.piltover.li/"

    private val XOR_KEY = byteArrayOf(
        0xA5.toByte(), 0x4D, 0x38, 0x9C.toByte(), 0x18, 0x52, 0x27, 0xD9.toByte(),
        0xFD.toByte(), 0x3E.toByte(), 0x7F.toByte(), 0x06, 0x43, 0xE2.toByte(), 0x7E, 0xDB.toByte()
    )

    private fun encode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            out[i] = (bytes[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return Base64.encodeToString(out, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun subtitleUrl(url: String, referer: String?): String {
        return build(url, referer, "sub.vtt")
    }

    fun hlsUrl(url: String, referer: String?): String {
        return build(url, referer, "pl.m3u8")
    }

    private fun build(target: String, referer: String?, suffix: String): String {
        // the site load balances across the two proxies, mirroring that with a
        // stable hash keeps any one proxy from taking the full load
        val base = if ((target.hashCode() and 1) == 0) PROXY_A else PROXY_B
        val sb = StringBuilder(base).append(encode(target))
        if (!referer.isNullOrBlank()) {
            sb.append('~').append(encode(referer))
        }
        return sb.append('/').append(suffix).toString()
    }
}
