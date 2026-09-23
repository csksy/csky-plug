package com.toonworld4all

import com.lagradost.api.Log
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    internal class RedirectInfo(
        val destination: String,
        val linkDomain: String,
        val linkHidden: String,
        val userSystem: String,
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

    suspend fun resolveRedirect(redirectPath: String): RedirectInfo? {
        val url = if (redirectPath.startsWith("http")) redirectPath else BASE + redirectPath
        repeat(2) { attempt ->
            try {
                val response = tw4aGet(url, headers, allowRedirects = false, timeout = 20_000L)
                val location = response.headers["location"]
                if (location != null && location.startsWith("http")) {
                    return RedirectInfo(location, "", "", "")
                }
                val props = parseProps(response.text)
                if (props != null) {
                    val dest = props.optString("destination")
                    if (dest.startsWith("http")) {
                        val link = props.optJSONObject("link")
                        return RedirectInfo(
                            destination = dest,
                            linkDomain = link?.optString("domain").orEmpty(),
                            linkHidden = link?.optString("hidden").orEmpty(),
                            userSystem = props.optString("userSystem")
                        )
                    }
                }
                if (response.text.length < 200) {
                    kotlinx.coroutines.delay(400L * (attempt + 1))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.d(TAG, "redirect attempt $attempt failed: ${e.message}")
                kotlinx.coroutines.delay(300L * (attempt + 1))
            }
        }
        return null
    }

    suspend fun resolveRedirects(paths: List<String>): Map<String, RedirectInfo> {
        if (paths.isEmpty()) return emptyMap()
        return try {
            coroutineScope {
                val gate = Semaphore(6)
                paths.distinct().map { path ->
                    async {
                        try {
                            gate.withPermit {
                                Pair(path, resolveRedirect(path))
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Pair(path, null)
                        }
                    }
                }.mapNotNull { deferred ->
                    try {
                        val (path, info) = deferred.await()
                        if (info != null) path to info else null
                    } catch (e: Exception) {
                        null
                    }
                }.toMap()
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveRedirects failed: ${e.message}")
            emptyMap()
        }
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
