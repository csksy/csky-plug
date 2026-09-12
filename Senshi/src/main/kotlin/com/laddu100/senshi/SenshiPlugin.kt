package com.laddu100.senshi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SenshiPlugin : Plugin() {
    override fun load() {
        registerMainAPI(SenshiProvider())
    }
}
