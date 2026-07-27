package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimoPlugin : Plugin() {
    override fun load(context: Context) {
        Animo.initCF()
        registerMainAPI(Animo())
        openSettings = { ctx ->
            (ctx as? androidx.appcompat.app.AppCompatActivity)?.let { activity ->
                AnimoSettingsFragment(this).show(activity.supportFragmentManager, "AnimoSettings")
            }
            kotlin.Unit
        }
    }
}
