package com.laddu100.rareanimes

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
import kotlin.coroutines.resume

private const val TAG = "RareAnimes_Resolver"

internal class RAIResolvedLink(
    val url: String,
    val kind: String
)

private val ALLOWED_HOSTS = listOf(
    "rareanimes.mov",
    "animetoonhindi.com",
    "codedew.com",
    "argon.razorshell.space",
    "groovy.monster",
    "hubcloud.ist",
    "hubcloud.cx",
    "gamerxyt.com",
    "pixeldrain.net",
    "pixeldrain.dev",
    "cloudflarestorage.com",
    "googleusercontent.com",
    "hbplay.pages.dev",
    "jwpcdn.com",
    "cloudflare.com",
    "cloudflareinsights.com",
    "cdnjs.cloudflare.com",
    "googleapis.com",
    "gstatic.com",
    "google.com",
    "fontawesome.com",
    "pages.dev"
)

private fun isAllowedHost(url: String): Boolean {
    return try {
        val host = Uri.parse(url).host ?: return false
        ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    } catch (e: Exception) {
        false
    }
}

internal fun classifyVideoUrl(url: String): String? {
    val u = url.substringBefore("#")
    return when {
        u.contains("groovy.monster") && u.contains(".m3u8") -> "hls"
        u.contains("cloudflarestorage.com/") && u.contains("X-Amz-") -> "r2"
        u.contains("pixeldrain.net/api/file/") || u.contains("pixeldrain.dev/api/file/") -> "pixeldrain"
        u.contains("pixeldrain.net/u/") || u.contains("pixeldrain.dev/u/") -> "pixeldrain_page"
        u.contains("googleusercontent.com/") && !u.contains("lh3.") -> "gvideo"
        else -> null
    }
}

private class CursorPos { var x: Float = 0f; var y: Float = 0f }

@SuppressLint("InflateParams")
private class RAIResolverDialog(
    private val startUrl: String,
    private val onFinished: ((RAIResolvedLink?) -> Unit)? = null
) {
    private var dialog: AlertDialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var urlText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    private var captured: RAIResolvedLink? = null

    private fun tryCapture(url: String): Boolean {
        val kind = classifyVideoUrl(url) ?: return false
        if (captured == null) {
            captured = RAIResolvedLink(url.substringBefore("#"), kind)
            statusText?.text = "Link captured - finishing..."
            handler.postDelayed({ finishWithCapture() }, 600)
            return true
        }
        return false
    }

    private fun finishWithCapture() {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        try { webView?.stopLoading() } catch (e: Exception) {}
        try { webView?.destroy() } catch (e: Exception) {}
        try { dialog?.dismiss() } catch (e: Exception) {}
        try { onFinished?.invoke(captured) } catch (e: Exception) {}
    }

    private fun finishManual() {
        if (captured != null) {
            finishWithCapture()
        } else {
            if (!resolved.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            try { webView?.stopLoading() } catch (e: Exception) {}
            try { webView?.destroy() } catch (e: Exception) {}
            try { dialog?.dismiss() } catch (e: Exception) {}
            try { onFinished?.invoke(null) } catch (e: Exception) {}
        }
    }

    private fun finishCancel() {
        captured = null
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        try { webView?.stopLoading() } catch (e: Exception) {}
        try { webView?.destroy() } catch (e: Exception) {}
        try { dialog?.dismiss() } catch (e: Exception) {}
        try { onFinished?.invoke(null) } catch (e: Exception) {}
    }

    private fun smartBack() {
        val wv = webView ?: return
        try {
            val list = wv.copyBackForwardList()
            var target = -1
            val currentUrl = wv.url ?: ""
            for (i in list.currentIndex - 1 downTo 0) {
                val itemUrl = list.getItemAtIndex(i)?.url ?: continue
                if (itemUrl != currentUrl) {
                    target = i - list.currentIndex
                    break
                }
            }
            if (target == -1) {
                statusText?.text = "Already at the first page"
            } else {
                statusText?.text = "Going back..."
                wv.goBackOrForward(target)
            }
        } catch (e: Exception) {
            if (wv.canGoBack()) wv.goBack()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: AppCompatActivity) {
        val dp = activity.resources.displayMetrics.density
        val screenH = activity.resources.displayMetrics.heightPixels
        val dialogW = (activity.resources.displayMetrics.widthPixels * 0.95f).toInt()
        val dialogH = (screenH * 0.9f).toInt()
        val webViewHeight = (screenH * 0.6f).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
        }

        container.addView(TextView(activity).apply {
            text = "Resolving Link"
            textSize = 16f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (6 * dp).toInt())
        })

        val statusView = TextView(activity).apply {
            text = "Loading the link page..."
            textSize = 12f; setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (2 * dp).toInt())
        }
        statusText = statusView
        container.addView(statusView)

        val urlView = TextView(activity).apply {
            text = startUrl.substringBefore("?").takeLast(60)
            textSize = 10f; setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, (6 * dp).toInt())
            maxLines = 1
        }
        urlText = urlView
        container.addView(urlView)

        val isTv = try { Globals.isLayout(Globals.TV) } catch (e: Throwable) { false }

        container.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = (6 * dp).toInt() }
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

            val pos = CursorPos()
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

            val step = 10f * dp
            webContainer.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { moveCursor(pos, cursor, cursorSize, webContainer, 0f, -step); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursor(pos, cursor, cursorSize, webContainer, 0f, step); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(pos, cursor, cursorSize, webContainer, -step, 0f); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(pos, cursor, cursorSize, webContainer, step, 0f); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { clickAtCursor(pos, webView); true }
                    KeyEvent.KEYCODE_BACK -> { smartBack(); true }
                    else -> false
                }
            }
            webContainer.requestFocus()
        }
        container.addView(webContainer)

        val navRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = (8 * dp).toInt() }
        }

        val backBtn = Button(activity).apply {
            text = "Back"
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(0xFF2E7D32.toInt())
            }
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        backBtn.setOnClickListener { smartBack() }
        navRow.addView(backBtn)

        val reloadBtn = Button(activity).apply {
            text = "Reload"
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(0xFF6D5ACF.toInt())
            }
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = (6 * dp).toInt() }
        }
        reloadBtn.setOnClickListener {
            statusText?.text = "Reloading..."
            try { webView?.reload() } catch (e: Exception) {}
        }
        navRow.addView(reloadBtn)

        val doneBtn = Button(activity).apply {
            text = "Done"
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(0xFF0B57D0.toInt())
            }
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = (6 * dp).toInt() }
        }
        doneBtn.setOnClickListener { finishManual() }
        navRow.addView(doneBtn)

        val cancelBtn = Button(activity).apply {
            text = "Cancel"
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(0xFFE5484D.toInt())
            }
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = (6 * dp).toInt() }
        }
        cancelBtn.setOnClickListener { finishCancel() }
        navRow.addView(cancelBtn)

        container.addView(navRow)

        container.addView(TextView(activity).apply {
            text = "Popup ads are blocked automatically. If one still opens, press Back to return to the link page."
            textSize = 11f; setTextColor(Color.parseColor("#707080"))
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })

        dialog = AlertDialog.Builder(activity).setView(container).setCancelable(false).create()
        webView?.setTag(dialog)
        dialog?.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!resolved.get()) {
                resolved.set(true)
                try { webView?.destroy() } catch (e: Exception) {}
                try { onFinished?.invoke(captured) } catch (e: Exception) {}
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
        handler.postDelayed({ finishManual() }, 120_000L)
    }

    private fun moveCursor(pos: CursorPos, cursorView: View, cursorSize: Int, container: View, dx: Float, dy: Float) {
        pos.x = (pos.x + dx).coerceIn(0f, container.width.toFloat())
        pos.y = (pos.y + dy).coerceIn(0f, container.height.toFloat())
        cursorView.translationX = pos.x - cursorSize / 2f
        cursorView.translationY = pos.y - cursorSize / 2f
    }

    private fun clickAtCursor(pos: CursorPos, webView: WebView?) {
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
                allowContentAccess = true; allowFileAccess = true
                userAgentString = RAI_UA
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                blockNetworkImage = true
                loadsImagesAutomatically = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!resolved.get()) statusText?.text = "Loading... $newProgress%"
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (tryCapture(url)) return true
                    if (!isAllowedHost(url)) {
                        statusText?.text = "Blocked a popup ad"
                        handler.post { smartBack() }
                        return true
                    }
                    urlText?.text = url.substringBefore("?").takeLast(60)
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (tryCapture(url)) {
                        handler.post { finishWithCapture() }
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (resolved.get()) return
                    statusText?.text = "Page loaded - waiting for the video link..."
                    try {
                        view?.evaluateJavascript(
                            "try{window.onpopstate=function(){};history.replaceState&&null;}catch(e){}"
                        ) { _ -> }
                    } catch (e: Exception) {
                        Log.e(TAG, "popstate reset: ${e.message}")
                    }
                    url?.let { tryCapture(it) }
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

internal suspend fun showRAIResolverPopupAndWait(url: String): RAIResolvedLink? =
    withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity as? AppCompatActivity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return@withContext null
        }
        suspendCancellableCoroutine { cont ->
            val resolverDialog = RAIResolverDialog(url) { link ->
                if (cont.isActive) cont.resume(link)
            }
            try { resolverDialog.show(activity) } catch (e: Exception) {
                Log.e(TAG, "show resolver: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { resolverDialog.dismiss() }
        }
    }
