package com.laddu100.themoviesboss

import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

// tmbcloud is the site's own file host. its file pages hand out mirror buttons pointing
// at hubcloud, gofile and friends instead of serving bytes themselves, so the job here
// is to surface every link on the page and hand each one to its real extractor
private const val TAG = "TheMoviesBoss"

class TmbCloudExtractor : ExtractorApi() {
    override val name = "TmbCloud"
    override val mainUrl = "https://tmbcloud.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, headers = mapOf("User-Agent" to HubCloudExtractor.USER_AGENT)).document
            var emitted = 0

            doc.select("a[href]").forEach { anchor ->
                val href = anchor.absUrl("href")
                when {
                    href.isBlank() || href == url -> return@forEach
                    href.contains("tmbcloud.", true) -> return@forEach
                    MIRROR_HOSTS.any { href.contains(it, ignoreCase = true) } -> {
                        loadExtractor(href, url, subtitleCallback, callback)
                        emitted++
                    }
                    DIRECT_FILE.containsMatchIn(href) -> {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name ${anchor.text().trim().ifBlank { "file" }}",
                                url = href,
                                referer = url,
                                quality = getIndexQuality(anchor.text()),
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        emitted++
                    }
                }
            }

            doc.select("button[data-file], button[data-href], a[data-href]").forEach { el ->
                val target = el.attr("data-file").ifBlank { el.attr("data-href") }
                if (target.startsWith("http") && !target.contains("tmbcloud.")) {
                    if (MIRROR_HOSTS.any { target.contains(it, ignoreCase = true) }) {
                        loadExtractor(target, url, subtitleCallback, callback)
                    } else if (DIRECT_FILE.containsMatchIn(target)) {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name file",
                                url = target,
                                referer = url,
                                quality = getIndexQuality(target),
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                    emitted++
                }
            }

            if (emitted == 0) Log.d(TAG, "tmbcloud page has no resolvable links: $url")
        } catch (e: Exception) {
            Log.e(TAG, "tmbcloud: ${e.message}")
        }
    }

    companion object {
        private val MIRROR_HOSTS = listOf(
            "hubcloud", "vcloud", "gofile.io", "gdflix", "gdlink", "driveleech",
            "driveseed", "filepress", "filebee", "pixeldrain", "hubdrive", "fastdl"
        )
        private val DIRECT_FILE = Regex("""\.(mp4|mkv|avi|webm)(\?|$)""", RegexOption.IGNORE_CASE)
    }
}
