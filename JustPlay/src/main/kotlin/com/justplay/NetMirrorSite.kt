package com.justplay

import android.net.Uri
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

// netmirror serves one shared catalog behind netflix, hotstar and prime video
// mirrors, the ott cookie value picks the catalog and every request needs the
// solved t_hash_t cookie first
internal object NetMirrorSite {

    private const val BASE = "https://net52.cc"

    // the api only answers the gatu wrapper user agents, plain browser ones
    // get an empty verify hash back
    private const val SOLVE_UA =
        "Mozilla/5.0 (Linux; Android 12; RMX2117 Build/SP1A.210812.016; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.55 " +
            "Mobile Safari/537.36 /OS.Gatu v3.0"
    private const val PAGE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 " +
            "Safari/537.36 /OS.Gatu v3.0"
    private const val PLAYER_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/149.0.7827.91 " +
            "Safari/537.36 /OS.Gatu v3.0"
    private const val XRW_APP = "app.netmirror.netmirrornew"

    private val apiHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif," +
            "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to PAGE_UA,
        "X-Requested-With" to "XMLHttpRequest"
    )

    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    )

    private class Ott(val code: String, val site: String)

    // netflix has the deepest catalog, hotstar and prime fill the indian and
    // originals gaps the others miss
    private val catalogs = listOf(
        Ott("nf", "netmirror_nf"),
        Ott("hs", "netmirror_hs"),
        Ott("pv", "netmirror_pv")
    )

    private data class MirrorHit(val id: String, val title: String)

    private data class MirrorEp(val id: String, val season: Int?, val episode: Int?)

    private class MirrorPost(
        val title: String,
        val year: String?,
        val movie: Boolean,
        val episodes: List<MirrorEp>,
        val morePages: Boolean,
        val pageCursor: String?
    )

    @Volatile
    private var cookieValue: String? = null

    @Volatile
    private var cookieAt = 0L

    private val cookieLock = Mutex()

    private fun apiCookies(cookie: String, ott: String): Map<String, String> =
        mapOf("t_hash_t" to cookie, "ott" to ott, "hd" to "on")

    private fun playlistHeaders(cookie: String, ott: String): Map<String, String> {
        val cookieLine = apiCookies(cookie, ott)
            .entries.joinToString("; ") { "${it.key}=${it.value}" }
        return mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
            "Connection" to "keep-alive",
            "Cookie" to cookieLine,
            "Referer" to "$BASE/mobile/home?app=1",
            "sec-ch-ua" to "\"Android WebView\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Android\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "User-Agent" to PLAYER_UA,
            "X-Requested-With" to XRW_APP
        )
    }

    // the cookie stays valid for around fifteen hours, a fresh solve blocks
    // for a minute so the cached one is worth the lock dance
    private fun cookieFresh(): Boolean =
        !cookieValue.isNullOrEmpty() &&
            System.currentTimeMillis() - cookieAt < 15L * 60 * 60 * 1000

    private suspend fun solvedCookie(): String? {
        if (cookieFresh()) return cookieValue
        return cookieLock.withLock {
            if (cookieFresh()) return cookieValue
            val fresh = solveCookie()
            cookieValue = fresh?.takeIf { it.isNotEmpty() }
            cookieAt = if (cookieValue == null) 0L else System.currentTimeMillis()
            fresh
        }
    }

    private fun hashIn(body: String): Boolean =
        Regex("data-addhash\\s*=\\s*[\"'][^\"']+[\"']").containsMatchIn(body)

    private suspend fun homeBody(): String? {
        val headers = mapOf("User-Agent" to SOLVE_UA, "X-Requested-With" to XRW_APP)
        val plain = try {
            app.get("$BASE/mobile/home?app=1", headers = headers, timeout = 20L)
        } catch (_: Exception) {
            null
        }
        if (plain != null && hashIn(plain.text)) return plain.text
        // some networks get a cloudflare wall on the home page, the webview
        // killer clears it and the same request goes through
        return try {
            val solved = app.get(
                "$BASE/mobile/home?app=1",
                headers = headers,
                interceptor = PlayNet.cfKiller,
                timeout = 60L
            )
            solved.text.takeIf { hashIn(it) }
        } catch (_: Exception) {
            null
        }
    }

    // the home page parks a verify hash, a worker server cracks it in the
    // background and the verify endpoint trades it for the real cookie
    private suspend fun solveCookie(): String? {
        val html = homeBody() ?: return null
        val addhash = Regex("data-addhash\\s*=\\s*[\"']([^\"']+)[\"']")
            .find(html)?.groupValues?.get(1)?.trim()
        if (addhash.isNullOrEmpty()) return null
        try {
            app.get(
                "https://userver.net52.cc/?hee5=" + Uri.encode(addhash) +
                    "&a=y&t=" + Math.random(),
                headers = mapOf("User-Agent" to SOLVE_UA),
                timeout = 15L
            )
        } catch (_: Exception) {}

        // the worker needs close to a minute, poll the same way the wrapper
        // app does and give up after the seven rounds it allows
        repeat(7) {
            delay(10_000L)
            val res = try {
                app.post(
                    "$BASE/mobile/verify2.php",
                    data = mapOf("verify" to addhash),
                    headers = mapOf(
                        "User-Agent" to SOLVE_UA,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    timeout = 30L
                )
            } catch (_: Exception) {
                null
            } ?: return@repeat
            if (res.text.contains("\"statusup\":\"All Done\"")) {
                return res.cookies["t_hash_t"].orEmpty()
            }
        }
        return null
    }

    private suspend fun search(cookie: String, ott: String, query: String): List<MirrorHit> {
        return try {
            val text = app.get(
                "$BASE/mobile/search.php?s=" + Uri.encode(query) +
                    "&t=" + System.currentTimeMillis(),
                headers = searchHeaders,
                referer = "$BASE/home",
                cookies = apiCookies(cookie, ott),
                timeout = 20L
            ).text
            val arr = JSONObject(text).optJSONArray("searchResult") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val t = o.optString("t")
                if (id.isBlank() || t.isBlank()) return@mapNotNull null
                MirrorHit(id, t)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseEpisodes(arr: JSONArray?): List<MirrorEp> {
        if (arr == null) return emptyList()
        val out = mutableListOf<MirrorEp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out.add(
                MirrorEp(
                    id,
                    o.optString("s").removePrefix("S").toIntOrNull(),
                    o.optString("ep").removePrefix("E").toIntOrNull()
                )
            )
        }
        return out
    }

    private suspend fun fetchPost(cookie: String, ott: String, id: String): MirrorPost? {
        return try {
            val text = app.get(
                "$BASE/mobile/post.php?id=$id&t=" + System.currentTimeMillis(),
                headers = apiHeaders,
                referer = "$BASE/home",
                cookies = apiCookies(cookie, ott),
                timeout = 20L
            ).text
            val root = JSONObject(text)
            val title = root.optString("title")
            val arr = root.optJSONArray("episodes") ?: return null
            if (title.isBlank()) return null
            // a movie post leads its episode list with an empty slot, series
            // posts carry a real episode right at the front
            MirrorPost(
                title,
                root.optString("year").takeIf { it.isNotBlank() },
                arr.optJSONObject(0) == null,
                parseEpisodes(arr),
                root.optInt("nextPageShow", 0) == 1,
                root.optString("nextPageSeason").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    // the post reply carries the first page, the rest sit behind the episodes
    // endpoint under the same cursor with a running page number
    private suspend fun collectEpisodes(
        cookie: String,
        ott: String,
        postId: String,
        post: MirrorPost
    ): List<MirrorEp> {
        val all = post.episodes.toMutableList()
        val cursor = post.pageCursor ?: return all
        var more = post.morePages
        var page = 2
        while (more && page < 14) {
            val next = try {
                JSONObject(
                    app.get(
                        "$BASE/mobile/episodes.php?s=" + Uri.encode(cursor) +
                            "&series=$postId&t=" + System.currentTimeMillis() + "&page=$page",
                        headers = apiHeaders,
                        referer = "$BASE/home",
                        cookies = apiCookies(cookie, ott),
                        timeout = 20L
                    ).text
                )
            } catch (_: Exception) {
                break
            }
            all.addAll(parseEpisodes(next.optJSONArray("episodes")))
            more = next.optInt("nextPageShow", 0) != 0
            page++
        }
        return all
    }

    private suspend fun emitPlaylist(
        site: String,
        cookie: String,
        ott: String,
        id: String,
        title: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = playlistHeaders(cookie, ott)
        val text = try {
            app.get(
                "$BASE/mobile/playlist.php?id=$id&t=" + Uri.encode(title) +
                    "&tm=" + System.currentTimeMillis(),
                headers = headers,
                referer = "$BASE/mobile/home?app=1",
                cookies = apiCookies(cookie, ott),
                timeout = 20L
            ).text
        } catch (_: Exception) {
            return false
        }
        val arr = try {
            JSONArray(text)
        } catch (_: Exception) {
            null
        } ?: return false

        var emitted = false
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val sources = item.optJSONArray("sources") ?: continue
            for (j in 0 until sources.length()) {
                val src = sources.optJSONObject(j) ?: continue
                val file = src.optString("file")
                if (file.isBlank()) continue
                val url = when {
                    file.startsWith("http") -> file
                    file.startsWith("/") -> BASE + file
                    else -> continue
                }
                // the quality rides as a q= argument on the playlist path
                val quality = getQualityFromName(file.substringAfter("q=", ""))
                callback(
                    newExtractorLink(
                        "[${PlayLabels.siteName(site)}]",
                        PlayLabels.buildLabel(site, src.optString("label"), ""),
                        url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$BASE/mobile/home?app=1"
                        this.quality = quality
                        this.headers = headers
                    }
                )
                emitted = true
            }
            val tracks = item.optJSONArray("tracks") ?: continue
            for (j in 0 until tracks.length()) {
                val track = tracks.optJSONObject(j) ?: continue
                if (track.optString("kind") != "captions") continue
                val file = track.optString("file").replace("\\", "")
                if (!file.startsWith("http")) continue
                subtitleCallback(
                    newSubtitleFile(track.optString("label"), httpsify(file)) {
                        this.headers = mapOf("Referer" to "$BASE/")
                    }
                )
            }
        }
        return emitted
    }

    // a movie plays off its own post id, a series episode plays off the
    // episode id it carries in the post reply
    private suspend fun resolvePost(
        ott: Ott,
        cookie: String,
        postId: String,
        post: MirrorPost,
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (res.season == null) {
            return emitPlaylist(
                ott.site, cookie, ott.code, postId, post.title, subtitleCallback, callback
            )
        }
        val eps = collectEpisodes(cookie, ott.code, postId, post)
        val ep = eps.firstOrNull { it.season == res.season && it.episode == res.episode }
            ?: return false
        return emitPlaylist(
            ott.site, cookie, ott.code, ep.id, post.title, subtitleCallback, callback
        )
    }

    private suspend fun resolveOtt(
        ott: Ott,
        cookie: String,
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val title = res.title ?: return
        val hits = search(cookie, ott.code, title)
            .filter { PlayNet.titleMatches(it.title, title) }
        if (hits.isEmpty()) return

        // same title remakes are common across the three catalogs, the year
        // picks the right one and the first hit stays as the fallback
        var fallback: Pair<String, MirrorPost>? = null
        for (hit in hits.take(3)) {
            val post = fetchPost(cookie, ott.code, hit.id) ?: continue
            if (post.movie != (res.season == null)) continue
            if (fallback == null) fallback = hit.id to post
            if (!PlayNet.yearMatches(post.year.orEmpty(), res.matchYear)) continue
            if (resolvePost(ott, cookie, hit.id, post, res, subtitleCallback, callback)) return
        }
        val spare = fallback ?: return
        resolvePost(ott, cookie, spare.first, spare.second, res, subtitleCallback, callback)
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cookie = solvedCookie() ?: return
            coroutineScope {
                catalogs.forEach { ott ->
                    async(Dispatchers.IO) {
                        try {
                            resolveOtt(ott, cookie, res, subtitleCallback, callback)
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
