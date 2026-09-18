package com.cinequiz

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.app

// The repo publishes the shared worker address in endpoint.json, so the owner
// deploys cs-social-hub once and every user of the repo shares it without
// configuring anything. A personal URL saved in the plugin settings always
// wins over the shared one, which keeps self hosting possible.
object WorkerEndpoint {
    private const val SOURCE =
        "https://raw.githubusercontent.com/csksy/csky-plug/main/social-worker/endpoint.json"
    private const val CACHE_MS = 10 * 60 * 1000L

    private val mapper = ObjectMapper()

    @Volatile private var sharedUrl: String = ""
    @Volatile private var fetchedAt: Long = 0L

    suspend fun resolve(stored: String): String {
        val own = stored.trim().trimEnd('/')
        if (own.startsWith("http")) return own

        if (sharedUrl.isBlank() || System.currentTimeMillis() - fetchedAt > CACHE_MS) {
            runCatching {
                val url = mapper.readTree(app.get(SOURCE).text)
                    .get("url")?.asText("").orEmpty().trim().trimEnd('/')
                if (url.startsWith("http")) {
                    sharedUrl = url
                    fetchedAt = System.currentTimeMillis()
                }
            }
        }
        return sharedUrl
    }
}
