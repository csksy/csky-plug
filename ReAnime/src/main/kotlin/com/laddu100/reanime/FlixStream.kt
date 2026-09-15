package com.laddu100.reanime

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/*
 * Direct playback pipeline for flixcloud.cc streams.
 *
 * The player fetches the CDN urls itself, so nothing is proxied through the
 * app anymore (the old local proxy was the buffering bottleneck). This
 * interceptor, attached to the player's http stack, does the three things
 * the CDN needs in-flight:
 *
 *  1. Referer/Origin injection for flixcloud.cc and its segment CDNs
 *  2. decrypting the base64+XOR wrapped m3u8 playlists (master, video and
 *     audio renditions) and picking the default audio track
 *  3. unwrapping segments that carry a fake webp/png header (+XOR), and
 *     trimming the aes-128 key response to exactly 16 bytes
 *
 * Segments without the fake header are standard AES-128 HLS data and are
 * passed through untouched - the player decrypts those itself.
 */
class FlixStreamContext(val pk: ByteArray, val preferEnglishAudio: Boolean)

class FlixStreamInterceptor(private val ctx: FlixStreamContext) : Interceptor {

    companion object {
        // same key the segment obfuscation uses on both cdn hosts
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
        val path = url.substringBefore("?")
        val host = request.url.host.lowercase()

        val builder = request.newBuilder()
        if (host == "flixcloud.cc" || host.endsWith(".flixcloud.cc") ||
            host.endsWith("rundowncdn.top") || host.endsWith("atomic4cdn.top")
        ) {
            builder.header("Referer", "https://flixcloud.cc/")
            builder.header("Origin", "https://flixcloud.cc")
        }
        val response = chain.proceed(builder.build())
        if (!response.isSuccessful) return response

        if (path.endsWith(".m3u8")) {
            val body = response.body ?: return response
            val plain = FlixResolver.decryptPlaylist(body.string(), ctx.pk) ?: return response
            val served = if (plain.contains("#EXT-X-STREAM-INF")) {
                swapDefaultAudio(plain, ctx.preferEnglishAudio)
            } else {
                plain
            }
            val bytes = served.toByteArray(Charsets.UTF_8)
            return response.newBuilder()
                .body(bytes.toResponseBody(HLS_TYPE))
                .header("Content-Type", "application/vnd.apple.mpegurl")
                .header("Content-Length", bytes.size.toString())
                .removeHeader("Content-Encoding")
                .build()
        }

        if (path.endsWith("key.bin")) {
            val body = response.body ?: return response
            var bytes = body.bytes()
            var end = bytes.size
            while (end > 0 && (bytes[end - 1] == '\n'.code.toByte() || bytes[end - 1] == '\r'.code.toByte())) {
                end--
            }
            if (end != bytes.size) bytes = bytes.copyOf(end)
            if (bytes.size > 16) bytes = bytes.copyOf(16)
            return response.newBuilder()
                .body(bytes.toResponseBody(KEY_TYPE))
                .header("Content-Length", bytes.size.toString())
                .removeHeader("Content-Encoding")
                .build()
        }

        val isSegment = (host.endsWith("rundowncdn.top") || host.endsWith("atomic4cdn.top") ||
            url.contains("seg-") || path.endsWith(".webp") || path.endsWith(".png"))
        if (isSegment) {
            val head = try {
                response.peekBody(16).bytes()
            } catch (e: Exception) {
                return response
            }
            val headerLen = imageHeaderLen(head)
            if (headerLen == 0) return response
            val body = response.body ?: return response
            val raw = body.bytes()
            if (raw.size <= headerLen) return response
            val out = ByteArray(raw.size - headerLen)
            if (raw[headerLen] == 0x47.toByte()) {
                System.arraycopy(raw, headerLen, out, 0, out.size)
            } else {
                for (i in out.indices) {
                    out[i] = (raw[headerLen + i].toInt() xor SEGMENT_XOR_KEY[i and 15].toInt()).toByte()
                }
            }
            return response.newBuilder()
                .body(out.toResponseBody(TS_TYPE))
                .header("Content-Type", "video/mp2t")
                .header("Content-Length", out.size.toString())
                .removeHeader("Content-Encoding")
                .build()
        }

        return response
    }

    /*
     * The site serves japanese as the default audio track. For the dub link
     * the English rendition becomes the default so the player starts on it,
     * both tracks stay switchable in the player.
     */
    private fun swapDefaultAudio(master: String, preferEnglish: Boolean): String {
        if (!preferEnglish) return master
        return master.split("\n").joinToString("\n") { line ->
            if (line.startsWith("#EXT-X-MEDIA") && line.contains("TYPE=AUDIO")) {
                val english = line.contains("""LANGUAGE="en""") ||
                    line.contains("English", ignoreCase = true)
                if (english) line.replace("DEFAULT=NO", "DEFAULT=YES")
                else line.replace("DEFAULT=YES", "DEFAULT=NO")
            } else {
                line
            }
        }
    }

    private fun imageHeaderLen(data: ByteArray): Int {
        if (data.size >= 12 &&
            data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
            data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
            data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
            data[10] == 0x42.toByte() && data[11] == 0x50.toByte()
        ) return 12
        if (data.size >= 8 &&
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4e.toByte() && data[3] == 0x47.toByte() &&
            data[4] == 0x0d.toByte() && data[5] == 0x0a.toByte() &&
            data[6] == 0x1a.toByte() && data[7] == 0x0a.toByte()
        ) return 8
        return 0
    }
}
