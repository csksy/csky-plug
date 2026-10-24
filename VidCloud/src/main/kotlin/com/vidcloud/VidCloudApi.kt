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
 * The api sits behind the site's php session: / hands out PHPSESSID,
 * generate_token.php mints the bearer the proxy wants, and both are replayed
 * on every api call together with the client headers the site sends.
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

    private val randomId = UUID.randomUUID().toString()

    @Volatile
    private var sessionCookie = ""

    @Volatile
    private var bearer = ""

    @Volatile
    private var tokenAt = 0L

    private const val TOKEN_TTL = 10L * 60L * 1000L

    /**
     * A live php session: / seeds PHPSESSID, generate_token.php exchanges it
     * for the bearer. Anything that fails is left empty - the api answers
     * for the parts it does not gate on it.
     */
    private suspend fun ensureSession(force: Boolean = false) {
        if (!force && bearer.isNotBlank() && System.currentTimeMillis() - tokenAt < TOKEN_TTL) return
        try {
            val root = app.get(
                SITE,
                headers = mapOf("User-Agent" to UA, "Accept" to "text/html", "Referer" to "$SITE/"),
                timeout = 15L
            )
            val sess = root.headers.values("Set-Cookie")
                .mapNotNull { Regex("PHPSESSID=([a-z0-9]+)").find(it)?.groupValues?.get(1) }
                .firstOrNull()
            if (sess != null) sessionCookie = "PHPSESSID=$sess"
        } catch (_: Exception) {
        }
        try {
            val tokenText = app.get(
                "$SITE/generate_token.php",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "*/*",
                    "Referer" to "$SITE/",
                ) + cookieHeader(),
                timeout = 15L
            ).text
            val t = JSONObject(tokenText).optString("access_token")
            if (t.isNotBlank()) {
                bearer = t
                tokenAt = System.currentTimeMillis()
            }
        } catch (_: Exception) {
        }
    }

    private fun cookieHeader(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val parts = mutableListOf<String>()
        if (sessionCookie.isNotBlank()) parts.add(sessionCookie)
        if (bearer.isNotBlank()) parts.add("auth_token=$bearer")
        if (parts.isNotEmpty()) out["Cookie"] = parts.joinToString("; ")
        return out
    }

    private suspend fun apiGet(url: String): JSONObject? {
        ensureSession()
        val headers = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$SITE/",
            "Origin" to SITE,
            "Authorization" to "Bearer $bearer",
            "Randomid" to randomId,
            "Client-Type" to "WEB",
            "Client-Id" to "5eb393ee95fab7468a79d189",
            "Client-Version" to "2.1.6",
            "Version" to "0.0.1",
        ) + cookieHeader()
        return try {
            val text = app.get(url, headers = headers, timeout = 20L).text
            if (text.isBlank()) null else JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------- catalog

    private const val BATCHES_TTL = 30L * 60L * 1000L

    @Volatile
    private var batchesCache: List<JSONObject> = emptyList()

    @Volatile
    private var batchesAt = 0L

    suspend fun batches(): List<JSONObject> {
        val now = System.currentTimeMillis()
        if (batchesCache.isNotEmpty() && now - batchesAt < BATCHES_TTL) return batchesCache
        return try {
            val text = app.get(BATCHES_URL, headers = mapOf("User-Agent" to UA), timeout = 30L).text
            val arr = JSONObject(text).optJSONArray("data") ?: return batchesCache
            val out = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                if (b.optString("batch_id").isBlank() || b.optString("name").isBlank()) continue
                out.add(b)
            }
            batchesCache = out
            batchesAt = now
            out
        } catch (_: Exception) {
            batchesCache
        }
    }

    fun batchById(id: String): JSONObject? = batchesCache.firstOrNull { it.optString("batch_id") == id }

    // ------------------------------------------------------------- study api

    suspend fun details(id: String): JSONObject? =
        apiGet("$SITE/api/v3/batches/$id/details?type=EXPLORE_LEAD")?.optJSONObject("data")

    suspend fun topics(batchId: String, subjectId: String): List<JSONObject> {
        val data = apiGet("$SITE/api/v2/batches/$batchId/subject/$subjectId/topics?page=1")
            ?.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until data.length()) {
            data.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    suspend fun contents(subjectId: String, topicId: String, contentType: String): List<JSONObject> {
        val url = "$SITE/api/v2/batches/$STUDY_BATCH/subject/$subjectId/contents" +
            "?tag=$topicId&contentType=$contentType&page=1"
        val data = apiGet(url)?.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until data.length()) {
            data.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    suspend fun dppTests(batchId: String, subjectId: String, topicId: String): List<JSONObject> {
        val url = "$SITE/api/v3/test-service/tests/new-dpp-list" +
            "?page=1&batchId=$batchId&batchSubjectId=$subjectId&chapterId=$topicId&dppType=ALL&limit=20"
        val data = apiGet(url)?.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until data.length()) {
            data.optJSONObject(i)?.let { out.add(it) }
        }
        return out
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
        return try {
            val html = app.get(
                playUrl,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "text/html,application/xhtml+xml",
                    "Referer" to "$SITE/",
                ) + cookieHeader(),
                timeout = 20L
            ).text
            fun field(id: String): String {
                val m = Regex("id=\"$id\"\\s+value=\"([^\"]*)\"").find(html) ?: return ""
                return fieldOf(m.groupValues[1])
            }
            val url = field("enc_video_url")
            if (url.isBlank()) null
            else PlayPayload(
                url = url,
                drmType = field("enc_drm_type"),
                keyId = field("enc_key_id"),
                keyValue = field("enc_key_value"),
                licenseUrl = field("enc_license_url"),
            )
        } catch (_: Exception) {
            null
        }
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
