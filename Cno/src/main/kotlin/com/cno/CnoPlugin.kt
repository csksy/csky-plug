package com.cno

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CnoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CnoProvider())
    }
}
