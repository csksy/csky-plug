package com.laddu100

import com.lagradost.cloudstream3.app
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Exact port of anidap.lol's client-side URL pipeline (assets/api-*.js) plus the
 * fast link-validation client (AniSnatch pattern).
 *
 * Everything in here was reverse-engineered from the live site and verified
 * against the real backend - no guesswork:
 *
 *  - uwu proxy:   https://cdnx.aniwatchtv.site/uwu/<token>
 *                 token = base64url( XOR( utf8(url) + 0x00 + utf8(key), "10b06cdc1ca48c9fb0b94af97cc040cf" ) )
 *  - shiro proxy: https://hls.dramavideo.se/media/<hex( XOR(utf8(url), 137 ) )>&origin=https://kem.clvd.xyz/
 *  - global rewrites: vivibebe.site/public/stream/ -> hawk.aniwatchtv.site/media/
 *                     playeng.animeapps.top/r2/<p>  -> bd.aniwatchtv.site/media/<p>
 *  - per-provider handlers: sora/yuki/uwu/kiwi/miku -> uwu proxy with fixed keys,
 *                           mochi -> host swap, beep -> bd.aniwatchtv.site path,
 *                           vee/neko -> passthrough
 *  - fallback: url unchanged by a handler AND response carried a Referer AND
 *              provider not in {vee, neko} -> uwu proxy with the Referer as key
 *               (this is what powers zuna + loli and any future provider)
 */
internal object AnidapUrl {

    private const val UWU_XOR_KEY = "10b06cdc1ca48c9fb0b94af97cc040cf"
    private const val SHIRO_HOST = "https://hls.dramavideo.se"
    private val UWU_HOSTS = listOf("https://cdnx.aniwatchtv.site")

    // providers whose urls the site wraps into the cdnx uwu proxy
    private val UWU_PROXY_KEYS = mapOf(
        "sora" to "https://krussdomi.com",
        "yuki" to "https://megaplay.buzz",
        "uwu" to "https://kwik.cx/",
        "kiwi" to "https://anidb.app/",
        "miku" to "https://allanime.uns.bio",
    )

    // providers the site never sends through the referer fallback
    private val FALLBACK_EXCLUDE = setOf("vee", "neko")

    private val stripDomainRegex = Regex("^https?://[^/]+")

    private fun stripDomain(url: String): String = url.replace(stripDomainRegex, "")

    // ==================== uwu proxy ====================

    fun uwuToken(url: String, key: String): String {
        val keyBytes = UWU_XOR_KEY.toByteArray(Charsets.US_ASCII)
        val data = url.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + key.toByteArray(Charsets.UTF_8)
        val out = ByteArray(data.size) { i -> (data[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte() }
        // base64url without padding (the js client strips "=" too)
        return android.util.Base64.encodeToString(out, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            .trimEnd('=')
    }

    fun uwuProxy(url: String, key: String, hostIndex: Int = 0): String {
        val host = UWU_HOSTS[hostIndex % UWU_HOSTS.size]
        return "$host/uwu/${uwuToken(url, key)}"
    }

    // ==================== shiro proxy ====================

    fun shiroProxy(url: String): String {
        val sb = StringBuilder()
        for (b in url.toByteArray(Charsets.UTF_8)) {
            sb.append(String.format("%02x", (b.toInt() xor 137) and 0xFF))
        }
        return "$SHIRO_HOST/media/$sb&origin=https://kem.clvd.xyz/"
    }

    // ==================== beep rewrite ====================

    private fun beepRewrite(url: String): String {
        return when {
            url.startsWith("https://bd.24stream.xyz/media") ||
                url.startsWith("https://bd.aniwatchtv.site/media") -> url
            url.startsWith("/") ->
                "https://bd.aniwatchtv.site/media" + url.replace("/r2", "")
            else ->
                "https://bd.aniwatchtv.site/media" + stripDomain(url).replace("/r2", "")
        }
    }

    // ==================== full pipeline (site's transformSourceUrl) ====================

    fun transform(url: String, providerId: String, referer: String?): String {
        val p = providerId.lowercase()
        var r = url

        // global rewrites first (site order)
        if (r.contains("https://vivibebe.site/public/stream/")) {
            r = r.replace("https://vivibebe.site/public/stream/", "https://hawk.aniwatchtv.site/media/")
        }
        if (r.startsWith("https://playeng.animeapps.top/r2/")) {
            r = "https://bd.aniwatchtv.site/media" + stripDomain(r).replace("/r2", "")
        }

        // per-provider handler
        when (p) {
            "shiro" -> r = shiroProxy(r)
            "sora", "yuki", "uwu", "kiwi", "miku" ->
                r = uwuProxy(r, UWU_PROXY_KEYS[p] ?: return r)
            "mochi" -> r = r.replace("https://tools.fast4speed.rsvp", "https://mp4.24stream.xyz/storage")
            "beep" -> r = beepRewrite(r)
            // vee + neko are passthrough; unknown providers (zuna, loli, ...) also pass through
            // and hit the referer fallback below
        }

        // fallback: nothing touched the url and the response carried a Referer
        if (r == url && !referer.isNullOrBlank() && p !in FALLBACK_EXCLUDE) {
            return uwuProxy(url, referer)
        }

        if (r.startsWith("http://")) {
            r = "https://" + r.removePrefix("http://")
        }
        return r
    }

    // yuki is the only provider whose subtitles the site routes through a proxy;
    // the app can send the needed headers directly, so everything stays raw
    fun transformSubtitle(url: String, providerId: String): String = url

    // ==================== probe client (AniSnatch pattern) ====================

    private val probeClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** Quick text fetch for playlist validation. Null on failure / non-2xx-3xx. */
    fun fetchText(url: String, headers: Map<String, String>): String? {
        return try {
            val req = Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (resp.code !in 200..399) null else resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Ranged byte fetch for segment sniffing. Null on failure / non-2xx-3xx. */
    fun fetchBytes(url: String, headers: Map<String, String>, rangeEnd: Int = 2047): ByteArray? {
        return try {
            val h = LinkedHashMap(headers)
            h["Range"] = "bytes=0-$rangeEnd"
            val req = Request.Builder().url(url)
                .apply { h.forEach { (k, v) -> header(k, v) } }
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (resp.code !in 200..399) null else resp.body?.bytes()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * True when the bytes look like actual video:
     *  - MPEG-TS (0x47 sync byte) - the megaplay CDN hides TS data behind a fake
     *    1x1 PNG header (verified live); players resync past it (ExoPlayer's
     *    TsUtil.findSyncBytePosition scans for 0x47 at any offset), we accept it here
     *  - fMP4 / MP4 (ftyp box)
     */
    fun isVideoBytes(d: ByteArray?): Boolean {
        if (d == null || d.isEmpty()) return false
        if (d[0] == 0x47.toByte()) return true
        if (d.size > 8 && d[0] == 0x89.toByte() && d[1] == 0x50.toByte()) {
            // PNG disguise: after the IEND chunk there must be a TS sync byte
            val iend = indexOf(d, "IEND".toByteArray())
            if (iend >= 0) {
                for (i in iend + 4 until minOf(d.size, iend + 4 + 64)) {
                    if (d[i] == 0x47.toByte()) return true
                }
            }
            return false
        }
        if (indexOf(d, "ftyp".toByteArray()) in 0..64) return true
        return false
    }

    // ==================== playlist parsing ====================

    class Variant(val quality: Int?, val url: String)

    fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http")) return ref
        val schemeIdx = base.indexOf("://")
        if (schemeIdx < 0) return ref
        val host = base.substring(schemeIdx + 3).substringBefore("/")
        val origin = base.substring(0, schemeIdx + 3) + host
        return if (ref.startsWith("/")) origin + ref
        else base.substringBeforeLast("/") + "/" + ref
    }

    fun parseMaster(body: String, masterUrl: String): List<Variant> {
        val out = ArrayList<Variant>()
        var pendingHeight: Int? = null
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                pendingHeight = res?.groupValues?.get(2)?.toIntOrNull()
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                out.add(Variant(pendingHeight, resolveUrl(masterUrl, line)))
                pendingHeight = null
            }
        }
        return out
    }

    fun firstSegment(body: String, playlistUrl: String): String? {
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isNotEmpty() && !line.startsWith("#")) {
                return resolveUrl(playlistUrl, line)
            }
        }
        return null
    }

    /** The site serves HLS playlists behind a ".txt" name - same thing for a player. */
    fun looksLikeHls(url: String, declaredType: String?): Boolean {
        val t = (declaredType ?: "").lowercase()
        val path = url.substringBefore("?")
        return t.contains("mpegurl") || t.contains("m3u8") ||
            path.endsWith(".m3u8") || path.endsWith(".txt") ||
            url.contains(".m3u8") || url.contains("index.txt")
    }

    fun looksLikeDash(url: String, declaredType: String?): Boolean {
        val t = (declaredType ?: "").lowercase()
        return t.contains("dash") || url.substringBefore("?").endsWith(".mpd") || url.contains(".mpd")
    }

    fun looksLikeMp4(url: String, declaredType: String?): Boolean {
        val t = (declaredType ?: "").lowercase()
        val path = url.substringBefore("?")
        return t.contains("mp4") || t.contains("webm") || t.contains("matroska") ||
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv")
    }
}
