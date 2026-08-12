package com.laddu100.onlytesting

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

object SubDLHelper {

    private const val TAG = "SubDL"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val wordToNum = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4,
        "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8,
        "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12,
        "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15,
        "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18,
        "nineteenth" to 19, "twentieth" to 20, "twenty" to 20,
        "thirty" to 30, "forty" to 40
    )

    private fun parseSeasonOrdinal(name: String): Int? {
        if (name == "specials-season") return 0
        val parts = name.removeSuffix("-season").split("-")
        return when (parts.size) {
            1 -> wordToNum[parts[0]]
            2 -> {
                val tens = wordToNum[parts[0]] ?: return null
                val ones = wordToNum[parts[1]] ?: return null
                if (tens >= 20 && ones in 1..9) tens + ones else null
            }
            else -> null
        }
    }

    private fun slugify(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')

    data class SubDLTitle(
        val sdId: String,
        val slug: String,
        val title: String,
        val year: Int,
        val type: String,
        val subCount: Int
    )

    data class SubDLSubEntry(
        val release: String,
        val dlUrl: String
    )

    private suspend fun searchTitles(query: String): List<SubDLTitle> {
        val results = mutableListOf<SubDLTitle>()
        try {
            val url = "https://subdl.com/search/${slugify(query)}"
            Log.d(TAG, "search: $url")
            val resp = app.get(url, headers = mapOf("User-Agent" to ua), timeout = 15_000L)
            if (resp.code != 200) {
                Log.d(TAG, "search HTTP ${resp.code}")
                return emptyList()
            }
            val pattern = Regex(
                """href="/subtitle/sd(\d+)/([^"]+)"[^>]*>(.*?)</a>""",
                RegexOption.DOT_MATCHES_ALL
            )
            for (match in pattern.findAll(resp.text)) {
                val sdId = match.groupValues[1]
                val slug = match.groupValues[2]
                val raw = match.groupValues[3]
                val clean = raw.replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                    .replace("&#39;", "'")
                val meta = Regex("(.+?)\\s*\\((\\d{4})\\)\\s*(tv|movie)\\s+(\\d+)").find(clean)
                if (meta != null) {
                    results.add(
                        SubDLTitle(
                            sdId = sdId,
                            slug = slug,
                            title = meta.groupValues[1].trim(),
                            year = meta.groupValues[2].toInt(),
                            type = meta.groupValues[3],
                            subCount = meta.groupValues[4].toInt()
                        )
                    )
                }
            }
            Log.d(TAG, "search found ${results.size} results for '$query'")
        } catch (e: Exception) {
            Log.d(TAG, "search error: ${e.message}")
        }
        return results
    }

    private suspend fun getSeasons(sdId: String, slug: String): Map<Int, String> {
        val seasons = mutableMapOf<Int, String>()
        try {
            val url = "https://subdl.com/subtitle/sd$sdId/$slug"
            val resp = app.get(url, headers = mapOf("User-Agent" to ua), timeout = 15_000L)
            if (resp.code != 200) return emptyMap()
            val pattern = Regex("/subtitle/sd$sdId/$slug/([a-z-]+season)")
            for (match in pattern.findAll(resp.text)) {
                val seasonName = match.groupValues[1]
                val num = parseSeasonOrdinal(seasonName)
                if (num != null) {
                    seasons[num] = seasonName
                }
            }
            Log.d(TAG, "found ${seasons.size} seasons: ${seasons.keys.sorted()}")
        } catch (e: Exception) {
            Log.d(TAG, "getSeasons error: ${e.message}")
        }
        return seasons
    }

    private suspend fun getSubtitlesFromPage(pageUrl: String): List<SubDLSubEntry> {
        val entries = mutableListOf<SubDLSubEntry>()
        try {
            val resp = app.get(pageUrl, headers = mapOf("User-Agent" to ua), timeout = 15_000L)
            if (resp.code != 200) return emptyList()
            val html = resp.text

            val infoPattern = Regex(
                """<a[^>]*href="(/s/info/[^"]+)"[^>]*>(.*?)</a>""",
                RegexOption.DOT_MATCHES_ALL
            )
            val dlPattern = Regex("""https://dl\.subdl\.com/[^"'<>\s]+""")

            val infoMatches = infoPattern.findAll(html).toList()
            val dlMatches = dlPattern.findAll(html).toList()

            Log.d(TAG, "page parse: ${infoMatches.size} info links, ${dlMatches.size} dl urls")

            for (info in infoMatches) {
                val nextDl = dlMatches.firstOrNull { it.range.first > info.range.first }
                if (nextDl != null) {
                    val release = info.groupValues[2]
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("&[#a-zA-Z0-9]+;"), "")
                        .replace(Regex("\\s+"), " ").trim()
                    entries.add(SubDLSubEntry(release, nextDl.value))
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "getSubtitlesFromPage error: ${e.message}")
        }
        return entries
    }

    private fun matchEpisode(subs: List<SubDLSubEntry>, episode: Int): List<SubDLSubEntry> {
        val exact = mutableListOf<SubDLSubEntry>()
        val range = mutableListOf<SubDLSubEntry>()
        for (sub in subs) {
            val r = sub.release
            Regex("[SE]\\d{1,2}E(\\d{1,4})(?!\\d)", RegexOption.IGNORE_CASE).find(r)?.let { m ->
                if (m.groupValues[1].toIntOrNull() == episode) {
                    exact.add(sub)
                    return@let
                }
            }
            Regex("(?:^|[^0-9])E(\\d{1,4})(?:[^0-9]|$)", RegexOption.IGNORE_CASE).find(r)?.let { m ->
                if (m.groupValues[1].toIntOrNull() == episode) {
                    exact.add(sub)
                    return@let
                }
            }
            Regex("[SE]\\d{1,2}E(\\d{1,4})-(\\d{1,4})", RegexOption.IGNORE_CASE).find(r)?.let { m ->
                val start = m.groupValues[1].toIntOrNull() ?: return@let
                val end = m.groupValues[2].toIntOrNull() ?: return@let
                if (episode in start..end) {
                    range.add(sub)
                }
            }
        }
        return exact.ifEmpty { range }
    }

    private fun extractSrtFromZip(zipBytes: ByteArray, episode: Int?): String? {
        try {
            val zis = ZipInputStream(zipBytes.inputStream())
            var entry = zis.nextEntry
            var bestMatch: String? = null
            var fallback: String? = null
            while (entry != null) {
                if (entry.name.endsWith(".srt") || entry.name.endsWith(".vtt")) {
                    val content = zis.readBytes().toString(Charsets.UTF_8)
                    if (episode != null) {
                        val epMatch = Regex("[SE]\\d{1,2}E(\\d{1,4})", RegexOption.IGNORE_CASE)
                            .find(entry.name)
                        if (epMatch != null && epMatch.groupValues[1].toIntOrNull() == episode) {
                            return content
                        }
                    }
                    if (fallback == null) {
                        fallback = content
                    }
                    if (bestMatch == null && entry.name.endsWith(".srt")) {
                        bestMatch = content
                    }
                }
                entry = zis.nextEntry
            }
            return bestMatch ?: fallback
        } catch (e: Exception) {
            Log.d(TAG, "extractSrt error: ${e.message}")
        }
        return null
    }

    private val subtitleStore = ConcurrentHashMap<String, ByteArray>()
    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    @Volatile private var serverRunning = false

    @Synchronized
    private fun ensureServerRunning(): Int {
        if (serverRunning && serverPort > 0) return serverPort
        try {
            val socket = ServerSocket(0)
            serverSocket = socket
            serverPort = socket.localPort
            serverRunning = true
            Log.d(TAG, "subtitle server started on port $serverPort")

            Thread {
                while (serverRunning) {
                    try {
                        val client = socket.accept()
                        handleRequest(client)
                    } catch (e: Exception) {
                        if (serverRunning) {
                            Log.d(TAG, "server accept error: ${e.message}")
                        }
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.d(TAG, "server start error: ${e.message}")
        }
        return serverPort
    }

    private fun handleRequest(client: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val headerLine = reader.readLine() ?: return
            val path = headerLine.split(" ").getOrNull(1) ?: return

            val subId = path.removePrefix("/subdl/")
            val content = subtitleStore[subId]

            val writer = OutputStreamWriter(client.getOutputStream())
            if (content != null) {
                writer.write("HTTP/1.1 200 OK\r\n")
                writer.write("Content-Type: application/x-subrip\r\n")
                writer.write("Content-Length: ${content.size}\r\n")
                writer.write("Access-Control-Allow-Origin: *\r\n")
                writer.write("Connection: close\r\n")
                writer.write("\r\n")
                writer.flush()
                client.getOutputStream().write(content)
                client.getOutputStream().flush()
            } else {
                writer.write("HTTP/1.1 404 Not Found\r\n")
                writer.write("Content-Length: 0\r\n")
                writer.write("Connection: close\r\n")
                writer.write("\r\n")
                writer.flush()
            }
        } catch (e: Exception) {
            Log.d(TAG, "handleRequest error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun cacheSubtitle(content: String): String {
        val id = MessageDigest.getInstance("MD5")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        subtitleStore[id] = content.toByteArray(Charsets.UTF_8)
        return id
    }

    private suspend fun downloadAndExtractSrt(
        dlUrl: String,
        episode: Int?
    ): String? {
        try {
            val resp = app.get(dlUrl, headers = mapOf("User-Agent" to ua), timeout = 30_000L)
            if (resp.code != 200) {
                Log.d(TAG, "download HTTP ${resp.code}")
                return null
            }
            val bytes = resp.body.bytes()
            val isZip = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()
            Log.d(TAG, "downloaded ${dlUrl.take(60)} -> ${bytes.size} bytes, isZip=$isZip")
            val srtContent = if (isZip) {
                extractSrtFromZip(bytes, episode)
            } else {
                bytes.toString(Charsets.UTF_8)
            } ?: run {
                Log.d(TAG, "SRT extraction returned null")
                return null
            }

            if (srtContent.isBlank()) {
                Log.d(TAG, "SRT content is empty")
                return null
            }

            val preview = srtContent.take(100).replace("\n", "\\n").replace("\r", "\\r")
            Log.d(TAG, "SRT (${srtContent.length} chars) preview: $preview")
            return srtContent
        } catch (e: Exception) {
            Log.d(TAG, "downloadAndExtract error: ${e.message}")
        }
        return null
    }

    suspend fun fetchSubtitles(
        context: Context?,
        title: String,
        jpTitle: String?,
        episode: Int,
        season: Int,
        isMovie: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val searchQueries = mutableListOf(title)
        if (jpTitle != null && jpTitle.isNotBlank() && jpTitle != title) {
            searchQueries.add(jpTitle)
        }

        var titleFound: SubDLTitle? = null
        for (query in searchQueries) {
            val results = searchTitles(query)
            val tvOrMovie = if (isMovie) "movie" else "tv"
            val matching = results.filter { it.type == tvOrMovie }
            if (matching.isNotEmpty()) {
                titleFound = matching.maxByOrNull { it.subCount }
                Log.d(TAG, "matched: ${titleFound?.title} (${titleFound?.year}) ${titleFound?.type}")
                break
            }
        }

        if (titleFound == null) {
            Log.d(TAG, "no title match for '$title'/'$jpTitle'")
            return
        }

        val subs = if (isMovie) {
            getSubtitlesFromPage("https://subdl.com/subtitle/sd${titleFound.sdId}/${titleFound.slug}/english")
        } else {
            val seasons = getSeasons(titleFound.sdId, titleFound.slug)
            if (seasons.isEmpty()) {
                Log.d(TAG, "no seasons found, trying title page directly")
                getSubtitlesFromPage("https://subdl.com/subtitle/sd${titleFound.sdId}/${titleFound.slug}/english")
            } else {
                val seasonName = seasons[season]
                    ?: seasons.minByOrNull { it.key }?.value
                    ?: run {
                        Log.d(TAG, "season $season not found in ${seasons.keys}")
                        return
                    }
                Log.d(TAG, "using season: $seasonName for season $season")
                getSubtitlesFromPage("https://subdl.com/subtitle/sd${titleFound.sdId}/${titleFound.slug}/$seasonName/english")
            }
        }

        Log.d(TAG, "found ${subs.size} English subtitle entries")
        if (subs.isEmpty()) return

        val matched = if (isMovie) {
            subs
        } else {
            val m = matchEpisode(subs, episode)
            if (m.isEmpty()) {
                Log.d(TAG, "no episode $episode match, using all as fallback")
                subs
            } else {
                Log.d(TAG, "matched ${m.size} subtitles for episode $episode")
                m
            }
        }

        val port = ensureServerRunning()
        if (port == 0) {
            Log.d(TAG, "failed to start subtitle server")
            return
        }

        for ((index, sub) in matched.withIndex()) {
            val srtContent = downloadAndExtractSrt(sub.dlUrl, episode)
            if (srtContent != null) {
                val cacheId = cacheSubtitle(srtContent)
                val subtitleUrl = "http://127.0.0.1:$port/subdl/$cacheId"
                val label = "SubDL English ${index + 1}"
                subtitleCallback.invoke(newSubtitleFile(label, subtitleUrl))
                Log.d(TAG, "added subtitle: $label -> $subtitleUrl")
            }
        }
    }
}
