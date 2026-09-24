package com.cskyplay

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CskyPlayPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CskyPlay())
        registerExtractorAPI(CskyHubCloud())
        registerExtractorAPI(CskyVCloud())
        registerExtractorAPI(CskyFastDl())
        registerExtractorAPI(CskyHblinks())
        registerExtractorAPI(CskyHubdrive())
        registerExtractorAPI(CskyHdStream4u())
        registerExtractorAPI(CskyFileBee())
        registerExtractorAPI(CskyGofile())
        openSettings = { ctx ->
            try {
                val activity = ctx as? androidx.appcompat.app.AppCompatActivity
                if (activity != null) {
                    CskyPlaySettingsFragment(this).show(activity.supportFragmentManager, "CskyPlaySettings")
                }
            } catch (e: Exception) {
            }
            kotlin.Unit
        }
    }
}
