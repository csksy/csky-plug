package com.animecomments

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import java.security.MessageDigest

@JsonIgnoreProperties(ignoreUnknown = true)
data class CommentEntry(
    val u: String = "",
    val t: String = "",
    val ts: Long = 0,
)

data class EpisodeRef(
    val apiName: String,
    val showTitle: String,
    val episodeTitle: String,
    val season: Int?,
    val episode: Int?,
)

object CommentsApi {
    private val mapper = ObjectMapper()

    fun normalizeBase(url: String): String =
        url.trim().trimEnd('/').takeIf { it.startsWith("http") } ?: ""

    fun episodeOf(meta: ResultEpisode): EpisodeRef? {
        val show = meta.headerName?.takeIf { it.isNotBlank() } ?: meta.name?.takeIf { it.isNotBlank() }
        ?: return null
        return EpisodeRef(
            apiName = meta.apiName.orEmpty().ifBlank { "unknown" },
            showTitle = show,
            episodeTitle = meta.name.orEmpty(),
            season = meta.season,
            episode = if (meta.episode > 0) meta.episode else null,
        )
    }

    // stable per provider + show + episode so the same room is shared no matter
    // which mirror or language track someone is watching
    fun keyOf(ref: EpisodeRef): String {
        val raw = "${ref.apiName}|${ref.showTitle.lowercase()}|${ref.season ?: 0}|${ref.episode ?: 0}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun labelOf(ref: EpisodeRef): String {
        val episodePart = when {
            ref.season != null && ref.episode != null -> "S${ref.season}E${ref.episode}"
            ref.episode != null -> "EP ${ref.episode}"
            else -> "Movie"
        }
        return "${ref.showTitle} - $episodePart"
    }

    suspend fun loadComments(base: String, key: String): List<CommentEntry> {
        val body = runCatching { app.get("$base/c/$key").text }.getOrNull() ?: return emptyList()
        return runCatching {
            mapper.readValue(body, object : TypeReference<List<CommentEntry>>() {})
        }.getOrNull() ?: emptyList()
    }

    // returns the full updated list on success, null when the post was rejected
    suspend fun postComment(base: String, key: String, user: String, text: String): List<CommentEntry>? {
        val payload = mapper.writeValueAsString(mapOf("u" to user, "t" to text))
        val response = runCatching {
            app.post("$base/c/$key", json = payload).text
        }.getOrNull() ?: return null
        val parsed = runCatching {
            mapper.readValue(response, object : TypeReference<List<CommentEntry>>() {})
        }.getOrNull() ?: return null
        return parsed
    }
}
