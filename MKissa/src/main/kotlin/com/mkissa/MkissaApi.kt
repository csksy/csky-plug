package com.mkissa

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

internal object MkissaApi {

    private const val TAG = "MKISSA"
    private const val API = "https://api.mkissa.net/api"
    private const val BOOTSTRAP = "https://api.mkissa.net/client-crypto/v1/bootstrap"
    private const val LANE = "k7"

    internal const val BROWSE_HASH = "8b319a0fda488e4319f1b6d99093ba12802f3fc039572f39bcc02d5f9b9d9b02"
    internal const val EPISODE_HASH = "670bbf38d0868f446e2346c1e956ca2c40c416e733ca248fd54e04f1c8b99145"
    internal const val SHOW_DETAIL_HASH = "c6c067496f962fba87c7aaf6c215a40a6d3933c69012bd15e4d8949e58f3c010"
    internal const val LIST_FOR_TAG_HASH = "3a3a6508363a381b20d2e10e22756bcea54cc17bb3ccafc156e5980d34084cd8"
    internal const val TRENDING_HASH = "c947693e2a04dfa9074df5ec01c6c5209fc9f9556f3e5d420dbca97f9d1b6d98"
    internal const val HOME_HASH = "cda3063b249be5ee6f30d3942cedd7e22739be3e09a24eb9ec9dc703246b9656"

    internal const val EPISODE_INFOS_QUERY =
        "query(\$showId: String!,\$episodeNumStart: Float!,\$episodeNumEnd: Float!){episodeInfos(showId: \$showId,episodeNumStart: \$episodeNumStart,episodeNumEnd: \$episodeNumEnd){episodeIdNum notes vidInforssub vidInforsdub vidInforsraw}}"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://mkissa.to",
        "Referer" to "https://mkissa.to/",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "cross-site",
        "x-build-id" to MkissaCrypto.BUILD_ID,
    )

    internal class Session {
        var epoch: Long = 0
        var key: ByteArray = ByteArray(0)
        var fetchedAt: Long = 0
    }

    @Volatile
    private var session: Session? = null
    private val sessionMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun fetchBootstrap(): Session? {
        return try {
            val epoch = MkissaCrypto.currentEpoch()
            val url = "$BOOTSTRAP?buildId=${MkissaCrypto.BUILD_ID}&k=$LANE"
            val h = headers.toMutableMap()
            h["x-aa-boot"] = MkissaCrypto.xAaBoot(epoch, LANE)
            val body = app.get(url, headers = h, timeout = 20_000L).text
            val obj = JSONObject(body)
            val s = Session()
            s.epoch = obj.optLong("epoch", epoch)
            s.key = MkissaCrypto.deriveKey(obj.getString("partB"))
            s.fetchedAt = System.currentTimeMillis()
            s
        } catch (e: Exception) {
            Log.d(TAG, "bootstrap failed: ${e.message}")
            null
        }
    }

    private suspend fun currentSession(force: Boolean = false): Session? {
        val cached = session
        if (!force && cached != null &&
            System.currentTimeMillis() - cached.fetchedAt < 60 * 60_000L &&
            cached.key.isNotEmpty()
        ) return cached
        return sessionMutex.withLock {
            val again = session
            if (!force && again != null &&
                System.currentTimeMillis() - again.fetchedAt < 60 * 60_000L &&
                again.key.isNotEmpty()
            ) return@withLock again
            val fresh = fetchBootstrap()
            if (fresh != null) session = fresh
            fresh
        }
    }

    private fun buildUrl(variables: JSONObject, extensions: JSONObject): String {
        return API + "?variables=" + URLEncoder.encode(variables.toString(), "UTF-8") +
                "&extensions=" + URLEncoder.encode(extensions.toString(), "UTF-8")
    }

    internal suspend fun queryByHash(hash: String, variables: JSONObject): JSONObject? {
        return try {
            val ext = JSONObject()
            val pq = JSONObject()
            pq.put("version", 1)
            pq.put("sha256Hash", hash)
            ext.put("persistedQuery", pq)
            val body = app.get(buildUrl(variables, ext), headers = headers, timeout = 25_000L).text
            JSONObject(body)
        } catch (e: Exception) {
            Log.d(TAG, "queryByHash failed: ${e.message}")
            null
        }
    }

    internal suspend fun queryByText(queryText: String, variables: JSONObject): JSONObject? {
        return try {
            val payload = JSONObject()
            payload.put("query", queryText)
            payload.put("variables", variables)
            val h = headers.toMutableMap()
            h["Content-Type"] = "application/json"
            val requestBody = payload.toString().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            val body = app.post(API, headers = h, requestBody = requestBody, timeout = 25_000L).text
            JSONObject(body)
        } catch (e: Exception) {
            Log.d(TAG, "queryByText failed: ${e.message}")
            null
        }
    }

    internal suspend fun episodeQuery(
        showId: String,
        translationType: String,
        episodeString: String
    ): JSONObject? {
        val variables = JSONObject()
        variables.put("showId", showId)
        variables.put("translationType", translationType)
        variables.put("episodeString", episodeString)

        var s = currentSession() ?: return null

        suspend fun run(aaReq: String?, captchaToken: String?): Pair<Int, String> {
            val ext = JSONObject()
            val pq = JSONObject()
            pq.put("version", 1)
            pq.put("sha256Hash", EPISODE_HASH)
            ext.put("persistedQuery", pq)
            ext.put("k", LANE)
            if (aaReq != null) ext.put("aaReq", aaReq)
            if (captchaToken != null) {
                val cap = JSONObject()
                cap.put("token", captchaToken)
                cap.put("provider", "turnstile1")
                ext.put("captcha", cap)
            }
            val resp = app.get(buildUrl(variables, ext), headers = headers, timeout = 25_000L)
            return resp.code to resp.text
        }

        try {
            var (code, body) = run(MkissaCrypto.buildAaReq(s.key, EPISODE_HASH, LANE, s.epoch), null)

            if (body.contains("NEED_CAPTCHA")) {
                Log.d(TAG, "episode query needs captcha - opening turnstile")
                val token = MkissaWeb.solveTurnstile()
                if (token != null) {
                    val second = run(
                        MkissaCrypto.buildAaReq(s.key, EPISODE_HASH, LANE, s.epoch),
                        token
                    )
                    code = second.first
                    body = second.second
                }
            }

            if (body.contains("NEED_CAPTCHA")) {
                s = currentSession(force = true) ?: return null
                val retry = run(MkissaCrypto.buildAaReq(s.key, EPISODE_HASH, LANE, s.epoch), null)
                code = retry.first
                body = retry.second
                if (body.contains("NEED_CAPTCHA")) {
                    val token = MkissaWeb.solveTurnstile()
                    if (token != null) {
                        val third = run(
                            MkissaCrypto.buildAaReq(s.key, EPISODE_HASH, LANE, s.epoch),
                            token
                        )
                        code = third.first
                        body = third.second
                    }
                }
            }

            if (code !in 200..299 && !body.trimStart().startsWith("{")) return null

            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return null
            val inner = data.optJSONObject("episode")
            val toBeParsed = if (inner != null && inner.has("tobeparsed")) {
                inner.optString("tobeparsed")
            } else {
                data.optString("tobeparsed")
            }
            if (toBeParsed.isBlank()) {
                return inner
            }
            val decrypted = MkissaCrypto.decryptPayload(toBeParsed, s.key)
            val dec = JSONObject(decrypted)
            return dec.optJSONObject("episode") ?: dec
        } catch (e: Exception) {
            Log.d(TAG, "episodeQuery failed: ${e.message}")
            return null
        }
    }

    internal suspend fun episodeNames(
        showId: String,
        startNum: Int,
        endNum: Int
    ): Map<Int, String> {
        val out = mutableMapOf<Int, String>()
        if (endNum < startNum) return out
        val variables = JSONObject()
        variables.put("showId", showId)
        variables.put("episodeNumStart", startNum.toDouble())
        variables.put("episodeNumEnd", endNum.toDouble())
        val root = queryByText(EPISODE_INFOS_QUERY, variables) ?: return out
        val infos = root.optJSONObject("data")?.optJSONArray("episodeInfos") ?: return out
        for (i in 0 until infos.length()) {
            val e = infos.optJSONObject(i) ?: continue
            val num = e.optInt("episodeIdNum", -1)
            val notes = e.optString("notes").takeIf { it.isNotBlank() && it != "null" }
            if (num > 0 && notes != null) out[num] = notes
        }
        return out
    }

    internal suspend fun resolveClockLinks(decodedPath: String): JSONArray? {
        return try {
            val marked = decodedPath.replace("/apivtwo/clock?", "/apivtwo/clock.json?")
            val url = if (marked.startsWith("http")) marked else "https://filelotion.fyi$marked"
            val h = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*",
                "Referer" to "https://filelotion.fyi/player.html",
            )
            val body = app.get(url, headers = h, timeout = 25_000L).text
            JSONObject(body).optJSONArray("links")
        } catch (e: Exception) {
            Log.d(TAG, "clock resolve failed: ${e.message}")
            null
        }
    }
}
