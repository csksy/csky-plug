package com.laddu100.animekai

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeKaiPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeKaiProvider())
    }
}
