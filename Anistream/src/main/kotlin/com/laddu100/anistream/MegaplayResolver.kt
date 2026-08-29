package com.laddu100.anistream

import android.util.Base64
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MegaPlay resolver (minky provider; yuki/beep share the same backend).
 *
 * Flow (verified live):
 *  1. GET megaplay.buzz/stream/ani/{anilistId}/{ep}/{sub|dub}
 *     with Referer https://anistream.one/  (without it the player 410s)
 *  2. data-id attr from #megaplay-player
 *  3. GET megaplay.buzz/stream/getSources?id={dataId} (Referer = embed page)
 *     → { sources{file}, tracks[]{file,label,kind}, intro{start,end}, outro }
 *
 * Some playlists embed AES-encrypted `/segment/{base64url}` URLs; those are
 * decrypted with the static key/IV found in megaplay's newclient.min.js.
 *
 * v2: requests go through AnistreamHttp (Cloudflare retry + DoH + cookies).
 */
object MegaplayResolver {

    const val MAIN_URL = "https://megaplay.buzz"
    private val mapper = ObjectMapper()

    data class Result(
        val m3u8: String,
        val tracks: List<TrackFile>,
        val intro: SkipTime?,
        val outro: SkipTime?
    )

    suspend fun resolve(anilistId: Int, epNum: Int, audio: String): Result? {
        return try {
            val embedUrl = "$MAIN_URL/stream/ani/$anilistId/$epNum/$audio"
            val page = AnistreamHttp.get(
                embedUrl,
                referer = "${AnistreamApi.MAIN_URL}/"
            )

            val dataId = Regex("""data-id="([^"]+)"""").find(page)?.groupValues?.get(1)
                ?: Regex("""data-realid="([^"]+)"""").find(page)?.groupValues?.get(1)
                ?: return null
            if (page.contains("error-code") || page.contains("Error Code")) return null

            val resp = AnistreamHttp.get(
                "$MAIN_URL/stream/getSources?id=$dataId",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = embedUrl
            ).let { mapper.readValue<MegaplayResponse>(it) }

            val raw = resp.sources?.file ?: return null
            val m3u8 = decryptSegmentUrls(raw)
            Result(
                m3u8 = m3u8,
                tracks = resp.tracks,
                intro = resp.intro,
                outro = resp.outro
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * If the playlist text contains /segment/{token} entries, decrypt each with
     * AES-256-CBC (key "i?LMTAx0Q6,:}50U" zero-padded to 32 bytes, IV
     * "W0;27ToaUpl_P%'c"). Tokens are base64url. Returns the playlist text
     * with real URLs substituted (plain when no segment tokens exist).
     */
    fun decryptSegmentUrls(playlistUrl: String): String {
        // Only relevant for playlist TEXT; when handed a master URL that itself
        // is not a segment URL we return it untouched.
        if (!playlistUrl.contains("/segment/")) return playlistUrl
        return try {
            val keyBytes = ByteArray(32)
            val k = "i?LMTAx0Q6,:}50U".toByteArray(Charsets.UTF_8)
            System.arraycopy(k, 0, keyBytes, 0, k.size)
            val iv = "W0;27ToaUpl_P%'c".toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            Regex("""/segment/([A-Za-z0-9_-]+)""").replace(playlistUrl) { m ->
                val tok = m.groupValues[1]
                    .replace("-", "+").replace("_", "/")
                    .let { it + "====".substring(it.length % 4) }
                try {
                    String(cipher.doFinal(Base64.decode(tok, Base64.DEFAULT)), Charsets.UTF_8).trim()
                } catch (e: Exception) {
                    m.value
                }
            }
        } catch (e: Exception) {
            playlistUrl
        }
    }
}
