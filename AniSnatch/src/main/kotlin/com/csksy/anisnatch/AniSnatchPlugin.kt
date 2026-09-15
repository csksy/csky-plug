package com.csksy.anisnatch

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniSnatchPlugin : Plugin() {
    override fun load(context: Context) {
        // the Cloudflare fallback WebView needs an app context
        AniSnatchWeb.init(context)
        registerMainAPI(AniSnatch())
    }
}
