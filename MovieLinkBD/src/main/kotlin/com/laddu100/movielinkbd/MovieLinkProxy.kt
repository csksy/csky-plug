package com.laddu100.movielinkbd

import android.content.Context
import com.lagradost.api.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

object MovieLinkProxy {

    private const val TAG = "MovieLinkBD"
    private const val MAX_STREAMS = 6
    private const val READAHEAD_BYTES = 256L * 1024 * 1024
    private const val HEAD_READ_BYTES = 64 * 1024
    private const val COPY_BUF_BYTES = 64 * 1024
    private const val SPOOL_DIR = "mlbd_proxy"
    private const val SPOOL_MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val FEEDER_ATTEMPTS = 3
    private const val NO_PROGRESS_LIMIT_MS = 120_000L

    private var appContext: Context? = null
    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    @Volatile
    private var serverRunning = false

    private val pool: ExecutorService = Executors.newCachedThreadPool()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 8
        })
        .build()

    private val streams = ConcurrentHashMap<String, StreamEntry>()
    private val order = ArrayDeque<String>()
    private val orderLock = Any()

    private class StreamEntry(
        val id: String,
        @Volatile var url: String,
        val headers: Map<String, String>
    ) {
        val lock = ReentrantLock()
        val dataAvailable = lock.newCondition()

        @Volatile var length: Long = -1
        @Volatile var contentType: String = "application/octet-stream"
        @Volatile var headersReady = false
        @Volatile var feederDone = false
        @Volatile var feederFailed = false
        @Volatile var spooledBytes: Long = 0

        val maxRequested = AtomicLong(0)
        val feederStarted = AtomicBoolean(false)
        var spoolFile: File? = null
    }

    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    @Synchronized
    fun register(url: String, headers: Map<String, String>): String? {
        val port = ensureServerRunning()
        if (port == 0) {
            Log.e(TAG, "proxy unavailable, cannot register stream")
            return null
        }
        val id = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(10)
        synchronized(orderLock) {
            val existing = streams[id]
            if (existing == null || existing.feederDone || existing.feederFailed) {
                existing?.let(::removeEntry)
                streams[id] = StreamEntry(id, url, headers)
            }
            order.remove(id)
            order.addLast(id)
            while (order.size > MAX_STREAMS) {
                order.removeFirstOrNull()?.let { old ->
                    streams.remove(old)?.let(::removeEntry)
                }
            }
        }
        return "http://127.0.0.1:$port/$id"
    }

    private fun removeEntry(entry: StreamEntry) {
        try {
            entry.spoolFile?.delete()
        } catch (e: Exception) {
            Log.d(TAG, "spool delete failed: ${e.message}")
        }
    }

    @Synchronized
    private fun ensureServerRunning(): Int {
        if (serverRunning && serverPort > 0) return serverPort
        try {
            val socket = ServerSocket(0)
            serverSocket = socket
            serverPort = socket.localPort
            serverRunning = true
            purgeStaleSpools()
            Log.d(TAG, "proxy listening on 127.0.0.1:$serverPort")
            Thread {
                while (serverRunning) {
                    try {
                        val conn = socket.accept()
                        pool.execute { handleRequest(conn) }
                    } catch (e: Exception) {
                        if (serverRunning) Log.e(TAG, "proxy accept failed: ${e.message}")
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "proxy start failed: ${e.message}")
        }
        return serverPort
    }

    private fun purgeStaleSpools() {
        val context = appContext ?: return
        val files = File(context.cacheDir, SPOOL_DIR).listFiles() ?: return
        val cutoff = System.currentTimeMillis() - SPOOL_MAX_AGE_MS
        for (file in files) {
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    private fun handleRequest(conn: Socket) {
        try {
            conn.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore("?").removePrefix("/")
            if (path.isEmpty()) {
                sendStatus(conn, 404, "Not Found", emptyList())
                return
            }

            val requestHeaders = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    requestHeaders[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val entry = streams[path]
            if (entry == null) {
                Log.w(TAG, "proxy request for unknown stream $path")
                sendStatus(conn, 404, "Not Found", emptyList())
                return
            }

            when (method) {
                "HEAD" -> serveHead(conn, entry)
                "GET" -> serveGet(conn, entry, requestHeaders["range"])
                else -> sendStatus(conn, 405, "Method Not Allowed", emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "proxy request failed: ${e.message}")
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun serveHead(conn: Socket, entry: StreamEntry) {
        if (!awaitHeaders(entry)) {
            sendStatus(conn, 503, "Service Unavailable", listOf("Content-Length" to "0"))
            return
        }
        sendStatus(
            conn, 200, "OK",
            listOf(
                "Content-Type" to entry.contentType,
                "Content-Length" to entry.length.toString()
            )
        )
    }

    private fun serveGet(conn: Socket, entry: StreamEntry, rangeHeader: String?) {
        if (!awaitHeaders(entry)) {
            sendStatus(conn, 503, "Service Unavailable", listOf("Content-Length" to "0"))
            return
        }
        val length = entry.length
        if (length <= 0) {
            sendStatus(conn, 503, "Service Unavailable", listOf("Content-Length" to "0"))
            return
        }

        var start = 0L
        var end = length - 1
        var partial = false
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").split(",").firstOrNull().orEmpty()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val first = spec.substring(0, dash).trim()
                val last = spec.substring(dash + 1).trim()
                if (first.isEmpty()) {
                    start = (length - (last.toLongOrNull() ?: 0L)).coerceAtLeast(0)
                    partial = true
                } else {
                    val parsed = first.toLongOrNull()
                    if (parsed != null) {
                        start = parsed
                        if (last.isNotEmpty()) end = last.toLongOrNull() ?: end
                        partial = true
                    }
                }
            }
        }

        if (start >= length) {
            sendStatus(
                conn, 416, "Range Not Satisfiable",
                listOf("Content-Range" to "bytes */$length", "Content-Length" to "0")
            )
            return
        }
        end = end.coerceAtMost(length - 1)

        // a read far past what has been fetched still has to be satisfiable, so the
        // feeder is allowed to advance that far before the park check applies
        entry.maxRequested.updateAndGet { current ->
            maxOf(current, (start + READAHEAD_BYTES).coerceAtMost(length))
        }
        entry.lock.withLock { entry.dataAvailable.signalAll() }

        val headers = mutableListOf(
            "Content-Type" to entry.contentType,
            "Content-Length" to (end - start + 1).toString()
        )
        if (partial) headers.add("Content-Range" to "bytes $start-$end/$length")
        sendStatus(conn, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", headers)

        streamFromSpool(conn, entry, start, end)
    }

    private fun streamFromSpool(conn: Socket, entry: StreamEntry, start: Long, end: Long) {
        val spool = entry.spoolFile ?: return
        try {
            RandomAccessFile(spool, "r").use { reader ->
                var pos = start
                var lastAvailable = 0L
                var lastProgress = System.currentTimeMillis()
                val out = conn.getOutputStream()
                val buf = ByteArray(COPY_BUF_BYTES)
                while (pos <= end) {
                    entry.lock.withLock {
                        while (pos >= entry.spooledBytes && !entry.feederDone && !entry.feederFailed) {
                            entry.dataAvailable.await(5, TimeUnit.SECONDS)
                        }
                        if (entry.spooledBytes > lastAvailable) {
                            lastAvailable = entry.spooledBytes
                            lastProgress = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - lastProgress > NO_PROGRESS_LIMIT_MS) {
                            return
                        }
                    }
                    val available = entry.spooledBytes
                    if (pos >= available) break
                    reader.seek(pos)
                    val want = minOf(buf.size.toLong(), end - pos + 1, available - pos).toInt()
                    val n = reader.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                    pos += n
                    entry.maxRequested.updateAndGet { current -> maxOf(current, pos) }
                    entry.lock.withLock { entry.dataAvailable.signalAll() }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "stream copy ended: ${e.message}")
        }
    }

    private fun awaitHeaders(entry: StreamEntry): Boolean {
        startFeeder(entry)
        val deadline = System.currentTimeMillis() + 90_000
        entry.lock.withLock {
            while (!entry.headersReady && !entry.feederFailed) {
                if (System.currentTimeMillis() > deadline) return false
                entry.dataAvailable.await(1, TimeUnit.SECONDS)
            }
        }
        return entry.headersReady
    }

    private fun startFeeder(entry: StreamEntry) {
        if (entry.feederStarted.compareAndSet(false, true)) {
            Thread({ feedWithRetry(entry) }, "mlbd-feeder-${entry.id}").apply {
                isDaemon = true
            }.start()
        }
    }

    private fun feedWithRetry(entry: StreamEntry) {
        var attempt = 0
        while (attempt < FEEDER_ATTEMPTS) {
            attempt++
            if (feed(entry, if (attempt == 1) 0L else entry.spooledBytes)) return
            if (entry.length > 0 && entry.spooledBytes >= entry.length) break
        }
        entry.lock.withLock {
            entry.feederFailed = true
            entry.dataAvailable.signalAll()
        }
        Log.d(TAG, "feeder gave up on ${entry.id} after $attempt attempts")
    }

    private fun feed(entry: StreamEntry, skip: Long): Boolean {
        return try {
            val builder = Request.Builder().url(entry.url).get()
            entry.headers.forEach { (k, v) -> builder.addHeader(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "upstream ${resp.code} for ${entry.id}")
                    return false
                }
                val body = resp.body ?: return false
                if (!entry.headersReady) {
                    entry.length = body.contentLength()
                    entry.contentType = body.contentType()?.toString() ?: "application/octet-stream"
                    if (entry.length <= 0) {
                        Log.d(TAG, "upstream sent no length for ${entry.id}")
                        return false
                    }
                    entry.lock.withLock {
                        entry.headersReady = true
                        entry.dataAvailable.signalAll()
                    }
                }
                val input = body.byteStream()
                if (skip > 0 && !discard(input, skip)) return false
                writeSpool(entry, input, skip)
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "feeder interrupted on ${entry.id}: ${e.message}")
            false
        }
    }

    private fun writeSpool(entry: StreamEntry, input: InputStream, from: Long) {
        val spool = ensureSpoolFile(entry)
        if (spool == null) {
            entry.lock.withLock {
                entry.feederFailed = true
                entry.dataAvailable.signalAll()
            }
            return
        }
        RandomAccessFile(spool, "rw").use { out ->
            out.seek(from)
            if (from == 0L) {
                val head = readHead(input)
                out.write(patchMkvHead(head))
                entry.lock.withLock {
                    entry.spooledBytes = head.size.toLong()
                    entry.dataAvailable.signalAll()
                }
            }
            val buf = ByteArray(COPY_BUF_BYTES)
            while (true) {
                entry.lock.withLock {
                    while (entry.spooledBytes - entry.maxRequested.get() >= READAHEAD_BYTES &&
                        entry.spooledBytes < entry.length
                    ) {
                        entry.dataAvailable.await(5, TimeUnit.SECONDS)
                    }
                }
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                entry.lock.withLock {
                    entry.spooledBytes += n
                    entry.dataAvailable.signalAll()
                }
            }
        }
        entry.lock.withLock {
            entry.feederDone = true
            entry.dataAvailable.signalAll()
        }
    }

    private fun ensureSpoolFile(entry: StreamEntry): File? {
        entry.spoolFile?.let { return it }
        val context = appContext ?: return null
        val dir = File(context.cacheDir, SPOOL_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, "${entry.id}.spool")
        entry.spoolFile = file
        return file
    }

    private fun readHead(input: InputStream): ByteArray {
        val head = ByteArray(HEAD_READ_BYTES)
        var filled = 0
        while (filled < head.size) {
            val n = input.read(head, filled, head.size - filled)
            if (n < 0) break
            filled += n
        }
        return if (filled == head.size) head else head.copyOf(filled)
    }

    private fun discard(input: InputStream, count: Long): Boolean {
        val buf = ByteArray(COPY_BUF_BYTES)
        var remaining = count
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) return false
            remaining -= n
        }
        return true
    }

    private fun sendStatus(conn: Socket, code: Int, status: String, headers: List<Pair<String, String>>) {
        try {
            val out = conn.getOutputStream()
            val sb = StringBuilder("HTTP/1.1 $code $status\r\n")
            for ((key, value) in headers) {
                sb.append(key).append(": ").append(value).append("\r\n")
            }
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (_: Exception) {}
    }

    // matroska files written while encoding keep the seek index at the end, and
    // the google endpoint they are served from ignores range requests, so players
    // that follow the index stall forever reading the tail. dropping the seek head
    // makes players fall back to plain sequential playback instead.
    private fun patchMkvHead(data: ByteArray): ByteArray {
        if (data.size < 16) return data
        if (data[0] != 0x1A.toByte() || data[1] != 0x45.toByte() ||
            data[2] != 0xDF.toByte() || data[3] != 0xA3.toByte()
        ) {
            return data
        }
        if (elementId(data, 0) != 0x1A45DFA3L) return data
        val afterEbml = nextElementPos(data, 0) ?: return data
        if (afterEbml >= data.size || elementId(data, afterEbml) != 0x18538067L) return data
        // the segment size is left unset by streamed muxes and is not needed here
        var pos = payloadPos(data, afterEbml) ?: return data

        val out = data.copyOf()
        var patched = 0
        while (pos < data.size - 8) {
            val id = elementId(data, pos) ?: break
            val idWidth = vintWidth(data[pos].toInt() and 0xFF)
            if (idWidth > 8) break
            val sizePos = pos + idWidth
            val size = elementSize(data, sizePos) ?: break
            val sizeW = sizeWidth(data, sizePos)
            if (size > data.size - (sizePos + sizeW)) break
            val total = idWidth + sizeW + size.toInt()
            if (total <= 0 || pos + total > data.size) break
            if (id == 0x1F43B675L) break
            if (id == 0x114D9B74L && total in 4..16383) {
                out[pos] = 0xEC.toByte()
                var voidSizeWidth = 1
                var payload = total - 2
                if (payload > 0x7F) {
                    voidSizeWidth = 2
                    payload = total - 3
                }
                if (voidSizeWidth == 1) {
                    out[pos + 1] = payload.toByte()
                } else {
                    out[pos + 1] = (0x40 or (payload shr 8)).toByte()
                    out[pos + 2] = (payload and 0xFF).toByte()
                }
                for (i in pos + 1 + voidSizeWidth until pos + total) out[i] = 0
                patched++
            }
            pos += total
        }
        if (patched > 0) Log.d(TAG, "dropped $patched seek head(s) from stream")
        return out
    }

    private fun vintWidth(first: Int): Int {
        var width = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            width++
            if (width > 8) return 9
        }
        return width
    }

    private fun elementId(data: ByteArray, pos: Int): Long? {
        if (pos >= data.size) return null
        val width = vintWidth(data[pos].toInt() and 0xFF)
        if (width > 8 || pos + width > data.size) return null
        var value = 0L
        for (i in 0 until width) {
            value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
        }
        return value
    }

    private fun elementSize(data: ByteArray, pos: Int): Long? {
        if (pos >= data.size) return null
        val width = vintWidth(data[pos].toInt() and 0xFF)
        if (width > 8 || pos + width > data.size) return null
        var value = (data[pos].toLong() and 0xFF) and (0xFFL shr width)
        for (i in 1 until width) {
            value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
        }
        return value
    }

    private fun sizeWidth(data: ByteArray, pos: Int): Int {
        if (pos >= data.size) return 9
        return vintWidth(data[pos].toInt() and 0xFF)
    }

    private fun nextElementPos(data: ByteArray, idPos: Int): Int? {
        if (idPos >= data.size) return null
        val idWidth = vintWidth(data[idPos].toInt() and 0xFF)
        if (idWidth > 8) return null
        val sizePos = idPos + idWidth
        val size = elementSize(data, sizePos) ?: return null
        val end = sizePos + sizeWidth(data, sizePos) + size
        if (end > data.size) return null
        return end.toInt()
    }

    private fun payloadPos(data: ByteArray, idPos: Int): Int? {
        if (idPos >= data.size) return null
        val idWidth = vintWidth(data[idPos].toInt() and 0xFF)
        if (idWidth > 8) return null
        val sizePos = idPos + idWidth
        return sizePos + sizeWidth(data, sizePos)
    }
}
