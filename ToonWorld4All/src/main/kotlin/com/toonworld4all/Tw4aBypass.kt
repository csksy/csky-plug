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
private const val SOLVER_TIMEOUT_MS = 120_000L
private const val POLL_INTERVAL_MS = 1000L
private const val CURSOR_STEP_DP = 10f
private const val BYPASS_COOLDOWN_MS = 60_000L
private const val STORE_KEY = "TW4A_CF_SESSIONS"

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

    fun getSession(host: String): Tw4aCFSession? {
        val s = sessions[host] ?: return null
        if (s.cookies.isBlank()) return null
        if (System.currentTimeMillis() - s.timestamp > COOKIE_TTL_MS) {
            clear(host)
            return null
        }
        return s
    }

    fun save(host: String, cookies: String, userAgent: String) {
        val s = Tw4aCFSession()
        s.cookies = cookies
        s.userAgent = userAgent
        s.timestamp = System.currentTimeMillis()
        sessions[host] = s
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

private fun isChallengeTitle(title: String): Boolean {
    val lower = title.lowercase()
    return CF_CHALLENGE_TITLES.any { lower.contains(it) }
}

private val cfBypassMutex = Mutex()
private class CursorPosHolder { var x: Float = 0f; var y: Float = 0f }

internal val TW4A_FILE_HOST = Regex(
    """(?i)(hubcloud\.|gdflix|filepress|filebee|gdtot|appdrive|gdrive\.|pixeldrain""" +
            """|gofile\.io|drive\.google\.com|googleusercontent|mega\.nz|mega\.co\.nz""" +
            """|\.mp4|\.mkv|\.m3u8|\.ts(?![a-z])|workers\.dev|busycdn)"""
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

@SuppressLint("InflateParams")
private class Tw4aCFDialog(
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null
) {
    private var dialog: AlertDialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy {
        try {
            val uri = Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) { targetUrl }
    }

    private fun extractAndFinish() {
        if (resolved.get()) return
        try {
            CookieManager.getInstance().flush()
            val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""
            if (cookieStr.contains("cf_clearance")) {
                finishSuccess(cookieStr)
                return
            }
            val currentUrl = webView?.url
            if (currentUrl != null && currentUrl != targetUrl) {
                try {
                    val uri = Uri.parse(currentUrl)
                    val altHost = "${uri.scheme}://${uri.host}"
                    val altCookies = CookieManager.getInstance().getCookie(altHost) ?: ""
                    if (altCookies.contains("cf_clearance")) {
                        finishSuccessForHost(altCookies, altHost)
                        return
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "extract: ${e.message}")
        }
    }

    private fun finishSuccess(cookieStr: String) {
        finishSuccessForHost(cookieStr, targetHost)
    }

    private fun finishSuccessForHost(cookieStr: String, host: String) {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        val ua = webView?.settings?.userAgentString ?: ""
        val hostKey = try { Uri.parse(host).host ?: host } catch (e: Exception) { host }
        Tw4aCFStore.save(hostKey, cookieStr, ua)
        try { webView?.destroy() } catch (e: Exception) {}
        try { (webView?.getTag() as? Dialog)?.dismiss() } catch (e: Exception) {}
        try { onFinished?.invoke(true) } catch (e: Exception) {}
    }

    private fun finishFailure() {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        try { webView?.destroy() } catch (e: Exception) {}
        try { dialog?.dismiss() } catch (e: Exception) {}
        try { onFinished?.invoke(false) } catch (e: Exception) {}
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (resolved.get() || dialog == null || dialog?.isShowing != true) return
            pollElapsedMs += POLL_INTERVAL_MS
            extractAndFinish()
            if (!resolved.get()) {
                if (pollElapsedMs >= SOLVER_TIMEOUT_MS) {
                    finishFailure()
                } else {
                    statusText?.text = "Waiting... (${pollElapsedMs / 1000}s)"
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: AppCompatActivity) {
        val dp = activity.resources.displayMetrics.density
        val screenH = activity.resources.displayMetrics.heightPixels
        val dialogW = (activity.resources.displayMetrics.widthPixels * 0.95f).toInt()
        val dialogH = (screenH * 0.9f).toInt()
        val webViewHeight = (screenH * 0.65f).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        container.addView(TextView(activity).apply {
            text = "Cloudflare Bypass"
            textSize = 16f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        val statusView = TextView(activity).apply {
            text = "Loading..."
            textSize = 12f; setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        statusText = statusView
        container.addView(statusView)

        val isTv = try { Globals.isLayout(Globals.TV) } catch (e: Throwable) { false }
        container.addView(TextView(activity).apply {
            text = if (isTv) "Use D-pad to move cursor, OK to click."
            else "Solve the CAPTCHA below, then tap Done."
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
            text = "Done"
            setOnClickListener {
                CookieManager.getInstance().flush()
                extractAndFinish()
                if (!resolved.get()) statusText?.text = "No cf_clearance found."
            }
        })
        btnContainer.addView(Button(activity).apply {
            text = "Cancel"
            setOnClickListener { finishFailure() }
        })
        container.addView(btnContainer)

        dialog = AlertDialog.Builder(activity).setView(container).setCancelable(false).create()
        webView?.setTag(dialog)
        dialog?.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!resolved.get()) {
                resolved.set(true)
                try { webView?.destroy() } catch (e: Exception) {}
                try { onFinished?.invoke(false) } catch (e: Exception) {}
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
        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
        handler.postDelayed({ finishFailure() }, SOLVER_TIMEOUT_MS)
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
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                mediaPlaybackRequiresUserGesture = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!resolved.get()) statusText?.text = "Loading... $newProgress%"
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (resolved.get()) return
                    val title = view?.title ?: ""
                    if (isChallengeTitle(title)) {
                        statusText?.text = "Challenge active - solve the CAPTCHA"
                        extractAndFinish()
                        return
                    }
                    statusText?.text = "Page loaded - checking cookies..."
                    extractAndFinish()
                }
            }
        }
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        try { webView?.apply { stopLoading(); destroy() } } catch (e: Exception) {}
        webView = null
        try { dialog?.dismiss() } catch (e: Exception) {}
        dialog = null
    }
}

suspend fun showTw4aCFBypassDialogAndWait(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val cfDialog = Tw4aCFDialog(url) { success ->
            if (cont.isActive) cont.resume(success)
        }
        try { cfDialog.show(activity) } catch (e: Exception) {
            Log.e(TAG, "show dialog: ${e.message}")
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { cfDialog.dismiss() }
    }
}

private val SHORTENER_CLICKER = """
    (function () {
        var h = (location.hostname || "").toLowerCase();
        if (h.indexOf("gplinks.") !== -1) {
            if (window.__tw4aGpl) return;
            window.__tw4aGpl = 1;
            var waited = 0, posting = false;
            var t = setInterval(function () {
                waited += 5;
                if (waited < 10) return;
                try {
                    var form = document.querySelector("form[action]");
                    if (form && !posting) {
                        posting = true;
                        fetch(form.action, {
                            method: "POST",
                            credentials: "same-origin",
                            headers: { "X-Requested-With": "XMLHttpRequest" },
                            body: new URLSearchParams(new FormData(form))
                        }).then(function (r) { return r.json(); })
                          .then(function (res) {
                              if (res && res.url) location.href = res.url;
                              else posting = false;
                          })
                          .catch(function () { posting = false; });
                        return;
                    }
                    var b = document.querySelector(".get-link, #get-link, .skip-ad, #skip-ad");
                    if (b && !b.disabled) b.click();
                } catch (e) { }
                if (waited > 120) clearInterval(t);
            }, 5000);
            return;
        }
        if (window.__tw4aClicker) return;
        window.__tw4aClicker = 1;
        var clicks = 0;
        var t = setInterval(function () {
            clicks++;
            try {
                var b;
                if ((b = document.querySelector('button[data-ref="continue"]')) &&
                    !b.disabled) { b.click(); return; }
                if ((b = document.querySelector('button[data-ref="captcha"]')) &&
                    !b.disabled) { b.click(); return; }
                if ((b = document.querySelector('button#invisibleCaptchaShortlink')) &&
                    !b.disabled) { b.click(); return; }
                if ((b = document.querySelector('button#submit-button')) &&
                    !b.disabled) { b.click(); return; }
                if ((b = document.querySelector('button#VerifyBtn')) &&
                    !b.disabled) { b.click(); return; }
                var g = document.querySelector('a.gate-btn-skip');
                if (g && g.href && g.href.indexOf(location.origin) !== 0 &&
                    g.href.charAt(g.href.length - 1) !== '#') { g.click(); return; }
            } catch (e) { }
            if (clicks > 90) clearInterval(t);
        }, 1500);
    })();
""".trimIndent()

@SuppressLint("InflateParams")
private class Tw4aShortenerDialog(
    private val startUrl: String,
    private val onFinished: ((String?) -> Unit)? = null
) {
    private var dialog: AlertDialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    private val resultUrl = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private var pollElapsedMs = 0L

    private val allowedHosts = listOf(
        "exe.io", "exeygo.com", "cuty.io", "cuttty.com",
        "gplinks.co", "gplinks.com", "challenges.cloudflare.com",
        "cloudflareinsights.com", "toonworld4all.me",
        "static.cloudflareinsights.com", "fstatic.netpub.media",
        "live.demand.supply", "a.nel.cloudflare.com"
    )

    private fun hostOf(url: String): String = try {
        Uri.parse(url).host?.lowercase() ?: ""
    } catch (e: Exception) { "" }

    private fun isAllowed(url: String): Boolean {
        if (!url.startsWith("http")) return true
        if (TW4A_FILE_HOST.containsMatchIn(url) && !tw4aIsShortenerUrl(url)) return true
        val host = hostOf(url)
        if (host.isEmpty()) return true
        return allowedHosts.any { host == it || host.endsWith(".$it") }
    }

    private fun maybeCapture(url: String) {
        if (resolved.get()) return
        if (resultUrl.get() != null) return
        if (TW4A_FILE_HOST.containsMatchIn(url) && !tw4aIsShortenerUrl(url)) {
            resultUrl.set(url)
            finishSuccess(url)
        }
    }

    private fun finishSuccess(url: String) {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        try {
            CookieManager.getInstance().flush()
            val hosts = mutableListOf("archive.toonworld4all.me", "toonworld4all.me")
            val landed = hostOf(url)
            if (landed.isNotEmpty() && landed !in hosts) hosts.add(landed)
            for (host in hosts) {
                val c = CookieManager.getInstance().getCookie("https://$host")
                if (!c.isNullOrBlank()) {
                    Tw4aCFStore.mergeCookies(host, c.split("; "), webView?.settings?.userAgentString ?: "")
                }
            }
        } catch (e: Exception) {}
        try { webView?.destroy() } catch (e: Exception) {}
        try { (webView?.getTag() as? Dialog)?.dismiss() } catch (e: Exception) {}
        try { onFinished?.invoke(url) } catch (e: Exception) {}
    }

    private fun finishFailure() {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        try {
            CookieManager.getInstance().flush()
            for (host in listOf("archive.toonworld4all.me", "toonworld4all.me")) {
                val c = CookieManager.getInstance().getCookie("https://$host")
                if (!c.isNullOrBlank()) {
                    Tw4aCFStore.mergeCookies(host, c.split("; "), webView?.settings?.userAgentString ?: "")
                }
            }
        } catch (e: Exception) {}
        try { webView?.destroy() } catch (e: Exception) {}
        try { dialog?.dismiss() } catch (e: Exception) {}
        try { onFinished?.invoke(null) } catch (e: Exception) {}
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (resolved.get() || dialog == null || dialog?.isShowing != true) return
            pollElapsedMs += POLL_INTERVAL_MS
            webView?.url?.let { maybeCapture(it) }
            if (!resolved.get()) {
                if (pollElapsedMs >= SOLVER_TIMEOUT_MS) {
                    finishFailure()
                } else {
                    statusText?.text = "Opening link... (${pollElapsedMs / 1000}s)"
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: AppCompatActivity) {
        val dp = activity.resources.displayMetrics.density
        val screenH = activity.resources.displayMetrics.heightPixels
        val dialogW = (activity.resources.displayMetrics.widthPixels * 0.95f).toInt()
        val dialogH = (screenH * 0.9f).toInt()
        val webViewHeight = (screenH * 0.65f).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        container.addView(TextView(activity).apply {
            text = "Opening Download Link"
            textSize = 16f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        val statusView = TextView(activity).apply {
            text = "Loading..."
            textSize = 12f; setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        statusText = statusView
        container.addView(statusView)

        val isTv = try { Globals.isLayout(Globals.TV) } catch (e: Throwable) { false }
        container.addView(TextView(activity).apply {
            text = if (isTv) "Press Continue / verify the captcha when it appears."
            else "Tap Continue and verify the captcha when it appears."
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
            text = "Done"
            setOnClickListener {
                webView?.url?.let { maybeCapture(it) }
                if (!resolved.get()) statusText?.text = "Link not opened yet - keep going."
            }
        })
        btnContainer.addView(Button(activity).apply {
            text = "Cancel"
            setOnClickListener { finishFailure() }
        })
        container.addView(btnContainer)

        dialog = AlertDialog.Builder(activity).setView(container).setCancelable(false).create()
        webView?.setTag(dialog)
        dialog?.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!resolved.get()) {
                resolved.set(true)
                try { webView?.destroy() } catch (e: Exception) {}
                try { onFinished?.invoke(null) } catch (e: Exception) {}
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
        webView?.loadUrl(startUrl)
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        handler.postDelayed({ finishFailure() }, SOLVER_TIMEOUT_MS)
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
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
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
                    return !isAllowed(url)
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    url?.let { maybeCapture(it) }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (resolved.get()) return
                    url?.let { maybeCapture(it) }
                    runCatching { view?.evaluateJavascript(SHORTENER_CLICKER, null) }
                    statusText?.text = "Continue in the page below if asked..."
                }
            }
        }
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        try { webView?.apply { stopLoading(); destroy() } } catch (e: Exception) {}
        webView = null
        try { dialog?.dismiss() } catch (e: Exception) {}
        dialog = null
    }
}

internal suspend fun showTw4aShortenerDialogAndWait(url: String): String? =
    withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity as? AppCompatActivity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return@withContext null
        }
        val result = suspendCancellableCoroutine { cont ->
            val sDialog = Tw4aShortenerDialog(url) { res ->
                if (cont.isActive) cont.resume(res)
            }
            try { sDialog.show(activity) } catch (e: Exception) {
                Log.e(TAG, "show shortener dialog: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { sDialog.dismiss() }
        }
        if (result == null) markTw4aShortenerRefused()
        result
    }

@Volatile private var shortenerRefusedUntil = 0L

internal fun markTw4aShortenerRefused() {
    shortenerRefusedUntil = System.currentTimeMillis() + 90_000L
}

internal fun tw4aShortenerRefused(): Boolean {
    return System.currentTimeMillis() < shortenerRefusedUntil
}

internal fun tw4aNormalizeUrl(url: String): String {
    return url
        .replace("https://gdflix.dev/", "https://new4.gdflix.io/")
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

private fun buildTw4aHeaders(url: String, original: Map<String, String>): Map<String, String> {
    val h = original.toMutableMap()
    if (!h.containsKey("Accept")) h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    val host = tw4aHostOf(url)
    val s = Tw4aCFStore.getSession(host)
    if (s != null) {
        h["User-Agent"] = s.userAgent
        h["Cookie"] = s.cookies
    } else if (!h.containsKey("User-Agent")) {
        h["User-Agent"] = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
    return h
}

private fun captureSetCookies(url: String, response: NiceResponse) {
    try {
        val host = tw4aHostOf(url)
        if (host.isEmpty()) return
        val values = response.headers.values("set-cookie")
        if (values.isNullOrEmpty()) return
        val ua = (response.request.headers["User-Agent"] as? String)
            ?: Tw4aCFStore.getSession(host)?.userAgent
            ?: "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
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
