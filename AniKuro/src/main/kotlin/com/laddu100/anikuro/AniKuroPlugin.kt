package com.laddu100.anikuro

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniKuroPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AniKuro())
    }
}
