package com.anidream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniDreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniDream())
    }
}
