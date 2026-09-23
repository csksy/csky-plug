package com.laddu100.cloudmoviez

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CloudMoviezPlugin : Plugin() {
    override fun load(context: Context) {
        initCMZCFBypass()
        registerMainAPI(CloudMoviezProvider())
        openSettings = { ctx ->
            (ctx as? androidx.appcompat.app.AppCompatActivity)?.let { activity ->
                CloudMoviezSettingsFragment(this).show(activity.supportFragmentManager, "CloudMoviezSettings")
            }
            kotlin.Unit
        }
    }
}
