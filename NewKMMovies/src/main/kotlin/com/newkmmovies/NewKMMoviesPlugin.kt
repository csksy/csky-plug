package com.newkmmovies

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NewKMMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        initKMCFBypass(context)
        registerMainAPI(NewKMMoviesProvider())
        registerExtractorAPI(SkydropExtractor())
        registerExtractorAPI(MagicLinksExtractor())
        registerExtractorAPI(GDTOTExtractor())
        registerExtractorAPI(EpisodesMagicLinksExtractor())
        openSettings = { ctx ->
            (ctx as? androidx.appcompat.app.AppCompatActivity)?.let { activity ->
                KMMoviesSettingsFragment(this).show(activity.supportFragmentManager, "KMMoviesSettings")
            }
            kotlin.Unit
        }
    }

    companion object {
        private const val KEY_CF_COOKIES = "NKM_CF_COOKIES"
        private const val KEY_CF_UA = "NKM_CF_USER_AGENT"
        private const val KEY_CF_HOST = "NKM_CF_COOKIE_HOST"

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
