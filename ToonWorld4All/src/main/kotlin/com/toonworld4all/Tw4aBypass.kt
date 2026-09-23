package com.toonworld4all

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "TW4A_CF"

private val CF_CHALLENGE_TITLES = listOf(
    "just a moment", "just a moment...", "checking your browser",
    "attention required", "ddos-guard", "one more step", "one moment, please"
)

private const val COOKIE_TTL_MS = 15L * 60 * 60 * 1000
private const val ARCHIVE_COOKIE_TTL_MS = 25L * 60 * 60 * 1000
private const val SOLVER_TIMEOUT_MS = 90_000L
private const val QUEUE_TIMEOUT_MS = 300_000L
private const val POLL_INTERVAL_MS = 1000L
private const val CURSOR_STEP_DP = 10f
private const val BYPASS_COOLDOWN_MS = 60_000L
private const val STORE_KEY = "TW4A_CF_SESSIONS"
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

@Volatile private var lastBypassHostTime: MutableMap<String, Long> = mutableMapOf()

internal class Tw4aCFSession {
    var cookies: String = ""
    var userAgent: String = ""
    var timestamp: Long = 0L
}

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
internal data class Tw4aCFStoreData(
    @com.fasterxml.jackson.annotation.JsonProperty("sessions") val sessions: Map<String, Tw4aCFSession> = emptyMap()
)

internal object Tw4aCFStore {
    @Volatile private var sessions: MutableMap<String, Tw4aCFSession> = mutableMapOf()
    @Volatile private var initialized = false

    private val families = listOf(
        "filepress", "gdflix", "hubcloud", "toonworld4all", "exeygo", "gplinks", "cutty"
    )

    private fun familyOf(host: String): String? = families.firstOrNull { host.contains(it) }

    fun init() {
        if (initialized) return
        initialized = true
        try {
            val raw = CloudStreamApp.getKey<String>(STORE_KEY)
            if (!raw.isNullOrBlank()) {
                val parsed = parseJson<Tw4aCFStoreData>(raw)
                sessions = parsed.sessions.toMutableMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "init: ${e.message}")
        }
    }

    private fun ttlFor(host: String): Long =
        if (host.lowercase().contains("toonworld4all")) ARCHIVE_COOKIE_TTL_MS else COOKIE_TTL_MS

    fun getSession(host: String): Tw4aCFSession? {
        val s = sessions[host] ?: return familySession(host)
        if (s.cookies.isBlank()) return familySession(host)
        if (System.currentTimeMillis() - s.timestamp > ttlFor(host)) {
            clear(host)
            return familySession(host)
        }
        return s
    }

    private fun familySession(host: String): Tw4aCFSession? {
        val family = familyOf(host.lowercase()) ?: return null
        var best: Tw4aCFSession? = null
        for ((other, s) in sessions) {
            if (!other.lowercase().contains(family)) continue
            if (s.cookies.isBlank()) continue
            if (System.currentTimeMillis() - s.timestamp > ttlFor(other)) continue
            if (s.cookies.contains("cf_clearance")) return s
            if (best == null || s.timestamp > best.timestamp) best = s
        }
        return best
    }

    fun save(host: String, cookies: String, userAgent: String) {
        val s = Tw4aCFSession()
        s.cookies = cookies
        s.userAgent = userAgent
        s.timestamp = System.currentTimeMillis()
        sessions[host] = s
        val family = familyOf(host.lowercase())
        if (family != null && cookies.contains("cf_clearance")) {
            for ((other, existing) in sessions) {
                if (other.lowercase().contains(family) && !existing.cookies.contains("cf_clearance")) {
                    val shared = Tw4aCFSession()
                    shared.cookies = cookies
                    shared.userAgent = userAgent
                    shared.timestamp = System.currentTimeMillis()
                    sessions[other] = shared
                }
            }
        }
        lastBypassHostTime[host] = System.currentTimeMillis()
        persist()
    }

    fun mergeCookies(host: String, setCookieValues: List<String>, userAgent: String) {
        if (setCookieValues.isEmpty()) return
        val existing = sessions[host]?.cookies?.split("; ")?.filter { it.isNotBlank() }?.associate {
            it.substringBefore("=").trim() to it.substringAfter("=").trim()
        } ?: emptyMap()
        val merged = existing.toMutableMap()
        for (raw in setCookieValues) {
            val pair = raw.substringBefore(";").trim()
            val name = pair.substringBefore("=").trim()
            val value = pair.substringAfter("=").trim()
            if (name.isEmpty()) continue
            if (value.isEmpty()) merged.remove(name) else merged[name] = value
        }
        val s = sessions[host] ?: Tw4aCFSession().also { sessions[host] = it }
        s.cookies = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (s.userAgent.isBlank()) s.userAgent = userAgent
        if (s.timestamp == 0L) s.timestamp = System.currentTimeMillis()
        persist()
    }

    fun hasUserCookie(host: String): Boolean {
        return sessions[host]?.cookies?.contains("user=") == true
    }

    fun clear(host: String) {
        sessions.remove(host)
        persist()
    }

    fun clearAll() {
        sessions.clear()
        persist()
    }

    fun isRecentlyBypassed(host: String): Boolean {
        val t = lastBypassHostTime[host] ?: return false
        return System.currentTimeMillis() - t < BYPASS_COOLDOWN_MS
    }

    fun markBypassed(host: String) {
        lastBypassHostTime[host] = System.currentTimeMillis()
    }

    private fun persist() {
        try {
            CloudStreamApp.setKey(STORE_KEY, Tw4aCFStoreData(sessions).toJson())
        } catch (e: Exception) {
            Log.e(TAG, "persist: ${e.message}")
        }
    }
}

internal fun isTw4aCloudflareBlocked(response: NiceResponse): Boolean {
    val code = response.code
    if (code == 503) return true
    val body = try { response.text.lowercase() } catch (e: Exception) { "" }
    if (code == 403) {
        if (body.contains("just a moment") && body.contains("challenge-platform")) return true
        if (body.contains("checking your browser") && body.contains("cloudflare")) return true
        if (body.contains("cf-browser-verification")) return true
        if (body.contains("checking if the site connection is secure")) return true
        if (body.contains("just a moment")) return true
        if (body.contains("attention required")) return true
        return false
    }
    if (body.contains("just a moment") && body.contains("challenge-platform")) return true
    if (body.contains("checking your browser") && body.contains("cloudflare")) return true
    if (body.contains("cf-browser-verification")) return true
    if (body.contains("checking if the site connection is secure")) return true
    return false
}

internal val TW4A_FILE_HOST = Regex(
    """(?i)(hubcloud\.|gdflix|filepress|filebee|gdtot|appdrive|gdrive\.|pixeldrain""" +
            """|gofile\.io|drive\.google\.com|googleusercontent|mega\.nz|mega\.co\.nz""" +
            """|\.mp4|\.mkv|\.m3u8|\.ts(?![a-z])|workers\.dev|busycdn|fastdl""" +
            """|flapdoodle|\.r2\.dev)"""
)

internal val TW4A_SHORTENER_HOST = listOf(
    "exe.io", "exeygo.com", "cuty.io", "cuttty.com",
    "gplinks.co", "gplinks.com", "linkvertise", "oxy"
)

internal fun tw4aIsShortenerUrl(url: String): Boolean {
    val host = tw4aHostOf(url).lowercase()
    if (host.isEmpty()) return false
    return TW4A_SHORTENER_HOST.any { host == it || host.endsWith(".$it") }
}

internal fun tw4aShortenerDomainKey(url: String): String? {
    val host = tw4aHostOf(url).lowercase()
    if (host.isEmpty()) return null
    return when {
        host.contains("gplinks") -> "gplinks"
        host.contains("exe.io") || host.contains("exeygo") -> "exe"
        host.contains("cuty") || host.contains("cuttty") -> "cuty"
        else -> host
    }
}

internal fun tw4aHostEnabled(host: String): Boolean {
    val h = host.lowercase()
    val key = when {
        h.contains("hubcloud") -> "TW4A_HOST_HUBCLOUD"
        h.contains("gdflix") -> "TW4A_HOST_GDFLIX"
        h.contains("filepress") || h.contains("filebee") -> "TW4A_HOST_FILEPRESS"
        h.contains("mega") -> "TW4A_HOST_MEGA"
        else -> return true
    }
    val def = key == "TW4A_HOST_HUBCLOUD"
    return try {
        CloudStreamApp.getKey<Boolean>(key) ?: def
    } catch (e: Exception) {
        def
    }
}

internal fun tw4aSetHostEnabled(key: String, value: Boolean) {
    try {
        CloudStreamApp.setKey(key, value)
    } catch (e: Exception) {
        Log.e(TAG, "set host: ${e.message}")
    }
}

internal fun tw4aHostToggleState(key: String): Boolean {
    val def = key == "TW4A_HOST_HUBCLOUD"
    return try {
        CloudStreamApp.getKey<Boolean>(key) ?: def
    } catch (e: Exception) {
        def
    }
}

internal fun tw4aHostOf(url: String): String = try {
    Uri.parse(url).host ?: ""
} catch (e: Exception) { "" }

internal fun tw4aSessionHeaders(host: String): Map<String, String> {
    val h = mutableMapOf<String, String>()
    val s = Tw4aCFStore.getSession(host)
    if (s != null) {
        h["User-Agent"] = s.userAgent
        h["Cookie"] = s.cookies
    }
    return h
}

internal fun tw4aNormalizeUrl(url: String): String {
    return url.replace("https://gdflix.dev/", "https://new4.gdflix.io/")
}

internal suspend fun tw4aSetSystem24Hour() {
    try {
        val url = "https://archive.toonworld4all.me/api/user/preference/system?id=24hour"
        val h = buildTw4aHeaders(
            url,
            mapOf(
                "Referer" to "https://archive.toonworld4all.me/",
                "Origin" to "https://archive.toonworld4all.me"
            )
        ).toMutableMap()
        h["Accept"] = "*/*"
        val response = app.post(url, headers = h, timeout = 15_000L)
        captureSetCookies(url, response)
    } catch (e: Exception) {
        Log.d(TAG, "system pref failed: ${e.message}")
    }
}

private fun buildTw4aHeaders(url: String, original: Map<String, String>): Map<String, String> {
    val h = original.toMutableMap()
    if (!h.containsKey("Accept")) h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    val host = tw4aHostOf(url)
    val s = Tw4aCFStore.getSession(host)
    if (s != null) {
        h["User-Agent"] = s.userAgent
        h["Cookie"] = s.cookies
    } else if (!h.containsKey("User-Agent")) {
        h["User-Agent"] = MOBILE_UA
    }
    return h
}

private fun captureSetCookies(url: String, response: NiceResponse) {
    try {
        val host = tw4aHostOf(url)
        if (host.isEmpty()) return
        val values = response.headers.values("set-cookie")
        if (values.isNullOrEmpty()) return
        val ua = Tw4aCFStore.getSession(host)?.userAgent ?: MOBILE_UA
        Tw4aCFStore.mergeCookies(host, values, ua)
    } catch (e: Exception) {
        Log.d(TAG, "captureSetCookies: ${e.message}")
    }
}

internal suspend fun tw4aGet(
    url: String,
    headers: Map<String, String> = emptyMap(),
    allowRedirects: Boolean = true,
    timeout: Long = 30_000L
): NiceResponse {
    val targetUrl = tw4aNormalizeUrl(url)
    val host = tw4aHostOf(targetUrl)
    val targetHost = try {
        val uri = Uri.parse(targetUrl)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) { targetUrl }

    var response = try {
        app.get(targetUrl, headers = buildTw4aHeaders(targetUrl, headers), timeout = timeout, allowRedirects = allowRedirects)
    } catch (e: Exception) { throw e }

    captureSetCookies(targetUrl, response)

    if (!isTw4aCloudflareBlocked(response)) return response

    if (Tw4aCFStore.isRecentlyBypassed(host)) {
        return response
    }

    cfBypassMutex.withLock {
        if (Tw4aCFStore.isRecentlyBypassed(host)) {
            response = try { app.get(targetUrl, headers = buildTw4aHeaders(targetUrl, headers), timeout = timeout, allowRedirects = allowRedirects) } catch (e: Exception) { throw e }
            captureSetCookies(targetUrl, response)
            if (!isTw4aCloudflareBlocked(response)) return response
            if (Tw4aCFStore.getSession(host) == null) return response
        }

        val cachedSession = Tw4aCFStore.getSession(host)
        if (cachedSession != null) {
            response = try { app.get(targetUrl, headers = buildTw4aHeaders(targetUrl, headers), timeout = timeout, allowRedirects = allowRedirects) } catch (e: Exception) { throw e }
            captureSetCookies(targetUrl, response)
            if (!isTw4aCloudflareBlocked(response)) return response
        }

        Tw4aCFStore.clear(host)
        val bypassSuccess = showTw4aCFBypassDialogAndWait(targetHost)
        if (!bypassSuccess) {
            Tw4aCFStore.markBypassed(host)
            return@withLock
        }
        for (attempt in 1..2) {
            response = try { app.get(targetUrl, headers = buildTw4aHeaders(targetUrl, headers), timeout = timeout, allowRedirects = allowRedirects) } catch (e: Exception) { throw e }
            captureSetCookies(targetUrl, response)
            if (!isTw4aCloudflareBlocked(response)) return@withLock
        }
        Tw4aCFStore.markBypassed(host)
    }
    return response
}

internal fun tw4aLooksLikeChallenge(html: String): Boolean {
    if (html.length > 400_000) return false
    val lower = html.lowercase()
    if (lower.contains("cf-browser-verification")) return true
    if (lower.contains("just a moment") && lower.contains("challenge-platform")) return true
    if (lower.contains("checking your browser") && lower.contains("cloudflare")) return true
    if (lower.contains("checking if the site connection is secure")) return true
    if (lower.contains("attention required") && lower.contains("cloudflare")) return true
    if (lower.contains("one moment, please") && lower.contains("cloudflare")) return true
    return false
}

fun initTw4aCFBypass() {
    Tw4aCFStore.init()
}

private val cfBypassMutex = Mutex()
private class CursorPosHolder { var x: Float = 0f; var y: Float = 0f }

private val SHORTENER_CLICKER = """
    (function () {
        var h = (location.hostname || "").toLowerCase();
        if (h.indexOf("gplinks.") !== -1) {
            if (window.__tw4aGpl) return;
            window.__tw4aGpl = 1;
            var waited = 0, posting = false;
            var t = setInterval(function () {
                waited += 3;
                if (waited < 9) return;
                try {
                    var form = document.querySelector('form[action*="links/go"]') ||
                               document.querySelector('form[action]');
                    if (form && !posting) {
                        var fd = new FormData(form);
                        if (!fd.get("_csrfToken") && !fd.get("cf-turnstile-response")) return;
                        posting = true;
                        fetch(form.action, {
                            method: "POST",
                            credentials: "same-origin",
                            headers: { "X-Requested-With": "XMLHttpRequest" },
                            body: new URLSearchParams(fd)
                        }).then(function (r) { return r.json(); })
                          .then(function (res) {
                              if (res && res.url) location.href = res.url;
                              else posting = false;
                          })
                          .catch(function () { posting = false; });
                        return;
                    }
                    var b = document.querySelector("#go-link, .btn-success, .get-link, #get-link, .skip-ad, #skip-ad");
                    if (b && !b.disabled && !posting) b.click();
                } catch (e) { }
                if (waited > 150) clearInterval(t);
            }, 3000);
            return;
        }
        if (window.__tw4aClicker) return;
        window.__tw4aClicker = 1;
        var clicks = 0;
        var t = setInterval(function () {
            clicks++;
            if (clicks === 12 || clicks === 17) {
                try {
                    var fb = document.querySelector('button[data-ref="continue"]');
                    if (fb && (fb.disabled || fb.className.indexOf("disabled") !== -1)) {
                        fb.disabled = false;
                        fb.className = fb.className.replace(/\bdisabled\b/g, "").trim();
                        fb.click();
                    }
                } catch (e) { }
            }
            try {
                var b;
                if ((b = document.querySelector('button[data-ref="continue"]')) &&
                    !b.disabled && b.className.indexOf("disabled") === -1) { b.click(); return; }
                if ((b = document.querySelector('button[data-ref="captcha"]')) &&
                    !b.disabled && b.className.indexOf("disabled") === -1) { b.click(); return; }
                if ((b = document.querySelector('button#invisibleCaptchaShortlink')) &&
                    !b.disabled) { b.click(); return; }
                if ((b = document.querySelector('button#submit-button')) &&
                    !b.disabled) { b.click(); return; }
                if ((b = document.querySelector('button#VerifyBtn')) &&
                    !b.disabled) { b.click(); return; }
                if ((b = document.querySelector('#go-link, #get-link, .btn-success[data-clipboard-text]')) &&
                    !b.disabled) { b.click(); return; }
                var a = document.querySelector('a.get-link, a#get-link, a.skip-ad, a[href="#getlink"], a#go-link');
                if (a) a.click();
            } catch (e) { }
            if (clicks > 90) clearInterval(t);
        }, 1200);
    })();
""".trimIndent()

internal class Tw4aShortenerResult(
    val landings: Map<String, String>,
    val completed: Boolean
)

@SuppressLint("InflateParams")
private class Tw4aWebDialog(
    private val mode: String,
    private val targetUrl: String,
    private val queue: List<String> = emptyList(),
    private val nextItem: (suspend () -> String?)? = null,
    private val onDone: ((Any?) -> Unit)? = null
) {
    private var dialog: AlertDialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var titleText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pollElapsedMs = 0L
    private val sessionScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )
    private var fetchingNext = false
    private var fetchAttempts = 0

    private val pending = mutableListOf<String>()
    private val landings = linkedMapOf<String, String>()
    private var cursor = 0
    private var queueFinished = false
    private var candidateUrl: String? = null
    private var candidateAt = 0L

    private val allowedHosts = listOf(
        "exe.io", "exeygo.com", "cuty.io", "cuttty.com", "gplinks.co", "gplinks.com",
        "challenges.cloudflare.com", "cloudflareinsights.com", "toonworld4all.me",
        "static.cloudflareinsights.com", "fstatic.netpub.media",
        "live.demand.supply", "a.nel.cloudflare.com", "ay267.com", "luugy.com"
    )

    private fun isAllowed(url: String): Boolean {
        if (!url.startsWith("http")) return true
        if (TW4A_FILE_HOST.containsMatchIn(url) && !tw4aIsShortenerUrl(url)) return true
        val host = tw4aHostOf(url)
        if (host.isEmpty()) return true
        return allowedHosts.any { host == it || host.endsWith(".$it") }
    }

    private fun isFileLanding(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (tw4aIsShortenerUrl(url)) return false
        if (tw4aHostOf(url).isEmpty()) return false
        if (hostInList(url, "challenges.cloudflare.com")) return false
        if (hostInList(url, "toonworld4all.me")) return false
        return TW4A_FILE_HOST.containsMatchIn(url)
    }

    private fun hostInList(url: String, hostName: String): Boolean {
        val h = tw4aHostOf(url).lowercase()
        return h == hostName || h.endsWith(".$hostName")
    }

    private fun harvestCookies(forUrl: String) {
        try {
            CookieManager.getInstance().flush()
            val hosts = mutableListOf("archive.toonworld4all.me", "toonworld4all.me")
            val landed = tw4aHostOf(forUrl)
            if (landed.isNotEmpty() && landed !in hosts) hosts.add(landed)
            if (mode == "cf") {
                val th = tw4aHostOf(targetUrl)
                if (th.isNotEmpty()) hosts.add(th)
            }
            val ua = webView?.settings?.userAgentString ?: MOBILE_UA
            for (host in hosts.distinct()) {
                val c = CookieManager.getInstance().getCookie("https://$host")
                if (!c.isNullOrBlank()) {
                    Tw4aCFStore.mergeCookies(host, listOf(c), ua)
                }
            }
        } catch (e: Exception) {}
    }

    private fun finishCf(success: Boolean, cookieStr: String = "", cookieHost: String = "") {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        sessionScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        if (success) {
            val ua = webView?.settings?.userAgentString ?: MOBILE_UA
            val host = cookieHost.ifBlank { tw4aHostOf(targetUrl) }
            Tw4aCFStore.save(host, cookieStr, ua)
            val targetHost = tw4aHostOf(targetUrl)
            if (host != targetHost) {
                try {
                    val targetCookies = CookieManager.getInstance().getCookie("https://$targetHost")
                    if (!targetCookies.isNullOrBlank()) {
                        Tw4aCFStore.mergeCookies(targetHost, listOf(targetCookies), ua)
                    }
                } catch (e: Exception) {}
            }
        }
        try { webView?.destroy() } catch (e: Exception) {}
        try { dialog?.dismiss() } catch (e: Exception) {}
        try { onDone?.invoke(if (success) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE) } catch (e: Exception) {}
    }

    private fun tryFinishCfFromCookies(): Boolean {
        try {
            CookieManager.getInstance().flush()
            val targetHost = tw4aHostOf(targetUrl)
            val cookieStr = CookieManager.getInstance().getCookie("https://$targetHost") ?: ""
            if (cookieStr.contains("cf_clearance")) {
                finishCf(true, cookieStr, targetHost)
                return true
            }
            val current = webView?.url
            if (current != null) {
                val curHost = tw4aHostOf(current)
                if (curHost.isNotEmpty() && curHost != targetHost) {
                    val altCookies = CookieManager.getInstance().getCookie("https://$curHost") ?: ""
                    if (altCookies.contains("cf_clearance")) {
                        finishCf(true, altCookies, curHost)
                        return true
                    }
                }
            }
        } catch (e: Exception) {}
        return false
    }

    private fun finishQueue(userClosed: Boolean) {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        sessionScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        harvestCookies(webView?.url ?: "")
        try { webView?.destroy() } catch (e: Exception) {}
        try { dialog?.dismiss() } catch (e: Exception) {}
        try {
            onDone?.invoke(Tw4aShortenerResult(landings.toMap(), !userClosed && queueFinished))
        } catch (e: Exception) {}
    }

    private fun maybeCapture(url: String, finished: Boolean = false) {
        if (mode != "queue") return
        if (!isFileLanding(url)) return
        val key = pending.getOrNull(cursor) ?: return
        if (landings.containsKey(key)) return
        if (finished) {
            candidateUrl = null
            landings[key] = url
            harvestCookies(url)
            advanceQueue()
        } else {
            candidateUrl = url
            candidateAt = SystemClock.elapsedRealtime()
        }
    }

    private fun settleCandidate() {
        val url = candidateUrl ?: return
        if (SystemClock.elapsedRealtime() - candidateAt < 8000L) return
        val key = pending.getOrNull(cursor) ?: return
        if (landings.containsKey(key)) {
            candidateUrl = null
            return
        }
        candidateUrl = null
        landings[key] = url
        harvestCookies(url)
        advanceQueue()
    }

    private fun advanceQueue() {
        var next = cursor + 1
        while (next < pending.size && pending[next] in landings) next++
        pollElapsedMs = 0L
        if (next >= pending.size) {
            if (fetchingNext) return
            if (nextItem != null && fetchAttempts < 6) {
                fetchingNext = true
                fetchAttempts++
                statusText?.text = "Checking for the next shortener..."
                sessionScope.launch {
                    val nxt = try {
                        nextItem?.invoke()
                    } catch (e: Exception) {
                        null
                    }
                    handler.post {
                        fetchingNext = false
                        if (resolved.get()) return@post
                        if (nxt.isNullOrBlank()) {
                            queueFinished = true
                            statusText?.text = "All links opened (${landings.size} solved)"
                            handler.postDelayed({ finishQueue(false) }, 900)
                        } else {
                            pending.add(nxt)
                            cursor = pending.size - 1
                            statusText?.text = "Opening next shortener (${landings.size} solved)..."
                            try {
                                webView?.loadUrl(nxt)
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
                return
            }
            queueFinished = true
            statusText?.text = "All links opened (${landings.size}/${pending.size})"
            handler.postDelayed({ finishQueue(false) }, 1200)
        } else {
            cursor = next
            statusText?.text = "Opened ${landings.size}/${pending.size} - loading next..."
            handler.postDelayed({
                if (!resolved.get()) {
                    try { webView?.loadUrl(pending[cursor]) } catch (e: Exception) {}
                }
            }, 900)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (resolved.get() || dialog == null || dialog?.isShowing != true) return
            pollElapsedMs += POLL_INTERVAL_MS
            if (mode == "cf") {
                if (pollElapsedMs >= SOLVER_TIMEOUT_MS) {
                    finishCf(false)
                    return
                }
                try {
                    CookieManager.getInstance().flush()
                    val targetHost = tw4aHostOf(targetUrl)
                    val cookieStr = CookieManager.getInstance().getCookie("https://$targetHost") ?: ""
                    if (cookieStr.contains("cf_clearance")) {
                        finishCf(true, cookieStr, targetHost)
                        return
                    }
                    val current = webView?.url
                    if (current != null) {
                        val curHost = tw4aHostOf(current)
                        if (curHost.isNotEmpty() && curHost != targetHost) {
                            val altCookies = CookieManager.getInstance().getCookie("https://$curHost") ?: ""
                            if (altCookies.contains("cf_clearance")) {
                                finishCf(true, altCookies, curHost)
                                return
                            }
                        }
                    }
                } catch (e: Exception) {}
                statusText?.text = "Waiting for clearance... (${pollElapsedMs / 1000}s)"
            } else {
                if (pollElapsedMs >= QUEUE_TIMEOUT_MS) {
                    finishQueue(true)
                    return
                }
                if (pollElapsedMs >= SOLVER_TIMEOUT_MS) {
                    pollElapsedMs = 0L
                    statusText?.text = "Skipped a slow link - opening next..."
                    advanceQueue()
                    return
                }
                settleCandidate()
                val cur = webView?.url ?: ""
                val label = if (tw4aHostOf(cur).isNotEmpty()) tw4aHostOf(cur) else "loading"
                statusText?.text = "Solving ${landings.size}/${pending.size} - $label (${pollElapsedMs / 1000}s)"
            }
            if (!resolved.get()) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun backOrDismiss() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            if (mode == "cf") finishCf(false) else finishQueue(true)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: AppCompatActivity) {
        val dp = activity.resources.displayMetrics.density
        val screenH = activity.resources.displayMetrics.heightPixels
        val dialogW = (activity.resources.displayMetrics.widthPixels * 0.95f).toInt()
        val dialogH = (screenH * 0.9f).toInt()
        val webViewHeight = (screenH * 0.6f).toInt()

        if (mode == "queue") {
            pending.addAll(queue)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        val title = TextView(activity).apply {
            text = if (mode == "cf") "Cloudflare Bypass" else "Opening Download Links"
            textSize = 16f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (6 * dp).toInt())
        }
        titleText = title
        container.addView(title)

        val statusView = TextView(activity).apply {
            text = "Loading..."
            textSize = 12f; setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        statusText = statusView
        container.addView(statusView)

        val isTv = try { Globals.isLayout(Globals.TV) } catch (e: Throwable) { false }
        container.addView(TextView(activity).apply {
            text = if (mode == "cf") {
                if (isTv) "Use D-pad to move the cursor and press OK to click."
                else "Solve the check below. Use Back to navigate, Cancel to close."
            } else {
                if (isTv) "Press Continue on each page and verify the captcha when asked."
                else "Tap Continue on each page and verify the captcha when asked."
            }
            textSize = 11f; setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        container.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = (8 * dp).toInt() }
        })

        val webContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, webViewHeight)
            isFocusable = true; isFocusableInTouchMode = true
        }
        webView = buildWebView(activity)
        webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))

        if (isTv) {
            val cursorSize = (22 * dp).toInt()
            val cursor = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(160, 255, 50, 50))
                    setStroke((2 * dp).toInt(), Color.WHITE)
                }
                elevation = 999f
            }
            webContainer.addView(cursor)

            val pos = CursorPosHolder()
            pos.x = webViewHeight / 2f; pos.y = webViewHeight / 2f
            cursor.translationX = pos.x - cursorSize / 2f
            cursor.translationY = pos.y - cursorSize / 2f

            webContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    webContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    pos.x = webContainer.width / 2f; pos.y = webContainer.height / 2f
                    cursor.translationX = pos.x - cursorSize / 2f
                    cursor.translationY = pos.y - cursorSize / 2f
                }
            })

            val step = CURSOR_STEP_DP * dp
            webContainer.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { moveCursor(pos, cursor, cursorSize, webContainer, 0f, -step); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursor(pos, cursor, cursorSize, webContainer, 0f, step); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(pos, cursor, cursorSize, webContainer, -step, 0f); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(pos, cursor, cursorSize, webContainer, step, 0f); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { clickAtCursor(pos, webView); true }
                    else -> false
                }
            }
            webContainer.requestFocus()
        }
        container.addView(webContainer)

        val btnContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = (8 * dp).toInt() }
        }
        btnContainer.addView(Button(activity).apply {
            text = "Back"
            setOnClickListener {
                val wv = webView
                if (wv != null && wv.canGoBack()) wv.goBack()
            }
        })
        btnContainer.addView(Button(activity).apply {
            text = "Reload"
            setOnClickListener { webView?.reload() }
        })
        if (mode == "queue") {
            btnContainer.addView(Button(activity).apply {
                text = "Skip"
                setOnClickListener {
                    if (resolved.get()) return@setOnClickListener
                    advanceQueue()
                }
            })
        }
        btnContainer.addView(Button(activity).apply {
            text = if (mode == "cf") "Done" else "Save & Close"
            setOnClickListener {
                if (mode == "cf") {
                    if (!tryFinishCfFromCookies()) {
                        statusText?.text = "No cf_clearance found yet."
                    }
                } else {
                    finishQueue(true)
                }
            }
        })
        btnContainer.addView(Button(activity).apply {
            text = "Cancel"
            setOnClickListener {
                if (mode == "cf") finishCf(false) else finishQueue(true)
            }
        })
        container.addView(btnContainer)

        dialog = AlertDialog.Builder(activity).setView(container).setCancelable(false).create()
        webView?.setTag(dialog)
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                backOrDismiss()
                true
            } else false
        }
        dialog?.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!resolved.get()) {
                resolved.set(true)
                try { webView?.destroy() } catch (e: Exception) {}
                try {
                    onDone?.invoke(
                        if (mode == "cf") java.lang.Boolean.FALSE
                        else Tw4aShortenerResult(landings.toMap(), false)
                    )
                } catch (e: Exception) {}
            }
        }
        dialog?.show()
        dialog?.window?.apply {
            setLayout(dialogW, dialogH)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            flush()
        }

        val archiveSession = tw4aSessionHeaders("archive.toonworld4all.me")
        val startUrl = if (mode == "cf") targetUrl else (queue.firstOrNull() ?: targetUrl)
        if (mode == "queue") {
            val cm = CookieManager.getInstance()
            archiveSession["Cookie"]?.split("; ")?.forEach { pair ->
                if (pair.contains("=")) {
                    try { cm.setCookie("https://archive.toonworld4all.me", pair) } catch (e: Exception) {}
                }
            }
            cm.flush()
        }
        webView?.loadUrl(startUrl)
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        if (mode == "cf") {
            handler.postDelayed({ finishCf(false) }, SOLVER_TIMEOUT_MS)
        } else {
            handler.postDelayed({ finishQueue(true) }, QUEUE_TIMEOUT_MS)
        }
    }

    private fun moveCursor(pos: CursorPosHolder, cursorView: View, cursorSize: Int, container: View, dx: Float, dy: Float) {
        pos.x = (pos.x + dx).coerceIn(0f, container.width.toFloat())
        pos.y = (pos.y + dy).coerceIn(0f, container.height.toFloat())
        cursorView.translationX = pos.x - cursorSize / 2f
        cursorView.translationY = pos.y - cursorSize / 2f
    }

    private fun clickAtCursor(pos: CursorPosHolder, webView: WebView?) {
        val wv = webView ?: return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, pos.x, pos.y, 0)
        val up = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, pos.x, pos.y, 0)
        try { wv.dispatchTouchEvent(down); wv.dispatchTouchEvent(up) } catch (e: Exception) {}
        finally { down.recycle(); up.recycle() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context): WebView {
        return WebView(context).apply {
            isFocusable = true; isFocusableInTouchMode = true; requestFocus()
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true; allowFileAccess = true; loadsImagesAutomatically = true
                userAgentString = MOBILE_UA
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!resolved.get()) statusText?.text = "Loading... $newProgress%"
                }
                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
                ): Boolean = false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (mode == "cf") return false
                    if (!request.isForMainFrame) return false
                    return !isAllowed(url)
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    url?.let { maybeCapture(it) }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (resolved.get()) return
                    if (url != null && mode == "queue") {
                        val title = view?.title ?: ""
                        if (!isChallengeTitle(title)) {
                            maybeCapture(url, finished = true)
                        }
                    }
                    if (resolved.get()) return
                    if (mode == "queue") {
                        runCatching { view?.evaluateJavascript(SHORTENER_CLICKER, null) }
                        val remain = pending.size - landings.size
                        statusText?.text = "Tap Continue${if (remain > 1) " ($remain links left)" else ""} or verify the captcha..."
                    } else {
                        val title = view?.title ?: ""
                        if (isChallengeTitle(title)) {
                            statusText?.text = "Challenge active - solve the check"
                        } else {
                            statusText?.text = "Page loaded - checking cookies..."
                        }
                    }
                }
            }
        }
    }

    private fun isChallengeTitle(title: String): Boolean {
        val lower = title.lowercase()
        return CF_CHALLENGE_TITLES.any { lower.contains(it) }
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        sessionScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        try { webView?.apply { stopLoading(); destroy() } } catch (e: Exception) {}
        webView = null
        try { dialog?.dismiss() } catch (e: Exception) {}
        dialog = null
    }
}

internal suspend fun showTw4aCFBypassDialogAndWait(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val cfDialog = Tw4aWebDialog(mode = "cf", targetUrl = url) { result ->
            if (cont.isActive) cont.resume(result == java.lang.Boolean.TRUE)
        }
        try { cfDialog.show(activity) } catch (e: Exception) {
            Log.e(TAG, "show dialog: ${e.message}")
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { cfDialog.dismiss() }
    }
}

internal suspend fun showTw4aShortenerSessionAndWait(
    links: List<String>,
    nextItem: (suspend () -> String?)? = null
): Tw4aShortenerResult =
    withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity as? AppCompatActivity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return@withContext Tw4aShortenerResult(emptyMap(), false)
        }
        suspendCancellableCoroutine { cont ->
            val sDialog = Tw4aWebDialog(mode = "queue", targetUrl = links.firstOrNull() ?: "", queue = links, nextItem = nextItem) { result ->
                if (cont.isActive) cont.resume(result as? Tw4aShortenerResult ?: Tw4aShortenerResult(emptyMap(), false))
            }
            try { sDialog.show(activity) } catch (e: Exception) {
                Log.e(TAG, "show shortener dialog: ${e.message}")
                if (cont.isActive) cont.resume(Tw4aShortenerResult(emptyMap(), false))
            }
            cont.invokeOnCancellation { sDialog.dismiss() }
        }
    }

@Volatile private var shortenerRefusedUntil = 0L

internal fun markTw4aShortenerRefused() {
    shortenerRefusedUntil = System.currentTimeMillis() + 90_000L
}

internal fun tw4aShortenerRefused(): Boolean {
    return System.currentTimeMillis() < shortenerRefusedUntil
}
