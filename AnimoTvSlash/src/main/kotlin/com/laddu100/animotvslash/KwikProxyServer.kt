package com.laddu100.animotvslash

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object KwikProxyServer {

    private const val TAG = "KwikProxy"
    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    @Volatile private var serverRunning = false

    private val streamCache = ConcurrentHashMap<String, StreamEntry>()

    data class StreamEntry(
        val m3u8Url: String,
        val referer: String,
        val cdnBase: String
    )

    @Synchronized
    private fun ensureServerRunning(): Int {
        if (serverRunning && serverPort > 0) return serverPort
        try {
            val socket = ServerSocket(0)
            serverSocket = socket
            serverPort = socket.localPort
            serverRunning = true
            Log.d(TAG, "server started on port $serverPort")

            Thread {
                while (serverRunning) {
                    try {
                        val client = socket.accept()
                        handleRequest(client)
                    } catch (e: Exception) {
                        if (serverRunning) Log.e(TAG, "accept error: ${e.message}")
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "start error: ${e.message}")
        }
        return serverPort
    }

    private fun handleRequest(client: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val headerLine = reader.readLine() ?: return
            val path = headerLine.split(" ").getOrNull(1) ?: return

            while (reader.readLine()?.isNotEmpty() == true) {}

            val cleanPath = path.substringBefore("?").removePrefix("/kwik/")

            val slashIdx = cleanPath.indexOf("/")
            if (slashIdx < 0) {
                send404(client)
                return
            }

            val streamId = cleanPath.substring(0, slashIdx)
            val subPath = cleanPath.substring(slashIdx + 1)

            val entry = streamCache[streamId]
            if (entry == null) {
                send404(client)
                return
            }

            val targetUrl = if (subPath.startsWith("http")) {
                subPath
            } else {
                "${entry.cdnBase}/$subPath"
            }

            val referer = entry.referer
            val isM3u8 = targetUrl.endsWith(".m3u8") || subPath.endsWith("uwu.m3u8") || subPath == "uwu.m3u8"
            val isKey = targetUrl.endsWith(".key") || subPath.endsWith("mon.key") || subPath == "mon.key"

            try {
                val client2 = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(targetUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                    .addHeader("Referer", referer)
                    .get()
                    .build()
                val response = client2.newCall(request).execute()
                val responseBody = response.body

                val writer = OutputStreamWriter(client.getOutputStream())

                if (isM3u8) {
                    var m3u8Content = responseBody?.string() ?: ""
                    val cdnHost = entry.cdnBase
                    m3u8Content = m3u8Content.replace(
                        "URI=\"${cdnHost}/",
                        "URI=\"/kwik/${streamId}/"
                    )
                    m3u8Content = m3u8Content.replace(
                        Regex("https://[^/]+/stream/[^/]+/[^/]+/([a-f0-9]+)/"),
                        "/kwik/${streamId}/"
                    )

                    val bytes = m3u8Content.toByteArray(Charsets.UTF_8)
                    writer.write("HTTP/1.1 200 OK\r\n")
                    writer.write("Content-Type: application/vnd.apple.mpegurl\r\n")
                    writer.write("Content-Length: ${bytes.size}\r\n")
                    writer.write("Access-Control-Allow-Origin: *\r\n")
                    writer.write("Connection: close\r\n")
                    writer.write("\r\n")
                    writer.flush()
                    client.getOutputStream().write(bytes)
                    client.getOutputStream().flush()
                } else {
                    val bytes = responseBody?.bytes() ?: ByteArray(0)
                    val contentType = when {
                        isKey -> "application/octet-stream"
                        targetUrl.endsWith(".jpg") || subPath.contains("segment") -> "video/MP2T"
                        else -> "application/octet-stream"
                    }
                    writer.write("HTTP/1.1 200 OK\r\n")
                    writer.write("Content-Type: $contentType\r\n")
                    writer.write("Content-Length: ${bytes.size}\r\n")
                    writer.write("Access-Control-Allow-Origin: *\r\n")
                    writer.write("Connection: close\r\n")
                    writer.write("\r\n")
                    writer.flush()
                    client.getOutputStream().write(bytes)
                    client.getOutputStream().flush()
                }
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "proxy fetch error for $subPath: ${e.message}")
                send404(client)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleRequest error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun send404(client: java.net.Socket) {
        try {
            val writer = OutputStreamWriter(client.getOutputStream())
            writer.write("HTTP/1.1 404 Not Found\r\n")
            writer.write("Content-Length: 0\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.flush()
        } catch (_: Exception) {}
    }

    suspend fun getProxiedM3u8Url(m3u8Url: String, referer: String): String? {
        return try {
            val port = ensureServerRunning()
            if (port == 0) return null

            val cdnUri = java.net.URI(m3u8Url)
            val cdnBase = "${cdnUri.scheme}://${cdnUri.host}"

            val streamId = MessageDigest.getInstance("MD5")
                .digest(m3u8Url.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(12)

            streamCache[streamId] = StreamEntry(
                m3u8Url = m3u8Url,
                referer = referer,
                cdnBase = cdnBase
            )

            "http://127.0.0.1:$port/kwik/$streamId/uwu.m3u8"
        } catch (e: Exception) {
            Log.e(TAG, "getProxiedM3u8Url: ${e.message}")
            null
        }
    }
}
