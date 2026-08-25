package com.laddu100

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class LIVETVPlugin : Plugin() {

    private var sharedPref = activity?.getSharedPreferences("LIVETV", Context.MODE_PRIVATE)

    private var iptvProviders: List<Map<String, Any>> = emptyList()

    override fun load(context: Context) {
        Log.d("LIVETV", "load: start")
        LIVETV.context = context
        LIVETVLiveEventsProvider.context = context

        // Re-resolve SharedPreferences here in case `activity` was null at
        // construction time (plugin loaded before MainActivity was ready).
        if (sharedPref == null) {
            sharedPref = activity?.getSharedPreferences("LIVETV", Context.MODE_PRIVATE)
        }

        // Always register the default live-events provider so the homepage
        // is never completely empty even if the categories fetch fails.
        registerMainAPI(LIVETVLiveEventsProvider())
        Log.d("LIVETV", "load: registered default LiveEventsProvider")

        // Fetch the dynamic IPTV providers list. If this fails (network,
        // crypto, Firebase) we still have the default LiveEvents provider.
        iptvProviders = runBlocking { LIVETVProviderManager.fetchProviders() }
        Log.d("LIVETV", "load: fetched ${iptvProviders.size} providers")

        val providerSettings = iptvProviders.mapNotNull { p ->
            val title = p["title"] as? String ?: return@mapNotNull null
            title to (sharedPref?.getBoolean(title, false) ?: false)
        }.toMap()

        val enabledCount = providerSettings.count { it.value }
        Log.d("LIVETV", "load: $enabledCount providers enabled in settings")

        iptvProviders
            .filter { p ->
                val title = p["title"] as? String
                title != null && providerSettings[title] == true
            }
            .forEach { p ->
                val title = p["title"] as String
                val catLink = p["catLink"] as String
                val type = p["type"] as? String ?: "custom"
                val displayTitle = "📺 $title"
                Log.d("LIVETV", "load: registering '$displayTitle' type=$type catLink=$catLink")
                if (type == "custom") {
                    registerMainAPI(LIVETVLiveEventsProvider(displayTitle, catLink))
                } else {
                    registerMainAPI(LIVETV(displayTitle, catLink))
                }
            }

        val act = context as AppCompatActivity

        // openSettings is `Function1<Context, Unit>` on the Plugin base class.
        // The lambda receives the Context (unused) — we re-fetch providers
        // on each open so a failed initial fetch (e.g. transient network
        // issue at app start) doesn't leave the settings sheet empty.
        openSettings = { _ ->
            val currentProviders = runBlocking {
                if (iptvProviders.isEmpty()) {
                    LIVETVProviderManager.fetchProviders()
                        .also { iptvProviders = it }
                } else {
                    iptvProviders
                }
            }
            Log.d("LIVETV", "openSettings: showing ${currentProviders.size} playlists")
            LIVETVSettings(
                this,
                sharedPref,
                currentProviders.mapNotNull { it["title"] as? String }
            ).show(act.supportFragmentManager, "LIVETVSettings")
        }
        Log.d("LIVETV", "load: done")
    }
}
