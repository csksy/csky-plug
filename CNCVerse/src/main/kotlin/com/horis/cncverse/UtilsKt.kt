package com.horis.cncverse

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.reflect.KClass

val JSONParser: ResponseParser = object : ResponseParser {
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)

    override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
        mapper.readValue(text, kClass.java)

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? = try {
        mapper.readValue(text, kClass.java)
    } catch (e: Exception) {
        Log.e("JSONParser", "parseSafe failed: ${e.message}")
        null
    }

    override fun writeValueAsString(obj: Any): String = mapper.writeValueAsString(obj)
}

val app: Requests = Requests(responseParser = JSONParser).apply {
    defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    )
}

inline fun <reified T : Any> parseJson(text: String): T =
    JSONParser.parse(text, T::class)

inline fun <reified T : Any> tryParseJson(text: String): T? = try {
    JSONParser.parseSafe(text, T::class)
} catch (e: Exception) {
    null
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

const val NETMIRROR_TV_URL = "https://netmirror.gg/tv"

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

// In-memory cache so we don't hit the discovery endpoint on every NewTv call
@Volatile
private var resolvedApiUrl: String = ""

fun buildNewTvHeaders(ott: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
    val result = newTvBaseHeaders.toMutableMap()
    result["Ott"] = ott
    result.putAll(extra)
    return result
}

// netmirror's verify endpoint issues short-lived t_hash_t cookies (~15h observed lifetime)
private const val BYPASS_COOKIE_TTL_MS = 54_000_000L

@Suppress("UNUSED_PARAMETER")
suspend fun bypass(mainUrl: String): String {
    val (savedCookie, savedTimestamp) = NetflixMirrorStorage.getCookie()
    if (!savedCookie.isNullOrEmpty() && System.currentTimeMillis() - savedTimestamp < BYPASS_COOKIE_TTL_MS) {
        return savedCookie
    }

    val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "Content-Type" to "application/x-www-form-urlencoded",
        "Origin" to "https://net22.cc",
        "Referer" to "https://net22.cc/verify2",
        "sec-ch-ua" to "\"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
    )

    val body = FormBody.Builder()
        .add("g-recaptcha-response", UUID.randomUUID().toString())
        .build()
    // Redirects disabled so the Set-Cookie on the verify response is the source of truth
    val client = app.baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    val request = Request.Builder()
        .url("https://net52.cc/verify.php")
        .post(body)
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .build()

    return try {
        client.newCall(request).execute().use { response ->
            val newCookie = response.headers("Set-Cookie")
                .firstOrNull { it.startsWith("t_hash_t=") }
                ?.substringAfter("t_hash_t=")
                ?.substringBefore(";")
                .orEmpty()
            if (newCookie.isNotEmpty()) {
                NetflixMirrorStorage.saveCookie(newCookie)
            }
            newCookie
        }
    } catch (e: Exception) {
        Log.e("UtilsKt", "bypass failed: ${e.message}")
        NetflixMirrorStorage.clearCookie()
        throw e
    }
}

// One day TTL on the resolved API URL — the rotate-by-base64 endpoints don't change often
private const val API_BASE_TTL_MS = 86_400_000L

suspend fun resolveApiUrl(): String {
    if (resolvedApiUrl.isNotBlank()) return resolvedApiUrl

    val (savedApiBase, savedTimestamp) = NetflixMirrorStorage.getApiBase()
    if (!savedApiBase.isNullOrEmpty() && System.currentTimeMillis() - savedTimestamp < API_BASE_TTL_MS) {
        resolvedApiUrl = savedApiBase
        return resolvedApiUrl
    }

    for (encoded in newTvDomains) {
        val base = decodeBase64(encoded).trimEnd('/')
        try {
            val response = app.get("$base/checknewtv.php", headers = newTvBaseHeaders)
            val parsed = tryParseJson<NewTvTokenResponse>(response.text)
            val tokenHash = parsed?.token_hash
            if (!tokenHash.isNullOrBlank()) {
                resolvedApiUrl = decodeBase64(tokenHash).trimEnd('/')
                NetflixMirrorStorage.saveApiBase(resolvedApiUrl)
                return resolvedApiUrl
            }
        } catch (e: Exception) {
            Log.d("UtilsKt", "resolveApiUrl candidate $base failed: ${e.message}")
        }
    }
    throw Exception("Failed to resolve NewTV API base URL")
}

suspend fun getNewTvUserToken(apiBase: String, ott: String, forceRefresh: Boolean = false): String {
    val (savedToken, _) = NetflixMirrorStorage.getUserToken(ott)
    if (!forceRefresh && !savedToken.isNullOrEmpty()) {
        return savedToken
    }

    val initialOtp = NetflixMirrorStorage.getOtp() ?: "109400"
    val otpHeaders = mutableMapOf(
        "accept" to "application/json, text/plain, */*",
        "cache-control" to "no-cache, no-store, must-revalidate",
        "Connection" to "Keep-Alive",
        "expires" to "0",
        "otp" to initialOtp,
        "pragma" to "no-cache",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.Gatu v1.0"
    )

    val firstResponse = app.get("$apiBase/newtv/otp.php", headers = otpHeaders)
    val firstOtpResponse = tryParseJson<NewTvOtpResponse>(firstResponse.text)

    val firstToken = firstOtpResponse?.usertoken
    if (!firstToken.isNullOrEmpty()) {
        NetflixMirrorStorage.saveUserToken(ott, firstToken)
        return firstToken
    }

    // No token — only retry when the server explicitly complains about the OTP
    val needsOtpRefresh =
        firstOtpResponse?.error_msg == "Invalid OTP, Please Enter Valid OTP"
    if (!needsOtpRefresh) return ""

    // OTP rotates server-side — pull the current value from the netmirror.gg/tv JS source
    val tvHtml = fetchNetmirrorTvHtml()
    val otpMatch = Regex("""(?m)^\s*const\s+otp\s*=\s*\[(.*?)]""").find(tvHtml) ?: return ""
    val newOtp = Regex("""\s*,\s*""").replace(otpMatch.groupValues[1], "").replace(" ", "")
    if (newOtp.isEmpty()) return ""
    NetflixMirrorStorage.saveOtp(newOtp)
    otpHeaders["otp"] = newOtp

    val retryResponse = app.get("$apiBase/newtv/otp.php", headers = otpHeaders)
    val retryOtpResponse = tryParseJson<NewTvOtpResponse>(retryResponse.text)
    val newToken = retryOtpResponse?.usertoken.orEmpty()
    if (newToken.isNotEmpty()) {
        NetflixMirrorStorage.saveUserToken(ott, newToken)
    }
    return newToken
}

// cf_clearance cookies last about a day; we keep them for 23h before forcing a re-solve
private const val CF_COOKIE_TTL_MS = 82_800_000L

private fun buildTvHtmlHeaders(cfClearance: String?): Map<String, String> {
    val h = mutableMapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )
    if (!cfClearance.isNullOrEmpty()) {
        h["Cookie"] = "cf_clearance=$cfClearance"
    }
    return h
}

private fun isCloudflare(html: String, statusCode: Int): Boolean {
    if (statusCode == 403 || statusCode == 503) return true
    if (!html.contains("netmirror.gg/tv", ignoreCase = true)) return false
    return html.contains("cf-browser-verification", ignoreCase = true) ||
        html.contains("Checking if the site connection is secure", ignoreCase = true) ||
        html.contains("Just a moment", ignoreCase = true) ||
        html.contains("cloudflare", ignoreCase = true)
}

suspend fun fetchNetmirrorTvHtml(): String = try {
    val (savedCf, savedCfTs) = NetflixMirrorStorage.getCfCookie()
    val cfCookieToUse =
        if (!savedCf.isNullOrEmpty() && System.currentTimeMillis() - savedCfTs < CF_COOKIE_TTL_MS) savedCf else null

    val firstResponse = app.get(NETMIRROR_TV_URL, headers = buildTvHtmlHeaders(cfCookieToUse))
    if (!isCloudflare(firstResponse.text, firstResponse.code)) {
        return firstResponse.text
    }

    val cfClearance = solveCloudflareInWebView(NETMIRROR_TV_URL) ?: return firstResponse.text
    NetflixMirrorStorage.saveCfCookie(cfClearance)

    try {
        app.get(NETMIRROR_TV_URL, headers = buildTvHtmlHeaders(cfClearance)).text
    } catch (e: Exception) {
        firstResponse.text
    }
} catch (e: Exception) {
    Log.e("UtilsKt", "fetchNetmirrorTvHtml failed: ${e.message}")
    ""
}

// 30s overall cap matches the spec for WebView-based CF solving
private const val CF_SOLVE_TIMEOUT_MS = 30_000L
private const val CF_COOKIE_POLL_MS = 1_000L
private const val CF_CLEARANCE_PATTERN = "cf_clearance=([^;]+)"
private const val CURSOR_STEP_DP = 10f

private class CursorPosHolder { var x: Float = 0f; var y: Float = 0f }

suspend fun solveCloudflareInWebView(url: String): String? {
    val ctx = NetflixMirrorProvider.getContext() ?: return null
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
            val cfRegex = Regex(CF_CLEARANCE_PATTERN)
            val mainHandler = Handler(Looper.getMainLooper())

            fun extractAndFinish() {
                if (resolved.get()) return
                val cookies = cookieManager.getCookie(url).orEmpty()
                val cf = cfRegex.find(cookies)?.groupValues?.getOrNull(1)
                if (!cf.isNullOrEmpty()) {
                    resolved.set(true)
                    mainHandler.removeCallbacksAndMessages(null)
                    try { wv.destroy() } catch (_: Exception) {}
                    try { (wv.tag as? AlertDialog)?.dismiss() } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(cf)
                }
            }

            // Cloudflare sometimes sets the cookie after the page renders, so poll until found or timeout
            val poller = object : Runnable {
                override fun run() {
                    extractAndFinish()
                    if (!resolved.get()) {
                        mainHandler.postDelayed(this, CF_COOKIE_POLL_MS)
                    }
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, pageUrl: String) {
                    super.onPageFinished(view, pageUrl)
                    extractAndFinish()
                    if (!resolved.get()) {
                        mainHandler.postDelayed(poller, CF_COOKIE_POLL_MS)
                    }
                }
            }

            val density = ctx.resources.displayMetrics.density
            val wm = ctx.getSystemService("window") as WindowManager
            val metrics = Point().also { wm.defaultDisplay.getSize(it) }
            val dialogW = (metrics.x * 0.95f).toInt()
            val dialogH = (metrics.y * 0.9f).toInt()

            val wrapper = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                minimumWidth = dialogW
                minimumHeight = dialogH
            }

            val caption = TextView(ctx).apply {
                text = "Solve the Cloudflare captcha — use D-pad to move cursor, OK to click."
                setTextColor(-1)
                setBackgroundColor(Color.parseColor("#1A1A2E"))
                textSize = 13f
                val p = (10 * density).toInt()
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            wrapper.addView(caption)

            val container = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply { weight = 1f }
                isFocusable = true
                isFocusableInTouchMode = true
            }
            wv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            container.addView(wv)

            val isTv = try { Globals.isLayout(Globals.TV) } catch (_: Throwable) { false }
            if (isTv) {
                val cursorSize = (22 * density).toInt()
                val pos = CursorPosHolder()
                pos.x = dialogW / 2f
                pos.y = dialogH / 2f
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
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            pos.y = (pos.y - step).coerceIn(0f, container.height.toFloat())
                            cursor.translationY = pos.y - cursorSize / 2f
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            pos.y = (pos.y + step).coerceIn(0f, container.height.toFloat())
                            cursor.translationY = pos.y - cursorSize / 2f
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            pos.x = (pos.x - step).coerceIn(0f, container.width.toFloat())
                            cursor.translationX = pos.x - cursorSize / 2f
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            pos.x = (pos.x + step).coerceIn(0f, container.width.toFloat())
                            cursor.translationX = pos.x - cursorSize / 2f
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
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
                            true
                        }
                        else -> false
                    }
                }
            }
            wrapper.addView(container)

            val dialog = AlertDialog.Builder(ctx).setView(wrapper).setCancelable(false).create()
            dialog.window?.let { win ->
                win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                win.setLayout(dialogW, dialogH)
            }
            wv.tag = dialog

            dialog.setOnDismissListener {
                if (!resolved.get()) {
                    resolved.set(true)
                    mainHandler.removeCallbacksAndMessages(null)
                    try { wv.destroy() } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(null)
                }
            }

            val timeoutRunnable = Runnable {
                if (!resolved.get()) {
                    resolved.set(true)
                    mainHandler.removeCallbacksAndMessages(null)
                    try { wv.destroy() } catch (_: Exception) {}
                    try { dialog.dismiss() } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(null)
                }
            }
            mainHandler.postDelayed(timeoutRunnable, CF_SOLVE_TIMEOUT_MS)

            cont.invokeOnCancellation {
                mainHandler.removeCallbacksAndMessages(null)
                try { wv.destroy() } catch (_: Exception) {}
                try { dialog.dismiss() } catch (_: Exception) {}
            }

            dialog.show()
            wv.loadUrl(url)
        }
    }
}
