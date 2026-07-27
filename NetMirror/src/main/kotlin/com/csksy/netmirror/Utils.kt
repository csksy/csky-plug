package com.csksy.netmirror

import android.annotation.SuppressLint
import android.app.AlertDialog
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
import android.view.WindowManager
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
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.reflect.KClass

private const val TAG = "NetMirror"

val jsonParser: ResponseParser = object : ResponseParser {
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)

    override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
        mapper.readValue(text, kClass.java)

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? = try {
        mapper.readValue(text, kClass.java)
    } catch (e: Exception) {
        Log.d(TAG, "parseSafe failed: ${e.message}")
        null
    }

    override fun writeValueAsString(obj: Any): String = mapper.writeValueAsString(obj)
}

val http: Requests = Requests(responseParser = jsonParser).apply {
    defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )
}

inline fun <reified T : Any> parseJson(text: String): T =
    jsonParser.parse(text, T::class)

inline fun <reified T : Any> tryParseJson(text: String): T? = try {
    jsonParser.parseSafe(text, T::class)
} catch (e: Exception) {
    null
}

const val NETMIRROR_TV_URL = "https://netmirror.gg/tv"
private const val BYPASS_COOKIE_TTL_MS = 54_000_000L
private const val API_BASE_TTL_MS = 86_400_000L
private const val CF_COOKIE_TTL_MS = 82_800_000L

val newTvBaseHeaders: Map<String, String> = mapOf(
    "Cache-Control" to "no-cache, no-store, must-revalidate",
    "Pragma" to "no-cache",
    "Expires" to "0",
    "X-Requested-With" to "NetmirrorNewTV v1.0",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
    "Accept" to "application/json, text/plain, */*"
)

val newTvDomains: List<String> = listOf(
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==",
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3QuYXBw",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNj",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNsaWNr",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmluaw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnBybw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNob3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNpdGU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNwYWNl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnN0b3Jl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnZpcA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lndpa2k=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lnh5eg==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5hcnQ=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5jYw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbmZv",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbms=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5saXZl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5wcm8=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5zdG9yZQ==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy50b3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy54eXo="
)

fun decodeBase64(value: String): String =
    String(Base64.getDecoder().decode(value), Charsets.UTF_8)

fun buildNewTvHeaders(ott: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
    val result = newTvBaseHeaders.toMutableMap()
    result["Ott"] = ott
    result.putAll(extra)
    return result
}

fun convertRuntimeToMinutes(runtime: String): Int {
    var total = 0
    for (part in runtime.split(" ")) {
        when {
            part.endsWith("h") -> total += (part.removeSuffix("h").trim().toIntOrNull() ?: 0) * 60
            part.endsWith("m") -> total += part.removeSuffix("m").trim().toIntOrNull() ?: 0
        }
    }
    return total
}

private fun hostOf(url: String): String = try {
    val uri = Uri.parse(url)
    "${uri.scheme}://${uri.host}"
} catch (e: Exception) {
    url
}

fun isCloudflareBlocked(response: NiceResponse): Boolean {
    val code = response.code
    if (code == 503) return true
    if (code == 403) {
        val body = try { response.text.lowercase() } catch (e: Exception) { "" }
        if (body.contains("just a moment") || body.contains("challenge-platform")) return true
        if (body.contains("checking your browser") || body.contains("cf-browser-verification")) return true
        if (body.contains("checking if the site connection is secure")) return true
        val cfMitigated = response.headers["cf-mitigated"]
        if (!cfMitigated.isNullOrBlank()) return true
        return false
    }
    return false
}

private val cfMutex = Mutex()
private const val CF_TIMEOUT_MS = 60_000L
private const val CF_POLL_MS = 1_000L
private const val CURSOR_STEP_DP = 10f

private class CursorPos { var x: Float = 0f; var y: Float = 0f }

var appContext: Context? = null

private suspend fun solveCfInWebView(targetUrl: String): String? {
    val ctx = CommonActivity.activity ?: appContext ?: return null
    val targetHost = hostOf(targetUrl)

    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val wv = WebView(ctx).apply {
                cookieManager.setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
            }

            val resolved = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val cfRegex = Regex("cf_clearance=([^;]+)")

            fun finishWith(cookie: String?) {
                if (!resolved.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                try { wv.destroy() } catch (_: Exception) {}
                try { (wv.tag as? AlertDialog)?.dismiss() } catch (_: Exception) {}
                if (cont.isActive) cont.resume(cookie)
            }

            fun tryExtract() {
                if (resolved.get()) return
                val cookies = cookieManager.getCookie(targetHost).orEmpty()
                val cf = cfRegex.find(cookies)?.groupValues?.getOrNull(1)
                if (!cf.isNullOrEmpty()) {
                    NetMirrorStorage.saveCfCookie(targetHost, cf)
                    finishWith(cf)
                }
            }

            val poller = object : Runnable {
                override fun run() {
                    tryExtract()
                    if (!resolved.get()) handler.postDelayed(this, CF_POLL_MS)
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
                override fun onPageFinished(view: WebView, url: String) {
                    tryExtract()
                    if (!resolved.get()) handler.postDelayed(poller, CF_POLL_MS)
                }
            }

            val density = ctx.resources.displayMetrics.density
            val wm = ctx.getSystemService("window") as WindowManager
            val display = wm.defaultDisplay
            val screenW = ctx.resources.displayMetrics.widthPixels
            val screenH = ctx.resources.displayMetrics.heightPixels
            val dialogW = (screenW * 0.95f).toInt()
            val dialogH = (screenH * 0.9f).toInt()

            val container = FrameLayout(ctx).apply {
                isFocusable = true
                isFocusableInTouchMode = true
            }
            wv.layoutParams = FrameLayout.LayoutParams(-1, -1)
            container.addView(wv)

            val isTv = try { Globals.isLayout(Globals.TV) } catch (_: Throwable) { false }
            if (isTv) {
                val cursorSize = (22 * density).toInt()
                val pos = CursorPos()
                pos.x = dialogW / 2f; pos.y = dialogH / 2f
                val cursor = View(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(160, 255, 50, 50))
                        setStroke((2 * density).toInt(), Color.WHITE)
                    }
                    elevation = 999f
                    translationX = pos.x - cursorSize / 2f
                    translationY = pos.y - cursorSize / 2f
                }
                container.addView(cursor)

                container.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        pos.x = container.width / 2f
                        pos.y = container.height / 2f
                        cursor.translationX = pos.x - cursorSize / 2f
                        cursor.translationY = pos.y - cursorSize / 2f
                    }
                })

                val step = CURSOR_STEP_DP * density
                container.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> { pos.y = (pos.y - step).coerceIn(0f, container.height.toFloat()); cursor.translationY = pos.y - cursorSize / 2f; true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { pos.y = (pos.y + step).coerceIn(0f, container.height.toFloat()); cursor.translationY = pos.y - cursorSize / 2f; true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { pos.x = (pos.x - step).coerceIn(0f, container.width.toFloat()); cursor.translationX = pos.x - cursorSize / 2f; true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { pos.x = (pos.x + step).coerceIn(0f, container.width.toFloat()); cursor.translationX = pos.x - cursorSize / 2f; true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            val t = SystemClock.uptimeMillis()
                            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, pos.x, pos.y, 0)
                            val up = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, pos.x, pos.y, 0)
                            try { wv.dispatchTouchEvent(down); wv.dispatchTouchEvent(up) } catch (_: Exception) {}
                            finally { down.recycle(); up.recycle() }
                            true
                        }
                        else -> false
                    }
                }
            }

            val wrapper = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            }
            wrapper.addView(TextView(ctx).apply {
                text = "Solving Cloudflare... Please wait."
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 0, 0, (8 * density).toInt())
            })
            wrapper.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * density).toInt() }
            })
            wrapper.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))

            val dialog = AlertDialog.Builder(ctx).setView(wrapper).setCancelable(false).create()
            dialog.window?.let { win ->
                win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                win.setLayout(dialogW, dialogH)
            }
            wv.tag = dialog

            dialog.setOnDismissListener { finishWith(null) }
            handler.postDelayed({ finishWith(null) }, CF_TIMEOUT_MS)

            cont.invokeOnCancellation {
                handler.removeCallbacksAndMessages(null)
                try { wv.destroy() } catch (_: Exception) {}
                try { dialog.dismiss() } catch (_: Exception) {}
            }

            dialog.show()
            wv.loadUrl(targetUrl)
        }
    }
}

private fun buildCfHeaders(url: String, extra: Map<String, String> = emptyMap(), extraCookies: Map<String, String> = emptyMap()): Map<String, String> {
    val host = hostOf(url)
    val h = extra.toMutableMap()
    if (!h.containsKey("User-Agent")) {
        h["User-Agent"] = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
    val (cfCookie, ts) = NetMirrorStorage.getCfCookie(host)
    val cookieParts = mutableListOf<String>()
    if (!cfCookie.isNullOrEmpty() && System.currentTimeMillis() - ts < CF_COOKIE_TTL_MS) {
        cookieParts.add("cf_clearance=$cfCookie")
    }
    for ((k, v) in extraCookies) {
        cookieParts.add("$k=$v")
    }
    if (cookieParts.isNotEmpty()) {
        h["Cookie"] = cookieParts.joinToString("; ")
    }
    return h
}

private suspend fun ensureCfBypass(url: String, response: NiceResponse): String? {
    if (!isCloudflareBlocked(response)) return null
    val host = hostOf(url)
    return cfMutex.withLock {
        val (existing, ts) = NetMirrorStorage.getCfCookie(host)
        if (!existing.isNullOrEmpty() && System.currentTimeMillis() - ts < CF_COOKIE_TTL_MS) {
            return@withLock existing
        }
        val solved = solveCfInWebView(host)
        if (solved != null) {
            NetMirrorStorage.saveCfCookie(host, solved)
        }
        solved
    }
}

suspend fun cfGet(
    url: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
    cookies: Map<String, String>? = null,
    allowRedirects: Boolean = true
): NiceResponse {
    val h = buildCfHeaders(url, headers, cookies ?: emptyMap())
    var response = http.get(url, headers = h, referer = referer, allowRedirects = allowRedirects, timeout = 30_000L)
    if (!isCloudflareBlocked(response)) return response

    ensureCfBypass(url, response) ?: return response
    val retryHeaders = buildCfHeaders(url, headers, cookies ?: emptyMap())
    return http.get(url, headers = retryHeaders, referer = referer, allowRedirects = allowRedirects, timeout = 30_000L)
}

suspend fun cfPost(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
    cookies: Map<String, String>? = null,
    allowRedirects: Boolean = false
): NiceResponse {
    val h = buildCfHeaders(url, headers, cookies ?: emptyMap())
    var response = http.post(url, data = mapOf("" to body), headers = h, referer = referer, allowRedirects = allowRedirects, timeout = 30_000L)
    if (!isCloudflareBlocked(response)) return response

    ensureCfBypass(url, response) ?: return response
    val retryHeaders = buildCfHeaders(url, headers, cookies ?: emptyMap())
    return http.post(url, data = mapOf("" to body), headers = retryHeaders, referer = referer, allowRedirects = allowRedirects, timeout = 30_000L)
}

@Suppress("UNUSED_PARAMETER")
suspend fun bypass(mainUrl: String): String {
    val (savedCookie, savedTimestamp) = NetMirrorStorage.getCookie()
    if (!savedCookie.isNullOrEmpty() && System.currentTimeMillis() - savedTimestamp < BYPASS_COOKIE_TTL_MS) {
        return savedCookie
    }

    val verifyUrl = "https://net52.cc/verify.php"
    val bypassHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "Origin" to "https://net22.cc",
        "Referer" to "https://net22.cc/verify2",
        "sec-ch-ua" to "\"Google Chrome\";v=\"124\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"124\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )

    return try {
        val client = http.baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        fun doPost(cfCookie: String?): okhttp3.Response {
            val cookieParts = mutableListOf<String>()
            if (!cfCookie.isNullOrEmpty()) {
                cookieParts.add("cf_clearance=$cfCookie")
            }
            val formBody = FormBody.Builder()
                .add("g-recaptcha-response", UUID.randomUUID().toString())
                .build()
            val request = Request.Builder()
                .url(verifyUrl)
                .post(formBody)
                .apply {
                    bypassHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (cookieParts.isNotEmpty()) {
                        addHeader("Cookie", cookieParts.joinToString("; "))
                    }
                }
                .build()
            return client.newCall(request).execute()
        }

        var response = doPost(null)
        var needCf = response.code == 403 || response.code == 503
        if (needCf) {
            response.close()
            val cfHost = hostOf(verifyUrl)
            val (existing, ts) = NetMirrorStorage.getCfCookie(cfHost)
            var cfCookie: String? = if (!existing.isNullOrEmpty() && System.currentTimeMillis() - ts < CF_COOKIE_TTL_MS) existing else null
            if (cfCookie == null) {
                cfCookie = solveCfInWebView(cfHost)
                if (!cfCookie.isNullOrEmpty()) {
                    NetMirrorStorage.saveCfCookie(cfHost, cfCookie)
                }
            }
            if (!cfCookie.isNullOrEmpty()) {
                response = doPost(cfCookie)
            }
        }

        response.use { resp ->
            val newCookie = resp.headers("Set-Cookie")
                .firstOrNull { it.startsWith("t_hash_t=") }
                ?.substringAfter("t_hash_t=")
                ?.substringBefore(";")
                .orEmpty()
            if (newCookie.isNotEmpty()) {
                NetMirrorStorage.saveCookie(newCookie)
            }
            newCookie
        }
    } catch (e: Exception) {
        Log.e(TAG, "bypass failed: ${e.message}")
        NetMirrorStorage.clearCookie()
        ""
    }
}

@Volatile
private var resolvedApiUrl: String = ""

suspend fun resolveApiUrl(): String {
    if (resolvedApiUrl.isNotBlank()) return resolvedApiUrl

    val (savedApiBase, savedTimestamp) = NetMirrorStorage.getApiBase()
    if (!savedApiBase.isNullOrEmpty() && System.currentTimeMillis() - savedTimestamp < API_BASE_TTL_MS) {
        resolvedApiUrl = savedApiBase
        return resolvedApiUrl
    }

    for (encoded in newTvDomains) {
        val base = decodeBase64(encoded).trimEnd('/')
        try {
            val response = cfGet("$base/checknewtv.php", headers = newTvBaseHeaders)
            val parsed = tryParseJson<NewTvTokenResponse>(response.text)
            val tokenHash = parsed?.token_hash
            if (!tokenHash.isNullOrBlank()) {
                resolvedApiUrl = decodeBase64(tokenHash).trimEnd('/')
                NetMirrorStorage.saveApiBase(resolvedApiUrl)
                return resolvedApiUrl
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveApiUrl $base failed: ${e.message}")
        }
    }
    throw Exception("Failed to resolve API URL")
}

suspend fun getNewTvUserToken(apiBase: String, ott: String, forceRefresh: Boolean = false): String {
    if (!forceRefresh) {
        val saved = NetMirrorStorage.getUserToken(ott)
        if (!saved.isNullOrEmpty()) return saved
    }

    val initialOtp = NetMirrorStorage.getOtp() ?: "109400"
    val otpHeaders = mutableMapOf(
        "accept" to "application/json, text/plain, */*",
        "cache-control" to "no-cache, no-store, must-revalidate",
        "Connection" to "Keep-Alive",
        "expires" to "0",
        "otp" to initialOtp,
        "pragma" to "no-cache",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.Gatu v1.0"
    )

    val firstResponse = cfGet("$apiBase/newtv/otp.php", headers = otpHeaders)
    val firstOtpResponse = tryParseJson<NewTvOtpResponse>(firstResponse.text)

    val firstToken = firstOtpResponse?.usertoken
    if (!firstToken.isNullOrEmpty()) {
        NetMirrorStorage.saveUserToken(ott, firstToken)
        return firstToken
    }

    val needsOtpRefresh = firstOtpResponse?.error_msg == "Invalid OTP, Please Enter Valid OTP"
    if (!needsOtpRefresh) return ""

    val tvHtml = fetchNetmirrorTvHtml()
    val otpMatch = Regex("""(?m)^\s*const\s+otp\s*=\s*\[(.*?)]""").find(tvHtml) ?: return ""
    val newOtp = Regex("""\s*,\s*""").replace(otpMatch.groupValues[1], "").replace(" ", "")
    if (newOtp.isEmpty()) return ""
    NetMirrorStorage.saveOtp(newOtp)
    otpHeaders["otp"] = newOtp

    val retryResponse = cfGet("$apiBase/newtv/otp.php", headers = otpHeaders)
    val retryOtpResponse = tryParseJson<NewTvOtpResponse>(retryResponse.text)
    val newToken = retryOtpResponse?.usertoken.orEmpty()
    if (newToken.isNotEmpty()) {
        NetMirrorStorage.saveUserToken(ott, newToken)
    }
    return newToken
}

suspend fun fetchNetmirrorTvHtml(): String = try {
    val (savedCf, savedCfTs) = NetMirrorStorage.getCfCookie("https://netmirror.gg")
    val cfCookieToUse =
        if (!savedCf.isNullOrEmpty() && System.currentTimeMillis() - savedCfTs < CF_COOKIE_TTL_MS) savedCf else null

    val headers = mutableMapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )
    if (!cfCookieToUse.isNullOrEmpty()) {
        headers["Cookie"] = "cf_clearance=$cfCookieToUse"
    }

    cfGet(NETMIRROR_TV_URL, headers = headers).text
} catch (e: Exception) {
    Log.e(TAG, "fetchNetmirrorTvHtml failed: ${e.message}")
    ""
}

val hdInterceptor = Interceptor { chain ->
    val request = chain.request()
    if (request.url.toString().contains(".m3u8")) {
        val newRequest = request.newBuilder().header("Cookie", "hd=on").build()
        chain.proceed(newRequest)
    } else {
        chain.proceed(request)
    }
}
