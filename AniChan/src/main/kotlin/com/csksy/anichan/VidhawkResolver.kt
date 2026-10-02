package com.csksy.anichan

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

data class VidhawkLink(val label: String, val src: String)

object VidhawkResolver {

    private const val MAIN_URL = "https://vidhawk.buzz"
    private const val TAG = "AniChan"
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    suspend fun resolveAll(anilistId: Int, ep: Int, audio: String, server: String): List<VidhawkLink> {
        return try {
            val headers = mapOf(
                "User-Agent" to AniChanApi.USER_AGENT,
                "Referer" to "${AniChanApi.MAIN_URL}/"
            )
            val raceUrl = "$MAIN_URL/api/stream/race?episode=$ep&audio=$audio&server=$server" +
                "&anilistId=$anilistId&parentHost=anichan.to"
            val raceResp = app.get(raceUrl, headers = headers)
            val race = mapper.readValue(raceResp.text, VidhawkRace::class.java)

            val candidates = race.servers?.filter { !it.ticket.isNullOrBlank() }
                ?.ifEmpty { listOfNotNull(race.ticket?.let { t -> VidhawkServer(ticket = t) }) }
                ?: emptyList()

            val wanted = if (audio.equals("dub", true)) listOf("dub", "hin") else listOf("sub")
            val links = mutableListOf<VidhawkLink>()
            for (candidate in candidates) {
                val playResp = app.get(
                    "$MAIN_URL/api/play?t=${URLEncoder.encode(candidate.ticket!!, "UTF-8")}",
                    headers = headers
                )
                val play = try {
                    mapper.readValue(playResp.text, VidhawkPlay::class.java)
                } catch (e: Exception) {
                    Log.d(TAG, "vidhawk play parse failed: ${e.message}")
                    continue
                }
                for (track in play.tracks.orEmpty()) {
                    val id = track.id?.lowercase() ?: continue
                    if (id !in wanted) continue
                    val src = track.src?.takeIf { it.startsWith("http") } ?: continue
                    val suffix = if (id == "hin") " Hindi" else ""
                    val serverTag = candidate.label?.takeIf { it.isNotBlank() } ?: candidate.id.orEmpty()
                    links.add(VidhawkLink("vidHawk ${serverTag}${suffix}".trim(), src))
                }
            }
            links
        } catch (e: Exception) {
            Log.d(TAG, "vidhawk resolve failed: ${e.message}")
            emptyList()
        }
    }
}
