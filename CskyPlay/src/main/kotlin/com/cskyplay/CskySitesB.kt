package com.cskyplay

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object TmfSite {
    private const val DEFAULT_DOMAIN = "https://themoviesflixhq.com"

    private fun episodeRegex(episode: Int?): Regex? {
        if (episode == null) return null
        return Regex("(?i)Episodes?\\s*:?\\s*0*${episode}(?!\\d)")
    }

    private fun seasonOf(text: String): Int? =
        Regex("(?i)Season\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\bS(\d{1,2})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun emitDrivePage(
        driveUrl: String,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(driveUrl, headers = CskyNet.headers(), timeout = 20000L).document
            val epRegex = episodeRegex(episode)
            if (epRegex != null) {
                val h4 = doc.select("h4").firstOrNull { epRegex.containsMatchIn(it.text()) }
                if (h4 != null) {
                    val links = mutableListOf<String>()
                    var sib = h4.nextElementSibling()
                    while (sib != null && sib.tagName() != "h4") {
                        links.addAll(sib.select("a[href]").map { it.attr("href").trim() })
                        sib = sib.nextElementSibling()
                    }
                    links.filter { it.startsWith("http") && !it.contains("nexdrive") }
                        .distinct()
                        .forEach { CskyNet.emitSiteLink("TheMoviesFlix", it, "", null, driveUrl, subtitleCallback, callback) }
                    return
                }
            }
            val skip = Regex("nexdrive\\.fit|mobilejsr\\.rest|moviesflix|themoviesflix|gmpg\\.org|wp-|fonts\\.|googleapis|w\\.org|catimages")
            doc.select("a[href]").mapNotNull { it.attr("href").trim() }
                .filter { it.startsWith("http") && !skip.containsMatchIn(it) }
                .distinct()
                .forEach { CskyNet.emitSiteLink("TheMoviesFlix", it, "", null, driveUrl, subtitleCallback, callback) }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "tmf drive page: ${e.message}")
        }
    }

    suspend fun invoke(
        res: CskyLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = FirebaseDomainHelper.getDomain("cskyplay_themoviesflix") ?: DEFAULT_DOMAIN
            val title = res.title ?: return
            val normTitle = CskyNet.normalizeTitle(title)

            val searchDoc = app.get(
                "$domain/?s=${Uri.encode(title)}",
                headers = CskyNet.headers(),
                timeout = 20000L
            ).document
            val anchors = searchDoc.select("article.latestpost a[id=featured-thumbnail]")
                .mapNotNull { el ->
                    val t = el.attr("title").trim().ifBlank { el.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                    val href = el.attr("href").trim()
                    if (t.isNotBlank() && href.startsWith("http")) t to href else null
                }
                .filter { (t, _) -> CskyNet.normalizeTitle(t).contains(normTitle) }
            val target = anchors.firstOrNull { (t, _) ->
                if (res.season != null) seasonOf(t) == res.season || t.contains("season ${res.season}", true)
                else res.year == null || t.contains("${res.year}")
            } ?: anchors.firstOrNull() ?: return

            val postUrl = target.second
            val doc = app.get(postUrl, headers = CskyNet.headers(domain), timeout = 20000L).document
            val groups = doc.select("div.mfx-download-group")

            if (res.season == null) {
                val driveLinks = groups.flatMap { group ->
                    val qualityTitle = group.selectFirst("h3")?.text().orEmpty()
                    group.select("a.mfx-download-link, a[href]").mapNotNull { el ->
                        val text = el.text()
                        val href = el.attr("href").trim()
                        if (!href.startsWith("http")) null
                        else if (text.contains("Batch", true) || text.contains("Zip", true)) null
                        else href
                    }
                }.distinct()
                driveLinks.amap { link ->
                    emitDrivePage(link, null, subtitleCallback, callback)
                }
            } else {
                val seasonRegex = Regex("(?i)Season\\s*${res.season}(?!\\d)")
                val seasonGroups = groups.filter { group ->
                    val h3 = group.selectFirst("h3")?.text()?.replace('\u00A0', ' ').orEmpty()
                    seasonRegex.containsMatchIn(h3)
                }.ifEmpty {
                    groups.filter { group ->
                        val h3 = group.selectFirst("h3")?.text().orEmpty()
                        seasonOf(h3) == res.season
                    }
                }
                val driveLinks = seasonGroups.flatMap { group ->
                    group.select("a.mfx-download-link, a[href]").mapNotNull { el ->
                        val text = el.text()
                        val href = el.attr("href").trim()
                        if (!href.startsWith("http")) null
                        else if (text.contains("Batch", true) || text.contains("Zip", true)) null
                        else href
                    }
                }.distinct()
                driveLinks.map { it.replace("mobilejsr.rest", "nexdrive.fit") }
                    .amap { link ->
                        emitDrivePage(link, res.episode, subtitleCallback, callback)
                    }
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "themoviesflix: ${e.message}")
        }
    }
}

internal object MultimoviesSite {
    private const val DEFAULT_DOMAIN = "https://multimovies.casa"

    private data class PlayerOption(
        val post: String,
        val nume: String,
        val type: String,
        val label: String
    )

    private fun optionsOf(doc: org.jsoup.nodes.Document): List<PlayerOption> {
        return doc.select("li.dooplay_player_option").mapNotNull { li ->
            val post = li.attr("data-post").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val nume = li.attr("data-nume").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = li.attr("data-type").ifBlank { "movie" }
            val id = li.attr("id").orEmpty()
            val title = li.selectFirst("span.title")?.text()?.trim().orEmpty()
            if (id.contains("trailer", true) || title.contains("trailer", true)) return@mapNotNull null
            val label = title.replace(Regex("(?i)(- )?Recommended"), "").trim()
                .replace("GDMIRROR", "GD Mirror", true).ifBlank { type }
            PlayerOption(post, nume, type, label)
        }
    }

    private suspend fun embedOf(domain: String, option: PlayerOption, pageUrl: String): String? {
        return try {
            val text = app.post(
                "$domain/wp-admin/admin-ajax.php",
                headers = mapOf(
                    "User-Agent" to CSKY_UA,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to pageUrl
                ),
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to option.post,
                    "nume" to option.nume,
                    "type" to option.type
                ),
                timeout = 15000L
            ).text
            CskyNet.deEsc(JSONObject(text).optString("embed_url"))
                .trim()
                .removeSurrounding("\"")
                .takeIf { it.startsWith("http") && !it.contains("youtube", true) }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveEmbed(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = try {
            Uri.parse(embedUrl).host ?: ""
        } catch (e: Exception) {
            ""
        }
        when {
            host.contains("modiplay") -> CskyModiplay.resolve(embedUrl, label, subtitleCallback, callback)
            host.contains("iqsmartgames") || host.contains("filesforever") ->
                CskyGdmirror.resolve(embedUrl, label, subtitleCallback, callback)
            else -> {
                val handled = CskyPacker.resolvePackedEmbed(embedUrl, label, "https://multimovies.casa/", callback)
                if (!handled) {
                    try {
                        val res = app.get(embedUrl, headers = CskyNet.headers("https://multimovies.casa/"), timeout = 20000L)
                        val text = res.text
                        val unpacked = if (text.contains("eval(function(p,a,c,k,e,d)")) {
                            runCatching { getAndUnpack(text) }.getOrNull() ?: text
                        } else text
                        for (m in Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""").findAll(unpacked)) {
                            CskyPacker.emitM3u8(m.groupValues[1], CskyNet.getBaseUrl(res.url), label, callback)
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    private suspend fun processPage(
        domain: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(pageUrl, headers = CskyNet.headers(domain), timeout = 20000L).document
        } catch (e: Exception) {
            return
        }
        val options = optionsOf(doc)
        if (options.isEmpty()) return
        coroutineScope {
            options.forEach { option ->
                async(Dispatchers.IO) {
                    val embed = embedOf(domain, option, pageUrl) ?: return@async
                    resolveEmbed(embed, "Multimovies ${option.label}", subtitleCallback, callback)
                }
            }
        }.let { }
    }

    suspend fun invoke(
        res: CskyLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = FirebaseDomainHelper.getDomain("cskyplay_multimovies")
                ?: FirebaseDomainHelper.getDomain("multimovies")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return
            val slug = CskyNet.slugify(title)

            val direct = if (res.season != null && res.episode != null) {
                "$domain/episodes/$slug-${res.season}x${res.episode}"
            } else if (res.season == null) {
                "$domain/movies/$slug"
            } else {
                null
            }

            if (direct != null) {
                try {
                    val probe = app.get(direct, headers = CskyNet.headers(domain), timeout = 15000L)
                    if (probe.code == 200 && probe.text.contains("dooplay_player_option")) {
                        processPage(domain, direct, subtitleCallback, callback)
                        return
                    }
                } catch (e: Exception) {
                }
            }

            val searchDoc = app.get(
                "$domain/?s=${Uri.encode(title)}",
                headers = CskyNet.headers(domain),
                timeout = 20000L
            ).document
            val normTitle = CskyNet.normalizeTitle(title)
            val candidates = searchDoc.select("article a[href], .result-item a[href], .items a[href]")
                .mapNotNull { el ->
                    val href = el.attr("href").trim()
                    if (!href.startsWith("http")) null
                    val text = el.text().trim()
                    if (CskyNet.normalizeTitle(text).contains(normTitle)) href else null
                }
                .distinct()
                .filter { it.contains("/tvshows/") || it.contains("/movies/") }

            if (res.season == null) {
                val movieUrl = candidates.firstOrNull { it.contains("/movies/") } ?: return
                processPage(domain, movieUrl, subtitleCallback, callback)
            } else {
                val showUrl = candidates.firstOrNull { it.contains("/tvshows/") }
                if (showUrl == null) {
                    candidates.firstOrNull { it.contains("/movies/") }?.let {
                        processPage(domain, it, subtitleCallback, callback)
                    }
                    return
                }
                val showDoc = app.get(showUrl, headers = CskyNet.headers(domain), timeout = 20000L).document
                val episodeUrl = showDoc.select("div.se-c").mapNotNull { seC ->
                    val seasonNum = seC.selectFirst("span.se-t")?.text()?.trim()?.toIntOrNull()
                    if (seasonNum != res.season) null
                    else seC.select("ul.episodios li").mapNotNull { li ->
                        val numerando = li.selectFirst("div.numerando")?.text().orEmpty()
                        val epNum = Regex("""(\d+)\s*-\s*(\d+)""").find(numerando)?.groupValues?.get(2)?.toIntOrNull()
                        if (res.episode == null || epNum == res.episode) {
                            li.selectFirst("div.episodiotitle a")?.attr("href")?.trim()?.takeIf { it.startsWith("http") }
                        } else null
                    }
                }.flatten().firstOrNull()

                if (episodeUrl != null) {
                    processPage(domain, episodeUrl, subtitleCallback, callback)
                } else if (res.episode != null) {
                    val guess = "$domain/episodes/$slug-${res.season}x${res.episode}"
                    try {
                        val probe = app.get(guess, headers = CskyNet.headers(domain), timeout = 15000L)
                        if (probe.code == 200 && probe.text.contains("dooplay_player_option")) {
                            processPage(domain, guess, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "multimovies: ${e.message}")
        }
    }
}

internal object NetNaijaSite {
    private const val API = "https://h5-api.aoneroom.com"
    private const val DEFAULT_SITE = "https://netnaija.film"
    private const val NA_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var token: String? = null

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaDub(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("detailPath") val detailPath: String? = null,
        @JsonProperty("lanName") val lanName: String? = null,
        @JsonProperty("type") val type: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaSubject(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("detailPath") val detailPath: String? = null,
        @JsonProperty("dubs") val dubs: List<NaDub>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaSearchData(val items: List<NaSubject>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaSearchResponse(val data: NaSearchData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaSeason(
        @JsonProperty("se") val se: Int? = null,
        @JsonProperty("maxEp") val maxEp: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaResource(val seasons: List<NaSeason>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaDetailData(
        val subject: NaSubject? = null,
        val resource: NaResource? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaDetailResponse(val data: NaDetailData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaStream(
        val format: String? = null,
        val id: String? = null,
        val url: String? = null,
        val resolutions: String? = null,
        val size: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaPlayData(
        val streams: List<NaStream>? = null,
        val hls: List<NaStream>? = null,
        val dash: List<NaStream>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaPlayResponse(val data: NaPlayData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaCaption(
        val lan: String? = null,
        val lanName: String? = null,
        val url: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaCaptionData(val captions: List<NaCaption>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaCaptionResponse(val data: NaCaptionData? = null)

    private fun xClientToken(): String {
        val ts = System.currentTimeMillis() / 1000
        val reversed = ts.toString().reversed()
        val md5 = MessageDigest.getInstance("MD5").digest(reversed.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$ts,$md5"
    }

    private suspend fun refreshToken(site: String): String? {
        token?.let { return it }
        return try {
            val res = app.get(
                "$API/wefeed-h5api-bff/home",
                headers = mapOf(
                    "User-Agent" to NA_UA,
                    "Accept" to "application/json",
                    "Origin" to site,
                    "Referer" to "$site/",
                    "X-Request-Lang" to "en",
                    "X-Client-Info" to """{"timezone":"Asia/Kolkata"}""",
                    "X-Client-Token" to xClientToken()
                ),
                timeout = 15000L
            )
            val xUser = res.headers["x-user"] ?: return null
            val t = JSONObject(xUser).optString("token").takeIf { it.isNotBlank() }
            t?.let { token = it }
            t
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun authHeaders(site: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to NA_UA,
            "Accept" to "application/json",
            "Origin" to site,
            "Referer" to "$site/",
            "X-Request-Lang" to "en",
            "X-Client-Info" to """{"timezone":"Asia/Kolkata"}"""
        )
        val t = refreshToken(site)
        if (!t.isNullOrBlank()) {
            h["Authorization"] = "Bearer $t"
        } else {
            h["X-Client-Token"] = xClientToken()
        }
        h.putAll(extra)
        return h
    }

    suspend fun invoke(
        res: CskyLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val site = FirebaseDomainHelper.getDomain("cskyplay_netnaija")
                ?: FirebaseDomainHelper.getDomain("netnaija")
                ?: DEFAULT_SITE
            val title = res.title ?: return
            val normTitle = CskyNet.normalizeTitle(title)
            val wantTv = res.season != null

            val body = JSONObject()
                .put("keyword", title)
                .put("page", 1)
                .put("perPage", 30)
                .put("subjectType", 0)
                .toString()
            val searchRes = app.post(
                "$API/wefeed-h5api-bff/subject/search",
                headers = authHeaders(site, mapOf("Content-Type" to "application/json")),
                requestBody = body.toRequestBody("application/json".toMediaType()),
                timeout = 15000L
            )
            val items = try {
                AppUtils.parseJson<NaSearchResponse>(searchRes.text).data?.items.orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            val matched = items.filter { it.title != null && CskyNet.normalizeTitle(it.title).contains(normTitle) }
                .filter { sub ->
                    when (sub.subjectType) {
                        1 -> !wantTv
                        2 -> wantTv
                        else -> true
                    }
                }
                .ifEmpty { matchedFallback(items, normTitle, wantTv) }
            val subject = matched.firstOrNull() ?: return
            val detailPath = subject.detailPath ?: return

            val detailRes = app.get(
                "$API/wefeed-h5api-bff/detail",
                params = mapOf("detailPath" to detailPath),
                headers = authHeaders(site),
                timeout = 15000L
            )
            val detail = try {
                AppUtils.parseJson<NaDetailResponse>(detailRes.text).data
            } catch (e: Exception) {
                null
            } ?: return
            val subj = detail.subject ?: return
            if (wantTv) {
                val seasonOk = detail.resource?.seasons.orEmpty().any { it.se == res.season }
                if (!seasonOk) return
            }

            val dubs = (subj.dubs ?: emptyList())
                .filter { it.type == 0 }
                .ifEmpty {
                    listOf(NaDub(subjectId = subj.subjectId, detailPath = detailPath, lanName = "Original", type = 0))
                }
                .take(6)

            var subtitlesDone = false
            dubs.forEach { dub ->
                val dubSubjectId = dub.subjectId ?: return@forEach
                val dubDetailPath = dub.detailPath ?: detailPath
                val lanName = dub.lanName ?: "Original"
                val audioLabel = lanName.replace("dub", "Audio", ignoreCase = true).trim()
                try {
                    val playRes = app.get(
                        "$API/wefeed-h5api-bff/subject/play",
                        params = mapOf(
                            "subjectId" to dubSubjectId,
                            "se" to "${res.season ?: 0}",
                            "ep" to "${res.episode ?: 0}",
                            "detailPath" to dubDetailPath
                        ),
                        headers = authHeaders(site, mapOf("X-Source" to "webNetnaijaSite")),
                        timeout = 20000L
                    )
                    val play = try {
                        AppUtils.parseJson<NaPlayResponse>(playRes.text).data
                    } catch (e: Exception) {
                        null
                    } ?: return@forEach

                    play.streams.orEmpty().forEach { stream ->
                        val url = stream.url ?: return@forEach
                        val quality = stream.resolutions?.toIntOrNull() ?: Qualities.Unknown.value
                        val size = stream.size?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                        callback(
                            newExtractorLink(
                                "NetNaija $audioLabel",
                                "NetNaija $audioLabel ${stream.resolutions ?: ""}p$size",
                                url,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                                this.headers = mapOf(
                                    "Referer" to "$site/",
                                    "Origin" to site,
                                    "User-Agent" to NA_UA
                                )
                            }
                        )
                    }

                    play.hls.orEmpty().forEach { stream ->
                        val url = stream.url ?: return@forEach
                        try {
                            M3u8Helper.generateM3u8(
                                "NetNaija $audioLabel HLS",
                                url,
                                "$site/",
                                headers = mapOf("User-Agent" to NA_UA)
                            ).forEach(callback)
                        } catch (e: Exception) {
                            callback(
                                newExtractorLink(
                                    "NetNaija $audioLabel",
                                    "NetNaija $audioLabel HLS",
                                    url,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.headers = mapOf("User-Agent" to NA_UA)
                                }
                            )
                        }
                    }

                    if (!subtitlesDone) {
                        subtitlesDone = true
                        val firstStreamId = play.streams?.firstOrNull()?.id
                        if (firstStreamId != null) {
                            try {
                                val capRes = app.get(
                                    "$API/wefeed-h5api-bff/subject/caption",
                                    params = mapOf(
                                        "format" to "MP4",
                                        "id" to firstStreamId,
                                        "subjectId" to dubSubjectId,
                                        "detailPath" to dubDetailPath
                                    ),
                                    headers = authHeaders(site),
                                    timeout = 15000L
                                ).text
                                val captions = AppUtils.parseJson<NaCaptionResponse>(capRes).data?.captions
                                captions?.forEach { cap ->
                                    if (!cap.url.isNullOrBlank()) {
                                        subtitleCallback(newSubtitleFile(cap.lanName ?: cap.lan ?: "English", cap.url) {})
                                    }
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(CskyNet.TAG, "netnaija dub $lanName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "netnaija: ${e.message}")
        }
    }

    private fun matchedFallback(items: List<NaSubject>, normTitle: String, wantTv: Boolean): List<NaSubject> {
        return items.filter { sub ->
            CskyNet.normalizeTitle(sub.title).contains(normTitle) &&
                    (sub.subjectType == null || (if (wantTv) sub.subjectType == 2 else sub.subjectType == 1))
        }
    }
}

internal object MovieBoxSite {
    private const val API = "https://api3.aoneroom.com"
    private const val MB_UA =
        "com.community.mbox.in/50020126 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"

    @Volatile
    private var token: String? = null

    private val secretKey: ByteArray by lazy {
        base64DecodeArray("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")
    }

    private val deviceId: String by lazy {
        val chars = "0123456789abcdef"
        (1..16).map { chars[(Math.random() * chars.length).toInt()] }.joinToString("")
    }

    private val clientInfo: String by lazy {
        JSONObject()
            .put("package_name", "com.community.mbox.in")
            .put("version_name", "4.0.02.0831.03")
            .put("version_code", 50020126)
            .put("os", "android")
            .put("os_version", "14")
            .put("device_id", deviceId)
            .put("install_store", "official")
            .put("gaid", "1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d")
            .put("brand", "Google")
            .put("model", "Pixel 8")
            .put("system_language", "en")
            .put("net", "NETWORK_WIFI")
            .put("region", "IN")
            .put("timezone", "Asia/Calcutta")
            .put("sp_code", "")
            .toString()
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun hmacMd5(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key, "HmacMD5"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun xClientToken(): String {
        val ts = System.currentTimeMillis().toString()
        return "$ts,${md5Hex(ts.reversed())}"
    }

    private fun buildHeaders(
        method: String,
        url: String,
        contentType: String = "application/json",
        accept: String = "application/json",
        body: String? = null
    ): Map<String, String> {
        val ts = System.currentTimeMillis().toString()
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            return emptyMap()
        }
        val path = uri.rawPath ?: ""
        val query = uri.rawQuery?.split("&")
            ?.map { p -> p.split("=", limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") } }
            ?.sortedBy { it.first }
            ?.joinToString("&") { "${it.first}=${it.second}" }
            .orEmpty()
        val canonicalUrl = if (query.isNotBlank()) "$path?$query" else path
        val bodyHash = if (body != null) md5Hex(body.take(102400)) else ""
        val bodyLen = body?.length?.toString() ?: ""
        val canonical = listOf(
            method.uppercase(Locale.ROOT),
            accept,
            contentType,
            bodyLen,
            ts,
            bodyHash,
            canonicalUrl
        ).joinToString("\n")
        val sig = android.util.Base64.encodeToString(hmacMd5(secretKey, canonical), android.util.Base64.NO_WRAP)
            .replace("\n", "").replace("\r", "")
        val h = mutableMapOf(
            "User-Agent" to MB_UA,
            "Accept" to accept,
            "Content-Type" to contentType,
            "Connection" to "keep-alive",
            "x-client-token" to xClientToken(),
            "x-tr-signature" to "$ts|2|$sig",
            "x-client-info" to clientInfo,
            "x-client-status" to "0"
        )
        token?.takeIf { it.isNotBlank() }?.let { h["Authorization"] = "Bearer $it" }
        return h
    }

    private suspend fun ensureToken(apiBase: String): String? {
        token?.let { return it }
        val urls = listOf(
            "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1",
            "$apiBase/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
        )
        for (u in urls) {
            try {
                val res = app.get(u, headers = buildHeaders("GET", u), timeout = 15000L)
                val xUser = res.headers["x-user"] ?: continue
                val t = JSONObject(xUser).optString("token").takeIf { it.isNotBlank() } ?: continue
                token = t
                return t
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun cleanMbTitle(s: String): String {
        var out = s.replace(Regex("\\[.*?]"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("(?i)\\b(dub|dubbed|hd|4k|hindi|tamil|telugu|dual audio)\\b"), "")
            .replace(Regex("[^a-zA-Z0-9 ]"), " ")
        out = Regex("\\s+").replace(out, " ").trim()
        return out.ifBlank { s }
    }

    private fun qualityOf(resolutions: String?): Int? {
        if (resolutions.isNullOrBlank()) return null
        for ((label, value) in listOf(
            "2160" to Qualities.P2160.value, "1440" to Qualities.P1440.value,
            "1080" to Qualities.P1080.value, "720" to Qualities.P720.value,
            "480" to Qualities.P480.value, "360" to Qualities.P360.value,
            "240" to Qualities.P240.value
        )) {
            if (resolutions.contains(label, true)) return value
        }
        return null
    }

    private fun policyResource(signCookie: String?): String? {
        if (signCookie.isNullOrBlank()) return null
        val policyRaw = Regex("CloudFront-Policy=([^;]+)").find(signCookie)?.groupValues?.get(1) ?: return null
        val decoded = runCatching {
            val cf = policyRaw.replace('-', '+').replace('~', '/').replace('_', '=')
            val padded = if (cf.length % 4 > 0) cf + "=".repeat(4 - cf.length % 4) else cf
            String(base64DecodeArray(padded), Charsets.UTF_8)
        }.getOrNull() ?: runCatching {
            val std = policyRaw.replace('-', '+').replace('_', '/')
            val padded = if (std.length % 4 > 0) std + "=".repeat(4 - std.length % 4) else std
            String(base64DecodeArray(padded), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return try {
            val root = JSONObject(decoded)
            val resource = root.optJSONArray("Statement")?.optJSONObject(0)?.optString("Resource")
            resource?.trimEnd('*', '/')?.let { if (it.endsWith(".mpd", true)) it else "$it/index.mpd" }
        } catch (e: Exception) {
            null
        }
    }

    private data class MbCandidate(val id: String, val title: String, val score: Int, val year: Int?, val isTv: Boolean)

    private suspend fun searchCandidates(apiBase: String, query: String, res: CskyLinkData): List<MbCandidate> {
        val url = "$apiBase/wefeed-mobile-bff/subject-api/search/v2"
        val body = JSONObject()
            .put("page", 1)
            .put("perPage", 20)
            .put("keyword", query)
            .toString()
        val response = app.post(
            url,
            headers = buildHeaders("POST", url, "application/json; charset=utf-8", "application/json", body),
            requestBody = body.toRequestBody("application/json".toMediaType()),
            timeout = 15000L
        )
        if (response.code == 401 || response.code == 441) {
            token = null
            ensureToken(apiBase)
            val retry = app.post(
                url,
                headers = buildHeaders("POST", url, "application/json; charset=utf-8", "application/json", body),
                requestBody = body.toRequestBody("application/json".toMediaType()),
                timeout = 15000L
            )
            return parseCandidates(retry.text, res)
        }
        return parseCandidates(response.text, res)
    }

    private fun parseCandidates(text: String, res: CskyLinkData): List<MbCandidate> {
        return try {
            val results = JSONObject(text).getJSONObject("data").optJSONArray("results") ?: return emptyList()
            val wantTv = res.season != null
            val targetClean = CskyNet.normalizeTitle(cleanMbTitle(res.title ?: ""))
            val candidates = mutableListOf<MbCandidate>()
            for (i in 0 until results.length()) {
                val subjects = results.optJSONObject(i)?.optJSONArray("subjects") ?: continue
                for (j in 0 until subjects.length()) {
                    val s = subjects.optJSONObject(j) ?: continue
                    val id = s.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val title = s.optString("title").takeIf { it.isNotBlank() } ?: continue
                    val subjectType = s.optInt("subjectType", 0)
                    val isTv = subjectType == 2 || subjectType == 7
                    val year = s.optString("releaseDate").take(4).toIntOrNull()
                    var score = 0
                    val candClean = CskyNet.normalizeTitle(cleanMbTitle(title))
                    val rawTitle = res.title ?: ""
                    when {
                        candClean == targetClean || title.equals(rawTitle, true) -> score += 100
                        candClean.contains(targetClean) || targetClean.contains(candClean) -> score += 50
                    }
                    score += if (isTv == wantTv) 40 else -30
                    when (year) {
                        res.year -> score += 50
                        null -> {}
                        else -> if (kotlin.math.abs(year - (res.year ?: year)) <= 1) score += 20 else score -= 40
                    }
                    candidates.add(MbCandidate(id, title, score, year, isTv))
                }
            }
            candidates.sortedByDescending { it.score }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun invoke(
        res: CskyLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val apiBase = FirebaseDomainHelper.getDomain("cskyplay_moviebox") ?: API
            ensureToken(apiBase) ?: return
            val title = res.title ?: return
            var candidates = searchCandidates(apiBase, cleanMbTitle(title), res)
            if (candidates.isEmpty()) {
                candidates = searchCandidates(apiBase, title, res)
            }
            if (candidates.isEmpty()) return
            val top = if (candidates.first().score >= 100) {
                listOf(candidates.first())
            } else {
                candidates.take(2)
            }

            for (candidate in top) {
                try {
                    val getUrl = "$apiBase/wefeed-mobile-bff/subject-api/get?subjectId=${candidate.id}"
                    val getRes = app.get(getUrl, headers = buildHeaders("GET", getUrl), timeout = 15000L)
                    getRes.headers["x-user"]?.let {
                        runCatching { token = JSONObject(it).optString("token").takeIf { t -> t.isNotBlank() } }
                    }
                    val data = JSONObject(getRes.text).optJSONObject("data") ?: continue
                    val dubs = data.optJSONArray("dubs") ?: continue
                    val dubPairs = mutableListOf<Pair<String, String>>()
                    for (i in 0 until dubs.length()) {
                        val d = dubs.optJSONObject(i) ?: continue
                        val id = d.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val lan = d.optString("lanName").ifBlank { "Original" }
                        dubPairs.add(id to lan)
                    }
                    if (dubPairs.isEmpty()) {
                        dubPairs.add(candidate.id to "Original")
                    }

                    for ((dubId, lan) in dubPairs.take(5)) {
                        val audioLabel = lan.replace("dub", "Audio", ignoreCase = true).trim()
                        val playUrl = "$apiBase/wefeed-mobile-bff/subject-api/play-info" +
                            "?subjectId=$dubId&se=${res.season ?: 0}&ep=${res.episode ?: 0}"
                        val playRes = app.get(playUrl, headers = buildHeaders("GET", playUrl), timeout = 20000L)
                        if (playRes.code == 401 || playRes.code == 441) {
                            token = null
                            ensureToken(apiBase)
                            continue
                        }
                        val playData = JSONObject(playRes.text).optJSONObject("data") ?: continue
                        val streams = playData.optJSONArray("streams") ?: continue
                        for (i in 0 until streams.length()) {
                            val stream = streams.optJSONObject(i) ?: continue
                            val streamUrl = stream.optString("url").takeIf { it.isNotBlank() } ?: continue
                            val signCookie = stream.optString("signCookie").takeIf { it.isNotBlank() }
                            val resolutions = stream.optString("resolutions")
                            val quality = qualityOf(resolutions)
                            val policyUrl = policyResource(signCookie) ?: streamUrl
                            if (policyUrl.contains("b164fbfb4347792950bdfbfb563d39d9") ||
                                streamUrl.contains("/other/2026/09/04/")
                            ) {
                                continue
                            }
                            val resLabel = if (resolutions.isNotBlank()) " $resolutions p" else ""
                            val linkType = when {
                                policyUrl.contains(".mpd") -> ExtractorLinkType.DASH
                                policyUrl.startsWith("magnet:") -> continue
                                policyUrl.contains(".torrent") -> continue
                                policyUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                                policyUrl.contains(".mp4") || policyUrl.contains(".mkv") -> ExtractorLinkType.VIDEO
                                else -> ExtractorLinkType.VIDEO
                            }
                            val headers = mutableMapOf(
                                "Referer" to "$apiBase/",
                                "User-Agent" to MB_UA
                            )
                            signCookie?.let { headers["Cookie"] = it }
                            callback(
                                newExtractorLink(
                                    "MovieBox $audioLabel",
                                    "MovieBox ($audioLabel$resLabel)",
                                    policyUrl,
                                    linkType
                                ) {
                                    this.quality = quality ?: Qualities.Unknown.value
                                    this.headers = headers
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.d(CskyNet.TAG, "moviebox candidate: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "moviebox: ${e.message}")
        }
    }
}
