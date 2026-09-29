package com.justplay

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLDecoder

internal object PlayPacker {
    fun findM3u8(unpacked: String): String? {
        listOf(
            Regex("\"hls2\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"hls3\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"hls4\"\\s*:\\s*\"([^\"]+)\""),
            Regex("""file\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""")
        ).forEach { rx ->
            rx.find(unpacked)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    suspend fun emitM3u8(
        m3u8: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (m3u8.isBlank() || !m3u8.startsWith("http")) return false
        return try {
            val master = app.get(m3u8, headers = PlayNet.headers(referer), timeout = 15000L).text
            if (!master.contains("#EXTM3U")) return false
            // multimovies masters list their variants low to high, the first
            // resolution is always the lowest one so no quality is set here
            callback(
                newExtractorLink(
                    "JustPlay",
                    PlayLabels.buildLabel("multimovies", "", label),
                    m3u8,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun resolvePackedEmbed(
        embedUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val res = app.get(embedUrl, headers = PlayNet.headers(referer), timeout = 20000L)
            val text = res.text
            val unpacked = if (text.contains("eval(function(p,a,c,k,e,d)")) {
                runCatching { getAndUnpack(text) }.getOrNull() ?: text
            } else text
            val m3u8 = findM3u8(unpacked)
                ?: Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""").find(unpacked)?.groupValues?.get(1)
                ?: return false
            emitM3u8(m3u8, PlayNet.getBaseUrl(res.url), label, callback)
        } catch (e: Exception) {
            false
        }
    }
}

internal object PlayModiplay {
    suspend fun resolve(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = PlayNet.getBaseUrl(embedUrl)
        if (base.isBlank()) return
        val html = try {
            app.get(embedUrl, headers = PlayNet.headers("https://multimovies.casa/"), timeout = 20000L).text
        } catch (e: Exception) {
            return
        }
        val servers = Regex("""switchServer\('([^']+)','([^']+)','([^']+)','([^']+)','([^']*)'""")
            .findAll(html).map { m ->
                listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            }.distinct().toList()

        loadSubs(base, embedUrl, subtitleCallback)

        if (servers.isEmpty()) {
            PlayPacker.resolvePackedEmbed(embedUrl, label, "https://multimovies.casa/", callback)
            return
        }
        for ((embed, platform, name, code) in servers) {
            if (PlayLabels.isDeadName(name)) continue
            val linkLabel = "$label $name"
            var handled = false
            if (embed.startsWith("http")) {
                handled = PlayPacker.resolvePackedEmbed(embed, linkLabel, base, callback)
            }
            if (!handled) {
                try {
                    resolveProxyFile(base, platform, code, linkLabel, callback)
                } catch (e: Exception) {
                }
            }
        }
    }

    private suspend fun resolveProxyFile(
        base: String,
        platform: String,
        fileCode: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val proxyUrl = "$base/proxy.php?p=$platform&c=$fileCode&title=&site_ref=&noredirect=1"
        val page = try {
            app.get(proxyUrl, headers = PlayNet.headers(base), timeout = 20000L).text
        } catch (e: Exception) {
            return false
        }
        val src = Regex("""var\s+src\s*=\s*"([^"]+)"""").find(page)?.groupValues?.get(1)?.let { PlayNet.deEsc(it) }
        val segRef = Regex("""var\s+SEG_REF\s*=\s*"([^"]+)"""").find(page)?.groupValues?.get(1)?.let { PlayNet.deEsc(it) }
        if (src.isNullOrBlank()) return false
        val masterUrl = PlayNet.absolute(src, base)
        return PlayPacker.emitM3u8(masterUrl, segRef ?: base, label, callback)
    }

    private suspend fun loadSubs(
        base: String,
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val imdbId = Regex("[?&]id=(tt\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            val tmdbId = Regex("[?&]id=(\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            val season = Regex("[?&]s=(\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            val ep = Regex("[?&]e=(\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            if (imdbId.isBlank() && tmdbId.isBlank()) return
            val seen = mutableSetOf<String>()
            for (lang in listOf("en", "hi", "")) {
                val resp = try {
                    app.get(
                        "$base/api/subtitle_fetch.php?tmdb_id=$tmdbId&imdb_id=$imdbId&season=$season&ep=$ep&lang=$lang",
                        headers = PlayNet.headers(base),
                        timeout = 15000L
                    ).text
                } catch (e: Exception) {
                    continue
                }
                val url = Regex(""""url"\s*:\s*"([^"]+)"""").find(resp)?.groupValues?.get(1)?.let { PlayNet.deEsc(it) }
                    ?: continue
                if (!url.startsWith("http")) continue
                val langName = Regex(""""lang"\s*:\s*"([^"]+)"""").find(resp)?.groupValues?.get(1)?.ifBlank { null }
                    ?: "English"
                if (seen.add(url)) {
                    subtitleCallback(newSubtitleFile(langName, url) {})
                }
            }
        } catch (e: Exception) {
        }
    }
}

internal object PlayGdmirror {
    suspend fun resolve(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(
                embedUrl,
                headers = PlayNet.headers("https://multimovies.casa/"),
                timeout = 20000L
            )
            val page = res.text
            val finalUrl = res.url
            val playerBase = Regex("""player_base\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
                ?: "https://pro.iqsmartgames.com"
            val apiUrl = Regex("""api_url\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
            val myKey = Regex("""myKey\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
            val finalId = Regex("""FinalID\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
            val idType = Regex("""idType\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
            val sid = Regex("""const\s+sid\s*=\s*"([^"]+)"""").find(page)?.groupValues?.get(1)
                ?: finalUrl.substringAfterLast("/").takeIf { it.isNotBlank() && it != "svid" && !it.contains("?") }

            val sids = mutableSetOf<String>()
            if (!sid.isNullOrBlank()) sids.add(sid)

            if (apiUrl != null && myKey != null && finalId != null) {
                val apiQuery = if (finalUrl.contains("/tv/") || page.contains("myseriesapi")) {
                    val season = Regex("""[?&]s=(\d+)""").find(finalUrl)?.groupValues?.get(1) ?: "1"
                    val ep = Regex("""[?&]e=(\d+)""").find(finalUrl)?.groupValues?.get(1) ?: "1"
                    "$apiUrl/myseriesapi?${idType ?: "imdbid"}=$finalId&season=$season&epname=$ep&key=$myKey"
                } else {
                    "$apiUrl/mymovieapi?${idType ?: "imdbid"}=$finalId&key=$myKey"
                }
                try {
                    val apiRes = app.get(apiQuery, headers = PlayNet.headers(apiUrl), timeout = 20000L).text
                    collectSlugs(apiRes, sids)
                } catch (e: Exception) {
                }
            }

            if (sids.isEmpty()) return

            for (s in sids) {
                try {
                    val helperRes = app.post(
                        "$playerBase/embedhelper2.php",
                        headers = mapOf(
                            "User-Agent" to PLAY_UA,
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Referer" to finalUrl,
                            "Origin" to PlayNet.getBaseUrl(finalUrl),
                            "X-Requested-With" to "XMLHttpRequest"
                        ),
                        data = mapOf("sid" to s, "UserFavSite" to "", "currentDomain" to "[]"),
                        timeout = 20000L
                    ).text
                    val helper = JSONObject(helperRes)
                    val mresult = helper.optString("mresult")
                    val codes = if (mresult.isNotBlank()) {
                        runCatching {
                            parseJson<Map<String, String>>(base64Decode(mresult))
                        }.getOrNull() ?: emptyMap()
                    } else emptyMap()
                    val sources = helper.optJSONObject("sources") ?: continue
                    for (key in sources.keys()) {
                        val src = sources.optJSONObject(key) ?: continue
                        val siteUrl = src.optString("siteUrl").takeIf { it.startsWith("http") } ?: continue
                        val suffix = src.optString("embed_suffix").takeIf { it != "null" && it.isNotBlank() } ?: ""
                        val code = codes[key] ?: continue
                        val friendly = src.optString("friendlyName").ifBlank { key }
                        if (PlayLabels.isDeadName(friendly) || PlayLabels.isDeadName(key)) continue
                        val embed = "$siteUrl$code$suffix"
                        PlayPacker.resolvePackedEmbed(embed, "$label $friendly", playerBase, callback)
                    }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "gdmirror: ${e.message}")
        }
    }

    private fun collectSlugs(body: String, out: MutableSet<String>) {
        try {
            val obj = JSONObject(body)
            fun walk(o: Any?) {
                when (o) {
                    is JSONObject -> {
                        for (k in listOf("fileslug", "slug", "sid")) {
                            val v = o.optString(k)
                            if (v.isNotBlank()) out.add(v)
                        }
                        for (key in o.keys()) walk(o.get(key))
                    }
                    is org.json.JSONArray -> {
                        for (i in 0 until o.length()) walk(o.get(i))
                    }
                }
            }
            walk(obj)
        } catch (e: Exception) {
        }
    }
}

class PlayHubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.ist"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = try {
            app.get(url, headers = PlayNet.headers(referer), timeout = 20000L)
        } catch (e: Exception) {
            return
        }
        emitHubServers(res.document, PlayNet.getBaseUrl(res.url), res.url, subtitleCallback, callback)
    }

    companion object {
        // the drive page only carries a generate button now, the real servers
        // sit behind it and need the second request
        suspend fun emitHubServers(
            doc: Document,
            base: String,
            pageUrl: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            var emitted = false
            val header = doc.selectFirst("div.card-header")?.text().orEmpty()
            val size = doc.selectFirst("i#size")?.text().orEmpty()
            // season packs arrive as zip archives, they are not playable
            if (Regex("(?i)\\.(zip|rar|7z)\\s*$").containsMatchIn(header.trim())) return false
            val quality = PlayNet.getIndexQuality(header)
            val labelExtras = listOf(header, size).filter { it.isNotBlank() }.joinToString(" ")

            val generate = doc.select("a.btn, a[download]")
                .firstOrNull { it.text().contains("generate", true) }
                ?.attr("href")?.trim()
            if (!generate.isNullOrBlank()) {
                try {
                    val genDoc = app.get(
                        PlayNet.absolute(generate, base),
                        headers = PlayNet.headers(pageUrl),
                        timeout = 20000L
                    ).document
                    if (emitGeneratedServers(genDoc, labelExtras, quality, callback)) return true
                } catch (e: Exception) {
                }
            }

            val inner = when {
                pageUrl.contains("/video/") -> doc.selectFirst("div.vd > center > a")?.attr("href")
                else -> null
            }
            if (!inner.isNullOrBlank()) {
                try {
                    val innerDoc = app.get(
                        PlayNet.absolute(inner, base),
                        headers = PlayNet.headers(base),
                        timeout = 20000L
                    ).document
                    if (emitHubServers(innerDoc, base, inner, subtitleCallback, callback)) return true
                } catch (e: Exception) {
                }
            }

            for (btn in doc.select("a.btn, a[download]")) {
                val text = btn.text()
                val link = btn.attr("href").trim()
                if (link.isBlank()) continue
                if (listOf("tinyurl", "telegram", "/tg/").any { link.contains(it) }) continue
                val label = text.trim().lowercase()
                val abs = PlayNet.absolute(link, base)
                when {
                    label.contains("fslv2") -> {
                        callback(newExtractorLink("Hub-Cloud", "FSLv2 [$labelExtras]", abs, ExtractorLinkType.VIDEO) { this.quality = quality })
                        emitted = true
                    }
                    label.contains("fsl") -> {
                        callback(newExtractorLink("Hub-Cloud", "FSL Server [$labelExtras]", abs, ExtractorLinkType.VIDEO) { this.quality = quality })
                        emitted = true
                    }
                    label.contains("buzzserver") -> {
                        try {
                            val dlink = app.get(
                                "$abs/download",
                                referer = abs,
                                allowRedirects = false,
                                timeout = 15000L
                            ).headers["hx-redirect"] ?: ""
                            if (dlink.isNotBlank()) {
                                callback(newExtractorLink("Hub-Cloud", "BuzzServer [$labelExtras]", PlayNet.absolute(dlink, PlayNet.getBaseUrl(abs)), ExtractorLinkType.VIDEO) { this.quality = quality })
                                emitted = true
                            }
                        } catch (e: Exception) {
                        }
                    }
                    label.contains("pixeldra") || label.contains("pixelserver") || label.contains("pixel server") || link.contains("pixeldra") -> {
                        val pixelBase = PlayNet.getBaseUrl(link)
                        val final = if (link.contains("download", true)) link
                        else "$pixelBase/api/file/${link.substringAfterLast("/")}?download"
                        callback(newExtractorLink("Hub-Cloud", "Pixeldrain [$labelExtras]", final, ExtractorLinkType.VIDEO) { this.quality = quality })
                        emitted = true
                    }
                    // the 10gbps and instant download buttons point at dead workers
                    // that answer with an empty 500, they are skipped on purpose
                    label.contains("s3 server") || label.contains("mega server") || label.contains("pdl") -> {
                        callback(newExtractorLink("Hub-Cloud", "${text.trim()} [$labelExtras]", abs, ExtractorLinkType.VIDEO) { this.quality = quality })
                        emitted = true
                    }
                    label.contains("download file") -> {
                        callback(newExtractorLink("Hub-Cloud", "Download File [$labelExtras]", abs, ExtractorLinkType.VIDEO) { this.quality = quality })
                        emitted = true
                    }
                    link.contains("gofile.io") -> {
                        PlayGofile().getUrl(abs, base, subtitleCallback, callback)
                        emitted = true
                    }
                }
            }
            return emitted
        }

        // generated page carries a signed r2 link, a pixel server and a pixeldrain mirror
        private suspend fun emitGeneratedServers(
            doc: Document,
            labelExtras: String,
            quality: Int,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            var emitted = false
            for (a in doc.select("center a[href], div.vd a[href], a[href]")) {
                val text = a.text().trim().lowercase()
                val href = a.attr("href").trim()
                if (!href.startsWith("http")) continue
                when {
                    text.contains("fsl") || href.contains("cloudflarestorage.com") -> {
                        callback(newExtractorLink("Hub-Cloud", "FSL Server [$labelExtras]", href, ExtractorLinkType.VIDEO) { this.quality = quality })
                        emitted = true
                    }
                    text.contains("pixelserver") || href.contains("pixeldrain") -> {
                        val final = if (href.contains("download", true)) href
                        else "https://pixeldrain.dev/api/file/${href.substringAfterLast("/u/")}?download"
                        callback(newExtractorLink("Hub-Cloud", "Pixeldrain [$labelExtras]", final, ExtractorLinkType.VIDEO) { this.quality = quality })
                        emitted = true
                    }
                }
            }
            return emitted
        }
    }
}

class PlayVCloud : ExtractorApi() {
    override val name = "V-Cloud"
    override val mainUrl = "https://vcloud.fit"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(
                url,
                headers = PlayNet.headers(referer),
                interceptor = PlayNet.cfKiller,
                timeout = 20000L
            )
            val doc = res.document
            val base = PlayNet.getBaseUrl(res.url)
            var link: String? = null
            if (res.url.contains("/video/")) {
                link = doc.selectFirst("div.vd > center > a")?.attr("href")
            } else {
                val script = doc.selectFirst("script:containsData(url)")?.data().orEmpty()
                link = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
                    .find(script)?.groupValues?.get(1)
                    ?.let { runCatching { base64Decode(base64Decode(it)) }.getOrNull() }
                    ?: Regex("""var\s+url\s*=\s*['"]([^'"]*)['"]""").find(script)?.groupValues?.get(1)
            }
            if (link.isNullOrBlank()) return
            val abs = if (link.startsWith("http")) link else base + link
            val targetDoc = try {
                app.get(abs, headers = PlayNet.headers(base), timeout = 20000L).document
            } catch (e: Exception) {
                return
            }
            PlayHubCloud.emitHubServers(targetDoc, PlayNet.getBaseUrl(abs), abs, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "vcloud: ${e.message}")
        }
    }
}

// fastdl and hubcdn serve the same redirect stub, the drive link always sits
// in the reurl variable, hubcdn just wraps it in one more base64 hop
internal object PlayDirectStub {
    suspend fun resolve(
        url: String,
        referer: String?
    ): String? {
        try {
            val res = app.get(
                url,
                headers = PlayNet.headers(referer ?: "https://nexdrive.fit/"),
                timeout = 20000L
            )
            val text = res.text
            val embedded = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                ?: Regex("""'(https://fastdl[^']*dl\.php\?link=[^']+)'""").find(text)?.groupValues?.get(1)
                ?: return null
            val wrapped = Regex("""[?&]r=([A-Za-z0-9+/=_-]+)""").find(embedded)?.groupValues?.get(1)
            val carrier = if (wrapped != null) {
                val padded = if (wrapped.length % 4 > 0) {
                    wrapped + "=".repeat(4 - wrapped.length % 4)
                } else wrapped
                runCatching { base64Decode(padded) }.getOrNull() ?: return null
            } else {
                embedded
            }
            val encoded = Regex("""link=(https?://[^&"']+)""").find(carrier)?.groupValues?.get(1) ?: return null
            val direct = URLDecoder.decode(encoded, "UTF-8")
            return direct.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "direct stub: ${e.message}")
            return null
        }
    }
}

class PlayFastDl : ExtractorApi() {
    override val name = "G-Direct"
    override val mainUrl = "https://fastdl.zip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val direct = PlayDirectStub.resolve(url, referer) ?: return
        callback(
            newExtractorLink(
                "G-Direct",
                "G-Direct",
                direct,
                ExtractorLinkType.VIDEO
            ) {
                this.headers = mapOf("Referer" to "https://fastdl.zip/")
            }
        )
    }
}

class PlayHubCdn : ExtractorApi() {
    override val name = "HubCdn"
    override val mainUrl = "https://hubcdn.wiki"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val direct = PlayDirectStub.resolve(url, referer) ?: return
        callback(
            newExtractorLink(
                "HubCdn",
                "G-Direct",
                direct,
                ExtractorLinkType.VIDEO
            ) {
                this.headers = mapOf("Referer" to "https://hubcdn.wiki/")
            }
        )
    }
}

class PlayHblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.lol"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(
                url,
                headers = PlayNet.headers(referer),
                interceptor = PlayNet.cfKiller,
                timeout = 20000L
            ).document
            val seen = mutableSetOf<String>()
            for (a in doc.select("div.entry-content a[href]")) {
                val href = a.attr("href").trim()
                if (!href.startsWith("http") || !seen.add(href)) continue
                loadExtractor(href, url, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "hblinks: ${e.message}")
        }
    }
}

class PlayHubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.pics"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(
                url,
                headers = PlayNet.headers(referer),
                interceptor = PlayNet.cfKiller,
                timeout = 20000L
            ).document
            val seen = mutableSetOf<String>()
            for (a in doc.select("div.entry-content a[href], main a[href], a[href*='hubcloud']")) {
                val href = a.attr("href").trim()
                if (!href.startsWith("http") || href.contains("/tg/") || !seen.add(href)) continue
                if (href.contains("hubcloud")) {
                    loadExtractor(href, url, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "hubdrive: ${e.message}")
        }
    }
}

class PlayHdStream4u : VidHidePro() {
    override val name = "HdStream4u"
    override val mainUrl = "https://hdstream4u.com"
}

class PlayGofile : ExtractorApi() {
    override val name = "GoFile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = url.substringAfterLast("/").substringBefore("#")
            if (code.isBlank()) return
            val api = "https://api.gofile.io"
            val token = try {
                JSONObject(
                    app.post("$api/accounts", timeout = 15000L).text
                ).getJSONObject("data").getString("token")
            } catch (e: Exception) {
                return
            }
            val wt = try {
                Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                    app.get("$api/dist/js/global.js", timeout = 15000L).text
                )?.groupValues?.get(1)
            } catch (e: Exception) {
                null
            }
            val contentUrl = if (wt.isNullOrBlank()) "$api/contents/$code?wt=$token"
            else "$api/contents/$code?wt=$wt"
            val contentRes = app.get(
                contentUrl,
                headers = mapOf("Authorization" to "Bearer $token"),
                timeout = 20000L
            ).text
            val data = JSONObject(contentRes).getJSONObject("data")
            val children = data.optJSONObject("children") ?: return
            for (key in children.keys()) {
                val child = children.optJSONObject(key) ?: continue
                val link = child.optString("link").takeIf { it.startsWith("http") } ?: continue
                val name = child.optString("name")
                val size = child.optLong("size", 0L)
                val sizeText = if (size > 0) "${size / 1024 / 1024} MB" else ""
                val inner = listOf(name.take(60), sizeText).filter { it.isNotBlank() }.joinToString(" | ")
                val quality = PlayNet.getIndexQuality(name)
                callback(
                    newExtractorLink(
                        "GoFile",
                        if (inner.isBlank()) "GoFile" else "GoFile [$inner]",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.headers = mapOf("Authorization" to "Bearer $token")
                    }
                )
            }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "gofile: ${e.message}")
        }
    }
}
