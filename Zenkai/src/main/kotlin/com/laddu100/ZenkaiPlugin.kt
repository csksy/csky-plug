package com.laddu100

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ZenkaiPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ZenkaiProvider())
    }
}
