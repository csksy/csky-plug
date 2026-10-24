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
 * Every stream server animetvplus.xyz offers, resolved exactly the way the
 * site's own player does. All flows were captured in the user's HAR
 * (2026-10-24) and the crypto was verified byte-for-byte against it:
 *
 *  Multi Audio  embed hls3/uq -> edge.animetvplus.xyz master.txt (up to 7 audio
 *              tracks), subtitles from meta.json and the resolver's own list
 *  Dub          megaplay.buzz /stream/mal/{mal}/{ep}/dub -> getSources (with
 *              the getSourcesNew rotation the player falls back to) -> enc
 *              (AES-256-CBC, key/iv seeded) -> master.m3u8 + HMAC cdn token,
 *              one link per quality and a subtitle set in 6+ languages
 *  Hardsub      sv1.animetvplus.site server list -> edge3 hls3/p {sources:[...]}
 *              (offline at build time, wired up so it plays the day it returns)
 *  Kamuri       the site's stream proxy worker (HD1) + subs
 *  Vidmoly      edge.animetvplus.site/lookup file_code -> vidmoly embed (resolved
 *              on the viewer's own network because the CDN signs URLs per ASN)
 *  VidFlow      direct m3u8 probe, sub and english-audio variant
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
     * works natively. Subtitles come from the sidecar meta.json and from the
     * resolver's own subtitles list.
     */
    suspend fun multiAudio(
        mal: String,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = AtvpApi.hls3uq(mal, episode) ?: return false
        val m3u8 = info.optString("m3u8").takeIf { it.startsWith("http") } ?: return false
        val seenSubs = HashSet<String>()

        // the resolver itself can list subtitle files next to the playlist
        val inline = info.optJSONArray("subtitles")
        if (inline != null) {
            for (i in 0 until inline.length()) {
                val s = inline.optJSONObject(i) ?: continue
                val file = s.optString("file").takeIf { it.startsWith("http") } ?: continue
                if (!seenSubs.add(file)) continue
                emitSubtitle(
                    s.optString("label").ifBlank { "English" },
                    file,
                    AtvpApi.edgeMediaHeaders,
                    subtitleCallback
                )
            }
        }

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
                    val full = absolute(file, base)
                    if (!seenSubs.add(full)) continue
                    emitSubtitle(
                        s.optString("label").ifBlank { "English" },
                        full,
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
    private fun decryptEnc(enc: String, keySeed: String = MEGAPLAY_KEY_SEED, ivSeed: String = MEGAPLAY_IV_SEED): String? {
        return runCatching {
            var b64 = enc.replace('-', '+').replace('_', '/')
            while (b64.length % 4 != 0) b64 += "="
            val cipherBytes = Base64.decode(b64, Base64.DEFAULT)

            val kb = keySeed.toByteArray(Charsets.UTF_8)
            val keyBytes = ByteArray(32)
            System.arraycopy(kb, 0, keyBytes, 0, minOf(32, kb.size))
            val ivb = ivSeed.toByteArray(Charsets.UTF_8)
            val ivBytes = ByteArray(16)
            System.arraycopy(ivb, 0, ivBytes, 0, minOf(16, ivb.size))

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    /** The key material rotates in newclient.min.js; the pinned pair is the fallback. */
    private suspend fun encSeeds(): List<Pair<String, String>> {
        val pinned = Pair(MEGAPLAY_KEY_SEED, MEGAPLAY_IV_SEED)
        return try {
            val js = app.get(
                "https://megaplay.buzz/lib/newclient.min.js",
                headers = AtvpApi.megaplayHeaders,
                timeout = 15L
            ).text
            val m = Regex("[A-Za-z]\\w*=\"([^\"]{16})\",[A-Za-z]\\w*=\"([^\"]{16})\"").find(js)
            if (m != null) listOf(Pair(m.groupValues[1], m.groupValues[2]), pinned) else listOf(pinned)
        } catch (_: Exception) {
            listOf(pinned)
        }
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
     * that page carries the file id, getSources (rotating to getSourcesNew the
     * way the player's own client does) returns the encrypted playlist plus a
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
            ?: Regex("File (\\d+) - MegaPlay").find(page)?.groupValues?.get(1)
            ?: return false

        val seeds = encSeeds()
        var root: JSONObject? = null
        for (endpoint in listOf("getSources", "getSourcesNew")) {
            val srcUrl = "https://megaplay.buzz/stream/$endpoint?id=$fileId&platform=OTHER"
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
            }.getOrNull() ?: continue
            val parsed = runCatching { JSONObject(srcText) }.getOrNull() ?: continue
            // a response without any usable payload keeps rotating endpoints
            val hasSources = parsed.opt("sources").let { it is JSONArray && it.length() > 0 || it is JSONObject }
            val hasEnc = parsed.optString("enc").isNotBlank()
            if (hasSources || hasEnc) {
                root = parsed
                break
            }
        }
        root ?: return false

        // subtitle tracks - one file per language the server hosts; the cdn
        // refuses the files without the megaplay referer so headers are load bearing
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
            for ((keySeed, ivSeed) in seeds) {
                val plain = decryptEnc(enc, keySeed, ivSeed) ?: continue
                master = runCatching { JSONObject(plain).optString("file") }.getOrNull()
                    ?.takeIf { it.startsWith("http") }
                if (master != null) break
            }
            master ?: return false
        }

        val signed = signUrl(master)
        val masterText = runCatching {
            app.get(signed, headers = AtvpApi.megaplayHeaders, timeout = 15L).text
        }.getOrNull()

        // one link per quality variant when the master lists them
        if (masterText != null) {
            val base = master!!.substringBefore('?').substringBeforeLast('/') + "/"
            val lines = masterText.lines()
            var i = 0
            var emitted = false
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val quality = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)?.groupValues?.get(2)?.toIntOrNull()
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                    if (j < lines.size) {
                        val uri = lines[j].trim()
                        if (uri.isNotEmpty()) {
                            val variant = if (uri.startsWith("http")) uri else base + uri
                            callback(
                                newExtractorLink(
                                    SRC,
                                    if (quality != null) "Dub ${quality}p" else "Dub",
                                    signUrl(variant),
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://megaplay.buzz/"
                                    this.headers = AtvpApi.megaplayHeaders
                                    if (quality != null) this.quality = quality
                                }
                            )
                            emitted = true
                        }
                        i = j
                    }
                }
                i++
            }
            if (emitted) return true
        }

        val height = masterText?.let { extractBestHeight(it) } ?: 0
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
            val labels = mapOf("sub" to "Kamuri", "dub" to "Kamuri (English audio)")
            for (type in listOf("sub", "dub")) {
                var body: String? = null
                // one retry: the cold worker occasionally trips on the first hit
                for (attempt in 0 until 2) {
                    body = runCatching {
                        app.get(
                            "$base/api/stream/$mal/$episode?type=$type&embed=false",
                            headers = mapOf("User-Agent" to AtvpApi.UA, "Accept" to "application/json"),
                            timeout = 20L
                        ).text
                    }.getOrNull()
                    if (body != null) break
                }
                body ?: continue
                val stream = runCatching { JSONObject(body).optJSONObject("stream") }.getOrNull()
                    ?: continue
                val m3u8 = stream.optString("m3u8_url").takeIf { it.startsWith("http") } ?: continue
                if (!seenUrls.add(m3u8)) continue

                // the worker hands out cached mewstream links whose upstream token
                // can be long dead (a stale cache answers the api call fine while
                // the cdn behind it 403s), so the playlist is probed once and the
                // whole source is dropped when it does not answer - a dead link
                // never reaches the player
                if (!probeHttp(m3u8)) continue

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

                callback(
                    newExtractorLink(SRC, labels[type] ?: "Kamuri", m3u8, type = ExtractorLinkType.M3U8) {
                        this.referer = "$base/"
                    }
                )
            }
            if (seenUrls.isNotEmpty()) return true
        }
        return false
    }

    // ------------------------------------------------------------------ Node server 2

    /**
     * The node player's second storage: edge3 (fastest for India) and edge2
     * (rest of the world) answer a per-audio source list. Both have been
     * offline for a while (the user's own browser session saw 500/521 on them
     * too) - they are wired exactly like the site wires them, so they light up
     * in the link list the day the edges return. Each source carries its own
     * height so every quality is a link of its own.
     */
    suspend fun nodeServers(
        mal: String,
        episode: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val edges = listOf(
            "https://edge3.animetvplus.xyz" to "Node (India)",
            "https://edge2.animetvplus.xyz" to "Node (Global)"
        )
        var emitted = false
        for ((edge, name) in edges) {
            val body = runCatching {
                app.get(
                    "$edge/hls3/p/${java.net.URLEncoder.encode(mal, "UTF-8")}?episode=$episode",
                    headers = AtvpApi.edgeMediaHeaders,
                    timeout = 12L
                ).text
            }.getOrNull() ?: continue
            val sources = runCatching { JSONObject(body).optJSONArray("sources") }.getOrNull()
                ?: continue
            for (i in 0 until sources.length()) {
                val s = sources.optJSONObject(i) ?: continue
                val url = s.optString("m3u8").takeIf { it.startsWith("http") } ?: continue
                val english = s.optString("audio") == "eng"
                val height = s.optInt("height", 0)
                val label = buildString {
                    append(name)
                    if (english) append(" (English audio)")
                    if (height > 0) append(" ").append(height).append("p")
                }
                callback(
                    newExtractorLink(SRC, label, url, type = ExtractorLinkType.M3U8) {
                        this.headers = AtvpApi.edgeMediaHeaders
                        if (height > 0) this.quality = height
                    }
                )
                emitted = true
            }
        }
        return emitted
    }

    /** A one-kilobyte ranged GET; true only when the url answers with 2xx. */
    private suspend fun probeHttp(url: String): Boolean {
        return runCatching {
            val res = app.get(
                url,
                headers = mapOf("User-Agent" to AtvpApi.UA, "Range" to "bytes=0-1023"),
                timeout = 12L
            )
            res.code in 200..299
        }.getOrDefault(false)
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
        val labels = mapOf("sub" to "VidFlow", "dub" to "VidFlow (English audio)")
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

            callback(
                newExtractorLink(SRC, labels[type] ?: "VidFlow", finalUrl, type = ExtractorLinkType.M3U8) {
                    this.referer = "${AtvpApi.SITE}/"
                }
            )
            emitted = true
        }
        return emitted
    }
}
