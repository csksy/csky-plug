package com.laddu100.raghavanime

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class RaghavAniChan : MainAPI() {
    override var mainUrl = "https://anichan.to"
    override var name = "AniChan"
    override val hasMainPage = false
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServersEnvelope(
        @JsonProperty("servers") val servers: List<Server>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SealedEnvelope(
        @JsonProperty("v") val v: Int? = null,
        @JsonProperty("i") val i: String? = null,
        @JsonProperty("d") val d: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Server(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("stream") val stream: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("subType") val subType: String? = null,
        @JsonProperty("subtitles") val subtitles: List<Subtitle>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Subtitle(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidhawkRace(
        @JsonProperty("ticket") val ticket: String? = null,
        @JsonProperty("servers") val servers: List<VidhawkServer>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidhawkServer(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("ticket") val ticket: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidhawkPlay(
        @JsonProperty("tracks") val tracks: List<VidhawkTrack>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidhawkTrack(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("src") val src: String? = null
    )

    private class WatchKeys(val wk: String, val k1: String, val k2: String)

    private class WatchSession(val cookie: String, val nonce: String)

    suspend fun loadLinksByAnilistId(
        anilistId: Int,
        episode: Int,
        isDub: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val category = if (isDub) "dub" else "sub"

        val servers = watchServers(anilistId, episode, category)
        if (servers.isEmpty()) {
            return false
        }

        val linkHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )
        val seenSubs = HashSet<String>()
        var found = false

        for (server in servers) {
            val label = serverLabel(server, isDub)
            if (server.type == "embed") {
                val embed = server.embed ?: continue
                val vidServer = Regex("[?&]server=([^&]+)").find(embed)?.groupValues?.get(1)
                    ?: "kari"
                val track = vidhawkResolve(anilistId, episode, category, vidServer)
                    ?.firstOrNull { it.id.equals(category, true) && !it.src.isNullOrBlank() }
                    ?: continue
                val src = track.src?.takeIf { it.startsWith("http") } ?: continue
                callback.invoke(
                    newExtractorLink(name, label, src, type = ExtractorLinkType.M3U8) {
                        this.headers = linkHeaders
                    }
                )
                found = true
            } else {
                val stream = server.stream?.takeIf { it.startsWith("http") }
                    ?: server.stream?.takeIf { it.startsWith("/") }?.let { "$mainUrl$it" }
                    ?: continue
                callback.invoke(
                    newExtractorLink(name, label, stream, type = ExtractorLinkType.M3U8) {
                        this.headers = linkHeaders
                    }
                )
                found = true
            }

            for (sub in server.subtitles.orEmpty()) {
                val url = sub.url?.takeIf { it.startsWith("http") } ?: continue
                val lang = sub.lang ?: "English"
                if (seenSubs.add(lang)) {
                    subtitleCallback.invoke(SubtitleFile(lang, url))
                }
            }
        }
        return found
    }

    private suspend fun newWatchSession(): WatchSession? {
        return try {
            val resp = app.post(
                "$mainUrl/api/watch/session",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Origin" to mainUrl
                ),
                json = mapOf("token" to "")
            )
            if (!resp.isSuccessful) return null
            val nonce = try {
                mapper.readTree(resp.text).get("n")?.asText()
            } catch (e: Exception) {
                null
            }
            val cookie = resp.headers.values("set-cookie")
                .firstOrNull { it.startsWith("anichan_ws=") }
                ?.substringBefore(";")?.substringAfter("anichan_ws=")
            if (nonce.isNullOrBlank() || cookie.isNullOrBlank()) null else WatchSession(cookie, nonce)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun watchServers(anilistId: Int, ep: Int, category: String): List<Server> {
        val base = "$mainUrl/api/watch/servers?anilistId=$anilistId&ep=$ep&category=$category"
        repeat(SESSION_ATTEMPTS) {
            val ws = newWatchSession()
            if (ws != null) {
                // the list comes back sealed, the key version is named by the X-Wk header
                // so the current bundle keys go first and the previous ones back them up,
                // both tiers are needed because the fast one drops servers under load
                for (keys in KEY_SETS) {
                    val merged = ArrayList<Server>()
                    var opened = false
                    for (tier in listOf("fast", "rest")) {
                        try {
                            val resp = app.get(
                                "$base&tier=$tier",
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Accept" to "application/json",
                                    "Cookie" to "anichan_ws=${ws.cookie}",
                                    "X-Wk" to keys.wk
                                )
                            )
                            if (resp.isSuccessful) {
                                decryptServers(resp.text, ws.nonce, keys)?.let {
                                    merged.addAll(it)
                                    // an empty answer also matches an unknown key version,
                                    // only a real list proves the key set is still honoured
                                    if (it.isNotEmpty()) opened = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("RaghavAnimeKitsu", "[AniChan] servers attempt failed: ${e.message}")
                        }
                    }
                    if (opened) {
                        return merged.distinctBy { listOf(it.name, it.label, it.type, it.stream, it.embed).joinToString("|") }
                    }
                }
            }
            delay(400)
        }
        return emptyList()
    }

    // sealed shape is {v: 1, i: iv, d: ciphertext}, the key is an hmac of the session
    // nonce under the bundle key pair, used directly as the aes-gcm key
    private fun decryptServers(body: String, nonce: String, keys: WatchKeys): List<Server>? {
        return try {
            val sealed = mapper.readValue(body, SealedEnvelope::class.java)
            if (sealed.v != 1 || sealed.d.isNullOrBlank() || sealed.i.isNullOrBlank()) {
                return mapper.readValue(body, ServersEnvelope::class.java).servers
            }
            val k1 = Base64.decode(keys.k1, Base64.DEFAULT)
            val k2 = Base64.decode(keys.k2, Base64.DEFAULT)
            val x = ByteArray(k1.size) { (k1[it].toInt() xor k2[it].toInt()).toByte() }
            val mac = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(x, "HmacSHA256"))
                doFinal(nonce.toByteArray())
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(mac, "AES"),
                GCMParameterSpec(128, Base64.decode(sealed.i, Base64.DEFAULT))
            )
            val plain = cipher.doFinal(Base64.decode(sealed.d, Base64.DEFAULT))
            mapper.readValue(plain, ServersEnvelope::class.java).servers
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun vidhawkResolve(
        anilistId: Int,
        ep: Int,
        audio: String,
        server: String
    ): List<VidhawkTrack>? {
        return try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
            val raceUrl = "https://vidhawk.buzz/api/stream/race?episode=$ep&audio=$audio&server=$server" +
                "&anilistId=$anilistId&parentHost=anichan.to"
            val raceResp = app.get(raceUrl, headers = headers, timeout = 12L)
            val race = mapper.readValue(raceResp.text, VidhawkRace::class.java)

            val ticket = race.servers?.firstOrNull { it.id.equals(server, true) }?.ticket
                ?: race.ticket
                ?: return null

            val playResp = app.get(
                "https://vidhawk.buzz/api/play?t=${URLEncoder.encode(ticket, "UTF-8")}",
                headers = headers,
                timeout = 10L
            )
            mapper.readValue(playResp.text, VidhawkPlay::class.java).tracks ?: emptyList()
        } catch (e: Exception) {
            null
        }
    }

    private fun serverLabel(server: Server, isDub: Boolean): String {
        val raw = (server.label ?: server.name ?: "AniChan")
            .replace("★", "")
            .replace(Regex("\\s*⧉\\s*\\(ads\\)"), "")
            .trim()
        val hardsub = server.subType.equals("hard", true)
        return when {
            hardsub -> "$raw (Hardsub)"
            isDub -> "$raw (Dub)"
            else -> raw
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val SESSION_ATTEMPTS = 4
        private val KEY_SETS = listOf(
            WatchKeys(
                "6be19f72",
                "wuEoOR48/o2oD14z71nB9MG2eC3T5q+uayZj5fb3Xsk=",
                "0Avpb+a3t0ihtp6DLmBkfB3wStHog0zOAhKALhnfeC4="
            ),
            WatchKeys(
                "9e04528d",
                "9jwvrqLYo5sLdkMzuLA7g7u2+jqd250K9E+tFkQTb1A=",
                "rv19AhQepRkeSdTetZolkfigCmWZyMITmGuhZ5cMHUI="
            )
        )
    }
}
