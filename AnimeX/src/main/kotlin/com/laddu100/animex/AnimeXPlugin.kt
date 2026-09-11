package com.laddu100.animex

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeXPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeXProvider())
    }
}
