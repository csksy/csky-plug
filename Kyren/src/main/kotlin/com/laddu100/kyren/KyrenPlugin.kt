package com.laddu100.kyren

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KyrenPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KyrenProvider())
    }
}
