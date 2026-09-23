package com.toonworld4all

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject

internal object Tw4aArchive {

    const val BASE = "https://archive.toonworld4all.me"
    private const val TAG = "TW4A"

    private val PROPS_REGEX = Regex(
        """window\.__PROPS__\s*=\s*(\{.*?\})\s*;?\s*(?:</script>|$)""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://toonworld4all.me/",
    )

    internal class ArchiveData(
        val metadata: JSONObject?,
        val encodes: List<JSONObject>,
        val streams: List<JSONObject>,
    )

    internal class ArchiveFile(
        val host: String,
        val redirectPath: String,
    )

    internal class ArchiveEncode(
        val codec: String,
        val size: String,
        val quality: Int,
        val files: List<ArchiveFile>,
    )

    suspend fun fetchArchive(url: String): ArchiveData? {
        return try {
            val response = tw4aGet(url, headers, timeout = 30_000L)
            val root = parseProps(response.text) ?: return null
            val inner = root.optJSONObject("data")?.optJSONObject("data") ?: return null
            val encodes = mutableListOf<JSONObject>()
            inner.optJSONArray("encodes")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { encodes.add(it) }
            }
            val streams = mutableListOf<JSONObject>()
            inner.optJSONArray("streams")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { streams.add(it) }
            }
            ArchiveData(
                metadata = inner.optJSONObject("metadata"),
                encodes = encodes,
                streams = streams,
            )
        } catch (e: Exception) {
            Log.d(TAG, "fetchArchive failed: ${e.message}")
            null
        }
    }

    fun parseEncodes(data: ArchiveData): List<ArchiveEncode> {
        val out = mutableListOf<ArchiveEncode>()
        for (encode in data.encodes) {
            val readable = encode.optJSONObject("readable")
            val codec = readable?.optString("codec")?.takeIf { it.isNotBlank() }
                ?: buildString {
                    append(encode.optString("resolution"))
                    if (encode.optString("codec").contains("265", true)) append(" HEVC")
                }
            val size = readable?.optString("size").orEmpty()
            val quality = Regex("""(\d{3,4})""").find(encode.optString("resolution"))
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d{3,4})""").find(codec)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            val files = mutableListOf<ArchiveFile>()
            encode.optJSONArray("files")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    val host = f.optString("host")
                    val link = f.optString("link")
                    if (link.isBlank()) continue
                    files.add(ArchiveFile(host = host, redirectPath = link))
                }
            }
            if (files.isNotEmpty()) {
                out.add(ArchiveEncode(codec = codec, size = size, quality = quality, files = files))
            }
        }
        return out
    }

    @Volatile
    private var manualSystemSet = false

    private suspend fun ensureManualSystem() {
        if (manualSystemSet) return
        try {
            val sessionHeaders = tw4aSessionHeaders(BASE)
            val h = headers.toMutableMap()
            if (sessionHeaders.isNotEmpty()) {
                h.putAll(sessionHeaders.filterValues { it.isNotBlank() })
            }
            app.post(
                "$BASE/api/user/preference/system?id=manual",
                headers = h,
                timeout = 15_000L
            )
            manualSystemSet = true
        } catch (e: Exception) {
            Log.d(TAG, "system preference save failed: ${e.message}")
        }
    }

    private suspend fun fetchDestination(redirectPath: String): String? {
        val url = if (redirectPath.startsWith("http")) redirectPath else BASE + redirectPath
        repeat(3) { attempt ->
            try {
                val response = tw4aGet(url, headers, allowRedirects = false, timeout = 30_000L)
                val location = response.headers["location"]
                if (location != null && location.startsWith("http")) return location
                val props = parseProps(response.text)
                val dest = props?.optString("destination").orEmpty()
                if (dest.startsWith("http")) return dest
                if (response.text.length < 200) {
                    kotlinx.coroutines.delay(400L * (attempt + 1))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.d(TAG, "redirect attempt $attempt failed: ${e.message}")
                kotlinx.coroutines.delay(400L * (attempt + 1))
            }
        }
        return null
    }

    suspend fun resolveDestinations(redirectPath: String): List<String> {
        val found = LinkedHashSet<String>()
        val first = fetchDestination(redirectPath)
        if (first != null && TW4A_FILE_HOST.containsMatchIn(first) && !tw4aIsShortenerUrl(first)) {
            return listOf(first)
        }
        if (first != null && first.startsWith("http")) found.add(first)
        ensureManualSystem()
        repeat(2) {
            val dest = fetchDestination(redirectPath) ?: return@repeat
            if (TW4A_FILE_HOST.containsMatchIn(dest) && !tw4aIsShortenerUrl(dest)) {
                return listOf(dest)
            }
            if (dest.startsWith("http")) found.add(dest)
            kotlinx.coroutines.delay(250)
        }
        return found.sortedByDescending { it.contains("gplinks") }
    }

    private fun parseProps(html: String): JSONObject? {
        val raw = PROPS_REGEX.find(html)?.groupValues?.get(1) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }
}
