package com.cno

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import java.net.URI

internal object CnoApi {

    const val MAIN_URL = "https://cno.to"

    private const val IMAGE_BASE = "https://image.tmdb.org/t/p"

    fun image(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "$IMAGE_BASE/$size$path"
    }

    fun absolute(url: String): String =
        if (url.startsWith("http")) url else MAIN_URL + url

    suspend fun json(path: String): JSONObject? {
        return try {
            val res = app.get(absolute(path), timeout = 15_000L)
            if (!res.isSuccessful) null else JSONObject(res.text)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun text(url: String): String? {
        return try {
            val res = app.get(absolute(url), timeout = 20_000L)
            if (!res.isSuccessful) null else res.text
        } catch (_: Exception) {
            null
        }
    }

    data class Variant(val url: String, val quality: Int)

    // the multi cdn server hands out absolute variant urls, the in house ones
    // use paths relative to cno.to, both resolve through the same URI call
    fun parseVariants(master: String, masterUrl: String): List<Variant> {
        val lines = master.lines()
        val out = ArrayList<Variant>(8)
        for (i in 0 until lines.size - 1) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val target = lines[i + 1].trim()
            if (target.isEmpty() || target.startsWith("#")) continue
            val size = RESOLUTION.find(lines[i])
            val width = size?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val height = size?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val url = try {
                URI(absolute(masterUrl)).resolve(target).toString()
            } catch (_: Exception) {
                continue
            }
            // server 20 names the quality in the path, that is exact, the
            // resolution line is the fallback for server 21's odd shapes
            val labeled = PATH_QUALITY.find(target)?.groupValues?.get(1)?.toIntOrNull()
            out.add(Variant(url, labeled ?: qualityOf(width, height)))
        }
        // a media playlist with no variants plays fine as a single link
        if (out.isEmpty() && master.startsWith("#EXTM3U")) {
            out.add(Variant(absolute(masterUrl), Qualities.Unknown.value))
        }
        return out
    }

    // scope masters are 3840 wide but barely 1600 tall, the width is what
    // makes those 4K, everything else buckets off the height
    private fun qualityOf(width: Int, height: Int): Int = when {
        width >= 3000 || height >= 1900 -> 2160
        height >= 1400 -> 1440
        height >= 1000 -> 1080
        height >= 700 -> 720
        height >= 500 -> 576
        height > 0 -> 480
        else -> Qualities.Unknown.value
    }

    private val RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val PATH_QUALITY = Regex("""/(\d{3,4})p/""")
}
