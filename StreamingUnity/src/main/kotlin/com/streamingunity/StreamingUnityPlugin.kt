package com.streamingunity

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamingUnityPlugin : Plugin() {
    override fun load(context: Context) {
        StreamingUnityProvider.context = context
        registerMainAPI(StreamingUnityProvider())
    }
}
