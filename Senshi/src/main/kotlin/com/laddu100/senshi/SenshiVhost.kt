package com.laddu100.senshi

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// the gateway moved its handshake behind a wasm protocol that ships inside a player
// bundle the site regenerates every couple of hours, so the constants can never be
// pinned. the live bundle runs in a hidden webview instead and the plugin just asks
// it to open a source id, rotations included for free
object SenshiVhost {

    private const val TAG = "Senshi"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val FALLBACK_BUNDLE = "https://cdn.vidcloud.se/vjs/vendor.js"
    private const val BRIDGE_PATH = "/__senshi_bridge"
    private const val BUNDLE_TTL = 10 * 60 * 1000L
    private const val PAGE_TIMEOUT = 25_000L
    private const val OPEN_TIMEOUT = 40_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    @Volatile
    private var origin = "https://senshi.to"

    @Volatile
    private var bundleUrl: String? = null
    @Volatile
    private var bundleBody: String? = null
    @Volatile
    private var bundleTs = 0L

    private var webView: WebView? = null
    @Volatile
    private var pageUp = false
    @Volatile
    private var loadedOrigin: String? = null
    @Volatile
    private var loadedBundle: String? = null
    private var readySignal: CompletableDeferred<Boolean> = CompletableDeferred()

    private val openMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    // the runtime fetches from the webview js thread, a plain sync client keeps that simple
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    suspend fun refreshDomain() {
        FirebaseDomainHelper.getDomain("senshi")?.let {
            val clean = it.removeSuffix("/")
            if (clean.isNotBlank()) origin = clean
        }
    }

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "*/*",
        "Origin" to origin,
        "Referer" to "$origin/"
    )

    // find the current player bundle on the site, the cdn name moves with the rotation
    private suspend fun loadBundle(fresh: Boolean): String? {
        val now = System.currentTimeMillis()
        if (!fresh && bundleBody != null && now - bundleTs < BUNDLE_TTL) {
            return bundleUrl
        }
        var url: String? = null
        try {
            val res = cfGet("$origin/", headers = baseHeaders(), timeout = 15_000L)
            if (res.code == 200) {
                url = Regex("""src=["']([^"']*vidcloud[^"']*\.js[^"']*)["']""")
                    .find(res.text)?.groupValues?.get(1)
                    ?.let { java.net.URI(origin).resolve(it).toString() }
            }
        } catch (_: Exception) {
        }
        if (url.isNullOrBlank()) {
            url = bundleUrl ?: FALLBACK_BUNDLE
        }
        val res = try {
            cfGet(url, headers = baseHeaders(), timeout = 20_000L)
        } catch (_: Exception) {
            return null
        }
        if (res.code != 200 || !res.text.contains("__oct")) {
            return null
        }
        bundleUrl = url
        bundleBody = res.text
        bundleTs = now
        return url
    }

    private class Bridge {
        @JavascriptInterface
        fun request(url: String, method: String, headersJson: String, bodyB64: String): String {
            return relay(url, method, headersJson, bodyB64)
        }

        @JavascriptInterface
        fun onReady() {
            readySignal.complete(true)
        }

        @JavascriptInterface
        fun onResult(token: String, json: String) {
            pending.remove(token)?.complete(json)
        }
    }

    private fun relay(url: String, method: String, headersJson: String, bodyB64: String): String {
        return try {
            val extra = try {
                parseJson<Map<String, String>>(headersJson)
            } catch (_: Exception) {
                emptyMap()
            }
            val headers = senshiHeaders(baseHeaders() + extra, url)
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (method.equals("POST", ignoreCase = true)) {
                val bytes = if (bodyB64.isNotEmpty()) {
                    Base64.decode(bodyB64, Base64.NO_WRAP)
                } else {
                    ByteArray(0)
                }
                val contentType = headers["Content-Type"] ?: "image/png"
                builder.post(bytes.toRequestBody(contentType.toMediaType()))
            } else {
                builder.get()
            }
            client.newCall(builder.build()).execute().use { resp ->
                val body = try {
                    resp.body?.bytes() ?: ByteArray(0)
                } catch (_: Exception) {
                    ByteArray(0)
                }
                val b64 = Base64.encodeToString(body, Base64.NO_WRAP)
                "${resp.code}\n${resp.header("Content-Type") ?: ""}\n$b64"
            }
        } catch (e: Exception) {
            "0\n\n"
        }
    }

    private fun relayResource(url: String): WebResourceResponse? {
        return try {
            val builder = Request.Builder().url(url).get()
            baseHeaders().forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.bytes() ?: return null
                val reason = if (resp.isSuccessful) "OK" else "HTTP ${resp.code}"
                val flat = resp.headers.toMultimap().entries
                    .associate { it.key to (it.value.firstOrNull() ?: "") }
                WebResourceResponse(
                    resp.header("Content-Type") ?: "application/octet-stream",
                    reason,
                    resp.code,
                    if (resp.isSuccessful) "OK" else reason,
                    flat,
                    body.inputStream()
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun bridgePage(bundle: String): String {
        val head = """
<script>
(function(){
  function toBytes(b){var s=atob(b),u=new Uint8Array(s.length);for(var i=0;i<s.length;i++)u[i]=s.charCodeAt(i);return u;}
  function toB64(u){var s="";for(var i=0;i<u.length;i++)s+=String.fromCharCode(u[i]);return btoa(s);}
  window.fetch=function(url,opts){
    opts=opts||{};
    var b64="";
    if(opts.body){
      try{
        var b=opts.body;
        if(b instanceof Uint8Array)b64=toB64(b);
        else if(b instanceof ArrayBuffer)b64=toB64(new Uint8Array(b));
        else b64=btoa(String(b));
      }catch(e){}
    }
    var h={};
    if(opts.headers){for(var k in opts.headers){try{h[k]=String(opts.headers[k]);}catch(e2){}}}
    var out=SenshiBridge.request(String(url),opts.method||"GET",JSON.stringify(h),b64);
    var p=out.split("\n");
    var status=parseInt(p[0],10)||0;
    var ct=p.length>1?p[1]:"application/octet-stream";
    var body=p.length>2?toBytes(p.slice(2).join("\n")):new Uint8Array(0);
    return Promise.resolve({
      ok:status>=200&&status<300,
      status:status,
      headers:{get:function(n){return String(n).toLowerCase()==="content-type"?ct:null;}},
      arrayBuffer:function(){return Promise.resolve(body.buffer);},
      text:function(){return Promise.resolve(new TextDecoder().decode(body));},
      json:function(){return Promise.resolve(JSON.parse(new TextDecoder().decode(body)));}
    });
  };
  window.__bridgeOpen=function(token,id){
    var tries=0;
    (function run(){
      if(window.__oct){
        try{
          window.__oct.open(Number(id)).then(function(r){
            SenshiBridge.onResult(token,JSON.stringify({ok:true,r:r}));
          },function(err){
            SenshiBridge.onResult(token,JSON.stringify({ok:false,e:String(err&&err.message||err)}));
          });
        }catch(e){
          SenshiBridge.onResult(token,JSON.stringify({ok:false,e:String(e&&e.message||e)}));
        }
        return;
      }
      tries++;
      if(tries>60){SenshiBridge.onResult(token,JSON.stringify({ok:false,e:"runtime missing"}));return;}
      setTimeout(run,250);
    })();
  };
})();
</script>
<script src="__BUNDLE__"></script>
<script>SenshiBridge.onReady();</script>
""".trimIndent().replace("__BUNDLE__", bundle)
        return "<!DOCTYPE html><html><head>$head</head><body></body></html>"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(bundle: String) {
        val ctx = appContext ?: return
        val wv = WebView(ctx)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        wv.addJavascriptInterface(Bridge(), "SenshiBridge")
        val currentBundle = bundle
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (url == "$origin$BRIDGE_PATH") {
                    return WebResourceResponse("text/html", "utf-8", bridgePage(currentBundle).byteInputStream())
                }
                if (url == currentBundle) {
                    val body = bundleBody
                        ?: return WebResourceResponse("application/javascript", "utf-8", ByteArray(0).inputStream())
                    return WebResourceResponse("application/javascript", "utf-8", body.byteInputStream())
                }
                // side files the runtime may pull from its own cdn stay on okhttp too
                val bundleHost = try {
                    java.net.URI(currentBundle).host
                } catch (_: Exception) {
                    null
                }
                if (bundleHost != null && url.startsWith("https://$bundleHost/")) {
                    return relayResource(url)
                }
                return null
            }
        }
        webView = wv
        loadedOrigin = origin
        loadedBundle = currentBundle
        wv.loadUrl("$origin$BRIDGE_PATH")
    }

    private fun destroyPage() {
        val wv = webView
        webView = null
        pageUp = false
        if (wv != null) {
            try {
                wv.stopLoading()
                wv.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun ensurePage(fresh: Boolean): Boolean {
        if (!fresh && pageUp && webView != null && loadedOrigin == origin && loadedBundle == bundleUrl) {
            return true
        }
        val bundle = loadBundle(fresh)
        if (bundle == null && bundleBody == null) {
            return false
        }
        val useBundle = bundle ?: bundleUrl ?: return false
        val signal = CompletableDeferred<Boolean>()
        readySignal = signal
        pageUp = false
        mainHandler.post {
            try {
                destroyPage()
                buildWebView(useBundle)
            } catch (e: Exception) {
                Log.e(TAG, "bridge page failed: ${e.message}")
            }
        }
        val ok = withTimeoutOrNull(PAGE_TIMEOUT) { signal.await() } == true
        pageUp = ok
        if (!ok) {
            mainHandler.post { destroyPage() }
        }
        return ok
    }

    private data class BridgeReply(
        val ok: Boolean = false,
        val r: Any? = null,
        val e: String? = null
    )

    suspend fun fetchSources(sourceId: Int): List<VidcloudSource>? = openMutex.withLock {
        for (attempt in 0..1) {
            if (!ensurePage(attempt > 0)) {
                continue
            }
            val token = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<String>()
            pending[token] = deferred
            val wv = webView
            if (wv == null) {
                pending.remove(token)
                continue
            }
            mainHandler.post {
                try {
                    wv.evaluateJavascript("window.__bridgeOpen&&window.__bridgeOpen(\"$token\",$sourceId);", null)
                } catch (e: Exception) {
                    Log.d(TAG, "open eval failed: ${e.message}")
                    pending.remove(token)?.complete("")
                }
            }
            val json = withTimeoutOrNull(OPEN_TIMEOUT) { deferred.await() }
            pending.remove(token)
            if (json != null) {
                parseReply(json)?.let { return it }
            }
            // the page or the bundle went stale mid flight, drop it so the next
            // attempt reloads the current bundle from the site
            bundleTs = 0L
            mainHandler.post { destroyPage() }
        }
        null
    }

    private fun parseReply(json: String): List<VidcloudSource>? {
        return try {
            val reply = parseJson<BridgeReply>(json)
            if (!reply.ok) {
                Log.d(TAG, "bridge open failed: ${reply.e}")
                return null
            }
            val raw = reply.r ?: return emptyList()
            val asText = raw.toJson()
            try {
                parseJson<List<VidcloudSource>>(asText)
            } catch (_: Exception) {
                try {
                    listOf(parseJson<VidcloudSource>(asText))
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
