package com.laddu100.anikage

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniKagePlugin : Plugin() {
    override fun load() {
        registerMainAPI(AniKage())
    }
}
