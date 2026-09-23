package com.mkissa

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
import android.webkit.WebResourceResponse
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
import com.lagradost.cloudstream3.ui.settings.Globals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal object MkissaWeb {

    private const val TAG = "MKISSA"
    private const val TURNSTILE_SITEKEY = "0x4AAAAAADXpHZ1lTeqKwhch"
    private const val DIALOG_TIMEOUT_MS = 120_000L
    private const val POLL_MS = 500L
    private const val CURSOR_STEP_DP = 10f

    private class CursorPosHolder { var x: Float = 0f; var y: Float = 0f }

    private fun isTv(): Boolean = try {
        Globals.isLayout(Globals.TV)
    } catch (e: Throwable) { false }

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

    private fun attachCursor(activity: AppCompatActivity, webContainer: FrameLayout, webView: WebView?) {
        val dp = activity.resources.displayMetrics.density
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
        pos.x = webContainer.width / 2f; pos.y = webContainer.height / 2f
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context, client: WebViewClient, chrome: WebChromeClient): WebView {
        return WebView(context).apply {
            isFocusable = true; isFocusableInTouchMode = true; requestFocus()
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true; allowFileAccess = true; loadsImagesAutomatically = true
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                mediaPlaybackRequiresUserGesture = false
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@apply, true)
            }
            webViewClient = client
            webChromeClient = chrome
        }
    }

    private fun buildChrome(onProgress: (Int) -> Unit): WebChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress)
        }
        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
        ): Boolean = false
    }

    private suspend fun <T> withDialog(
        title: String,
        hint: String,
        initialLoad: (WebView) -> Unit,
        buildClient: (MkissaDialogHooks) -> WebViewClient,
        poll: (MkissaDialogHooks, WebView?) -> T?,
    ): T? = withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity as? AppCompatActivity
            ?: return@withContext null
        if (activity.isFinishing || activity.isDestroyed) return@withContext null

        suspendCancellableCoroutine { cont ->
            val dp = activity.resources.displayMetrics.density
            val screenH = activity.resources.displayMetrics.heightPixels
            val dialogW = (activity.resources.displayMetrics.widthPixels * 0.95f).toInt()
            val dialogH = (screenH * 0.9f).toInt()
            val webViewHeight = (screenH * 0.6f).toInt()

            val hooks = MkissaDialogHooks()
            var dialog: AlertDialog? = null
            var webView: WebView? = null
            val handler = Handler(Looper.getMainLooper())

            fun finish(result: T?) {
                if (hooks.resolved.getAndSet(true)) return
                handler.removeCallbacksAndMessages(null)
                try { webView?.stopLoading() } catch (e: Exception) {}
                try { webView?.destroy() } catch (e: Exception) {}
                try { dialog?.dismiss() } catch (e: Exception) {}
                if (cont.isActive) cont.resume(result)
            }

            hooks.finish = { r: Any? -> @Suppress("UNCHECKED_CAST") finish(r as? T) }

            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            }
            container.addView(TextView(activity).apply {
                text = title
                textSize = 16f; setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, (8 * dp).toInt())
            })
            val statusView = TextView(activity).apply {
                text = "Loading..."
                textSize = 12f; setTextColor(Color.parseColor("#A0A0B0"))
                setPadding(0, 0, 0, (4 * dp).toInt())
            }
            container.addView(statusView)
            container.addView(TextView(activity).apply {
                text = hint
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
            webView = buildWebView(activity, buildClient(hooks), buildChrome { p ->
                if (!hooks.resolved.get()) statusView.text = "Loading... $p%"
            })
            webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))
            if (isTv()) attachCursor(activity, webContainer, webView)
            container.addView(webContainer)

            val btnContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = (8 * dp).toInt() }
            }
            btnContainer.addView(Button(activity).apply {
                text = "Cancel"
                setOnClickListener { finish(null) }
            })
            container.addView(btnContainer)

            dialog = AlertDialog.Builder(activity).setView(container).setCancelable(false).create()
            webView?.setTag(dialog)
            dialog?.setOnDismissListener {
                handler.removeCallbacksAndMessages(null)
                if (!hooks.resolved.getAndSet(true)) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            dialog?.show()
            dialog?.window?.apply {
                setLayout(dialogW, dialogH)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }

            try { initialLoad(webView!!) } catch (e: Exception) {
                Log.e(TAG, "initial load failed: ${e.message}")
                finish(null)
                return@suspendCancellableCoroutine
            }
            hooks.status = { text ->
                handler.post { if (!hooks.resolved.get()) statusView.text = text }
            }

            val pollRunnable = object : Runnable {
                override fun run() {
                    if (hooks.resolved.get() || dialog == null || dialog?.isShowing != true) return
                    val result = poll(hooks, webView)
                    if (result != null && !hooks.resolved.get()) {
                        finish(result)
                        return
                    }
                    handler.postDelayed(this, POLL_MS)
                }
            }
            handler.postDelayed(pollRunnable, POLL_MS)
            handler.postDelayed({ finish(null) }, DIALOG_TIMEOUT_MS)

            cont.invokeOnCancellation {
                hooks.resolved.set(true)
                handler.removeCallbacksAndMessages(null)
                try { webView?.destroy() } catch (e: Exception) {}
                try { dialog?.dismiss() } catch (e: Exception) {}
            }
        }
    }

    internal class MkissaDialogHooks {
        val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
        var finish: (Any?) -> Unit = {}
        var status: (String) -> Unit = {}
    }

    internal suspend fun solveTurnstile(): String? {
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
            <style>body{background:#101018;color:#eee;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;margin:0}
            h3{margin-bottom:24px;font-weight:500}</style></head>
            <body><h3>Verify to continue</h3>
            <div class="cf-turnstile" data-sitekey="$TURNSTILE_SITEKEY" data-callback="onTok"></div>
            <script>function onTok(t){window.__mkToken=t;var d=document.createElement('div');d.id='done';d.textContent='OK';document.body.appendChild(d);}</script>
            </body></html>
        """.trimIndent()

        var loaded = false
        val latestToken = java.util.concurrent.atomic.AtomicReference<String?>(null)
        return withDialog(
            title = "Security Check",
            hint = "Tap the checkbox below to verify.",
            initialLoad = { wv -> wv.loadData(html, "text/html", "UTF-8") },
            buildClient = { hooks ->
                object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (hooks.resolved.get()) return
                        loaded = true
                        hooks.status("Waiting for verification...")
                    }
                }
            },
            poll = { hooks, webView ->
                if (!loaded || hooks.resolved.get()) return@withDialog null
                if (latestToken.get() == null) {
                    try {
                        webView?.evaluateJavascript("(window.__mkToken||'')||null") { v ->
                            if (v != null && v != "null" && v != "\"\"") {
                                latestToken.set(v.trim().trim('"'))
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
                val token = latestToken.get()
                if (!token.isNullOrBlank()) {
                    hooks.status("Verified")
                    token
                } else null
            }
        )?.also { Log.d(TAG, "turnstile solved") }
    }

    private val MEDIA_URL = Regex(
        """(?i)https?://[^\"'\s]+\.m3u8[^\"'\s]*|https?://[^\"'\s]+\.mp4[^\"'\s]*"""
    )

    internal suspend fun interceptMediaUrl(embedUrl: String): String? {
        return withDialog(
            title = "Opening Player",
            hint = "The stream link is being extracted. Tap play if nothing happens.",
            initialLoad = { wv -> wv.loadUrl(embedUrl) },
            buildClient = { hooks ->
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        return !url.startsWith("http")
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        val m = MEDIA_URL.find(url)
                        if (m != null && !hooks.resolved.get()) {
                            hooks.finish(m.groupValues[0])
                        }
                        return null
                    }
                }
            },
            poll = { _, _ -> null }
        )
    }

    internal suspend fun solveCloudflare(url: String): Boolean {
        val targetHost = try {
            val uri = Uri.parse(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) { url }

        var loaded = false
        val result = withDialog(
            title = "Cloudflare Bypass",
            hint = "Solve the CAPTCHA below if it appears.",
            initialLoad = { wv -> wv.loadUrl(url) },
            buildClient = { hooks ->
                object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (hooks.resolved.get()) return
                        loaded = true
                        hooks.status("Checking cookies...")
                    }
                }
            },
            poll = { hooks, _ ->
                if (!loaded || hooks.resolved.get()) return@withDialog null
                try {
                    CookieManager.getInstance().flush()
                    val cookies = CookieManager.getInstance().getCookie(targetHost) ?: ""
                    if (cookies.contains("cf_clearance")) true else null
                } catch (e: Exception) { null }
            }
        )
        return result == true
    }
}
