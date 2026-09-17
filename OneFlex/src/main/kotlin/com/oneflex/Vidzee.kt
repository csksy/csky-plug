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

// core.vidzee.wtf answers plain unless e=1 is passed, the wrapped variant is
// rc4 with a 2048 byte drop and a key baked into the stream player wasm
object Vidzee {
    private const val TAG = "OneFlexVidzee"
    private const val API = "https://core.vidzee.wtf"
    private const val PAGE = "https://player.vidzee.wtf/"
    private const val WARMUP = 2048

    private val KEY = OneFlexProvider.hexBytes("e4f9b27d8c1a6ef5037db98ac54e21f0b9d6c3a781fe42ad65c0e9b73f148a2d")

    private val mapper = ObjectMapper()

    fun decryptBody(wrapped: String): String? {
        return try {
            val ct = Base64.decode(wrapped, Base64.DEFAULT)
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (KEY[i % KEY.size].toInt() and 0xFF)) and 0xFF
                val t = s[i]; s[i] = s[j]; s[j] = t
            }
            var a = 0
            var b = 0
            val out = ByteArray(ct.size)
            for (n in 0 until WARMUP + ct.size) {
                a = (a + 1) and 0xFF
                b = (b + s[a]) and 0xFF
                val t = s[a]; s[a] = s[b]; s[b] = t
                if (n >= WARMUP) {
                    val idx = n - WARMUP
                    out[idx] = (s[(s[a] + s[b]) and 0xFF] xor (ct[idx].toInt() and 0xFF)).toByte()
                }
            }
            String(out, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d(TAG, "body decrypt failed: ${e.message}")
            null
        }
    }

    private fun pathFor(type: String, id: String, season: Int?, episode: Int?): String =
        if (type == "tv" && season != null && episode != null) "tv/$id/$season/$episode" else "movie/$id"

    private suspend fun availableLanguages(path: String): List<String> {
        return try {
            val body = app.get("$API/streams/languages/$path", headers = OneFlexProvider.BASE_HEADERS).text
            mapper.readTree(body).get("languages")?.mapNotNull { it.asText() } ?: emptyList()
        } catch (e: Exception) {
            Log.d(TAG, "languages unavailable: ${e.message}")
            emptyList()
        }
    }

    private suspend fun emitSubtitles(path: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val body = app.get("$API/subs/$path", headers = OneFlexProvider.BASE_HEADERS).text
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

        // server ids and labels follow the list the embed player builds,
        // acme children come from the per title language set
        val servers = mutableListOf<Pair<String, String>>()
        servers.add("dcloud" to "Dcloud")
        availableLanguages(path).forEach { lang ->
            servers.add("v4:$lang" to "Acme $lang")
        }
        servers.add("tik" to "TCloud")
        servers.add("ipcloud" to "IPcloud")
        servers.add("v6:Hindi" to "Hindi v3")

        var found = false
        for ((serverId, serverLabel) in servers) {
            try {
                val body = app.get("$API/streams/$path?s=$serverId", headers = OneFlexProvider.BASE_HEADERS).text
                val payload = if (body.contains("\"c\"")) {
                    val wrapped = mapper.readTree(body).get("c")?.asText() ?: continue
                    decryptBody(wrapped) ?: continue
                } else {
                    body
                }
                val obj = mapper.readTree(payload)
                val url = obj.get("url")?.asText() ?: continue
                if (!url.startsWith("http")) continue
                val language = obj.get("language")?.asText()
                val suffix = when {
                    serverLabel.startsWith("Acme") || serverLabel.endsWith("v3") -> " (Multi Language)"
                    !language.isNullOrBlank() && language != "Auto" ->
                        " (${language.replaceFirstChar { it.uppercase() }})"
                    else -> ""
                }
                val name = "Server 6 (Vidzee) $serverLabel$suffix"
                callback(
                    newExtractorLink(name, name, url, type = ExtractorLinkType.M3U8) {
                        // the ngcorp cdn answers the dcloud streams only with the
                        // player origin present, the salsa cdns ignore it
                        this.headers = mapOf(
                            "User-Agent" to OneFlexProvider.USER_AGENT,
                            "Referer" to PAGE,
                            "Origin" to PAGE.trimEnd('/')
                        )
                    }
                )
                found = true
            } catch (e: Exception) {
                Log.w(TAG, "server $serverId failed: ${e.message}")
            }
        }

        if (found) emitSubtitles(path, subtitleCallback)
        return found
    }
}
