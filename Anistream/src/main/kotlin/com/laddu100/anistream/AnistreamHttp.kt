package com.laddu100.anistream

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resilient HTTP layer for the Anistream plugin.
 *
 * Why this exists: plain `app.get` failed silently on some devices
 * with NO feedback. Three independent real-world blockers were identified:
 *
 *  1. Cloudflare bot challenge (403/503) against the app's OkHttp TLS
 *     fingerprint - browsers pass, apps get challenged.
 *     -> Fixed by per-host CloudflareKiller retry (WebView solver, the same
 *       mechanism AniSuge/AniShows/TheMoviesFlix in this repo ecosystem use).
 *
 *  2. ISP-level DNS blocking (common on Indian mobile ISPs for streaming
 *     domains - the browser may bypass via Secure DNS, apps use system DNS).
 *     -> Fixed by a DNS-over-HTTPS resolver (1.1.1.1 JSON API) wired into a
 *       custom OkHttp client (same mechanism as DamiTVProvider).
 *
 *  3. The site's own frontend sends `credentials: 'include'` on all REST
 *     calls - the `_amx_id` cookie set by api.anistream.one is part of the
 *     expected flow.
 *     -> Fixed by an in-memory per-host cookie jar that mirrors browser
 *       behavior (set-cookie captured, cookie header injected).
 *
 * Additionally: 429 rate-limit retry (the API allows 30/min GraphQL,
 * 100/min REST) and descriptive exceptions that surface in the CloudStream
 * UI instead of silent empty rows.
 */
object AnistreamHttp {

    private const val TAG = "Anistream"

    /**
     * Mobile browser UA. CloudflareKiller rewrites requests with the WebView
     * UA itself on its retry path, so a sane static UA is enough here.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

    // Cloudflare

    private val cfKillerMap = ConcurrentHashMap<String, CloudflareKiller>()
    private val cfMutexMap = ConcurrentHashMap<String, Mutex>()

    private fun killerFor(url: String): CloudflareKiller =
        cfKillerMap.getOrPut(hostOf(url)) { CloudflareKiller() }

    private fun mutexFor(url: String): Mutex =
        cfMutexMap.getOrPut(hostOf(url)) { Mutex() }

    private fun isCloudflareCode(code: Int): Boolean = code == 403 || code == 503

    // Cookies

    /** host -> cookie name -> value (mirrors browser cookie jar per host). */
    private val cookieJar = ConcurrentHashMap<String, MutableMap<String, String>>()

    private fun hostOf(url: String): String = try {
        url.toHttpUrl().host
    } catch (e: Exception) {
        url
    }

    private fun cookieHeader(url: String): String? {
        val host = hostOf(url)
        val cookies = cookieJar[host] ?: return null
        if (cookies.isEmpty()) return null
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun storeCookies(url: String, values: List<String>) {
        if (values.isEmpty()) return
        val host = hostOf(url)
        val jar = cookieJar.getOrPut(host) { ConcurrentHashMap() }
        for (raw in values) {
            // "name=value; Path=/; Secure; ..."
            val first = raw.substringBefore(';').trim()
            val name = first.substringBefore('=').trim()
            val value = first.substringAfter('=', "").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) {
                jar[name] = value
                Log.d(TAG, "cookie stored: $host $name")
            }
        }
    }

    private fun headersWithCookies(url: String, headers: Map<String, String>): Map<String, String> {
        val cookie = cookieHeader(url) ?: return headers
        val merged = headers.toMutableMap()
        val existing = merged["Cookie"].orEmpty()
        merged["Cookie"] = if (existing.isBlank()) cookie else "$existing; $cookie"
        return merged
    }

    /** Pull cf_clearance (and friends) out of a killer into our jar.
     *  savedCookies is keyed: host -> (cookieName -> cookieValue). */
    private fun harvestKillerCookies(url: String) {
        try {
            val host = hostOf(url)
            val killer = cfKillerMap[host] ?: return
            val hostCookies = killer.savedCookies[host] ?: return
            if (hostCookies.isEmpty()) return
            val jar = cookieJar.getOrPut(host) { ConcurrentHashMap() }
            for ((k, v) in hostCookies) if (v.isNotBlank()) jar[k] = v
            Log.d(TAG, "harvested ${hostCookies.size} CloudflareKiller cookies for $host")
        } catch (e: Exception) {
            Log.d(TAG, "cookie harvest failed: ${e.message}")
        }
    }

    // DoH

    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    private val dohBootstrap = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /** Resolve via Cloudflare DNS-over-HTTPS JSON API (bypasses ISP DNS blocks). */
    private fun resolveDnsDoH(hostname: String): List<InetAddress> {
        return try {
            val request = Request.Builder()
                .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                .header("Accept", "application/dns-json")
                .header("User-Agent", USER_AGENT)
                .build()
            dohBootstrap.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val text = response.body?.string() ?: return emptyList()
                val ips = Regex(""""data"\s*:\s*"([0-9.]+)"""")
                    .findAll(text).map { it.groupValues[1] }.distinct().toList()
                if (ips.isEmpty()) emptyList() else ips.map { InetAddress.getByName(it) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "DoH resolve failed for $hostname: ${e.message}")
            emptyList()
        }
    }

    private val dohDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            dnsCache[hostname]?.let { return it }
            val resolved = resolveDnsDoH(hostname)
            if (resolved.isNotEmpty()) {
                dnsCache[hostname] = resolved
                Log.d(TAG, "DoH resolved $hostname -> ${resolved.joinToString { it.hostAddress ?: "?" }}")
                return resolved
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    /** OkHttp client whose DNS goes through DoH (SNI/certs stay correct). */
    private val dohClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .dns(dohDns)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** DoH + CloudflareKiller clients, built per host (correct OkHttp interceptor wiring). */
    private val dohCfClients = ConcurrentHashMap<String, OkHttpClient>()

    private fun dohCfClientFor(url: String): OkHttpClient {
        val host = hostOf(url)
        return dohCfClients.getOrPut(host) {
            dohClient.newBuilder()
                .addInterceptor(killerFor(url))
                .build()
        }
    }

    // pipeline

    class AnistreamException(message: String) : Exception(message)

    private fun fail(url: String, code: Int, body: String): Nothing {
        val snippet = body.replace("\n", " ").take(140)
        throw AnistreamException("Anistream API HTTP $code - ${hostOf(url)}${if (snippet.isBlank()) "" else " ($snippet)"}")
    }

    /**
     * GET through the full resilience pipeline. Returns the response body.
     * Throws AnistreamException with a human-readable reason on hard failure.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): String {
        val h = buildMap {
            put("User-Agent", USER_AGENT)
            put("Accept", "application/json, text/plain, */*")
            referer?.let { put("Referer", it) }
            putAll(headers)
        }
        Log.d(TAG, "GET $url")

        // attempt 1: normal path (fast; works when nothing is blocked)
        var lastCode = -1
        var lastBody = ""
        try {
            val resp = app.get(url, headers = headersWithCookies(url, h))
            storeCookies(url, resp.headers.values("set-cookie"))
            when {
                resp.code == 429 -> Log.d(TAG, "429 on $url - rate limited")
                isCloudflareCode(resp.code) -> Log.d(TAG, "CF code ${resp.code} on $url")
                resp.code >= 400 -> fail(url, resp.code, resp.text)
                else -> {
                    val text = resp.text
                    if (looksLikeChallenge(text)) throw AnistreamException(
                        "Anistream API returned a Cloudflare challenge page - " +
                            "open anistream.one in a browser once and retry."
                    )
                    return text
                }
            }
            lastCode = resp.code
            lastBody = resp.text
        } catch (e: AnistreamException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "normal GET failed for $url: ${e.javaClass.simpleName}: ${e.message}")
            // network-level failure (DNS/timeout/reset) - fall through to DoH path
            return dohPath(url, h)
        }

        // attempt 2: 429 -> wait & retry once
        if (lastCode == 429) {
            delay(2500)
            try {
                val resp = app.get(url, headers = headersWithCookies(url, h))
                storeCookies(url, resp.headers.values("set-cookie"))
                if (resp.code in 200..399) return resp.text
                lastCode = resp.code
                lastBody = resp.text
            } catch (_: Exception) { }
        }

        // attempt 3: Cloudflare challenge -> CloudflareKiller (WebView solver)
        if (isCloudflareCode(lastCode)) {
            return mutexFor(url).withLock {
                Log.d(TAG, "CloudflareKiller retry for $url")
                val killer = killerFor(url)
                val retry = app.get(url, headers = h, interceptor = killer)
                if (isCloudflareCode(retry.code)) {
                    killer.savedCookies.clear()
                    val retry2 = app.get(url, headers = h, interceptor = killer)
                    if (isCloudflareCode(retry2.code)) {
                        harvestKillerCookies(url)
                        throw AnistreamException(
                            "Anistream is protected by Cloudflare and the challenge could not be " +
                                "solved automatically. A WebView should have opened - solve it, then retry. " +
                                "If no WebView appeared, your network may block anistream.one (try a VPN)."
                        )
                    }
                    harvestKillerCookies(url)
                    if (retry2.code >= 400) fail(url, retry2.code, retry2.text)
                    retry2.text
                } else {
                    harvestKillerCookies(url)
                    if (retry.code >= 400) fail(url, retry.code, retry.text)
                    retry.text
                }
            }
        }

        fail(url, lastCode, lastBody)
    }

    /**
     * POST JSON through the full resilience pipeline (GraphQL calls).
     * [payload] must be a serializable Map - nicehttp serializes it for us.
     */
    suspend fun postJson(
        url: String,
        payload: Map<String, Any?>,
        headers: Map<String, String> = emptyMap()
    ): String {
        val h = buildMap {
            put("User-Agent", USER_AGENT)
            put("Accept", "application/json, text/plain, */*")
            put("Content-Type", "application/json")
            put("Origin", AnistreamApi.MAIN_URL)
            put("Referer", "${AnistreamApi.MAIN_URL}/")
            putAll(headers)
        }
        Log.d(TAG, "POST $url")

        var lastCode = -1
        var lastBody = ""
        try {
            val resp = app.post(url, json = payload, headers = headersWithCookies(url, h))
            storeCookies(url, resp.headers.values("set-cookie"))
            when {
                resp.code == 429 -> Log.d(TAG, "429 on $url - rate limited")
                isCloudflareCode(resp.code) -> Log.d(TAG, "CF code ${resp.code} on $url")
                resp.code >= 400 -> fail(url, resp.code, resp.text)
                else -> return resp.text
            }
            lastCode = resp.code
            lastBody = resp.text
        } catch (e: AnistreamException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "normal POST failed for $url: ${e.javaClass.simpleName}: ${e.message}")
            return dohPost(url, payload, h)
        }

        if (lastCode == 429) {
            delay(2500)
            try {
                val resp = app.post(url, json = payload, headers = headersWithCookies(url, h))
                storeCookies(url, resp.headers.values("set-cookie"))
                if (resp.code in 200..399) return resp.text
                lastCode = resp.code
                lastBody = resp.text
            } catch (_: Exception) { }
        }

        if (isCloudflareCode(lastCode)) {
            return mutexFor(url).withLock {
                Log.d(TAG, "CloudflareKiller retry (POST) for $url")
                val killer = killerFor(url)
                val retry = app.post(url, json = payload, headers = h, interceptor = killer)
                if (isCloudflareCode(retry.code)) {
                    killer.savedCookies.clear()
                    val retry2 = app.post(url, json = payload, headers = h, interceptor = killer)
                    if (isCloudflareCode(retry2.code)) {
                        harvestKillerCookies(url)
                        throw AnistreamException(
                            "Anistream GraphQL is Cloudflare-protected and the challenge could not " +
                                "be solved automatically. Solve the WebView once and retry, or try a VPN."
                        )
                    }
                    harvestKillerCookies(url)
                    if (retry2.code >= 400) fail(url, retry2.code, retry2.text)
                    retry2.text
                } else {
                    harvestKillerCookies(url)
                    if (retry.code >= 400) fail(url, retry.code, retry.text)
                    retry.text
                }
            }
        }

        fail(url, lastCode, lastBody)
    }

    // DoH raw fallback

    private suspend fun dohPath(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "DoH fallback path for $url")
            val cookie = cookieHeader(url)
            val builder = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                cookie?.let { header("Cookie", it) }
            }
            dohCfClientFor(url).newCall(builder.build()).execute().use { resp ->
                storeCookies(url, resp.headers("set-cookie"))
                when {
                    resp.code in 200..399 -> resp.body?.string() ?: ""
                    isCloudflareCode(resp.code) -> throw AnistreamException(
                        "Anistream API unreachable behind Cloudflare even via DNS-over-HTTPS. " +
                            "Your ISP may block anistream.one - try a VPN or different network."
                    )
                    else -> fail(url, resp.code, resp.body?.string() ?: "")
                }
            }
        }

    private val jsonMapper = com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())

    private suspend fun dohPost(url: String, payload: Map<String, Any?>, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "DoH fallback path (POST) for $url")
            val cookie = cookieHeader(url)
            val json = jsonMapper.writeValueAsString(payload)
            val body = json.toRequestBody("application/json".toMediaType())
            val builder = Request.Builder().url(url).post(body).apply {
                headers.forEach { (k, v) -> header(k, v) }
                cookie?.let { header("Cookie", it) }
            }
            dohCfClientFor(url).newCall(builder.build()).execute().use { resp ->
                storeCookies(url, resp.headers("set-cookie"))
                when {
                    resp.code in 200..399 -> resp.body?.string() ?: ""
                    isCloudflareCode(resp.code) -> throw AnistreamException(
                        "Anistream GraphQL unreachable behind Cloudflare even via DNS-over-HTTPS. " +
                            "Your ISP may block anistream.one - try a VPN or different network."
                    )
                    else -> fail(url, resp.code, resp.body?.string() ?: "")
                }
            }
        }

    // helpers

    private suspend fun delay(ms: Long) =
        kotlinx.coroutines.delay(ms)

    /** Challenge pages contain these unambiguous markers. */
    private fun looksLikeChallenge(body: String): Boolean {
        if (body.length > 200_000) return false
        return body.contains("cf-challenge") || body.contains("Just a moment...") ||
            body.contains("_cf_chl_opt") || body.contains("cf_chl_prog")
    }
}
