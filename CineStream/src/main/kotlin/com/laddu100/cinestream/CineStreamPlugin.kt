package com.laddu100.cinestream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CineStreamPlugin : Plugin() {
    override fun load(context: Context) {
        initCineStreamCFBypass()
        registerMainAPI(CineStreamProvider())
    }
}
