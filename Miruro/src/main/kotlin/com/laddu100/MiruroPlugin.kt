package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MiruroPlugin : Plugin() {
    override fun load(context: Context) {
        MiruroApi.context = context
        registerMainAPI(MiruroProvider())
        registerExtractorAPI(MiruroMegaPlay())
        registerExtractorAPI(MiruroVidWish())
    }
}
