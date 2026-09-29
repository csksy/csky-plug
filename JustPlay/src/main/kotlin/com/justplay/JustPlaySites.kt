package com.justplay

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

internal object DrivePages {
    // hosts that never carry a playable file: cloudflare walled mirrors, site
    // plumbing and ad jumps, everything else found on the drive pages is fair game
    private val junk = Regex(
        "gdflix|gdtot|filebee|filepress|gmpg\\.org|googleapis|googletagmanager|fonts\\.|schema\\.org|w3\\.org|" +
            "twitter\\.com|facebook\\.com|pinterest|whatsapp|telegram|t\\.me/|/tg/|catimages|tinyurl|bonuscaf|" +
            "winexch|a-ads|pixabay|i-poster|scene-source|vglist|vegamovies-apk|hdhub4u\\.download|hdhub4u\\.tv"
    )

    private fun isSelfLink(href: String, hosts: Set<String>): Boolean {
        return try {
            hosts.contains(URI(href).host?.lowercase())
        } catch (e: Exception) {
            false
        }
    }

    private fun episodeRegexes(episode: Int, season: Int?): List<Regex> {
        val s2 = (season ?: 1).toString().padStart(2, '0')
        return listOf(
            Regex("(?i)Episodes?\\s*:?\\s*0*$episode(?!\\d)"),
            Regex("(?i)\\b${s2}\\s*[xXeE]\\s*0*$episode(?!\\d)"),
            Regex("(?i)\\b${season ?: 1}\\s*[xX]\\s*$episode(?!\\d)")
        )
    }

    private fun headingLinks(doc: Document, regexes: List<Regex>): List<Pair<String, String>>? {
        val heads = doc.select("h2, h3, h4, h5").filter { el ->
            val text = el.text().replace('\u00A0', ' ')
            regexes.any { it.containsMatchIn(text) }
        }
        if (heads.isEmpty()) return null
        val links = mutableListOf<Pair<String, String>>()
        for (head in heads) {
            val label = head.text().replace(Regex("\\s+"), " ").trim()
            var sib = head.nextElementSibling()
            while (sib != null && sib.tagName() !in listOf("h2", "h3", "h4", "h5")) {
                sib.select("a[href]").forEach { a ->
                    links.add(a.attr("href").trim() to label)
                }
                sib = sib.nextElementSibling()
            }
        }
        return links.ifEmpty { null }
    }

    suspend fun emit(
        site: String,
        driveUrl: String,
        episode: Int?,
        season: Int? = null,
        label: String = "",
        siteDomain: String? = null,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // mobilejsr rest loops redirects for some visitors, nexdrive serves the same app
            val fetchUrl = driveUrl.replace("mobilejsr.rest", "nexdrive.fit")
            val res = app.get(fetchUrl, headers = PlayNet.headers(referer), timeout = 20000L)
            val doc = res.document
            val driveHost = try {
                URI(res.url).host?.lowercase()
            } catch (e: Exception) {
                null
            }
            val hosts = listOfNotNull(driveHost, siteDomain?.let {
                try {
                    URI(it).host?.lowercase()
                } catch (e: Exception) {
                    null
                }
            }).toSet()

            if (episode != null) {
                val links = headingLinks(doc, episodeRegexes(episode, season))
                if (links != null) {
                    val picked = links.mapNotNull { (href, headLabel) ->
                        if (href.startsWith("http") && !junk.containsMatchIn(href) && !isSelfLink(href, hosts)) {
                            href to headLabel
                        } else null
                    }.distinctBy { it.first }
                    coroutineScope {
                        picked.forEach { (href, headLabel) ->
                            async(Dispatchers.IO) {
                                val quality = listOf(headLabel, label).firstNotNullOfOrNull {
                                    PlayNet.getIndexQuality(it).takeIf { q -> q != Qualities.Unknown.value }
                                }
                                PlayNet.emitSiteLink(site, href, "Episode $episode", quality, fetchUrl, subtitleCallback, callback)
                            }
                        }
                    }
                    return
                }
                val markers = episodeRegexes(episode, season)
                val row = doc.select("a[href]").firstOrNull { a ->
                    val href = a.attr("href").trim()
                    href.startsWith("http") && !junk.containsMatchIn(href) &&
                        markers.any { it.containsMatchIn(a.text()) }
                }
                if (row != null) {
                    val href = row.attr("href").trim()
                    if (!isSelfLink(href, hosts)) {
                        PlayNet.emitSiteLink(site, href, "Episode $episode", null, fetchUrl, subtitleCallback, callback)
                        return
                    }
                }
                return
            }

            val external = doc.select("a[href]").mapNotNull { el ->
                val href = el.attr("href").trim()
                if (!href.startsWith("http")) null
                else if (junk.containsMatchIn(href) || isSelfLink(href, hosts)) null
                else href
            }.distinct()
            // movies4u hands over a bare drive url, the drive page title carries
            // the same quality and size info the other sites put in headings
            val fallbackTitle = if (label.isBlank()) {
                doc.title().substringBefore(" – ").substringBefore(" - ").trim()
            } else ""
            val effectiveLabel = label.ifBlank { fallbackTitle }
            val quality = if (effectiveLabel.isBlank()) null else PlayNet.getIndexQuality(effectiveLabel)
            val info = effectiveLabel
            coroutineScope {
                external.forEach { href ->
                    async(Dispatchers.IO) {
                        PlayNet.emitSiteLink(site, href, info, quality, fetchUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "$site drive page: ${e.message}")
        }
    }
}

internal object VegaMoviesSite {
    private const val DEFAULT_DOMAIN = "https://vegamovies.gallery"

    private fun headers(api: String): Map<String, String> = mapOf(
        "User-Agent" to PLAY_UA,
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

    // the search api happily answers with pets movies for a walter mitty query,
    // so only a real title, year and season match is allowed to resolve
    private fun pickDoc(docs: List<VegaDoc>, res: PlayLinkData): VegaDoc? {
        val title = res.title ?: return null
        docs.firstOrNull { res.imdbId != null && it.imdbId.equals(res.imdbId, ignoreCase = true) }?.let { return it }
        val matched = docs.filter { PlayNet.titleMatches(it.postTitle, title) }
        if (res.season != null) {
            val seasonPosts = matched.filter { PlayNet.seasonsOf(it.postTitle ?: "")?.contains(res.season) == true }
            if (seasonPosts.isNotEmpty()) {
                return seasonPosts.firstOrNull { PlayNet.yearMatches(it.postTitle ?: "", res.matchYear) } ?: seasonPosts.first()
            }
            return matched.firstOrNull { PlayNet.seasonsOf(it.postTitle ?: "") == null }
        }
        return matched.firstOrNull { PlayNet.yearMatches(it.postTitle ?: "", res.matchYear) }
            ?: matched.firstOrNull()
    }

    // every quality row sits between two headings, the download anchors below a
    // heading belong to the label of that heading
    private fun collectRows(doc: Document, season: Int?): List<Pair<String, String>> {
        val seasonRegex = season?.let { Regex("(?i)Season\\s*$it(?!\\d)|\\bS${it.toString().padStart(2, '0')}\\b") }
        val heads = doc.select("h3, h4, h5").filter { el ->
            val text = el.text().replace('\u00A0', ' ')
            seasonRegex == null || seasonRegex.containsMatchIn(text)
        }
        val targets = mutableListOf<Pair<String, String>>()
        for (head in heads) {
            val label = head.text().replace(Regex("\\s+"), " ").trim()
            var sib = head.nextElementSibling()
            while (sib != null && sib.tagName() !in listOf("h3", "h4", "h5")) {
                for (a in sib.select("a[href]")) {
                    val text = a.text()
                    val href = a.attr("href").trim()
                    if (!href.startsWith("http")) continue
                    if (text.contains("Batch", true) || text.contains("Zip", true)) continue
                    if (a.selectFirst("button.dwd-button") != null ||
                        text.contains("V-Cloud", true) || text.contains("G-Direct", true)
                    ) {
                        targets.add(href to label)
                    }
                }
                sib = sib.nextElementSibling()
            }
        }
        return targets.distinctBy { it.first }
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val api = FirebaseDomainHelper.getDomain("justplay_vegamovies")
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
            val target = pickDoc(docs, res) ?: return
            val permalink = target.permalink?.takeIf { it.isNotBlank() } ?: return
            val postUrl = if (permalink.startsWith("http")) permalink else api + permalink
            val doc = app.get(postUrl, headers = headers(api), timeout = 20000L).document

            val rows = collectRows(doc, res.season)
            if (res.season == null) {
                coroutineScope {
                    rows.forEach { (link, label) ->
                        async(Dispatchers.IO) {
                            DrivePages.emit(
                                "vegamovies", link, null, null, label, api, api,
                                subtitleCallback, callback
                            )
                        }
                    }
                }
            } else {
                rows.forEach { (link, label) ->
                    DrivePages.emit(
                        "vegamovies", link, res.episode, res.season, label, api, api,
                        subtitleCallback, callback
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "vegamovies: ${e.message}")
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
                headers = mapOf("User-Agent" to PLAY_UA, "Referer" to "$domain/"),
                timeout = 15000L
            ).text
            val hits = org.json.JSONObject(text).optJSONArray("hits") ?: return emptyList()
            (0 until hits.length()).mapNotNull { i ->
                val d = hits.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
                val postTitle = d.optString("post_title")
                val permalink = d.optString("permalink")
                if (postTitle.isBlank() || permalink.isBlank()) return@mapNotNull null
                val url2 = if (permalink.startsWith("http", true)) {
                    // the indexed permalinks point at dead mirrors, only the path is stable
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
        val target = if (url.contains("id=")) PlayNet.decryptIdLink(url, referer) ?: return else url
        if (target.isBlank()) return
        PlayNet.emitSiteLink("hdhub4u", target, label, PlayNet.getIndexQuality(label), referer, subtitleCallback, callback)
    }

    private suspend fun processPost(
        domain: String,
        postUrl: String,
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(postUrl, headers = PlayNet.headers("$domain/"), timeout = 20000L).document
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
                            PlayNet.emitSiteLink("hdhub4u", watchHref, "Episode $epNum Watch", null, domain, subtitleCallback, callback)
                        }
                    }

                    val href = epLink.absUrl("href").ifBlank { epLink.attr("href") }
                    if (!href.startsWith("http")) continue
                    emitResolved(href, "Episode $epNum", domain, subtitleCallback, callback)
                }
            } else {
                for (el in doc.select("h3 a:matches((?i)480|720|1080|2160|4K), h4 a:matches((?i)480|720|1080|2160|4K)")) {
                    val href = el.absUrl("href").ifBlank { el.attr("href") }
                    if (!href.startsWith("http")) continue
                    emitResolved(href, el.text().trim(), domain, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "hdhub4u post: ${e.message}")
        }
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = FirebaseDomainHelper.getDomain("justplay_hdhub4u")
                ?: FirebaseDomainHelper.getDomain("hdhub4u")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return
            val posts = typesenseSearch(domain, title)
            if (posts.isEmpty()) return

            val titleMatched = posts.filter { (postTitle, _) ->
                PlayNet.titleMatches(postTitle, title)
            }
            // a season request must never land on a post that only carries a
            // different season, and posts without any season tag come last
            val chosen = if (res.season != null) {
                val seasonPosts = titleMatched.filter { (postTitle, _) ->
                    PlayNet.seasonsOf(postTitle)?.contains(res.season) == true
                }
                if (seasonPosts.isNotEmpty()) seasonPosts
                else titleMatched.filter { (postTitle, _) -> PlayNet.seasonsOf(postTitle) == null }
            } else {
                titleMatched.filter { (postTitle, _) -> PlayNet.yearMatches(postTitle, res.matchYear) }
                    .ifEmpty { titleMatched }
            }.take(2)
            coroutineScope {
                chosen.forEach { (_, postUrl) ->
                    async(Dispatchers.IO) {
                        processPost(domain, postUrl, res, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "hdhub4u: ${e.message}")
        }
    }
}

internal object FourKhdHubSite {
    private const val DEFAULT_DOMAIN = "https://4khdhub.one"

    private fun seasonOf(text: String): Int? =
        Regex("""\bS(\d{1,2})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?i)Season\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun emitLinks(
        hrefs: List<String>,
        label: String,
        domain: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // a movie page carries a dozen encoded links, a burst that big gets
        // throttled by the linker so keep it at six in flight
        val semaphore = Semaphore(6)
        coroutineScope {
            hrefs.distinct().forEach { href ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (!href.startsWith("http")) return@withPermit
                        val target = if (href.contains("id=")) PlayNet.decryptIdLink(href, domain) ?: return@withPermit else href
                        if (target.isNotBlank()) {
                            PlayNet.emitSiteLink(
                                "4khdhub", target, label, PlayNet.getIndexQuality(label),
                                domain, subtitleCallback, callback
                            )
                        }
                    }
                }
            }
        }
    }

    private fun blockLabel(el: Element): String {
        val fileTitle = el.selectFirst(".episode-file-title")?.text()?.trim().orEmpty()
        if (fileTitle.isNotBlank()) return fileTitle
        val movieTitle = el.selectFirst("div.file-title")?.text()?.trim().orEmpty()
        if (movieTitle.isNotBlank()) return movieTitle
        val header = el.selectFirst("div.download-header, div.episode-header")?.text()?.trim().orEmpty()
        if (header.isNotBlank()) return header.replace(Regex("\\s+"), " ")
        return ""
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = FirebaseDomainHelper.getDomain("justplay_4khdhub")
                ?: FirebaseDomainHelper.getDomain("4khdhub")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return

            val searchDoc = app.get(
                "$domain/?s=${Uri.encode(title)}",
                headers = PlayNet.headers(),
                timeout = 20000L
            ).document
            val elements = searchDoc.select("div.card-grid > a.movie-card")
            fun contentOf(el: Element): String = el.selectFirst("div.movie-card-content")?.text()?.lowercase() ?: ""

            val matched = elements.firstOrNull { el ->
                val content = contentOf(el)
                PlayNet.titleMatches(content, title) && PlayNet.yearMatches(content, res.year)
            } ?: elements.firstOrNull { el ->
                PlayNet.titleMatches(contentOf(el), title)
            } ?: return

            val href = matched.attr("href").trim()
            val detailUrl = if (href.startsWith("http")) href else domain + href
            val doc = app.get(detailUrl, headers = PlayNet.headers(domain), timeout = 20000L).document

            if (res.season != null) {
                val seasonText = "S" + res.season.toString().padStart(2, '0')
                val epRegex = if (res.episode != null) {
                    Regex("(?i)${seasonText}\\s*[xXeE]\\s*0*${res.episode}(?!\\d)")
                } else {
                    Regex("(?i)\\b$seasonText(?!\\d)")
                }
                val items = doc.select("div.episode-download-item").filter { el ->
                    epRegex.containsMatchIn(el.text().replace('\u00A0', ' '))
                }
                val hrefs = items.flatMap { el ->
                    el.select("div.episode-links > a, a[href]").map { it.attr("href").trim() }
                }
                val labels = items.map { blockLabel(it) }
                val label = labels.firstOrNull { it.isNotBlank() }?.let { l ->
                    res.episode?.let { "S${res.season}E$it - $l" } ?: l
                } ?: "S${res.season}${res.episode?.let { "E$it" } ?: ""}"
                emitLinks(hrefs, label, domain, subtitleCallback, callback)
            } else {
                val items = doc.select("div.download-item")
                if (items.isNotEmpty()) {
                    items.forEach { el ->
                        val hrefs = el.select("a[href]").map { it.attr("href").trim() }
                        val label = blockLabel(el).ifBlank { doc.title() }
                        emitLinks(hrefs, label, domain, subtitleCallback, callback)
                    }
                } else {
                    val hrefs = doc.select("div.download-item a[href], a[href*=id=]").map { it.attr("href").trim() }
                    emitLinks(hrefs, doc.title(), domain, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "4khdhub: ${e.message}")
        }
    }
}

internal object Movies4uSite {
    private const val DEFAULT_DOMAIN = "https://movies4u.cr"
    private val driveHostRegex = Regex("mdrive\\.cloud|nexdrive\\.fit|mobilejsr\\.rest|mdisk")

    private suspend fun emitDrivePage(
        driveUrl: String,
        episode: Int?,
        season: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        DrivePages.emit(
            "movies4u", driveUrl, episode, season, "", null, null,
            subtitleCallback, callback
        )
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = FirebaseDomainHelper.getDomain("justplay_movies4u")
                ?: FirebaseDomainHelper.getDomain("movies4u")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return

            val searchDoc = app.get(
                "$domain/?s=${Uri.encode(title)}",
                headers = PlayNet.headers(),
                timeout = 20000L
            ).document
            val anchors = searchDoc.select("article h2 a, article h3 a, h2.entry-title a, h3.entry-title a")
                .mapNotNull { el ->
                    val t = el.text().trim()
                    val href = el.attr("href").trim()
                    if (t.isNotBlank() && href.startsWith("http")) t to href else null
                }
                .filter { (t, _) -> PlayNet.titleMatches(t, title) }

            val filtered = if (res.season != null) {
                val seasonPosts = anchors.filter { (t, _) ->
                    PlayNet.seasonsOf(t)?.contains(res.season) == true
                }
                if (seasonPosts.isNotEmpty()) seasonPosts
                else anchors.filter { (t, _) -> PlayNet.seasonsOf(t) == null }
            } else {
                anchors.filter { (t, _) -> PlayNet.yearMatches(t, res.matchYear) }.ifEmpty { anchors }
            }
            val chosen = filtered.take(2)
            if (chosen.isEmpty()) return

            chosen.forEach { (_, postUrl) ->
                try {
                    val doc = app.get(postUrl, headers = PlayNet.headers(domain), timeout = 20000L).document
                    if (res.season == null) {
                        val drives = doc.select("a[href]").mapNotNull { el ->
                            val text = el.text()
                            val href = el.attr("href").trim()
                            if (!href.startsWith("http")) null
                            else if (text.contains("Batch", true) || text.contains("Zip", true)) null
                            else if (driveHostRegex.containsMatchIn(href)) href
                            else null
                        }.distinct()
                        if (drives.isEmpty()) {
                            doc.select("a:has(button)").mapNotNull { el ->
                                val href = el.attr("href").trim()
                                if (href.startsWith("http") && driveHostRegex.containsMatchIn(href)) href else null
                            }.distinct().forEach { driveUrl ->
                                emitDrivePage(driveUrl, null, null, subtitleCallback, callback)
                            }
                        } else {
                            coroutineScope {
                                drives.forEach { driveUrl ->
                                    async(Dispatchers.IO) {
                                        emitDrivePage(driveUrl, null, null, subtitleCallback, callback)
                                    }
                                }
                            }
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
                        targets.distinct().forEach { driveUrl ->
                            emitDrivePage(driveUrl, res.episode, res.season, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(PlayNet.TAG, "movies4u post: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "movies4u: ${e.message}")
        }
    }
}

internal object TmfSite {
    private const val DEFAULT_DOMAIN = "https://themoviesflixhq.com"

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = FirebaseDomainHelper.getDomain("justplay_themoviesflix")
                ?: FirebaseDomainHelper.getDomain("themoviesflix")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return

            val searchDoc = app.get(
                "$domain/?s=${Uri.encode(title)}",
                headers = PlayNet.headers(),
                timeout = 20000L
            ).document
            val anchors = searchDoc.select("article.latestpost a[id=featured-thumbnail]")
                .mapNotNull { el ->
                    val t = el.attr("title").trim().ifBlank { el.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                    val href = el.attr("href").trim()
                    if (t.isNotBlank() && href.startsWith("http")) t to href else null
                }
                .filter { (t, _) -> PlayNet.titleMatches(t, title) }
            val target = anchors.firstOrNull { (t, _) ->
                if (res.season != null) PlayNet.seasonsOf(t)?.contains(res.season) == true
                else PlayNet.yearMatches(t, res.matchYear)
            } ?: anchors.firstOrNull() ?: return

            val postUrl = target.second
            val doc = app.get(postUrl, headers = PlayNet.headers(domain), timeout = 20000L).document
            val groups = doc.select("div.mfx-download-group")

            if (res.season == null) {
                val driveLinks = groups.flatMap { group ->
                    val qualityTitle = group.selectFirst("h3")?.text()
                        ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                    group.select("a.mfx-download-link, a[href]").mapNotNull { el ->
                        val text = el.text()
                        val href = el.attr("href").trim()
                        if (!href.startsWith("http")) null
                        else if (text.contains("Batch", true) || text.contains("Zip", true)) null
                        else href to qualityTitle
                    }
                }.distinctBy { it.first }
                coroutineScope {
                    driveLinks.forEach { (link, qualityTitle) ->
                        async(Dispatchers.IO) {
                            DrivePages.emit(
                                "themoviesflix", link, null, null, qualityTitle, domain, domain,
                                subtitleCallback, callback
                            )
                        }
                    }
                }
            } else {
                val seasonRegex = Regex("(?i)Season\\s*${res.season}(?!\\d)")
                val seasonGroups = groups.filter { group ->
                    val h3 = group.selectFirst("h3")?.text()?.replace('\u00A0', ' ').orEmpty()
                    seasonRegex.containsMatchIn(h3) || PlayNet.seasonsOf(h3)?.contains(res.season) == true
                }
                val driveLinks = seasonGroups.flatMap { group ->
                    val qualityTitle = group.selectFirst("h3")?.text()
                        ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                    group.select("a.mfx-download-link, a[href]").mapNotNull { el ->
                        val text = el.text()
                        val href = el.attr("href").trim()
                        if (!href.startsWith("http")) null
                        else if (text.contains("Batch", true) || text.contains("Zip", true)) null
                        else href to qualityTitle
                    }
                }.distinctBy { it.first }
                driveLinks.forEach { (link, qualityTitle) ->
                    DrivePages.emit(
                        "themoviesflix", link, res.episode, res.season, qualityTitle, domain, domain,
                        subtitleCallback, callback
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "themoviesflix: ${e.message}")
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

    private fun optionsOf(doc: Document): List<PlayerOption> {
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
                    "User-Agent" to PLAY_UA,
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
            PlayNet.deEsc(org.json.JSONObject(text).optString("embed_url"))
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
            host.contains("modiplay") -> PlayModiplay.resolve(embedUrl, label, subtitleCallback, callback)
            host.contains("iqsmartgames") || host.contains("filesforever") ->
                PlayGdmirror.resolve(embedUrl, label, subtitleCallback, callback)
            else -> {
                val handled = PlayPacker.resolvePackedEmbed(embedUrl, label, "https://multimovies.casa/", callback)
                if (!handled) {
                    try {
                        val res = app.get(embedUrl, headers = PlayNet.headers("https://multimovies.casa/"), timeout = 20000L)
                        val text = res.text
                        val unpacked = if (text.contains("eval(function(p,a,c,k,e,d)")) {
                            runCatching { getAndUnpack(text) }.getOrNull() ?: text
                        } else text
                        for (m in Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""").findAll(unpacked)) {
                            PlayPacker.emitM3u8(m.groupValues[1], PlayNet.getBaseUrl(res.url), label, callback)
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
            app.get(pageUrl, headers = PlayNet.headers(domain), timeout = 20000L).document
        } catch (e: Exception) {
            return
        }
        val options = optionsOf(doc)
        if (options.isEmpty()) return
        coroutineScope {
            options.forEach { option ->
                async(Dispatchers.IO) {
                    val embed = embedOf(domain, option, pageUrl) ?: return@async
                    resolveEmbed(embed, option.label, subtitleCallback, callback)
                }
            }
        }
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = FirebaseDomainHelper.getDomain("justplay_multimovies")
                ?: FirebaseDomainHelper.getDomain("multimovies")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return
            val slug = PlayNet.slugify(title)

            val direct = if (res.season != null && res.episode != null) {
                "$domain/episodes/$slug-${res.season}x${res.episode}"
            } else if (res.season == null) {
                "$domain/movies/$slug"
            } else {
                null
            }

            if (direct != null) {
                try {
                    val probe = app.get(direct, headers = PlayNet.headers(domain), timeout = 15000L)
                    if (probe.code == 200 && probe.text.contains("dooplay_player_option")) {
                        processPage(domain, direct, subtitleCallback, callback)
                        return
                    }
                } catch (e: Exception) {
                }
            }

            val searchDoc = app.get(
                "$domain/?s=${Uri.encode(title)}",
                headers = PlayNet.headers(domain),
                timeout = 20000L
            ).document
            val candidates = searchDoc.select("article a[href], .result-item a[href], .items a[href]")
                .mapNotNull { el ->
                    val href = el.attr("href").trim()
                    if (!href.startsWith("http")) {
                        null
                    } else {
                        val text = el.text().trim()
                        if (PlayNet.titleMatches(text, title)) href else null
                    }
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
                val showDoc = app.get(showUrl, headers = PlayNet.headers(domain), timeout = 20000L).document
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
                } else if (res.episode != null && direct != null) {
                    try {
                        val probe = app.get(direct, headers = PlayNet.headers(domain), timeout = 15000L)
                        if (probe.code == 200 && probe.text.contains("dooplay_player_option")) {
                            processPage(domain, direct, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(PlayNet.TAG, "multimovies: ${e.message}")
        }
    }
}
