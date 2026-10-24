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
 *              subject, the page batch does not carry the study material; the
 *              tag query narrows it to one topic while a call without the tag
 *              walks the whole subject page by page, and every item carries
 *              its topic inside tags[] so both shapes group the same way
 *  dpp tests   vidcloud.eu.org/api/v3/test-service/tests/new-dpp-list
 *
 * The site's edge refuses plain http clients on the api paths from whole
 * network ranges while letting the same client through from others, so every
 * api read goes three deep: plain, with a session the plugin mints the same
 * way the site's own script mints one, and finally from inside the site's own
 * page where the request rides the webview's browser stack. Whatever happens,
 * the real reason travels back out - a blocked read is never an empty one.
 *
 * play.php encrypts the stream url and the drm fields into hidden inputs
 * before the page ships; the player script walks them back with a fixed
 * aes key so the same walk is mirrored here. The stream behind it is a plain
 * hls playlist the cdn hands out under a .docx name with the media segments
 * under .pdf names, all pre-signed in the url itself, so the native player
 * plays it directly; lectures that ship real clearkey fields ride the
 * player's own drm link instead.
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

    private fun okJson(text: String): SiteResult.Ok? {
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

    // every api answer is kept for a short window so a season rebuild, a
    // sheet reopen or a second episode of the same topic never re-asks
    private const val JSON_TTL = 10L * 60L * 1000L
    private val jsonCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, SiteResult.Ok>>()

    private fun cached(path: String): SiteResult.Ok? {
        jsonCache[path]?.let { (at, ok) ->
            if (System.currentTimeMillis() - at < JSON_TTL) return ok
        }
        return null
    }

    /**
     * One api read, three transports deep. The note on a Fail carries what
     * each transport actually saw so the surface can show the real reason.
     */
    suspend fun siteJson(path: String): SiteResult {
        cached(path)?.let { return it }
        val order = when (fastLane) {
            3 -> listOf(3, 2, 1)
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
                            okJson(resp.textLarge)?.let { fastLane = 1; return keep(path, it) }
                            findings.add("plain: not json")
                        } else findings.add("plain: ${resp.code}")
                    } catch (e: Exception) {
                        findings.add("plain: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                2 -> {
                    val session = session()
                    val jar = session?.first
                    if (jar.isNullOrBlank()) {
                        findings.add("session: none yet")
                        continue
                    }
                    try {
                        val resp = app.get(
                            SITE + path,
                            headers = apiHeaders(session?.second ?: UA, jar, session?.third ?: ""),
                            timeout = 10L
                        )
                        if (resp.isSuccessful) {
                            okJson(resp.textLarge)?.let { fastLane = 2; return keep(path, it) }
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
                        okJson(body)?.let { fastLane = 3; return keep(path, it) }
                        findings.add("page: not json")
                    } else findings.add("page: $status")
                }
            }
        }
        if (fastLane != 0) fastLane = 0
        return SiteResult.Fail(findings.distinct().joinToString("; ").ifBlank { "no transport answered" })
    }

    private fun keep(path: String, ok: SiteResult.Ok): SiteResult.Ok {
        jsonCache[path] = System.currentTimeMillis() to ok
        return ok
    }

    // ------------------------------------------------------------- session

    // a session minted the same way the site's own script mints one: the
    // token page answers with the bearer in its body and the php session in
    // its set-cookie headers, and both together ride every later call
    private const val SESSION_TTL = 45L * 60L * 1000L

    @Volatile
    private var mintedCookies: String? = null

    @Volatile
    private var mintedToken: String? = null

    @Volatile
    private var mintedUa: String? = null

    @Volatile
    private var mintAt = 0L

    @Volatile
    private var mintFailedAt = 0L

    /**
     * The cookie jar, bearer and user agent every plain call should ride:
     * the webview's own harvest when one exists, else a pair minted from
     * the site's token page, null when neither could be built. A mint that
     * just failed is not retried for a minute so a blocked network never
     * turns every read into another token attempt.
     */
    suspend fun session(): Triple<String, String, String>? {
        val now = System.currentTimeMillis()
        val webviewJar = VidCloudWebView.cookies()
        if (!webviewJar.isNullOrBlank()) {
            mintedCookies = webviewJar
            mintedToken = VidCloudWebView.token()
            mintedUa = VidCloudWebView.userAgent()
            mintAt = now
            return Triple(webviewJar, mintedUa ?: UA, mintedToken ?: "")
        }
        if (!mintedCookies.isNullOrBlank() && now - mintAt < SESSION_TTL) {
            return Triple(mintedCookies!!, mintedUa ?: UA, mintedToken ?: "")
        }
        if (now - mintFailedAt < 60_000L) return null
        return try {
            val resp = app.get(
                "$SITE/generate_token.php",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "*/*",
                    "Referer" to "$SITE/",
                    "Origin" to SITE,
                ),
                timeout = 10L,
            )
            if (!resp.isSuccessful) {
                mintFailedAt = now
                return null
            }
            val token = try {
                JSONObject(resp.textLarge).optString("access_token")
            } catch (_: Exception) {
                ""
            }
            val jar = resp.headers.values("Set-Cookie").mapNotNull { raw ->
                raw.substringBefore(';').takeIf { it.contains('=') }
            }.joinToString("; ").ifBlank { if (token.isNotBlank()) "auth_token=$token" else "" }
            if (jar.isBlank() && token.isBlank()) {
                mintFailedAt = now
                return null
            }
            mintedCookies = jar
            mintedToken = token
            mintedUa = UA
            mintAt = now
            Triple(jar, UA, token)
        } catch (_: Exception) {
            mintFailedAt = now
            null
        }
    }

    // ------------------------------------------------------------- catalog

    private const val BATCHES_TTL = 30L * 60L * 1000L

    /**
     * The four catalog fields anything downstream ever reads; the mirror
     * carries seventeen thousand entries, so keeping whole json objects for
     * all of them would hold tens of megabytes on the heap for nothing.
     */
    class BatchEntry(val id: String, val name: String, val exam: String, val photo: String)

    @Volatile
    private var batchesCache: List<BatchEntry> = emptyList()

    @Volatile
    private var batchesAt = 0L

    // the mirror ships an etag, so a refresh after the ttl revalidates for
    // the price of an empty 304 instead of the whole file again
    @Volatile
    private var batchesEtag: String? = null

    /**
     * The catalog mirror is a plain static file - several megabytes, which
     * is over what the plain .text accessor allows by design, so the body is
     * read with .textLarge and walked one element at a time instead of ever
     * holding the whole thing as json. A failure here is a real failure and
     * travels out instead of an empty page.
     */
    suspend fun batches(): List<BatchEntry> {
        val now = System.currentTimeMillis()
        if (batchesCache.isNotEmpty() && now - batchesAt < BATCHES_TTL) return batchesCache
        val headers = mutableMapOf("User-Agent" to UA)
        batchesEtag?.let { headers["If-None-Match"] = it }
        val resp = try {
            app.get(BATCHES_URL, headers = headers, timeout = 60L)
        } catch (e: Exception) {
            throw SiteException("catalog unreachable: ${e.message ?: e.javaClass.simpleName}")
        }
        if (resp.code == 304 && batchesCache.isNotEmpty()) {
            batchesAt = now
            return batchesCache
        }
        if (!resp.isSuccessful) {
            throw SiteException("catalog unreachable: HTTP ${resp.code}")
        }
        val text = try {
            resp.textLarge
        } catch (e: Exception) {
            throw SiteException("catalog unreadable: ${e.message ?: e.javaClass.simpleName}")
        }
        val out = parseBatches(text)
        if (out.isEmpty()) throw SiteException("catalog unreadable")
        resp.headers["etag"]?.let { batchesEtag = it }
        batchesCache = out
        batchesAt = now
        return out
    }

    /**
     * Walks the data array straight off the raw text: one element at a time,
     * each closed by its own brace balance, so only the four kept fields ever
     * reach the heap. The data key is only honored where it sits at the top
     * level outside every string, and an element's fields are unescaped by
     * the json parser itself because some of the site's values carry escaped
     * quotes in them.
     */
    private fun parseBatches(text: String): List<BatchEntry> {
        val out = mutableListOf<BatchEntry>()
        val n = text.length
        var i = 0
        if (n > 0 && text[0] == '\uFEFF') i = 1

        // find the top level data array: a "data" key seen at depth one,
        // outside every string, followed by its value opening bracket
        var depth = 0
        var inString = false
        var escaped = false
        var dataStart = -1
        var searchKey = true
        while (i < n && dataStart < 0) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                '"' -> {
                    inString = true
                    if (depth == 1 && searchKey && text.startsWith("\"data\"", i)) {
                        var j = i + 6
                        while (j < n && text[j].isWhitespace()) j++
                        if (j < n && text[j] == ':') {
                            j++
                            while (j < n && text[j].isWhitespace()) j++
                            if (j < n && text[j] == '[') dataStart = j + 1
                        }
                    }
                }

                '{' -> depth++
                '}' -> depth--
                ':' -> searchKey = false
                ',' -> searchKey = true
            }
            i++
        }
        if (dataStart < 0) return out

        // one brace-balanced element at a time
        i = dataStart
        while (i < n) {
            val c = text[i]
            if (c == ']') return out
            if (c != '{') {
                i++
                continue
            }
            var brace = 0
            var j = i
            var str = false
            var esc = false
            while (j < n) {
                val d = text[j]
                if (str) {
                    when {
                        esc -> esc = false
                        d == '\\' -> esc = true
                        d == '"' -> str = false
                    }
                } else when (d) {
                    '"' -> str = true
                    '{' -> brace++
                    '}' -> {
                        brace--
                        if (brace == 0) break
                    }
                }
                j++
            }
            if (brace != 0) return out
            entryOf(text.substring(i, j + 1))?.let { out.add(it) }
            i = j + 1
        }
        return out
    }

    private fun entryOf(element: String): BatchEntry? {
        val o = try {
            JSONObject(element)
        } catch (_: Exception) {
            return null
        }
        val id = o.optString("batch_id")
        val name = o.optString("name")
        if (id.isBlank() || name.isBlank()) return null
        return BatchEntry(id, name, o.optString("exam"), o.optString("photo"))
    }

    class SiteException(message: String) : Exception(message)

    // ------------------------------------------------------------- study api

    suspend fun details(id: String): SiteResult =
        siteJson("/api/v3/batches/$id/details?type=EXPLORE_LEAD")

    suspend fun topics(batchId: String, subjectId: String): SiteResult =
        siteJson("/api/v2/batches/$batchId/subject/$subjectId/topics?page=1")

    suspend fun contents(subjectId: String, topicId: String, contentType: String): SiteResult =
        siteJson(
            "/api/v2/batches/$STUDY_BATCH/subject/$subjectId/contents" +
                "?tag=$topicId&contentType=$contentType&page=1"
        )

    /**
     * The whole subject in one walk: the same contents route without the
     * tag, page by page. Every item still carries its topic inside tags[],
     * so the caller groups exactly the way the per topic calls would.
     */
    suspend fun subjectContents(subjectId: String, contentType: String, page: Int): SiteResult =
        siteJson(
            "/api/v2/batches/$STUDY_BATCH/subject/$subjectId/contents" +
                "?contentType=$contentType&page=$page&limit=100"
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
        val startedAt: Long,
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
            startedAt = runCatching { isoTime(item.optString("startTime")) }.getOrDefault(0L),
        )
    }

    /**
     * The api's 2026-02-08T04:30:00.000Z stamps read without java.time so the
     * walk runs on every android the app itself runs on; anything the format
     * does not match is simply no date at all.
     */
    private fun isoTime(value: String): Long {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})").find(value)
            ?: return 0L
        val (y, mo, d, h, mi, s) = m.destructured
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        return cal.timeInMillis
    }

    /** The topic tag an item hangs under, the same key the per topic call filters on. */
    fun tagOf(item: JSONObject): Pair<String, String>? {
        val tag = item.optJSONArray("tags")?.optJSONObject(0) ?: return null
        val id = tag.optString("_id").takeIf { it.isNotBlank() } ?: return null
        return id to tag.optString("name").ifBlank { "Lectures" }
    }

    /**
     * hh:mm:ss or mm:ss into plain minutes, null when there is nothing to read.
     */
    fun minutesOf(duration: String): Int? {
        if (duration.isBlank()) return null
        val parts = duration.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty() || parts.size !in 2..3) return null
        return when (parts.size) {
            3 -> parts[0] * 60 + parts[1]
            else -> parts[0]
        }.takeIf { it > 0 }
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
    ) {
        val clearkey: Boolean
            get() = drmType.equals("clearkey", true) && keyId.isNotBlank() && keyValue.isNotBlank()
    }

    /**
     * Walks play.php's hidden inputs back to plain values. The page is asked
     * twice: once over plain http riding the minted session, which is the
     * way the site's own pages ask from a phone, and once from inside the
     * site's own page when the first ask comes back empty. Empty fields stay
     * empty.
     */
    suspend fun resolvePlay(playUrl: String): PlayPayload? {
        val html = fetchPlayHttp(playUrl)
            ?: VidCloudWebView.fetch(playUrl.removePrefix(SITE))?.let { (status, body) ->
                if (status in 200..299) body else null
            }
            ?: return null
        if (!html.contains("enc_video_url")) return null
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

    private suspend fun fetchPlayHttp(playUrl: String): String? {
        val (jar, ua, token) = session() ?: return null
        val headers = mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$SITE/",
            "Origin" to SITE,
            "Cookie" to jar,
        )
        if (token.isNotBlank()) headers["Authorization"] = "Bearer $token"
        return try {
            val resp = app.get(playUrl, headers = headers, timeout = 15L)
            if (resp.isSuccessful) resp.textLarge else null
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
