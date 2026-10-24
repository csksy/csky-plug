package com.vidcloud

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class VidCloudPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(VidCloudProvider())
    }
}
