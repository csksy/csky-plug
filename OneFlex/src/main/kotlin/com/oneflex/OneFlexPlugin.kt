package com.oneflex

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OneFlexPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(OneFlexProvider())
    }
}
