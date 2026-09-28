package com.laddu100.eonmovies

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object EonSources {

    private const val TAG = "EonMovies"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    // one resolved playable stream; the ps.gcloud.cyou proxy rejects requests
    // without a gcloud origin so headers travel with the link
    data class EonStream(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        val subLabel: String? = null
    )

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // every emitted link is ranged probed first, stored zip season packs
    // from any mirror fail the probe and never reach the source list
    private val probeClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private class ProbeResult(val code: Int, val contentType: String?, val head: ByteArray)

    private val toxFormatRegex = Regex("""Format:\s*([^<"]+)""")
    private val toxTitleRegex = Regex("""<title>Download (.+) - TOXcloud</title>""")
    private val toxTempRegex = Regex("""ACTIVE_TEMP_ID\s*=\s*"([^"]+)"""")
    private val toxMegaRegex = Regex("""data-megaid="([^"]+)"""")
    private val gomonsterDlRegex = Regex("""href="(/dl\?token=[^"]+)"""")
    private val toxDriveHrefRegex = Regex("""href="(https://video-downloads\.googleusercontent\.com/[^"]+)"""")
    private val toxDriveJsonRegex = Regex(""""download_url"\s*:\s*"([^"]+)"""")
    private val toxS3HrefRegex = Regex("""href="(/dls3\?id=[^"]+)"""")
    private val dflFilenameRegex = Regex("""filename\\*"\s*:\s*\\*"([^"\\]+)""")
    private val dflFiletypeRegex = Regex("""fileType\\*"\s*:\s*\\*"([^"\\]+)""")
    private val dflPdRegex = Regex("""pixeldrainLink\\*"\s*:\s*\\*"([^"\\]+)""")
    private val dflDriveRegex = Regex("""hasDirectDrive\\*"\s*:\s*\\*"?(true|false)""")
    private val hubTitleRegex = Regex("""<title>([^<]+)</title>""")
    private val hubDownloadRegex = Regex("""id="download"\s+href="([^"]+)"""")
    private val hubPxlRegex = Regex("""var pxl\s*=\s*"([^"]+)"""")
    private val hubPdFallbackRegex = Regex("""href="(https://pixeldrain\.(?:com|dev)/u/[^"]+)"""")
    private val gcPlayerBtnRegex = Regex("""href="(https://gdshare\.top/player/[^"]+)"""")
    private val gcInstantBtnRegex = Regex("""href="(https://gdshare\.top/instant/[^"]+)"""")
    private val gcSourcesRegex = Regex(
        """<script id="player-sources" type="application/json">(\[.*?\])</script>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val pdPageRegex = Regex("""https?://(pixeldrain\.(?:com|dev))/u/([A-Za-z0-9]+)""")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DriveReply(val success: Boolean? = null, val workerUrl: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class InstantReply(val success: Boolean? = null, val download_url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlayerSource(val label: String? = null, val type: String? = null, val file: String? = null, val url: String? = null)

    private fun isArchiveName(name: String): Boolean =
        name.contains("zip", ignoreCase = true) ||
            name.contains("rar", ignoreCase = true) ||
            name.endsWith(".7z", ignoreCase = true)

    private suspend fun probe(url: String): ProbeResult? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Range", "bytes=0-15")
                .build()
            probeClient.newCall(req).execute().use { res ->
                val body = res.body?.bytes()
                ProbeResult(res.code, res.header("Content-Type"), body?.copyOf(16) ?: ByteArray(0))
            }
        } catch (e: Exception) {
            Log.d(TAG, "probe failed: ${e.message}")
            null
        }
    }

    private fun ProbeResult?.isVideo(): Boolean {
        if (this == null || code !in 200..299) return false
        if (contentType != null) {
            val ct = contentType.lowercase()
            if (ct.startsWith("video/")) return true
            if (ct.contains("zip") || ct.contains("rar") ||
                ct.contains("html") || ct.contains("json")
            ) return false
        }
        if (head.size >= 2 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte()) return true
        if (head.size >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) return false
        if (head.size >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp") return true
        return false
    }

    private suspend fun fetchText(url: String, timeout: Long = 25_000L): String? {
        return try {
            app.get(url, headers = headers, timeout = timeout).text
        } catch (e: Exception) {
            Log.d(TAG, "fetch failed: ${e.message}")
            null
        }
    }

    fun pixelDrainApi(url: String): String? {
        pdPageRegex.find(url)?.let {
            return "https://${it.groupValues[1]}/api/file/${it.groupValues[2]}"
        }
        return url.takeIf { it.contains("/api/file/") }
    }

    // toxcloud fronts mega and drive copies of the same file; the page itself
    // reports the format so season zips are dropped before any host is asked
    private suspend fun resolveToxCloud(url: String): List<EonStream> {
        val page = fetchText(url) ?: return emptyList()
        val described = toxFormatRegex.find(page)?.groupValues?.get(1).orEmpty()
        val titled = toxTitleRegex.find(page)?.groupValues?.get(1).orEmpty()
        if (isArchiveName(described) || isArchiveName(titled)) return emptyList()

        val out = mutableListOf<EonStream>()
        val megaId = toxMegaRegex.find(page)?.groupValues?.get(1)
        if (megaId != null) {
            val payload = """{"id":"$megaId","expires":${System.currentTimeMillis() + 3_600_000L}}"""
            val token = Base64.encodeToString(
                URLEncoder.encode(payload, "UTF-8").toByteArray(),
                Base64.NO_WRAP
            )
            val cloudPage = fetchText("https://cloud.gomonster.dpdns.org/cloud?token=$token")
            val dlHref = cloudPage?.let { gomonsterDlRegex.find(it)?.groupValues?.get(1) }
            if (dlHref != null) {
                val dlUrl = "https://cloud.gomonster.dpdns.org$dlHref"
                if (probe(dlUrl).isVideo()) out.add(EonStream(dlUrl, subLabel = "Mega"))
            }
        }

        val tempId = toxTempRegex.find(page)?.groupValues?.get(1)
        if (tempId != null) {
            val token = Base64.encodeToString(tempId.toByteArray(), Base64.NO_WRAP)
            val driveUrl = instantDriveLink(
                "https://instant.gomonster.dpdns.org/instant?token=$token"
            )
            if (driveUrl != null && probe(driveUrl).isVideo()) {
                out.add(EonStream(driveUrl, subLabel = "Drive"))
            }
        }
        return out
    }

    // the instant worker either serves a page carrying the drive link or hops
    // through /s3 to the file, redirects are walked so no video body is read
    private suspend fun instantDriveLink(startUrl: String): String? {
        var url = startUrl
        for (hop in 0 until 3) {
            val res = try {
                app.get(url, headers = headers, allowRedirects = false, timeout = 40_000L)
            } catch (e: Exception) {
                Log.d(TAG, "instant hop failed: ${e.message}")
                return null
            }
            if (res.code in 300..399) {
                val next = res.headers["location"] ?: return null
                val nextUrl = when {
                    next.startsWith("http") -> next
                    else -> "https://instant.gomonster.dpdns.org$next"
                }
                if (nextUrl.contains("googleusercontent.com")) return nextUrl
                url = nextUrl
                continue
            }
            val bodyType = res.headers["content-type"].orEmpty()
            if (!bodyType.contains("html", ignoreCase = true)) return null
            val body = try {
                res.text
            } catch (e: Exception) {
                return null
            }
            toxDriveHrefRegex.find(body)?.groupValues?.get(1)?.let { return it }
            toxS3HrefRegex.find(body)?.groupValues?.get(1)?.let {
                return "https://instant.gomonster.dpdns.org$it"
            }
            return toxDriveJsonRegex.find(body)?.groupValues?.get(1)
        }
        return null
    }

    // dotflix is a next app; the share page ships a json blob with the file
    // facts and a direct drive worker backs up the pixeldrain mirror
    private suspend fun resolveDotflix(url: String): List<EonStream> {
        val res = try {
            app.get(url, headers = headers, timeout = 25_000L)
        } catch (e: Exception) {
            Log.d(TAG, "dotflix page failed: ${e.message}")
            return emptyList()
        }
        val page = res.text
        val fileType = dflFiletypeRegex.find(page)?.groupValues?.get(1).orEmpty()
        val filename = dflFilenameRegex.find(page)?.groupValues?.get(1).orEmpty()
        if (isArchiveName(fileType) || isArchiveName(filename)) return emptyList()

        val out = mutableListOf<EonStream>()
        val pdLink = dflPdRegex.find(page)?.groupValues?.get(1)
        if (pdLink != null) {
            val api = pixelDrainApi(pdLink) ?: pdLink
            if (probe(api).isVideo()) out.add(EonStream(api))
        }
        if (out.isEmpty() && dflDriveRegex.find(page)?.groupValues?.get(1) == "true") {
            val code = res.url.trimEnd('/').substringAfterLast('/')
            val worker = try {
                val reply = parseJson<DriveReply>(
                    app.post(
                        "https://dotflix.store/api/generate-direct-drive-download",
                        json = mapOf("sharingCode" to code),
                        headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to "https://dotflix.store/"
                        ),
                        timeout = 40_000L
                    ).text
                )
                if (reply.success == true) reply.workerUrl else null
            } catch (e: Exception) {
                Log.d(TAG, "dotflix api failed: ${e.message}")
                null
            }
            if (worker != null && probe(worker).isVideo()) out.add(EonStream(worker))
        }
        return out
    }

    // hubcloud swaps its button through a generation page whose script
    // variable holds the real pixeldrain file
    private suspend fun resolveHubCloud(url: String): List<EonStream> {
        val page = fetchText(url) ?: return emptyList()
        val titled = hubTitleRegex.find(page)?.groupValues?.get(1).orEmpty()
        if (isArchiveName(titled)) return emptyList()
        val dlHref = hubDownloadRegex.find(page)?.groupValues?.get(1) ?: return emptyList()
        val dl = fetchText(dlHref.replace("&amp;", "&")) ?: return emptyList()
        val pdLink = hubPxlRegex.find(dl)?.groupValues?.get(1)
            ?: hubPdFallbackRegex.find(dl)?.groupValues?.get(1)
            ?: return emptyList()
        val api = pixelDrainApi(pdLink) ?: pdLink
        return if (probe(api).isVideo()) listOf(EonStream(api)) else emptyList()
    }

    // gcloud hands every video file a jw player page with an hls or direct
    // source; zip only products carry neither button and stay unlisted
    private suspend fun resolveGCloud(url: String): List<EonStream> {
        val signed = try {
            app.get(url, headers = headers, timeout = 25_000L).url
        } catch (e: Exception) {
            Log.d(TAG, "gcloud page failed: ${e.message}")
            return emptyList()
        }
        val links = fetchText(signed.trimEnd('/') + "/generate-links/") ?: return emptyList()

        val out = mutableListOf<EonStream>()
        val playerHref = gcPlayerBtnRegex.find(links)?.groupValues?.get(1)
        if (playerHref != null) {
            val playerPage = fetchText(playerHref)
            val sourcesJson = playerPage?.let { gcSourcesRegex.find(it)?.groupValues?.get(1) }
            if (sourcesJson != null) {
                val sources = try {
                    parseJson<List<PlayerSource>>(sourcesJson)
                } catch (e: Exception) {
                    emptyList()
                }
                for (src in sources) {
                    val file = src.file ?: src.url ?: continue
                    val isHls = src.type.equals("hls", ignoreCase = true) || file.contains(".m3u8")
                    val streamHeaders = if (file.contains("ps.gcloud.cyou")) {
                        mapOf("Origin" to "https://gcloud.cyou")
                    } else {
                        emptyMap()
                    }
                    out.add(
                        EonStream(
                            url = file,
                            headers = streamHeaders,
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            subLabel = src.label?.takeIf {
                                it.isNotBlank() && !it.equals("Auto", ignoreCase = true)
                            }
                        )
                    )
                }
            }
        }

        val instantHref = gcInstantBtnRegex.find(links)?.groupValues?.get(1)
        if (instantHref != null) {
            val instantBase = try {
                app.get(instantHref, headers = headers, timeout = 25_000L).url.trimEnd('/')
            } catch (e: Exception) {
                null
            }
            if (instantBase != null) {
                val reply = try {
                    parseJson<InstantReply>(
                        app.get(
                            instantBase + "/?ajax=1&_t=" + System.currentTimeMillis(),
                            headers = headers,
                            timeout = 40_000L
                        ).text
                    )
                } catch (e: Exception) {
                    null
                }
                val driveUrl = reply?.takeIf { it.success == true }?.download_url
                if (driveUrl != null && probe(driveUrl).isVideo()) {
                    out.add(EonStream(driveUrl, subLabel = "Drive"))
                }
            }
        }
        return out
    }

    suspend fun resolve(url: String): List<EonStream> {
        return when {
            url.contains("azonahub.biz") -> resolveToxCloud(url)
            url.contains("dtflix.") || url.contains("dotflix.") -> resolveDotflix(url)
            url.contains("hubcloud.") -> resolveHubCloud(url)
            url.contains("gdshare.top") || url.contains("gcloud.cyou") -> resolveGCloud(url)
            url.contains("pixeldrain.com") || url.contains("pixeldrain.dev") -> {
                val api = pixelDrainApi(url) ?: url
                if (probe(api).isVideo()) listOf(EonStream(api)) else emptyList()
            }
            else -> if (probe(url).isVideo()) listOf(EonStream(url)) else emptyList()
        }
    }

    fun sourceName(url: String): String {
        return when {
            url.contains("azonahub.biz") -> "ToxCloud"
            url.contains("dtflix.") || url.contains("dotflix.") -> "Dotflix"
            url.contains("hubcloud.") -> "HubCloud"
            url.contains("gdshare.top") || url.contains("gcloud.cyou") -> "GCloud"
            url.contains("pixeldrain.com") || url.contains("pixeldrain.dev") -> "Pixeldrain"
            else -> try {
                url.toHttpUrl().host
            } catch (e: Exception) {
                url
            }
        }
    }
}
