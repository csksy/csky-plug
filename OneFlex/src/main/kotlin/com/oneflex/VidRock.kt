package com.oneflex

import android.util.Base64
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// vidrock.to serves an open json api, each server entry carries an
// aes-gcm wrapped url using a key that ships inside the player bundle
object VidRock {
    private const val TAG = "OneFlexVidRock"
    private const val API = "https://vidrock.to/api"
    private const val SUBS = "https://sub.vdrk.site/v2"
    private const val PAGE = "https://vidrock.to/"

    private val KEY = OneFlexProvider.hexBytes("7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f")

    private val mapper = ObjectMapper()

    fun decryptUrl(token: String): String? {
        return try {
            var b64 = token.replace('-', '+').replace('_', '/')
            while (b64.length % 4 != 0) b64 += "="
            val raw = Base64.decode(b64, Base64.DEFAULT)
            if (raw.size < 28) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(KEY, "AES"),
                GCMParameterSpec(128, raw.copyOfRange(0, 12))
            )
            String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d(TAG, "url decrypt failed: ${e.message}")
            null
        }
    }

    private fun pathFor(type: String, id: String, season: Int?, episode: Int?): String =
        if (type == "tv" && season != null && episode != null) "tv/$id/$season/$episode" else "movie/$id"

    private suspend fun emitSubtitles(path: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val body = app.get("$SUBS/$path", headers = OneFlexProvider.BASE_HEADERS).text
            mapper.readTree(body).forEach { sub ->
                val label = sub.get("label")?.asText() ?: return@forEach
                val file = sub.get("file")?.asText() ?: return@forEach
                subtitleCallback(newSubtitleFile(label, file))
            }
        } catch (e: Exception) {
            Log.d(TAG, "subs unavailable: ${e.message}")
        }
    }

    suspend fun load(
        type: String,
        id: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val path = pathFor(type, id, season, episode)
        val json = try {
            mapper.readTree(app.get("$API/$path", headers = OneFlexProvider.BASE_HEADERS).text)
        } catch (e: Exception) {
            Log.e(TAG, "api request failed for $path: ${e.message}")
            return false
        }

        var found = false
        json.fields().forEach { entry ->
            val server = entry.value ?: return@forEach
            val token = server.get("url")?.asText() ?: return@forEach
            val url = decryptUrl(token) ?: return@forEach
            if (!url.startsWith("http")) return@forEach
            val language = server.get("language")?.asText()?.takeIf { it.isNotBlank() }
            val isMp4 = server.get("type")?.asText() == "mp4"
            val name = if (language == null) {
                "Server 5 (VidRock) ${entry.key}"
            } else {
                "Server 5 (VidRock) ${entry.key} ($language)"
            }
            callback(
                newExtractorLink(name, name, url, type = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                    this.headers = mapOf(
                        "User-Agent" to OneFlexProvider.USER_AGENT,
                        "Referer" to PAGE
                    )
                }
            )
            found = true
        }

        if (found) emitSubtitles(path, subtitleCallback)
        return found
    }
}
