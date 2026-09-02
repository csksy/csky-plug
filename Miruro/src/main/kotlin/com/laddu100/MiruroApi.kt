package com.laddu100

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.coroutines.resume

object MiruroApi {

    var context: Context? = null

    private const val TAG = "Miruro"

    const val DEFAULT_DOMAIN = "https://www.miruro.to"

    private val MIRRORS = listOf(
        "https://www.miruro.to",
        "https://www.miruro.tv",
        "https://www.miruro.ru",
        "https://www.miruro.bz"
    )

    // webview and okhttp must share the same user agent or cf_clearance cookies
    // cached after a webview solve get rejected on the direct path
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    // key the site's player bundle ships to obfuscate pipe responses (x-obfuscated: 2)
    private val PIPE_XOR_KEY = byteArrayOf(
        0x71, 0x95.toByte(), 0x10, 0x34, 0xF8.toByte(), 0xFB.toByte(), 0xCF.toByte(), 0x53,
        0xD8.toByte(), 0x9D.toByte(), 0xB5.toByte(), 0x2C, 0xEB.toByte(), 0x3D, 0xC2.toByte(), 0x2C
    )

    private val domainRef = AtomicReference<String>(DEFAULT_DOMAIN)
    private val cookieCache = ConcurrentHashMap<String, String>()
    private val domainLocks = ConcurrentHashMap<String, Mutex>()

    // the same webview solver anisuge and anishows in this repo use; wired in
    // as an okhttp interceptor it opens a challenge webview once and then
    // keeps replaying the cf_clearance cookies on every request
    private val cfKillers = ConcurrentHashMap<String, CloudflareKiller>()

    private fun killerFor(domain: String): CloudflareKiller =
        cfKillers.getOrPut(domain) { CloudflareKiller() }

    @Volatile
    var remoteDomain: String? = null

    fun workingDomain(): String = remoteDomain ?: domainRef.get() ?: DEFAULT_DOMAIN

    fun setWorkingDomain(domain: String) {
        domainRef.set(domain)
    }

    private fun encodeRequest(payload: Map<String, Any?>): String {
        val json = payload.toJson()
        return Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun decompress(data: ByteArray): String {
        if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            ByteArrayInputStream(data).use { bais ->
                GZIPInputStream(bais).use { gzis -> return gzis.readBytes().toString(Charsets.UTF_8) }
            }
        }

        val isZlib = data.size > 1 &&
            (data[0].toInt() and 0x0f) == 0x08 &&
            (data[0].toInt() shr 4) <= 7 &&
            (((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)) % 31 == 0

        val inflater = if (isZlib) Inflater() else Inflater(true)
        ByteArrayInputStream(data).use { bais ->
            InflaterInputStream(bais, inflater).use { iis ->
                return iis.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    private fun xor(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (i in data.indices) {
            out[i] = (data[i].toInt() xor PIPE_XOR_KEY[i % PIPE_XOR_KEY.size].toInt()).toByte()
        }
        return out
    }

    private fun decodeResponse(body: String, obfuscated: String?): String {
        if (obfuscated == null) return body.trim()

        val trimmed = body.trim()
        val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
        var decoded = Base64.decode(padded, Base64.URL_SAFE)
        if (obfuscated == "2") {
            decoded = xor(decoded)
        }
        return decompress(decoded)
    }

    // inside a webview the response headers are not observable, so the encoding
    // has to be detected from the body itself
    private fun decodeResponseAuto(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed

        val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
        val decoded = try {
            Base64.decode(padded, Base64.URL_SAFE)
        } catch (e: Exception) {
            throw Exception("pipe response is not base64: ${e.message}")
        }

        return try {
            decompress(decoded)
        } catch (e: Exception) {
            decompress(xor(decoded))
        }
    }

    private fun isCloudflareBlock(body: String, code: Int): Boolean {
        val lower = body.lowercase()
        if (code == 403 || code == 503) {
            return lower.contains("cloudflare") ||
                lower.contains("just a moment") ||
                lower.contains("sorry, you have been blocked") ||
                lower.contains("attention required") ||
                lower.contains("challenge-platform")
        }
        return lower.contains("just a moment") && lower.contains("challenge")
    }

    suspend fun pipeRequest(path: String, query: Map<String, Any?>): String {
        return pipeRequest(path, query, live = true)
    }

    // live requests bypass the api's server side cache the same way the site
    // does when a user opens a watch page; config is static so it skips this
    suspend fun pipeRequest(path: String, query: Map<String, Any?>, live: Boolean): String {
        val enriched = HashMap<String, Any?>(query)
        if (live) {
            enriched["live"] = "true"
            enriched["_t"] = (System.currentTimeMillis() / 600_000L) * 600_000L
        }

        val encoded = encodeRequest(
            mapOf(
                "path" to path,
                "method" to "GET",
                "query" to enriched,
                "body" to null
            )
        )

        val first = workingDomain()
        val ordered = mutableListOf(first)
        MIRRORS.filter { it !in ordered }.forEach { ordered.add(it) }

        var lastError: Exception? = null
        // the webview solve is slow, so only the primary mirror and one backup
        // get the full treatment before giving up
        for ((index, domain) in ordered.withIndex()) {
            try {
                return pipeRequestForDomain(domain, encoded, path, allowWebView = index < 2)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("pipe request failed for /$path")
    }

    private suspend fun pipeRequestForDomain(
        domain: String,
        encoded: String,
        path: String,
        allowWebView: Boolean
    ): String {
        val pipeUrl = "$domain/api/secure/pipe?e=$encoded"

        try {
            return directFetch(domain, pipeUrl)
        } catch (e: Exception) {
            Log.d(TAG, "direct pipe fetch failed on $domain: ${e.message}")
        }

        if (!allowWebView) {
            throw Exception("cloudflare block on $domain for /$path")
        }

        // link loading fires one pipe call per provider, and without this
        // gate every one of them would spin up its own webview at once; the
        // first solve wins, everyone else retries direct with the new cookies
        val lock = domainLocks.getOrPut(domain) { Mutex() }
        lock.withLock {
            try {
                return directFetch(domain, pipeUrl)
            } catch (e: Exception) {
                Log.d(TAG, "direct retry after lock failed on $domain: ${e.message}")

                // cloudflarekiller keeps its own cookie jar and webview ua,
                // so it survives the cookie/ua pairing problems a hand rolled
                // retry loop runs into
                try {
                    return killerFetch(domain, pipeUrl, path)
                } catch (killer: Exception) {
                    Log.d(TAG, "cloudflare killer fetch failed on $domain: ${killer.message}")
                }
            }

            val webBody = fetchViaWebView(domain, pipeUrl)
                ?: throw Exception("pipe request blocked on $domain for /$path")

            return decodeResponseAuto(webBody)
        }
    }

    private suspend fun killerFetch(domain: String, pipeUrl: String, path: String): String {
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$domain/",
            "Origin" to domain,
            "Accept" to "*/*"
        )
        val response = app.get(pipeUrl, headers = headers, interceptor = killerFor(domain))
        if (response.code == 200) {
            val body = response.text
            if (!isCloudflareBlock(body, 200)) {
                return try {
                    decodeResponse(body, response.headers["x-obfuscated"])
                } catch (e: Exception) {
                    decodeResponseAuto(body)
                }
            }
        }
        throw Exception("http ${response.code} from pipe /$path")
    }

    private suspend fun directFetch(domain: String, pipeUrl: String): String {
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$domain/",
            "Origin" to domain,
            "Accept" to "*/*"
        )
        cookieCache[domain]?.let { headers["Cookie"] = it }

        val response = app.get(pipeUrl, headers = headers, timeout = 20)
        if (response.code == 200) {
            val body = response.text
            if (!isCloudflareBlock(body, 200)) {
                return try {
                    decodeResponse(body, response.headers["x-obfuscated"])
                } catch (e: Exception) {
                    decodeResponseAuto(body)
                }
            }
        }
        throw Exception("http ${response.code} from pipe")
    }

    // the pipe endpoint sits behind cloudflare's managed challenge; loading the
    // site in a webview lets the challenge clear itself, then the pipe request
    // runs from inside the page so it carries cf_clearance and the domain cookies
    private suspend fun fetchViaWebView(domain: String, pipeUrl: String): String? {
        val ctx = context ?: return null

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                val injected = AtomicBoolean(false)
                var webView: WebView? = null

                fun finish(result: String?) {
                    if (!done.compareAndSet(false, true)) return
                    try {
                        CookieManager.getInstance().getCookie(domain)?.takeIf { it.isNotEmpty() }
                            ?.let { cookieCache[domain] = it }
                    } catch (e: Exception) {
                        Log.d(TAG, "cookie capture failed: ${e.message}")
                    }
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                    } catch (e: Exception) {
                        Log.d(TAG, "webview cleanup failed: ${e.message}")
                    }
                    cont.resume(result)
                }

                fun injectFetch(view: WebView?) {
                    if (done.get() || !injected.compareAndSet(false, true)) return
                    val relative = pipeUrl.substringAfter(domain)
                    val js = """
                        (function() {
                            window.__pipe = null;
                            fetch("$relative", {
                                method: "GET",
                                credentials: "include",
                                headers: { "Accept": "*/*" }
                            }).then(function(r) {
                                return r.text();
                            }).then(function(t) {
                                window.__pipe = t;
                            }).catch(function() {
                                window.__pipe = "";
                            });
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js) {}

                    for (i in 1..40) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (done.get()) return@postDelayed
                            view?.evaluateJavascript("window.__pipe") { result ->
                                if (done.get()) return@evaluateJavascript
                                if (result != null && result != "null") {
                                    val text = result.trim().removeSurrounding("\"")
                                        .replace("\\n", "\n")
                                        .replace("\\\"", "\"")
                                        .replace("\\\\", "\\")
                                    when {
                                        text.isEmpty() -> finish(null)
                                        text.length > 10 -> finish(text)
                                    }
                                }
                            }
                        }, i * 500L)
                    }
                }

                fun checkReady(view: WebView?) {
                    if (done.get() || injected.get()) return
                    view?.evaluateJavascript("document.title") { titleResult ->
                        if (done.get() || injected.get()) return@evaluateJavascript
                        val title = titleResult?.trim()?.removeSurrounding("\"") ?: ""
                        val lower = title.lowercase()
                        val challenge = lower.contains("just a moment") ||
                            lower.contains("attention required") ||
                            lower.contains("blocked") ||
                            lower.contains("cloudflare") ||
                            title.isBlank()
                        if (!challenge) {
                            injectFetch(view)
                        }
                    }
                }

                try {
                    webView = WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = USER_AGENT
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                super.onPageFinished(view, pageUrl)
                                Handler(Looper.getMainLooper()).postDelayed({ checkReady(view) }, 400)
                            }
                        }
                    }

                    webView?.loadUrl(domain)

                    for (i in 1..20) {
                        Handler(Looper.getMainLooper()).postDelayed({ checkReady(webView) }, i * 1000L)
                    }

                    // ~25s hard cap; hanging longer just burns cloudstream's own timeout
                    Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, 25_000L)
                } catch (e: Exception) {
                    Log.d(TAG, "webview setup failed: ${e.message}")
                    finish(null)
                }
            }
        }
    }

    suspend inline fun <reified T : Any> pipeJson(path: String, query: Map<String, Any?>): T {
        val body = pipeRequest(path, query)
        return parseJson(body)
    }
}

object AniListApi {

    private const val URL = "https://graphql.anilist.co"

    suspend fun query(query: String, variables: Map<String, Any?>): String {
        val body = mapOf(
            "query" to query,
            "variables" to variables
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        return app.post(
            URL,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            ),
            requestBody = body
        ).text
    }
}
