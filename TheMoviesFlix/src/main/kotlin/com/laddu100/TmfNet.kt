package com.laddu100

import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

object TmfNet {

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // the site blocks a bare user agent on a lot of networks, only the full
    // browser header set gets through
    fun browserHeaders(referer: String? = null): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = DESKTOP_UA
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        h["Accept-Language"] = "en-US,en;q=0.9"
        h["sec-ch-ua"] = "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\""
        h["sec-ch-ua-mobile"] = "?0"
        h["sec-ch-ua-platform"] = "\"Windows\""
        h["Sec-Fetch-Dest"] = "document"
        h["Sec-Fetch-Mode"] = "navigate"
        h["Sec-Fetch-Site"] = if (referer != null) "same-origin" else "none"
        h["Sec-Fetch-User"] = "?1"
        h["Upgrade-Insecure-Requests"] = "1"
        referer?.let { h["Referer"] = it }
        return h
    }

    // the live domain moves every few months, firebase is first and every
    // known mirror is tried until one answers
    private val FALLBACK_DOMAINS = listOf(
        "https://themoviesflix.forum",
        "https://moviesflix.red",
        "https://moviesflixhq.com",
        "https://themoviesflix.actor",
        "https://moviesflixi.com",
        "https://themoviesflixhq.com"
    )

    private val domainMutex = Mutex()

    @Volatile
    private var activeDomain: String? = null

    suspend fun domain(): String {
        activeDomain?.let { return it }
        return domainMutex.withLock {
            activeDomain?.let { return it }
            val candidates = listOfNotNull(
                FirebaseDomainHelper.getDomain("themoviesflix")?.removeSuffix("/"),
                *FALLBACK_DOMAINS.toTypedArray()
            ).distinct()
            for (candidate in candidates) {
                val res = try {
                    app.get("$candidate/", headers = browserHeaders(), timeout = 12L)
                } catch (_: Exception) {
                    null
                }
                if (res != null && res.isSuccessful) {
                    activeDomain = candidate
                    return candidate
                }
            }
            FALLBACK_DOMAINS.first()
        }
    }

    suspend fun fetchPage(url: String, referer: String? = null): Document? {
        val res = fetchWithRetry(url, referer) ?: return null
        if (!res.isSuccessful) return null
        return try {
            res.document
        } catch (_: Exception) {
            null
        }
    }

    // the site has short bad moments behind cloudflare, one quick retry saves
    // the whole load instead of surfacing an error
    private suspend fun fetchWithRetry(url: String, referer: String?): NiceResponse? {
        var last: NiceResponse? = null
        repeat(2) {
            val res = try {
                app.get(url, headers = browserHeaders(referer), timeout = 15L)
            } catch (_: Exception) {
                null
            }
            if (res != null && res.code !in 500..599) return res
            last = res
        }
        return last
    }

    class DrivePage(
        val title: String,
        val quality: Int?,
        val info: String,
        val links: List<String>,
        val episodes: Map<Int, List<String>>
    )

    private class CachedDrivePage(val savedAt: Long, val page: DrivePage)

    private val driveCache = ConcurrentHashMap<String, CachedDrivePage>()
    private val driveLocks = ConcurrentHashMap<String, Mutex>()
    private val driveSlots = Semaphore(6)
    private const val DRIVE_CACHE_MS = 5 * 60 * 1000L

    fun qualityOf(text: String): Int? =
        Regex("""(\d{3,4})[pP]\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (text.contains("2160", true) || text.contains("4K", true) || text.contains("UHD", true)) 2160 else null

    fun infoOf(title: String): String =
        Regex("""[\[{(]([^\]})]+)[\]})]""").findAll(title)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.equals("links", true) }
            .joinToString(" · ")

    // links that belong to the drive page itself, the site's own bookmark
    // promos, hosts that sit behind an interactive turnstile the player can
    // never pass, and file lockers that have been dead for months
    private val DRIVE_JUNK = listOf(
        "nexdrive", "mobilejsr", "vglist", "w.org", "wordpress", "gmpg",
        "googleapis", "googletagmanager", "font-awesome", "schema", "category/",
        "t.me/+", "telegram", "moviesflix", "mcloud.best", "gofile.io",
        "1fichier", "megaup", "vikingfile", "gdtot", "dropgalaxy"
    )

    private fun isDriveJunk(href: String): Boolean = DRIVE_JUNK.any { href.contains(it, true) }

    private val encodedRegex = Regex("""const\s+encoded\s*=\s*"((?:[A-Za-z0-9+/=]|\\/)+)"""")

    // the drive page ships its whole body as a base64 string inside a script
    // and injects it on click, the plain dom only holds a wait button
    private fun decodeEmbeddedBody(html: String): Document? {
        val encoded = encodedRegex.find(html)?.groupValues?.get(1)
            ?.replace("\\", "") ?: return null
        val decoded = try {
            java.util.Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            Jsoup.parse(String(decoded, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDrivePage(doc: Document, rawHtml: String): DrivePage {
        val embedded = decodeEmbeddedBody(rawHtml)

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore(" – ").substringBefore(" - ").trim()

        // the embedded body is pure content without the site's article
        // wrapper, the plain dom fallback keeps the old page shape
        val root = embedded?.body()
            ?: doc.selectFirst("article")
            ?: doc.selectFirst("div.entry-content")
            ?: return DrivePage(title, qualityOf(title), infoOf(title), emptyList(), emptyMap())

        val links = root.select("a[href]").mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http") && !isDriveJunk(href)) href else null
        }.distinct()

        // episode rows sit under an h4 header with the button set in the
        // paragraph right below it
        val episodes = mutableMapOf<Int, MutableList<String>>()
        for (h4 in root.select("h4")) {
            val text = h4.text().trim()
            if (!text.contains("Episode", true)) continue
            val epNum = Regex("""Episodes?\s*:?\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue

            val epLinks = mutableListOf<String>()
            var sibling = h4.nextElementSibling()
            var attempts = 0
            while (sibling != null && attempts < 3) {
                for (a in sibling.select("a[href]")) {
                    val href = a.attr("href").trim()
                    if (href.startsWith("http") && !isDriveJunk(href)) epLinks.add(href)
                }
                if (epLinks.isNotEmpty()) break
                sibling = sibling.nextElementSibling()
                attempts++
            }
            if (epLinks.isNotEmpty()) {
                episodes.getOrPut(epNum) { mutableListOf() }.addAll(epLinks.distinct())
            }
        }

        return DrivePage(title, qualityOf(title), infoOf(title), links, episodes)
    }

    // the drive app answers on two domains that swap health by the hour, the
    // old addresses only redirect so everything is normalized to the current
    // pair before it hits the cache
    private fun normalizeDriveUrl(url: String): String = when {
        url.contains("nexdrive.fit") -> url.replace("nexdrive.fit", "nexdrive.beer")
        url.contains("mobilejsr.rest") -> url.replace("mobilejsr.rest", "mobilejsr.best")
        else -> url
    }

    private fun mirrorUrl(url: String): String = when {
        url.contains("nexdrive.beer") -> url.replace("nexdrive.beer", "mobilejsr.best")
        url.contains("mobilejsr.best") -> url.replace("mobilejsr.best", "nexdrive.beer")
        else -> url
    }

    private suspend fun driveFetchOnce(url: String): String? {
        val jar = mutableMapOf<String, String>()
        var current = url
        val seen = mutableSetOf<String>()
        repeat(8) {
            if (!seen.add(current)) return null
            val res = try {
                app.get(
                    current,
                    headers = browserHeaders("https://themoviesflix.forum/"),
                    cookies = jar,
                    allowRedirects = false,
                    timeout = 12L
                )
            } catch (_: Exception) {
                return null
            }
            jar.putAll(res.cookies)
            val loc = res.headers["location"]?.trim().orEmpty()
            if (loc.isEmpty()) {
                if (res.code != 200) return null
                val body = try {
                    res.text
                } catch (_: Exception) {
                    return null
                }
                if (body.contains("challenge-platform") || body.contains("Just a moment", true)) {
                    return null
                }
                return body
            }
            current = when {
                loc.startsWith("http") -> loc
                loc.startsWith("/") -> originOf(current) + loc
                else -> current.trimEnd('/') + "/" + loc
            }
        }
        return null
    }

    // the origin behind cloudflare drops requests with 520-525 several times
    // in a row, a couple of spaced retries is the difference between a dead
    // page and a loaded one
    private suspend fun driveFetch(url: String): String? {
        repeat(3) { attempt ->
            val html = driveFetchOnce(url)
            if (html != null) return html
            if (attempt < 2) delay(700L)
        }
        return null
    }

    // failures are not cached on purpose, the flaky origin recovers within
    // seconds and a cached null would kill every retry for the next minutes
    suspend fun fetchDrivePage(url: String): DrivePage? {
        val key = normalizeDriveUrl(url)
        driveCache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.savedAt < DRIVE_CACHE_MS) return cached.page
            driveCache.remove(key)
        }
        val lock = driveLocks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            driveCache[key]?.let { cached ->
                if (System.currentTimeMillis() - cached.savedAt < DRIVE_CACHE_MS) return cached.page
                driveCache.remove(key)
            }
            val page = driveSlots.withPermit {
                val html = driveFetch(key) ?: driveFetch(mirrorUrl(key)) ?: return@withPermit null
                val doc = try {
                    Jsoup.parse(html)
                } catch (_: Exception) {
                    null
                } ?: return@withPermit null
                val parsed = parseDrivePage(doc, html)
                if (parsed.title.contains("zip", true)) null else parsed
            }
            if (page != null) {
                driveCache[key] = CachedDrivePage(System.currentTimeMillis(), page)
            }
            page
        }
    }

    // one kilobyte range request, drops links whose file is gone before they
    // reach the player
    suspend fun probe(url: String, referer: String? = null): Int? {
        return try {
            val res = app.get(
                url,
                headers = browserHeaders(referer).toMutableMap().apply {
                    put("Range", "bytes=0-1023")
                },
                timeout = 12L
            )
            res.code
        } catch (_: Exception) {
            null
        }
    }

    fun originOf(url: String): String = try {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Exception) {
        url
    }
}
