package com.laddu100.reanime

import com.lagradost.api.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

object FlixProxy {

    private const val TAG = "ReAnime"

    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    @Volatile
    private var serverRunning = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val streams = ConcurrentHashMap<String, StreamEntry>()

    class StreamEntry(
        val id: String,
        val masterUrl: String,
        val masterContent: String,
        val pkKey: ByteArray
    )

    @Synchronized
    private fun ensureServerRunning(): Int {
        if (serverRunning && serverPort > 0) return serverPort
        try {
            val socket = ServerSocket(0)
            serverSocket = socket
            serverPort = socket.localPort
            serverRunning = true
            Thread {
                while (serverRunning) {
                    try {
                        val conn = socket.accept()
                        handleRequest(conn)
                    } catch (e: Exception) {
                        if (serverRunning) Log.e(TAG, "accept failed: ${e.message}")
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "proxy start failed: ${e.message}")
        }
        return serverPort
    }

    fun registerMaster(masterUrl: String, masterContent: String, pkKey: ByteArray): String? {
        val port = ensureServerRunning()
        if (port == 0) return null
        val id = MessageDigest.getInstance("MD5")
            .digest(masterUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
        streams[id] = StreamEntry(id, masterUrl, masterContent, pkKey)
        return "http://127.0.0.1:$port/$id/master.m3u8"
    }

    private fun handleRequest(conn: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val rawPath = parts.getOrNull(1) ?: return
            while (reader.readLine()?.isNotEmpty() == true) {}

            val pathOnly = rawPath.substringBefore("?")
            val query = rawPath.substringAfter("?", "")
            val segments = pathOnly.trim('/').split("/")
            if (segments.size < 2) {
                send404(conn)
                return
            }
            val id = segments[0]
            val entry = streams[id]
            if (entry == null) {
                send404(conn)
                return
            }

            when (segments[1]) {
                "master.m3u8" -> serveMaster(conn, entry, query)
                "p" -> {
                    val target = decodeUrl(segments.drop(2).joinToString("/"))
                    if (target == null) send404(conn) else servePlaylist(conn, entry, target)
                }
                "k" -> {
                    val target = decodeUrl(segments.drop(2).joinToString("/"))
                    if (target == null) send404(conn) else serveKey(conn, target)
                }
                else -> send404(conn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "proxy request failed: ${e.message}")
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun serveMaster(conn: java.net.Socket, entry: StreamEntry, query: String) {
        val lang = Regex("""(?:^|&)lang=([^&]*)""").find(query)?.groupValues?.get(1) ?: "sub"
        val rewritten = rewriteMaster(entry, lang)
        if (rewritten == null) {
            send404(conn)
            return
        }
        sendBytes(conn, rewritten.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
    }

    private fun rewriteMaster(entry: StreamEntry, lang: String): String? {
        try {
            val lines = entry.masterContent.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val audioIdx = mutableListOf<Int>()
            for (i in lines.indices) {
                if (lines[i].startsWith("#EXT-X-MEDIA") && lines[i].contains("TYPE=AUDIO")) {
                    audioIdx.add(i)
                }
            }
            val keep = mutableSetOf<Int>()
            if (audioIdx.isNotEmpty()) {
                val english = audioIdx.filter { idx ->
                    val l = Regex("""LANGUAGE="([^"]*)"""").find(lines[idx])?.groupValues?.get(1) ?: ""
                    val n = Regex("""NAME="([^"]*)"""").find(lines[idx])?.groupValues?.get(1) ?: ""
                    l.startsWith("en") || n.contains("English", ignoreCase = true)
                }
                val wanted = if (lang == "dub") english else audioIdx.filter { it !in english }
                keep.addAll(if (wanted.isEmpty()) audioIdx else wanted)
            }
            val out = StringBuilder()
            val masterUri = URI(entry.masterUrl.substringBefore("?"))
            var pendingVariant = false
            for (i in lines.indices) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-MEDIA") && line.contains("TYPE=AUDIO")) {
                    if (i in keep) {
                        out.append(rewriteUri(line, masterUri, entry)).append('\n')
                    }
                    continue
                }
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    pendingVariant = true
                    out.append(line).append('\n')
                    continue
                }
                if (pendingVariant && !line.startsWith("#")) {
                    val abs = resolveUri(line, masterUri) ?: return null
                    out.append(proxyPath(entry, abs, "p")).append('\n')
                    pendingVariant = false
                    continue
                }
                out.append(line).append('\n')
            }
            return out.toString()
        } catch (e: Exception) {
            Log.e(TAG, "master rewrite failed: ${e.message}")
            return null
        }
    }

    private fun rewriteUri(line: String, base: URI, entry: StreamEntry): String {
        val m = Regex("""URI="([^"]+)"""").find(line) ?: return line
        val abs = resolveUri(m.groupValues[1], base) ?: return line
        return line.replaceRange(m.range, """URI="${proxyPath(entry, abs, "p")}"""")
    }

    private fun servePlaylist(conn: java.net.Socket, entry: StreamEntry, target: String) {
        try {
            val resp = fetch(target)
            val body = resp ?: run { send404(conn); return }
            val plain = FlixResolver.decryptPlaylist(body, entry.pkKey) ?: run { send404(conn); return }
            val base = URI(target.substringBefore("?"))
            val out = StringBuilder()
            for (raw in plain.split("\n")) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP")) {
                    out.append(rewriteUri(line, base, entry)).append('\n')
                    continue
                }
                if (!line.startsWith("#")) {
                    val abs = resolveUri(line, base) ?: line
                    out.append(abs).append('\n')
                    continue
                }
                out.append(line).append('\n')
            }
            sendBytes(conn, out.toString().toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
        } catch (e: Exception) {
            Log.e(TAG, "playlist fetch failed: ${e.message}")
            send404(conn)
        }
    }

    private fun serveKey(conn: java.net.Socket, target: String) {
        try {
            val bytes = fetchBytes(target)
            if (bytes == null) {
                send404(conn)
                return
            }
            sendBytes(conn, bytes, "application/octet-stream")
        } catch (e: Exception) {
            Log.e(TAG, "key fetch failed: ${e.message}")
            send404(conn)
        }
    }

    private fun proxyPath(entry: StreamEntry, abs: String, kind: String): String {
        val enc = URLEncoder.encode(abs, "UTF-8")
        return "/${entry.id}/$kind/$enc"
    }

    private fun resolveUri(ref: String, base: URI): String? {
        return try {
            base.resolve(ref).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeUrl(seg: String): String? {
        return try {
            java.net.URLDecoder.decode(seg, "UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    private fun fetch(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", ReAnimeApi.BROWSER_HEADERS["User-Agent"]!!)
            .addHeader("Referer", "${ReAnimeApi.FLIX_EMBED_BASE}/")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchBytes(url: String): ByteArray? {
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", ReAnimeApi.BROWSER_HEADERS["User-Agent"]!!)
            .addHeader("Referer", "${ReAnimeApi.FLIX_EMBED_BASE}/")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sendBytes(conn: java.net.Socket, bytes: ByteArray, contentType: String) {
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
        } catch (_: Exception) {}
    }

    private fun send404(conn: java.net.Socket) {
        try {
            val out: OutputStream = conn.getOutputStream()
            val head = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (_: Exception) {}
    }
}
