package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MkvBasePlugin : Plugin() {
    override fun load(context: Context) {
        initMkvBaseCFBypass(context)
        registerMainAPI(MkvBaseProvider())
        // setOpenSettings wires the gear icon in the plugin row to our settings sheet.
        // The callback receives the Activity context and shows the BottomSheet dialog.
        // In Kotlin, Plugin's Java setter setOpenSettings(Function1) is accessed as a
        // property assignment: openSettings = { ctx -> ... }.
        openSettings = { ctx ->
            (ctx as? androidx.appcompat.app.AppCompatActivity)?.let { activity ->
                MkvBaseSettingsFragment(this).show(activity.supportFragmentManager, "MkvBaseSettings")
            }
            kotlin.Unit
        }
    }

    companion object {
        private const val KEY_CF_COOKIES = "MKVBASE_CF_COOKIES"
        private const val KEY_CF_UA = "MKVBASE_CF_USER_AGENT"
        private const val KEY_CF_HOST = "MKVBASE_CF_COOKIE_HOST"

        // CF cookies are stored via CloudStreamApp.setKey/getKey so they persist across app
        // restarts and can be cleared from the Settings fragment. Using the app-level
        // datastore (instead of plugin-private SharedPreferences) matches Cinemacity's pattern
        // and lets the Settings fragment read/write the same values without holding a Context.
        var cfCookies: String
            get() = try { CloudStreamApp.getKey<String>(KEY_CF_COOKIES) ?: "" } catch (e: Exception) { "" }
            set(value) { try { CloudStreamApp.setKey(KEY_CF_COOKIES, value) } catch (e: Exception) {} }

        var cfUserAgent: String
            get() = try { CloudStreamApp.getKey<String>(KEY_CF_UA) ?: "" } catch (e: Exception) { "" }
            set(value) { try { CloudStreamApp.setKey(KEY_CF_UA, value) } catch (e: Exception) {} }

        var cfCookieHost: String
            get() = try { CloudStreamApp.getKey<String>(KEY_CF_HOST) ?: "" } catch (e: Exception) { "" }
            set(value) { try { CloudStreamApp.setKey(KEY_CF_HOST, value) } catch (e: Exception) {} }
    }
}



