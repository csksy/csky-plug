package com.atvp

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Every stream server animetvplus.xyz offers, resolved exactly the way the site's
 * own player does. All flows were captured in the user's HAR (2026-10-24) and the
 * crypto was verified byte-for-byte against it:
 *
 *  Multi Audio  embed hls3/uq -> edge.animetvplus.xyz master.txt (up to 7 audio
 *              tracks), subtitles from meta.json
 *  Dub          megaplay.buzz /stream/mal/{mal}/{ep}/dub -> getSources -> enc
 *              (AES-256-CBC, key/iv seeded) -> master.m3u8 + HMAC token, 7-language subs
 *  Hardsub      sv1.animetvplus.site server list -> edge3 hls3/p {sources:[...]}
 *              (offline at build time, wired up so it plays the day it returns)
 *  Kamuri       the site's stream proxy worker /api/stream (mewstream) + subs
 *  Vidmoly      edge.animetvplus.site/lookup file_code -> vidmoly embed (resolved
 *              on the viewer's own network because the CDN signs URLs per ASN)
 *  VidFlow      direct m3u8 probe, sub and dub
 */
object AtvpSources {

    private const val TAG = "AtvpSources"

    /** The one list everything links into - no sub/dub split, per the site's own layout. */
    private const val SRC = "AnimeTVPlus"

    // ------------------------------------------------------------------ helpers

    private fun extractBestHeight(master: String): Int {
        return Regex("RESOLUTION=(\\d+)x(\\d+)")
            .findAll(master)
            .mapNotNull { it.groupValues[2].toIntOrNull() }
            .maxOrNull() ?: 0
    }

    private suspend fun emitSubtitle(
        label: String,
        url: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (url.isBlank() || !url.startsWith("http")) return
        runCatching {
            subtitleCallback(
                newSubtitleFile(label.ifBlank { "English" }, url) {
                    this.headers = headers
                }
            )
        }
    }

    private fun absolute(url: String, base: String): String {
        if (url.startsWith("http")) return url
        return base.trimEnd('/') + "/" + url.trimStart('/')
    }

    // ------------------------------------------------------------------ Multi Audio (edge)

    /**
     * The site's own storage. One master playlist carries every audio track
     * (e.g. My Hero Academia: ja/en/kn/ml/te/ta/hi) and every quality, so the
     * master itself is handed to the player - track and quality switching then
     * works natively. Subtitles come from the sidecar meta.json.
     */
    suspend fun multiAudio(
        mal: String,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = AtvpApi.hls3uq(mal, episode) ?: return false
        val m3u8 = info.optString("m3u8").takeIf { it.startsWith("http") } ?: return false

        // meta.json sits next to the playlist and lists subtitle files + audio map;
        // for muxed single-rendition streams it also carries the video height
        var metaHeight = 0
        val metaUrl = info.optString("meta").takeIf { it.startsWith("http") }
        if (metaUrl != null) {
            runCatching {
                val metaText = app.get(metaUrl, headers = AtvpApi.edgeMediaHeaders, timeout = 15L).text
                val meta = JSONObject(metaText)
                metaHeight = meta.optInt("height", 0)
                val base = metaUrl.substringBefore('?').substringBeforeLast('/') + "/"
                val subs = meta.optJSONArray("subs") ?: JSONArray()
                for (i in 0 until subs.length()) {
                    val s = subs.optJSONObject(i) ?: continue
                    val file = s.optString("file").takeIf { it.isNotBlank() } ?: continue
                    emitSubtitle(
                        s.optString("label").ifBlank { "English" },
                        absolute(file, base),
                        AtvpApi.edgeMediaHeaders,
                        subtitleCallback
                    )
                }
            }
        }

        val height = runCatching {
            extractBestHeight(app.get(m3u8, headers = AtvpApi.edgeMediaHeaders, timeout = 15L).text)
        }.getOrDefault(0).takeIf { it > 0 } ?: metaHeight

        callback(
            newExtractorLink(SRC, "Multi Audio", m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = "${AtvpApi.EMBED}/"
                this.headers = AtvpApi.edgeMediaHeaders
                if (height > 0) quality = height
            }
        )
        return true
    }

    // ------------------------------------------------------------------ Dub (megaplay)

    private const val MEGAPLAY_KEY_SEED = "i?LMTAx0Q6,:}50U"
    private const val MEGAPLAY_IV_SEED = "W0;27ToaUpl_P%'c"
    private const val MEGAPLAY_TOKEN_KEY = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"
    private const val MEGAPLAY_TOKEN_LIFETIME = 7L * 24L * 60L * 60L

    /** AES-256-CBC decrypt of the getSources "enc" blob (seed-padded key/iv, base64url). */
    private fun decryptEnc(enc: String): String? {
        return runCatching {
            var b64 = enc.replace('-', '+').replace('_', '/')
            while (b64.length % 4 != 0) b64 += "="
            val cipherBytes = Base64.decode(b64, Base64.DEFAULT)

            val keyBytes = ByteArray(32)
            val kb = MEGAPLAY_KEY_SEED.toByteArray(Charsets.UTF_8)
            System.arraycopy(kb, 0, keyBytes, 0, minOf(32, kb.size))
            val ivBytes = ByteArray(16)
            val ivb = MEGAPLAY_IV_SEED.toByteArray(Charsets.UTF_8)
            System.arraycopy(ivb, 0, ivBytes, 0, minOf(16, ivb.size))

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * The CDN token the megaplay player mints: base64url("expires|h1/h2") +
     * "." + base64url(HMAC-SHA256(payload)). Verified identical to the token the
     * site's browser produced for the same episode.
     */
    private fun signUrl(url: String): String {
        val m = Regex("/([a-f0-9]{32})/([a-f0-9]{32})/", RegexOption.IGNORE_CASE).find(url)
            ?: return url
        val expires = System.currentTimeMillis() / 1000L + MEGAPLAY_TOKEN_LIFETIME
        val payload = "$expires|${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(MEGAPLAY_TOKEN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val token = "${b64url(payload.toByteArray(Charsets.UTF_8))}.${b64url(signature)}"
        return "$url${if (url.contains('?')) '&' else '?'}token=$token"
    }

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /**
     * Megaplay's dub stream. The site embeds megaplay.buzz/stream/mal/{mal}/{ep}/dub;
     * that page carries the file id, getSources returns the encrypted playlist plus a
     * full set of translated subtitle tracks (ara/eng/ger/por/spa/tur...).
     */
    suspend fun dub(
        mal: String,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = "https://megaplay.buzz/stream/mal/$mal/$episode/dub"
        val page = runCatching {
            app.get(
                pageUrl,
                headers = mapOf(
                    "User-Agent" to AtvpApi.UA,
                    "Accept" to "text/html",
                    "Referer" to "${AtvpApi.SITE}/"
                ),
                timeout = 15L
            ).text
        }.getOrNull() ?: return false

        val fileId = Regex("data-id=\"(\\d+)\"").find(page)?.groupValues?.get(1)
            ?: Regex("""/stream/s-\d+/(\d+)/""").find(pageUrl)?.groupValues?.get(1)
            ?: return false

        val srcUrl = "https://megaplay.buzz/stream/getSources?id=$fileId&platform=OTHER"
        val srcText = runCatching {
            app.get(
                srcUrl,
                headers = AtvpApi.megaplayHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Referer" to pageUrl
                ),
                timeout = 15L
            ).text
        }.getOrNull() ?: return false

        val root = runCatching { JSONObject(srcText) }.getOrNull() ?: return false

        // subtitle tracks - one file per language the server hosts
        val tracks = root.optJSONArray("tracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val kind = t.optString("kind")
                if (kind != "captions" && kind != "subtitles") continue
                val file = t.optString("file").takeIf { it.startsWith("http") } ?: continue
                emitSubtitle(t.optString("label"), file, AtvpApi.megaplayHeaders, subtitleCallback)
            }
        }

        // playlist: plain "sources" when present, otherwise the encrypted "enc"
        var master: String? = null
        val sources = root.opt("sources")
        when {
            sources is JSONArray && sources.length() > 0 ->
                master = sources.optJSONObject(0)?.optString("file")?.takeIf { it.startsWith("http") }
            sources is JSONObject ->
                master = sources.optString("file").takeIf { it.startsWith("http") }
        }
        if (master == null) {
            val enc = root.optString("enc").takeIf { it.isNotBlank() } ?: return false
            master = runCatching {
                JSONObject(decryptEnc(enc) ?: return false).optString("file")
                    .takeIf { it.startsWith("http") }
            }.getOrNull() ?: return false
        }

        val signed = signUrl(master)
        val height = runCatching {
            extractBestHeight(app.get(signed, headers = AtvpApi.megaplayHeaders, timeout = 15L).text)
        }.getOrDefault(0)

        callback(
            newExtractorLink(SRC, "Dub", signed, type = ExtractorLinkType.M3U8) {
                this.referer = "https://megaplay.buzz/"
                this.headers = AtvpApi.megaplayHeaders
                if (height > 0) quality = height
            }
        )
        return true
    }

    // ------------------------------------------------------------------ Hardsub (sv1 relay)

    /**
     * sv1.animetvplus.site publishes the server registry for an episode; the
     * "sources" kind entry is the hardsub relay (edge3.animetvplus.xyz/hls3/p)
     * answering {sources: [{audio, height, m3u8, bitrate}]}. The relay is down
     * right now - this stays wired so it starts working the moment it returns.
     */
    suspend fun hardsub(
        mal: String,
        episode: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = runCatching {
            app.get(
                "https://sv1.animetvplus.site/$mal/$episode?server=s2",
                headers = mapOf("User-Agent" to AtvpApi.UA, "Referer" to "${AtvpApi.SITE}/"),
                timeout = 15L
            ).text
        }.getOrNull() ?: return false

        val attr = Regex("data-servers=\"([^\"]*)\"").find(page)?.groupValues?.get(1) ?: return false
        val servers = runCatching { JSONArray(jsonUnescape(attr)) }.getOrNull() ?: return false

        var emitted = false
        for (i in 0 until servers.length()) {
            val srv = servers.optJSONObject(i) ?: continue
            if (srv.optString("kind") != "sources") continue
            val api = srv.optString("api").takeIf { it.startsWith("http") } ?: continue
            val body = runCatching {
                app.get(
                    api,
                    headers = mapOf("User-Agent" to AtvpApi.UA, "Accept" to "*/*"),
                    timeout = 15L
                ).text
            }.getOrNull() ?: continue
            val list = runCatching { JSONObject(body).optJSONArray("sources") }.getOrNull() ?: continue

            for (j in 0 until list.length()) {
                val s = list.optJSONObject(j) ?: continue
                val url = s.optString("m3u8").takeIf { it.startsWith("http") } ?: continue
                val height = s.optInt("height", 0)
                val audio = s.optString("audio")
                val label = buildString {
                    append("Hardsub")
                    if (height > 0) append(' ').append(height).append('p')
                    // the relay serves per-audio renditions; tag the non-default one
                    if (audio.equals("eng", true)) append(" (English audio)")
                }
                callback(
                    newExtractorLink(SRC, label, url, type = ExtractorLinkType.M3U8) {
                        this.referer = "${AtvpApi.EMBED}/"
                        this.headers = AtvpApi.edgeMediaHeaders
                        if (height > 0) quality = height
                    }
                )
                emitted = true
            }
        }
        return emitted
    }

    private fun jsonUnescape(s: String): String =
        s.replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    // ------------------------------------------------------------------ Kamuri (stream proxy)

    /**
     * The site's HD1 route: a stream-proxy worker (with the origin render service
     * as fallback) that answers a mewstream playlist plus a full subtitle set.
     */
    suspend fun kamuri(
        mal: String,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val bases = listOf(
            "https://anime-tv-stream-proxy.sharmishthajha5.workers.dev",
            "https://anime-search-api-burw.onrender.com"
        )
        for (base in bases) {
            val seenUrls = HashSet<String>()
            for (type in listOf("sub", "dub")) {
                val body = runCatching {
                    app.get(
                        "$base/api/stream/$mal/$episode?type=$type&embed=false",
                        headers = mapOf("User-Agent" to AtvpApi.UA, "Accept" to "application/json"),
                        timeout = 20L
                    ).text
                }.getOrNull() ?: continue
                val stream = runCatching { JSONObject(body).optJSONObject("stream") }.getOrNull()
                    ?: continue
                val m3u8 = stream.optString("m3u8_url").takeIf { it.startsWith("http") } ?: continue
                if (!seenUrls.add(m3u8)) continue

                // subtitles arrive either as a JSON array or as a JSON-encoded string
                val subsRaw = stream.opt("subtitles")
                val subs: JSONArray? = when (subsRaw) {
                    is JSONArray -> subsRaw
                    is String -> runCatching { JSONArray(subsRaw) }.getOrNull()
                    else -> null
                }
                if (subs != null) {
                    for (i in 0 until subs.length()) {
                        val s = subs.optJSONObject(i) ?: continue
                        val file = s.optString("file").takeIf { it.startsWith("http") } ?: continue
                        emitSubtitle(
                            s.optString("label"), file,
                            mapOf("User-Agent" to AtvpApi.UA),
                            subtitleCallback
                        )
                    }
                }

                // the playlist is adaptive and served through the worker proxy;
                // it is emitted straight away (no pre-fetch) so the link appears fast
                val label = if (type == "dub") "Kamuri (Dub)" else "Kamuri"
                callback(
                    newExtractorLink(SRC, label, m3u8, type = ExtractorLinkType.M3U8) {
                        this.referer = "$base/"
                    }
                )
            }
            if (seenUrls.isNotEmpty()) return true
        }
        return false
    }

    // ------------------------------------------------------------------ Vidmoly

    /**
     * edge.animetvplus.site/lookup maps (mal, episode) to a vidmoly file code and
     * the vidmoly embed page carries the playlist. The lookup host only has a
     * Cloudflare origin certificate so plain http is used (verified working), and
     * the vidmoly CDN signs URLs to the requesting ASN, which is exactly why this
     * resolves on the viewer's own device inside the app.
     */
    suspend fun vidmoly(
        mal: String,
        episode: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val code = runCatching {
            val text = app.get(
                "http://edge.animetvplus.site/lookup?mal=$mal&episode=$episode&type=sub&quality=1080p",
                headers = mapOf("User-Agent" to AtvpApi.UA, "Accept" to "application/json"),
                timeout = 15L
            ).text
            JSONObject(text).optString("file_code").takeIf { it.isNotBlank() }
        }.getOrNull() ?: return false

        for (host in listOf("vidmoly.me", "vidmoly.biz", "vidmoly.to")) {
            val html = fetchVidmolyEmbed(host, code) ?: continue
            val m3u8 = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""")
                .find(html)?.groupValues?.get(1)
                ?: Regex("""file\s*:\s*["'](https?://[^"']+?\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)
                ?: continue

            val playHeaders = mapOf(
                "User-Agent" to AtvpApi.UA,
                "Referer" to "https://$host/",
                "Origin" to "https://$host"
            )
            val links = runCatching {
                M3u8Helper.generateM3u8("Vidmoly", m3u8, "https://$host", headers = playHeaders)
            }.getOrNull().orEmpty()
            if (links.isNotEmpty()) {
                links.forEach(callback)
                return true
            }
            // single-file playlist (no variants) - hand it over directly
            callback(
                newExtractorLink(SRC, "Vidmoly", m3u8, type = ExtractorLinkType.M3U8) {
                    this.referer = "https://$host/"
                    this.headers = playHeaders
                }
            )
            return true
        }
        return false
    }

    /**
     * The vidmoly embed sits behind a javascript redirect challenge
     * (embed-...html?ch=1&js={jwt}&sid={sid}) that mints a session cookie. The
     * dance is replayed here: follow the redirect URL once with the cookie kept.
     */
    private suspend fun fetchVidmolyEmbed(host: String, code: String): String? {
        var url = "https://$host/embed-$code.html"
        val headers = mapOf(
            "User-Agent" to AtvpApi.UA,
            "Accept" to "text/html",
            "Referer" to "${AtvpApi.SITE}/"
        )
        var cookie: String? = null
        repeat(3) {
            val resp = runCatching {
                val h = if (cookie != null) headers + mapOf("Cookie" to cookie!!) else headers
                app.get(url, headers = h, timeout = 15L)
            }.getOrNull() ?: return null
            val body = resp.text ?: return null
            val redirect = Regex(
                """window\.location\.replace\('([^']*(?:ch=1|js=)[^']*)'\)"""
            ).find(body)?.groupValues?.get(1)
            if (redirect == null) return body.takeIf { it.isNotBlank() }
            url = redirect
            // the challenge reply sets the sid cookie that unlocks the embed
            runCatching {
                val setCookie = resp.headers.values("set-cookie")
                    .joinToString("; ") { c -> c.substringBefore(';') }
                    .takeIf { it.isNotBlank() }
                if (setCookie != null) cookie = setCookie
            }
        }
        return null
    }

    // ------------------------------------------------------------------ VidFlow

    /**
     * A direct playlist endpoint the site HEAD-checks before offering the route.
     * Offline at build time (404) - the probe is nearly free so it is kept and
     * revives automatically.
     */
    suspend fun vidFlow(
        mal: String,
        episode: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        for (type in listOf("sub", "dub")) {
            val url =
                "https://vidflow.animetvplus.com/watch.m3u8?mal=$mal&episode=$episode&type=$type&quality=1080p"
            val finalUrl = runCatching {
                val resp = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to AtvpApi.UA,
                        "Origin" to AtvpApi.SITE,
                        "Referer" to "${AtvpApi.SITE}/",
                        "Range" to "bytes=0-0"
                    ),
                    timeout = 12L
                )
                if (resp.isSuccessful) (resp.url.takeIf { it.isNotBlank() } ?: url) else null
            }.getOrNull() ?: continue

            val label = if (type == "dub") "VidFlow (Dub)" else "VidFlow"
            callback(
                newExtractorLink(SRC, label, finalUrl, type = ExtractorLinkType.M3U8) {
                    this.referer = "${AtvpApi.SITE}/"
                }
            )
            emitted = true
        }
        return emitted
    }
}
