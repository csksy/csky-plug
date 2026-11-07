package com.csksy.animex

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeXPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeXProvider())
    }
}
