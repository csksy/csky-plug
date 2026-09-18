package com.retrovault

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import com.lagradost.cloudstream3.CommonActivity
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

object RetroVaultLauncher {
    private const val TAG = "RetroVault"

    fun unpack(context: Context): File {
        val dir = File(context.cacheDir, "retrovault")
        val versionFile = File(dir, "version.txt")
        val ready = versionFile.exists() && versionFile.readText().trim() == RetroVaultPayload.PAYLOAD_VERSION
        if (ready) return dir

        dir.deleteRecursively()
        dir.mkdirs()
        val bytes = android.util.Base64.decode(RetroVaultPayload.ZIP_BASE64, android.util.Base64.DEFAULT)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val target = File(dir, entry.name)
                if (!target.canonicalPath.startsWith(dir.canonicalPath)) continue
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> zip.copyTo(out) }
                zip.closeEntry()
            }
        }
        versionFile.writeText(RetroVaultPayload.PAYLOAD_VERSION)
        return dir
    }

    fun urlFor(dir: File, path: String): String =
        "file://" + File(dir, path).absolutePath

    @SuppressLint("SetJavaScriptEnabled")
    fun launch(
        context: Context,
        targetUrl: String,
        landscape: Boolean,
        onDismiss: () -> Unit,
        romProvider: (() -> String?)? = null,
    ) {
        val activity: Activity = (context as? Activity)
            ?: CommonActivity.activity
            ?: run {
                onDismiss()
                return
            }
        activity.runOnUiThread {
            try {
                showGame(activity, targetUrl, landscape, onDismiss, romProvider)
            } catch (t: Throwable) {
                t.printStackTrace()
                onDismiss()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showGame(
        activity: Activity,
        targetUrl: String,
        landscape: Boolean,
        onDismiss: () -> Unit,
        romProvider: (() -> String?)?,
    ) {
        val originalOrientation = activity.requestedOrientation
        activity.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.apply {
            setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val root = FrameLayout(activity)
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.setBackgroundColor(Color.BLACK)

        val webView = WebView(activity)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        webView.setBackgroundColor(Color.BLACK)
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun closeGame() {
                    activity.runOnUiThread { dialog.dismiss() }
                }

                // runs on the webview bridge thread, rom bytes are base64 so
                // the page never needs the file scheme or remote CORS
                @JavascriptInterface
                fun getRom(): String? = runCatching { romProvider?.invoke() }.getOrNull()
            },
            "AndroidApp",
        )

        webView.loadUrl(targetUrl)
        root.addView(webView)
        dialog.setContentView(root)

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dialog.dismiss()
                true
            } else {
                false
            }
        }

        dialog.setOnDismissListener {
            webView.evaluateJavascript("window.EJS_emulator && EJS_emulator.callEvent('exit');", null)
            webView.destroy()
            activity.requestedOrientation = originalOrientation
            onDismiss()
        }

        dialog.show()

        if (Build.VERSION.SDK_INT >= 30) {
            dialog.window?.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            dialog.window?.decorView?.systemUiVisibility = 5894
        }
    }
}
