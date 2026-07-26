package com.laddu100

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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "MkvBase_CF"

// Cloudflare challenge page titles. When the WebView's page title matches one of these,
// we know the CF interstitial is showing and the user needs to solve it. We don't treat
// this as "solved" — we keep polling CookieManager for cf_clearance instead.
private val CF_CHALLENGE_TITLES = listOf(
    "just a moment", "just a moment...", "checking your browser",
    "attention required", "ddos-guard", "one more step"
)

// Cookie TTL. CF cookies on mkvbase.site typically last several hours; we cache for 15h
// to match CNCVerse's strategy of minimizing user friction. The mkv_client_key + mkv_seq
// cookies are tied to the CF session so they expire together.
private const val COOKIE_TTL_MS = 15L * 60 * 60 * 1000

// Hard timeout for the WebView solver. If the user hasn't solved the challenge in 120s,
// we give up, destroy the WebView and resume with null. Prevents coroutine leaks.
private const val SOLVER_TIMEOUT_MS = 120_000L

// Polling interval for cookie extraction. 1s matches CNCVerse — fast enough to detect
// cf_clearance the moment it lands, without hammering CookieManager.
private const val POLL_INTERVAL_MS = 1000L

// Cursor step in dp. 10dp per D-Pad press feels right on a TV — small enough to be precise,
// large enough to reach the Turnstile checkbox in a few presses.
private const val CURSOR_STEP_DP = 10f

// In-process fast cache for CF cookies. Mirrors the persistent values in
// MkvBasePlugin.cfCookies/cfUserAgent/cfCookieHost (which use CloudStreamApp.setKey/getKey
// so they survive app restarts and can be cleared from the Settings fragment).
//
// Why both? CloudStreamApp.getKey is slow (SharedPreferences + JSON deserialization) and
// we call it on every HTTP request. The in-process cache avoids that overhead. The Settings
// fragment writes through to BOTH stores so the cache stays consistent.
internal object MkvBaseCFStore {
    private const val PREFS_NAME = "MkvBaseCFBypass"
    private const val KEY_COOKIES = "cf_cookies"
    private const val KEY_UA = "cf_user_agent"
    private const val KEY_HOST = "cf_cookie_host"
    private const val KEY_TIMESTAMP = "cf_timestamp"

    private var prefs: android.content.SharedPreferences? = null
    @Volatile private var cachedCookies: String? = null
    @Volatile private var cachedUA: String? = null
    @Volatile private var cachedHost: String? = null
    @Volatile private var cachedTimestamp: Long = 0L

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Load from local prefs first (fast path).
            cachedCookies = prefs?.getString(KEY_COOKIES, null)
            cachedUA = prefs?.getString(KEY_UA, null)
            cachedHost = prefs?.getString(KEY_HOST, null)
            cachedTimestamp = prefs?.getLong(KEY_TIMESTAMP, 0L) ?: 0L
            // If local prefs are empty, try loading from the persistent CloudStreamApp
            // datastore (set by the Settings fragment or a previous plugin version).
            if (cachedCookies.isNullOrBlank()) {
                val persisted = MkvBasePlugin.cfCookies
                val persistedUa = MkvBasePlugin.cfUserAgent
                val persistedHost = MkvBasePlugin.cfCookieHost
                if (persisted.isNotBlank()) {
                    cachedCookies = persisted
                    cachedUA = persistedUa
                    cachedHost = persistedHost
                    cachedTimestamp = System.currentTimeMillis()
                    prefs?.edit()?.apply {
                        putString(KEY_COOKIES, persisted)
                        putString(KEY_UA, persistedUa)
                        putString(KEY_HOST, persistedHost)
                        putLong(KEY_TIMESTAMP, cachedTimestamp)
                    }?.apply()
                }
            }
        }
    }

    fun getCookies(): String? {
        val cookies = cachedCookies
        if (cookies.isNullOrBlank()) return null
        if (System.currentTimeMillis() - cachedTimestamp > COOKIE_TTL_MS) {
            clear()
            return null
        }
        return cookies
    }

    fun getUserAgent(): String? = cachedUA?.takeIf { it.isNotBlank() }
    fun getHost(): String? = cachedHost?.takeIf { it.isNotBlank() }

    // Save to BOTH the in-process cache AND the persistent CloudStreamApp datastore.
    // The datastore is what the Settings fragment reads to update its button label.
    fun save(cookies: String, userAgent: String, host: String) {
        cachedCookies = cookies
        cachedUA = userAgent
        cachedHost = host
        cachedTimestamp = System.currentTimeMillis()
        prefs?.edit()?.apply {
            putString(KEY_COOKIES, cookies)
            putString(KEY_UA, userAgent)
            putString(KEY_HOST, host)
            putLong(KEY_TIMESTAMP, cachedTimestamp)
        }?.apply()
        // Mirror to the persistent datastore so the Settings fragment sees the update.
        MkvBasePlugin.cfCookies = cookies
        MkvBasePlugin.cfUserAgent = userAgent
        MkvBasePlugin.cfCookieHost = host
    }

    fun clear() {
        cachedCookies = null
        cachedUA = null
        cachedHost = null
        cachedTimestamp = 0L
        prefs?.edit()?.clear()?.apply()
        // Also clear the persistent datastore so the Settings fragment label resets.
        MkvBasePlugin.cfCookies = ""
        MkvBasePlugin.cfUserAgent = ""
        MkvBasePlugin.cfCookieHost = ""
    }
}

// Detects whether an HTTP response is a Cloudflare challenge (vs a normal error).
//
// CRITICAL: mkvbase.site's own API returns HTTP 403 with body
// {"error":"Human interaction verification failed"} when the PoW signature is wrong.
// That is NOT a Cloudflare block — it's mkvbase's anti-bot rejecting our request.
// Treating it as CF would loop the WebView solver forever (the solver can't fix a bad
// signature). So for 403, we ONLY treat it as CF if the body contains explicit CF
// challenge markers (Turnstile/JS challenge HTML), NOT the JSON error string.
internal fun isCloudflareBlocked(response: NiceResponse): Boolean {
    val code = response.code
    // 503 is almost always CF managed challenge or DDoS-Guard. Treat as CF.
    if (code == 503) return true
    val body = try { response.text.lowercase() } catch (e: Exception) { "" }
    // mkvbase's own "Human interaction verification failed" JSON error is NOT CF.
    // Explicitly exclude it so we don't loop the solver on signature failures.
    if (body.contains("\"error\":\"human interaction verification failed\"")) return false
    // For 403, only treat as CF if body has explicit CF challenge markers.
    if (code == 403) {
        if (body.contains("just a moment") && body.contains("challenge-platform")) return true
        if (body.contains("checking your browser") && body.contains("cloudflare")) return true
        if (body.contains("cf-browser-verification")) return true
        if (body.contains("checking if the site connection is secure")) return true
        if (body.contains("just a moment")) return true
        return false
    }
    // For other status codes, detect via HTML markers.
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

// Single mutex ensures only one WebView solver runs at a time. Concurrent solvers would
// race on CookieManager and confuse the user (multiple dialogs stacked).
private val cfBypassMutex = Mutex()

// Mutable holder for cursor position. All cursor operations (D-Pad key events, polling
// runnable, clickAtCursor) run on the main thread, so no synchronization is needed —
// plain var fields are sufficient and clearer than AtomicFloat (which doesn't exist in
// Java anyway). Defined at file scope so moveCursor/clickAtCursor can cast to it.
private class CursorPosHolder { var x: Float = 0f; var y: Float = 0f }

// The WebView-based CF solver. Modeled on CNCVerse's solveCloudflareInWebView with the
// critical addition of a D-Pad-driven fake cursor for Android TV users.
//
// Why a cursor? Cloudflare Turnstile renders an iframe with a checkbox that must be
// physically clicked. On phones/tablets the user taps the screen. On Android TV (where
// CloudStream runs most) there is no touchscreen — only a D-pad remote. Without a fake
// cursor that dispatches MotionEvents, TV users can NEVER solve Turnstile and the plugin
// is dead on arrival for them.
//
// The cursor is a small red circle overlaid on the WebView. D-Pad moves it, OK/Enter
// dispatches a down+up MotionEvent pair (120ms apart, matching a real tap). Once the
// checkbox is clicked, Turnstile runs its JS and sets cf_clearance, which we extract
// from CookieManager and resume the coroutine with.
@SuppressLint("InflateParams")
private class MkvBaseCFDialog(
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null
) {
    private var dialog: AlertDialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var cursorView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy {
        try {
            val uri = Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse target host: ${e.message}")
            targetUrl
        }
    }

    // Single extraction routine called from both onPageFinished and the polling runnable.
    // Idempotent — the AtomicBoolean guarantees we only resolve once.
    private fun extractAndFinish() {
        if (resolved.get()) return
        try {
            CookieManager.getInstance().flush()
            val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""
            // We need BOTH cf_clearance (Cloudflare) AND mkv_client_key (mkvbase's own
            // session token). Without mkv_client_key the API PoW/signature flow fails.
            // If we only have cf_clearance after 30s, save anyway — some pages set
            // mkv_client_key lazily after a follow-up request.
            val hasCfClearance = cookieStr.contains("cf_clearance")
            val hasMkvClientKey = cookieStr.contains("mkv_client_key")
            if (hasCfClearance && hasMkvClientKey) {
                finishSuccess(cookieStr)
            } else if (hasCfClearance && pollElapsedMs >= 30_000L) {
                Log.d(TAG, "cf_clearance found but no mkv_client_key after 30s, saving anyway")
                finishSuccess(cookieStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractAndFinish error: ${e.message}")
        }
    }

    private fun finishSuccess(cookieStr: String) {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        val ua = webView?.settings?.userAgentString ?: ""
        MkvBaseCFStore.save(cookieStr, ua, targetHost)
        Log.d(TAG, "Cookies saved (len=${cookieStr.length}) host=$targetHost")
        try { webView?.destroy() } catch (e: Exception) { Log.e(TAG, "destroy: ${e.message}") }
        try { (webView?.getTag() as? Dialog)?.dismiss() } catch (e: Exception) {}
        try { onFinished?.invoke(true) } catch (e: Exception) { Log.e(TAG, "onFinished: ${e.message}") }
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
                    statusText?.text = "Timed out. Tap Cancel to close."
                    finishFailure()
                } else {
                    statusText?.text = "Waiting for cookies... (${pollElapsedMs / 1000}s)"
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun show(activity: AppCompatActivity) {
        val dp = activity.resources.displayMetrics.density
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        // 95% width, 90% height matches CNCVerse — leaves a sliver of background visible
        // so the user can see the dialog is a dialog and tap outside to cancel on phones.
        val dialogW = (screenW * 0.95f).toInt()
        val dialogH = (screenH * 0.9f).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        val titleView = TextView(activity).apply {
            text = "Cloudflare Bypass"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        container.addView(titleView)

        val statusView = TextView(activity).apply {
            text = "Loading challenge page..."
            textSize = 12f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        statusText = statusView
        container.addView(statusView)

        // TV users see D-pad instructions; phone/tablet users see tap instructions.
        val isTv = try { Globals.isLayout(Globals.TV) } catch (e: Throwable) { false }
        val hintView = TextView(activity).apply {
            text = if (isTv) {
                "Use D-pad to move the cursor, OK/Enter to click the captcha checkbox."
            } else {
                "Solve the CAPTCHA below, then tap Done."
            }
            textSize = 11f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        container.addView(hintView)

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = (8 * dp).toInt() }
        }
        progressBar = progress
        container.addView(progress)

        // The web container holds the WebView AND (on TV) the fake cursor overlay.
        // On TV the cursor sits on top of the WebView and is moved by D-Pad events.
        //
        // CRITICAL: Use an EXPLICIT height, not weight=1 with height=0. AlertDialog's
        // content frame is WRAP_CONTENT, so a weighted child with height=0 gets 0 actual
        // height — the WebView becomes invisible and the user can't see the Cloudflare
        // challenge. Using 65% of screen height (matching v12) guarantees the WebView is
        // always visible regardless of how AlertDialog measures its content frame.
        val webViewHeight = (screenH * 0.65f).toInt()
        val webContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, webViewHeight)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        webView = buildWebView(activity)
        webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))

        if (isTv) {
            // Build the fake cursor. Red circle, semi-transparent, white stroke, elevated
            // above the WebView so it's always visible. 22dp is large enough to see from
            // couch distance on a TV.
            val cursorSize = (22 * dp).toInt()
            val cursor = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(160, 255, 50, 50))
                    setStroke((2 * dp).toInt(), Color.WHITE)
                }
                background = shape
                elevation = 999f
            }
            cursorView = cursor
            webContainer.addView(cursor)

            // Track cursor position in a simple mutable holder. All cursor ops (D-Pad
            // key events, polling runnable, clickAtCursor) run on the main thread, so
            // no atomic types needed — plain Float vars are sufficient and clearer.
            val pos = CursorPosHolder()
            pos.x = webViewHeight / 2f
            pos.y = webViewHeight / 2f
            cursor.translationX = pos.x - cursorSize / 2f
            cursor.translationY = pos.y - cursorSize / 2f

            webContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    webContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    pos.x = webContainer.width / 2f
                    pos.y = webContainer.height / 2f
                    cursor.translationX = pos.x - cursorSize / 2f
                    cursor.translationY = pos.y - cursorSize / 2f
                }
            })

            val step = CURSOR_STEP_DP * dp
            webContainer.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        moveCursor(pos, cursor, cursorSize, webContainer, 0f, -step)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        moveCursor(pos, cursor, cursorSize, webContainer, 0f, step)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveCursor(pos, cursor, cursorSize, webContainer, -step, 0f)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveCursor(pos, cursor, cursorSize, webContainer, step, 0f)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        clickAtCursor(pos, webView)
                        true
                    }
                    else -> false
                }
            }
            // Cursor needs focus to receive D-Pad events.
            webContainer.requestFocus()
        }
        container.addView(webContainer)

        val btnContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = (8 * dp).toInt() }
        }

        val doneButton = android.widget.Button(activity).apply {
            text = "Done"
            setOnClickListener {
                CookieManager.getInstance().flush()
                extractAndFinish()
                if (!resolved.get()) {
                    statusText?.text = "No cookies yet. Solve the CAPTCHA first."
                }
            }
        }
        btnContainer.addView(doneButton)

        val cancelButton = android.widget.Button(activity).apply {
            text = "Cancel"
            setOnClickListener { finishFailure() }
        }
        btnContainer.addView(cancelButton)
        container.addView(btnContainer)

        dialog = AlertDialog.Builder(activity)
            .setView(container)
            .setCancelable(false)
            .create()

        // Tag the WebView with the dialog so extractAndFinish can dismiss it via
        // wv.getTag() without needing a separate field. Single source of truth.
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
        // Size the dialog window explicitly — AlertDialog default wraps content which
        // would collapse the WebView to 0 height inside a weighted LinearLayout.
        dialog?.window?.apply {
            setLayout(dialogW, dialogH)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        // Clear any stale CF cookies so the WebView starts a fresh challenge.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                setCookie(targetHost, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            }
            flush()
        }
        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)

        // Hard timeout. Even if the user walks away, the coroutine resumes and the
        // WebView is destroyed. No leaks.
        handler.postDelayed({ finishFailure() }, SOLVER_TIMEOUT_MS)
    }

    // Move the cursor, clamping within the container bounds. Updates the cursor View's
    // translation so it visually moves, and stores the new position for clickAtCursor.
    private fun moveCursor(
        pos: CursorPosHolder,
        cursorView: View,
        cursorSize: Int,
        container: View,
        dx: Float,
        dy: Float
    ) {
        pos.x = (pos.x + dx).coerceIn(0f, container.width.toFloat())
        pos.y = (pos.y + dy).coerceIn(0f, container.height.toFloat())
        cursorView.translationX = pos.x - cursorSize / 2f
        cursorView.translationY = pos.y - cursorSize / 2f
    }

    // Dispatch a down+up MotionEvent pair at the cursor position. 120ms gap matches a
    // real human tap — Turnstile checks for bot-like instant taps. The MotionEvents
    // are dispatched to the WebView (not the container) so they reach the Turnstile
    // iframe's hit region.
    private fun clickAtCursor(
        pos: CursorPosHolder,
        webView: WebView?
    ) {
        val wv = webView ?: return
        val x = pos.x
        val y = pos.y
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, x, y, 0)
        try {
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
        } catch (e: Exception) {
            Log.e(TAG, "clickAtCursor: ${e.message}")
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context): WebView {
        return WebView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // CF challenge JS sometimes loads mixed content; allow it.
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true
                allowFileAccess = true
                loadsImagesAutomatically = true
                // Mobile Pixel UA — CF is more lenient with mobile UAs and the
                // Turnstile challenge is often easier/shorter on mobile.
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                // CRITICAL: CF Turnstile JS requires media playback without user gesture.
                // Without this, the challenge JS may stall waiting for a gesture that
                // never comes on TV (where there's no touchscreen).
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
                    Log.d(TAG, "onPageFinished title='$title' url=$url")

                    if (isChallengeTitle(title)) {
                        statusText?.text = "Challenge active - solve the CAPTCHA above"
                        // Even while the challenge is showing, try extracting — Turnstile
                        // sometimes sets cf_clearance before the title changes.
                        extractAndFinish()
                        return
                    }

                    statusText?.text = "Page loaded - checking cookies..."
                    extractAndFinish()

                    // Also try extracting from the URL's host (in case of redirect to a
                    // different subdomain). This handles the case where CF sets the
                    // clearance cookie on www.mkvbase.site but the dialog was loaded
                    // from mkvbase.site.
                    url?.let {
                        try {
                            val uri = Uri.parse(it)
                            val altHost = "${uri.scheme}://${uri.host}"
                            if (altHost != targetHost) {
                                val altCookies = CookieManager.getInstance().getCookie(altHost) ?: ""
                                if (altCookies.contains("cf_clearance") && altCookies.contains("mkv_client_key")) {
                                    if (!resolved.get()) {
                                        resolved.set(true)
                                        handler.removeCallbacksAndMessages(null)
                                        val ua = webView?.settings?.userAgentString ?: ""
                                        MkvBaseCFStore.save(altCookies, ua, altHost)
                                        try { webView?.destroy() } catch (e: Exception) {}
                                        try { dialog?.dismiss() } catch (e: Exception) {}
                                        try { onFinished?.invoke(true) } catch (e: Exception) {}
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "alt host extract: ${e.message}")
                        }
                    }
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

// Public entry point for the Settings fragment's "Bypass Cloudflare" button.
// Shows the WebView CF solver dialog and waits for the user to solve the challenge.
// Returns true if cookies were saved, false if cancelled/failed.
suspend fun showMkvBaseCFBypassDialogAndWait(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        Log.e(TAG, "No activity available to show CF dialog")
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val cfDialog = MkvBaseCFDialog(url) { success ->
            if (cont.isActive) cont.resume(success)
        }
        try {
            cfDialog.show(activity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show CF dialog: ${e.message}")
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { cfDialog.dismiss() }
    }
}

// Public entry point. Builds CF-aware headers, makes the request, and if CF blocks us,
// invokes the WebView solver (with cursor support for TV) and retries.
//
// The mutex serializes bypass attempts so concurrent requests don't each pop a dialog.
// Once one request solves CF, the saved cookies satisfy the other waiting requests.
suspend fun mkvBaseGet(
    url: String,
    headers: Map<String, String> = emptyMap()
): NiceResponse {
    val targetHost = try {
        val uri = Uri.parse(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) {
        Log.e(TAG, "URL parse failed: ${e.message}")
        url
    }

    fun buildCfHeaders(): Map<String, String> {
        val h = headers.toMutableMap()
        if (!h.containsKey("Accept")) {
            h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        }
        if (!h.containsKey("Accept-Language")) {
            h["Accept-Language"] = "en-US,en;q=0.5"
        }
        h["sec-ch-ua-mobile"] = "?1"
        h["sec-ch-ua-platform"] = "\"Android\""
        MkvBaseCFStore.getCookies()?.let { cookies ->
            h["Cookie"] = cookies
        }
        MkvBaseCFStore.getUserAgent()?.let { ua ->
            h["User-Agent"] = ua
        } ?: run {
            if (!h.containsKey("User-Agent")) {
                h["User-Agent"] = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
        }
        return h
    }

    // First attempt with cached cookies (if any). Most requests should succeed here
    // without ever showing a dialog.
    var response = try {
        app.get(url, headers = buildCfHeaders(), timeout = 30_000L)
    } catch (e: Exception) {
        Log.e(TAG, "First request failed: ${e.message}")
        throw e
    }

    if (!isCloudflareBlocked(response)) return response

    Log.d(TAG, "Cloudflare blocked (HTTP ${response.code}) for $url - triggering bypass")

    // Mutex — only one solver at a time. Other concurrent callers wait, then re-check
    // the cookie cache (which the first solver will have populated).
    cfBypassMutex.withLock {
        // Re-check cookies — another coroutine may have just solved CF while we waited.
        val cachedCookies = MkvBaseCFStore.getCookies()
        if (cachedCookies != null) {
            response = try {
                app.get(url, headers = buildCfHeaders(), timeout = 30_000L)
            } catch (e: Exception) {
                Log.e(TAG, "Retry with cached cookies failed: ${e.message}")
                throw e
            }
            if (!isCloudflareBlocked(response)) {
                Log.d(TAG, "Succeeded with cookies from another coroutine")
                return response
            }
        }

        // No valid cookies — clear and invoke the WebView solver.
        MkvBaseCFStore.clear()
        val bypassSuccess = showMkvBaseCFBypassDialogAndWait(targetHost)

        if (!bypassSuccess) {
            Log.e(TAG, "CF bypass dialog failed/cancelled")
            return@withLock
        }

        // Retry up to 2 times with the fresh cookies.
        for (attempt in 1..2) {
            response = try {
                app.get(url, headers = buildCfHeaders(), timeout = 30_000L)
            } catch (e: Exception) {
                Log.e(TAG, "Retry $attempt failed: ${e.message}")
                throw e
            }
            if (!isCloudflareBlocked(response)) {
                Log.d(TAG, "Request succeeded after CF bypass (attempt $attempt)")
                return@withLock
            }
            Log.e(TAG, "Still CF-blocked after retry $attempt")
        }
    }

    return response
}

fun initMkvBaseCFBypass(context: Context) {
    MkvBaseCFStore.init(context)
    Log.d(TAG, "MkvBase CF bypass initialized (cursor-enabled, TTL=15h)")
}
