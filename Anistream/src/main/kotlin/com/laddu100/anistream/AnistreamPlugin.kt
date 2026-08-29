package com.laddu100.anistream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnistreamPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Anistream())
    }
}
