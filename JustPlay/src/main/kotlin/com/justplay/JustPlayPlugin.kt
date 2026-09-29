package com.justplay

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JustPlayPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JustPlay())
        registerExtractorAPI(PlayHubCloud())
        registerExtractorAPI(PlayVCloud())
        registerExtractorAPI(PlayFastDl())
        registerExtractorAPI(PlayHubCdn())
        registerExtractorAPI(PlayHblinks())
        registerExtractorAPI(PlayHubdrive())
        registerExtractorAPI(PlayHdStream4u())
        registerExtractorAPI(PlayGofile())
        openSettings = { ctx ->
            val activity = ctx as? androidx.appcompat.app.AppCompatActivity
            if (activity != null) {
                try {
                    JustPlaySettingsFragment(this).show(activity.supportFragmentManager, "JustPlaySettings")
                } catch (e: Exception) {
                }
            }
        }
    }
}
