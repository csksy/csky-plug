package com.laddu100.anistream

import com.lagradost.cloudstream3.app
import java.net.URLEncoder

/**
 * VidHawk resolver (hawk provider).
 *
 * Flow (verified live — the CF challenge only guards the HTML page, /api is open):
 *  1. GET vidhawk.buzz/api/stream/race?episode={n}&audio={sub|dub}&server=kari
 *     &anilistId={id}&parentHost=anistream.one   (Referer anistream.one)
 *     → { winner, ticket, servers:[{id,label,category,ticket}] }
 *  2. GET vidhawk.buzz/api/play?t={ticket}
 *     → { tracks:[{id:"sub"|"dub", src:m3u8}], captions:{sub:[vtt], dub:[vtt]},
 *         intro, outro, server, serverLabel }
 *
 * Caption VTTs require an Origin header (any of vidhawk.buzz / anistream.one
 * works); the m3u8 itself is token-authenticated and header-free.
 */
object VidhawkResolver {

    const val MAIN_URL = "https://vidhawk.buzz"

    data class Result(
        val tracks: List<VidhawkTrack>,
        val captions: Map<String, List<VidhawkCaption>>,
        val intro: SkipTime?,
        val outro: SkipTime?,
        val server: String?,
        val serverLabel: String?
    ) {
        fun trackFor(audio: String): VidhawkTrack? =
            tracks.firstOrNull { it.id.equals(audio, true) }
                ?: tracks.firstOrNull { it.src?.isNotBlank() == true }
    }

    /** Resolve all servers for an episode; falls back through the ticket list. */
    suspend fun resolve(anilistId: Int, epNum: Int, audio: String, server: String = "kari"): Result? {
        return try {
            val raceUrl = buildString {
                append("$MAIN_URL/api/stream/race?")
                append("episode=$epNum&audio=$audio&server=$server")
                append("&anilistId=$anilistId&parentHost=anistream.one")
            }
            val race = app.get(
                raceUrl,
                headers = mapOf(
                    "User-Agent" to AnistreamApi.USER_AGENT,
                    "Referer" to "${AnistreamApi.MAIN_URL}/",
                    "Accept" to "application/json, text/plain, */*"
                )
            ).parsedSafe<VidhawkRace>() ?: return null

            val ticket = race.servers.firstOrNull { it.id.equals(server, true) }?.ticket
                ?: race.ticket
                ?: return null

            val play = app.get(
                "$MAIN_URL/api/play?t=${URLEncoder.encode(ticket, "UTF-8")}",
                headers = mapOf(
                    "User-Agent" to AnistreamApi.USER_AGENT,
                    "Referer" to "${AnistreamApi.MAIN_URL}/",
                    "Accept" to "application/json, text/plain, */*"
                )
            ).parsedSafe<VidhawkPlay>() ?: return null

            Result(
                tracks = play.tracks,
                captions = play.captions.orEmpty(),
                intro = play.intro,
                outro = play.outro,
                server = play.server,
                serverLabel = play.serverLabel
            )
        } catch (e: Exception) {
            null
        }
    }
}
