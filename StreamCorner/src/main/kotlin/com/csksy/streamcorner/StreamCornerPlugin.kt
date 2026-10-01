package com.csksy.streamcorner

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamCornerPlugin : Plugin() {
    override fun load(context: Context) {
        StreamCornerApi.init(context)
        registerMainAPI(StreamCornerProvider())
    }
}
