package com.vidcloud

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * Network layer for Study Stark (vidcloud.eu.org). Every route, header and
 * payload shape is lifted from the site's own traffic:
 *
 *  catalog     studystark.github.io/batches/batches.json - the full batch list
 *  batches     vidcloud.eu.org/api/v3/batches/<id>/details
 *  topics      vidcloud.eu.org/api/v2/batches/<id>/subject/<sid>/topics
 *  contents    vidcloud.eu.org/api/v2/batches/<study batch>/subject/<sid>/contents
 *              the study batch is a fixed id the site uses for every batch and
 *              subject, the page batch does not carry the study material
 *  dpp tests   vidcloud.eu.org/api/v3/test-service/tests/new-dpp-list
 *
 * The site's edge refuses plain http clients on the api paths, so every api
 * read goes three deep: plain, with the webview's harvested session, and
 * finally from inside the site's own page where the request rides the
 * webview's browser stack. Whatever happens, the real reason travels back
 * out - a blocked read is never an empty one.
 *
 * play.php encrypts the stream url and the drm fields into hidden inputs
 * before the page ships; the player script walks them back with a fixed
 * aes key so the same walk is mirrored here.
 */
object VidCloudApi {

    const val SITE = "https://vidcloud.eu.org"
    const val CDN = "https://static.pw.live"

    private const val BATCHES_URL = "https://studystark.github.io/batches/batches.json"

    const val STUDY_BATCH = "634bd315ed7a360018558283"

    // aes pair baked into the site's player script
    private val PLAY_KEY = "R7@kP4#xL9!mQ2\$v".toByteArray()
    private val PLAY_IV = "T3!nW8\$qZ5@rK1#p".toByteArray()

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

    val randomId = UUID.randomUUID().toString()

    // ------------------------------------------------------------- results

    sealed class SiteResult {
        class Ok(val json: JSONObject) : SiteResult()
        class Fail(val reason: String) : SiteResult()
    }

    private fun okJson(text: String): SiteResult? {
        if (text.isBlank()) return null
        return try {
            SiteResult.Ok(JSONObject(text))
        } catch (_: Exception) {
            null
        }
    }

    private fun apiHeaders(ua: String, cookie: String, token: String): Map<String, String> {
        val out = mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$SITE/",
            "Origin" to SITE,
            "Randomid" to randomId,
            "Client-Type" to "WEB",
            "Client-Id" to "5eb393ee95fab7468a79d189",
            "Client-Version" to "2.1.6",
            "Version" to "0.0.1",
        )
        if (token.isNotBlank()) out["Authorization"] = "Bearer $token"
        if (cookie.isNotBlank()) out["Cookie"] = cookie
        return out
    }

    // the transport that answered last goes first the next time; a transport
    // that stops answering resets it
    @Volatile
    private var fastLane = 0

    /**
     * One api read, three transports deep. The note on a Fail carries what
     * each transport actually saw so the surface can show the real reason.
     */
    suspend fun siteJson(path: String): SiteResult {
        val order = when (fastLane) {
            3 -> listOf(3, 1, 2)
            2 -> listOf(2, 1, 3)
            else -> listOf(1, 2, 3)
        }
        val findings = mutableListOf<String>()
        for (lane in order) {
            when (lane) {
                1 -> {
                    try {
                        val resp = app.get(SITE + path, headers = apiHeaders(UA, "", ""), timeout = 10L)
                        if (resp.isSuccessful) {
                            okJson(resp.text)?.let { fastLane = 1; return it }
                            findings.add("plain: not json")
                        } else findings.add("plain: ${resp.code}")
                    } catch (e: Exception) {
                        findings.add("plain: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                2 -> {
                    val jar = VidCloudWebView.cookies()
                    val ua = VidCloudWebView.userAgent() ?: UA
                    if (jar.isNullOrBlank()) {
                        findings.add("session: none yet")
                        continue
                    }
                    try {
                        val resp = app.get(
                            SITE + path,
                            headers = apiHeaders(ua, jar, VidCloudWebView.token() ?: ""),
                            timeout = 10L
                        )
                        if (resp.isSuccessful) {
                            okJson(resp.text)?.let { fastLane = 2; return it }
                            findings.add("session: not json")
                        } else findings.add("session: ${resp.code}")
                    } catch (e: Exception) {
                        findings.add("session: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                3 -> {
                    val got = VidCloudWebView.fetch(path)
                    if (got == null) {
                        findings.add("page: no webview")
                        continue
                    }
                    val (status, body) = got
                    if (status in 200..299) {
                        okJson(body)?.let { fastLane = 3; return it }
                        findings.add("page: not json")
                    } else findings.add("page: $status")
                }
            }
        }
        if (fastLane != 0) fastLane = 0
        return SiteResult.Fail(findings.distinct().joinToString("; ").ifBlank { "no transport answered" })
    }

    // ------------------------------------------------------------- catalog

    private const val BATCHES_TTL = 30L * 60L * 1000L

    @Volatile
    private var batchesCache: List<JSONObject> = emptyList()

    @Volatile
    private var batchesAt = 0L

    /**
     * The catalog mirror is a plain static file, so a failure here is a real
     * failure and travels out instead of an empty page.
     */
    suspend fun batches(): List<JSONObject> {
        val now = System.currentTimeMillis()
        if (batchesCache.isNotEmpty() && now - batchesAt < BATCHES_TTL) return batchesCache
        val text = try {
            app.get(BATCHES_URL, headers = mapOf("User-Agent" to UA), timeout = 30L).text
        } catch (e: Exception) {
            throw SiteException("catalog unreachable: ${e.message ?: e.javaClass.simpleName}")
        }
        val arr = try {
            JSONObject(text).optJSONArray("data")
        } catch (_: Exception) {
            null
        } ?: throw SiteException("catalog unreadable")
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            if (b.optString("batch_id").isBlank() || b.optString("name").isBlank()) continue
            out.add(b)
        }
        batchesCache = out
        batchesAt = now
        return out
    }

    class SiteException(message: String) : Exception(message)

    // ------------------------------------------------------------- study api

    private const val DETAILS_TTL = 10L * 60L * 1000L
    private val detailsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, SiteResult.Ok>>()

    suspend fun details(id: String): SiteResult {
        detailsCache[id]?.let { (at, ok) ->
            if (System.currentTimeMillis() - at < DETAILS_TTL) return ok
        }
        return when (val r = siteJson("/api/v3/batches/$id/details?type=EXPLORE_LEAD")) {
            is SiteResult.Ok -> {
                detailsCache[id] = System.currentTimeMillis() to r
                r
            }
            is SiteResult.Fail -> r
        }
    }

    suspend fun topics(batchId: String, subjectId: String): SiteResult =
        siteJson("/api/v2/batches/$batchId/subject/$subjectId/topics?page=1")

    suspend fun contents(subjectId: String, topicId: String, contentType: String): SiteResult =
        siteJson(
            "/api/v2/batches/$STUDY_BATCH/subject/$subjectId/contents" +
                "?tag=$topicId&contentType=$contentType&page=1"
        )

    suspend fun dppTests(batchId: String, subjectId: String, topicId: String): SiteResult =
        siteJson(
            "/api/v3/test-service/tests/new-dpp-list" +
                "?page=1&batchId=$batchId&batchSubjectId=$subjectId&chapterId=$topicId&dppType=ALL&limit=20"
        )

    fun dataArrayOf(r: SiteResult): List<JSONObject> = when (r) {
        is SiteResult.Fail -> emptyList()
        is SiteResult.Ok -> {
            val arr = r.json.optJSONArray("data") ?: return emptyList()
            val out = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(it) }
            }
            out
        }
    }

    // ------------------------------------------------------------- lectures

    class Lecture(
        val videoId: String,
        val typeId: String,
        val url: String,
        val name: String,
        val image: String,
        val duration: String,
    )

    fun toLecture(item: JSONObject): Lecture? {
        val url = item.optString("url").takeIf { it.startsWith("http") } ?: return null
        val details = item.optJSONObject("videoDetails")
        return Lecture(
            videoId = item.optString("_id"),
            typeId = details?.optString("id")?.takeIf { it.isNotBlank() } ?: details?.optString("_id") ?: "",
            url = url,
            name = item.optString("topic").takeIf { it.isNotBlank() }
                ?: details?.optString("name")?.takeIf { it.isNotBlank() }
                ?: "Lecture",
            image = details?.optString("image")?.takeIf { it.startsWith("http") } ?: "",
            duration = details?.optString("duration")?.takeIf { it.isNotBlank() } ?: "",
        )
    }

    /**
     * The player page the site's own links open: every field the page asks
     * for comes from the contents item, exactly the set the site sends. The
     * site builds it with URLSearchParams so spaces ride as +, which is what
     * URLEncoder produces.
     */
    fun playUrl(batchId: String, subjectId: String, topicId: String, lecture: Lecture): String {
        fun q(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$SITE/play.php" +
            "?batch_id=$batchId" +
            "&program_id=" +
            "&subject_id=$subjectId" +
            "&topic_id=$topicId" +
            "&video_id=${q(lecture.videoId)}" +
            "&typeId=${q(lecture.typeId)}" +
            "&video_url=${q(lecture.url)}" +
            "&video_name=${q(lecture.name)}" +
            "&video_img=${q(lecture.image)}" +
            "&video_type=new" +
            "&play_type=Lecture"
    }

    class PlayPayload(
        val url: String,
        val drmType: String,
        val keyId: String,
        val keyValue: String,
        val licenseUrl: String,
    )

    /**
     * Walks play.php's hidden inputs back to plain values. The page encrypts
     * each one with the same key the player script holds, empty fields stay
     * empty.
     */
    suspend fun resolvePlay(playUrl: String): PlayPayload? {
        val got = VidCloudWebView.fetch(playUrl.removePrefix(SITE)) ?: return null
        val (status, html) = got
        if (status !in 200..299) return null
        fun field(id: String): String {
            val m = Regex("id=\"$id\"\\s+value=\"([^\"]*)\"").find(html) ?: return ""
            return fieldOf(m.groupValues[1])
        }
        val url = field("enc_video_url")
        if (url.isBlank()) return null
        return PlayPayload(
            url = url,
            drmType = field("enc_drm_type"),
            keyId = field("enc_key_id"),
            keyValue = field("enc_key_value"),
            licenseUrl = field("enc_license_url"),
        )
    }

    fun decryptField(b64: String): String {
        if (b64.isBlank()) return ""
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(PLAY_KEY, "AES"), IvParameterSpec(PLAY_IV))
            String(cipher.doFinal(bytes), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun fieldOf(raw: String): String {
        val unescaped = raw
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
        return decryptField(unescaped)
    }

    // ------------------------------------------------------------- pdfs

    class PdfRef(
        val title: String,
        val tag: String,
        val viewerUrl: String,
        val directUrl: String,
        val fileName: String,
    )

    /**
     * Notes and DPP sheets come as homework attachments. The site opens them
     * through its pdf.js page with the attachment's own base url, the file
     * itself always lives on static.pw.live so the download goes there.
     */
    fun toPdfRefs(contentItems: List<JSONObject>): List<PdfRef> {
        val out = mutableListOf<PdfRef>()
        for (item in contentItems) {
            val homework = item.optJSONArray("homeworkIds") ?: continue
            for (h in 0 until homework.length()) {
                val hw = homework.optJSONObject(h) ?: continue
                val attachments = hw.optJSONArray("attachmentIds") ?: continue
                for (a in 0 until attachments.length()) {
                    val att = attachments.optJSONObject(a) ?: continue
                    val base = att.optString("baseUrl").takeIf { it.isNotBlank() } ?: continue
                    val key = att.optString("key").takeIf { it.isNotBlank() } ?: continue
                    val full = base + key
                    val direct = if (base.contains("static.pw.live")) "$CDN/$key" else full
                    val name = att.optString("name").takeIf { it.isNotBlank() }
                        ?: hw.optString("topic").takeIf { it.isNotBlank() }
                        ?: key.substringAfterLast('/')
                    out.add(
                        PdfRef(
                            title = hw.optString("topic").takeIf { it.isNotBlank() } ?: name,
                            tag = hw.optString("note").takeIf { it.isNotBlank() } ?: "Notes",
                            // the site's own links pass the attachment url raw
                            // in the s parameter, mirrored here
                            viewerUrl = "$SITE/view-pdf.html?s=$full",
                            directUrl = direct,
                            fileName = name.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").trim(),
                        )
                    )
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------- quizzes

    /**
     * The site's quiz page carries the whole test - questions, options,
     * solutions and the timer - so the quiz runs in the page itself, the
     * same url the site's Start button opens. Its links are built with
     * encodeURIComponent, so spaces stay percent-encoded here.
     */
    fun quizUrl(
        testId: String,
        testName: String,
        batchId: String,
        subjectId: String,
        subjectName: String,
        topicId: String,
        topicName: String,
    ): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "$SITE/start-dpp-test/" +
            "?test_id=${e(testId)}" +
            "&batch_id=${e(batchId)}" +
            "&subject_id=${e(subjectId)}" +
            "&subject_name=${e(subjectName)}" +
            "&topic_id=${e(topicId)}" +
            "&topic_name=${e(topicName)}" +
            "&test_name=${e(testName)}"
    }

    fun testOf(entry: JSONObject): JSONObject? =
        entry.optJSONObject("dppQuizDetails")?.optJSONObject("test")
}
