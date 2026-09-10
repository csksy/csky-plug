package com.csksy.anichan

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

object VidhawkResolver {

    private const val MAIN_URL = "https://vidhawk.buzz"
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    data class Result(
        val tracks: List<VidhawkTrack>,
        val captions: Map<String, List<VidhawkCaption>>
    ) {
        fun trackFor(audio: String): VidhawkTrack? =
            tracks.firstOrNull { it.id.equals(audio, true) && !it.src.isNullOrBlank() }
    }

    suspend fun resolve(anilistId: Int, ep: Int, audio: String, server: String): Result? {
        return try {
            val headers = mapOf(
                "User-Agent" to AniChanApi.BASE_HEADERS["User-Agent"]!!,
                "Referer" to "${AniChanApi.MAIN_URL}/"
            )
            val raceUrl = "$MAIN_URL/api/stream/race?episode=$ep&audio=$audio&server=$server" +
                "&anilistId=$anilistId&parentHost=anichan.net"
            val raceResp = app.get(raceUrl, headers = headers)
            val race = mapper.readValue(raceResp.text, VidhawkRace::class.java)

            val ticket = race.servers?.firstOrNull { it.id.equals(server, true) }?.ticket
                ?: race.ticket
                ?: return null

            val playResp = app.get(
                "$MAIN_URL/api/play?t=${URLEncoder.encode(ticket, "UTF-8")}",
                headers = headers
            )
            val play = mapper.readValue(playResp.text, VidhawkPlay::class.java)
            Result(play.tracks ?: emptyList(), play.captions ?: emptyMap())
        } catch (e: Exception) {
            null
        }
    }
}
