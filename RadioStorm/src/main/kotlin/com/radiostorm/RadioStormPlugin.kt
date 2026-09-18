package com.radiostorm

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class RadioStormPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(RadioStorm())
    }
}
