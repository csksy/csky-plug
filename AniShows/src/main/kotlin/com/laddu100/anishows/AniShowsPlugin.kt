package com.laddu100.anishows

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniShowsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniShows())
    }
}
