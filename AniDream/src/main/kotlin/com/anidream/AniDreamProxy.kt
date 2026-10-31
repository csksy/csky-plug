package com.anidream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AniDreamProxy {

    private const val CACHED_STREAMS = 120
    private const val HLS_TYPE = "application/vnd.apple.mpegurl"
    private const val VTT_TYPE = "text/vtt"

    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    @Volatile
    private var serverRunning = false

    private val pool: ExecutorService = Executors.newCachedThreadPool()

    private val sessions = object : LinkedHashMap<String, Session>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>?) = size > CACHED_STREAMS
    }

    class Session(
        val id: String,
        val dub: Boolean,
        val subtitles: List<AniDreamApi.SubtitleTrack>,
        val refresher: (suspend () -> String?)?
    ) {
        val playlists = LinkedHashMap<String, String>()
        val playlistCache = ConcurrentHashMap<String, String>()
        val vttCache = ConcurrentHashMap<Int, String>()
        @Volatile var masterBody: String = ""
        @Volatile var refreshPending = false
    }

    class RegisterResult(val url: String, val height: Int)

    // fetches the master once, keeps only the audio rendition that matches the
    // requested language so the player cannot pick the wrong track, and maps
    // every child playlist to a local path
    fun register(
        masterUrl: String,
        dub: Boolean,
        subtitles: List<AniDreamApi.SubtitleTrack>,
        refresher: (suspend () -> String?)? = null
    ): RegisterResult? {
        val port = ensureServerRunning()
        if (port == 0) return null

        val id = newId()
        val session = Session(id, dub, subtitles, refresher)
        val height = buildMaster(session, masterUrl) ?: return null

        synchronized(sessions) { sessions[id] = session }
        return RegisterResult("http://127.0.0.1:$port/$id/master.m3u8", height)
    }

    // returns the best variant height, or null when the master is unusable
    private fun buildMaster(session: Session, masterUrl: String): Int? {
        val body = fetchText(masterUrl) ?: return null
        if (!body.startsWith("#EXTM3U")) return null
        val base = masterUrl.substringBefore("master.m3u8")

        val lines = body.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val audioLines = lines.filter { it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") }
        val keepAudio = audioLines.filter { line ->
            val lang = audioLanguage(line)
            if (session.dub) lang == "eng" || lang == "en" else lang != "eng" && lang != "en"
        }.toSet().ifEmpty { audioLines.toSet() }

        val playlists = LinkedHashMap<String, String>()
        val out = StringBuilder()
        var childIndex = 0
        var height = 0
        var pendingVariant = false
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") -> {
                    if (!keepAudio.contains(line)) continue
                    val uri = mediaUri(line) ?: continue
                    playlists[childIndex.toString()] = resolve(uri, base)
                    out.append(line.replaceRange(uriRange(line)!!, "URI=\"pl/$childIndex\"")).append('\n')
                    childIndex++
                }
                pendingVariant && !line.startsWith("#") -> {
                    playlists[childIndex.toString()] = resolve(line, base)
                    out.append("pl/").append(childIndex).append('\n')
                    childIndex++
                    pendingVariant = false
                }
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)?.groupValues?.get(2)?.toIntOrNull()?.let {
                        if (it > height) height = it
                    }
                    out.append(line).append('\n')
                    pendingVariant = true
                }
                else -> out.append(line).append('\n')
            }
        }

        if (playlists.isEmpty()) return null
        session.playlists.clear()
        session.playlists.putAll(playlists)
        session.playlistCache.clear()
        session.masterBody = out.toString()
        return height
    }

    // the cdn token lives around an hour, a fresh stream call replaces every
    // upstream url in the session
    private fun refreshSession(session: Session): Boolean {
        if (session.refreshPending) return false
        synchronized(session) {
            if (session.refreshPending) return false
            session.refreshPending = true
            try {
                val refresher = session.refresher ?: return false
                val fresh = runBlocking { refresher() } ?: return false
                return buildMaster(session, fresh) != null
            } finally {
                session.refreshPending = false
            }
        }
    }

    @Synchronized
    private fun ensureServerRunning(): Int {
        if (serverRunning && serverPort > 0) return serverPort
        try {
            val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            serverPort = socket.localPort
            serverRunning = true
            Thread {
                while (serverRunning) {
                    try {
                        val conn = socket.accept()
                        pool.execute { handleRequest(conn) }
                    } catch (e: Exception) {
                        Log.d("AniDream", "accept failed: ${e.message}")
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.d("AniDream", "server start failed: ${e.message}")
        }
        return serverPort
    }

    private fun newId(): String {
        val bytes = ByteArray(9)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    private fun handleRequest(conn: Socket) {
        try {
            conn.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val rawPath = requestLine.split(" ").getOrNull(1) ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val pathOnly = rawPath.substringBefore("?")
            val parts = pathOnly.split("/").filter { it.isNotEmpty() }
            if (parts.size < 2) {
                send404(conn)
                return
            }
            val session = synchronized(sessions) { sessions[parts[0]] }
            if (session == null) {
                send404(conn)
                return
            }

            when {
                parts.size == 2 && parts[1] == "master.m3u8" ->
                    sendBytes(conn, session.masterBody.toByteArray(Charsets.UTF_8), HLS_TYPE)
                parts.size == 3 && parts[1] == "pl" -> servePlaylist(conn, session, parts[2])
                parts.size == 3 && parts[1] == "vtt" ->
                    serveSubtitle(conn, session, parts[2].removeSuffix(".vtt").toIntOrNull() ?: -1)
                else -> send404(conn)
            }
        } catch (e: Exception) {
            Log.d("AniDream", "request failed: ${e.message}")
        } finally {
            try { conn.close() } catch (e: Exception) { Log.d("AniDream", "close failed: ${e.message}") }
        }
    }

    // the cdn hands out segments as fake images, asking for the same path
    // with a .ts name returns plain mpegts bytes
    private val imageExt = Regex("""\.(webp|png)(?=$|\?)""")

    private fun servePlaylist(conn: Socket, session: Session, name: String) {
        session.playlistCache[name]?.let {
            sendBytes(conn, it.toByteArray(Charsets.UTF_8), HLS_TYPE)
            return
        }
        var upstream = session.playlists[name] ?: run { send404(conn); return }
        var body = fetchText(upstream)
        if (body == null && refreshSession(session)) {
            upstream = session.playlists[name] ?: run { send404(conn); return }
            body = fetchText(upstream)
        }
        if (body == null) {
            send404(conn)
            return
        }

        val out = StringBuilder()
        for (raw in body.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("#")) {
                out.append(imageExt.replace(line, ".ts")).append('\n')
            } else {
                out.append(line).append('\n')
            }
        }
        val rewritten = out.toString()
        session.playlistCache[name] = rewritten
        sendBytes(conn, rewritten.toByteArray(Charsets.UTF_8), HLS_TYPE)
    }

    // exoplayer cannot load standalone ass files, so they are converted to
    // webvtt on the fly, srt dubtitles get the same treatment
    private fun serveSubtitle(conn: Socket, session: Session, index: Int) {
        val track = session.subtitles.getOrNull(index) ?: run { send404(conn); return }
        session.vttCache[index]?.let {
            sendBytes(conn, it.toByteArray(Charsets.UTF_8), VTT_TYPE)
            return
        }
        val body = fetchText(track.url) ?: run { send404(conn); return }
        val vtt = if (track.type.equals("ass", ignoreCase = true)) assToVtt(body) else srtToVtt(body)
        if (vtt.isEmpty()) {
            send404(conn)
            return
        }
        session.vttCache[index] = vtt
        sendBytes(conn, vtt.toByteArray(Charsets.UTF_8), VTT_TYPE)
    }

    private fun fetchText(url: String): String? {
        return try {
            runBlocking {
                val response = app.get(url, headers = AniDreamApi.cdnHeaders)
                if (response.isSuccessful) response.text else null
            }
        } catch (e: Exception) {
            Log.d("AniDream", "fetch failed: ${e.message}")
            null
        }
    }

    private fun resolve(ref: String, base: String): String {
        return try {
            URI(base).resolve(ref).toString()
        } catch (_: Exception) {
            ref
        }
    }

    private val languageAttr = Regex("""LANGUAGE="([^"]+)"""")
    private val uriAttr = Regex("""URI="([^"]+)"""")
    private val overrideBlock = Regex("""\{[^}]*\}""")
    private val assTime = Regex("""^(\d+):(\d{2}):(\d{2})[.,](\d{1,3})$""")
    private val srtTime = Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})""")

    private fun audioLanguage(line: String): String =
        languageAttr.find(line)?.groupValues?.get(1) ?: ""

    private fun mediaUri(line: String): String? =
        uriAttr.find(line)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    private fun uriRange(line: String): IntRange? =
        uriAttr.find(line)?.range

    private fun assToVtt(ass: String): String {
        var format: List<String>? = null
        var inEvents = false
        val cues = StringBuilder()
        for (raw in ass.split("\n")) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents || line.isEmpty()) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                format = line.substring(7).split(",").map { it.trim().lowercase() }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val fmt = format ?: continue
            val columns = line.substring(9).split(",", limit = fmt.size)
            if (columns.size < fmt.size) continue
            val startIdx = fmt.indexOf("start")
            val endIdx = fmt.indexOf("end")
            val textIdx = fmt.indexOf("text")
            if (startIdx < 0 || endIdx < 0 || textIdx < 0) continue
            val start = assTimestamp(columns[startIdx]) ?: continue
            val end = assTimestamp(columns[endIdx]) ?: continue
            var text = overrideBlock.replace(columns[textIdx], "")
            text = text.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
            if (text.isEmpty()) continue
            cues.append(start).append(" --> ").append(end).append('\n')
                .append(text).append("\n\n")
        }
        if (cues.isEmpty()) return ""
        return "WEBVTT\n\n$cues"
    }

    private fun assTimestamp(value: String): String? {
        val m = assTime.find(value.trim()) ?: return null
        val (h, mi, s, frac) = m.destructured
        val ms = (frac + "000").take(3)
        return String.format("%02d:%s:%s.%s", h.toInt(), mi, s, ms)
    }

    private fun srtToVtt(srt: String): String {
        val body = srtTime.replace(srt.trimStart('\uFEFF').trim(), "$1.$2")
        if (body.isEmpty()) return ""
        return "WEBVTT\n\n$body\n"
    }

    private fun sendBytes(conn: Socket, bytes: ByteArray, contentType: String) {
        try {
            val out: OutputStream = conn.getOutputStream()
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            Log.d("AniDream", "send failed: ${e.message}")
        }
    }

    private fun sendStatus(conn: Socket, code: Int) {
        try {
            val out: OutputStream = conn.getOutputStream()
            out.write("HTTP/1.1 $code Status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (e: Exception) {
            Log.d("AniDream", "send failed: ${e.message}")
        }
    }

    private fun send404(conn: Socket) {
        sendStatus(conn, 404)
    }
}
