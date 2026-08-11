package com.laddu100.telegrameera

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

/**
 * CloudStream provider that sources Movies / TV / Anime from Telegram.
 *
 * The actual Telegram automation (login, group search, bot delivery, the
 * 50-second self-destruct race and streaming) runs on a small companion
 * "bridge" server (see /bridge in this repo). This provider is a thin HTTP
 * client for that bridge, exactly like every other site plugin in this repo.
 *
 * Flow:
 *  1. search()    -> GET /api/search?q=...        (group bot file list)
 *  2. load()      -> wraps a chosen file as a movie / single-episode series
 *  3. loadLinks() -> GET /api/select?payload=...  (delivers via @Movie_world2_bot)
 *                  -> GET /api/stream/{fileId}    (progressive HTTP stream)
 */
class TelegramEeraProvider : MainAPI() {
    override var mainUrl = "https://t.me/eera_Search_Zone"
    override var name = "Telegram Eera"
    override val hasMainPage = false
    override var lang = "en"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime,
    )

    companion object {
        /**
         * Base URL of your deployed Eera bridge. Change this to your own
         * bridge (see /bridge folder) after deploying it.
         */
        const val DEFAULT_BRIDGE = "https://eera-bridge.onrender.com"

        private const val KEY_BRIDGE = "bridge_url"
        private const val KEY_TIMEOUT = "request_timeout"

        private val SERIES_RE =
            Regex("""(?i)(\bS\d{1,2}\s*E\d{1,3}\b|Episode\s*\d+|E\d{2,3}\b)""")
    }

    private val bridge: String
        get() = TelegramEeraSettings.bridgeUrl().trim().trimEnd('/').ifBlank { DEFAULT_BRIDGE }

    private val requestTimeout: Long
        get() = TelegramEeraSettings.requestTimeout().toLongOrNull() ?: 90L

    private suspend fun bridgeGet(path: String): String {
        try {
            return app.get("$bridge$path", timeout = requestTimeout).text
        } catch (e: Exception) {
            throw ErrorLoadingException(
                "Telegram Eera bridge unreachable ($bridge). " +
                    "Deploy the bridge and set TelegramEeraProvider.DEFAULT_BRIDGE to its URL."
            )
        }
    }

    // ------------------------------------------------------------------
    // Search -> the group bot's "THE RESULTS FOR" file list
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val json = bridgeGet("/api/search?q=${URLEncoder.encode(query, "UTF-8")}")
        val response = tryParseJson<EeraSearchResponse>(json)
            ?: throw ErrorLoadingException("Unexpected reply from the bridge.")
        if (response.results.isEmpty()) {
            // Surface the bot's actual reply (or a bridge message) to the user.
            throw ErrorLoadingException(response.message ?: "No results found on Telegram.")
        }
        return response.results.mapNotNull { r ->
            if (r.title.isBlank()) return@mapNotNull null
            newMovieSearchResponse(r.title, r.toJson(), TvType.Movie)
        }
    }

    // ------------------------------------------------------------------
    // Load -> one file = a movie, or a single-episode series
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val item = tryParseJson<EeraResult>(url) ?: return null
        val title = item.title.ifBlank { return null }

        val isSeries = SERIES_RE.containsMatchIn(title)
        val episode = newEpisode(item) {
            this.name = title
            this.description = item.size?.let { "Size: $it" }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                this.plot = "Streamed from Telegram (Search Zone / MovieEera)."
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, item) {
                this.plot = "Streamed from Telegram (Search Zone / MovieEera)."
            }
        }
    }

    // ------------------------------------------------------------------
    // Links -> ask the bridge to fetch the file from the bot, then stream
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // loadLinks receives the serialized EeraResult (object JSON) both for
        // movies (dataUrl) and episodes (episode.data).
        val item = tryParseJson<EeraResult>(data) ?: return false
        val payload = item.payload?.takeIf { it.isNotBlank() } ?: item.title ?: return false

        val select = tryParseJson<EeraSelectResponse>(
            bridgeGet("/api/select?payload=${URLEncoder.encode(payload, "UTF-8")}")
        )
        val fileId = select?.fileId?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException(
                select?.error ?: "Bridge could not get the file from Telegram. Is it logged in?"
            )

        val streamUrl = "$bridge/api/stream/$fileId"
        callback.invoke(
            newExtractorLink(
                name,
                "Telegram",
                streamUrl,
                ExtractorLinkType.VIDEO,
            ) {
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
                )
            }
        )
        return true
    }
}
