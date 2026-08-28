package com.laddu100.anikage

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniKagePlugin : Plugin() {
    override fun load(context: Context) {
        initProxCFBypass(context)
        registerMainAPI(AniKage())
    }
}
