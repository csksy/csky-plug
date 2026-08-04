package com.laddu100.kyren

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KyrenPlugin : Plugin() {
    override fun load() {
        registerMainAPI(KyrenProvider())
    }
}
