package com.laddu100

import android.annotation.SuppressLint
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
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// a handful of hosts on the drive chain sit behind a managed cloudflare
// challenge, okhttp can never pass it so the clearance is solved once in a
// webview and replayed for every later request of the same host
object TmfCf {

    private const val PREFS = "tmf_cf"
    private const val COOKIE_TTL_MS = 15L * 60 * 60 * 1000
    private const val SOLVER_TIMEOUT_MS = 60_000L
    private const val POLL_MS = 1000L

    private val CHALLENGE_TITLES = listOf(
        "just a moment", "checking your browser", "attention required",
        "one more step", "checking if the site connection is secure"
    )

    private class HostEntry(val cookies: String, val ua: String, val savedAt: Long)

    private var prefs: android.content.SharedPreferences? = null
    private val store = java.util.concurrent.ConcurrentHashMap<String, HostEntry>()
    private val solverMutex = Mutex()

    private fun storage(): android.content.SharedPreferences? {
        prefs?.let { return it }
        val ctx = try {
            CommonActivity.activity
        } catch (_: Throwable) {
            null
        } ?: return null
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
    }

    private fun cookiesFor(host: String): HostEntry? {
        store[host]?.let { saved ->
            return if (System.currentTimeMillis() - saved.savedAt < COOKIE_TTL_MS) saved else {
                store.remove(host)
                drop(host)
                null
            }
        }
        val sp = storage() ?: return null
        val raw = sp.getString(host, null) ?: return null
        val parts = raw.split("\u0001", limit = 3)
        if (parts.size < 3) return null
        val entry = HostEntry(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L)
        if (System.currentTimeMillis() - entry.savedAt >= COOKIE_TTL_MS) {
            drop(host)
            return null
        }
        store[host] = entry
        return entry
    }

    private fun save(host: String, cookies: String, ua: String) {
        val entry = HostEntry(cookies, ua, System.currentTimeMillis())
        store[host] = entry
        storage()?.edit()?.putString(host, "${cookies}\u0001$ua\u0001${entry.savedAt}")?.apply()
    }

    private fun drop(host: String) {
        store.remove(host)
        storage()?.edit()?.remove(host)?.apply()
    }

    fun hostOf(url: String): String = try {
        val uri = Uri.parse(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Exception) {
        url.substringBefore("/")
    }

    fun isChallenge(res: NiceResponse): Boolean {
        val body = try {
            res.text
        } catch (_: Exception) {
            ""
        }
        if (res.code == 403 || res.code == 503) {
            if (body.contains("challenge-platform", true)) return true
            if (CHALLENGE_TITLES.any { body.contains(it, true) }) return true
        }
        if (body.contains("challenge-platform", true) && body.length < 8000) return true
        return false
    }

    private fun headersFor(url: String, referer: String?): Map<String, String> {
        val h = TmfNet.browserHeaders(referer).toMutableMap()
        cookiesFor(hostOf(url))?.let { saved ->
            if (saved.cookies.isNotBlank()) h["Cookie"] = saved.cookies
            if (saved.ua.isNotBlank()) h["User-Agent"] = saved.ua
        }
        return h
    }

    suspend fun get(
        url: String,
        referer: String? = null,
        timeout: Long = 20L
    ): NiceResponse? {
        val first = try {
            app.get(url, headers = headersFor(url, referer), timeout = timeout)
        } catch (_: Exception) {
            null
        } ?: return null
        if (!isChallenge(first)) return first

        solverMutex.withLock {
            val second = try {
                app.get(url, headers = headersFor(url, referer), timeout = timeout)
            } catch (_: Exception) {
                null
            }
            if (second != null && !isChallenge(second)) return second

            val solved = solveInWebView(url)
            if (!solved) return null

            repeat(2) {
                val retry = try {
                    app.get(url, headers = headersFor(url, referer), timeout = timeout)
                } catch (_: Exception) {
                    null
                }
                if (retry != null && !isChallenge(retry)) return retry
            }
        }
        return null
    }

    private suspend fun solveInWebView(url: String): Boolean =
        withContext(Dispatchers.Main) {
            val activity = CommonActivity.activity as? AppCompatActivity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                return@withContext false
            }
            suspendCancellableCoroutine { cont ->
                val solver = SolverDialog(url) { ok ->
                    if (cont.isActive) cont.resume(ok)
                }
                try {
                    solver.show(activity)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
                cont.invokeOnCancellation { solver.dismiss() }
            }
        }

    private class CursorPos {
        var x = 0f
        var y = 0f
    }

    private class SolverDialog(
        private val targetUrl: String,
        private val onDone: (Boolean) -> Unit
    ) {
        private var dialog: AlertDialog? = null
        private var webView: WebView? = null
        private var status: TextView? = null
        private val handler = Handler(Looper.getMainLooper())
        private val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        private var waited = 0L
        private val targetHost = hostOf(targetUrl)

        private fun checkCookies() {
            if (finished.get()) return
            try {
                CookieManager.getInstance().flush()
                val jar = CookieManager.getInstance().getCookie(targetHost) ?: ""
                if (jar.contains("cf_clearance")) {
                    finish(jar)
                }
            } catch (_: Exception) {
            }
        }

        private fun finish(cookies: String) {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            save(targetHost, cookies, webView?.settings?.userAgentString ?: "")
            try { webView?.destroy() } catch (_: Exception) {}
            try { dialog?.dismiss() } catch (_: Exception) {}
            onDone(true)
        }

        private fun fail() {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            try { webView?.destroy() } catch (_: Exception) {}
            try { dialog?.dismiss() } catch (_: Exception) {}
            onDone(false)
        }

        private val poller = object : Runnable {
            override fun run() {
                if (finished.get()) return
                waited += POLL_MS
                checkCookies()
                if (finished.get()) return
                if (waited >= SOLVER_TIMEOUT_MS) {
                    status?.text = "Timed out, skip is kept"
                    fail()
                } else {
                    status?.text = "Verifying access ${waited / 1000}s"
                    handler.postDelayed(this, POLL_MS)
                }
            }
        }

        fun show(activity: AppCompatActivity) {
            val dp = activity.resources.displayMetrics.density
            val w = activity.resources.displayMetrics.widthPixels
            val h = activity.resources.displayMetrics.heightPixels

            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            }

            column.addView(TextView(activity).apply {
                text = "One time access check"
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            val statusView = TextView(activity).apply {
                text = "Loading page"
                textSize = 12f
                setTextColor(Color.parseColor("#A0A0B0"))
            }
            status = statusView
            column.addView(statusView)
            column.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
            })

            val webSlot = FrameLayout(activity).apply {
                isFocusable = true
                isFocusableInTouchMode = true
            }
            webView = buildWeb(activity)
            webSlot.addView(webView, FrameLayout.LayoutParams(-1, -1))

            val onTv = try {
                com.lagradost.cloudstream3.ui.settings.Globals.isLayout(
                    com.lagradost.cloudstream3.ui.settings.Globals.TV
                )
            } catch (_: Throwable) {
                false
            }
            if (onTv) {
                val dot = 22 * dp
                val cursor = View(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(dot.toInt(), dot.toInt())
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(170, 220, 40, 40))
                        setStroke((2 * dp).toInt(), Color.WHITE)
                    }
                }
                webSlot.addView(cursor)
                val pos = CursorPos()
                webSlot.viewTreeObserver.addOnGlobalLayoutListener(object :
                    ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        webSlot.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        pos.x = webSlot.width / 2f
                        pos.y = webSlot.height / 2f
                        cursor.translationX = pos.x - dot / 2
                        cursor.translationY = pos.y - dot / 2
                    }
                })
                val step = 10f * dp
                webSlot.setOnKeyListener { _, code, ev ->
                    if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> { nudge(pos, cursor, dot, webSlot, 0f, -step); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { nudge(pos, cursor, dot, webSlot, 0f, step); true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { nudge(pos, cursor, dot, webSlot, -step, 0f); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { nudge(pos, cursor, dot, webSlot, step, 0f); true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { tap(pos); true }
                        else -> false
                    }
                }
                webSlot.requestFocus()
            }
            column.addView(webSlot, LinearLayout.LayoutParams(-1, (h * 0.6f).toInt()))

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.addView(Button(activity).apply {
                text = "Done"
                setOnClickListener {
                    CookieManager.getInstance().flush()
                    checkCookies()
                    if (!finished.get()) status?.text = "No access yet"
                }
            })
            row.addView(Button(activity).apply {
                text = "Skip"
                setOnClickListener { fail() }
            })
            column.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (8 * dp).toInt()
            })

            dialog = AlertDialog.Builder(activity)
                .setView(column)
                .setCancelable(false)
                .create()
                .also { dlg ->
                    dlg.setOnDismissListener {
                        handler.removeCallbacksAndMessages(null)
                        if (!finished.get()) {
                            finished.set(true)
                            try { webView?.destroy() } catch (_: Exception) {}
                            onDone(false)
                        }
                    }
                    dlg.show()
                    dlg.window?.apply {
                        setLayout((w * 0.95f).toInt(), (h * 0.9f).toInt())
                        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    }
                }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
                flush()
            }
            webView?.loadUrl(targetUrl)
            handler.postDelayed(poller, POLL_MS)
            handler.postDelayed({ fail() }, SOLVER_TIMEOUT_MS)
        }

        private fun nudge(pos: CursorPos, cursor: View, size: Float, box: View, dx: Float, dy: Float) {
            pos.x = (pos.x + dx).coerceIn(0f, box.width.toFloat())
            pos.y = (pos.y + dy).coerceIn(0f, box.height.toFloat())
            cursor.translationX = pos.x - size / 2
            cursor.translationY = pos.y - size / 2
        }

        private fun tap(pos: CursorPos) {
            val wv = webView ?: return
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, pos.x, pos.y, 0)
            val up = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, pos.x, pos.y, 0)
            try {
                wv.dispatchTouchEvent(down)
                wv.dispatchTouchEvent(up)
            } catch (_: Exception) {
            } finally {
                down.recycle()
                up.recycle()
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun buildWeb(context: Context): WebView = WebView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true
                loadsImagesAutomatically = true
                userAgentString = TmfNet.DESKTOP_UA
                mediaPlaybackRequiresUserGesture = false
            }
            webChromeClient = object : WebChromeClient() {}
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (finished.get()) return
                    val title = (view?.title ?: "").lowercase()
                    if (CHALLENGE_TITLES.any { title.contains(it) }) {
                        status?.text = "Complete the check above"
                        checkCookies()
                        return
                    }
                    checkCookies()
                }
            }
        }

        fun dismiss() {
            handler.removeCallbacksAndMessages(null)
            try { webView?.apply { stopLoading(); destroy() } } catch (_: Exception) {}
            webView = null
            try { dialog?.dismiss() } catch (_: Exception) {}
            dialog = null
        }
    }
}
