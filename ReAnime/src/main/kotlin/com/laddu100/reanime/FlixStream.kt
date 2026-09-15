package com.laddu100.reanime

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap

object FlixStreamState {

    data class PlaylistContext(val pk: ByteArray?, val preferEnglish: Boolean)

    val keyCache = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    var lastKeyUrl: String? = null

    private val contexts = object : LinkedHashMap<String, PlaylistContext>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlaylistContext>): Boolean =
            size > 64
    }

    fun register(url: String, context: PlaylistContext) {
        synchronized(contexts) { contexts[url] = context }
    }

    fun contextFor(url: String): PlaylistContext? {
        synchronized(contexts) {
            contexts[url]?.let { return it }
            return contexts[url.substringBeforeLast("/") + "/"]
        }
    }
}

/*
 * The player fetches everything straight from the CDN. This interceptor,
 * attached to the player's http stack, does the in-flight work the stream
 * needs: key.bin prefetching, the key rewrite inside proxied playlists,
 * segment deobfuscation and the default audio track swap for dub links.
 */
class FlixStreamInterceptor : Interceptor {

    companion object {
        private val SEGMENT_XOR_KEY = intArrayOf(
            157, 42, 241, 71, 179, 142, 92, 112, 166, 25, 228, 59, 216, 98, 15, 197
        ).map { it.toByte() }.toByteArray()

        private val HLS_TYPE = "application/vnd.apple.mpegurl".toMediaType()
        private val TS_TYPE = "video/mp2t".toMediaType()
        private val KEY_TYPE = "application/octet-stream".toMediaType()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val host = request.url.host.lowercase()

        if (url.contains("key.bin")) {
            serveKey(request, url)?.let { return it }
        }

        val builder = request.newBuilder()
        if (host == "flixcloud.cc" || host.endsWith(".flixcloud.cc") ||
            host.endsWith("rundowncdn.top") || host.endsWith("atomic4cdn.top")
        ) {
            builder.header("Referer", "https://flixcloud.cc/")
            builder.header("Origin", "https://flixcloud.cc")
        }
        val response = chain.proceed(builder.build())
        if (!response.isSuccessful) return response

        if (url.contains("parse-flixcloud")) {
            return rewriteProxiedPlaylist(request, response, url)
        }

        if (url.substringBefore("?").endsWith(".m3u8") &&
            (host == "flixcloud.cc" || host.endsWith(".flixcloud.cc"))
        ) {
            val ctx = FlixStreamState.contextFor(url)
            val pk = ctx?.pk ?: return response
            val plain = FlixResolver.decryptPlaylist(response.body.string(), pk) ?: return response
            return response.newBuilder()
                .body(swapDefaultAudio(plain, ctx).toByteArray().toResponseBody(HLS_TYPE))
                .header("Content-Type", "application/vnd.apple.mpegurl")
                .removeHeader("Content-Encoding")
                .build()
        }

        if (isSegment(url, host)) {
            val out = deobfuscateSegment(response.body.bytes()) ?: return response
            return response.newBuilder()
                .body(out.toResponseBody(TS_TYPE))
                .header("Content-Type", "video/mp2t")
                .removeHeader("Content-Encoding")
                .build()
        }

        return response
    }

    private fun serveKey(request: Request, url: String): Response? {
        val target = when {
            url.contains("url=") -> request.url.queryParameter("url") ?: FlixStreamState.lastKeyUrl
            url.contains("flixcloud.cc") -> url
            else -> FlixStreamState.lastKeyUrl
        } ?: return null
        val key = FlixStreamState.keyCache[target] ?: fetchKey(target) ?: return null
        if (key.isEmpty()) return null
        return Response.Builder()
            .code(200)
            .message("OK")
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .body(key.toResponseBody(KEY_TYPE))
            .build()
    }

    private fun fetchKey(url: String): ByteArray? {
        return try {
            runBlocking {
                app.get(url, headers = mapOf(
                    "User-Agent" to ReAnimeApi.DESKTOP_UA,
                    "Referer" to "https://flixcloud.cc/"
                )).body.bytes()
            }.also {
                if (it.isNotEmpty()) FlixStreamState.keyCache[url] = it
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun rewriteProxiedPlaylist(request: Request, response: Response, url: String): Response {
        val contentType = response.body.contentType()
        var text = response.body.string()
        val rawUrl = request.url.queryParameter("url")
        if (text.contains("""URI="key.bin"""") && rawUrl != null) {
            val keyUrl = rawUrl.substringBeforeLast("/") + "/key.bin"
            FlixStreamState.lastKeyUrl = keyUrl
            text = text.replace("""URI="key.bin"""", """URI="$keyUrl"""")
        }
        FlixStreamState.contextFor(url)?.let { ctx ->
            text = swapDefaultAudio(text, ctx)
        }
        return response.newBuilder()
            .body(text.toByteArray().toResponseBody(contentType ?: HLS_TYPE))
            .removeHeader("Content-Encoding")
            .build()
    }

    private fun isSegment(url: String, host: String): Boolean {
        if (url.contains(".m3u8")) return false
        return url.contains(".webp") || url.contains(".png") || url.contains("seg-") ||
            host.endsWith("rundowncdn.top") || host.endsWith("atomic4cdn.top")
    }

    private fun deobfuscateSegment(bytes: ByteArray): ByteArray? {
        var offset = -1
        var needXor = true
        if (bytes.size >= 13 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) {
            offset = 12
            needXor = bytes[12] != 0x47.toByte()
        } else if (bytes.size >= 9 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0d.toByte() && bytes[5] == 0x0a.toByte() &&
            bytes[6] == 0x1a.toByte() && bytes[7] == 0x0a.toByte()
        ) {
            offset = 8
            needXor = bytes[8] != 0x47.toByte()
        }
        if (offset == -1) return null
        val out = ByteArray(bytes.size - offset)
        if (needXor) {
            for (i in out.indices) {
                out[i] = (bytes[offset + i].toInt() xor SEGMENT_XOR_KEY[i and 15].toInt()).toByte()
            }
        } else {
            System.arraycopy(bytes, offset, out, 0, out.size)
        }
        return out
    }

    /*
     * The source serves japanese as the default audio track. For dub links
     * the english rendition becomes the default so playback starts on it,
     * both tracks stay switchable in the player.
     */
    private fun swapDefaultAudio(playlist: String, ctx: FlixStreamState.PlaylistContext): String {
        if (!ctx.preferEnglish) return playlist
        if (!playlist.contains("#EXT-X-MEDIA")) return playlist
        return playlist.split("\n").joinToString("\n") { line ->
            if (line.startsWith("#EXT-X-MEDIA") && line.contains("TYPE=AUDIO")) {
                val english = line.contains("""LANGUAGE="en"""") ||
                    line.contains("English", ignoreCase = true)
                if (english) line.replace("DEFAULT=NO", "DEFAULT=YES")
                else line.replace("DEFAULT=YES", "DEFAULT=NO")
            } else {
                line
            }
        }
    }
}
