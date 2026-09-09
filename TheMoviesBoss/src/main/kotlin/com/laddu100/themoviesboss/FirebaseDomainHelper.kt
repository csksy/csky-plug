package com.laddu100.themoviesboss

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// the site rotates domains often, the firebase entry lets it be fixed without a plugin update
@JsonIgnoreProperties(ignoreUnknown = true)
object FirebaseDomainHelper {
    private const val TAG = "TheMoviesBoss"
    private const val URL = "https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    @Volatile
    private var domains: Map<String, String> = emptyMap()

    @Volatile
    private var lastLoadTime: Long = 0L

    @Volatile
    private var everLoadedSuccessfully: Boolean = false

    private val loadMutex = Mutex()

    private suspend fun load(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && everLoadedSuccessfully && now - lastLoadTime < CACHE_TTL_MS) return
        if (!force && !everLoadedSuccessfully && now - lastLoadTime < CACHE_TTL_MS) return
        loadMutex.withLock {
            val refreshed = System.currentTimeMillis()
            if (!force && everLoadedSuccessfully && refreshed - lastLoadTime < CACHE_TTL_MS) return
            if (!force && !everLoadedSuccessfully && refreshed - lastLoadTime < CACHE_TTL_MS) return
            try {
                val raw = app.get(URL, timeout = 5000L).text
                if (raw.isBlank() || raw == "null") {
                    lastLoadTime = refreshed
                    return
                }
                val parsed = com.lagradost.cloudstream3.utils.AppUtils.tryParseJson<Map<String, Any?>>(raw)
                if (parsed != null) {
                    val cleaned = parsed.mapNotNull { (key, value) ->
                        val v = (value as? String)?.trim()?.removeSuffix("/")
                        if (v.isNullOrBlank()) null else key to v
                    }.toMap()
                    if (cleaned.isNotEmpty()) {
                        domains = cleaned
                        everLoadedSuccessfully = true
                    }
                }
                lastLoadTime = refreshed
            } catch (e: Exception) {
                // keep the old cache on failure and back off so every request does not retry
                lastLoadTime = System.currentTimeMillis()
                Log.e(TAG, "firebase domains: ${e.message}")
            }
        }
    }

    suspend fun getDomain(key: String): String? {
        load()
        return domains[key]
            ?: domains["${key}_url"]
            ?: domains["${key}_domain"]
    }

    fun invalidate() {
        lastLoadTime = 0L
    }
}
