package com.vidcloud

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VidCloudPlugin : Plugin() {
    override fun load(context: Context) {
        VidCloudWebView.init(context)
        registerMainAPI(VidCloudProvider())
    }
}
