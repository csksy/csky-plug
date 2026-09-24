package com.cskyplay

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

internal object VegaMoviesSite {
    private const val DEFAULT_DOMAIN = "https://vegamovies.gallery"

    private fun headers(api: String): Map<String, String> = mapOf(
        "User-Agent" to CSKY_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "cookie" to "xla=s4t",
        "Referer" to "$api/"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VegaDoc(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("post_title") val postTitle: String? = null,
        @JsonProperty("permalink") val permalink: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VegaHit(val document: VegaDoc? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VegaResponse(val hits: List<VegaHit> = emptyList())

    private suspend fun fetchResults(api: String, query: String): List<VegaDoc> = try {
        val text = app.get(
            "$api/search.php?q=${Uri.encode(query)}",
            headers = headers(api),
            timeout = 15000L
        ).text
        AppUtils.parseJson<VegaResponse>(text).hits.mapNotNull { it.document }
    } catch (e: Exception) {
        emptyList()
    }

    private fun episodeRegex(episode: Int?): Regex? {
        if (episode == null) return null
        return Regex("(?i)Episodes?\\s*:?\\s*0*${episode}(?!\\d)")
    }

    private fun externalLinks(doc: Document, base: String): List<String> {
        val skip = Regex("nexdrive\\.fit|nexdrive\\.cv|mdrive\\.cloud|mobilejsr\\.rest|gmpg\\.org|vglist|vegamovies|themoviesflix|moviesflix|fonts\\.|googleapis|googletagmanager|sharethis|catimages|i-poster|scene-source")
        return doc.select("a[href]").mapNotNull { el ->
            val href = el.attr("href").trim()
            if (href.startsWith("http") && !skip.containsMatchIn(href)) href else null
        }.distinct()
    }

    private suspend fun emitDrivePage(
        api: String,
        driveUrl: String,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(driveUrl, headers = headers(api), timeout = 20000L).document
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
                        .forEach { CskyNet.emitSiteLink("VegaMovies", it, "", null, driveUrl, subtitleCallback, callback) }
                    return
                }
            }
            externalLinks(doc, api).forEach {
                CskyNet.emitSiteLink("VegaMovies", it, "", null, driveUrl, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "vega drive page: ${e.message}")
        }
    }

    suspend fun invoke(
        res: CskyLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val api = FirebaseDomainHelper.getDomain("cskyplay_vegamovies")
                ?: FirebaseDomainHelper.getDomain("vegamovies")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return
            val imdbId = res.imdbId?.takeIf { it.isNotBlank() }

            val docs = if (imdbId != null) {
                val byImdb = fetchResults(api, imdbId)
                val imdbMatch = byImdb.firstOrNull { it.imdbId.equals(imdbId, ignoreCase = true) }
                if (imdbMatch != null) listOf(imdbMatch) else fetchResults(api, title)
            } else {
                fetchResults(api, title)
            }
            if (docs.isEmpty()) return
            val target = docs.firstOrNull { it.postTitle?.contains(title, ignoreCase = true) == true }
                ?: docs.firstOrNull()
                ?: return
            val permalink = target.permalink?.takeIf { it.isNotBlank() } ?: return
            val postUrl = if (permalink.startsWith("http")) permalink else api + permalink
            val doc = app.get(postUrl, headers = headers(api), timeout = 20000L).document

            if (res.season == null) {
                val pages = doc.select("a:has(button.dwd-button)")
                    .map { it.attr("href").trim() }
                    .filter { it.startsWith("http") }
                    .distinct()
                if (pages.isNotEmpty()) {
                    pages.amap { page ->
                        emitDrivePage(api, page, null, subtitleCallback, callback)
                    }
                } else {
                    val driveAnchors = doc.select("a[href]").mapNotNull { el ->
                        val text = el.text()
                        val href = el.attr("href").trim()
                        if (!href.startsWith("http")) null
                        else if (text.contains("Batch", true) || text.contains("Zip", true)) null
                        else if (text.contains("V-Cloud", true) || text.contains("G-Direct", true)) href
                        else null
                    }.distinct()
                    driveAnchors.amap { page ->
                        emitDrivePage(api, page, null, subtitleCallback, callback)
                    }
                }
            } else {
                val seasonRegex = Regex("(?i)Season\\s*${res.season}(?!\\d)")
                val heads = doc.select("h3, h5").filter { el ->
                    val text = el.text()
                    seasonRegex.containsMatchIn(text) || text.contains("Episode", true)
                }
                val targets = mutableListOf<String>()
                for (head in heads) {
                    var sib = head.nextElementSibling()
                    while (sib != null && sib.tagName() !in listOf("h3", "h4", "h5")) {
                        for (a in sib.select("a[href]")) {
                            val text = a.text()
                            val href = a.attr("href").trim()
                            if (!href.startsWith("http")) continue
                            if (text.contains("Batch", true) || text.contains("Zip", true)) continue
                            if (text.contains("V-Cloud", true) || text.contains("Episode", true) ||
                                text.contains("G-Direct", true) || text.contains("Download", true)
                            ) {
                                targets.add(href)
                            }
                        }
                        sib = sib.nextElementSibling()
                    }
                }
                targets.distinct().amap { link ->
                    emitDrivePage(api, link, res.episode, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "vegamovies: ${e.message}")
        }
    }
}

internal object HdHub4uSite {
    private const val DEFAULT_DOMAIN = "https://new6.hdhub4u.cl"

    private suspend fun typesenseSearch(domain: String, query: String): List<Pair<String, String>> {
        return try {
            val url = "https://search.pingora.fyi/collections/post/documents/search" +
                "?q=${Uri.encode(query)}" +
                "&query_by=post_title,category&query_by_weights=4,2" +
                "&sort_by=sort_by_date:desc&limit=20&highlight_fields=none&use_cache=true&page=1"
            val text = app.get(
                url,
                headers = mapOf("User-Agent" to CSKY_UA, "Referer" to "$domain/"),
                timeout = 15000L
            ).text
            val hits = JSONObject(text).optJSONArray("hits") ?: return emptyList()
            (0 until hits.length()).mapNotNull { i ->
                val d = hits.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
                val postTitle = d.optString("post_title")
                val permalink = d.optString("permalink")
                if (postTitle.isBlank() || permalink.isBlank()) return@mapNotNull null
                val url2 = if (permalink.startsWith("http", true)) {
                    val path = try {
                        URI(permalink).path
                    } catch (e: Exception) {
                        null
                    }
                    if (path.isNullOrBlank()) permalink else domain + path
                } else {
                    domain + permalink
                }
                postTitle to url2
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun emitResolved(
        url: String,
        label: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val target = if (url.contains("id=")) CskyNet.decryptIdLink(url, referer) ?: url else url
        if (target.isBlank()) return
        CskyNet.emitSiteLink("HDHub4u", target, label, CskyNet.getIndexQuality(label), referer, subtitleCallback, callback)
    }

    private suspend fun processPost(
        domain: String,
        postUrl: String,
        res: CskyLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(postUrl, headers = CskyNet.headers("$domain/"), timeout = 20000L).document
            if (res.season != null) {
                val epRegex = Regex("(?i)episode\\s*(\\d+)")
                for (h3 in doc.select("h3")) {
                    val links = h3.select("a[href]")
                    val epLink = links.firstOrNull { it.text().contains("episode", true) } ?: continue
                    val epNum = epRegex.find(epLink.text())?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (res.episode != null && epNum != res.episode) continue

                    val watch = links.firstOrNull { it.text().trim().equals("watch", true) }
                    if (watch != null) {
                        val watchHref = watch.absUrl("href").ifBlank { watch.attr("href") }
                        if (watchHref.startsWith("http")) {
                            emitResolved(watchHref, "Episode $epNum Watch", domain, subtitleCallback, callback)
                        }
                    }

                    val href = epLink.absUrl("href").ifBlank { epLink.attr("href") }
                    if (!href.startsWith("http")) continue
                    if (!href.contains("id=")) {
                        CskyNet.emitSiteLink("HDHub4u", href, "Episode $epNum", null, domain, subtitleCallback, callback)
                        continue
                    }
                    val resolved = CskyNet.decryptIdLink(href, domain) ?: continue
                    if (resolved.contains("id=")) {
                        val second = CskyNet.decryptIdLink(resolved, domain) ?: resolved
                        CskyNet.emitSiteLink("HDHub4u", second, "Episode $epNum", null, domain, subtitleCallback, callback)
                    } else {
                        try {
                            val epDoc = app.get(resolved, headers = CskyNet.headers(domain), timeout = 20000L).document
                            val inner = epDoc.select("h3 a[href], h4 a[href], h5 a[href], div.entry-content a[href]")
                                .mapNotNull { it.absUrl("href").ifBlank { null } }
                                .filter { it.startsWith("http") }
                                .distinct()
                            for (link in inner) {
                                if (link.contains("id=")) {
                                    val final = CskyNet.decryptIdLink(link, domain) ?: continue
                                    CskyNet.emitSiteLink("HDHub4u", final, "Episode $epNum", null, domain, subtitleCallback, callback)
                                } else {
                                    CskyNet.emitSiteLink("HDHub4u", link, "Episode $epNum", null, domain, subtitleCallback, callback)
                                }
                            }
                        } catch (e: Exception) {
                            CskyNet.emitSiteLink("HDHub4u", resolved, "Episode $epNum", null, domain, subtitleCallback, callback)
                        }
                    }
                }
            } else {
                for (el in doc.select("h3 a:matches((?i)480|720|1080|2160|4K), h4 a:matches((?i)480|720|1080|2160|4K)")) {
                    val href = el.absUrl("href").ifBlank { el.attr("href") }
                    if (!href.startsWith("http")) continue
                    emitResolved(href, el.text().trim(), domain, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "hdhub4u post: ${e.message}")
        }
    }

    suspend fun invoke(
        res: CskyLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = FirebaseDomainHelper.getDomain("cskyplay_hdhub4u")
                ?: FirebaseDomainHelper.getDomain("hdhub4u")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return
            val normTitle = CskyNet.normalizeTitle(title)
            val posts = typesenseSearch(domain, title)
            if (posts.isEmpty()) return

            val titleMatched = posts.filter { (postTitle, _) ->
                val normPost = CskyNet.normalizeTitle(postTitle)
                normPost.contains(normTitle)
            }
            val filtered = titleMatched.filter { (postTitle, _) ->
                when {
                    res.season != null -> postTitle.contains("season ${res.season}", true) ||
                            postTitle.contains("S${res.season.toString().padStart(2, '0')}", true)
                    res.year != null -> postTitle.contains("${res.year}")
                    else -> true
                }
            }
            val relaxed = filtered.ifEmpty {
                titleMatched.filter { (postTitle, _) ->
                    res.season == null || postTitle.contains("season ${res.season}", true)
                }
            }
            val chosen = relaxed.ifEmpty { titleMatched }.take(2)
            chosen.forEach { (_, postUrl) ->
                processPost(domain, postUrl, res, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "hdhub4u: ${e.message}")
        }
    }
}

internal object FourKhdHubSite {
    private const val DEFAULT_DOMAIN = "https://4khdhub.one"

    private suspend fun emitLinks(
        hrefs: List<String>,
        label: String,
        domain: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        hrefs.distinct().forEach { href ->
            if (!href.startsWith("http")) return@forEach
            val target = if (href.contains("id=")) CskyNet.decryptIdLink(href, domain) ?: href else href
            if (target.isNotBlank()) {
                CskyNet.emitSiteLink("4KHDHub", target, label, CskyNet.getIndexQuality(label), domain, subtitleCallback, callback)
            }
        }
    }

    suspend fun invoke(
        res: CskyLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = FirebaseDomainHelper.getDomain("cskyplay_4khdhub")
                ?: FirebaseDomainHelper.getDomain("4khdhub")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return
            val normTitle = CskyNet.normalizeTitle(title)
            val yearStr = res.year?.toString()

            val searchDoc = app.get(
                "$domain/?s=${Uri.encode(title)}",
                headers = CskyNet.headers(),
                timeout = 20000L
            ).document
            val elements = searchDoc.select("div.card-grid > a.movie-card")
            fun contentOf(el: Element): String = el.selectFirst("div.movie-card-content")?.text()?.lowercase() ?: ""

            val matched = elements.firstOrNull { el ->
                val content = contentOf(el)
                val normContent = CskyNet.normalizeTitle(content)
                normContent.contains(normTitle) && (yearStr == null || content.contains(yearStr))
            } ?: elements.firstOrNull { el ->
                CskyNet.normalizeTitle(contentOf(el)).contains(normTitle)
            } ?: return

            val href = matched.attr("href").trim()
            val detailUrl = if (href.startsWith("http")) href else domain + href
            val doc = app.get(detailUrl, headers = CskyNet.headers(domain), timeout = 20000L).document

            if (res.season != null) {
                val seasonText = "S" + res.season.toString().padStart(2, '0')
                val episodeText = res.episode?.let { "E" + it.toString().padStart(2, '0') }
                val items = doc.select("div.episode-download-item").filter { el ->
                    val text = el.text()
                    text.contains(seasonText, true) && (episodeText == null || text.contains(episodeText, true))
                }
                val hrefs = items.flatMap { el ->
                    val quality = el.selectFirst("span[class*=badge-], .episode-file-title")?.text().orEmpty()
                    el.select("div.episode-links > a").map { it.attr("href").trim() to quality }
                }
                for ((link, quality) in hrefs) {
                    val target = if (link.contains("id=")) CskyNet.decryptIdLink(link, domain) ?: link else link
                    CskyNet.emitSiteLink("4KHDHub", target, quality, CskyNet.getIndexQuality(quality), domain, subtitleCallback, callback)
                }
            } else {
                val hrefs = doc.select("div.download-item a[href]").map { it.attr("href").trim() }
                val quality = doc.title()
                emitLinks(hrefs, quality, domain, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "4khdhub: ${e.message}")
        }
    }
}

internal object Movies4uSite {
    private const val DEFAULT_DOMAIN = "https://movies4u.cr"
    private val driveHostRegex = Regex("mdrive\\.cloud|nexdrive\\.fit|mobilejsr\\.rest|mdisk")

    private fun episodeRegex(episode: Int?): Regex? {
        if (episode == null) return null
        return Regex("(?i)Episodes?\\s*:?\\s*0*${episode}(?!\\d)")
    }

    private fun collectDriveAnchors(doc: Document): List<String> {
        return doc.select("a[href]").mapNotNull { el ->
            val text = el.text()
            val href = el.attr("href").trim()
            if (!href.startsWith("http")) null
            else if (text.contains("Batch", true) || text.contains("Zip", true)) null
            else if (driveHostRegex.containsMatchIn(href)) href
            else null
        }.distinct()
    }

    private fun pageExternalLinks(doc: Document): List<String> {
        val skip = Regex("mdrive\\.cloud|movies4u\\.cr|gmpg\\.org|googleapis|googletagmanager|fonts\\.|wp-|schema\\.org|twitter\\.com|facebook\\.com|pinterest|whatsapp|telegram\\.me|t\\.me/|catimages")
        return doc.select("div.entry-content a[href], article a[href], main a[href]")
            .mapNotNull { it.attr("href").trim() }
            .filter { it.startsWith("http") && !skip.containsMatchIn(it) }
            .distinct()
    }

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
                    links.filter { it.startsWith("http") && !it.contains("mdrive.cloud") }
                        .distinct()
                        .forEach { CskyNet.emitSiteLink("Movies4u", it, "", null, driveUrl, subtitleCallback, callback) }
                    return
                }
            }
            pageExternalLinks(doc).forEach {
                CskyNet.emitSiteLink("Movies4u", it, "", null, driveUrl, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "movies4u drive page: ${e.message}")
        }
    }

    suspend fun invoke(
        res: CskyLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = FirebaseDomainHelper.getDomain("cskyplay_movies4u")
                ?: FirebaseDomainHelper.getDomain("movies4u")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return
            val normTitle = CskyNet.normalizeTitle(title)

            val searchDoc = app.get(
                "$domain/?s=${Uri.encode(title)}",
                headers = CskyNet.headers(),
                timeout = 20000L
            ).document
            val anchors = searchDoc.select("article h2 a, article h3 a, h2.entry-title a, h3.entry-title a")
                .mapNotNull { el ->
                    val t = el.text().trim()
                    val href = el.attr("href").trim()
                    if (t.isNotBlank() && href.startsWith("http")) t to href else null
                }
                .filter { (t, _) -> CskyNet.normalizeTitle(t).contains(normTitle) }

            val filtered = anchors.filter { (t, _) ->
                when {
                    res.season != null -> t.contains("season ${res.season}", true) ||
                            t.contains("S${res.season.toString().padStart(2, '0')}", true)
                    res.year != null -> t.contains("${res.year}")
                    else -> true
                }
            }
            val chosen = filtered.ifEmpty {
                anchors.filter { (t, _) ->
                    res.season == null || t.contains("season ${res.season}", true)
                }
            }.take(2)
            if (chosen.isEmpty()) return

            chosen.forEach { (_, postUrl) ->
                try {
                    val doc = app.get(postUrl, headers = CskyNet.headers(domain), timeout = 20000L).document
                    if (res.season == null) {
                        val drives = collectDriveAnchors(doc).ifEmpty {
                            doc.select("a:has(button)").mapNotNull { el ->
                                val href = el.attr("href").trim()
                                if (href.startsWith("http") && driveHostRegex.containsMatchIn(href)) href else null
                            }.distinct()
                        }
                        drives.amap { driveUrl ->
                            emitDrivePage(driveUrl, null, subtitleCallback, callback)
                        }
                    } else {
                        val seasonRegex = Regex("(?i)Season\\s*${res.season}(?!\\d)")
                        val heads = doc.select("h2, h3, h4").filter { el ->
                            val text = el.text().replace('\u00A0', ' ')
                            seasonRegex.containsMatchIn(text) && text.contains("Episode", true)
                        }
                        val targets = mutableListOf<String>()
                        for (head in heads) {
                            var sib = head.nextElementSibling()
                            while (sib != null && sib.tagName() !in listOf("h2", "h3", "h4")) {
                                for (a in sib.select("a[href]")) {
                                    val text = a.text()
                                    val href = a.attr("href").trim()
                                    if (!href.startsWith("http")) continue
                                    if (text.contains("Batch", true) || text.contains("Zip", true)) continue
                                    if (text.contains("G-Direct", true) || text.contains("V-Cloud", true) ||
                                        text.contains("Download", true)
                                    ) {
                                        targets.add(href)
                                    }
                                }
                                sib = sib.nextElementSibling()
                            }
                        }
                        targets.distinct().amap { driveUrl ->
                            emitDrivePage(driveUrl, res.episode, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(CskyNet.TAG, "movies4u post: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d(CskyNet.TAG, "movies4u: ${e.message}")
        }
    }
}
