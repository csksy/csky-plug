package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile

/**
 * vidout.pages.dev source.
 *
 * Vidout is a thin wrapper over a GitHub-hosted HLS catalog
 * (github.com/Watchout2025/api):
 *   movie: hls/movie/{tmdbId}          -> plain text stream url
 *   tv:    hls/tv/{tmdbId}/S{s}.json   -> {"1": url, ...}
 * The multimovies embed carries an imdb id for movies, tmdb id for tv.
 *
 * CRITICAL: the stream hosts require `Referer: https://vidout.pages.dev/`
 * (they 403 otherwise). Subtitles come from the same urlset CDNs
 * ({srv}.{acek-cdn.com|dramiyos-cdn.com}/vtt/.../{file}_{lang}.vtt) and the
 * sub/movie|tv GitHub folders.
 */
object MMVidout {

    private const val TAG = "MM_Vidout"
    const val REFERER = "https://vidout.pages.dev/"
    private const val GITHUB_RAW = "https://raw.githubusercontent.com/Watchout2025/api/refs/heads/main"

    private val LANG_NAMES = mapOf(
        "eng" to "English", "hin" to "Hindi", "spa" to "Spanish", "fre" to "French",
        "ger" to "German", "ita" to "Italian", "por" to "Portuguese", "rus" to "Russian",
        "zho" to "Chinese", "ara" to "Arabic", "kor" to "Korean", "jpn" to "Japanese",
        "tam" to "Tamil", "tel" to "Telugu", "kan" to "Kannada", "mal" to "Malayalam",
    )

    suspend fun resolve(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        try {
            // movie: /movie/{imdb|tmdb} ; tv: /tv/{tmdb}/s{S}/e{E}
            val tvMatch = Regex("/tv/(\\d+)/(?:s(\\d+)|(\\d+))/(?:e(\\d+)|(\\d+))", RegexOption.IGNORE_CASE)
                .find(embedUrl)
            val movieMatch = Regex("/movie/(tt\\d+|\\d+)", RegexOption.IGNORE_CASE).find(embedUrl)

            var streamUrl: String? = null
            var tmdbId: String? = null
            var season: Int? = null
            var episode: Int? = null

            if (tvMatch != null) {
                tmdbId = tvMatch.groupValues[1]
                season = (tvMatch.groupValues[2].ifEmpty { tvMatch.groupValues[3] }).toIntOrNull()
                episode = (tvMatch.groupValues[4].ifEmpty { tvMatch.groupValues[5] }).toIntOrNull()
                if (tmdbId == null || season == null || episode == null) return false
                val body = MMNet.getText("$GITHUB_RAW/hls/tv/$tmdbId/S$season.json") ?: return false
                // {"1": "https://...", "2": ...}
                streamUrl = Regex("\"$episode\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                    ?.let { MMNet.deEsc(it) }
            } else if (movieMatch != null) {
                val id = movieMatch.groupValues[1]
                tmdbId = if (id.startsWith("tt")) {
                    MMNxsha.imdbToTmdb(id, "movie")
                } else {
                    id
                } ?: return false
                streamUrl = MMNet.getText("$GITHUB_RAW/hls/movie/$tmdbId")?.trim()
                    ?.takeIf { it.startsWith("http") }
            } else {
                return false
            }

            var url = streamUrl ?: return false
            url = MMNet.deEsc(url)

            // vidout quirk: '#' means the entry points at an embed page, and a
            // .txt that is not an /hls3/ urlset falls back to the videasy player -
            // neither is resolvable over http, so bail out quietly.
            if (url.contains("#")) return false
            val lower = url.lowercase()
            if (lower.endsWith(".txt") && !lower.contains("/hls3/")) return false
            if (!lower.contains(".m3u8") && !lower.endsWith(".txt") && !lower.contains("/stream/")) {
                return false
            }

            // emit master playlist (multi-audio + multi-quality) as one link
            callback(
                newExtractorLink(label, label, url, type = ExtractorLinkType.M3U8) {
                    this.headers = mapOf("Referer" to REFERER)
                }
            )

            loadUrlsetSubtitles(url, subtitleCallback)
            loadGithubSubtitles(tmdbId, season, episode, subtitleCallback)
            return true
        } catch (e: Exception) {
            Log.d(TAG, "resolve failed: ${e.message?.take(80)}")
            return false
        }
    }

    /**
     * For /hls3/ urlset streams the sibling vtt files live on
     * https://{srv}.{acek-cdn.com|dramiyos-cdn.com}/vtt/{prefix}/{folder}/{file}_{lang}.vtt
     */
    private suspend fun loadUrlsetSubtitles(streamUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val m = Regex("/([^/]+)/hls3/([^/]+)/([^/]+)/([^/]+)_(?:,|[nhl]/)").find(streamUrl)
                ?: return
            val (srv, prefix, folderId, filePrefix) = m.destructured
            for (lang in LANG_NAMES.keys) {
                val name = LANG_NAMES[lang] ?: continue
                val cdn = "https://$srv.acek-cdn.com/vtt/$prefix/$folderId/${filePrefix}_$lang.vtt"
                subtitleCallback(newSubtitleFile(name, cdn) {
                    this.headers = mapOf("Referer" to REFERER)
                })
            }
        } catch (e: Exception) {
        }
    }

    /** sub/movie/{tmdb}/subtitles.json or sub/tv/{tmdb}/{s}/{e}/subtitles.json */
    private suspend fun loadGithubSubtitles(
        tmdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (tmdbId == null) return
        try {
            val path = if (season != null && episode != null) {
                "sub/tv/$tmdbId/$season/$episode/subtitles.json"
            } else {
                "sub/movie/$tmdbId/subtitles.json"
            }
            val body = MMNet.getText("$GITHUB_RAW/$path") ?: return
            for (m in Regex("\"([a-z]{2})\"\\s*:\\s*\"(https?[^\"]+)\"").findAll(body)) {
                val name = m.groupValues[1].uppercase()
                val url = MMNet.deEsc(m.groupValues[2])
                subtitleCallback(newSubtitleFile(name, url) {})
            }
        } catch (e: Exception) {
        }
    }
}
