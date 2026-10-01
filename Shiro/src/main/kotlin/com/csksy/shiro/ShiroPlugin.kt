package com.csksy.shiro

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ShiroPlugin : Plugin() {
    override fun load(context: Context) {
        ShiroApi.init(context)
        registerMainAPI(Shiro())
    }
}
