package com.laddu100.rareanimes

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

@JsonIgnoreProperties(ignoreUnknown = true)
data class RAIVariant(
    @JsonProperty("n") val n: String,
    @JsonProperty("u") val u: String,
    @JsonProperty("k") val k: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RAIEpisodeData(
    @JsonProperty("v") val v: List<RAIVariant> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JuicyDataInner(
    @JsonProperty("token") val token: String? = null,
    @JsonProperty("routes") val routes: JuicyRoutes? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JuicyRoutes(
    @JsonProperty("links") val links: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JuicyDataWrapper(
    @JsonProperty("data") val data: JuicyDataInner? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgonQuality(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("link") val link: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgonLinks(
    @JsonProperty("qualities") val qualities: List<ArgonQuality>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamBetaLink(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RelatedEpisode(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("ep_name") val epName: String? = null,
    @JsonProperty("s") val s: Int? = null,
    @JsonProperty("e") val e: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RelatedSeason(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("episodes") val episodes: List<RelatedEpisode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoFileAccountData(
    @JsonProperty("token") val token: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoFileAccount(
    @JsonProperty("data") val data: GoFileAccountData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoFileChild(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("link") val link: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoFileContentData(
    @JsonProperty("children") val children: Map<String, GoFileChild>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoFileContent(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("data") val data: GoFileContentData? = null
)

class RareAnimesProvider : MainAPI() {
    override var mainUrl = "https://www.rareanimes.mov"
    override var name = "Rare Toons India"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Cartoon, TvType.TvSeries, TvType.Movie)

    private val TAG = "RareAnimes"
    private val SOURCE = "Rare Toons India"
    private val EPISODE_HEADER = Regex("""^(?:Episode|EP)\s*[.\-]?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val GOFILE_WT_SECRET = "12af056dacea0b"

    override val mainPage = mainPageOf(
        "hindi-dub" to "Hindi Dub",
        "cartoon-network" to "Cartoon Network",
        "disney-xd" to "Disney XD",
        "hungama" to "Hungama",
        "marvel-hq" to "Marvel HQ",
        "pokemon" to "Pokemon"
    )

    private data class PageEntry(val title: String, val url: String, val poster: String?)

    private fun parseListings(html: String): List<PageEntry> {
        val doc = Jsoup.parse(html)
        return doc.select("article").mapNotNull { art ->
            val link = art.selectFirst("h2.entry-title a, .entry-title a")?.attr("abs:href")
            if (link.isNullOrBlank() || !link.contains(mainUrl)) return@mapNotNull null
            val title = art.selectFirst("h2.entry-title, .entry-title")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = art.selectFirst("img")?.let { img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }
                when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("http") -> src
                    else -> null
                }
            }
            PageEntry(title, link, poster)
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page <= 1) "$mainUrl/hindi/category/${request.data}/"
            else "$mainUrl/hindi/category/${request.data}/page/$page/"
            val response = raiGet(url)
            val entries = parseListings(response.text)
            val items = entries.map {
                newMovieSearchResponse(it.title, it.url, TvType.Anime) {
                    this.posterUrl = it.poster
                }
            }
            newHomePageResponse(request.name, items, hasNext = entries.isNotEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val results = mutableListOf<PageEntry>()
            for (pageNum in 1..3) {
                val url = if (pageNum == 1) "$mainUrl/?s=$encoded"
                else "$mainUrl/page/$pageNum/?s=$encoded"
                val response = raiGet(url)
                val entries = parseListings(response.text)
                if (entries.isEmpty()) break
                results.addAll(entries)
            }
            results.distinctBy { it.url }.map {
                newMovieSearchResponse(it.title, it.url, TvType.Anime) {
                    this.posterUrl = it.poster
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    private data class SourceRef(val label: String, val url: String, val kind: String, val contextKey: String)

    private data class ArchiveEpisode(
        val name: String,
        val variants: List<RAIVariant>,
        val s: Int? = null,
        val e: Int? = null
    )

    private data class ArchiveResult(
        val dubKey: String,
        val sourceName: String,
        val episodes: List<ArchiveEpisode>
    )

    private data class DirectEpisode(
        val season: Int,
        val epNum: Int,
        val name: String,
        val variants: MutableList<RAIVariant>
    )

    private data class DirectParse(
        val episodes: List<DirectEpisode>,
        val orphans: List<RAIVariant>
    )

    private fun dubKeyOf(text: String): String {
        val t = " $text ".lowercase()
        val isCn = t.contains("cartoon network") || t.contains("caroon network") ||
            t.contains("cn dub") || Regex("""[\s(\[-]cn[)\],\s–-]""").containsMatchIn(t)
        val isXd = t.contains("hungama") || t.contains("disney xd") || t.contains("marvel hq")
        if (isCn && isXd) return ""
        if (isCn) return "cn"
        if (isXd) return "xd"
        val langs = listOf(
            "tamil" to "ta", "telugu" to "te", "english" to "en",
            "bengali" to "be", "hindi" to "hi"
        ).filter { t.contains(it.first) }.map { it.second }.distinct()
        return when (langs.size) {
            1 -> langs.first()
            0 -> ""
            else -> "multi"
        }
    }

    private fun dubLabel(key: String): String {
        return when (key) {
            "cn" -> "Hindi (Cartoon Network)"
            "xd" -> "Hindi (Hungama XD)"
            "hi" -> "Hindi"
            "ta" -> "Tamil"
            "te" -> "Telugu"
            "en" -> "English"
            "be" -> "Bengali"
            "multi" -> "Multi Audio"
            else -> "Main"
        }
    }

    private fun extractSources(doc: Document): List<SourceRef> {
        val content = doc.selectFirst("div.entry-content") ?: return emptyList()
        val sources = mutableListOf<SourceRef>()
        var context = ""
        val all: List<Element> = content.allElements
        for (el in all) {
            when (el.tagName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val t = el.text().trim()
                    if (t.isNotBlank()) context = t
                }
                "a" -> {
                    val href = el.attr("abs:href")
                    val text = el.text().trim()
                    when {
                        href.contains("$STORE_HOST/archives/") ->
                            sources.add(SourceRef(text.ifBlank { context }, href, "archive", dubKeyOf(context)))
                        href.contains("$CODEDEW_HOST/zipper/") ->
                            sources.add(SourceRef(text.ifBlank { context }, href, "zipper", dubKeyOf(context)))
                        href.contains("$CODEDEW_HOST/zipcloud/") ->
                            sources.add(SourceRef(text.ifBlank { context }, href, "zipcloud", dubKeyOf(context)))
                    }
                }
            }
        }
        return sources.distinctBy { it.url }
    }

    private fun archiveSourceName(title: String, fallback: String): String {
        val t = title.lowercase()
        return when {
            t.contains("multiquality") -> "WatchMultiQuality"
            t.contains("hubcloud") -> "HubCloud"
            t.contains("watchnow") -> "WatchNow"
            else -> fallback.ifBlank { "Watch" }
        }
    }

    private fun parseArchiveEpisodes(html: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(html)
        val content = doc.selectFirst("div.entry-content") ?: doc.body() ?: return emptyList()
        return content.select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href")
            val text = a.text().trim()
            val isCodedew = href.contains("$CODEDEW_HOST/zipper/") ||
                    href.contains("$CODEDEW_HOST/zipcloud/")
            if (isCodedew && text.isNotBlank()) text to href else null
        }.distinctBy { it.second }
    }

    private fun languageFromText(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("telugu") -> "Telugu"
            t.contains("tamil") -> "Tamil"
            t.contains("bengali") -> "Bengali"
            t.contains("english") || t.contains(" eng") -> "English"
            t.contains("hindi") -> "Hindi"
            else -> ""
        }
    }

    private fun parseDirectEpisodes(
        doc: Document,
        defaultSeason: Int
    ): DirectParse {
        val content = doc.selectFirst("div.entry-content") ?: return DirectParse(emptyList(), emptyList())
        val episodes = mutableListOf<DirectEpisode>()
        val orphans = mutableListOf<RAIVariant>()
        var current: DirectEpisode? = null
        var sectionContext = ""
        var sectionKey = ""
        val usedOrphanNames = mutableSetOf<String>()
        for (el in content.children()) {
            val tag = el.tagName().lowercase()
            if (tag == "hr") continue
            val text = el.text().trim()
            if (text.isBlank()) continue
            val links = el.select(
                "a[href*=codedew.com/zipper/], a[href*=codedew.com/zipcloud/]"
            )

            val headerMatch = EPISODE_HEADER.find(text)
            var startedEpisode = false
            if (headerMatch != null) {
                val epNum = headerMatch.groupValues[1].toIntOrNull()
                if (epNum != null) {
                    current = DirectEpisode(defaultSeason, epNum, text, mutableListOf())
                    episodes.add(current)
                    startedEpisode = true
                    if (links.isEmpty()) continue
                }
            }

            if (links.isEmpty()) {
                if (tag.startsWith("h") && el.select("a").isEmpty()) {
                    sectionContext = text
                    sectionKey = dubKeyOf(text)
                    if (!startedEpisode) current = null
                }
                continue
            }

            val langSpan = el.selectFirst("span:not(.ra-serv-txt)")?.text()
            val lang = languageFromText(langSpan ?: text.substringBefore("["))
            for (a in links) {
                val href = a.attr("abs:href")
                if (href.isBlank()) continue
                val label = a.text().trim()
                if (label.isBlank()) continue
                val target = current
                if (target != null) {
                    val variantName = if (lang.isBlank()) label else "$lang $label"
                    target.variants.add(RAIVariant(variantName, href, dubKeyOf(lang.ifBlank { sectionContext })))
                } else {
                    val effectiveLang = if (lang.isBlank()) languageFromText(sectionContext) else lang
                    val base = if (effectiveLang.isBlank()) label else "$effectiveLang $label"
                    val variantName = if (usedOrphanNames.add(base)) base
                    else if (sectionContext.isBlank()) base
                    else "$sectionContext $base"
                    orphans.add(RAIVariant(variantName, href, sectionKey))
                }
            }
        }
        return DirectParse(episodes, orphans)
    }

    private fun parseRelatedData(html: String): Map<String, RelatedSeason> {
        return try {
            val m = Regex("""const\s+relatedData\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                .find(html) ?: return emptyMap()
            val parsed = parseJson<Map<String, RelatedSeason>>(m.groupValues[1])
            parsed.filterValues { it.episodes.orEmpty().isNotEmpty() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private suspend fun loadArchive(
        url: String,
        sourceLabel: String,
        contextKey: String,
        depth: Int
    ): ArchiveResult? {
        if (depth > 2) return null
        return try {
            val response = raiGet(url)
            val html = response.text
            val doc = Jsoup.parse(html)
            val docTitle = doc.title()
            val dubKey = dubKeyOf(docTitle).ifBlank { contextKey }
            val sourceName = archiveSourceName(docTitle, sourceLabel)
            val eps = parseArchiveEpisodes(html)

            if (sourceName == "WatchMultiQuality" && eps.isNotEmpty()) {
                val first = eps.first()
                val related = try {
                    when (val t = CodedewResolver.resolveUrl(first.second)) {
                        is ResolvedTarget.Argon -> {
                            val mq = raiGet("https://$CODEDEW_HOST/multiquality/?url=${t.code}")
                            parseRelatedData(mq.text)
                        }
                        else -> emptyMap()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "mq probe: ${e.message}")
                    emptyMap()
                }
                val relatedEps = related.values.flatMap { season ->
                    season.episodes.orEmpty().mapNotNull { ep ->
                        val id = ep.id ?: return@mapNotNull null
                        ArchiveEpisode(
                            name = ep.epName?.takeIf { it.isNotBlank() } ?: id,
                            variants = listOf(
                                RAIVariant("WatchMultiQuality", "https://$ARGON_HOST/embed/$id", dubKey),
                                RAIVariant("DLBeta", "https://$ARGON_HOST/downlead/$id/", dubKey)
                            ),
                            s = ep.s,
                            e = ep.e
                        )
                    }
                }
                if (relatedEps.isNotEmpty()) {
                    return ArchiveResult(dubKey, sourceName, relatedEps)
                }
            }

            when {
                eps.size > 1 -> ArchiveResult(
                    dubKey,
                    sourceName,
                    eps.map { (name, u) -> ArchiveEpisode(name, listOf(RAIVariant(sourceName, u, dubKey))) }
                )
                eps.size == 1 -> {
                    val single = eps.first()
                    val target = try {
                        CodedewResolver.resolveUrl(single.second)
                    } catch (e: Exception) {
                        Log.e(TAG, "single chase: ${e.message}")
                        null
                    }
                    if (target is ResolvedTarget.Archive) {
                        loadArchive(target.url, sourceLabel, dubKey, depth + 1)
                    } else {
                        ArchiveResult(
                            dubKey,
                            sourceName,
                            listOf(ArchiveEpisode(single.first, listOf(RAIVariant(sourceName, single.second, dubKey))))
                        )
                    }
                }
                else -> {
                    val codedew = doc.selectFirst(
                        "div.entry-content a[href*=codedew.com]"
                    )?.attr("abs:href") ?: return null
                    val target = try {
                        CodedewResolver.resolveUrl(codedew)
                    } catch (e: Exception) {
                        Log.e(TAG, "archive chase: ${e.message}")
                        null
                    }
                    when (target) {
                        is ResolvedTarget.Archive -> loadArchive(target.url, sourceLabel, dubKey, depth + 1)
                        is ResolvedTarget.Argon ->
                            ArchiveResult(dubKey, sourceName, listOf(ArchiveEpisode("Full", listOf(RAIVariant(sourceName, codedew, dubKey)))))
                        is ResolvedTarget.ArgonDownload ->
                            ArchiveResult(dubKey, sourceName, listOf(ArchiveEpisode("Full", listOf(RAIVariant(sourceName, codedew, dubKey)))))
                        is ResolvedTarget.HubCloud ->
                            ArchiveResult(dubKey, sourceName, listOf(ArchiveEpisode("Full", listOf(RAIVariant(sourceName, codedew, dubKey)))))
                        is ResolvedTarget.PixelDrain ->
                            ArchiveResult(dubKey, sourceName, listOf(ArchiveEpisode("Full", listOf(RAIVariant(sourceName, codedew, dubKey)))))
                        else -> null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadArchive: ${e.message}")
            null
        }
    }

    private fun parseEpisodeNumber(name: String): Pair<Int?, Int>? {
        val m = Regex("""S\s*(\d{1,2})\s*E\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(name)
        if (m != null) {
            val s = m.groupValues[1].toIntOrNull() ?: return null
            val e = m.groupValues[2].toIntOrNull() ?: return null
            return s to e
        }
        val e2 = Regex("""\bE\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(name)
            ?: Regex("""\bEpisode\s+(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(name)
        if (e2 != null) {
            val e = e2.groupValues[1].toIntOrNull() ?: return null
            return null to e
        }
        return null
    }

    private fun jsonEscape(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\t", " ")
    }

    private fun variantsJson(variants: List<RAIVariant>): String {
        return """{"v":[""" + variants.joinToString(",") {
            """{"n":"${jsonEscape(it.n)}","u":"${jsonEscape(it.u)}","k":"${jsonEscape(it.k)}"}"""
        } + """]}"""
    }

    private fun extractMeta(doc: Document): Map<String, String> {
        val content = doc.selectFirst("div.entry-content")?.text() ?: ""
        val map = mutableMapOf<String, String>()
        Regex("""Season\s*No[.:]?\s*(\d{1,2})""", RegexOption.IGNORE_CASE).find(content)?.let {
            map["season"] = it.groupValues[1]
        }
        Regex("""Release\s*Year[.:]?\s*(\d{4})""", RegexOption.IGNORE_CASE).find(content)?.let {
            map["year"] = it.groupValues[1]
        }
        Regex("""Episodes[.:]?\s*(\d{1,4})""", RegexOption.IGNORE_CASE).find(content)?.let {
            map["episodes"] = it.groupValues[1]
        }
        return map
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val response = raiGet(url)
            val doc = Jsoup.parse(response.text)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".herald-post-thumbnail img, img.wp-post-image")?.attr("abs:src")

            val meta = extractMeta(doc)
            val year = meta["year"]?.toIntOrNull()
            val defaultSeason = meta["season"]?.toIntOrNull() ?: 1

            val plot = doc.select("div.entry-content p").map { it.text().trim() }
                .firstOrNull {
                    it.startsWith("Synopsis", true) || it.startsWith("Storyline", true) ||
                            it.startsWith("Story:", true)
                }
                ?.substringAfter(":")?.trim()
                ?: doc.select("div.entry-content p").map { it.text().trim() }
                    .filter { it.length > 120 }
                    .maxByOrNull { it.length }

            val genres = doc.select(".meta-category a, .herald-meta a[href*=category]").map {
                it.text().trim()
            }.filter { it.isNotBlank() }.distinct().take(8)

            val sources = extractSources(doc)
            val direct = parseDirectEpisodes(doc, defaultSeason)

            val archiveResults = mutableListOf<ArchiveResult>()
            for (src in sources.filter { it.kind == "archive" }) {
                loadArchive(src.url, src.label, src.contextKey, 0)?.let { archiveResults.add(it) }
            }

            data class EpEntry(
                val seasonIdx: Int,
                val realSeason: Int,
                val epNum: Int,
                val name: String,
                val variants: MutableList<RAIVariant>
            )

            val dubOrder = mutableListOf<String>()
            fun orderKey(k: String) {
                if (k.isNotBlank() && !dubOrder.contains(k)) dubOrder.add(k)
            }
            archiveResults.forEach { orderKey(it.dubKey) }
            direct.episodes.forEach { ep -> ep.variants.forEach { orderKey(it.k) } }
            direct.orphans.forEach { orderKey(it.k) }
            val splitDubs = dubOrder.size >= 2

            val epMap = LinkedHashMap<String, EpEntry>()
            val usedVariantNames = mutableSetOf<String>()
            var anyNumbered = false

            fun addVariant(seasonIdx: Int, season: Int, epNum: Int, name: String, variant: RAIVariant) {
                val key = "$seasonIdx:$season:$epNum"
                val entry = epMap.getOrPut(key) { EpEntry(seasonIdx, season, epNum, name, mutableListOf()) }
                if (entry.variants.none { it.u == variant.u }) entry.variants.add(variant)
            }

            for (ep in direct.episodes) {
                anyNumbered = true
                val keys = ep.variants.map { it.k }.filter { it.isNotBlank() }.distinct()
                val groups = if (keys.isEmpty()) listOf("") else keys
                for (g in groups) {
                    val idx = if (splitDubs) dubOrder.indexOf(g).let { if (it >= 0) it else 0 } else 0
                    ep.variants.filter { it.k == g || (g.isEmpty() && it.k.isBlank()) }.forEach { v ->
                        addVariant(idx, ep.season, ep.epNum, ep.name, v)
                    }
                }
            }

            for (archive in archiveResults) {
                var vName = archive.sourceName
                if (!usedVariantNames.add("${archive.dubKey}:$vName")) {
                    var i = 2
                    while (!usedVariantNames.add("${archive.dubKey}:$vName $i")) i++
                    vName = "$vName $i"
                }
                archive.episodes.forEachIndexed { idx, ep ->
                    val parsed = parseEpisodeNumber(ep.name)
                    if (parsed != null || ep.e != null) anyNumbered = true
                    val season = ep.s ?: parsed?.first ?: defaultSeason
                    val epNum = ep.e ?: parsed?.second ?: (idx + 1)
                    val idx0 = if (splitDubs) dubOrder.indexOf(archive.dubKey).let { if (it >= 0) it else 0 } else 0
                    ep.variants.forEach { v ->
                        addVariant(idx0, season, epNum, ep.name, RAIVariant(vName, v.u, archive.dubKey))
                    }
                }
            }

            val isMovie = !anyNumbered

            if (isMovie) {
                val variants = mutableListOf<RAIVariant>()
                for (archive in archiveResults) {
                    archive.episodes.forEach { ep ->
                        ep.variants.forEach { v ->
                            if (variants.none { it.u == v.u }) {
                                val label = if (archive.episodes.size > 1) "${v.n} ${ep.name}" else v.n
                                variants.add(RAIVariant(label, v.u, v.k))
                            }
                        }
                    }
                }
                direct.orphans.forEach { v ->
                    if (variants.none { it.u == v.u }) variants.add(v)
                }
                sources.filter { it.kind != "archive" }.forEach { src ->
                    if (variants.none { it.u == src.url }) {
                        variants.add(RAIVariant(src.label, src.url, src.contextKey))
                    }
                }
                if (variants.isEmpty()) return null
                newMovieLoadResponse(title, url, TvType.Movie, variantsJson(variants)) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = genres
                }
            } else {
                val zipVariants = direct.orphans.filter { v ->
                    epMap.values.none { entry -> entry.variants.any { it.u == v.u } }
                }
                if (zipVariants.isNotEmpty()) {
                    val keys = zipVariants.map { it.k }.filter { it.isNotBlank() }.distinct()
                    val groups = if (keys.isEmpty()) listOf("") else keys
                    for (g in groups) {
                        val groupVariants = zipVariants.filter { it.k == g || (g.isEmpty() && it.k.isBlank()) }
                        if (groupVariants.isEmpty()) continue
                        val zipIdx = if (splitDubs) dubOrder.indexOf(g).let { if (it >= 0) it else 0 } else 0
                        val maxEntry = epMap.values.filter { it.seasonIdx == zipIdx }.maxByOrNull { it.epNum }
                        val zipReal = if (splitDubs) defaultSeason else (maxEntry?.realSeason ?: defaultSeason)
                        val zipNum = (maxEntry?.epNum ?: 0) + 1
                        val key = "$zipIdx:$zipReal:$zipNum:zip"
                        epMap[key] = EpEntry(zipIdx, zipReal, zipNum, "ZIP Batch (Full Season)", groupVariants.toMutableList())
                    }
                }

                val episodes = epMap.values.sortedWith(
                    compareBy({ it.seasonIdx }, { it.realSeason }, { it.epNum })
                ).map { e ->
                    newEpisode(variantsJson(e.variants)) {
                        this.season = if (splitDubs) e.seasonIdx + 1 else e.realSeason
                        this.episode = e.epNum
                        this.name = e.name
                    }
                }.toMutableList()

                if (episodes.isEmpty()) return null

                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = genres
                    if (splitDubs) {
                        this.seasonNames = dubOrder.mapIndexed { idx, k ->
                            com.lagradost.cloudstream3.SeasonData(idx + 1, dubLabel(k))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    private fun qualityFromLabel(label: String): Int {
        return when {
            label.contains("2160", ignoreCase = true) -> Qualities.P2160.value
            label.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            label.contains("720", ignoreCase = true) -> Qualities.P720.value
            label.contains("480", ignoreCase = true) -> Qualities.P480.value
            label.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun resolveArgon(
        code: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val embed = raiGet("https://$ARGON_HOST/embed/$code")
            val config = JuicyCodes.decodeFromHtml(embed.text)
            val m3u8 = config?.let {
                Regex(""""file"\s*:\s*"([^"]*\.m3u8)"""").find(it)?.groupValues?.get(1)
            }?.replace("\\/", "/")
            if (!m3u8.isNullOrBlank() && m3u8.startsWith("http")) {
                callback(
                    newExtractorLink(
                        SOURCE,
                        "MultiQuality [$suffix]",
                        m3u8,
                        ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "https://$ARGON_HOST/"
                        this.headers = mapOf(
                            "User-Agent" to RAI_UA,
                            "Accept" to "*/*",
                            "Accept-Language" to "en-US,en;q=0.9",
                            "Origin" to "https://$ARGON_HOST",
                            "Sec-Fetch-Site" to "cross-site",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Dest" to "empty"
                        )
                    }
                )
                found = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "argon embed: ${e.message}")
        }
        return found
    }

    private suspend fun resolveArgonDownload(
        code: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val dl = raiGet("https://$ARGON_HOST/downlead/$code/")
            val cookieHeader = dl.headers.values("set-cookie")
                .mapNotNull { it.substringBefore(";").takeIf { c -> c.contains("=") } }
                .joinToString("; ")
            val juicy = Regex(
                """window\.juicyData\s*=\s*(\{.*?\})\s*</script>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(dl.text)?.groupValues?.get(1)
            val wrapper = juicy?.let { parseJson<JuicyDataWrapper>(it) }
            val token = wrapper?.data?.token
            val route = wrapper?.data?.routes?.links
            if (token.isNullOrBlank() || route.isNullOrBlank()) return false
            val headers = mutableMapOf(
                "Accept" to "application/json",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to "https://$ARGON_HOST/downlead/$code/",
                "Origin" to "https://$ARGON_HOST",
                "X-Requested-With" to "XMLHttpRequest"
            )
            if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader
            val api = raiPostJson(
                "https://$ARGON_HOST$route",
                """{"captcha":null,"_token":"$token"}""",
                headers
            )
            if (api.code != 200) {
                Log.e(TAG, "argon dl api: ${api.code}")
                return false
            }
            val links = parseJson<ArgonLinks>(api.text)
            links.qualities?.forEach { q ->
                val link = q.link ?: return@forEach
                if (!link.startsWith("http")) return@forEach
                val label = q.label ?: "Download"
                val size = q.size?.let { " ($it)" } ?: ""
                callback(
                    newExtractorLink(
                        SOURCE,
                        "DLBeta $label$size [$suffix]",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityFromLabel(label)
                        this.referer = "https://$ARGON_HOST/"
                        this.headers = mapOf(
                            "User-Agent" to RAI_UA,
                            "Accept" to "*/*",
                            "Accept-Language" to "en-US,en;q=0.9",
                            "Origin" to "https://$ARGON_HOST"
                        )
                    }
                )
                found = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "argon download: ${e.message}")
        }
        return found
    }

    private suspend fun emitPixelDrain(
        id: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(
            newExtractorLink(
                SOURCE,
                "PixelDrain [$suffix]",
                "https://$PIXELDRAIN_HOST/api/file/$id",
                ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to RAI_UA)
            }
        )
        return true
    }

    private fun pixelDrainId(url: String): String? {
        val m = Regex("""/(?:u|api/file)/([A-Za-z0-9]{6,12})""").find(url) ?: return null
        return m.groupValues[1]
    }

    private suspend fun probeOk(url: String, timeoutMs: Long = 9_000L): Boolean {
        return try {
            val r = com.lagradost.cloudstream3.app.get(
                url,
                headers = mapOf(
                    "User-Agent" to RAI_UA,
                    "Range" to "bytes=0-1023"
                ),
                timeout = timeoutMs
            )
            r.code in 200..299 || r.code == 206
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun resolveStreamBeta(
        id: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val page = raiGet("https://$CODEDEW_HOST/streambeta/?url=" +
                java.net.URLEncoder.encode(id, "UTF-8"))
            if (page.code != 200) return false
            val m = Regex(
                """playerSources\s*=\s*(\[.*?\])\s*;""",
                RegexOption.DOT_MATCHES_ALL
            ).find(page.text) ?: return false
            val parsed = parseJson<List<StreamBetaLink>>(m.groupValues[1])
            if (parsed.isEmpty()) return false
            val emitted = mutableSetOf<String>()
            for (src in parsed) {
                val name = src.name ?: "Server"
                src.streamUrl?.takeIf { it.startsWith("http") }?.let { stream ->
                    if (!emitted.contains(stream) && probeOk(stream)) {
                        emitted.add(stream)
                        callback(
                            newExtractorLink(
                                SOURCE,
                                "WatchNow $name [$suffix]",
                                stream,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf("User-Agent" to RAI_UA)
                            }
                        )
                        found = true
                    }
                }
                src.url?.takeIf { it.startsWith("http") }?.let { dl ->
                    val pdId = if (dl.contains("pixeldra")) pixelDrainId(dl) else null
                    val target = if (pdId != null) "https://$PIXELDRAIN_HOST/api/file/$pdId" else dl
                    if (!emitted.contains(target) && !target.contains("mega.nz") && probeOk(target)) {
                        emitted.add(target)
                        callback(
                            newExtractorLink(
                                SOURCE,
                                "WatchNow $name DL [$suffix]",
                                target,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf("User-Agent" to RAI_UA)
                            }
                        )
                        found = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "streambeta: ${e.message}")
        }
        return found
    }

    private var goFileTokenCache: String? = null
    private var goFileTokenAt: Long = 0

    private suspend fun goFileToken(): String? {
        val cached = goFileTokenCache
        if (cached != null && System.currentTimeMillis() - goFileTokenAt < 3_600_000L) {
            return cached
        }
        return try {
            val account = parseJson<GoFileAccount>(
                com.lagradost.cloudstream3.app.post(
                    "https://api.gofile.io/accounts",
                    headers = mapOf(
                        "User-Agent" to RAI_UA,
                        "Origin" to "https://gofile.io",
                        "Referer" to "https://gofile.io/"
                    ),
                    timeout = 20_000L
                ).text
            )
            val token = account.data?.token
            if (token.isNullOrBlank()) return null
            com.lagradost.cloudstream3.app.get(
                "https://api.gofile.io/accounts/website",
                headers = mapOf(
                    "User-Agent" to RAI_UA,
                    "Authorization" to "Bearer $token",
                    "Origin" to "https://gofile.io",
                    "Referer" to "https://gofile.io/"
                ),
                timeout = 20_000L
            )
            goFileTokenCache = token
            goFileTokenAt = System.currentTimeMillis()
            token
        } catch (e: Exception) {
            Log.e(TAG, "gofile account: ${e.message}")
            null
        }
    }

    private fun goFileWt(token: String): String {
        val bucket = System.currentTimeMillis() / 14_400_000L
        val payload = "$RAI_UA::en-US::$token::$bucket::$GOFILE_WT_SECRET"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun resolveGoFile(
        code: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val token = goFileToken() ?: return false
            val wt = goFileWt(token)
            val content = parseJson<GoFileContent>(
                com.lagradost.cloudstream3.app.get(
                    "https://api.gofile.io/contents/$code?pageSize=100&sortField=name&sortDirection=1",
                    headers = mapOf(
                        "User-Agent" to RAI_UA,
                        "Authorization" to "Bearer $token",
                        "X-Website-Token" to wt,
                        "X-BL" to "en-US",
                        "Origin" to "https://gofile.io",
                        "Referer" to "https://gofile.io/"
                    ),
                    timeout = 25_000L
                ).text
            )
            if (content.status != "ok") {
                goFileTokenCache = null
                Log.e(TAG, "gofile contents: ${content.status}")
                return false
            }
            content.data?.children.orEmpty().values.forEach { child ->
                val link = child.link ?: return@forEach
                if (!link.startsWith("http")) return@forEach
                val sizeMb = child.size?.let {
                    if (it > 0) " ${it / 1048576}MB" else ""
                } ?: ""
                callback(
                    newExtractorLink(
                        SOURCE,
                        "GoFile ${(child.name ?: "file").take(40)}$sizeMb [$suffix]",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityFromLabel(child.name ?: "")
                        this.headers = mapOf(
                            "User-Agent" to RAI_UA,
                            "Authorization" to "Bearer $token"
                        )
                    }
                )
                found = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "gofile: ${e.message}")
        }
        return found
    }

    private suspend fun resolveMediaFire(
        url: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val page = raiGet(url)
            if (page.code != 200) return false
            val direct = Regex("""href="(https://download[0-9a-z]*\.mediafire\.com/[^"]+)"""")
                .find(page.text)?.groupValues?.get(1)
            if (direct.isNullOrBlank()) return false
            callback(
                newExtractorLink(
                    SOURCE,
                    "MediaFire [$suffix]",
                    direct,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to RAI_UA)
                }
            )
            found = true
        } catch (e: Exception) {
            Log.e(TAG, "mediafire: ${e.message}")
        }
        return found
    }

    private suspend fun resolveHubCloud(
        id: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val page = raiGet("https://$HUBCLOUD_HOST/drive/$id")
            val resolverUrl = Regex("""var\s+url\s*=\s*'([^']+)'""").find(page.text)?.groupValues?.get(1)
            if (resolverUrl.isNullOrBlank()) return false
            val res = raiGet(resolverUrl, mapOf("Referer" to "https://$HUBCLOUD_HOST/"))
            val body = res.text

            Regex("""<a[^>]*href="(https?://[^"]+)"[^>]*id="fsl"""").find(body)?.let { m ->
                val fsl = htmlUnescape(m.groupValues[1])
                if (fsl.startsWith("http") && !fsl.contains("hubcloud.cx/favicon")) {
                    callback(
                        newExtractorLink(
                            SOURCE,
                            "HubCloud FSL [$suffix]",
                            fsl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to RAI_UA)
                        }
                    )
                    found = true
                }
            }
            if (!found) {
                Regex("""href="(https?://[^"]*cloudflarestorage\.com/[^"]+)"""").find(body)?.let { m ->
                    callback(
                        newExtractorLink(
                            SOURCE,
                            "HubCloud FSL [$suffix]",
                            htmlUnescape(m.groupValues[1]),
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to RAI_UA)
                        }
                    )
                    found = true
                }
            }

            Regex("""https://pixel\.hubcloud\.ist/\?id=[^"'\s<>]+""").find(body)?.let { pixelMatch ->
                try {
                    var hopUrl: String? = pixelMatch.value
                    for (hop in 0 until 3) {
                        val current = hopUrl ?: break
                        val hopResp = raiGet(current, allowRedirects = false)
                        val loc = hopResp.headers["location"]
                        if (loc.isNullOrBlank()) break
                        val next: String = if (loc.startsWith("http")) loc
                        else "https://pixel.hubcloud.ist$loc"
                        hopUrl = next
                        if (next.contains("dl.php?link=")) {
                            val direct = android.net.Uri.decode(
                                next.substringAfter("dl.php?link=")
                            )
                            if (direct.startsWith("http")) {
                                callback(
                                    newExtractorLink(
                                        SOURCE,
                                        "HubCloud 10Gbps [$suffix]",
                                        direct,
                                        ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf("User-Agent" to RAI_UA)
                                    }
                                )
                                found = true
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "hubcloud 10gbps: ${e.message}")
                }
            }

            Regex("""https?://pixel(?:drain|dra)\.[a-z]+/u/([A-Za-z0-9]+)""")
                .findAll(body)
                .map { it.groupValues[1] }
                .distinct()
                .take(2)
                .forEach { pid ->
                    val api = "https://$PIXELDRAIN_HOST/api/file/$pid"
                    if (probeOk(api)) {
                        emitPixelDrain(pid, "$suffix mirror", callback)
                        found = true
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "hubcloud: ${e.message}")
        }
        return found
    }

    private suspend fun emitResolvedPopupLink(
        resolved: RAIResolvedLink,
        callback: (ExtractorLink) -> Unit
    ) {
        when (resolved.kind) {
            "hls" -> callback(
                newExtractorLink(
                    SOURCE,
                    "MultiQuality [WebView]",
                    resolved.url,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://$ARGON_HOST/"
                    this.headers = mapOf(
                        "User-Agent" to RAI_UA,
                        "Origin" to "https://$ARGON_HOST"
                    )
                }
            )
            "pixeldrain" -> callback(
                newExtractorLink(
                    SOURCE,
                    "PixelDrain [WebView]",
                    resolved.url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to RAI_UA)
                }
            )
            "pixeldrain_page" -> {
                val id = resolved.url.substringAfter("/u/").substringBefore("?").substringBefore("/")
                if (id.isNotBlank()) {
                    emitPixelDrain(id, "WebView", callback)
                }
            }
            "r2", "gvideo", "worker", "mediafire" -> callback(
                newExtractorLink(
                    SOURCE,
                    "Direct [WebView]",
                    resolved.url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to RAI_UA)
                }
            )
        }
    }

    private suspend fun resolveTarget(
        t: ResolvedTarget,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when (t) {
            is ResolvedTarget.Argon -> resolveArgon(t.code, label, callback)
            is ResolvedTarget.ArgonDownload -> resolveArgonDownload(t.code, label, callback)
            is ResolvedTarget.StreamBeta -> resolveStreamBeta(t.id, label, callback)
            is ResolvedTarget.PixelDrain -> emitPixelDrain(t.id, label, callback)
            is ResolvedTarget.HubCloud -> resolveHubCloud(t.id, label, callback)
            is ResolvedTarget.GoFile -> resolveGoFile(t.code, label, callback)
            is ResolvedTarget.MediaFire -> resolveMediaFire(t.url, label, callback)
            is ResolvedTarget.Mega -> {
                Log.i(TAG, "mega link is not streamable, skipped")
                false
            }
            is ResolvedTarget.Archive -> {
                var emitted = false
                try {
                    val resp = raiGet(t.url)
                    parseArchiveEpisodes(resp.text).take(3).forEach { (_, epUrl) ->
                        try {
                            val t2 = CodedewResolver.resolveUrl(epUrl)
                            if (resolveTarget(t2, label, callback)) emitted = true
                        } catch (e: Exception) {
                            Log.e(TAG, "archive ep resolve: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "archive fetch: ${e.message}")
                }
                emitted
            }
            is ResolvedTarget.Direct -> {
                callback(
                    newExtractorLink(
                        SOURCE,
                        "Direct [$label]",
                        t.url,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to RAI_UA)
                    }
                )
                true
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val variants = try {
            parseJson<RAIEpisodeData>(data).v
        } catch (e: Exception) {
            listOf(RAIVariant("Default", data))
        }.filter { it.u.isNotBlank() }

        if (variants.isEmpty()) return false

        var any = false
        for (v in variants) {
            try {
                if (resolveTarget(CodedewResolver.resolveUrl(v.u), v.n, callback)) any = true
            } catch (e: Exception) {
                Log.e(TAG, "variant ${v.n}: ${e.message}")
            }
        }

        if (!any) {
            val popup = showRAIResolverPopupAndWait(variants.first().u)
            if (popup != null) {
                emitResolvedPopupLink(popup, callback)
                any = true
            }
        }
        return any
    }
}
