package com.cno

import android.util.Base64
import com.lagradost.api.Log
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Serves the anime hls tree to the player when the edge refuses every non
 * browser transport on it. The master and the media playlists live on the
 * guarded host, so they are fetched through the plugin's own transport and
 * handed back with their playlist references pointing at this server,
 * while the segments themselves stay on the open cdn the playlists
 * already name in full.
 */
internal object CnoProxy {

    private const val TAG = "Cno"
    private const val HLS_TYPE = "application/vnd.apple.mpegurl"
    private const val CACHE_LIMIT = 64

    private var serverSocket: ServerSocket? = null
    private var serverPort = 0

    @Volatile
    private var serverRunning = false

    private val pool: ExecutorService = Executors.newCachedThreadPool()

    private val playlistCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_LIMIT
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
                        Log.d(TAG, "proxy accept failed: ${e.message}")
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.d(TAG, "proxy start failed: ${e.message}")
        }
        return serverPort
    }

    fun linkFor(masterUrl: String): String? {
        val port = ensureServerRunning()
        if (port == 0) return null
        val id = Base64.encodeToString(masterUrl.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return "http://127.0.0.1:$port/m/$id"
    }

    private fun handleRequest(conn: Socket) {
        try {
            conn.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val rawPath = requestLine.split(" ").getOrNull(1) ?: return
            val pathOnly = rawPath.substringBefore("?")
            val segments = pathOnly.split("/").filter { it.isNotEmpty() }
            when {
                segments.size == 2 && segments[0] == "m" -> serveMaster(conn, decodeB64(segments[1]))
                segments.size >= 3 && segments[0] == "p" -> {
                    val target = decodeUrl(segments.drop(2).joinToString("/"))
                    if (target == null) send404(conn) else servePlaylist(conn, target)
                }
                else -> send404(conn)
            }
        } catch (e: Exception) {
            Log.d(TAG, "proxy request failed: ${e.message}")
        } finally {
            try {
                conn.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun serveMaster(conn: Socket, masterUrl: String?) {
        if (masterUrl == null) {
            send404(conn)
            return
        }
        val body = fetchPlaylist(masterUrl)
        if (body == null) {
            send404(conn)
            return
        }
        val out = StringBuilder()
        val base = URI(masterUrl.substringBefore("?"))
        var expectsUri = false
        for (raw in body.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    out.append(line).append('\n')
                    expectsUri = true
                }
                line.startsWith("#EXT-X-MEDIA") -> {
                    out.append(rewriteAttrUri(line, base)).append('\n')
                }
                !line.startsWith("#") && expectsUri -> {
                    val abs = resolveUri(line, base)
                    if (abs == null) {
                        expectsUri = false
                        continue
                    }
                    out.append(proxyPath(abs)).append('\n')
                    expectsUri = false
                }
                else -> out.append(line).append('\n')
            }
        }
        sendBytes(conn, out.toString().toByteArray(Charsets.UTF_8))
    }

    private fun servePlaylist(conn: Socket, target: String) {
        synchronized(playlistCache) { playlistCache[target] }?.let {
            sendBytes(conn, it.toByteArray(Charsets.UTF_8))
            return
        }
        val body = fetchPlaylist(target)
        if (body == null) {
            send404(conn)
            return
        }
        val out = StringBuilder()
        val base = URI(target.substringBefore("?"))
        for (raw in body.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA") -> out.append(rewriteAttrUri(line, base)).append('\n')
                // keys, maps and segments sit on the open cdn with their full
                // url in the playlist, they go straight to the player
                line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP") ->
                    out.append(rewriteAttrUri(line, base, direct = true)).append('\n')
                !line.startsWith("#") -> {
                    val abs = resolveUri(line, base) ?: line
                    out.append(abs).append('\n')
                }
                else -> out.append(line).append('\n')
            }
        }
        val rewritten = out.toString()
        synchronized(playlistCache) { playlistCache[target] = rewritten }
        sendBytes(conn, rewritten.toByteArray(Charsets.UTF_8))
    }

    private val uriAttr = Regex("""URI="([^"]+)"""")

    private fun rewriteAttrUri(line: String, base: URI, direct: Boolean = false): String {
        val m = uriAttr.find(line) ?: return line
        val abs = resolveUri(m.groupValues[1], base) ?: return line
        val replacement = if (direct) abs else proxyPath(abs)
        return line.replaceRange(m.range, """URI="$replacement"""")
    }

    private fun fetchPlaylist(url: String): String? {
        return try {
            // the referer the whole site family accepts, the edge and the
            // cdns behind it alike
            runBlocking { CnoApi.text(url, CnoApi.streamReferer(), accept = "*/*") }
        } catch (e: Exception) {
            Log.d(TAG, "proxy playlist fetch failed: ${e.message}")
            null
        }
    }

    private fun proxyPath(abs: String): String {
        return "/p/" + URLEncoder.encode(abs, "UTF-8")
    }

    private fun resolveUri(ref: String, base: URI): String? = try {
        base.resolve(ref).toString()
    } catch (_: Exception) {
        null
    }

    private fun decodeB64(value: String): String? = try {
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun decodeUrl(seg: String): String? = try {
        URLDecoder.decode(seg, "UTF-8")
    } catch (_: Exception) {
        null
    }

    private fun sendBytes(conn: Socket, bytes: ByteArray) {
        try {
            val out: OutputStream = conn.getOutputStream()
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $HLS_TYPE\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            Log.d(TAG, "proxy send failed: ${e.message}")
        }
    }

    private fun send404(conn: Socket) {
        try {
            val out: OutputStream = conn.getOutputStream()
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (_: Exception) {
        }
    }
}
